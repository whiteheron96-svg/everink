package app.everink.core.annot

import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFDocument
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.Rect

/**
 * 표준 준수 주석 쓰기.
 *
 * 모든 쓰기는:
 *  - appearance stream을 생성(update)해 타 뷰어 호환을 보장하고
 *  - 증분 저장("incremental")으로 기존 바이트를 보존한다.
 */
object AnnotationWriter {

    /** 페이지에 Square 주석을 추가하고 증분 저장한다. */
    fun addSquare(
        path: String,
        pageIndex: Int,
        contents: String,
        rect: Rect = Rect(50f, 50f + pageIndex, 250f, 120f),
        strokeColor: FloatArray = floatArrayOf(0.95f, 0.75f, 0.1f),
        fillColor: FloatArray = floatArrayOf(1f, 0.98f, 0.6f),
    ) {
        withPdf(path) { pdf ->
            val page = pdf.loadPage(pageIndex) as PDFPage
            val annot = page.createAnnotation(PDFAnnotation.TYPE_SQUARE)
            annot.rect = rect
            annot.setColor(strokeColor)
            annot.setInteriorColor(fillColor)
            annot.contents = contents
            annot.update()                     // appearance stream 생성 = 타 뷰어 호환
            pdf.save(path, "incremental")      // 기존 바이트 유지, 변경분만 append
        }
    }

    /** 재열람 검증용: 페이지의 주석 수. */
    fun annotationCount(path: String, pageIndex: Int): Int =
        withPdf(path) { pdf ->
            val page = pdf.loadPage(pageIndex) as PDFPage
            page.annotations?.size ?: 0
        }

    data class AnnotInfo(
        val index: Int,
        val type: Int,
        val contents: String,
        val x0: Float,
        val y0: Float,
        val x1: Float,
        val y1: Float,
    ) {
        fun contains(x: Float, y: Float, slop: Float = 6f): Boolean =
            x in (x0 - slop)..(x1 + slop) && y in (y0 - slop)..(y1 + slop)
    }

    /** 페이지의 주석 목록(탭 히트테스트·내용 표시용). */
    fun list(path: String, pageIndex: Int): List<AnnotInfo> =
        withPdf(path) { pdf ->
            val page = pdf.loadPage(pageIndex) as PDFPage
            (page.annotations ?: emptyArray()).mapIndexed { i, a ->
                val r = a.rect
                AnnotInfo(i, a.type, a.contents ?: "", r.x0, r.y0, r.x1, r.y1)
            }
        }

    /** 주석 내용을 바꾸고 appearance 갱신 후 증분 저장. */
    fun updateContents(path: String, pageIndex: Int, annotIndex: Int, contents: String) {
        withPdf(path) { pdf ->
            val page = pdf.loadPage(pageIndex) as PDFPage
            val a = page.annotations?.getOrNull(annotIndex)
                ?: error("주석 없음: page=$pageIndex index=$annotIndex")
            a.contents = contents
            a.update()
            pdf.save(path, "incremental")
        }
    }

    /** 주석을 삭제하고 증분 저장(기존 바이트는 보존, 삭제도 비파괴 append). */
    fun delete(path: String, pageIndex: Int, annotIndex: Int) {
        withPdf(path) { pdf ->
            val page = pdf.loadPage(pageIndex) as PDFPage
            val a = page.annotations?.getOrNull(annotIndex)
                ?: error("주석 없음: page=$pageIndex index=$annotIndex")
            page.deleteAnnotation(a)
            pdf.save(path, "incremental")
        }
    }

    private inline fun <T> withPdf(path: String, block: (PDFDocument) -> T): T {
        val pdf = Document.openDocument(path) as PDFDocument
        try {
            return block(pdf)
        } finally {
            pdf.destroy()
        }
    }
}
