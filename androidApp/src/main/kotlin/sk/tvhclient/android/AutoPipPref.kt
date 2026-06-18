package sk.tvhclient.android

import android.content.Context

/**
 * Automaticky vstup do PiP pri odchode z prehravaca (navrat domov, EPG, tlacidlo Home).
 * Predvolene zapnute. Ked je vypnute, opustenie prehravaca ho rovno zatvori (bez PiP);
 * manualne PiP tlacidlo v ovladani funguje stale. Ulozene globalne v SharedPreferences.
 */
object AutoPipPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "auto_pip"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, true)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
