package sk.tvhclient.android

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

/**
 * M663: the chain of PlayerUi BackHandlers (extracted because of the 64 KB method limit). THE ORDER IS
 * BEHAVIOUR: the one composed first has the lowest priority, more specific handlers further down
 * take precedence. The conditions are 1:1 with the original code; writes to PlayerUi states go through setters.
 */
@Composable
internal fun PlayerBackHandlers(
    autoPipEnabled: Boolean,
    pipSupported: Boolean,
    playing: Boolean,
    seekable: Boolean,
    controlsVisible: Boolean,
    menu: String?,
    showChannelList: Boolean,
    showOptions: Boolean,
    showInfo: Boolean,
    returnLiveOnBack: Boolean,
    onEnterPip: () -> Unit,
    onClose: () -> Unit,
    onRequestExit: () -> Unit,
    setShowChannelList: (Boolean) -> Unit,
    setMenu: (String?) -> Unit,
    setControlsVisible: (Boolean) -> Unit
) {
    // phone: BACK from plain playback -> PiP (reveals the home screen), not exit.
    // composed first => has the lowest priority, more specific handlers further down take precedence.
    // governed by the automatic PiP setting.
    BackHandler(
        enabled = autoPipEnabled && pipSupported && playing && !controlsVisible && menu == null && !showChannelList && !showOptions
    ) { onEnterPip() }
    BackHandler(enabled = showChannelList) { setShowChannelList(false) }
    BackHandler(enabled = menu != null) { setMenu(null) }
    BackHandler(
        enabled = controlsVisible && menu == null && !showChannelList && !showOptions
    ) { setControlsVisible(false) }
    // "Play from start" from live TV: Back (when nothing is open) returns to the original live channel
    BackHandler(
        enabled = returnLiveOnBack && !controlsVisible && menu == null && !showChannelList && !showOptions
    ) { onClose() }
    // M280: BACK during plain live playback (outside PiP) -> exit confirmation (like exit in the menu),
    // so that an accidental Back press does not end playback immediately.
    // M280-fix: ONLY on TV (devices without PiP). On phone/tablet (pipSupported) the
    // confirmation is not shown at all — BACK there handles PiP / normal behaviour.
    // M537: "TV" MUST NOT be derived from !pipSupported — TV boxes with PiP (Homatics,
    // Shield, Raspberry Pi; see M429) got no confirmation and BACK ended
    // playback immediately. What decides is the UI mode (leanback): on TV the confirmation
    // is always shown, except when the auto-PiP handler above takes precedence
    // (auto-PiP enabled on a box with PiP -> BACK = thumbnail, as before).
    // (M537-fix: the computation is in a separate composable — PlayerUi is at the 64 KB method limit.)
    BackHandler(
        enabled = exitConfirmOnBack(pipSupported, autoPipEnabled) && !seekable && !controlsVisible && menu == null
                  && !showChannelList && !showOptions && !returnLiveOnBack && !showInfo
    ) { onRequestExit() }
}
