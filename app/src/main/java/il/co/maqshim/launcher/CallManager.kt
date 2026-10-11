package il.co.maqshim.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.telecom.Call
import android.telecom.CallAudioState
import androidx.core.content.ContextCompat
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CopyOnWriteArraySet

/** Live list of phone calls handed to us by Android (we are the phone app). */
@Suppress("DEPRECATION")
object CallManager {
    val calls = CopyOnWriteArrayList<Call>()
    val listeners = CopyOnWriteArraySet<Runnable>()
    @Volatile var audioState: CallAudioState? = null
    private val names = HashMap<String, String?>()
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun add(call: Call) { calls.add(call); fire() }
    fun remove(call: Call) { calls.remove(call); fire() }
    fun fire() { main.post { for (l in listeners) l.run() } }

    /** The call the screen is about: ringing first, then active, then dialing, then anything else. */
    fun primary(): Call? =
        calls.firstOrNull { it.state == Call.STATE_RINGING }
            ?: calls.firstOrNull { it.state == Call.STATE_ACTIVE }
            ?: calls.firstOrNull { it.state == Call.STATE_DIALING || it.state == Call.STATE_CONNECTING }
            ?: calls.firstOrNull { it.state != Call.STATE_DISCONNECTED }
            ?: calls.firstOrNull()

    fun numberOf(call: Call): String = call.details?.handle?.schemeSpecificPart.orEmpty()

    fun displayName(ctx: Context, call: Call): String {
        val number = numberOf(call)
        if (number.isBlank()) return "מספר חסוי"
        lookup(ctx, number)?.let { return it }
        call.details?.callerDisplayName?.takeIf { it.isNotBlank() }?.let { return it }
        return number
    }

    private fun lookup(ctx: Context, number: String): String? {
        if (names.containsKey(number)) return names[number]
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        var found: String? = null
        try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) found = it.getString(0)
            }
        } catch (_: Exception) { }
        names[number] = found
        return found
    }

    private fun duration(call: Call): String {
        val start = call.details?.connectTimeMillis ?: 0L
        if (start <= 0L) return "00:00"
        val seconds = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        return "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    fun stateText(call: Call): String = when (call.state) {
        Call.STATE_RINGING -> "שיחה נכנסת"
        Call.STATE_DIALING, Call.STATE_CONNECTING, Call.STATE_NEW, Call.STATE_SELECT_PHONE_ACCOUNT -> "מחייג..."
        Call.STATE_ACTIVE -> "מחובר · ${duration(call)}"
        Call.STATE_HOLDING -> "בהמתנה"
        Call.STATE_DISCONNECTING -> "מנתק..."
        Call.STATE_DISCONNECTED -> "השיחה הסתיימה"
        else -> ""
    }

    /** One-line text for the "call in progress" card on the home screen. */
    fun cardText(ctx: Context): String? {
        val call = primary() ?: return null
        val name = displayName(ctx, call)
        return when (call.state) {
            Call.STATE_RINGING -> "שיחה נכנסת · $name"
            Call.STATE_ACTIVE -> "שיחה · $name · ${duration(call)}"
            Call.STATE_HOLDING -> "בהמתנה · $name"
            else -> "מחייג · $name"
        }
    }
}
