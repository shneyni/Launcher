package il.co.maqshim.launcher

import android.content.Context

/** Persistent flags for the custom lock screen (survive process death). */
object LockState {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    fun isLocked(ctx: Context): Boolean = prefs(ctx).getBoolean("locked", false)
    fun setLocked(ctx: Context, value: Boolean) { prefs(ctx).edit().putBoolean("locked", value).apply() }

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean("lock_enabled", true)
    fun setEnabled(ctx: Context, value: Boolean) { prefs(ctx).edit().putBoolean("lock_enabled", value).apply() }
}
