package il.co.maqshim.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

private const val CARD_YELLOW = 0xFFFDC100.toInt()
private const val CARD_BLACK = 0xFF000000.toInt()

/** Thin progress line on top of the media card (gray track, red progress). */
class ProgressLine(context: Context) : View(context) {
    var fraction = 0f
        set(value) { if (field != value) { field = value; invalidate() } }
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = 0xFF4A4A4A.toInt()
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.color = 0xFFE50000.toInt()
        canvas.drawRect(0f, 0f, width * fraction, height.toFloat(), paint)
    }
}

/**
 * The yellow notification cards: missed calls, unread messages and the playing song.
 * interactive = true shows a focus frame (home screen); false is display-only (lock screen).
 */
class CardsView(context: Context, private val interactive: Boolean) : LinearLayout(context) {
    enum class Kind { CALL, CALLS, MESSAGES, MEDIA, SETUP }

    var kinds: List<Kind> = emptyList()
        private set
    var focus = -1
        private set
    private var progress: ProgressLine? = null
    private var callText: TextView? = null

    init {
        orientation = VERTICAL
        layoutDirection = LAYOUT_DIRECTION_RTL
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + .5f).toInt()

    fun rebuild(requestedFocus: Int) {
        removeAllViews()
        progress = null
        callText = null
        val list = mutableListOf<Kind>()
        if (interactive && CallManager.primary() != null) list.add(Kind.CALL)
        if (NotificationHub.calls != null) list.add(Kind.CALLS)
        if (NotificationHub.messages != null) list.add(Kind.MESSAGES)
        if (NotificationHub.media != null) list.add(Kind.MEDIA)
        if (interactive && !NotificationHub.accessEnabled) list.add(Kind.SETUP)
        kinds = list
        focus = if (interactive) requestedFocus.coerceIn(-1, list.size - 1) else -1
        list.forEachIndexed { i, kind ->
            val focused = interactive && i == focus
            val card = when (kind) {
                Kind.CALL -> textCard(CallManager.cardText(context).orEmpty(), focused, "handset", 0xFF5FD068.toInt(), true)
                Kind.CALLS -> textCard(NotificationHub.callsText(), focused, "missed")
                Kind.MESSAGES -> textCard(NotificationHub.messagesText(), focused, "message")
                Kind.SETUP -> textCard("אישור: הפעלת גישה להתראות", focused, "message")
                Kind.MEDIA -> NotificationHub.media?.let { mediaCard(it, focused) } ?: View(context)
            }
            addView(card, LayoutParams(-1, dp(46)).apply { topMargin = dp(3) })
        }
        updateProgress()
    }

    fun updateProgress() {
        progress?.fraction = NotificationHub.progress()
        callText?.let { t -> CallManager.cardText(context)?.let { t.text = it } }
    }

    private fun frame(focused: Boolean, color: Int = CARD_YELLOW) = FrameLayout(context).apply {
        background = GradientDrawable().apply {
            setColor(color)
            if (focused) setStroke(dp(3), CARD_BLACK)
        }
    }

    private fun textCard(message: String, focused: Boolean, glyph: String, color: Int = CARD_YELLOW, isCall: Boolean = false): View {
        val card = frame(focused, color)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = LAYOUT_DIRECTION_RTL
            setPadding(dp(14), 0, dp(14), 0)
        }
        row.addView(GlyphView(context, glyph).apply { tint = CARD_BLACK }, LayoutParams(dp(26), dp(26)))
        val messageView = TextView(context).apply {
            text = message
            textSize = 16f
            setTextColor(CARD_BLACK)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
            layoutDirection = LAYOUT_DIRECTION_RTL
            textDirection = TEXT_DIRECTION_FIRST_STRONG
            includeFontPadding = false
            setPadding(dp(8), 0, 0, 0)
        }
        row.addView(messageView, LayoutParams(0, -1, 1f))
        if (isCall) callText = messageView
        card.addView(row, FrameLayout.LayoutParams(-1, -1))
        card.contentDescription = message
        return card
    }

    private fun line(text: String, size: Float, bold: Boolean) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(CARD_BLACK)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_HORIZONTAL
        includeFontPadding = false
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        layoutDirection = LAYOUT_DIRECTION_RTL
        textDirection = TEXT_DIRECTION_FIRST_STRONG
    }

    private fun mediaCard(m: NotificationHub.Media, focused: Boolean): View {
        val card = frame(focused)
        val bar = ProgressLine(context)
        progress = bar
        card.addView(bar, FrameLayout.LayoutParams(-1, dp(3)).apply { setMargins(dp(16), dp(5), dp(16), 0) })

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            layoutDirection = LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(7), dp(6), dp(2))
        }
        val texts = LinearLayout(context).apply { orientation = VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        if (m.artist.isNotBlank()) texts.addView(line(m.artist, 11f, false), LayoutParams(-1, -2))
        if (m.album.isNotBlank()) texts.addView(line(m.album, 11f, false), LayoutParams(-1, -2))
        texts.addView(line(m.title, 15f, true), LayoutParams(-1, -2))
        row.addView(texts, LayoutParams(0, -2, 1f))

        val controls = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER; layoutDirection = LAYOUT_DIRECTION_LTR }
        fun control(kind: String, size: Int) = GlyphView(context, kind).apply { tint = CARD_BLACK }
            .also { controls.addView(it, LayoutParams(dp(size), dp(size)).apply { setMargins(dp(12), 0, dp(12), 0) }) }
        control("prev", 28)
        control(if (m.playing) "pause" else "play", 38)
        control("next", 28)
        row.addView(controls, LayoutParams(0, -1, 1.5f))
        card.addView(row, FrameLayout.LayoutParams(-1, -1))
        card.contentDescription = "${m.title} ${m.artist}"
        return card
    }
}
