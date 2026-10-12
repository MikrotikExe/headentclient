package sk.tvhclient.android

import android.content.Context

/**
 * The pinned player control panel (touch devices). Pinned = the info + control panel stays on
 * screen: no auto-hide after 3 s and a tap on the video does not hide it; the pin button in the
 * panel unpins it again. Useful where the panel fits next to the picture (portrait, a parked
 * car's screen) or whenever the user simply wants it visible. Not offered on TV: the remote's
 * D-pad navigation relies on the panel hiding. Off by default; stored globally in SharedPreferences.
 */
object ControlsPinPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "controls_pinned"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, pinned: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, pinned).apply()
    }
}
