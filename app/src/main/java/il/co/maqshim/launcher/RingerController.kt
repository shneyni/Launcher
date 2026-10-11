package il.co.maqshim.launcher

import android.content.Context
import android.media.AudioManager

object RingerController {
    enum class Mode { SOUND, VIBRATE, SILENT }

    private fun audio(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    fun current(ctx: Context): Mode = when (audio(ctx).ringerMode) {
        AudioManager.RINGER_MODE_NORMAL -> Mode.SOUND
        AudioManager.RINGER_MODE_VIBRATE -> Mode.VIBRATE
        else -> Mode.SILENT
    }

    fun next(ctx: Context): Mode = when (current(ctx)) {
        Mode.SOUND -> Mode.VIBRATE
        Mode.VIBRATE -> Mode.SILENT
        Mode.SILENT -> Mode.SOUND
    }

    /** Returns false when Android refuses (silent mode needs "Do not disturb" access). */
    fun set(ctx: Context, mode: Mode): Boolean = try {
        audio(ctx).ringerMode = when (mode) {
            Mode.SOUND -> AudioManager.RINGER_MODE_NORMAL
            Mode.VIBRATE -> AudioManager.RINGER_MODE_VIBRATE
            Mode.SILENT -> AudioManager.RINGER_MODE_SILENT
        }
        current(ctx) == mode
    } catch (_: SecurityException) {
        false
    } catch (_: Exception) {
        false
    }

    fun glyph(mode: Mode): String = when (mode) {
        Mode.SOUND -> "sound"
        Mode.VIBRATE -> "vibrate"
        Mode.SILENT -> "silent"
    }

    fun label(mode: Mode): String = when (mode) {
        Mode.SOUND -> "צליל"
        Mode.VIBRATE -> "רטט"
        Mode.SILENT -> "שקט"
    }
}
