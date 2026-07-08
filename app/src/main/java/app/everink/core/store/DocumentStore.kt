package app.everink.core.store

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 기록 문서(document of record) 저장 파이프라인.
 *
 * "주석이 절대 안 사라진다" 원칙의 프로덕션 구현부:
 *  - 매 쓰기 전 세대 백업(최근 [keepGenerations]개 유지)
 *  - temp 사본에서 편집(증분 저장) 후 같은 디렉토리 내 원자적 교체
 *  - 저장 중 크래시가 나도 기존 기록 문서는 항상 온전
 *
 * 스파이크(StorageSpike)와 뷰어가 동일한 프리미티브를 사용한다.
 */
class DocumentStore(
    private val workDir: File,
    private val keepGenerations: Int = 3,
) {

    /** 기록 문서 파일. [import] 후에만 존재한다. */
    val document: File = File(workDir, "doc_of_record.pdf")

    val backupsDir: File = File(workDir, "backups")

    init {
        workDir.mkdirs()
        backupsDir.mkdirs()
    }

    /** 원본 PDF를 기록 문서로 복사한다(원본은 변경하지 않음). */
    fun import(source: File): File {
        if (document.exists()) document.delete()
        copy(source, document)
        return document
    }

    /** 쓰기 전 스냅샷 백업을 만들고 오래된 세대를 정리한다. */
    fun createBackup(tag: String = ""): File {
        check(document.exists()) { "document of record does not exist" }
        val backup = File(backupsDir, "gen_${System.nanoTime()}${if (tag.isEmpty()) "" else "_$tag"}.pdf")
        copy(document, backup)
        trimBackups()
        return backup
    }

    /** 편집용 temp 사본을 만든다. 편집 후 [commit]으로 반영한다. */
    fun stageCopy(): File {
        check(document.exists()) { "document of record does not exist" }
        val temp = File(workDir, "doc.tmp")
        if (temp.exists()) temp.delete()
        copy(document, temp)
        return temp
    }

    /** temp를 기록 문서로 원자적 교체한다. */
    fun commit(temp: File): CommitResult = try {
        Files.move(
            temp.toPath(),
            document.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        CommitResult(ok = true, atomic = true, detail = "atomic move")
    } catch (e: AtomicMoveNotSupportedException) {
        CommitResult(ok = false, atomic = false, detail = "atomic move 미지원: ${e.message}")
    } catch (e: Exception) {
        CommitResult(ok = false, atomic = false, detail = "${e.javaClass.simpleName}: ${e.message}")
    }

    /**
     * 편의 진입점: 백업 → temp 사본 → [edit] (temp 경로에 증분 저장) → 원자적 교체.
     * [edit]가 예외를 던지면 기록 문서는 변경되지 않는다.
     */
    fun saveEdit(edit: (tempPath: String) -> Unit): CommitResult {
        createBackup()
        val temp = stageCopy()
        try {
            edit(temp.absolutePath)
        } catch (t: Throwable) {
            temp.delete()
            return CommitResult(ok = false, atomic = false,
                detail = "편집 실패: ${t.javaClass.simpleName}: ${t.message}")
        }
        return commit(temp)
    }

    fun backupCount(): Int = backupsDir.listFiles()?.size ?: 0

    private fun trimBackups() {
        val files = backupsDir.listFiles()?.sortedBy { it.lastModified() } ?: return
        if (files.size > keepGenerations) {
            files.take(files.size - keepGenerations).forEach { it.delete() }
        }
    }

    private fun copy(src: File, dst: File) {
        src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o) } }
    }

    data class CommitResult(val ok: Boolean, val atomic: Boolean, val detail: String)
}
