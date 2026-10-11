package il.co.maqshim.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** Keeps a receiver alive so our lock screen can appear whenever the screen turns off/on. */
class LockService : Service() {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!LockState.enabled(context)) return
            when (intent.action) {
                // Only remember the lock; the lock screen appears when the screen turns on, so it never keeps the display lit.
                Intent.ACTION_SCREEN_OFF -> LockState.setLocked(context, true)
                Intent.ACTION_SCREEN_ON -> if (LockState.isLocked(context)) LockActivity.show(context)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("lock", "מסך נעילה", NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, "lock")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("מסך הנעילה פעיל")
            .setOngoing(true)
            .build()
        try {
            ServiceCompat.startForeground(
                this, 7, notification,
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            )
        } catch (_: Exception) {}
        ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (LockState.enabled(this) && LockState.isLocked(this)) LockActivity.show(this)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
