package il.co.maqshim.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!LockState.enabled(context)) return
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) LockState.setLocked(context, true)
        try {
            ContextCompat.startForegroundService(context, Intent(context, LockService::class.java))
        } catch (_: Exception) {}
    }
}
