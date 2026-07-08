package app.everink.viewer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.recyclerview.widget.RecyclerView

/**
 * 핀치/더블탭 줌을 지원하는 세로 스크롤 RecyclerView.
 *
 * 구현 방식:
 *  - 그리기: dispatchDraw에서 canvas translate+scale (기본 배율 1, 최대 [maxScale])
 *  - 입력: 자식에게는 역변환된 좌표를 전달해 기존 탭/길게누르기 로직이
 *    줌 상태와 무관하게 동일한 콘텐츠 좌표를 받게 한다.
 *  - 세로 스크롤은 RecyclerView 본연의 스크롤을 사용하고, 리스트 끝에 닿아
 *    더 스크롤할 수 없을 때만 세로 팬(tranY)이 이어받는다. 가로 팬은 줌 중에만.
 *  - 더블탭: 1배 ↔ 2배 토글(탭 지점 중심).
 *  - 단일탭은 [onSingleTap] 콜백으로 전달(콘텐츠 좌표).
 *
 * 참고: 렌더 해상도는 기본 폭 기준이므로 고배율에서는 다소 흐려질 수 있다.
 * 배율별 재렌더는 후속 작업.
 */
@SuppressLint("ClickableViewAccessibility")
class ZoomableRecyclerView(context: Context) : RecyclerView(context) {

    var maxScale = 4f
    var doubleTapScale = 2f

    /** 단일탭 콜백(콘텐츠 좌표계 = 줌 이전 좌표). */
    var onSingleTap: ((x: Float, y: Float) -> Unit)? = null

    /** 배율이 확정된 시점(핀치 종료·더블탭) 콜백. 고해상 재렌더 트리거용. */
    var onScaleSettled: ((scale: Float) -> Unit)? = null

    private var scaleFactorZ = 1f
    private var tranX = 0f
    private var tranY = 0f

    val currentScale: Float get() = scaleFactorZ

    private val scaleDetector = ScaleGestureDetector(context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // 자식이 DOWN을 받은 상태라면 취소시켜 롱프레스 오발동을 막는다
                cancelChildren()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val newScale = (scaleFactorZ * detector.scaleFactor).coerceIn(1f, maxScale)
                val ratio = newScale / scaleFactorZ
                if (ratio != 1f) {
                    tranX = detector.focusX - (detector.focusX - tranX) * ratio
                    tranY = detector.focusY - (detector.focusY - tranY) * ratio
                    scaleFactorZ = newScale
                    clampPan()
                    invalidate()
                }
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                onScaleSettled?.invoke(scaleFactorZ)
            }
        })

    private val gestureDetector = GestureDetector(context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float,
            ): Boolean {
                if (scaleFactorZ > 1f && !scaleDetector.isInProgress) {
                    tranX -= dx
                    // 세로는 리스트 스크롤이 우선, 끝에 닿으면 팬이 이어받는다
                    val listConsumes = if (dy > 0) canScrollVertically(1) else canScrollVertically(-1)
                    if (!listConsumes) tranY -= dy
                    clampPan()
                    invalidate()
                }
                return false
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (scaleFactorZ > 1.01f) {
                    scaleFactorZ = 1f
                    tranX = 0f
                    tranY = 0f
                } else {
                    val target = doubleTapScale.coerceAtMost(maxScale)
                    tranX = e.x - (e.x - tranX) * (target / scaleFactorZ)
                    tranY = e.y - (e.y - tranY) * (target / scaleFactorZ)
                    scaleFactorZ = target
                    clampPan()
                }
                invalidate()
                onScaleSettled?.invoke(scaleFactorZ)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onSingleTap?.invoke((e.x - tranX) / scaleFactorZ, (e.y - tranY) / scaleFactorZ)
                return false
            }
        })

    /** 새 문서를 열 때 줌·팬 상태를 초기화한다. */
    fun resetZoom() {
        scaleFactorZ = 1f
        tranX = 0f
        tranY = 0f
        invalidate()
    }

    private fun clampPan() {
        val maxTran = (scaleFactorZ - 1f)
        tranX = tranX.coerceIn(-maxTran * width, 0f)
        tranY = tranY.coerceIn(-maxTran * height, 0f)
    }

    private fun cancelChildren() {
        val now = android.os.SystemClock.uptimeMillis()
        val cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0f, 0f, 0)
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    override fun dispatchDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(tranX, tranY)
        canvas.scale(scaleFactorZ, scaleFactorZ)
        super.dispatchDraw(canvas)
        canvas.restore()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        gestureDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) return true

        // 자식·리스트 스크롤에는 콘텐츠 좌표로 역변환해 전달
        val transformed = MotionEvent.obtain(ev)
        transformed.setLocation((ev.x - tranX) / scaleFactorZ, (ev.y - tranY) / scaleFactorZ)
        val handled = super.dispatchTouchEvent(transformed)
        transformed.recycle()
        return handled
    }
}
