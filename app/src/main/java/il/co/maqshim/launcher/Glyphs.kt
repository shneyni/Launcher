package il.co.maqshim.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * Simple line icons for the power menu and the ringer-mode popup.
 * lit = true -> white, lit = false -> translucent gray.
 */
class GlyphView(context: Context, var kind: String, var lit: Boolean = true) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    /** When set, overrides the white / gray on-off colors. */
    var tint: Int? = null

    fun update(newLit: Boolean) {
        if (lit != newLit) { lit = newLit; invalidate() }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val s = minOf(width, height).toFloat()
        if (s <= 0f) return
        val color = tint ?: if (lit) 0xFFFFFFFF.toInt() else 0x66B0B0B0
        stroke.color = color
        fill.color = color
        stroke.strokeWidth = s * .08f
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        when (kind) {
            "lock" -> {
                canvas.drawRoundRect(RectF(s * .24f, s * .46f, s * .76f, s * .84f), s * .06f, s * .06f, fill)
                val shackle = Path().apply {
                    moveTo(s * .34f, s * .46f)
                    lineTo(s * .34f, s * .34f)
                    arcTo(RectF(s * .34f, s * .16f, s * .66f, s * .52f), 180f, 180f, false)
                    lineTo(s * .66f, s * .46f)
                }
                canvas.drawPath(shackle, stroke)
            }
            "power" -> {
                canvas.drawArc(RectF(s * .22f, s * .26f, s * .78f, s * .82f), -50f, 280f, false, stroke)
                canvas.drawLine(s * .5f, s * .14f, s * .5f, s * .52f, stroke)
            }
            "restart" -> {
                val cx = s * .5f; val cy = s * .5f; val r = s * .28f
                canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -60f, 270f, false, stroke)
                val end = Math.toRadians(210.0)
                val ex = cx + r * cos(end).toFloat()
                val ey = cy + r * sin(end).toFloat()
                val tx = -sin(end).toFloat(); val ty = cos(end).toFloat()
                val nx = -ty; val ny = tx
                val a = s * .13f
                val head = Path().apply {
                    moveTo(ex + tx * a, ey + ty * a)
                    lineTo(ex + nx * a * .8f, ey + ny * a * .8f)
                    lineTo(ex - nx * a * .8f, ey - ny * a * .8f)
                    close()
                }
                canvas.drawPath(head, fill)
            }
            "bluetooth" -> {
                val p = Path().apply {
                    moveTo(s * .30f, s * .32f); lineTo(s * .70f, s * .68f); lineTo(s * .50f, s * .86f)
                    lineTo(s * .50f, s * .14f); lineTo(s * .70f, s * .32f); lineTo(s * .30f, s * .68f)
                }
                canvas.drawPath(p, stroke)
            }
            "wifi" -> {
                val cx = s * .5f; val cy = s * .8f
                for (k in 0..2) {
                    val r = s * (.2f + .17f * k)
                    canvas.drawArc(RectF(cx - r, cy - r, cx + r, cy + r), -135f, 90f, false, stroke)
                }
                canvas.drawCircle(cx, cy, s * .05f, fill)
            }
            "sound" -> {
                speaker(canvas, s)
                for (r in floatArrayOf(.16f, .28f)) {
                    canvas.drawArc(RectF(s * (.5f - r), s * (.5f - r), s * (.5f + r), s * (.5f + r)), -45f, 90f, false, stroke)
                }
            }
            "vibrate" -> {
                canvas.drawRoundRect(RectF(s * .36f, s * .18f, s * .64f, s * .82f), s * .05f, s * .05f, stroke)
                canvas.drawLine(s * .24f, s * .36f, s * .24f, s * .64f, stroke)
                canvas.drawLine(s * .12f, s * .43f, s * .12f, s * .57f, stroke)
                canvas.drawLine(s * .76f, s * .36f, s * .76f, s * .64f, stroke)
                canvas.drawLine(s * .88f, s * .43f, s * .88f, s * .57f, stroke)
            }
            "handset" -> svg(canvas, s, SvgPaths.PHONE, 24f)
            "message" -> svg(canvas, s, SvgPaths.MESSAGE, 24f)
            "missed" -> svg(canvas, s, SvgPaths.MISSED, 512f)
            "prev" -> {
                canvas.drawRect(s * .2f, s * .25f, s * .3f, s * .75f, fill)
                canvas.drawPath(Path().apply { moveTo(s * .8f, s * .22f); lineTo(s * .36f, s * .5f); lineTo(s * .8f, s * .78f); close() }, fill)
            }
            "next" -> {
                canvas.drawRect(s * .7f, s * .25f, s * .8f, s * .75f, fill)
                canvas.drawPath(Path().apply { moveTo(s * .2f, s * .22f); lineTo(s * .64f, s * .5f); lineTo(s * .2f, s * .78f); close() }, fill)
            }
            "play" -> canvas.drawPath(Path().apply { moveTo(s * .26f, s * .14f); lineTo(s * .84f, s * .5f); lineTo(s * .26f, s * .86f); close() }, fill)
            "pause" -> {
                canvas.drawRect(s * .24f, s * .16f, s * .42f, s * .84f, fill)
                canvas.drawRect(s * .58f, s * .16f, s * .76f, s * .84f, fill)
            }
            "silent" -> {
                speaker(canvas, s)
                canvas.drawLine(s * .62f, s * .40f, s * .82f, s * .60f, stroke)
                canvas.drawLine(s * .82f, s * .40f, s * .62f, s * .60f, stroke)
            }
        }
        canvas.restore()
    }

    private fun svg(canvas: Canvas, s: Float, data: String, box: Float) {
        canvas.save()
        canvas.scale(s / box, s / box)
        canvas.drawPath(SvgPaths.path(data), fill)
        canvas.restore()
    }

    private fun speaker(canvas: Canvas, s: Float) {
        val p = Path().apply {
            moveTo(s * .18f, s * .40f); lineTo(s * .32f, s * .40f); lineTo(s * .50f, s * .24f)
            lineTo(s * .50f, s * .76f); lineTo(s * .32f, s * .60f); lineTo(s * .18f, s * .60f); close()
        }
        canvas.drawPath(p, fill)
    }
}

/** App icon: a white vector symbol (SVG path) on a colored rounded square. */
class AppIconDrawable(private val data: String, private val box: Float, color: Int) : Drawable() {
    private val background = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = 0xFFFFFFFF.toInt() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = minOf(b.width(), b.height()).toFloat()
        if (s <= 0f) return
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.drawRoundRect(RectF(0f, 0f, s, s), s * .22f, s * .22f, background)
        val symbol = s * .58f
        canvas.translate((s - symbol) / 2f, (s - symbol) / 2f)
        canvas.scale(symbol / box, symbol / box)
        canvas.drawPath(SvgPaths.path(data), fill)
        canvas.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = 192
    override fun getIntrinsicHeight(): Int = 192
}
