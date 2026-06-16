package sk.tvhclient.android

import android.content.Context

/**
 * Zapamatanie posledne zvoleneho live kanala (per server), aby po spusteni
 * aplikacie zoznam zafokusoval/scrolloval na ten kanal namiesto vyhladavania.
 * Pri prvom spusteni (ziadny zaznam) sa pouzije prvy kanal (cislo 1).
 */
object LastChannel {
    private const val PREFS = "app_prefs"
    private const val KEY = "last_channel_uuid_"

    fun get(context: Context, serverId: String?): String? {
        if (serverId == null) return null
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY + serverId, null)
    }

    fun set(context: Context, serverId: String?, uuid: String?) {
        if (serverId == null || uuid == null) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY + serverId, uuid).apply()
    }
}
