package il.co.maqshim.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Global key handling (needs to be switched on in Android accessibility settings):
 *  - A long press on "#" anywhere cycles sound -> vibrate -> silent.
 *  - A long press on the power key opens our own power menu.
 */
class KeyAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var instance: KeyAccessibilityService? = null
        /** Our own screens (dialer, call screen) register here to receive a short press of "#". */
        @Volatile var onPoundShort: (() -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private var suppressPowerUntil = 0L
    private var powerHandled = false
    private var poundConsumed = false
    private var poundLong = false
    private val poundLongPress = Runnable { poundLong = true; cycleRinger() }
    private var feedbackView: View? = null
    private val hideFeedback = Runnable { removeFeedback() }
    private var screenReceiverRegistered = false
    // Accessibility services may start activities from the background, so the lock screen
    // appears even without the "display over other apps" permission.
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!LockState.enabled(context)) return
            when (intent.action) {
                // Only remember the lock; the lock screen appears when the screen turns on, so it never keeps the display lit.
                Intent.ACTION_SCREEN_OFF -> LockState.setLocked(context, true)
                Intent.ACTION_SCREEN_ON -> if (LockState.isLocked(context)) LockActivity.show(context)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (!screenReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, screenReceiver,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            screenReceiverRegistered = true
        }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        removeFeedback()
        if (screenReceiverRegistered) { runCatching { unregisterReceiver(screenReceiver) }; screenReceiverRegistered = false }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {}

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // While our lock screen is up, hand the left soft key straight to it.
        val lock = LockActivity.current
        if (lock != null && LockState.isLocked(this) &&
            (event.keyCode == KeyEvent.KEYCODE_SOFT_LEFT || event.keyCode == KeyEvent.KEYCODE_MENU)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) handler.post { lock.handleKey(event.keyCode) }
            return true
        }
        when (event.keyCode) {
            KeyEvent.KEYCODE_POUND -> {
                // A long press switches sound / vibrate / silent. A short press is just "#":
                // it goes to our dialer / call screen, and typing fields get it untouched.
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount > 0) return poundConsumed
                    poundLong = false
                    poundConsumed = !editableFocused()
                    if (poundConsumed) handler.postDelayed(poundLongPress, 600)
                    return poundConsumed
                }
                val consumed = poundConsumed
                poundConsumed = false
                if (consumed) {
                    handler.removeCallbacks(poundLongPress)
                    if (!poundLong) onPoundShort?.invoke()
                }
                return consumed
            }
            KeyEvent.KEYCODE_POWER -> {
                if (event.action == KeyEvent.ACTION_DOWN && event.isLongPress && !powerHandled) {
                    powerHandled = true
                    openPowerMenu()
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP) powerHandled = false
            }
        }
        return false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (SystemClock.uptimeMillis() < suppressPowerUntil) return
        val pkg = event.packageName?.toString().orEmpty()
        val cls = event.className?.toString().orEmpty()
        val systemWindow = pkg == "android" || pkg == "com.android.systemui"
        if (systemWindow && (cls.contains("GlobalActions", true) || cls.contains("PowerDialog", true) ||
                    cls.contains("PowerMenu", true) || cls.contains("ShutdownActivity", true))) {
            suppressPowerUntil = SystemClock.uptimeMillis() + 1500
            performGlobalAction(GLOBAL_ACTION_BACK)
            openPowerMenu()
        }
    }

    /** Used by the power menu's shutdown / restart buttons: an app cannot power off by itself. */
    fun showSystemPowerDialog() {
        suppressPowerUntil = SystemClock.uptimeMillis() + 4000
        performGlobalAction(GLOBAL_ACTION_POWER_DIALOG)
    }

    private fun openPowerMenu() {
        val intent = Intent(this, PowerMenuActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        try { startActivity(intent) } catch (_: Exception) {}
    }

    private fun editableFocused(): Boolean = try {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        node != null && (node.isEditable || node.className?.toString()?.contains("EditText") == true)
    } catch (_: Exception) {
        false
    }

    private fun cycleRinger() {
        val target = RingerController.next(this)
        if (!RingerController.set(this, target)) {
            Toast.makeText(this, "צריך לאשר גישה למצב שקט", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
        }
        showFeedback(RingerController.current(this))
    }

    private fun showFeedback(mode: RingerController.Mode) {
        removeFeedback()
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val ctx = this
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (10 * d).toInt(), (16 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { setColor(0xEE262626.toInt()); cornerRadius = 18 * d }
            addView(GlyphView(ctx, RingerController.glyph(mode)), LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()))
            addView(TextView(ctx).apply {
                text = RingerController.label(mode)
                setTextColor(Color.WHITE)
                textSize = 18f
                setPadding((10 * d).toInt(), 0, 0, 0)
            }, LinearLayout.LayoutParams(-2, -2))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.CENTER; windowAnimations = 0 }
        try {
            wm.addView(box, params)
            feedbackView = box
            handler.postDelayed(hideFeedback, 1200)
        } catch (_: Exception) {}
    }

    private fun removeFeedback() {
        handler.removeCallbacks(hideFeedback)
        feedbackView?.let { v ->
            try { (getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
        }
        feedbackView = null
    }
}
