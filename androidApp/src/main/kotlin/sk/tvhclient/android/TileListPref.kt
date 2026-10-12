package sk.tvhclient.android

import android.content.Context

/**
 * M605 / M723 (issue #14): what the TV channels and Radio tiles on the TV home screen do.
 *
 *  - [PLAY]: the tile starts the last watched channel; the list is opened in the player via OK
 *    (the default, the former switch off).
 *  - [LIST]: the player starts the last channel and opens the channel list over it right away
 *    (in the last group, cursor on the last channel); BACK from the list returns to home
 *    (the former switch on — migrated).
 *  - [ONLY]: the tile opens the channel (station) list screen of the app — the same one as on a
 *    phone, with the groups and now/next — and nothing plays until a channel is chosen;
 *    BACK returns to home. M723.
 *
 * It does not affect restoring the last channel after start-up, the shortcuts, the favourites row.
 */
object TileListPref {
    private const val PREFS = "app_prefs"
    private const val KEY_OLD = "tile_list_first"   // M605 boolean
    private const val KEY = "tile_mode"

    const val PLAY = "play"
    const val LIST = "list"
    const val ONLY = "only"

    val options = listOf(PLAY, LIST, ONLY)

    fun mode(context: Context): String {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.getString(KEY, null)?.let { return it }
        return if (p.getBoolean(KEY_OLD, false)) LIST else PLAY   // M605 migration
    }

    /** M605 semantics: the player opens with the list over the playing channel. */
    fun get(context: Context): Boolean = mode(context) == LIST

    /** M723: the tile opens the list screen only (nothing plays). */
    fun listOnly(context: Context): Boolean = mode(context) == ONLY

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
