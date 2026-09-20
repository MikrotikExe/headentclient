package sk.tvhclient.android

import android.content.Context

/** Automatic refresh rate (AFR, M346): the player switches the display mode
 *  according to the frame rate of the stream (25/50 fps broadcast -> 50 Hz),
 *  which removes judder on 60 Hz panels. ON by default
 *  (M347-fix) — whoever does not want the short blackout on the switch turns it off. */
object AfrPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "afr_enabled"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
