package sk.tvhclient.android

import android.content.Context

/** Diagnostika diaľkového: zobrazovanie kódu stlačených klávesov (predvolene vypnuté). */
object RemoteDebugPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "remote_debug_keycodes"
    fun isEnabled(c: Context): Boolean =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)
    fun setEnabled(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
    }
}
