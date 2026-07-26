package sk.tvhclient.android

import android.content.Context

/**
 * Automaticky vstup do PiP pri odchode z prehravaca (navrat domov, EPG, tlacidlo Home).
 * Predvolene zapnute. Ked je vypnute, opustenie prehravaca ho rovno zatvori (bez PiP);
 * manualne PiP tlacidlo v ovladani funguje stale. Ulozene globalne v SharedPreferences.
 */
object ZapOverlayPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "zap_overlay"

    /** M430-fix: false (predvolene) = pri CH+/CH- sa zobrazi kompaktny zap pas
     *  (picona, cislo, kanal, program, priebeh) — spravanie klasickej telky;
     *  true = otvori sa prekryv/ovladanie (kto ho chce, zapne si ho). */
    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
