package il.co.maqshim.launcher

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Our own call screen (incoming, dialing and active calls). Keypad only. */
@Suppress("DEPRECATION")
class CallActivity : NoTouchActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusHolder: FrameLayout
    private lateinit var nameView: TextView
    private lateinit var numberView: TextView
    private lateinit var stateView: TextView
    private lateinit var extraView: TextView
    private lateinit var dtmfView: TextView
    private lateinit var speakerLabel: TextView
    private lateinit var muteLabel: TextView
    private lateinit var hintView: TextView
    private val typed = StringBuilder()
    private var ending = false
    private var proximity: PowerManager.WakeLock? = null
    private val listener = Runnable { refresh() }
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)       // an incoming call wakes the screen
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        volumeControlStream = AudioManager.STREAM_VOICE_CALL
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = 0xFF000000.toInt()
        window.navigationBarColor = 0xFF000000.toInt()

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF080A0C.toInt()) }
        statusHolder = FrameLayout(this)
        root.addView(statusHolder, LinearLayout.LayoutParams(-1, dp(27)))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(10), dp(20), dp(10))
        }
        nameView = text(28, 0xFFF5F5F5.toInt(), true).apply { maxLines = 2 }
        numberView = text(16, 0xFFA6A6A6.toInt(), false)
        stateView = text(20, 0xFFFDC100.toInt(), true).apply { setPadding(0, dp(14), 0, 0) }
        extraView = text(15, 0xFFF5F5F5.toInt(), false).apply { setPadding(0, dp(10), 0, 0) }
        dtmfView = text(22, 0xFFF5F5F5.toInt(), false).apply { setPadding(0, dp(10), 0, 0); textDirection = View.TEXT_DIRECTION_LTR }
        for (v in listOf(nameView, numberView, stateView, extraView, dtmfView)) content.addView(v, LinearLayout.LayoutParams(-1, -2))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundColor(0xFF101315.toInt())
        }
        speakerLabel = text(13, 0xFFF5F5F5.toInt(), true).apply { gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; this.text = "רמקול" }
        hintView = text(11, 0xFFA6A6A6.toInt(), false).apply { gravity = Gravity.CENTER }
        muteLabel = text(13, 0xFFF5F5F5.toInt(), true).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; this.text = "השתק" }
        bar.addView(speakerLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(hintView, LinearLayout.LayoutParams(0, -2, 2f))
        bar.addView(muteLabel, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(bar, LinearLayout.LayoutParams(-1, dp(43)))
        setContentView(root)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        CallManager.listeners.add(listener)
        KeyAccessibilityService.onPoundShort = { sendDtmf('#') }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        CallManager.listeners.remove(listener)
        KeyAccessibilityService.onPoundShort = null
        updateProximity(false)
        super.onPause()
    }

    private fun text(size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = android.graphics.Typeface.create("sans-serif", if (bold) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun speakerOn() = CallManager.audioState?.route == CallAudioState.ROUTE_SPEAKER
    private fun muted() = CallManager.audioState?.isMuted == true

    private fun refresh() {
        statusHolder.removeAllViews()
        statusHolder.addView(createStatusBar(this), FrameLayout.LayoutParams(-1, -1))
        val call = CallManager.primary()
        if (call == null) {
            updateProximity(false)
            if (!ending) {
                ending = true
                stateView.text = "השיחה הסתיימה"
                handler.postDelayed({ finish() }, 900)
            }
            return
        }
        ending = false
        val name = CallManager.displayName(this, call)
        val number = CallManager.numberOf(call)
        nameView.text = name
        numberView.text = if (number.isNotBlank() && number != name) number else ""
        stateView.text = CallManager.stateText(call)
        val waiting = CallManager.calls.firstOrNull { it !== call && it.state == Call.STATE_RINGING }
        val other = CallManager.calls.firstOrNull { it !== call && it.state != Call.STATE_DISCONNECTED }
        extraView.text = when {
            waiting != null -> "שיחה ממתינה: ${CallManager.displayName(this, waiting)}"
            other != null -> "שיחה נוספת: ${CallManager.displayName(this, other)}"
            else -> ""
        }
        dtmfView.text = typed.toString()
        speakerLabel.setTextColor(if (speakerOn()) 0xFFFDC100.toInt() else 0xFFF5F5F5.toInt())
        muteLabel.setTextColor(if (muted()) 0xFFFDC100.toInt() else 0xFFF5F5F5.toInt())
        hintView.text = when (call.state) {
            Call.STATE_RINGING -> "חייג: מענה · סיום: דחייה"
            Call.STATE_ACTIVE -> "↑ המתנה · סיום: ניתוק"
            Call.STATE_HOLDING -> "↑ חזרה לשיחה · סיום: ניתוק"
            else -> "סיום: ביטול"
        }
        updateProximity((call.state == Call.STATE_ACTIVE || call.state == Call.STATE_DIALING || call.state == Call.STATE_CONNECTING) && !speakerOn())
    }

    /** Screen off against the ear, like a normal phone. */
    private fun updateProximity(want: Boolean) {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (want) {
                if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
                    proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "launcher:call").apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }
            } else {
                proximity?.let { if (it.isHeld) it.release() }
                proximity = null
            }
        } catch (_: Exception) { }
    }

    private fun digitOf(keyCode: Int): Char? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_STAR -> '*'
        KeyEvent.KEYCODE_POUND -> '#'
        else -> null
    }

    private fun sendDtmf(c: Char) {
        val call = CallManager.primary() ?: return
        if (call.state != Call.STATE_ACTIVE) return
        try {
            call.playDtmfTone(c)
            handler.postDelayed({ try { call.stopDtmfTone() } catch (_: Exception) { } }, 160)
        } catch (_: Exception) { }
        typed.append(c)
        refresh()
    }

    private fun hangUp() {
        val call = CallManager.primary() ?: return
        try {
            if (call.state == Call.STATE_RINGING) call.reject(false, null) else call.disconnect()
        } catch (_: Exception) { }
    }

    private fun toggleSpeaker() {
        try {
            CallService.instance?.setAudioRoute(if (speakerOn()) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER)
        } catch (_: Exception) { }
    }

    private fun toggleMute() {
        try { CallService.instance?.setMuted(!muted()) } catch (_: Exception) { }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val call = CallManager.primary()
        val repeat = event?.repeatCount ?: 0
        when (keyCode) {
            KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (repeat == 0 && call?.state == Call.STATE_RINGING) {
                    try { call.answer(VideoProfile.STATE_AUDIO_ONLY) } catch (_: Exception) { }
                }
                return true
            }
            KeyEvent.KEYCODE_ENDCALL -> { if (repeat == 0) hangUp(); return true }
            KeyEvent.KEYCODE_BACK -> { if (repeat == 0 && call?.state == Call.STATE_RINGING) hangUp(); return true }
            KeyEvent.KEYCODE_SOFT_LEFT -> { if (repeat == 0) toggleSpeaker(); return true }
            KeyEvent.KEYCODE_SOFT_RIGHT -> { if (repeat == 0) toggleMute(); return true }
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (repeat == 0 && call != null) {
                    try { if (call.state == Call.STATE_ACTIVE) call.hold() else if (call.state == Call.STATE_HOLDING) call.unhold() } catch (_: Exception) { }
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (call?.state == Call.STATE_RINGING) {
                    try { (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).silenceRinger() } catch (_: Exception) { }
                    return true
                }
                return super.onKeyDown(keyCode, event)
            }
        }
        val digit = digitOf(keyCode)
        if (digit != null) {
            if (repeat == 0 && call?.state == Call.STATE_ACTIVE) {
                try { call.playDtmfTone(digit) } catch (_: Exception) { }
                typed.append(digit)
                refresh()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (digitOf(keyCode) != null) {
            try { CallManager.primary()?.stopDtmfTone() } catch (_: Exception) { }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
