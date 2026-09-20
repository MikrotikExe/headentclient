package sk.tvhclient.android

import android.content.Context

/**
 * M448: the verbose player log (libVLC verbose).
 *
 * OFF by default — the app passes "--quiet" to libVLC, so the player writes
 * nothing into logcat. Once it is enabled, "-vv" is passed, which brings up the messages about
 * the clock (PCR), buffering and decoding. It serves to diagnose reported
 * problems (stuttering, freezing, drifting audio) — the log can then simply be
 * captured with adb logcat or sent via Diagnostics.
 *
 * Having it on costs performance (a lot of writes), so it is turned off after the diagnosis.
 */
object VlcVerbosePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "vlc_verbose"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
