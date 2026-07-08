package app.everink.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat

/**
 * "잉크 & 종이" 디자인 토큰 헬퍼.
 * 색은 res/values(-night)/colors.xml에서 읽어 다크모드를 자동 반영한다.
 * 프로그래매틱 UI에서 모양(필·카드)과 간격을 일관되게 쓰기 위한 유틸.
 */
object InkUi {

    fun color(ctx: Context, id: Int): Int = ContextCompat.getColor(ctx, id)

    fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** 채운 필(pill)/라운드 사각 배경. */
    fun pill(fillColor: Int, radiusDp: Float, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, radiusDp).toFloat()
            setColor(fillColor)
        }

    /** 표면 카드: 표면색 + 옅은 외곽선 + 12dp 라운드. */
    fun card(ctx: Context, fillColor: Int, strokeColor: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(ctx, 12f).toFloat()
            setColor(fillColor)
            setStroke(dp(ctx, 1f), strokeColor)
        }
}
