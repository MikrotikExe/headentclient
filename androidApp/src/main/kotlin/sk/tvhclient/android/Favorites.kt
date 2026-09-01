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

    /** M541: oblubene V PORADI (poradie pridania / rucne usporiadanie). */
    fun list(context: Context, serverId: String): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(serverId), "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    fun isFav(context: Context, serverId: String, uuid: String): Boolean =
        all(context, serverId).contains(uuid)

    /** Pridanie ide na koniec zoznamu (poradie sa zachovava — LinkedHashSet). */
    fun toggle(context: Context, serverId: String, uuid: String) {
        val set = all(context, serverId).toMutableSet()
        if (!set.add(uuid)) set.remove(uuid)
        save(context, serverId, set.toList())
    }

    /** M541: presun polozky v poradi oblubenych. */
    fun move(context: Context, serverId: String, from: Int, to: Int) {
        val l = list(context, serverId).toMutableList()
        if (from !in l.indices || to !in l.indices || from == to) return
        val u = l.removeAt(from)
        l.add(to, u)
        save(context, serverId, l)
    }

    private fun save(context: Context, serverId: String, order: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(serverId), order.joinToString(",")).apply()
    }
}
