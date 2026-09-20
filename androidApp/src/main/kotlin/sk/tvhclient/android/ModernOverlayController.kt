package sk.tvhclient.android

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf

/**
 * M327 / M328 / M642: modern TV overlay (channel cards + control bar) and its
 * "More" menu — state and keys, split out of PlayerActivity. Drawing is done by ModernTvOverlay
 * and ModernMoreMenu in PlayerUi; only signals (poke/exec) and indices come here.
 *
 * [live] supplies the channel count and the current index; [seekable]/[timeshiftEngaged] affect
 * the bar's items; actions go through [Actions] (playback, switching, list, menu…).
 */
internal class ModernOverlayController(
    private val live: LiveSession,
    private val seekable: () -> Boolean,
    private val timeshiftEngaged: () -> Boolean,
    private val profileSwitchAvailable: () -> Boolean,
    private val dvrRecordVisible: () -> Boolean,
    private val teletextVisible: () -> Boolean,
    private val actions: Actions
) {
    interface Actions {
        fun hideZapBar()
        fun togglePlayPause()
        fun timeshiftSkip(seconds: Int)
        fun switchLive(dir: Int)
        fun openChannelList()
        fun openSleepMenu()
        fun toggleInfo()
        fun openProfileMenu()
        fun toggleRecordCurrent()
        fun openTeletext()
        /** Holding OK in the overlay = the focused channel's menu (M338). The activity sets okLongFired. */
        fun openChannelContextMenu(cardIndex: Int)
    }

    val visible = mutableStateOf(false)
    val row = mutableStateOf(0)      // 0 = cards, 1 = bar
    val card = mutableStateOf(0)
    val strip = mutableStateOf(0)
    val poke = mutableStateOf(0)
    val exec = mutableStateOf(0)     // signal for the composable
    val execId = mutableStateOf("")
    private var okLong = false
    // OK from playback: we open the overlay only on OK-UP, so it does not flash when OK is held (M328)
    private var okPending = false

    // The bar's "More" menu (M327): less used items — headroom for longer translations
    val moreVisible = mutableStateOf(false)
    val moreIdx = mutableStateOf(0)

    val isOpen: Boolean get() = visible.value
    val isMoreOpen: Boolean get() = moreVisible.value

    /** Items of the overlay's control bar (transport in the middle; seeking only with timeshift). */
    fun stripIds(): List<String> = buildList {
        add("epg"); add("audio")
        // channel switching straight from the bar (M323) — only on live with several channels
        val zap = !seekable() && live.uuids.size > 1
        if (zap) add("chprev")
        if (timeshiftEngaged()) add("tsrew")
        add("play")
        if (timeshiftEngaged()) add("tsff")
        if (zap) add("chnext")
        add("subs"); add("more")
    }

    // M383: "profile" is added only when the switcher is available (HTTP live)
    fun moreIds(): List<String> = buildList {
        add("list"); add("sleep"); add("info")
        if (profileSwitchAvailable()) add("profile")
        if (dvrRecordVisible()) add("rec")   // M490
        if (teletextVisible()) add("teletext")   // M553
    }

    fun moreActivate() {
        val id = moreIds().getOrNull(moreIdx.value) ?: return
        moreVisible.value = false
        when (id) {
            "list" -> { close(); actions.openChannelList() }
            "sleep" -> { close(); actions.openSleepMenu() }
            "info" -> { close(); actions.toggleInfo() }
            "profile" -> { close(); actions.openProfileMenu() }
            "rec" -> { close(); actions.toggleRecordCurrent() }   // M490
            "teletext" -> actions.openTeletext()   // M553
        }
    }

    fun morePick(i: Int) { moreIdx.value = i; moreActivate() }
    fun moreDismiss() { moreVisible.value = false }

    fun open() {
        actions.hideZapBar()  // M446
        card.value = live.indexState.value.coerceAtLeast(0)
        strip.value = stripIds().indexOf("play").coerceAtLeast(0)
        row.value = 0
        poke.value++
        visible.value = true
    }

    /** Opens the overlay with the card on the current channel (after a CH+/- switch with the overlay on). */
    fun openAtCurrent() { card.value = live.indexState.value.coerceAtLeast(0); open() }

    fun close() { visible.value = false }

    private fun syncCard() { card.value = live.indexState.value.coerceAtLeast(0); poke.value++ }

    /** OK in the overlay: card -> switch channel; bar -> run the action. */
    private fun activate() {
        if (row.value == 0) {
            execId.value = "card"; exec.value++
            close()
        } else when (stripIds().getOrNull(strip.value)) {
            "play" -> { actions.togglePlayPause(); poke.value++ }
            "tsrew" -> { actions.timeshiftSkip(-30); poke.value++ }
            "tsff" -> { actions.timeshiftSkip(+30); poke.value++ }
            "more" -> { moreIdx.value = 0; moreVisible.value = true }
            "chprev" -> { actions.switchLive(-1); syncCard() }
            "chnext" -> { actions.switchLive(+1); syncCard() }
            null -> {}
            else -> {
                execId.value = stripIds()[strip.value]; exec.value++
                close()
            }
        }
    }

    /** Keys of the "More" menu (M327). Always consumes. */
    fun handleMoreKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        if (down) when (kc) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { moreIdx.value = (moreIdx.value + 1) % moreIds().size; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { moreIdx.value = (moreIdx.value - 1 + moreIds().size) % moreIds().size; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) moreActivate(); return true
            }
            KeyEvent.KEYCODE_BACK -> { moreVisible.value = false; return true }
        }
        return true
    }

    /**
     * Keys of the open overlay. Returns false only for volume (let it go to the system),
     * everything else is consumed.
     */
    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        val ids = stripIds()
        if (down) {
            when (kc) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (row.value == 0) {
                        val n = live.uuids.size
                        if (n > 0) card.value = (card.value - 1 + n) % n
                    } else strip.value = (strip.value - 1 + ids.size) % ids.size
                    poke.value++; return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (row.value == 0) {
                        val n = live.uuids.size
                        if (n > 0) card.value = (card.value + 1) % n
                    } else strip.value = (strip.value + 1) % ids.size
                    poke.value++; return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (row.value == 0) {
                        row.value = 1
                        strip.value = ids.indexOf("play").coerceAtLeast(0)
                    }
                    poke.value++; return true
                }
                KeyEvent.KEYCODE_DPAD_UP -> {
                    if (row.value == 1) row.value = 0
                    poke.value++; return true
                }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 1) {
                        // Holding OK in the overlay = options for the FOCUSED channel
                        // (Info / Play from start / Lock) — M338. The big list
                        // stays reachable via More -> Channels and a long OK from plain playback.
                        okLong = false
                        actions.openChannelContextMenu(card.value)
                    }
                    return true
                }
                // M407-fix2: CH+/- and Page+/- switch the channel in the modern overlay too
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                    if (!seekable() && live.uuids.size > 1) { actions.switchLive(+1); syncCard() }
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                    if (!seekable() && live.uuids.size > 1) { actions.switchLive(-1); syncCard() }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { close(); return true }
            }
        } else {
            when (kc) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (!okLong) activate()
                    okLong = false
                    return true
                }
                KeyEvent.KEYCODE_BACK -> return true
            }
        }
        when (kc) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> return false
        }
        return true
    }

    /**
     * OK from plain playback on TV (M328): short OK -> overlay only on UP; holding
     * (repeatCount 1) -> straight to the big list with no overlay flash. Always consumes.
     * [onLongOpenList] is called by the activity (it sets okLongFired and opens the list).
     */
    fun handlePlaybackOk(down: Boolean, event: KeyEvent, onLongOpenList: () -> Unit): Boolean {
        if (down && event.repeatCount == 0) { okPending = true; return true }
        if (down && event.repeatCount == 1 && okPending) {
            okPending = false
            onLongOpenList()
            return true
        }
        if (down) return true
        if (okPending) { okPending = false; open() }
        return true
    }
}
