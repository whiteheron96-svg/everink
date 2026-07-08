package app.everink.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.widget.ImageView

/**
 * 페이지 비트맵 위에 검색 하이라이트를 겹쳐 그리는 ImageView.
 * 하이라이트 좌표는 PDF 포인트 단위로 받고, 뷰 폭 기준으로 변환해 그린다.
 */
@SuppressLint("AppCompatCustomView")
class PageImageView(context: Context) : ImageView(context) {

    /** 페이지 경계(PDF 포인트). 좌표 변환용. */
    var pageX0 = 0f
    var pageY0 = 0f
    var pageWidthPts = 0f

    /** 하이라이트 영역(PDF 포인트 좌표). */
    var highlights: List<RectF> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint().apply {
        color = Color.argb(90, 255, 190, 0)
        style = Paint.Style.FILL
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (highlights.isEmpty() || pageWidthPts <= 0f || width == 0) return
        val s = width / pageWidthPts
        for (r in highlights) {
            canvas.drawRect(
                (r.left - pageX0) * s,
                (r.top - pageY0) * s,
                (r.right - pageX0) * s,
                (r.bottom - pageY0) * s,
                paint,
            )
        }
    }
}
