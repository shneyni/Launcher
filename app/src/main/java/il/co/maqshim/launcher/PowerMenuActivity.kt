package il.co.maqshim.launcher

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * Power menu opened by a long press on the power key.
 * Icons are white when "on" / active and translucent gray when "off".
 */
class PowerMenuActivity : NoTouchActivity() {
    private enum class Action { LOCK, OFF, RESTART, BLUETOOTH, WIFI, SOUND, VIBRATE, SILENT }
    private class Tile(val action: Action, val glyph: GlyphView, val box: FrameLayout)

    private val rows = mutableListOf<List<Tile>>()
    private var row = 0
    private var col = 0
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = 0xFF262626.toInt()
        window.navigationBarColor = 0xFF262626.toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(0xFF262626.toInt())
        }
        val layout = listOf(
            listOf(Action.LOCK to "lock", Action.OFF to "power", Action.RESTART to "restart"),
            listOf(Action.BLUETOOTH to "bluetooth", Action.WIFI to "wifi"),
            listOf(Action.SOUND to "sound", Action.VIBRATE to "vibrate", Action.SILENT to "silent")
        )
        for (definition in layout) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
            val tiles = mutableListOf<Tile>()
            for ((action, kind) in definition) {
                val glyph = GlyphView(this, kind)
                val box = FrameLayout(this)
                box.addView(glyph, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
                line.addView(box, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) })
                tiles.add(Tile(action, glyph, box))
            }
            rows.add(tiles)
            content.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        setContentView(content)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    override fun onPause() {
        runCatching { unregisterReceiver(stateReceiver) }
        super.onPause()
    }

    private fun isLit(action: Action): Boolean = when (action) {
        Action.LOCK, Action.OFF, Action.RESTART -> true
        Action.BLUETOOTH -> runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)
        Action.WIFI -> runCatching {
            (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled
        }.getOrDefault(false)
        Action.SOUND -> RingerController.current(this) == RingerController.Mode.SOUND
        Action.VIBRATE -> RingerController.current(this) == RingerController.Mode.VIBRATE
        Action.SILENT -> RingerController.current(this) == RingerController.Mode.SILENT
    }

    private fun refresh() {
        rows.forEachIndexed { r, tiles ->
            tiles.forEachIndexed { c, tile ->
                tile.glyph.update(isLit(tile.action))
                tile.box.background = GradientDrawable().apply {
                    setColor(0xFF404040.toInt())
                    cornerRadius = dp(16).toFloat()
                    if (r == row && c == col) setStroke(dp(3), 0xFFFDC100.toInt())
                }
            }
        }
    }

    private fun activate() {
        when (rows[row][col].action) {
            Action.LOCK -> {
                LockState.setLocked(this, true)
                LockActivity.show(this)
                finish()
            }
            Action.OFF, Action.RESTART -> systemPowerDialog()
            Action.BLUETOOTH -> toggleBluetooth()
            Action.WIFI -> toggleWifi()
            Action.SOUND -> setRinger(RingerController.Mode.SOUND)
            Action.VIBRATE -> setRinger(RingerController.Mode.VIBRATE)
            Action.SILENT -> setRinger(RingerController.Mode.SILENT)
        }
    }

    private fun systemPowerDialog() {
        val service = KeyAccessibilityService.instance
        if (service == null) {
            toast("יש להפעיל את שירות המקשים בהגדרות הנגישות")
        } else {
            finish()
            service.showSystemPowerDialog()
        }
    }

    private fun setRinger(mode: RingerController.Mode) {
        if (!RingerController.set(this, mode)) {
            toast("צריך לאשר גישה למצב שקט")
            openSettings(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
        }
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun toggleWifi() {
        val done = Build.VERSION.SDK_INT < 29 && runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wm.setWifiEnabled(!wm.isWifiEnabled)
        }.getOrDefault(false)
        // Android 10+ no longer lets apps switch Wi-Fi directly; open the Wi-Fi settings instead.
        if (!done) openSettings(Settings.ACTION_WIFI_SETTINGS)
        refresh()
    }

    @Suppress("DEPRECATION", "MissingPermission")
    private fun toggleBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val done = adapter != null && runCatching {
            if (adapter.isEnabled) adapter.disable() else adapter.enable()
        }.getOrDefault(false)
        if (!done) openSettings(Settings.ACTION_BLUETOOTH_SETTINGS)
        refresh()
    }

    private fun openSettings(action: String) {
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            finish()
        } catch (_: Exception) {
            toast("ההגדרה אינה זמינה")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            // The screen is RTL, so the first tile of a row is the right-most one.
            KeyEvent.KEYCODE_DPAD_RIGHT -> col = (col - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_LEFT -> col = (col + 1).coerceAtMost(rows[row].lastIndex)
            KeyEvent.KEYCODE_DPAD_UP -> { row = (row - 1).coerceAtLeast(0); col = col.coerceAtMost(rows[row].lastIndex) }
            KeyEvent.KEYCODE_DPAD_DOWN -> { row = (row + 1).coerceAtMost(rows.lastIndex); col = col.coerceAtMost(rows[row].lastIndex) }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_SOFT_RIGHT -> { finish(); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
            else -> return super.onKeyDown(keyCode, event)
        }
        refresh()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (event?.isCanceled != true) activate()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
