package sk.tvhclient.android

import android.content.Context

/**
 * Favourite channels. For each server a list of channel uuids (CSV in SharedPreferences).
 */
object Favorites {
    private const val PREFS = "favorites"

    /**
     * M723 (issue #7): bumped on every change (add / remove / move), so the screens that show the
     * favourites — the home row, the channel and radio lists — refresh in place instead of
     * on the next app start (the launcher shortcuts were already republished, M573).
     */
    val revision = kotlinx.coroutines.flow.MutableStateFlow(0)
    private fun key(serverId: String) = "fav:$serverId"

    /** M750 (performance audit U5): the parsed set per server, reused while the stored string is the
     *  same — isFav is called per row on every selection move in the player's channel list. */
    private val parsed = HashMap<String, Pair<String, Set<String>>>()

    fun all(context: Context, serverId: String): Set<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(serverId), "") ?: ""
        synchronized(parsed) {
            parsed[serverId]?.let { (r, set) -> if (r == raw) return set }
        }
        val set = raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        synchronized(parsed) { parsed[serverId] = raw to set }
        return set
    }

    /** M541: favourites IN ORDER (order of adding / manual arrangement). */
    fun list(context: Context, serverId: String): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(serverId), "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.distinct()
    }

    fun isFav(context: Context, serverId: String, uuid: String): Boolean =
        all(context, serverId).contains(uuid)

    /** Adding goes to the end of the list (the order is preserved — LinkedHashSet). */
    fun toggle(context: Context, serverId: String, uuid: String) {
        val set = all(context, serverId).toMutableSet()
        if (!set.add(uuid)) set.remove(uuid)
        save(context, serverId, set.toList())
    }

    /** M541: move an item within the favourites order. */
    fun move(context: Context, serverId: String, from: Int, to: Int) {
        val l = list(context, serverId).toMutableList()
        if (from !in l.indices || to !in l.indices || from == to) return
        val u = l.removeAt(from)
        l.add(to, u)
        save(context, serverId, l)
    }

    /**
     * M583: move by uuid. The favourites list is SHARED per server for both TV and
     * radio, but the tabs only show their own part — an index in the filtered list
     * is not an index in the saved order. [uuid] is inserted at the position of [targetUuid]
     * (exactly as the player does when reordering with the D-pad).
     */
    fun moveUuid(context: Context, serverId: String, uuid: String, targetUuid: String) {
        val l = list(context, serverId)
        val from = l.indexOf(uuid); val to = l.indexOf(targetUuid)
        if (from < 0 || to < 0) return
        move(context, serverId, from, to)
    }

    private fun save(context: Context, serverId: String, order: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(serverId), order.joinToString(",")).apply()
        FavoriteShortcuts.onFavoritesChanged(context, serverId)   // M573
        revision.value = revision.value + 1   // M723
    }
}
