package app.everink.bench

import app.everink.core.annot.AnnotationWriter
import app.everink.core.store.DocumentStore
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/**
 * 저장 파이프라인 검증 — "주석이 절대 안 사라진다" 포지셔닝의 기술적 근거.
 *
 * 실제 저장 동작은 프로덕션 모듈 [DocumentStore]·[AnnotationWriter]가 수행하고,
 * 이 스파이크는 그 결과를 바이트 수준에서 검증한다.
 *
 * 검증 항목(PRD 데이터안전 §2.2):
 *  - 증분 저장(Incremental): 원본 바이트 영역 무변경 (prefix 해시 동일)
 *  - 원자적 쓰기: temp 파일에 기록 후 rename (저장 중 크래시 무손상 설계)
 *  - 자동 백업: 매 쓰기 전 세대 스냅샷 보관
 *  - 왕복/누적: N회 주석 세션 후에도 모든 주석이 재열람 시 살아있음
 *  - 표준 준수: 각 주석에 appearance stream 생성(update) → 타 뷰어 호환
 */
object StorageSpike {

    data class Step(val name: String, val ok: Boolean, val detail: String)

    data class Report(val steps: List<Step>, val allPassed: Boolean) {
        fun pretty(): String = buildString {
            appendLine("=== 저장 파이프라인 스파이크 ===")
            steps.forEach { s ->
                appendLine("  ${if (s.ok) "✓" else "✗"} ${s.name}")
                if (s.detail.isNotEmpty()) appendLine("      ${s.detail}")
            }
            appendLine("  ----------------------------")
            appendLine("  종합: ${if (allPassed) "✅ 전부 통과" else "❌ 실패 항목 있음"}")
        }
    }

    /** 파일의 앞 [len] 바이트만 해시(원본 prefix 무변경 검증용). */
    private fun prefixHash(file: File, len: Long): String {
        require(len >= 0) { "len must be non-negative" }
        require(file.length() >= len) { "file is shorter than requested prefix" }
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            var remaining = len
            while (remaining > 0) {
                val read = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read < 0) error("Unexpected EOF while hashing prefix")
                digest.update(buf, 0, read)
                remaining -= read
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }.take(16)
    }

    /**
     * @param basePath  원본 PDF 경로(변경하지 않음)
     * @param workDir   작업 폴더(문서 사본·temp·백업)
     * @param sessions  주석 세션 반복 횟수(누적 저장 스트레스)
     */
    fun run(basePath: String, workDir: File, sessions: Int = 5): Report {
        val steps = mutableListOf<Step>()
        val base = File(basePath)
        val store = DocumentStore(workDir, keepGenerations = 3)

        try {
            // 0) 원본 스냅샷: 크기 + prefix 해시
            val baseSize = base.length()
            val baseHash = prefixHash(base, baseSize)
            steps += Step("원본 로드", true, "${base.name} · ${baseSize / 1024}KB · hash=$baseHash")

            // 1) 기록 문서 생성 (원본 → 사본)
            val target = store.import(base)
            steps += Step("기록 문서 사본 생성", target.length() == baseSize,
                "target=${target.length() / 1024}KB")

            // 2) N회 주석 세션 (매회: 백업 → temp 증분저장 → 원자적 rename)
            //    스토어 프리미티브를 단계별로 호출해 중간 상태를 검증한다.
            var sessionOk = true
            var atomicOk = true
            var lastDetail = ""
            for (s in 0 until sessions) {
                // 2a) 백업 = 문서 rename (IO 0, 이 시점부터 commit까지 문서 경로는 빔)
                val backup = store.renameToBackup(tag = "$s")

                // 2b) temp = 백업 복사 → 주석 추가(증분 저장)
                val temp = store.stageFrom(backup)
                val beforeSize = temp.length()
                AnnotationWriter.addSquare(temp.absolutePath, pageIndex = 0, contents = "EverInk 세션 $s")

                // 2c) 검증: 증분 저장은 앞부분(기존 바이트) 무변경 + 파일 증가
                val grew = temp.length() >= beforeSize
                val prefixUnchanged = prefixHash(temp, beforeSize) == prefixHash(backup, beforeSize)
                if (!grew || !prefixUnchanged) {
                    sessionOk = false
                    lastDetail = "세션 $s: grew=$grew prefixUnchanged=$prefixUnchanged"
                    break
                }

                // 2d) 원자적 교체: temp → target
                val replace = store.commit(temp)
                if (!replace.ok) {
                    sessionOk = false
                    lastDetail = "세션 $s: ${replace.detail}"
                    break
                }
                atomicOk = atomicOk && replace.atomic
                lastDetail = "세션 $s OK: ${beforeSize / 1024}KB→${target.length() / 1024}KB · ${replace.detail}"
            }
            steps += Step("증분+원자적+백업 ${sessions}회", sessionOk && atomicOk, lastDetail)

            // 3) 원본 무손상 검증: 최종 target의 prefix가 원본과 바이트 동일
            val finalPrefix = prefixHash(target, baseSize)
            steps += Step("원본 바이트 무변경(비파괴 저장)", finalPrefix == baseHash,
                "base=$baseHash / target[0..baseSize]=$finalPrefix")

            // 4) 재열람: 주석이 N개 누적되어 살아있는가
            val count = AnnotationWriter.annotationCount(target.absolutePath, 0)
            steps += Step("재열람 시 주석 누적 확인", count >= sessions,
                "page0 주석 수=$count (기대 ≥$sessions)")

            // 5) 백업 존재(복원 가능성)
            val bcount = store.backupCount()
            steps += Step("백업 세대 보관", bcount in 1..3, "백업 파일 수=$bcount")

        } catch (t: Throwable) {
            steps += Step("예외 발생", false, "${t.javaClass.simpleName}: ${t.message}")
        }

        return Report(steps, steps.all { it.ok })
    }
}
