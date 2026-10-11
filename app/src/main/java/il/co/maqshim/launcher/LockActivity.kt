package il.co.maqshim.launcher

import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

/**
 * Custom lock screen. Uses the lock-screen (or home) wallpaper configured on the device.
 * Unlock: a single press of the left soft key.
 */
class LockActivity : NoTouchActivity() {
    companion object {
        @Volatile var current: LockActivity? = null

        fun show(ctx: Context) {
            try {
                ctx.startActivity(
                    Intent(ctx, LockActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                )
            } catch (_: Exception) {}
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wallpaperView: ImageView
    private lateinit var timeView: TextView
    private lateinit var dateView: TextView
    private lateinit var hebrewView: TextView
    private lateinit var hintView: TextView
    private lateinit var statusHolder: FrameLayout
    private var cardsView: CardsView? = null
    private val hubListener = Runnable { cardsView?.rebuild(-1) }
    private val progressTick = object : Runnable {
        override fun run() { cardsView?.updateProgress(); handler.postDelayed(this, 1_000L) }
    }
    private val tick = object : Runnable {
        override fun run() { updateTexts(); handler.postDelayed(this, 5_000L) }
    }

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        // Shown above the system lock screen, but it never wakes the screen by itself.
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = 0xFF000000.toInt()
        window.navigationBarColor = 0xFF000000.toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        wallpaperView = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        root.addView(wallpaperView, FrameLayout.LayoutParams(-1, -1))
        root.addView(View(this).apply { setBackgroundColor(0x77000000) }, FrameLayout.LayoutParams(-1, -1))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(34), dp(28), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        timeView = text(55, 0xFFF5F5F5.toInt(), true)
        dateView = text(15, 0xFFF5F5F5.toInt(), false)
        hebrewView = text(15, 0xFFD0D0D0.toInt(), false).apply { setPadding(0, dp(4), 0, 0) }
        content.addView(timeView, LinearLayout.LayoutParams(-1, -2))
        content.addView(View(this).apply { setBackgroundColor(0xFFFDC100.toInt()) },
            LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .72f).toInt(), dp(2)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; topMargin = dp(5); bottomMargin = dp(9)
            })
        content.addView(dateView, LinearLayout.LayoutParams(-1, -2))
        content.addView(hebrewView, LinearLayout.LayoutParams(-1, -2))
        // Notification cards (display only: nothing can be opened from the lock screen).
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val cards = CardsView(this, false)
        cards.rebuild(-1)
        cardsView = cards
        column.addView(cards, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        statusHolder = FrameLayout(this)
        root.addView(statusHolder, FrameLayout.LayoutParams(-1, dp(27), Gravity.TOP))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            setBackgroundColor(0xCC101315.toInt())
        }
        bar.addView(text(13, 0xFFF5F5F5.toInt(), true).apply { this.text = "פתיחה" }, LinearLayout.LayoutParams(0, -2, 1f))
        hintView = text(12, 0xFFA6A6A6.toInt(), false).apply { gravity = Gravity.CENTER; this.text = "" }
        bar.addView(hintView, LinearLayout.LayoutParams(0, -2, 2f))
        bar.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        column.addView(bar, LinearLayout.LayoutParams(-1, dp(43)))

        setContentView(root)
        updateTexts()
    }

    override fun onResume() {
        super.onResume()
        if (!LockState.isLocked(this)) { finish(); return }
        applyWallpaper()
        handler.removeCallbacks(tick)
        handler.post(tick)
        NotificationHub.listeners.add(hubListener)
        cardsView?.rebuild(-1)
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        handler.removeCallbacks(progressTick)
        NotificationHub.listeners.remove(hubListener)
        super.onPause()
    }

    private fun text(size: Int, color: Int, bold: Boolean) = TextView(this).apply {
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER
        includeFontPadding = false
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun updateTexts() {
        statusHolder.removeAllViews()
        statusHolder.addView(createStatusBar(this), FrameLayout.LayoutParams(-1, -1))
        val now = LocalDate.now()
        timeView.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val weekday = SimpleDateFormat("EEEE", Locale("he", "IL")).format(Date())
        dateView.text = "$weekday  ${now.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))}"
        hebrewView.text = now.toHebrewDateString()
    }

    private fun applyWallpaper() {
        val drawable = loadWallpaper()
        if (drawable != null) wallpaperView.setImageDrawable(drawable) else wallpaperView.setImageDrawable(null)
    }

    /** Lock wallpaper first, then the home wallpaper; falls back to plain black if Android refuses access. */
    private fun loadWallpaper(): Drawable? {
        val wm = WallpaperManager.getInstance(this)
        if (Build.VERSION.SDK_INT >= 24) {
            try {
                wm.getWallpaperFile(WallpaperManager.FLAG_LOCK)?.use { pfd ->
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = 2
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    val bmp = BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)
                    if (bmp != null) return BitmapDrawable(resources, bmp)
                }
            } catch (_: Throwable) {}
        }
        try { wm.drawable?.let { return it } } catch (_: Throwable) {}
        try { wm.peekDrawable()?.let { return it } } catch (_: Throwable) {}
        return null
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    private fun isLeftKey(code: Int) = code == KeyEvent.KEYCODE_SOFT_LEFT || code == KeyEvent.KEYCODE_MENU

    // Caught before any view gets the key, so the system keyguard cannot swallow it.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (isLeftKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) unlock()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** Also called by the accessibility service, so the key works even when the system keyguard holds focus. */
    fun handleKey(keyCode: Int): Boolean {
        if (isLeftKey(keyCode)) { unlock(); return true }
        return false
    }

    private fun unlock() {
        if (isFinishing) return
        LockState.setLocked(this, false)
        try {
            (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)
        } catch (_: Exception) {}
        finish()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}
