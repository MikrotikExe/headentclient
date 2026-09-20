package sk.tvhclient.android

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.MutableState

/**
 * M541 / M638: D-pad favourites reordering mode in the player's channel list
 * (split out of PlayerActivity). Switched on from the channel menu in the Favourites group. OK grabs /
 * drops the channel under the cursor, UP/DOWN move the grabbed channel (the order is saved immediately and
 * the list is renumbered), BACK ends the mode. The hint is in the group pill
 * ([groupLabel]) — no new UI code in PlayerUi.
 *
 * M541-fix: the grabbed row is distinguished by data — "↕" before the name and a hint
 * instead of the programme; the displayed state ([liveChannels]) is a copy, LivePlaylist.channels stays clean.
 */
class FavReorder(
    private val ctx: Context,
    private val serverId: () -> String?,
    private val liveUuids: () -> List<String>,
    private val liveChannels: MutableState<List<LivePlaylist.LiveChannel>>,
    private val navIndex: MutableState<Int>,
    private val groupLabel: MutableState<String>,
    private val groupLabelFor: (String) -> String,
    private val reapplyFavGroup: () -> Unit,
    private val okLongFired: () -> Boolean
) {
    var active = false
        private set
    private var grabbed = false

    fun enter() {
        if (LivePlaylist.activeGroupKey != LivePlaylist.GROUP_FAV) return
        active = true
        grabbed = false
        updateLabel()
    }

    fun exit() {
        if (!active) return
        active = false
        grabbed = false
        groupLabel.value = groupLabelFor(LivePlaylist.activeGroupKey)
        liveChannels.value = LivePlaylist.channels   // M541-fix: clear the decoration
    }

    private fun updateLabel() {
        groupLabel.value = if (grabbed) {
            val name = LivePlaylist.channels.getOrNull(navIndex.value)?.name ?: ""
            ctx.getString(R.string.fav_reorder_grabbed, name)
        } else ctx.getString(R.string.fav_reorder_hint)
        decorateGrabbedRow()
    }

    private fun decorateGrabbedRow() {
        val base = LivePlaylist.channels
        if (!active || !grabbed) { liveChannels.value = base; return }
        val g = navIndex.value
        val hint = ctx.getString(R.string.fav_reorder_row)
        liveChannels.value = base.mapIndexed { i, ch ->
            if (i == g) ch.copy(name = "↕ " + ch.name, nowTitle = hint) else ch
        }
    }

    /** Moves the grabbed channel by [dir] (+1 down / -1 up) in the favourites order. */
    private fun moveGrabbed(dir: Int) {
        val sid = serverId() ?: return
        val from = navIndex.value
        val to = from + dir
        val order = Favorites.list(ctx, sid)
        if (from !in order.indices || to !in order.indices) return
        // the displayed Favourites list = favOrder in that same order (favChannels),
        // but channels the server no longer has are missing from it -> map via uuid
        val uuid = liveChannels.value.getOrNull(from)?.uuid ?: return
        val target = liveChannels.value.getOrNull(to)?.uuid ?: return
        val fi = order.indexOf(uuid); val ti = order.indexOf(target)
        if (fi < 0 || ti < 0) return
        Favorites.move(ctx, sid, fi, ti)
        reapplyFavGroup()   // refreshFavOrder + applyGroup(FAV): renumbers 1..n and recomputes liveIndex
        navIndex.value = liveUuids().indexOf(uuid).coerceAtLeast(0)
        updateLabel()
    }

    /** Keys in reordering mode. Returns true if the event was handled. */
    fun handleKey(kc: Int, down: Boolean, isOk: Boolean): Boolean {
        if (!active) return false
        val n = liveUuids().size
        if (isOk) {
            if (okLongFired()) return true
            if (!down && n > 0) {
                grabbed = !grabbed
                updateLabel()
            }
            return true
        }
        if (!down) return true
        when (kc) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (grabbed) moveGrabbed(-1)
                else if (n > 0) navIndex.value = (navIndex.value - 1 + n) % n
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (grabbed) moveGrabbed(+1)
                else if (n > 0) navIndex.value = (navIndex.value + 1) % n
                return true
            }
            KeyEvent.KEYCODE_BACK -> { exit(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
        }
        return false
    }
}
