package app.everink.core.render

import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 열린 PDF 문서 1개에 대한 렌더 세션.
 *
 * MuPDF Document는 스레드 안전하지 않으므로 렌더 진입점을 세션 단위로
 * 직렬화한다(@Synchronized). 뷰어·벤치마크가 공용으로 사용한다.
 */
class PdfSession private constructor(
    private val doc: Document,
    val path: String,
) : AutoCloseable {

    companion object {
        /** 문서를 연다. 암호 문서는 [needsPassword]로 확인 후 [authenticate]. */
        fun open(path: String): PdfSession =
            PdfSession(Document.openDocument(path), path)
    }

    @Volatile
    private var closed = false

    val pageCount: Int
        @Synchronized get() = doc.countPages()

    @Synchronized
    fun needsPassword(): Boolean = doc.needsPassword()

    /** @return 인증 성공 여부 */
    @Synchronized
    fun authenticate(password: String): Boolean = doc.authenticatePassword(password)

    /** 페이지의 세로/가로 비율(레이아웃 자리표시자 높이 계산용). */
    @Synchronized
    fun pageAspectRatio(index: Int): Float {
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            val w = b.x1 - b.x0
            val h = b.y1 - b.y0
            return if (w > 0f) h / w else 1.4142f
        } finally {
            page.destroy()
        }
    }

    /**
     * 한 페이지를 [targetWidthPx] 가로폭에 맞춰 렌더한 Bitmap을 반환한다.
     * 반환된 비트맵의 recycle 책임은 호출자에게 있다.
     */
    @Synchronized
    fun renderPage(index: Int, targetWidthPx: Int): Bitmap {
        check(!closed) { "PdfSession is closed" }
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            val wPts = b.x1 - b.x0
            val hPts = b.y1 - b.y0
            val scale = if (wPts > 0) targetWidthPx / wPts else 1f
            val w = ceil((wPts * scale).toDouble()).toInt().coerceAtLeast(1)
            val h = ceil((hPts * scale).toDouble()).toInt().coerceAtLeast(1)
            val ctm = Matrix(scale, scale)
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val dev = AndroidDrawDevice(bmp, (b.x0 * scale).roundToInt(), (b.y0 * scale).roundToInt())
            try {
                page.run(dev, ctm, Cookie())
                dev.close()
            } catch (t: Throwable) {
                bmp.recycle()
                throw t
            } finally {
                dev.destroy()
            }
            return bmp
        } finally {
            page.destroy()
        }
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            doc.destroy()
        }
    }
}
