package il.co.maqshim.launcher

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

/** Loads the device's home wallpaper once and keeps a sharp and a blurred copy sized for the screen. */
object WallpaperHelper {
    private var cachedId = Long.MIN_VALUE
    private var cachedSize = ""
    private var sharp: Bitmap? = null
    private var blurred: Bitmap? = null
    private var lastFailAt = 0L

    fun invalidate() { sharp = null; blurred = null; cachedId = Long.MIN_VALUE }
    fun retryNow() { lastFailAt = 0L }

    private fun currentId(ctx: Context): Long =
        try { WallpaperManager.getInstance(ctx).getWallpaperId(WallpaperManager.FLAG_SYSTEM).toLong() } catch (_: Throwable) { -1L }

    fun get(ctx: Context, blur: Boolean): Bitmap? {
        val metrics = ctx.resources.displayMetrics
        val size = "${metrics.widthPixels}x${metrics.heightPixels}"
        val id = currentId(ctx)
        if (sharp != null && id == cachedId && size == cachedSize) return if (blur) blurred else sharp
        if (sharp == null && System.currentTimeMillis() - lastFailAt < 30_000L) return null
        val raw = loadRaw(ctx)
        if (raw == null) { lastFailAt = System.currentTimeMillis(); sharp = null; blurred = null; return null }
        sharp = cropScale(raw, metrics.widthPixels, metrics.heightPixels)
        val small = cropScale(raw, (metrics.widthPixels / 10).coerceAtLeast(8), (metrics.heightPixels / 10).coerceAtLeast(8))
        blurred = boxBlur(small, 2)
        cachedId = id
        cachedSize = size
        return if (blur) blurred else sharp
    }

    /** Home wallpaper; falls back to whatever Android lets us read. */
    private fun loadRaw(ctx: Context): Bitmap? {
        val wm = WallpaperManager.getInstance(ctx)
        try {
            wm.getWallpaperFile(WallpaperManager.FLAG_SYSTEM)?.use { pfd ->
                val opts = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFileDescriptor(pfd.fileDescriptor, null, opts)?.let { return it }
            }
        } catch (_: Throwable) { }
        try {
            val d = wm.drawable
            if (d != null) {
                val w = d.intrinsicWidth.coerceAtLeast(1)
                val h = d.intrinsicHeight.coerceAtLeast(1)
                val scale = minOf(1f, 1200f / maxOf(w, h))
                val bmp = Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bmp)
                d.setBounds(0, 0, bmp.width, bmp.height)
                d.draw(canvas)
                return bmp
            }
        } catch (_: Throwable) { }
        return null
    }

    private fun cropScale(src: Bitmap, w: Int, h: Int): Bitmap {
        val scale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = (src.width * scale).toInt().coerceAtLeast(w)
        val sh = (src.height * scale).toInt().coerceAtLeast(h)
        val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
        return Bitmap.createBitmap(scaled, (sw - w) / 2, (sh - h) / 2, w, h)
    }

    private fun boxBlur(bmp: Bitmap, radius: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val a = IntArray(w * h)
        val b = IntArray(w * h)
        bmp.getPixels(a, 0, w, 0, 0, w, h)
        repeat(2) { pass(a, b, w, h, radius, true); pass(b, a, w, h, radius, false) }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        return out
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until len) {
                var red = 0; var green = 0; var blue = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val c = src[if (horizontal) line * w + j else j * w + line]
                    red += (c shr 16) and 255; green += (c shr 8) and 255; blue += c and 255
                }
                val n = 2 * r + 1
                dst[if (horizontal) line * w + i else i * w + line] =
                    (0xFF shl 24) or ((red / n) shl 16) or ((green / n) shl 8) or (blue / n)
            }
        }
    }
}
