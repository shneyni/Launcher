package il.co.maqshim.launcher

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Reads missed-call / message notifications and the active media session (needs notification access). */
class NotifListener : NotificationListenerService() {
    private var sessionManager: MediaSessionManager? = null
    private val tracked = mutableListOf<MediaController>()

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) { pickMedia() }
        override fun onMetadataChanged(metadata: MediaMetadata?) { pickMedia() }
        override fun onSessionDestroyed() { pickMedia() }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> track(list ?: emptyList()) }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val component = ComponentName(this, NotifListener::class.java)
        try {
            val manager = getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            sessionManager = manager
            manager.addOnActiveSessionsChangedListener(sessionsListener, component)
            track(manager.getActiveSessions(component))
        } catch (_: Exception) { }
        scan()
    }

    override fun onListenerDisconnected() {
        try { sessionManager?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) { }
        untrack()
        NotificationHub.publish(null, null)
        NotificationHub.publishMedia(null)
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) { scan() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { scan() }

    // ---- notifications ----

    private fun isOngoing(n: Notification) = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
    private fun isSummary(n: Notification) = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0

    private fun isMissedCall(n: Notification): Boolean {
        val category = n.category
        return category == "missed_call" ||
                (category == Notification.CATEGORY_CALL && !isOngoing(n) && n.fullScreenIntent == null)
    }

    private fun isMessage(n: Notification) = n.category == Notification.CATEGORY_MESSAGE && !isOngoing(n)

    private fun text(n: Notification, key: String): String = n.extras?.getCharSequence(key)?.toString().orEmpty()

    private fun callSender(n: Notification): String {
        val title = text(n, Notification.EXTRA_TITLE)
        val body = text(n, Notification.EXTRA_TEXT)
        val generic = title.isBlank() || listOf("שיחה", "שיחות", "missed", "Missed").any { title.contains(it) }
        return if (generic) body.ifBlank { title } else title
    }

    private fun scan() {
        val all: List<StatusBarNotification> = try { activeNotifications?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }

        val callList = all.filter { isMissedCall(it.notification) }
        val callUse = callList.filter { !isSummary(it.notification) }.ifEmpty { callList }
        val calls = if (callUse.isEmpty()) null else NotificationHub.Calls(
            count = callUse.size,
            sender = callSender(callUse.first().notification),
            intent = callUse.firstNotNullOfOrNull { it.notification.contentIntent }
        )

        val messageList = all.filter { isMessage(it.notification) }
        val messageUse = messageList.filter { !isSummary(it.notification) }.ifEmpty { messageList }
        val messages = if (messageUse.isEmpty()) null else {
            var count = 0
            val senders = linkedSetOf<String>()
            for (sbn in messageUse) {
                val extras = sbn.notification.extras
                val size = extras?.getParcelableArray(Notification.EXTRA_MESSAGES)?.size ?: 0
                count += size.coerceAtLeast(1)
                text(sbn.notification, Notification.EXTRA_TITLE).takeIf { it.isNotBlank() }?.let { senders.add(it) }
            }
            NotificationHub.Messages(count, senders.toList(), messageUse.firstNotNullOfOrNull { it.notification.contentIntent })
        }
        NotificationHub.publish(calls, messages)
    }

    // ---- media ----

    private fun untrack() {
        for (c in tracked) { try { c.unregisterCallback(callback) } catch (_: Exception) { } }
        tracked.clear()
    }

    private fun track(list: List<MediaController>) {
        untrack()
        tracked.addAll(list)
        for (c in tracked) { try { c.registerCallback(callback) } catch (_: Exception) { } }
        pickMedia()
    }

    private fun pickMedia() {
        fun state(c: MediaController) = c.playbackState?.state ?: PlaybackState.STATE_NONE
        val best = tracked.firstOrNull { state(it) == PlaybackState.STATE_PLAYING }
            ?: tracked.firstOrNull { (state(it) == PlaybackState.STATE_PAUSED || state(it) == PlaybackState.STATE_BUFFERING) && it.metadata != null }
        if (best == null) { NotificationHub.publishMedia(null); return }
        val meta = best.metadata
        val playback = best.playbackState
        fun str(vararg keys: String): String {
            for (k in keys) { val v = meta?.getString(k); if (!v.isNullOrBlank()) return v }
            return ""
        }
        val title = str(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        if (title.isBlank()) { NotificationHub.publishMedia(null); return }
        NotificationHub.publishMedia(
            NotificationHub.Media(
                title = title,
                artist = str(MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST, MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE),
                album = str(MediaMetadata.METADATA_KEY_ALBUM),
                playing = playback?.state == PlaybackState.STATE_PLAYING,
                position = (playback?.position ?: 0L).coerceAtLeast(0L),
                duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                updatedAt = playback?.lastPositionUpdateTime ?: 0L,
                speed = playback?.playbackSpeed ?: 1f,
                controller = best
            )
        )
    }
}
