package sk.tvhclient.android

import android.content.Context

/**
 * Oblubene kanaly. Pre kazdy server zoznam uuid kanalov (CSV v SharedPreferences).
 */
object Favorites {
    private const val PREFS = "favorites"
    private fun key(serverId: String) = "fav:$serverId"

    fun all(context: Context, serverId: String): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(serverId), "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    fun isFav(context: Context, serverId: String, uuid: String): Boolean =
        all(context, serverId).contains(uuid)

    fun toggle(context: Context, serverId: String, uuid: String) {
        val set = all(context, serverId).toMutableSet()
        if (!set.add(uuid)) set.remove(uuid)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(serverId), set.joinToString(",")).apply()
    }
}
