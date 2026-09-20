package sk.tvhclient.android

import android.content.Context

/**
 * M605: "The TV channels tile opens the list" (Settings → Appearance, TV only).
 *
 * By default the tile on the TV home screen starts the last watched channel and
 * the list is opened only in the player via OK. With the option on, the player opens
 * straight with the channel list (in the last group, cursor on the last channel) and
 * nothing plays until the user confirms a channel; BACK from the list returns to home.
 * It does not affect restoring the last channel after start-up, shortcuts, the favourites row or radios.
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
