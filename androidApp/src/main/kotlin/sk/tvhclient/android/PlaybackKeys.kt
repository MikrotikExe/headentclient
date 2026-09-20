package sk.tvhclient.android

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.MutableState

/**
 * M651: keys during normal playback (block 4 of dispatchKeyEvent), split out of PlayerActivity.
 *
 * Called only after all the overlays (dialogs, channel list, menu, modern overlay…),
 * once the player has been created. Handles: zapping (CH+/-, Page+/-, up/down arrows on live
 * broadcast), digits = channel selection by number (+ OK confirms immediately), control bar navigation
 * (D-pad + OK, and on the archive also smooth seeking with the cursor — M597/M598) and the keys with the
 * controls hidden (OK = play/pause, channel list or modern overlay; arrows = bar/seeking).
 *
 * [handleKey] returns true/false when it handled the key, or null = let the system have it
 * (super.dispatchKeyEvent: volume, BACK for the Compose BackHandler…). Both the logic and the order
 * of the conditions are identical to the original block.
 */
internal class PlaybackKeys(
    private val ctx: Context,
    private val live: LiveSession,
    private val scrub: ScrubController,
    private val numEntry: ChannelNumberEntry,
    private val controlNav: MutableState<Int>,
    private val seekable: () -> Boolean,
    private val controlsShown: () -> Boolean,
    private val modernTvActive: () -> Boolean,
    /** Order of the control bar's items for the given state (playerControlOrder). */
    private val controlOrder: (canZap: Boolean) -> List<String>,
    private val actions: Actions
) {
    interface Actions {
        fun switchLive(delta: Int)
        fun showZapBar()
        fun openModernOverlayAtCurrent()
        fun openModernOverlay()
        fun showControlsFocused()
        fun pokeControls()
        fun activateControl(id: String?)
        fun togglePlayPause()
        /** Opens the channel list and sets okLongFired (swallows the OK-up). */
        fun openChannelListLong()
        /** Modern TV overlay: short OK -> overlay on UP, hold -> list (M328/M642). */
        fun modernPlaybackOk(down: Boolean, event: KeyEvent): Boolean
        fun beginScrub(dir: Int)
        fun initScrub()
    }

    private fun afterZap() {
        if (!ZapOverlayPref.get(ctx)) actions.showZapBar()
        else if (modernTvActive()) actions.openModernOverlayAtCurrent()
        else actions.showControlsFocused()
    }

    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean? {
        val seekablePlayback = seekable()
        // M598-fix2: releasing the arrow ends the smooth seeking and schedules the jump
        if (!down && seekablePlayback &&
            (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT) &&
            scrub.holding
        ) { scrub.stopHold(); return true }
        val canZap = !seekablePlayback && live.uuids.size > 1
        // channel switching: Channel+/-, Page+/-, and the up/down arrows = zap
        // M407-fix: CH+/- and Page+/- no longer filter on repeatCount — thanks to the debounce
        // in switchLive() fast presses only move the target and the loading starts only once
        // it stops, so switching stays brisk even with the bar shown and also
        // when held / clicked rapidly. D-pad up/down stays on the first press
        // (repeatCount==0), because there it collides with navigation in the bar.
        val zapDelta = when (kc) {
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> if (down && canZap) +1 else 0
            KeyEvent.KEYCODE_DPAD_UP -> if (down && canZap && event.repeatCount == 0) +1 else 0
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> if (down && canZap) -1 else 0
            KeyEvent.KEYCODE_DPAD_DOWN -> if (down && canZap && event.repeatCount == 0) -1 else 0
            else -> 0
        }
        if (zapDelta != 0) {
            actions.switchLive(zapDelta)
            afterZap()
            return true
        }
        // digits 0-9 (the numeric keypad too) = channel selection by number
        val digit = when (kc) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> kc - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> kc - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        if (digit >= 0) { if (down && live.uuids.isNotEmpty()) numEntry.digit(digit); return true }
        // a partly typed channel number + OK => confirm immediately (faster switching,
        // no need to wait for the 1.5 s timer)
        if (numEntry.isPending && DialogKeys.isOk(kc)) {
            if (down && event.repeatCount == 0) numEntry.commitNow()
            return true
        }
        // controls shown -> left/right navigate the panel, OK activates
        // the highlighted item (up/down switch the channel — handled above)
        if (controlsShown()) {
            val order = controlOrder(canZap)
            val n = order.size
            fun moveNav(delta: Int) {
                controlNav.value = (controlNav.value + delta + n) % n
                if (order.getOrNull(controlNav.value) == "seek") actions.initScrub()
            }
            if (seekablePlayback) {
                val onSeek = order.getOrNull(controlNav.value) == "seek"
                when (kc) {
                    KeyEvent.KEYCODE_DPAD_UP -> if (down) {
                        scrub.cancelAuto()   // M597
                        moveNav(-1)
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                        scrub.cancelAuto()   // M597
                        moveNav(+1)
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                        if (onSeek) {
                            if (event.repeatCount == 0) scrub.tapOrHold(-1)   // M598-fix2/fix4: click + smooth hold
                        } else {
                            scrub.cancelAuto()
                            moveNav(-1)
                        }
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                        if (onSeek) {
                            if (event.repeatCount == 0) scrub.tapOrHold(+1)   // M598-fix2/fix4: click + smooth hold
                        } else {
                            scrub.cancelAuto()
                            moveNav(+1)
                        }
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (down && event.repeatCount == 0) {
                            if (onSeek) {
                                scrub.commit()   // M597: OK confirms immediately (the same path)
                                actions.pokeControls()
                            } else actions.activateControl(order.getOrNull(controlNav.value))
                        }
                        return true
                    }
                }
            } else {
                // live: left/right navigate the panel (up/down switch the channel — handled above)
                when (kc) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                        controlNav.value = (controlNav.value - 1 + n) % n
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                        controlNav.value = (controlNav.value + 1) % n
                        actions.pokeControls(); return true
                    }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (down && event.repeatCount == 0) actions.activateControl(order.getOrNull(controlNav.value))
                        return true
                    }
                }
            }
            // BACK we leave to the Compose BackHandler (it hides the controls); volume/the rest too
            return null
        }
        // controls hidden
        when (kc) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (seekablePlayback) {
                    if (down && event.repeatCount == 0) { actions.togglePlayPause(); actions.showControlsFocused() }
                    return true
                }
                if (modernTvActive()) {
                    // Short OK -> overlay only on UP; holding -> straight to the big list (M328, M642).
                    return actions.modernPlaybackOk(down, event)
                }
                if (down && event.repeatCount == 0) {
                    actions.openChannelListLong()  // okLongFired swallows the following OK-up
                    return true
                }
                if (down) return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                if (seekablePlayback) {
                    if (event.repeatCount == 0) {
                        if (!scrub.continues(-1)) actions.beginScrub(-1)   // M598-fix4
                        scrub.startHold(-1)
                    }
                    return true
                }
                if (modernTvActive()) actions.openModernOverlay() else actions.showControlsFocused(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                if (seekablePlayback) {
                    if (event.repeatCount == 0) {
                        if (!scrub.continues(+1)) actions.beginScrub(+1)   // M598-fix4
                        scrub.startHold(+1)
                    }
                    return true
                }
                if (modernTvActive()) actions.openModernOverlay() else actions.showControlsFocused(); return true
            }
            // up/down only get here when zapping is not possible (e.g. DVR) -> open the panel
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN ->
                if (down) { actions.showControlsFocused(); return true }
        }
        return null
    }
}
