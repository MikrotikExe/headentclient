package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.EpgEvent

/**
 * M369 / M541 / M640: group filter in the player's channel list (and thereby of CH+/-),
 * split out of PlayerActivity. It rebuilds [live] (uuids/names/index + compose states)
 * for the selected group: All, Favourites (saved order, numbered 1..n), server tags,
 * Hidden channels. The choice is remembered (LastTag, M506). Now/next is filled in from the EPG cache
 * ([epgUpcoming]), so that switching a tag never loses the programme.
 */
internal class LiveGroups(
    private val ctx: Context,
    private val live: LiveSession,
    private val epgUpcoming: () -> Map<String, List<EpgEvent>>,
    private val navIndex: MutableState<Int>,
    private val groupLabel: MutableState<String>
) {
    private fun serverId(): String? = (live.server ?: Tvh.store.active())?.id

    fun labelFor(key: String): String = when (key) {
        LivePlaylist.GROUP_ALL -> ctx.getString(R.string.all_channels)
        LivePlaylist.GROUP_FAV -> ctx.getString(R.string.favorites)
        LivePlaylist.GROUP_HIDDEN -> ctx.getString(R.string.hidden_channels)   // M541
        else -> LivePlaylist.groups.firstOrNull { it.key == key }?.label
            ?: ctx.getString(R.string.all_channels)
    }

    /** Order of groups for cycling: All, Favourites (if there are any), tags,
     *  and Hidden channels at the end (M541, only if there are any). */
    fun keys(): List<String> {
        val keys = mutableListOf(LivePlaylist.GROUP_ALL)
        if (LivePlaylist.favChannels().isNotEmpty()) keys.add(LivePlaylist.GROUP_FAV)
        LivePlaylist.groups.forEach { keys.add(it.key) }
        if (LivePlaylist.hiddenChannels.isNotEmpty()) keys.add(LivePlaylist.GROUP_HIDDEN)
        return keys
    }

    /** M541: update the favourites order in LivePlaylist from the saved preferences. */
    fun refreshFavOrder() {
        val srvId = serverId() ?: return
        LivePlaylist.favOrder = Favorites.list(ctx, srvId)
    }

    /** Rebuilds the live list for the selected group; CH+/-, the tabs and the list then move within it. */
    fun apply(key: String) {
        val all = LivePlaylist.allChannels
        if (all.isEmpty() && key != LivePlaylist.GROUP_HIDDEN) return
        val srvId = serverId()
        // M541: Favourites = saved order, numbered 1..n; Hidden = its own list
        val filteredRaw: List<LivePlaylist.LiveChannel> = when (key) {
            LivePlaylist.GROUP_ALL -> all
            LivePlaylist.GROUP_FAV -> LivePlaylist.favChannels()
            LivePlaylist.GROUP_HIDDEN -> LivePlaylist.hiddenChannels
            else -> {
                val allow = LivePlaylist.groups.firstOrNull { it.key == key }?.uuids ?: return
                all.filter { it.uuid in allow }
            }
        }
        if (filteredRaw.isEmpty()) return   // empty group -> leave the state alone
        // Fill in "now" from the process EPG cache — so that switching a tag never loses the programme,
        // even if allChannels has not been enriched yet.
        val nowSec = System.currentTimeMillis() / 1000
        val epg = epgUpcoming()
        val filtered = filteredRaw.map { ch ->
            if (ch.nowTitle.isNotBlank()) ch
            else {
                val ev = epg[ch.uuid]?.firstOrNull { it.start <= nowSec && nowSec < it.stop }
                if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
            }
        }
        LivePlaylist.activeGroupKey = key
        // M506: remember the group choice — it is restored after the app restarts. "All" is
        // empty; M541: Favourites are remembered too (LastTag.FAV), Hidden never.
        LastTag.set(
            ctx, srvId, live.playKind == "radio",
            if (key == LivePlaylist.GROUP_ALL || key == LivePlaylist.GROUP_HIDDEN) null else LastTag.fromGroupKey(key)
        )
        LivePlaylist.channels = filtered
        live.channelsState.value = filtered
        live.uuids = filtered.map { it.uuid }
        live.names = filtered.map { it.name }
        val ni = live.uuids.indexOf(live.uuidState.value)
        live.index = if (ni >= 0) ni else 0     // if the current channel is not in the group, CH+/- starts from 0
        live.indexState.value = live.index
        navIndex.value = live.index
        groupLabel.value = labelFor(key)
    }

    /** Switches to the neighbouring group (dir +1 / -1). */
    fun cycle(dir: Int) {
        val ks = keys()
        if (ks.size < 2) return
        val cur = ks.indexOf(LivePlaylist.activeGroupKey).coerceAtLeast(0)
        val next = ((cur + dir) % ks.size + ks.size) % ks.size
        apply(ks[next])
    }
}
