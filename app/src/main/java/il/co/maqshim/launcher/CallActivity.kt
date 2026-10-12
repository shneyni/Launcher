package il.co.maqshim.launcher

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
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
import android.widget.Toast
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Our own call screen (incoming, dialing and active calls). Keypad only:
 *  OK = mute, right soft key = speaker, green key = hold / resume, left soft key = more options.
 */
@Suppress("DEPRECATION")
class CallActivity : NoTouchActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var statusHolder: FrameLayout
    private lateinit var overlay: FrameLayout
    private lateinit var nameView: TextView
    private lateinit var numberView: TextView
    private lateinit var stateView: TextView
    private lateinit var extraView: TextView
    private lateinit var dtmfView: TextView
    private lateinit var optionsLabel: TextView
    private lateinit var speakerLabel: TextView
    private lateinit var hintView: TextView
    private val typed = StringBuilder()
    private var ending = false
    private var proximity: PowerManager.WakeLock? = null
    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var menuOpen = false
    private var menuIndex = 0
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

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(0xFF080A0C.toInt()) }
        statusHolder = FrameLayout(this)
        statusHolder.addView(StatusBarView(this), FrameLayout.LayoutParams(-1, -1))
        column.addView(statusHolder, LinearLayout.LayoutParams(-1, dp(27)))

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
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundColor(0xFF101315.toInt())
        }
        optionsLabel = text(13, 0xFFF5F5F5.toInt(), true).apply { gravity = Gravity.LEFT or Gravity.CENTER_VERTICAL; this.text = "אפשרויות" }
        hintView = text(10, 0xFFA6A6A6.toInt(), false).apply { gravity = Gravity.CENTER; maxLines = 2 }
        speakerLabel = text(13, 0xFFF5F5F5.toInt(), true).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; this.text = "רמקול" }
        bar.addView(optionsLabel, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(hintView, LinearLayout.LayoutParams(0, -2, 2f))
        bar.addView(speakerLabel, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(bar, LinearLayout.LayoutParams(-1, dp(43)))

        overlay = FrameLayout(this)
        val frame = FrameLayout(this)
        frame.addView(column, FrameLayout.LayoutParams(-1, -1))
        frame.addView(overlay, FrameLayout.LayoutParams(-1, -1))
        setContentView(frame)
        refresh()
    }

    override fun onResume() {
        super.onResume()
        CallManager.listeners.add(listener)
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(CallService.NOTIFICATION_ID) } catch (_: Exception) { }
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

    override fun onDestroy() {
        stopRecording(false)
        super.onDestroy()
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
        val call = CallManager.primary()
        if (call == null) {
            updateProximity(false)
            stopRecording(true)
            menuOpen = false
            overlay.removeAllViews()
            if (!ending) {
                ending = true
                stateView.text = "השיחה הסתיימה"
                // the lock screen (if it was waiting) appears only now, after the call is over
                handler.postDelayed({
                    finish()
                    if (LockState.enabled(this) && LockState.isLocked(this)) LockActivity.show(applicationContext)
                }, 900)
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
        val notes = mutableListOf<String>()
        if (muted()) notes.add("מושתק")
        if (recorder != null) notes.add("● מקליט")
        if (waiting != null) notes.add("שיחה ממתינה: ${CallManager.displayName(this, waiting)}")
        else if (other != null) notes.add("שיחה נוספת: ${CallManager.displayName(this, other)}")
        extraView.text = notes.joinToString("\n")
        dtmfView.text = typed.toString()
        speakerLabel.setTextColor(if (speakerOn()) 0xFFFDC100.toInt() else 0xFFF5F5F5.toInt())
        hintView.text = when (call.state) {
            Call.STATE_RINGING -> "חייג או אישור: מענה\nסיום: דחייה"
            Call.STATE_ACTIVE -> "אישור: השתקה\nחייג: המתנה"
            Call.STATE_HOLDING -> "חייג: חזרה לשיחה\nסיום: ניתוק"
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

    private fun toggleHold() {
        val call = CallManager.primary() ?: return
        try {
            if (call.state == Call.STATE_ACTIVE) call.hold() else if (call.state == Call.STATE_HOLDING) call.unhold()
        } catch (_: Exception) { }
    }

    // ---------------- more options ----------------

    private fun menuItems(): List<Pair<String, () -> Unit>> = listOf(
        (if (recorder != null) "עצור הקלטת שיחה" else "הקלטת שיחה") to { toggleRecording() },
        "מיזוג שיחות (ועידה)" to { mergeCalls() },
        "הוספת שיחה" to { addCall() }
    )

    private fun openMenu() {
        menuOpen = true
        menuIndex = 0
        drawMenu()
    }

    private fun closeMenu() {
        menuOpen = false
        overlay.removeAllViews()
    }

    private fun drawMenu() {
        overlay.removeAllViews()
        if (!menuOpen) return
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xE6000000.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF141719.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(2), 0xFFFDC100.toInt())
            }
        }
        box.addView(text(16, 0xFFFDC100.toInt(), true).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; this.text = "אפשרויות שיחה" }, LinearLayout.LayoutParams(-1, dp(30)))
        menuItems().forEachIndexed { i, entry ->
            val active = i == menuIndex
            box.addView(
                text(15, if (active) 0xFF080A0C.toInt() else 0xFFF5F5F5.toInt(), false).apply {
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(8), 0)
                    this.text = entry.first
                    setBackgroundColor(if (active) 0xFFFDC100.toInt() else 0x00000000)
                },
                LinearLayout.LayoutParams(-1, dp(42)).apply { topMargin = dp(2) }
            )
        }
        scrim.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(14), 0, dp(14), 0) })
        overlay.addView(scrim, FrameLayout.LayoutParams(-1, -1))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** Merge two calls into a conference (Android decides whether the carrier allows it). */
    private fun mergeCalls() {
        val call = CallManager.primary()
        val other = CallManager.calls.firstOrNull { it !== call && it.state != Call.STATE_DISCONNECTED }
        try {
            if (call != null && call.details.can(Call.Details.CAPABILITY_MERGE_CONFERENCE)) call.mergeConference()
            else if (call != null && other != null) call.conference(other)
            else toast("אין שיחה נוספת למיזוג")
        } catch (_: Exception) { toast("לא ניתן למזג שיחות") }
    }

    /** Opens our dialer so a second call can be placed; the first call is put on hold by Android. */
    private fun addCall() {
        try {
            startActivity(Intent(this, LauncherActivity::class.java).setAction(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { toast("לא ניתן לפתוח את החייגן") }
    }

    // ---------------- recording ----------------

    private fun toggleRecording() {
        if (recorder != null) { stopRecording(true); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 61)
            return
        }
        startRecording()
    }

    /**
     * Best effort: Android only lets ordinary apps record their own microphone during a call,
     * so the other side is usually captured only on speaker. Files go to Android/data/<app>/files/Recordings.
     */
    private fun startRecording() {
        val dir = getExternalFilesDir("Recordings") ?: filesDir
        dir.mkdirs()
        val file = File(dir, "call_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".m4a")
        for (source in intArrayOf(MediaRecorder.AudioSource.VOICE_CALL, MediaRecorder.AudioSource.VOICE_COMMUNICATION, MediaRecorder.AudioSource.MIC)) {
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()
            try {
                r.setAudioSource(source)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(64_000)
                r.setOutputFile(file.absolutePath)
                r.prepare()
                r.start()
                recorder = r
                recordFile = file
                toast("ההקלטה התחילה")
                refresh()
                return
            } catch (_: Exception) {
                try { r.release() } catch (_: Exception) { }
            }
        }
        toast("ההקלטה אינה נתמכת במכשיר")
    }

    private fun stopRecording(announce: Boolean) {
        val r = recorder ?: return
        recorder = null
        try { r.stop() } catch (_: Exception) { }
        try { r.release() } catch (_: Exception) { }
        if (announce) toast("ההקלטה נשמרה: ${recordFile?.name.orEmpty()}")
        if (!isFinishing) refresh()
    }

    @Deprecated("Permission result callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 61 && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) startRecording()
    }

    // ---------------- keys ----------------

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val call = CallManager.primary()
        val repeat = event?.repeatCount ?: 0

        if (keyCode == KeyEvent.KEYCODE_ENDCALL) { if (repeat == 0) hangUp(); return true }

        if (menuOpen) {
            val items = menuItems()
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { menuIndex = (menuIndex - 1).coerceAtLeast(0); drawMenu(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { menuIndex = (menuIndex + 1).coerceAtMost(items.lastIndex); drawMenu(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (repeat == 0) {
                        val action = items.getOrNull(menuIndex)?.second
                        closeMenu()
                        action?.invoke()
                    }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_LEFT, KeyEvent.KEYCODE_BACK -> { closeMenu(); return true }
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> return super.onKeyDown(keyCode, event)
            }
            return true
        }

        when (keyCode) {
            KeyEvent.KEYCODE_CALL -> {
                if (repeat == 0 && call != null) {
                    when (call.state) {
                        Call.STATE_RINGING -> { try { call.answer(VideoProfile.STATE_AUDIO_ONLY) } catch (_: Exception) { } }
                        else -> toggleHold()
                    }
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (repeat == 0 && call != null) {
                    if (call.state == Call.STATE_RINGING) { try { call.answer(VideoProfile.STATE_AUDIO_ONLY) } catch (_: Exception) { } }
                    else toggleMute()
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { if (repeat == 0 && call?.state == Call.STATE_RINGING) hangUp(); return true }
            KeyEvent.KEYCODE_SOFT_LEFT -> { if (repeat == 0 && call != null && call.state != Call.STATE_RINGING) openMenu(); return true }
            KeyEvent.KEYCODE_SOFT_RIGHT -> { if (repeat == 0) toggleSpeaker(); return true }
            // Up / down change the call volume
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (call != null && call.state != Call.STATE_RINGING) {
                    try {
                        (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(
                            AudioManager.STREAM_VOICE_CALL,
                            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                            AudioManager.FLAG_SHOW_UI
                        )
                    } catch (_: Exception) { }
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
