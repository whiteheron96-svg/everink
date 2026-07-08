package app.everink.core.render

import android.graphics.Bitmap
import android.graphics.RectF
import com.artifex.mupdf.fitz.Cookie
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.Outline
import com.artifex.mupdf.fitz.PDFDocument
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

    /** 손상 문서를 MuPDF가 복구해서 열었는지(내용 불완전 가능 안내용). */
    @Synchronized
    fun wasRepaired(): Boolean = (doc as? PDFDocument)?.wasRepaired() ?: false

    /** 페이지의 세로/가로 비율(레이아웃 자리표시자 높이 계산용). */
    @Synchronized
    fun pageAspectRatio(index: Int): Float {
        val b = pageBounds(index)
        return if (b.width > 0f) b.height / b.width else 1.4142f
    }

    data class PageBounds(val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
        val width: Float get() = x1 - x0
        val height: Float get() = y1 - y0
    }

    /** 페이지 경계(PDF 포인트 단위). 뷰 좌표 → 문서 좌표 변환용. */
    @Synchronized
    fun pageBounds(index: Int): PageBounds {
        val page = doc.loadPage(index)
        try {
            val b = page.bounds
            return PageBounds(b.x0, b.y0, b.x1, b.y1)
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

    /**
     * 페이지에서 [needle]을 검색해 일치 영역들을 PDF 포인트 좌표로 반환한다.
     * 바깥 리스트 = 일치 건, 안쪽 리스트 = 그 건을 덮는 사각형들(여러 줄 걸침 대응).
     */
    @Synchronized
    fun searchPage(index: Int, needle: String): List<List<RectF>> {
        if (needle.isBlank()) return emptyList()
        val page = doc.loadPage(index)
        try {
            val hits = page.search(needle) ?: return emptyList()
            return hits.map { quads ->
                quads.map { q ->
                    RectF(
                        minOf(q.ul_x, q.ll_x, q.ur_x, q.lr_x),
                        minOf(q.ul_y, q.ur_y, q.ll_y, q.lr_y),
                        maxOf(q.ur_x, q.lr_x, q.ul_x, q.ll_x),
                        maxOf(q.ll_y, q.lr_y, q.ul_y, q.ur_y),
                    )
                }
            }
        } finally {
            page.destroy()
        }
    }

    data class OutlineItem(val title: String, val page: Int, val depth: Int)

    /** 문서 목차(북마크)를 깊이 정보와 함께 평탄화해 반환. 없으면 빈 리스트. */
    @Synchronized
    fun outline(): List<OutlineItem> {
        val root = try {
            doc.loadOutline()
        } catch (t: Throwable) {
            null
        } ?: return emptyList()
        val out = mutableListOf<OutlineItem>()
        fun walk(items: Array<Outline>, depth: Int) {
            for (o in items) {
                val page = try {
                    doc.pageNumberFromLocation(doc.resolveLink(o))
                } catch (t: Throwable) {
                    -1
                }
                out += OutlineItem(o.title ?: "(제목 없음)", page, depth)
                o.down?.let { walk(it, depth + 1) }
            }
        }
        walk(root, 0)
        return out
    }

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            doc.destroy()
        }
    }
}
