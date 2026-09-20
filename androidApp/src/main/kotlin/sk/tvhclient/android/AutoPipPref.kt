package sk.tvhclient.android

import android.content.Context

/**
 * Automatic entry into PiP when leaving the player (back home, EPG, Home button).
 * On by default on phones/tablets, OFF on TV (M575, issue #11):
 * some TV boxes do support PiP at system level, but the floating window cannot be
 * focused or closed with the remote — the user could only get rid of it by quitting the app.
 * On TV, therefore, BACK closes the player; whoever wants PiP on TV turns it on in settings.
 * When off, leaving the player closes it straight away (no PiP); the manual PiP
 * button in the controls still works on a phone. Stored globally in SharedPreferences.
 */
object AutoPipPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "auto_pip"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, !isTvUiMode(context))   // M679

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
