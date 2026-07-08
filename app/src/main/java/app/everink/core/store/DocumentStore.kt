package app.everink.core.store

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 기록 문서(document of record) 저장 파이프라인.
 *
 * "주석이 절대 안 사라진다" 원칙의 프로덕션 구현부. 편집 한 번의 흐름:
 *
 *  1. 백업 = 문서를 backups/로 rename (IO 0, 원자적)
 *  2. temp = 백업을 복사 (편집당 전체 복사는 이 1회뿐)
 *  3. temp에 증분 저장으로 편집
 *  4. temp를 문서 경로로 원자적 rename
 *
 * 어느 시점에 크래시가 나도 수정 전 상태가 백업 또는 문서 경로에 온전히
 * 존재한다. 문서 경로가 비어 있는 창(1~4 사이)은 다음 열기 때
 * [recoverIfNeeded]가 최신 백업을 승격시켜 메운다.
 *
 * 참고: 백업을 하드링크로 만드는 방안은 Android SELinux가 앱 데이터
 * 디렉토리의 link 생성을 차단해 실기기(Galaxy S25)에서 동작하지 않았다.
 */
class DocumentStore(
    private val workDir: File,
    private val keepGenerations: Int = 3,
) {

    /** 기록 문서 파일. [import] 후에만 존재한다. */
    val document: File = File(workDir, "doc_of_record.pdf")

    val backupsDir: File = File(workDir, "backups")

    private val temp: File = File(workDir, "doc.tmp")

    init {
        workDir.mkdirs()
        backupsDir.mkdirs()
        recoverIfNeeded()
    }

    /**
     * 편집 도중 크래시로 문서 경로가 비어 있으면 최신 백업을 승격한다.
     * 백업조차 없으면(새 스토어) 아무것도 하지 않는다.
     */
    fun recoverIfNeeded() {
        if (document.exists()) return
        val newest = backupsDir.listFiles()?.maxByOrNull { it.lastModified() } ?: return
        Files.move(newest.toPath(), document.toPath(), StandardCopyOption.ATOMIC_MOVE)
        temp.delete()   // 중단된 편집의 잔재 정리
    }

    /** 원본 PDF를 기록 문서로 복사한다(원본은 변경하지 않음). */
    fun import(source: File): File {
        if (document.exists()) document.delete()
        copy(source, document)
        return document
    }

    /**
     * 백업 = 문서를 backups/로 원자적 rename. IO가 없어 파일 크기와 무관하게
     * 즉시 끝난다. 이 호출 후 [commit] 또는 [restore]까지 문서 경로는 비어 있다.
     */
    fun renameToBackup(tag: String = ""): File {
        check(document.exists()) { "document of record does not exist" }
        val backup = File(backupsDir, "gen_${System.nanoTime()}${if (tag.isEmpty()) "" else "_$tag"}.pdf")
        Files.move(document.toPath(), backup.toPath(), StandardCopyOption.ATOMIC_MOVE)
        trimBackups()   // 가장 오래된 세대만 정리(방금 만든 최신 세대는 안전)
        return backup
    }

    /** [source](보통 방금 만든 백업)를 편집용 temp로 복사한다. */
    fun stageFrom(source: File): File {
        if (temp.exists()) temp.delete()
        copy(source, temp)
        return temp
    }

    /** temp를 기록 문서로 원자적 교체한다. */
    fun commit(staged: File): CommitResult = try {
        Files.move(
            staged.toPath(),
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

    /** 편집 실패 시 백업을 문서 경로로 되돌린다(편집 전과 완전히 동일한 상태). */
    fun restore(backup: File) {
        temp.delete()
        if (!document.exists() && backup.exists()) {
            Files.move(backup.toPath(), document.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * 편의 진입점: rename 백업 → temp 복사 → [edit](temp 경로에 증분 저장) →
     * 원자적 교체 → 오래된 백업 정리. [edit]가 실패하면 문서는 편집 전
     * 상태로 복원된다.
     */
    fun saveEdit(edit: (tempPath: String) -> Unit): CommitResult {
        recoverIfNeeded()
        val backup = renameToBackup()
        val staged = try {
            val t = stageFrom(backup)
            edit(t.absolutePath)
            t
        } catch (t: Throwable) {
            restore(backup)
            return CommitResult(ok = false, atomic = false,
                detail = "편집 실패: ${t.javaClass.simpleName}: ${t.message}")
        }
        val result = commit(staged)
        if (!result.ok) restore(backup)
        return result
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
