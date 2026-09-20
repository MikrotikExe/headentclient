package sk.tvhclient.android

import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent

/**
 * M641: channel context menu in the player (long-press OK / long click), split out
 * of PlayerActivity. Items depend on the channel: info, play from start (if it is recording),
 * record (M607), favourite (M368), reorder (M541), lock, hide/unhide (M541).
 * Hiding/unhiding directly rearranges [live] (list, index) and LivePlaylist.
 *
 * Actions that reach into the player or DVR are passed in as lambdas ([actions]).
 */
internal class ChannelContextMenu(
    private val ctx: Context,
    private val live: LiveSession,
    private val groups: LiveGroups,
    private val epgUpcoming: () -> Map<String, List<EpgEvent>>,
    private val recInProgress: () -> Map<String, DvrEntry>,
    private val canRecord: () -> Boolean,
    private val navIndex: MutableState<Int>,
    private val okLongFired: () -> Boolean,
    private val actions: Actions
) {
    interface Actions {
        fun showInfo(idx: Int)
        fun playFromStart(rec: DvrEntry, nowStart: Long, nowStop: Long)
        fun switchTo(idx: Int)
        fun toggleLock(idx: Int)
        fun record(ch: LivePlaylist.LiveChannel, ev: EpgEvent)
        fun enterReorder()
    }

    val idxState = mutableStateOf(-1)  // channel index, -1 = closed
    val selState = mutableStateOf(0)   // highlighted item

    val isOpen: Boolean get() = idxState.value >= 0

    private fun serverId(): String? = (live.server ?: Tvh.store.active())?.id

    /** Menu items for the given channel (in order). "lock" only if the lock is enabled,
     *  "fromstart" only if the programme is currently recording (it can be played from the start). */
    fun keys(idx: Int): List<String> {
        val ch = live.channelsState.value.getOrNull(idx) ?: return emptyList()
        val keys = mutableListOf("info")
        if (recInProgress().let { it[ch.uuid] ?: it[ch.name] } != null) keys.add("fromstart")
        // M607: record the programme currently running on the selected channel straight from the list
        // (the channel need not be playing) — only when we have its EPG with an eventId and it is not recording yet
        else if (canRecord() && eventOf(ch) != null) keys.add("rec")
        // M368: favourites and hiding a channel on TV too (previously phone only)
        keys.add("fav")
        // M541: reordering favourites (only in the Favourites group, D-pad only)
        if (LivePlaylist.activeGroupKey == LivePlaylist.GROUP_FAV && live.channelsState.value.size > 1) keys.add("reorder")
        if (ParentalLock.isEnabled(ctx)) keys.add("lock")
        // M541-fix: a hidden channel (anywhere, not just in the Hidden group) -> "Unhide channel"
        val hiddenCh = LivePlaylist.activeGroupKey == LivePlaylist.GROUP_HIDDEN ||
            HiddenChannels.isHidden(ctx, serverId(), ch.uuid)
        keys.add(if (hiddenCh) "unhide" else "hide")
        return keys
    }

    /** M607: the programme currently running on the channel (from the now/next cache), if it has an eventId. */
    fun eventOf(ch: LivePlaylist.LiveChannel): EpgEvent? {
        val nowSec = System.currentTimeMillis() / 1000
        return epgUpcoming()[ch.uuid]?.firstOrNull { it.start <= nowSec && nowSec < it.stop && it.eventId != null }
    }

    fun open(idx: Int) {
        if (idx < 0 || idx >= live.channelsState.value.size) return
        if (keys(idx).isEmpty()) return
        selState.value = 0
        idxState.value = idx
    }

    fun close() { idxState.value = -1 }

    /**
     * Adding/removing a channel from favourites (context menu, M579: the BOOKMARK key
     * on the remote). [announce] shows a confirmation — with the key and no menu the
     * user would otherwise not see what happened.
     */
    fun toggleFavoriteAt(idx: Int, announce: Boolean) {
        val ch = live.channelsState.value.getOrNull(idx) ?: return
        val sid = serverId() ?: return
        Favorites.toggle(ctx, sid, ch.uuid)
        val nowFav = Favorites.isFav(ctx, sid, ch.uuid)
        groups.refreshFavOrder()   // M541
        // M541: in the Favourites group the list has changed (channel removed / renumbering)
        if (LivePlaylist.activeGroupKey == LivePlaylist.GROUP_FAV) {
            if (LivePlaylist.favChannels().isEmpty()) groups.apply(LivePlaylist.GROUP_ALL) else groups.apply(LivePlaylist.GROUP_FAV)
            navIndex.value = navIndex.value.coerceIn(0, (live.uuids.size - 1).coerceAtLeast(0))
        }
        if (announce) {
            Toast.makeText(ctx, ctx.getString(if (nowFav) R.string.fav_added else R.string.fav_removed, ch.name), Toast.LENGTH_SHORT).show()
        }
    }

    fun activate(key: String) {
        val idx = idxState.value
        val ch = live.channelsState.value.getOrNull(idx)
        close()
        if (ch == null) return
        when (key) {
            "info" -> actions.showInfo(idx)                       // programme detail directly in the player
            "fromstart" -> {
                val rec = recInProgress().let { it[ch.uuid] ?: it[ch.name] }
                if (rec != null) actions.playFromStart(rec, ch.nowStart, ch.nowStop)
                else if (idx != live.index) actions.switchTo(idx)   // if the channel is not playing yet and is not archive -> at least switch to live
            }
            "lock" -> actions.toggleLock(idx)                     // already handles the PIN + grace window
            "fav" -> toggleFavoriteAt(idx, announce = false)
            "rec" -> eventOf(ch)?.let { actions.record(ch, it) }   // M607
            "reorder" -> actions.enterReorder()   // M541
            "unhide" -> unhide(ch, idx)
            "hide" -> hide(ch, idx)
        }
    }

    /** M541: unhide a channel — back among all channels (by number), out of Hidden. */
    private fun unhide(ch: LivePlaylist.LiveChannel, idx: Int) {
        val sid = serverId() ?: return
        HiddenChannels.setHidden(ctx, sid, ch.uuid, false)
        LivePlaylist.hiddenChannels = LivePlaylist.hiddenChannels.filter { it.uuid != ch.uuid }
        if (LivePlaylist.allChannels.none { it.uuid == ch.uuid }) {
            LivePlaylist.allChannels = (LivePlaylist.allChannels + ch)
                .sortedWith(compareBy({ if (it.number > 0) it.number else Int.MAX_VALUE }, { it.name.lowercase() }))
        }
        if (LivePlaylist.activeGroupKey == LivePlaylist.GROUP_HIDDEN) {
            if (LivePlaylist.hiddenChannels.isEmpty()) groups.apply(LivePlaylist.GROUP_ALL)
            else {
                groups.apply(LivePlaylist.GROUP_HIDDEN)
                navIndex.value = idx.coerceIn(0, (live.uuids.size - 1).coerceAtLeast(0))
            }
        } else if (LivePlaylist.activeGroupKey == LivePlaylist.GROUP_ALL) {
            groups.apply(LivePlaylist.GROUP_ALL)   // an unhidden channel appears in its place
            navIndex.value = live.uuids.indexOf(ch.uuid).coerceAtLeast(0)
        }
    }

    private fun hide(ch: LivePlaylist.LiveChannel, idx: Int) {
        val sid = serverId() ?: return
        HiddenChannels.setHidden(ctx, sid, ch.uuid, true)
        // M541: move to the hidden list (pseudo-group), out of all.
        // Take the original number from allChannels (in Favourites `ch.number` is the position 1..n).
        val orig = LivePlaylist.allChannels.firstOrNull { it.uuid == ch.uuid } ?: ch
        LivePlaylist.allChannels = LivePlaylist.allChannels.filter { it.uuid != ch.uuid }
        if (LivePlaylist.hiddenChannels.none { it.uuid == ch.uuid }) {
            LivePlaylist.hiddenChannels = LivePlaylist.hiddenChannels + orig
        }
        // Remove a hidden channel from the zapping list right away (if it is not playing);
        // shift liveIndex so that CH+/- still match.
        if (idx != live.index) {
            val cur = live.channelsState.value.toMutableList()
            if (idx in cur.indices) {
                cur.removeAt(idx)
                live.channelsState.value = cur
                LivePlaylist.channels = cur
                live.uuids = cur.map { it.uuid }
                live.names = cur.map { it.name }
                if (idx < live.index) live.index--
                live.indexState.value = live.index
                navIndex.value = navIndex.value.coerceIn(0, (cur.size - 1).coerceAtLeast(0))
            }
        }
    }

    /** Keys while the menu is open: up/down + OK (on release) + BACK/LEFT. Always consumes. */
    fun handleKey(kc: Int, down: Boolean): Boolean {
        val ks = keys(idxState.value)
        val cnt = ks.size
        val isOk = kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (isOk) {
            if (okLongFired()) return true                 // swallow the up from the opening long-press
            if (!down && cnt > 0) activate(ks.getOrElse(selState.value) { ks.first() })
            return true                                    // OK activates only on release
        }
        if (down && cnt > 0) {
            when (kc) {
                KeyEvent.KEYCODE_DPAD_UP -> { selState.value = (selState.value - 1 + cnt) % cnt; return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { selState.value = (selState.value + 1) % cnt; return true }
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> { close(); return true }
            }
        }
        return true
    }
}
