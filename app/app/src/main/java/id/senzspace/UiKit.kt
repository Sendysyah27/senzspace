package id.senzspace

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable

/** Util UI bersama (v0.1.32): latar membulat + titik warna.
 *  Dipakai dialog-dialog desain + menu Space. */
object UiKit {
    fun dp(ctx: Context, v: Int): Int =
        (v * ctx.resources.displayMetrics.density).toInt()

    fun roundedBg(color: String, radiusDp: Int, ctx: Context): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(ctx, radiusDp).toFloat()
        }

    fun dotBg(color: String): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor(color))
        }
}
