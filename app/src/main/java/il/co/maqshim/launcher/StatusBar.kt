package il.co.maqshim.launcher

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Typeface
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Status row (signal, Wi-Fi, Bluetooth, battery, time) that keeps itself up to date:
 * it listens to system broadcasts and also refreshes every few seconds.
 */
class StatusBarView(context: Context) : LinearLayout(context) {
    private val glyphs = mutableListOf<NokiaStatusGlyph>()
    private val time = TextView(context)
    private val handler = Handler(Looper.getMainLooper())
    private var registered = false
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 3_000L) }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    init {
        orientation = HORIZONTAL
        layoutDirection = LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), 0, dp(9), 0)
        setBackgroundColor(0xFF030405.toInt())
        fun glyph(kind: String, w: Int) {
            val g = NokiaStatusGlyph(context, kind).apply { setPadding(dp(1), 0, dp(2), 0) }
            glyphs.add(g)
            addView(g, LayoutParams(dp(w), dp(17)))
        }
        glyph("signal", 19)
        glyph("wifi", 19)
        glyph("bluetooth", 17)
        addView(View(context), LayoutParams(0, 1, 1f))
        glyph("battery", 23)
        time.apply {
            textSize = 12f
            setTextColor(0xFFF5F5F5.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            includeFontPadding = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            setSingleLine(true)
            minWidth = dp(40)
            setPadding(dp(2), 0, 0, 0)
        }
        addView(time, LayoutParams(-2, -2))
        refresh()
    }

    fun refresh() {
        time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        for (g in glyphs) g.invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiManager.RSSI_CHANGED_ACTION)
                addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(ConnectivityManager.CONNECTIVITY_ACTION)
                addAction("android.intent.action.SIM_STATE_CHANGED")
                addAction("android.intent.action.SERVICE_STATE")
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            registered = true
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        if (registered) { try { context.unregisterReceiver(receiver) } catch (_: Exception) { }; registered = false }
        super.onDetachedFromWindow()
    }
}

fun createStatusBar(ctx: Context): View = StatusBarView(ctx)
