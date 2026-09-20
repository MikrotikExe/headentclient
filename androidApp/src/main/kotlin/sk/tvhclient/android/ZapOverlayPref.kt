package sk.tvhclient.android

import android.content.Context

/**
 * Automatic entry into PiP when leaving the player (going back home, EPG, the Home button).
 * On by default. When it is off, leaving the player closes it outright (without PiP);
 * the manual PiP button in the controls still works. Stored globally in SharedPreferences.
 */
object ZapOverlayPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "zap_overlay"

    /** M430-fix: false (the default) = on CH+/CH- the compact zap bar is shown
     *  (picon, number, channel, programme, progress) — the behaviour of a classic TV set;
     *  true = the overlay/controls open (whoever wants it turns it on). */
    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
