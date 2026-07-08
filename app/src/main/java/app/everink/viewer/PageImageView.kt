package app.everink.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
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

    /** 저장 전 미리보기용 필기 획(PDF 포인트 좌표). */
    var inkStrokes: List<List<PointF>> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    private val paint = Paint().apply {
        color = Color.argb(90, 255, 190, 0)
        style = Paint.Style.FILL
    }

    private val inkPaint = Paint().apply {
        color = Color.rgb(31, 74, 217)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val inkPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pageWidthPts <= 0f || width == 0) return
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
        if (inkStrokes.isNotEmpty()) {
            inkPaint.strokeWidth = 2.5f * s
            for (stroke in inkStrokes) {
                if (stroke.isEmpty()) continue
                inkPath.reset()
                inkPath.moveTo((stroke[0].x - pageX0) * s, (stroke[0].y - pageY0) * s)
                for (i in 1 until stroke.size) {
                    inkPath.lineTo((stroke[i].x - pageX0) * s, (stroke[i].y - pageY0) * s)
                }
                if (stroke.size == 1) {
                    inkPath.lineTo((stroke[0].x - pageX0) * s + 1f, (stroke[0].y - pageY0) * s)
                }
                canvas.drawPath(inkPath, inkPaint)
            }
        }
    }
}
