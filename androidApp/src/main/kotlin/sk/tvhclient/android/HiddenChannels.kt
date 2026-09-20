package sk.tvhclient.android

import android.content.Context

/**
 * Hidden channels (per server). A hidden channel is NOT SHOWN in the player
 * (CH+/CH- zapping, the list in the player), but stays visible in the channel list
 * (the Channels screen) — so that the user can unhide it again.
 */
object HiddenChannels {
    private const val PREFS = "app_prefs"
    private const val KEY = "hidden_channels_" // + serverId -> Set<uuid>

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(c: Context, serverId: String?): Set<String> {
        if (serverId == null) return emptySet()
        return p(c).getStringSet(KEY + serverId, emptySet()) ?: emptySet()
    }

    fun isHidden(c: Context, serverId: String?, uuid: String?): Boolean {
        if (serverId == null || uuid == null) return false
        return all(c, serverId).contains(uuid)
    }

    fun setHidden(c: Context, serverId: String?, uuid: String, hidden: Boolean) {
        if (serverId == null) return
        val cur = HashSet(all(c, serverId))
        if (hidden) cur.add(uuid) else cur.remove(uuid)
        p(c).edit().putStringSet(KEY + serverId, cur).apply()
    }
}
