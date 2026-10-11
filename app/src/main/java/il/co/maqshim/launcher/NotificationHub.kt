package il.co.maqshim.launcher

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArraySet

/** Shared state for the home / lock screen cards: missed calls, unread messages and the playing song. */
object NotificationHub {
    class Calls(val count: Int, val sender: String, val intent: PendingIntent?)
    class Messages(val count: Int, val senders: List<String>, val intent: PendingIntent?)
    class Media(
        val title: String, val artist: String, val album: String, val playing: Boolean,
        val position: Long, val duration: Long, val updatedAt: Long, val speed: Float,
        val controller: MediaController
    )

    @Volatile var calls: Calls? = null
    @Volatile var messages: Messages? = null
    @Volatile var media: Media? = null

    val listeners = CopyOnWriteArraySet<Runnable>()
    private val main = Handler(Looper.getMainLooper())

    fun publish(newCalls: Calls?, newMessages: Messages?) {
        calls = newCalls
        messages = newMessages
        fire()
    }

    fun publishMedia(newMedia: Media?) {
        media = newMedia
        fire()
    }

    private fun fire() {
        main.post { for (l in listeners) l.run() }
    }

    fun callsText(): String {
        val c = calls ?: return ""
        val noun = if (c.count == 1) "שיחה שלא נענתה" else "שיחות שלא נענו"
        return "${c.count} $noun" + if (c.sender.isNotBlank()) " מ${c.sender}" else ""
    }

    fun messagesText(): String {
        val m = messages ?: return ""
        val noun = if (m.count == 1) "הודעה שלא נקראה" else "הודעות שלא נקראו"
        val names = when {
            m.senders.isEmpty() -> ""
            m.senders.size == 1 -> " מ${m.senders[0]}"
            m.senders.size == 2 -> " מ${m.senders[0]} ו${m.senders[1]}"
            else -> " מ${m.senders[0]} ועוד ${m.senders.size - 1}"
        }
        return "${m.count} $noun$names"
    }

    /** Playback position as 0..1, extrapolated while the song is playing. */
    fun progress(): Float {
        val m = media ?: return 0f
        if (m.duration <= 0) return 0f
        val elapsed = if (m.playing) ((SystemClock.elapsedRealtime() - m.updatedAt) * m.speed).toLong() else 0L
        return ((m.position + elapsed).toFloat() / m.duration).coerceIn(0f, 1f)
    }

    fun togglePlay() {
        val m = media ?: return
        try {
            val controls = m.controller.transportControls
            if (m.playing) controls.pause() else controls.play()
        } catch (_: Exception) { }
    }

    fun skip(next: Boolean) {
        val m = media ?: return
        try {
            val controls = m.controller.transportControls
            if (next) controls.skipToNext() else controls.skipToPrevious()
        } catch (_: Exception) { }
    }

    /** Opens the music app that owns the current session. */
    fun openMediaApp(ctx: Context) {
        val m = media ?: return
        try {
            val session = m.controller.sessionActivity
            if (session != null) { session.send(); return }
        } catch (_: Exception) { }
        try {
            ctx.packageManager.getLaunchIntentForPackage(m.controller.packageName)?.let {
                ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        } catch (_: Exception) { }
    }
}
