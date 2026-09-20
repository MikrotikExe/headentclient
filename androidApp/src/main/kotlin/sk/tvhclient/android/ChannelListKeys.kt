package sk.tvhclient.android

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import androidx.compose.runtime.MutableState

/**
 * M645: keys of the open channel list in the player (split out of dispatchKeyEvent).
 * The order of the guards is the behaviour: search (results) → group pill (M369) → reorder
 * mode (M541) → OK (protective window after opening, holding = channel menu,
 * "single OK" mode M596) → navigation (up from the top = the pill; left/right by 7; BACK).
 * Returns false only for volume (let it go to the system), otherwise always consumes.
 */
internal class ChannelListKeys(
    private val ctx: Context,
    private val live: LiveSession,
    private val groups: LiveGroups,
    private val search: ChannelSearch,
    private val reorder: FavReorder,
    private val navIndex: MutableState<Int>,
    private val groupPicker: MutableState<Boolean>,
    private val actions: Actions
) {
    interface Actions {
        var okLongFired: Boolean
        fun openContextMenu(idx: Int)
        fun closeList()
        /** M600-fix: "single OK" — the list closes and the switch comes with a delay (video back to full screen). */
        fun switchDelayed(idx: Int)
        fun selectOrArchive(idx: Int)
        /** OK on an already playing channel: close the list and show the controls / the modern overlay. */
        fun reselectCurrent()
    }

    /** When the list was opened — OK events right after opening (leftovers of the opening long
     *  press, ghost DOWN/UP pairs from IR/CEC remotes) are ignored (M330-fix2). */
    var openedAt = 0L

    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        val n = live.uuids.size
        // M370: active search, focus on the results (the field handles the early bypass in the activity)
        if (search.isActive) {
            if (search.handleResultsKey(kc, down)) return true
            return !DialogKeys.isVolume(kc)
        }
        val isOk = DialogKeys.isOk(kc)
        // M369: focus on the group pill (above the list) — LEFT/RIGHT changes the group,
        // DOWN/OK back to the list, UP search, BACK closes the pill.
        if (groupPicker.value) {
            if (down) when (kc) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { groups.cycle(-1); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { groups.cycle(+1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER -> { groupPicker.value = false; return true }
                KeyEvent.KEYCODE_DPAD_UP -> { search.open(); return true }
                KeyEvent.KEYCODE_BACK -> { groupPicker.value = false; return true }
            }
            return !DialogKeys.isVolume(kc)
        }
        // M541: the favourites reorder mode takes precedence over ordinary navigation
        if (reorder.active) {
            if (DialogKeys.isVolume(kc)) return false
            reorder.handleKey(kc, down, isOk)
            return true
        }
        if (isOk) {
            // do not react while the opening OK (and its repeats) is held
            if (actions.okLongFired) return true
            // M596: "single OK" mode — a shorter protective window and an immediate switch
            val oneOk = OneOkPref.get(ctx)
            // debounce after opening: some remotes (IR/CEC) send another ghost DOWN/UP
            // pair after a long press — that would immediately confirm the channel
            // and close the list; every OK within 400 ms of opening is discarded
            val guardMs = if (oneOk) 150L else 400L
            if (SystemClock.uptimeMillis() - openedAt < guardMs) return true
            if (down) {
                // holding OK in the list = the channel context menu (Info / from start / lock)
                if (event.isLongPress && n > 0) {
                    actions.okLongFired = true               // the OK-up is then swallowed (it does not select a channel)
                    actions.openContextMenu(navIndex.value)
                    return true
                }
                return true                          // do not select on DOWN (we wait for the release)
            } else if (n > 0) {
                if (oneOk) {
                    // M596-fix/M596-fix2/M600-fix: a single OK = the channel straight to full screen,
                    // it switches only on RELEASE and with a delay after the list closes
                    val idx = navIndex.value
                    actions.closeList()
                    if (idx != live.index) actions.switchDelayed(idx)
                    return true
                }
                // releasing OK (a short click) = selecting/switching the channel
                if (navIndex.value == live.indexState.value) actions.reselectCurrent()
                else actions.selectOrArchive(navIndex.value)
            }
            return true
        }
        if (down && n > 0) when (kc) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // UP from the top of the list -> focus on the group pill (if there are any groups)
                if (navIndex.value == 0 && groups.keys().size > 1) groupPicker.value = true
                else navIndex.value = (navIndex.value - 1 + n) % n
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> { navIndex.value = (navIndex.value + 1) % n; return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { navIndex.value = (navIndex.value - 7).coerceIn(0, n - 1); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { navIndex.value = (navIndex.value + 7).coerceIn(0, n - 1); return true }
            KeyEvent.KEYCODE_BACK -> { actions.closeList(); return true }
        }
        return !DialogKeys.isVolume(kc)
    }
}
