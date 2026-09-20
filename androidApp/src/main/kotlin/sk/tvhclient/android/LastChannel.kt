package sk.tvhclient.android

import android.content.Context

/**
 * Remembering the last selected live channel (per server), so that after the app
 * starts the list focuses/scrolls to that channel instead of searching.
 * On the first start (no record) the first channel (number 1) is used.
 */
object LastChannel {
    private const val PREFS = "app_prefs"
    private const val KEY = "last_channel_uuid_"

    fun get(context: Context, serverId: String?): String? {
        if (serverId == null) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY + serverId, null)
    }

    /** M391: forget the last channel (a change of connection method -> the old ids are invalid). */
    fun clear(context: Context, serverId: String?) {
        if (serverId == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY + serverId).apply()
    }

    fun set(context: Context, serverId: String?, uuid: String?) {
        if (serverId == null || uuid == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY + serverId, uuid).apply()
    }
}
