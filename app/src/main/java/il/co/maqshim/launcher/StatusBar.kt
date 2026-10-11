package il.co.maqshim.launcher

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Same status row as the home screen (signal, Wi-Fi, Bluetooth, battery, time) for other screens. */
fun createStatusBar(ctx: Context): View {
    val density = ctx.resources.displayMetrics.density
    fun dp(v: Int) = (v * density + .5f).toInt()
    fun glyph(kind: String): View = NokiaStatusGlyph(ctx, kind).apply { setPadding(dp(1), 0, dp(2), 0) }
    val bar = LinearLayout(ctx)
    bar.orientation = LinearLayout.HORIZONTAL
    bar.layoutDirection = View.LAYOUT_DIRECTION_LTR
    bar.gravity = Gravity.CENTER_VERTICAL
    bar.setPadding(dp(9), 0, dp(9), 0)
    bar.setBackgroundColor(0xFF030405.toInt())
    bar.addView(glyph("signal"), LinearLayout.LayoutParams(dp(19), dp(17)))
    bar.addView(glyph("wifi"), LinearLayout.LayoutParams(dp(19), dp(17)))
    bar.addView(glyph("bluetooth"), LinearLayout.LayoutParams(dp(17), dp(17)))
    bar.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
    bar.addView(glyph("battery"), LinearLayout.LayoutParams(dp(23), dp(17)))
    val time = TextView(ctx)
    time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    time.textSize = 12f
    time.setTextColor(0xFFF5F5F5.toInt())
    time.typeface = Typeface.create("sans-serif", Typeface.BOLD)
    time.includeFontPadding = false
    time.gravity = Gravity.CENTER_VERTICAL
    bar.addView(time, LinearLayout.LayoutParams(dp(46), -2))
    return bar
}
