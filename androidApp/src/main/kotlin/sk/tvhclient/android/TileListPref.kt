package sk.tvhclient.android

import android.content.Context

/**
 * M605: „Dlaždica TV kanály otvorí zoznam" (Nastavenia → Vzhľad, len TV).
 *
 * Predvolene dlaždica na úvodnej obrazovke TV spustí posledný sledovaný kanál a
 * zoznam sa otvára až v prehrávači cez OK. So zapnutou voľbou sa prehrávač otvorí
 * rovno so zoznamom kanálov (v poslednej skupine, kurzor na poslednom kanáli) a
 * nič nehrá, kým používateľ kanál nepotvrdí; BACK zo zoznamu vráti na úvod.
 * Netýka sa obnovy posledného kanála po štarte, skratiek, radu obľúbených ani rádií.
 */
object TileListPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "tile_list_first"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
