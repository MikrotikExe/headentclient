package sk.tvhclient.android

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.MutableState

/**
 * M658: the chain of guards for the player's dispatchKeyEvent, extracted from PlayerActivity.
 *
 * The order of the guards IS behaviour: diagnostics → search (field) → teletext → PIN → dialogs
 * (resume, DVR profile, archive, OK-up after a long OK) → context menu → info → exit
 * confirmation → special keys (EPG, subtitles, audio, MENU, INFO, media) → channel list
 * → options → track menu → modern overlay → normal playback ([PlaybackKeys]).
 *
 * [handle] returns true/false when it has handled the key, or null = the activity calls
 * super.dispatchKeyEvent(event) (volume, BACK for the Compose BackHandler…).
 * The logic and the order of the conditions are identical to the original block.
 */
internal class PlayerKeyRouter(
    private val ctx: Context,
    private val search: ChannelSearch,
    private val ttx: TeletextController,
    private val pin: PinPrompt,
    private val ctxMenu: ChannelContextMenu,
    private val info: ChannelInfo,
    private val listKeys: ChannelListKeys,
    private val modernOv: ModernOverlayController,
    private val tracks: TrackState,
    private val sleep: SleepTimer,
    private val engine: VlcEngine,
    private val playbackKeys: PlaybackKeys,
    private val resumePromptState: MutableState<Boolean>,
    private val resumeSelState: MutableState<Int>,
    private val resumeAnswerState: MutableState<Int>,
    private val dvrAskState: MutableState<List<String>>,
    private val dvrAskSelState: MutableState<Int>,
    private val archiveChoiceIdxState: MutableState<Int>,
    private val archiveChoiceSelState: MutableState<Int>,
    private val exitConfirmState: MutableState<Boolean>,
    private val exitConfirmSelState: MutableState<Int>,
    private val isPlayingState: MutableState<Boolean>,
    private val optionsNavState: MutableState<Int>,
    private val actions: Actions
) {
    interface Actions {
        val remoteDebug: Boolean
        var okLongFired: Boolean
        val seekablePlayback: Boolean
        val liveIndex: Int
        val channelListOpen: Boolean
        val returnLiveUuid: String?
        val optionsOpen: Boolean
        val trackMenuOpen: Boolean
        val htspStream: Boolean
        fun isCommonKey(kc: Int): Boolean
        fun resolveDvrAsk(name: String?)
        fun resolveArchiveChoice(fromStart: Boolean)
        fun finish()
        fun openEpgInApp()
        fun openSpuMenu()
        fun openAudioMenu()
        fun modernTvActive(): Boolean
        fun openModernOverlay()
        fun showControlsFocused()
        fun toggleInfo()
        fun togglePlayPause()
        fun pokeControls()
        fun scrubSeek(seconds: Int)
        fun toggleFavoriteAt(idx: Int, announce: Boolean)
        fun closePlayer()
        fun openChannelList()
        fun seekRelative(deltaMs: Long)
        fun selectOption(idx: Int)
        fun closeOptions()
        fun selectTrackAtNav()
        fun closeTrackMenu()
        /** The activity's whole dispatchKeyEvent (recursion for MEDIA_NEXT/PREV -> CH+/-). */
        fun dispatchKeyEvent(event: KeyEvent): Boolean
    }

    fun handle(kc: Int, down: Boolean, event: KeyEvent): Boolean? {
        // DIAGNOSTICS (optional in settings): code for an unusual key
        if (actions.remoteDebug && down && !actions.isCommonKey(kc)) {
            val keyCodeStr = "Key code: $kc (${KeyEvent.keyCodeToString(kc)})"
            Toast.makeText(
                ctx,
                keyCodeStr,
                Toast.LENGTH_SHORT
            ).show()
            Log.d("HEADEND", keyCodeStr)
        }

        // M370: active search with focus on the text field -> the text is handled by the system/IME;
        // we only capture BACK (close search) and DOWN (move to the results).
        if (search.isActive && search.fieldFocusedState.value) {
            if (search.handleFieldKey(kc, down)) return true
            return null
        }

        // M553: an open teletext takes all keys except volume
        if (ttx.openState.value) {
            if (ttx.handleKey(kc, down, event)) return true
            return null
        }
        // M553: the TEXT key on the remote opens teletext directly
        if (kc == KeyEvent.KEYCODE_TV_TELETEXT && down && ttx.visible()) {
            ttx.open(); return true
        }

        // 0) Parental lock PIN -> digits, D-pad grid, CH+/- during the prompt (PinPrompt, M629)
        if (pin.isOpen) return pin.handleKey(kc, down, event)

        // 0a) "Resume playback" dialog -> left/right arrows + OK are handled by us (on a box it has no focus otherwise)
        if (resumePromptState.value) return DialogKeys.twoChoice(kc, down, event, resumeSelState,
            onOk = { sel -> resumeAnswerState.value = if (sel == 1) 1 else 2 },
            onBack = { resumeAnswerState.value = 2 })

        // 0a2) M606: DVR profile selection -> up/down + OK + BACK are handled by us
        if (dvrAskState.value.isNotEmpty()) return DialogKeys.verticalList(kc, down, event,
            count = dvrAskState.value.size, sel = dvrAskSelState, okFirstPressOnly = true,
            // M606-fix: the OK-up after closing the dialog otherwise reached the channel list and confirmed (started) the selected channel
            onOk = { actions.okLongFired = true; actions.resolveDvrAsk(dvrAskState.value.getOrNull(dvrAskSelState.value)) },
            onBack = { actions.resolveDvrAsk(null) })
        // 0b) Selection for an archived channel -> left/right arrows + OK + BACK are handled by us
        if (archiveChoiceIdxState.value >= 0) return DialogKeys.twoChoice(kc, down, event, archiveChoiceSelState,
            onOk = { sel -> actions.resolveArchiveChoice(sel == 1) },
            onBack = { archiveChoiceIdxState.value = -1 })
        val okKey = kc == KeyEvent.KEYCODE_DPAD_CENTER ||
            kc == KeyEvent.KEYCODE_ENTER ||
            kc == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (okKey && !down && actions.okLongFired) { actions.okLongFired = false; return true }

        // 0c) Channel context menu (long-press in the list) -> up/down + OK (on release) + BACK (M641)
        if (ctxMenu.isOpen) return ctxMenu.handleKey(kc, down)

        // 0d) Programme info (detail) -> any OK/BACK/left closes it (M643: ChannelInfo)
        if (info.isOpen) return info.handleKey(kc, down)

        // 0e) Confirmation of ending live playback (BACK) -> arrows + OK + BACK are handled by us
        if (exitConfirmState.value) return DialogKeys.twoChoice(kc, down, event, exitConfirmSelState,
            onOk = { sel -> if (sel == 1) actions.finish() else exitConfirmState.value = false },
            onBack = { exitConfirmState.value = false })

        if (down) {
            when (kc) {
                // EPG keys of various remotes (M345)
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_TV_DATA_SERVICE,
                KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU -> { actions.openEpgInApp(); return true }
                // Subtitle key -> subtitles (previously it opened the EPG by mistake)
                KeyEvent.KEYCODE_CAPTIONS -> { actions.openSpuMenu(); return true }
                // Audio key (on many TV/box remotes) -> audio tracks
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { actions.openAudioMenu(); return true }
                // MENU key -> OSD/controls during playback
                KeyEvent.KEYCODE_MENU -> {
                    if (actions.modernTvActive()) actions.openModernOverlay() else actions.showControlsFocused()
                    return true
                }
                KeyEvent.KEYCODE_INFO -> { actions.toggleInfo(); return true }
                // M577 (issue #11): media keys on the remote — STOP stops playback
                // (like Back without PiP and without confirmation), PLAY/PAUSE/PLAY_PAUSE control the pause,
                // RW/FF jump within a recording and in timeshift too, NEXT/PREV = next/previous channel
                // (within a recording a jump by a minute)
                KeyEvent.KEYCODE_MEDIA_STOP -> { LastPlayback.clear(ctx); actions.finish(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { actions.togglePlayPause(); actions.pokeControls(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!isPlayingState.value) { actions.togglePlayPause(); actions.pokeControls() }; return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (isPlayingState.value) { actions.togglePlayPause(); actions.pokeControls() }; return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { actions.scrubSeek(-30); actions.pokeControls(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { actions.scrubSeek(+30); actions.pokeControls(); return true }
                // M579: BOOKMARK (Google TV remotes) = add/remove the currently playing channel
                // from favourites; the TV key = back from a recording to the live channel, otherwise the channel list
                KeyEvent.KEYCODE_BOOKMARK -> {
                    if (!actions.seekablePlayback && actions.liveIndex >= 0 && !actions.channelListOpen) { actions.toggleFavoriteAt(actions.liveIndex, announce = true); return true }
                }
                KeyEvent.KEYCODE_TV -> {
                    if (actions.seekablePlayback && actions.returnLiveUuid != null) { actions.closePlayer(); return true }
                    if (!actions.seekablePlayback && !actions.channelListOpen) { actions.openChannelList(); return true }
                }
                KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    val fwd = kc == KeyEvent.KEYCODE_MEDIA_NEXT
                    if (actions.seekablePlayback) { actions.seekRelative(if (fwd) 60_000L else -60_000L); actions.pokeControls(); return true }
                    // live channel: the same handling as CH+/CH- (list, PIN, zapping bar)
                    val mapped = KeyEvent(
                        event.downTime, event.eventTime, event.action,
                        if (fwd) KeyEvent.KEYCODE_CHANNEL_UP else KeyEvent.KEYCODE_CHANNEL_DOWN,
                        event.repeatCount
                    )
                    return actions.dispatchKeyEvent(mapped)
                }
            }
        }

        // 1) The channel list is open -> we do the navigating (M645: ChannelListKeys)
        if (actions.channelListOpen) {
            if (listKeys.handleKey(kc, down, event)) return true
            return null   // volume
        }

        // 2) The sleep timer selection is open -> vertical navigation
        if (actions.optionsOpen) {
            if (DialogKeys.verticalList(kc, down, event, count = sleep.durations.size, sel = optionsNavState,
                    leftCloses = true, passVolume = true,
                    onOk = { actions.selectOption(optionsNavState.value) }, onBack = { actions.closeOptions() })) return true
            return null
        }

        // 3) The track menu is open (audio/subtitles) -> we do the navigating (up/down + OK)
        if (actions.trackMenuOpen) {
            if (DialogKeys.verticalList(kc, down, event, count = tracks.menuIds(actions.htspStream).size, sel = tracks.navIndex,
                    leftCloses = true, passVolume = true,
                    onOk = { actions.selectTrackAtNav() }, onBack = { actions.closeTrackMenu() })) return true
            return null
        }

        // 3b0) The "More" menu above the modern overlay (M327) — ModernOverlayController (M642)
        if (modernOv.isMoreOpen) return modernOv.handleMoreKey(kc, down, event)
        // 3b) The modern TV overlay (channel cards + control bar) -> we do the navigating (M642)
        if (modernOv.isOpen) {
            if (modernOv.handleKey(kc, down, event)) return true
            return null   // volume
        }

        // 4) Normal playback (M651: PlaybackKeys.kt)
        if (engine.ready) {
            playbackKeys.handleKey(kc, down, event)?.let { return it }
        }
        return null
    }
}
