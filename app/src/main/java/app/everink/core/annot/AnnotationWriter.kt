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

    private inline fun <T> withPdf(path: String, block: (PDFDocument) -> T): T {
        val pdf = Document.openDocument(path) as PDFDocument
        try {
            return block(pdf)
        } finally {
            pdf.destroy()
        }
    }
}
