package il.co.maqshim.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After boot the device starts locked; the lock screen is shown by the accessibility service. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (LockState.enabled(context) && intent.action == Intent.ACTION_BOOT_COMPLETED) LockState.setLocked(context, true)
    }
}
