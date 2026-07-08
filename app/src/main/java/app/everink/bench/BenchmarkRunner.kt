package app.everink.bench

import android.graphics.Bitmap
import android.os.Debug
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * MuPDF 대용량 렌더링 벤치마크의 순수 로직.
 *
 * PRD v0.5 게이트 검증용:
 *  - 200MB 스캔본 첫 페이지 표시 ≤ 2000ms (중급기 기준)
 *  - 2,000페이지 문서 순회 시 OOM 크래시 0
 *
 * 측정 항목: 문서 열기 시간, 첫 페이지 렌더 시간, 페이지당 평균 렌더 시간,
 *           네이티브 힙 피크 사용량.
 */
object BenchmarkRunner {

    data class Result(
        val fileName: String,
        val fileSizeMB: Double,
        val pageCount: Int,
        val openMs: Long,
        val firstPageMs: Long,
        val sampledPages: Int,
        val avgRenderMs: Double,
        val maxRenderMs: Long,
        val nativeHeapPeakMB: Double,
        val targetPx: Int,
        val error: String? = null,
    ) {
        fun pretty(): String = buildString {
            appendLine("■ $fileName")
            if (error != null) {
                appendLine("  ✗ 오류: $error")
                return@buildString
            }
            appendLine("  파일: ${"%.1f".format(fileSizeMB)}MB · ${pageCount}p · 렌더폭 ${targetPx}px")
            appendLine("  열기:        ${openMs}ms")
            val gate = if (firstPageMs <= 2000) "✓ PASS" else "✗ FAIL"
            appendLine("  첫 페이지:   ${firstPageMs}ms   [게이트 ≤2000ms: $gate]")
            appendLine("  페이지 평균: ${"%.1f".format(avgRenderMs)}ms (표본 ${sampledPages}p, 최대 ${maxRenderMs}ms)")
            appendLine("  네이티브 힙 피크: ${"%.1f".format(nativeHeapPeakMB)}MB")
        }
    }

    /**
     * @param path       PDF 절대 경로
     * @param fileSize    파일 바이트 크기(표시용)
     * @param targetPx    렌더 목표 가로 픽셀(실기기 화면폭 근사, 기본 1080)
     * @param maxSamples  순회 렌더 표본 페이지 수 상한(전 페이지가 크면 균등 샘플링)
     * @param onProgress  진행 콜백(현재/전체)
     */
    fun run(
        path: String,
        fileName: String,
        fileSize: Long,
        targetPx: Int = 1080,
        maxSamples: Int = 50,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Result {
        val fileSizeMB = fileSize / (1024.0 * 1024.0)
        var doc: Document? = null
        try {
            // 1) 문서 열기 + 페이지 수
            val tOpen = System.nanoTime()
            doc = Document.openDocument(path)
            if (doc.needsPassword()) {
                return Result(fileName, fileSizeMB, 0, 0, 0, 0, 0.0, 0, 0.0, targetPx,
                    error = "암호 보호 문서 (스파이크 미지원)")
            }
            val pageCount = doc.countPages()
            val openMs = (System.nanoTime() - tOpen) / 1_000_000

            // 2) 첫 페이지 렌더
            val firstPageMs = renderPage(doc, 0, targetPx)

            // 3) 순회 렌더(균등 샘플링) — 2,000p OOM 게이트 겸용
            val step = if (pageCount <= maxSamples) 1 else pageCount / maxSamples
            var sampled = 0
            var sumMs = 0L
            var maxMs = 0L
            var nativePeak = Debug.getNativeHeapAllocatedSize()
            var i = 0
            while (i < pageCount) {
                val ms = renderPage(doc, i, targetPx)
                sumMs += ms
                if (ms > maxMs) maxMs = ms
                sampled++
                val heap = Debug.getNativeHeapAllocatedSize()
                if (heap > nativePeak) nativePeak = heap
                onProgress(i, pageCount)
                i += step
            }

            val avg = if (sampled > 0) sumMs.toDouble() / sampled else 0.0
            return Result(
                fileName = fileName,
                fileSizeMB = fileSizeMB,
                pageCount = pageCount,
                openMs = openMs,
                firstPageMs = firstPageMs,
                sampledPages = sampled,
                avgRenderMs = avg,
                maxRenderMs = maxMs,
                nativeHeapPeakMB = nativePeak / (1024.0 * 1024.0),
                targetPx = targetPx,
            )
        } catch (t: Throwable) {
            return Result(fileName, fileSizeMB, 0, 0, 0, 0, 0.0, 0, 0.0, targetPx,
                error = "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            doc?.destroy()
        }
    }

    /** 한 페이지를 targetPx 가로폭에 맞춰 Bitmap으로 렌더하고 소요 ms 반환. 비트맵은 즉시 회수. */
    private fun renderPage(doc: Document, index: Int, targetPx: Int): Long {
        val t = System.nanoTime()
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            val wPts = (b.x1 - b.x0)
            val hPts = (b.y1 - b.y0)
            val scale = if (wPts > 0) targetPx / wPts else 1f
            val w = ceil((wPts * scale).toDouble()).toInt().coerceAtLeast(1)
            val h = ceil((hPts * scale).toDouble()).toInt().coerceAtLeast(1)
            val ctm = Matrix(scale, scale)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val dev = AndroidDrawDevice(bmp, (b.x0 * scale).roundToInt(), (b.y0 * scale).roundToInt())
            try {
                page.run(dev, ctm, Cookie())
                dev.close()
            } finally {
                dev.destroy()
                bmp.recycle()
            }
        } finally {
            page.destroy()
        }
        return (System.nanoTime() - t) / 1_000_000
    }
}
