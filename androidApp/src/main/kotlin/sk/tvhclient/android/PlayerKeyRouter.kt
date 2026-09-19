package sk.tvhclient.android

import android.content.Context
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.runtime.MutableState

/**
 * M658: reťaz stráží dispatchKeyEvent prehrávača, vyclenená z PlayerActivity.
 *
 * Poradie stráží JE správanie: diagnostika → hľadanie (pole) → teletext → PIN → dialógy
 * (obnoviť, DVR profil, archív, OK-up po dlhom OK) → kontextové menu → info → potvrdenie
 * ukončenia → špeciálne klávesy (EPG, titulky, audio, MENU, INFO, mediálne) → zoznam kanálov
 * → možnosti → track menu → moderný overlay → bežné prehrávanie ([PlaybackKeys]).
 *
 * [handle] vráti true/false, keď kláves spracoval, alebo null = aktivita zavolá
 * super.dispatchKeyEvent(event) (hlasitosť, BACK pre Compose BackHandler…).
 * Logika aj poradie podmienok sú zhodné s pôvodným blokom.
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
        /** Celý dispatchKeyEvent aktivity (rekurzia pre MEDIA_NEXT/PREV -> CH+/-). */
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

        // M370: aktivne hladanie s fokusom na textovom poli -> text spracuje system/IME;
        // zachytime len BACK (zavri hladanie) a DOLE (prejdi na vysledky).
        if (search.isActive && search.fieldFocusedState.value) {
            if (search.handleFieldKey(kc, down)) return true
            return null
        }

        // M553: otvorený teletext berie všetky klávesy okrem hlasitosti
        if (ttx.openState.value) {
            if (ttx.handleKey(kc, down, event)) return true
            return null
        }
        // M553: kláves TEXT na diaľkovom otvorí teletext priamo
        if (kc == KeyEvent.KEYCODE_TV_TELETEXT && down && ttx.visible()) {
            ttx.open(); return true
        }

        // 0) PIN rodicovskeho zamku -> cislice, D-pad mriezka, CH+/- pocas vyzvy (PinPrompt, M629)
        if (pin.isOpen) return pin.handleKey(kc, down, event)

        // 0a) Dialog "Obnovit prehravanie" -> sipky vlavo/vpravo + OK riesime my (na boxe inak bez fokusu)
        if (resumePromptState.value) return DialogKeys.twoChoice(kc, down, event, resumeSelState,
            onOk = { sel -> resumeAnswerState.value = if (sel == 1) 1 else 2 },
            onBack = { resumeAnswerState.value = 2 })

        // 0a2) M606: vyber DVR profilu -> hore/dole + OK + BACK riesime my
        if (dvrAskState.value.isNotEmpty()) return DialogKeys.verticalList(kc, down, event,
            count = dvrAskState.value.size, sel = dvrAskSelState, okFirstPressOnly = true,
            // M606-fix: OK-up po zatvoreni dialogu inak dorazil do zoznamu kanalov a potvrdil (spustil) vybrany kanal
            onOk = { actions.okLongFired = true; actions.resolveDvrAsk(dvrAskState.value.getOrNull(dvrAskSelState.value)) },
            onBack = { actions.resolveDvrAsk(null) })
        // 0b) Vyber pri archivovanom kanali -> sipky vlavo/vpravo + OK + BACK riesime my
        if (archiveChoiceIdxState.value >= 0) return DialogKeys.twoChoice(kc, down, event, archiveChoiceSelState,
            onOk = { sel -> actions.resolveArchiveChoice(sel == 1) },
            onBack = { archiveChoiceIdxState.value = -1 })
        val okKey = kc == KeyEvent.KEYCODE_DPAD_CENTER ||
            kc == KeyEvent.KEYCODE_ENTER ||
            kc == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (okKey && !down && actions.okLongFired) { actions.okLongFired = false; return true }

        // 0c) Kontextove menu kanala (long-press v zozname) -> hore/dole + OK (na uvolnenie) + BACK (M641)
        if (ctxMenu.isOpen) return ctxMenu.handleKey(kc, down)

        // 0d) Info o relacii (detail) -> hociktore OK/BACK/vlavo zatvori (M643: ChannelInfo)
        if (info.isOpen) return info.handleKey(kc, down)

        // 0e) Potvrdenie ukoncenia ziveho prehravania (BACK) -> sipky + OK + BACK riesime my
        if (exitConfirmState.value) return DialogKeys.twoChoice(kc, down, event, exitConfirmSelState,
            onOk = { sel -> if (sel == 1) actions.finish() else exitConfirmState.value = false },
            onBack = { exitConfirmState.value = false })

        if (down) {
            when (kc) {
                // EPG klavesy roznych ovladacov (M345)
                KeyEvent.KEYCODE_GUIDE,
                KeyEvent.KEYCODE_TV_DATA_SERVICE,
                KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU -> { actions.openEpgInApp(); return true }
                // Titulkovy klaves -> titulky (predtym omylom otvaral EPG)
                KeyEvent.KEYCODE_CAPTIONS -> { actions.openSpuMenu(); return true }
                // Audio klaves (na mnohych TV/box ovladacoch) -> zvukove stopy
                KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { actions.openAudioMenu(); return true }
                // MENU klaves -> OSD/ovladanie pocas prehravania
                KeyEvent.KEYCODE_MENU -> {
                    if (actions.modernTvActive()) actions.openModernOverlay() else actions.showControlsFocused()
                    return true
                }
                KeyEvent.KEYCODE_INFO -> { actions.toggleInfo(); return true }
                // M577 (issue #11): medialne klavesy dialkoveho — STOP zastavi prehravanie
                // (ako Spat bez PiP a bez potvrdenia), PLAY/PAUSE/PLAY_PAUSE ovladaju pauzu,
                // RW/FF skacu v nahravke aj v timeshifte, NEXT/PREV = dalsi/predosly kanal
                // (v nahravke skok o minutu)
                KeyEvent.KEYCODE_MEDIA_STOP -> { LastPlayback.clear(ctx); actions.finish(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { actions.togglePlayPause(); actions.pokeControls(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (!isPlayingState.value) { actions.togglePlayPause(); actions.pokeControls() }; return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (isPlayingState.value) { actions.togglePlayPause(); actions.pokeControls() }; return true }
                KeyEvent.KEYCODE_MEDIA_REWIND -> { actions.scrubSeek(-30); actions.pokeControls(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { actions.scrubSeek(+30); actions.pokeControls(); return true }
                // M579: ZALOZKA (Google TV ovladace) = pridat/odobrat prave hrajuci kanal
                // z oblubenych; TV klaves = z nahravky spat na zivy kanal, inak zoznam kanalov
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
                    // zivy kanal: rovnake spracovanie ako CH+/CH- (zoznam, PIN, zap bar)
                    val mapped = KeyEvent(
                        event.downTime, event.eventTime, event.action,
                        if (fwd) KeyEvent.KEYCODE_CHANNEL_UP else KeyEvent.KEYCODE_CHANNEL_DOWN,
                        event.repeatCount
                    )
                    return actions.dispatchKeyEvent(mapped)
                }
            }
        }

        // 1) Otvoreny zoznam kanalov -> navigujeme my (M645: ChannelListKeys)
        if (actions.channelListOpen) {
            if (listKeys.handleKey(kc, down, event)) return true
            return null   // hlasitost
        }

        // 2) Otvoreny vyber casovaca uspatia -> vertikalna navigacia
        if (actions.optionsOpen) {
            if (DialogKeys.verticalList(kc, down, event, count = sleep.durations.size, sel = optionsNavState,
                    leftCloses = true, passVolume = true,
                    onOk = { actions.selectOption(optionsNavState.value) }, onBack = { actions.closeOptions() })) return true
            return null
        }

        // 3) Otvorene track menu (audio/titulky) -> navigujeme my (hore/dole + OK)
        if (actions.trackMenuOpen) {
            if (DialogKeys.verticalList(kc, down, event, count = tracks.menuIds(actions.htspStream).size, sel = tracks.navIndex,
                    leftCloses = true, passVolume = true,
                    onOk = { actions.selectTrackAtNav() }, onBack = { actions.closeTrackMenu() })) return true
            return null
        }

        // 3b0) "Viac" menu nad modernym overlayom (M327) — ModernOverlayController (M642)
        if (modernOv.isMoreOpen) return modernOv.handleMoreKey(kc, down, event)
        // 3b) Moderny TV overlay (karty kanalov + ovladacia lista) -> navigujeme my (M642)
        if (modernOv.isOpen) {
            if (modernOv.handleKey(kc, down, event)) return true
            return null   // hlasitost
        }

        // 4) Bezne prehravanie (M651: PlaybackKeys.kt)
        if (engine.ready) {
            playbackKeys.handleKey(kc, down, event)?.let { return it }
        }
        return null
    }
}
