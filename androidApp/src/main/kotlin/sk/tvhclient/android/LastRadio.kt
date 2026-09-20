package sk.tvhclient.android

import android.content.Context

/**
 * Remembering the last selected radio station (per server) — separate from
 * LastChannel (TV), so that they do not overwrite each other. The launcher on TV can use it to
 * play the last station straight away, just as with TV channels.
 */
object LastRadio {
    private const val PREFS = "app_prefs"
    private const val KEY = "last_radio_uuid_"

    fun get(context: Context, serverId: String?): String? {
        if (serverId == null) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY + serverId, null)
    }

    /** M391: forget the last station (a change of connection method). */
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
