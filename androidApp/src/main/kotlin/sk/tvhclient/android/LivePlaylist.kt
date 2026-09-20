package sk.tvhclient.android

/**
 * Shared list of live channels for switching (zapping) and for the channel list
 * directly in the player. It is populated when the channel list / grid is shown.
 */
object LivePlaylist {
    data class LiveChannel(
        val uuid: String,
        val name: String,
        val number: Int,
        val piconUrl: String?,
        val nowTitle: String,
        val nowStart: Long,
        val nowStop: Long,
        val nextTitle: String = "",
        val nextStart: Long = 0,
        val nextStop: Long = 0,
        val recording: Boolean = false
    )

    @Volatile
    var channels: List<LiveChannel> = emptyList()

    // M369: the full (group-unfiltered) list + groups/tags for the filter.
    // 'channels' is the currently shown subset; 'allChannels' is always the whole list,
    // from which it is rebuilt when the group changes. The groups are only tags; All/Favourites
    // are implicit (Favourites are read dynamically from Favorites in the player).
    data class Group(val key: String, val label: String, val uuids: Set<String>)

    const val GROUP_ALL = ""
    const val GROUP_FAV = "\u0000fav"
    /** M541: pseudo-group of hidden channels (only in the player, so they can be unhidden). */
    const val GROUP_HIDDEN = "\u0000hidden"

    /** M541: favourites order (uuid) — in the Favourites group the channels are numbered 1..n by it. */
    @Volatile
    var favOrder: List<String> = emptyList()
    /** M541: hidden channels (they are neither in allChannels nor in the groups). */
    @Volatile
    var hiddenChannels: List<LiveChannel> = emptyList()

    /** M541: favourites in the saved order, renumbered 1..n. */
    fun favChannels(): List<LiveChannel> {
        val byUuid = allChannels.associateBy { it.uuid }
        return favOrder.mapNotNull { byUuid[it] }.mapIndexed { i, ch -> ch.copy(number = i + 1) }
    }

    @Volatile
    var allChannels: List<LiveChannel> = emptyList()
    @Volatile
    var groups: List<Group> = emptyList()
    @Volatile
    var activeGroupKey: String = GROUP_ALL

    /** M391: full reset (a change of the server's connection method — the old ids are invalid). */
    fun reset() {
        allChannels = emptyList()
        channels = emptyList()
        groups = emptyList()
        favOrder = emptyList()
        hiddenChannels = emptyList()
        activeGroupKey = GROUP_ALL
    }

    /**
     * Populates both the list and the groups.
     *
     * M506: [restoreKey] = the last selected group (from LastTag). If it is found among
     * the groups, the filter is set to it — otherwise it starts from "All".
     * Thanks to that the player comes up after an app restart in the same group the
     * user finished in, just like the Channels list.
     */
    fun setChannels(
        full: List<LiveChannel>, grps: List<Group>, restoreKey: String? = null,
        favs: List<String> = emptyList(), hidden: List<LiveChannel> = emptyList()
    ) {
        allChannels = full
        groups = grps
        favOrder = favs
        hiddenChannels = hidden
        // M541: Favourites are remembered as a group too
        if (restoreKey == GROUP_FAV) {
            val f = favChannels()
            if (f.isNotEmpty()) { activeGroupKey = GROUP_FAV; channels = f; return }
        }
        val g = restoreKey?.let { k -> grps.firstOrNull { it.key == k } }
        activeGroupKey = g?.key ?: GROUP_ALL
        channels = if (g != null) full.filter { it.uuid in g.uuids } else full
    }

    // M271: process EPG cache (uuid -> programmes) + the time of the last successful refresh.
    // It survives closing/opening the player, so it is not downloaded again on every open.
    @Volatile
    var epgUpcoming: Map<String, List<sk.tvhclient.shared.model.EpgEvent>> = emptyMap()
    @Volatile
    var epgLastOkMs: Long = 0L

    fun clearEpg() {
        epgUpcoming = emptyMap()
        epgLastOkMs = 0L
    }

    @Volatile
    var index: Int = -1

    fun setIndexForUuid(uuid: String?) {
        index = if (uuid == null) -1 else channels.indexOfFirst { it.uuid == uuid }
    }
}
