package sk.tvhclient.android

import android.content.Context

/**
 * Timeshift in the player (pause/seek of live TV over HTSP). On by default.
 * This is only a user option — timeshift is actually enabled only if the server
 * supports it too (HtspData.timeshiftAvailable: HTSP port available + the "timeshift" capability).
 * Stored globally in SharedPreferences.
 */
object TimeshiftPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "timeshift_enabled"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
