package sk.tvhclient.android

import android.content.Context

/**
 * When an archived (currently recording) channel is selected in the player, offer the choice
 * "Play live / Play from start". Off by default. When off, selecting a
 * channel switches straight to live (no question). Stored globally in SharedPreferences.
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
