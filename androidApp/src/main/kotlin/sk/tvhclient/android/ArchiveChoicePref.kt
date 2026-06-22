package sk.tvhclient.android

import android.content.Context

/**
 * Pri vybere archivovaneho (prave nahravaneho) kanala v prehravaci ponuknut vyber
 * "Prehrat nazivo / Prehrat od zaciatku". Predvolene vypnute. Ked je vypnute, vyber
 * kanala rovno prepne nazivo (bez otazky). Ulozene globalne v SharedPreferences.
 */
object ArchiveChoicePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "archive_choice"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
