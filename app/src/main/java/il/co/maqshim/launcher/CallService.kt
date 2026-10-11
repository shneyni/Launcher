package il.co.maqshim.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService

/** Android binds this service when we are the default phone app and a call starts. */
@Suppress("DEPRECATION")
class CallService : InCallService() {
    companion object {
        @Volatile var instance: CallService? = null
        private const val NOTIFICATION_ID = 21
    }

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) { CallManager.fire(); updateNotification() }
        override fun onDetailsChanged(call: Call, details: Call.Details) { CallManager.fire() }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("calls", "שיחות", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)       // Android itself plays the ringtone
            enableVibration(false)
        })
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun callIntent() = Intent(this, CallActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)

    override fun onCallAdded(call: Call) {
        call.registerCallback(callback)
        CallManager.add(call)
        updateNotification()
        try { startActivity(callIntent()) } catch (_: Exception) { }   // the notification below is the fallback
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        CallManager.remove(call)
        updateNotification()
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState?) {
        CallManager.audioState = audioState
        CallManager.fire()
    }

    private fun updateNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val call = CallManager.primary()
        if (call == null) { nm.cancel(NOTIFICATION_ID); return }
        val pending = PendingIntent.getActivity(this, 0, callIntent(), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val builder = Notification.Builder(this, "calls")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(CallManager.displayName(this, call))
            .setContentText(CallManager.stateText(call))
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(pending)
        if (call.state == Call.STATE_RINGING) builder.setFullScreenIntent(pending, true)
        try { nm.notify(NOTIFICATION_ID, builder.build()) } catch (_: Exception) { }
    }
}
