package sk.tvhclient.android

import android.content.Context

/**
 * Automaticky vstup do PiP pri odchode z prehravaca (navrat domov, EPG, tlacidlo Home).
 * Predvolene zapnute na telefonoch/tabletoch, VYPNUTE na TV (M575, issue #11):
 * niektore TV boxy PiP systemovo podporuju, ale dialkovym ovladacom sa plavajuce
 * okno neda zameraat ani zavriet — pouzivatel ho vedel zrusit len ukoncenim appky.
 * Na TV preto BACK prehravac zatvori; kto PiP na TV chce, zapne si ho v nastaveniach.
 * Ked je vypnute, opustenie prehravaca ho rovno zatvori (bez PiP); manualne PiP
 * tlacidlo v ovladani funguje na telefone stale. Ulozene globalne v SharedPreferences.
 */
object AutoPipPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "auto_pip"

    private fun isTv(context: Context): Boolean {
        val um = context.getSystemService(Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, !isTv(context))

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
