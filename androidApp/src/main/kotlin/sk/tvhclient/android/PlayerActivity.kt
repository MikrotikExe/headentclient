package sk.tvhclient.android

import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import sk.tvhclient.shared.Tvh
import kotlin.math.roundToInt

/**
 * Live prehravac na libVLC. Dekoduje MPEG-2 + MP2/AC3/EAC3/DTS softverovo.
 * Ovladanie je Compose overlay: play/pause, zavriet, vyber audio stopy
 * (jazyk) a titulkov (libVLC get/setAudioTrack, get/setSpuTrack).
 */
class PlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    // M656: libVLC + MediaPlayer zivotny cyklus vo VlcEngine.kt (M677: pristup priamo cez engine)
    private val engine: VlcEngine by lazy {
        VlcEngine(this, mediaFactory, vlcEvents,
            recreates = { stall.recreates },
            bumpSurfaceGen = { videoSurfaceGen.value = videoSurfaceGen.value + 1 },
            resetStall = { stall.reset() })
    }
    // M655: stav streamu (feedery, HTSP priznaky, URL) v StreamState.kt (M677: pristup priamo cez stream)
    private val stream = StreamState()
    // HTSP titulky: kompletny zoznam jazykov berieme z metadat (feeder.subtitleStreams),
    // nie z libVLC (to ma len jazyky, ktore uz "prehovorili"). Vyber mapujeme na realnu
    // libVLC stopu podla anglickeho nazvu jazyka (libVLC DVB titulky netaguje kodom).
    // M637: stav stop (zvuk/titulky/profil) v TrackState.kt
    private val tracks: TrackState by lazy {
        TrackState(this,
            player = { if (engine.ready && !engine.tornDown) engine.player else null },
            htspFeeder = { stream.htspFeeder })
    }
    // M392: stav titulkov spred restartu streamu pri zmene profilu (HTTP live) —
    // novy kontajner (napr. matroska) moze mat default titulkovu stopu, ktoru by
    // libVLC sam zapol; po restarte preto obnovime povodnu volbu pouzivatela.
    // M392-fix: trvala volba pouzivatela pre HTTP live titulky. Default OFF
    // (zhodne s HTSP, kde null = vypnute). Vynucuje sa pri kazdom ESAdded,
    // takze ani neskoro registrovana default stopa (matroska na pomalom boxe)
    // titulky nezapne. Rusi ju len rucne zapnutie v menu (D-pad aj dotyk).
    // M262: ci uz prebehlo urcenie HTSP rezimu pre toto sedenie. doPlay (startovacie
    // prehratie) ho nastavi; ak vsak pouzivatel prepne kanal este pred doPlay (napr.
    // odchod z PIN vyzvy zamknuteho kanala), inicializuje HTSP switchToIndex.
    private var htspInitDone = false
    // M647: HTSP timeshift v TimeshiftController.kt (M677: pristup priamo cez timeshift)
    private val timeshift: TimeshiftController by lazy {
        TimeshiftController(lifecycleScope,
            feeder = { stream.htspFeeder },
            onResumePlayback = {
                stream.htspFeeder?.resume()
                isPlayingState.value = true
                if (engine.ready && !engine.player.isPlaying) engine.player.play()
            },
            onSeekSpinner = { showSeekSpinner() })
    }
    // timeshift "zapnuty" (po prvej pauze) -> az vtedy davaju zmysel RW/FF a dvojklik

    // ===== Moderny TV overlay (karty kanalov + ovladacia lista) — ModernOverlayController.kt (M642) =====
    private val modernOv: ModernOverlayController by lazy {
        ModernOverlayController(live,
            seekable = { seekablePlayback },
            timeshiftEngaged = { timeshift.engaged.value },
            profileSwitchAvailable = { profileSwitchAvailable() },
            dvrRecordVisible = { dvrRecordVisible() },
            teletextVisible = { teletextVisible() },
            actions = object : ModernOverlayController.Actions {
                override fun hideZapBar() = zapBar.hide()
                override fun togglePlayPause() = this@PlayerActivity.togglePlayPause()
                override fun timeshiftSkip(seconds: Int) = this@PlayerActivity.timeshiftSkip(seconds)
                override fun switchLive(dir: Int) = this@PlayerActivity.switchLive(dir)
                override fun openChannelList() = this@PlayerActivity.openChannelList()
                override fun openSleepMenu() = this@PlayerActivity.openSleepMenu()
                override fun toggleInfo() = this@PlayerActivity.toggleInfo()
                override fun openProfileMenu() = trackMenu.openProfileMenu()
                override fun toggleRecordCurrent() = this@PlayerActivity.toggleRecordCurrent()
                override fun openTeletext() = this@PlayerActivity.openTeletext()
                override fun openChannelContextMenu(cardIndex: Int) {
                    okLongFired = true   // guard prehltne OK-up (inak by potvrdil polozku menu)
                    ctxMenu.open(cardIndex)
                }
            })
    }

    private val isTvBox by lazy {
        (getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Moderny overlay ma zmysel len na TV, v modernom rezime, pri zivom so zoznamom. */
    private fun modernTvActive(): Boolean =
        isTvBox && UiModePref.get(this) == UiModePref.MODERN &&
            !seekablePlayback && live.uuids.size > 1

    // ===== M490 / M669: nahravanie prave beziacej relacie — DvrRecordController.kt =====
    // Stav aj akcie zdielaju vsetky vstupy (klasicky bar, panel „Viac", TV overlay). M677: ostavaju
    // len verejne delegaty, ktore composables citaju cez dvrActivity; ostatne idu priamo cez dvrRec.
    private val dvrRec: DvrRecordController by lazy {
        DvrRecordController(this, lifecycleScope, live,
            epgUpcoming = epg.upcoming,
            recInProgressByChan = recInProgressByChan,
            refreshRecordingOnly = { epg.refreshRecordingOnly() })
    }
    val dvrExistingState: androidx.compose.runtime.MutableState<sk.tvhclient.shared.model.DvrEntry?> get() = dvrRec.existingState
    /** Ma sa ovladac nahravania vobec ukazat? */
    fun dvrRecordVisible(): Boolean = dvrRec.recordVisible()
    /** Zisti prava a stav nahravky pre prave sledovanu relaciu (start, prepnutie kanala). */
    fun refreshDvrState() { dvrRec.refreshState() }
    /** Nahrat prave beziacu relaciu, alebo zrusit uz naplanovanu nahravku. */
    fun toggleRecordCurrent() { dvrRec.toggleRecordCurrent() }

    // M639: stav ziveho prehravania v LiveSession (M677: pristup priamo cez live)
    private val live = LiveSession()
    // pre opatovne pripojenie videa po navrate z pozadia
    private var videoLayout: VLCVideoLayout? = null
    private var subOverlay: SubtitleOverlayView? = null
    // ===== M553 / M627: teletext — stav a ovládanie v TeletextController, vykreslenie v TeletextOverlay =====
    private val ttx: TeletextController by lazy {
        TeletextController(this,
            liveServer = { live.server },
            liveUuid = { live.uuidState.value },
            seekable = { seekablePlayback },
            onOpened = { modernOv.close() })
    }
    /** M552: teletext aktuálneho kanála (HTSP: dáta z feedera, HTTP: vlastná odbočka). */
    val teletext: TeletextSession get() = ttx.session
    fun teletextVisible(): Boolean = ttx.visible()
    fun openTeletext() { ttx.open() }
    fun closeTeletext() { ttx.close() }

    // Picture-in-Picture (obraz v obraze)
    private val inPipState = androidx.compose.runtime.mutableStateOf(false)
    // false = audio-only (rozhlas) -> zobraz logo namiesto ciernej
    // automaticke znovupripojenie zivého streamu po vypadku siete
    // M636: casovanie/stav reconnectu v ReconnectController.kt; co sa pri pokuse spravi, je nizsie
    private val reconnect: ReconnectController by lazy {
        ReconnectController(this,
            playerReady = { engine.ready },
            isPlaying = { engine.player.isPlaying })
    }
    // tocenie pri pretacani timeshiftu (kratky resync pipe -> libVLC)
    private val seekingState = androidx.compose.runtime.mutableStateOf(false)
    private var seekSpinnerJob: kotlinx.coroutines.Job? = null
    // YouTube-style dvojklik pretacanie: nazbierane sekundy (+/-), 0 = skryte
    // M648: vypocet ciela pretacania, M594 zotavenie a dvojklik v DvrSeek.kt
    private val dvrSeek: DvrSeek by lazy {
        DvrSeek(this, lifecycleScope,
            durMs = { if (dvr.durationMs > 0) dvr.durationMs else (if (engine.ready) engine.player.length else 0L) },
            recording = { dvr.recording },
            playheadMs = { dvr.playheadMsState.value },
            seekable = { engine.ready && seekablePlayback },
            performSeek = { target, from, dur -> seekDvrTo(target, from, dur) })
    }
    // M634: EPG now/next + cache v PlayerEpgStore.kt (M677: pristup priamo cez epg)
    private val epg: PlayerEpgStore by lazy {
        PlayerEpgStore(this,
            liveServer = { live.server },
            liveChannels = live.channelsState,
            recInProgress = recInProgressByChan,
            onDvrStateChanged = { refreshDvrState() })
    }

    // D-pad / diaľkové: signál na zobrazenie ovládania, info pre seek a sw dekóder
    private val controlsPokeState = androidx.compose.runtime.mutableStateOf(0)
    private val isPlayingState = androidx.compose.runtime.mutableStateOf(true)
    // D-pad navigacia zoznamu kanalov v prehravaci
    private val openChannelListState = androidx.compose.runtime.mutableStateOf(0)
    private val navChannelIndexState = androidx.compose.runtime.mutableStateOf(0)
    // M369: aktivny filter skupiny v zozname kanalov + priznak, ci je fokus na pilulke skupiny.
    private val activeGroupLabelState = androidx.compose.runtime.mutableStateOf("")
    private val groupPickerState = androidx.compose.runtime.mutableStateOf(false)
    // M370 / M635: hladanie kanala podla nazvu — stav a klavesy v ChannelSearch.kt
    private val search: ChannelSearch by lazy {
        ChannelSearch(this,
            onSelect = { uuid -> selectLiveByUuid(uuid) },
            onDeactivate = { groupPickerState.value = false })
    }
    private var seekablePlayback = false
    // Zadavanie kanala cislami z dialkoveho ovladaca (M635: ChannelNumberEntry.kt)
    private val numEntry: ChannelNumberEntry by lazy {
        ChannelNumberEntry(lifecycleScope) { typed ->
            val idx = LivePlaylist.channels.indexOfFirst { it.number == typed }
            if (idx in live.uuids.indices) { switcher.switchToIndex(idx); pokeControls() }
        }
    }

    // M654: stavba libVLC Media (URL / feeder, dekodér, deinterlacing, demux) v MediaFactory.kt
    private val mediaFactory: MediaFactory by lazy { MediaFactory(this) { engine.libVlc } }

    // M655: otvaranie streamu (HTTP / feeder / DVR / HTSP, auth sonda) v StreamOpener.kt
    private val opener: StreamOpener by lazy {
        StreamOpener(this, lifecycleScope, stream, live, mediaFactory, tracks,
            player = { engine.player },
            hooks = object : StreamOpener.Hooks {
                override fun ensureHealthyPlayer() { this@PlayerActivity.ensureHealthyPlayer() }
                override fun startPlayback() { engine.startPlayback() }
                override fun resetTimeshift() { this@PlayerActivity.resetTimeshift() }
                override fun resetTeletext() { closeTeletext(); teletext.reset() }   // M552/M553
                override fun teletextSetHtspAvailable(available: Boolean) { teletext.setHtspAvailable(available) }
                override fun teletextFeedHtsp(es: ByteArray) { teletext.feedHtsp(es) }
                override fun subtitlePage(page: sk.tvhclient.shared.htsp.DvbSubtitleDecoder.DecodedPage, ms: Long) { subOverlay?.onPage(page, ms) }
                override fun subtitleReset() { subOverlay?.reset() }
                override fun fallbackTitle(): String? = intent.getStringExtra(EXTRA_TITLE)
            })
    }

    private fun pokeControls() {
        zapBar.hide()  // M446
        // Moderny rezim na TV pri live: stary ovladaci panel sa nezobrazuje (M325/M327)
        if (modernTvActive()) return
        controlsPokeState.value = controlsPokeState.value + 1
    }
    // INFO kláves / tlacidlo -> okno s detailom aktualnej relacie
    private val infoPokeState = androidx.compose.runtime.mutableStateOf(0)
    private fun toggleInfo() { infoPokeState.value = infoPokeState.value + 1 }
    // EPG kláves / tlacidlo -> otvor TV program (mriezku) v hlavnej aplikacii
    // M383-fix: EPG sa smie otvorit az PO dokonceni vstupu do PiP — startActivity
    // vypaleny pocas PiP prechodu system na mnohych zariadeniach spolkne (vidno
    // len PiP okno, EPG "dobehne" az po zvacseni). Preto: enterPip -> cakaj na
    // onPictureInPictureModeChanged(true) -> az potom startActivity.
    private var pendingEpgAfterPip = false

    private fun launchEpgActivity() {
        val i = android.content.Intent(this, MainActivity::class.java).apply {
            putExtra("open_epg", true)
            // M591: z prehravaca radia sa ma otvorit program STANIC, nie TV kanalov
            if (live.playKind == "radio") putExtra("epg_radio", true)
            // zapamataj aktualny zivy kanal, nech BACK z EPG vrati do prehravaca nan
            if (!seekablePlayback) live.uuids.getOrNull(live.index)?.let { putExtra("epg_return_uuid", it) }
        }
        runCatching { startActivity(i) }
    }

    private fun openEpgInApp() {
        // M601-fix: radio nejde do PiP — autoPipIfPossible urobi handoff na pozadie
        // (a aktivitu ukonci), ale vratil true, takze sa TV program otvoril az po
        // 1,2 s poistke; medzitym bol vidiet uvod. Program otvor hned, handoff
        // (moderny rezim) pride po nom; v klasiku ostane prehravac pod programom
        // ako doteraz.
        if (live.playKind == "radio") {
            launchEpgActivity()
            radioHandoffIfPossible()
            return
        }
        // na telefonoch: vstup do PiP, aby video bezalo v plavajucom okne nad EPG
        if (autoPipIfPossible()) {
            pendingEpgAfterPip = true
            // poistka: keby callback neprisiel (PiP zlyha po ceste), otvor EPG aj tak
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (pendingEpgAfterPip) { pendingEpgAfterPip = false; launchEpgActivity() }
            }, 1200)
        } else {
            launchEpgActivity()
        }
    }
    private fun showControlsFocused() {
        zapBar.hide()  // M446
        val order = playerControlOrder(!seekablePlayback && live.uuids.size > 1, seekablePlayback, pipButtonVisible(), timeshift.engaged.value, profileSwitchAvailable(), dvrRecordVisible(), teletextVisible())
        controlNavState.value = order.indexOf("play").coerceAtLeast(0)
        pokeControls()
    }

    private fun togglePlayPause() {
        if (!engine.ready) return
        timeshift.flushNow()   // doruc nazbierany skok, nech je server konzistentny
        if (isPlayingState.value) {
            if (stream.htspStream) stream.htspFeeder?.pause()         // zastav HTSP delivery (aj bez timeshiftu)
            if (stream.htspLive) {
                // prva pauza "zapne" timeshift: odtialto sa rata buffer aj cervene pocitadlo
                timeshift.onPaused()
                // zapnutim timeshiftu pribudnu ovladace pretacania (tsrew pred play) a posunu sa
                // indexy — re-ukotvi fokus na play/pause, nech "neskoci" na pretacanie
                val ord = playerControlOrder(!seekablePlayback && live.uuids.size > 1, seekablePlayback, pipButtonVisible(), true, profileSwitchAvailable(), dvrRecordVisible())
                controlNavState.value = ord.indexOf("play").coerceAtLeast(0)
            }
            isPlayingState.value = false
            engine.player.pause()
        } else {
            if (stream.htspStream) stream.htspFeeder?.resume()
            if (stream.htspLive) timeshift.onResumed()
            isPlayingState.value = true
            reconnect.resetDvrReopen()   // manualny play -> povol nove pokusy o nacitanie novsich dat
            engine.player.play()
        }
    }

    /** Novy zivy zaciatok (cerstva subscription = na zivo) -> vynuluj timeshift. */
    private fun resetTimeshift() {
        timeshift.reset()
        hideSeekSpinner()
        // M492: akumulator dvojkliku a hint sa nulovat musia tiez — inak by po prepnuti
        // media ostal vychodzi bod z predchadzajucej nahravky. Playhead vynuluj z rovnakeho dovodu.
        dvrSeek.resetForNewMedia()
        dvr.playheadMsState.value = 0L
    }

    /** Relativny skok v timeshifte (sekundy; zaporne = vzad). */
    private fun timeshiftSkip(seconds: Int) { if (stream.htspLive) timeshift.skip(seconds) }

    /** Zhasne seek-koliesko (Playing/Buffering 100 % dobehol, alebo novy zivy zaciatok). */
    private fun hideSeekSpinner() { seekSpinnerJob?.cancel(); seekingState.value = false }

    /** Koliesko v strede pocas resyncu; zhasne ho Playing/Buffering event, poistka po 4 s. */
    private fun showSeekSpinner() {
        seekingState.value = true
        seekSpinnerJob?.cancel()
        seekSpinnerJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(4000)
            seekingState.value = false
        }
    }

    /** Pretoc DVR nahravku na cielovy program-relativny cas PREBUDOVANIM streamu.
     *  Priame URL -> nova Media s :start-time (libVLC seekuje cez HTTP Range).
     *  Feeder/pipe -> restart HTTP feedu na odhadnutom byte-offsete (pipe sa neseekuje).
     *  V oboch pripadoch naseeduje playhead hodiny na cielovy cas. */
    private fun seekDvrTo(targetMs: Long, fromMs: Long, dur: Long) {
        val url = stream.currentStreamUrl ?: return
        if (!engine.ready) return
        val offsetMs = if (dvr.progStartSec > 0 && dvr.realStartSec in 1 until dvr.progStartSec)
            (dvr.progStartSec - dvr.realStartSec) * 1000 else 0L
        val fileMs = (offsetMs + targetMs).coerceAtLeast(0L)   // cas v subore (0 = realny zaciatok nahravky)
        reconnect.clearPending()
        // Bezny seek = kratky restart streamu; ukaz len lahky seek-spinner, NIE "Opatovne
        // pripajanie" (to patri len skutocnemu vypadku/reconnectu). Zhasne ho Playing/Buffering,
        // poistka po 6 s keby event nedosiel.
        seekingState.value = true
        seekSpinnerJob?.cancel()
        seekSpinnerJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(6000)
            seekingState.value = false
        }
        opener.seekDvrFile(url, fileMs, offsetMs, fromMs, dur)   // M670: telo v StreamOpener
        // M594: cas a ciel posledneho pretocenia — ked hned po nom pride koniec/chyba,
        // je to trafeny EOF (subor kratsi nez trvanie z EPG) a nie skutocny koniec
        dvrSeek.markSeek(targetMs)
        // playhead hned na cielovu poziciu + seed pre hodiny (po restarte je player.position
        // neplatna, hodiny ju nesmu citat - prevezmu seed a tikaju dalej z neho)
        dvr.playheadMsState.value = targetMs
        dvr.seekSeedState.value = targetMs
    }

    /** Dvojklik na lavu/pravu stranu (YouTube-style): skok o 10 s.
     *  DVR -> seek v medii (akumulovane); aktivny timeshift -> subscriptionSkip hned. */
    private fun doubleTapSeek(forward: Boolean) {
        when {
            seekablePlayback -> dvrSeek.doubleTap(forward, applyImmediately = false)
            stream.htspLive -> {
                if (timeshift.maxRewindMs() <= 0L) return   // timeshift sa zapne az pauzou, dovtedy niet co pretacat
                timeshiftSkip(if (forward) 10 else -10)   // live timeshift: lacne, pretoc hned
                dvrSeek.doubleTap(forward, applyImmediately = true)
            }
            else -> return   // ziadne pretacanie (zive bez timeshiftu) -> ignoruj
        }
    }

    /** Horizontalne tahanie (MX Player) -> skok o dany pocet sekund (zaporne = vzad). */
    private fun scrubSeek(seconds: Int) {
        if (seconds == 0) return
        when {
            seekablePlayback -> dvrSeek.seekRelative(seconds.toLong() * 1000L)
            stream.htspLive -> { if (timeshift.maxRewindMs() > 0L) timeshiftSkip(seconds) }  // konvencia ako seekRelative: zaporne = vzad
            else -> {}
        }
    }

    // M473 / M669: currentEventId / currentLiveEvent / runningRecordingHere / currentEventRecording v DvrRecordController

    /** TV/box (Android TV) — na detekciu kde sa ma archivny vyber zobrazovat. */
    private fun isTvDevice(): Boolean {
        val um = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    // ===== M657: jadro prepinania kanalov (selectChannelOrArchive, resolveArchiveChoice,
    // rememberPlayback, playRecordingFromStart, saveLastLive, switchToIndex) — ChannelSwitcher.kt
    // (M677: volania priamo cez switcher) =====
    private val switcher: ChannelSwitcher by lazy {
        ChannelSwitcher(this, lifecycleScope, live, stream, tracks, object : ChannelSwitcher.Actions {
            override fun isTvDevice(): Boolean = this@PlayerActivity.isTvDevice()
            override fun pokeControls() { this@PlayerActivity.pokeControls() }
            override fun closeChannelList() { this@PlayerActivity.closeChannelList() }
            override fun refreshDvrState() { this@PlayerActivity.refreshDvrState() }
            override fun cancelReconnect() { reconnect.cancel() }
            override fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, channelIndex: Int?) {
                this@PlayerActivity.requestPin(onOk = onOk, onCancel = onCancel, channelIndex = channelIndex)
            }
            override fun playHtspLive(server: sk.tvhclient.shared.model.TvhServer, channelId: Long, timeshift: Boolean): Boolean =
                opener.playHtspLive(server, channelId, timeshift)
            override fun playLiveAuto(server: sk.tvhclient.shared.model.TvhServer, url: String) { opener.playLiveAuto(server, url) }
            override fun playDvrViaFeeder(server: sk.tvhclient.shared.model.TvhServer, url: String) { opener.playDvrViaFeeder(server, url) }
            override fun playHttp(url: String) { opener.playHttp(url) }
            override fun setHasVideo(v: Boolean) { vlcEvents.hasVideo.value = v }
            override fun htspInitDone(): Boolean = this@PlayerActivity.htspInitDone
            override fun setHtspInitDone(v: Boolean) { this@PlayerActivity.htspInitDone = v }
            override fun archiveChoiceIdx(): Int = archiveChoiceIdxState.value
            override fun setArchiveChoiceIdx(v: Int) { archiveChoiceIdxState.value = v }
            override fun setArchiveChoiceSel(v: Int) { archiveChoiceSelState.value = v }
            override fun recInProgressByChan(): Map<String, sk.tvhclient.shared.model.DvrEntry> = this@PlayerActivity.recInProgressByChan.value
            override fun dvrUuid(): String? = dvr.uuid
            override fun intentUuid(): String? = intent.getStringExtra(EXTRA_UUID)
            override fun startActivity(i: android.content.Intent) { this@PlayerActivity.startActivity(i) }
        })
    }

    /** Zatvorenie prehravaca: ak bol spusteny cez "od zaciatku" zo zivej TV, vrat sa na povodny kanal. */
    /** M342/M344: BACK z hrajuceho radia = handoff do RadioPlayerService.
     *  Vrati true, ak handoff prebehol (aktivita sa ukoncila) — radio hra dalej
     *  na pozadi s mini listou. Moderny: telefon aj TV. M624: aj klasik na
     *  telefone (mini lista je uz aj v klasiku); klasik na TV povodne — nema
     *  panel, radio by hralo bez ovladania. */
    private fun radioHandoffIfPossible(): Boolean {
        if (live.playKind != "radio") return false
        if (UiModePref.get(this) != UiModePref.MODERN && isTvDevice()) return false
        val uuid = live.uuids.getOrNull(live.indexState.value) ?: return false
        val server = live.server ?: sk.tvhclient.shared.Tvh.store.active() ?: return false
        if (!engine.ready || !engine.player.isPlaying) return false
        val ch = LivePlaylist.channels.firstOrNull { it.uuid == uuid }
        RadioCenter.stations = LivePlaylist.channels.map {
            RadioCenter.RadioStation(it.uuid, it.name, it.piconUrl, it.nowTitle, it.nowStart, it.nowStop)
        }
        RadioCenter.play(
            this, server, uuid,
            ch?.name ?: "",
            picon = ch?.piconUrl,
            epgTitle = ch?.nowTitle ?: "",
            epgStart = ch?.nowStart ?: 0L,
            epgStop = ch?.nowStop ?: 0L
        )
        finish()
        return true
    }

    private fun closePlayer() {
        // M494: odchod z prehravaca = uz niet co obnovovat (pouzivatel skoncil
        // na zozname, nie na kanali). Navrat na zivy kanal z archivu odchod nie je.
        if (returnLiveUuid == null) LastPlayback.clear(this)
        if (radioHandoffIfPossible()) return
        val ru = returnLiveUuid
        if (ru != null) {
            returnLiveUuid = null
            val i = android.content.Intent(this, PlayerActivity::class.java).apply {
                putExtra(EXTRA_UUID, ru)
                putExtra(EXTRA_TITLE, returnLiveTitle ?: "")
            }
            runCatching { startActivity(i) }
            finish()
        } else if (!autoPipIfPossible()) finish()   // M343: respektuj vypnute Auto-PiP — BACK = stop, nie PiP
    }

    /** Prepne na susedny live kanal (delta +1 / -1). */
    /** Kratka haptika pri prepnuti kanala — len telefon/tablet v modernom rezime (M336). */
    private fun hapticChannelSwitch() {
        if (isTvBox) return
        if (UiModePref.get(this) != UiModePref.MODERN) return
        runCatching {
            window.decorView.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP
            )
        }
    }

    // M407 / M652: debounce rychleho zappingu v ZapDebounce.kt
    private val zap: ZapDebounce by lazy {
        ZapDebounce(live,
            haptic = { hapticChannelSwitch() },
            pokeOnCommit = { ZapOverlayPref.get(this) },
            commit = { target, poke -> switcher.switchToIndex(target, poke = poke) })
    }
    private fun switchLive(delta: Int) { zap.switchLive(delta) }

    // ===== M369 / M640: filter skupin v zozname kanalov — LiveGroups.kt =====
    private val groups: LiveGroups by lazy {
        LiveGroups(this, live,
            epgUpcoming = { epg.upcoming.value },
            navIndex = navChannelIndexState,
            groupLabel = activeGroupLabelState)
    }

    // ===== M370 / M635: hladanie kanala — ChannelSearch.kt =====
    /** Vyber kanala z vysledkov hladania: prepne (aj skupinu ak treba) a pusti. */
    private fun selectLiveByUuid(uuid: String) {
        okLongFired = true   // prehltne nasledne OK-up, inak by zoznam potvrdil iny kanal (index 0)
        search.close()
        closeChannelList()
        var i = live.uuids.indexOf(uuid)
        if (i < 0) { groups.apply(LivePlaylist.GROUP_ALL); i = live.uuids.indexOf(uuid) }
        if (i >= 0) switcher.selectChannelOrArchive(i, poke = false)
    }

    // stav prekryti (z Compose) — kym je otvorene, D-pad riesime my (zoznam) alebo Compose (menu)
    private var trackMenuOpen = false
    private var channelListOpen = false
    private val closeChannelListState = androidx.compose.runtime.mutableStateOf(0)
    // Moznosti (Zvuk / Titulky / SW dekod) — vertikalne overlay, navigujeme z Activity
    private var optionsOpen = false
    private var remoteDebug = false
    /** PiP tlacidlo v ovladani (M349-fix2): zobrazit len ked je Auto-PiP
     *  v nastaveniach VYPNUTY — vtedy je jedina cesta do PiP rucna. Pri
     *  zapnutom Auto-PiP je tlacidlo zbytocne (BACK spravi PiP sam).
     *  pipSupported zaroven vylucuje TV (nemaju FEATURE_PICTURE_IN_PICTURE). */
    // M575: na TV sa tlacidlo neponuka ani ked box PiP hlasi — okno sa dialkovym
    // ovladacom neda ovladat (issue #11)
    private fun pipButtonVisible(): Boolean = pip.supported && !isTvDevice() && !AutoPipPref.get(this)

    private var controlsShown = false
    private val openOptionsState = androidx.compose.runtime.mutableStateOf(0)
    private val closeOptionsState = androidx.compose.runtime.mutableStateOf(0)
    private val optionsNavState = androidx.compose.runtime.mutableStateOf(0)
    // Casovac uspatia
    // M629: casovac uspatia v SleepTimer.kt
    private val sleep: SleepTimer by lazy { SleepTimer(this) { finish() } }
    // Navigacia ovladacieho panela (focus riadime z Activity, nie cez Compose focus)
    private val controlNavState = androidx.compose.runtime.mutableStateOf(0)
    private var okLongFired = false

    // Rodicovsky zamok (PIN) — stav a klavesy v PinPrompt.kt (M629), vykreslenie PinDialog v PlayerUi
    private val pin: PinPrompt by lazy {
        PinPrompt(this,
            isTv = { isTvDevice() },
            channelCount = { live.uuids.size },
            openChannelList = { openChannelList() },
            switchToIndex = { idx -> switcher.switchToIndex(idx) },
            onRequested = { okLongFired = false })   // PIN vyzva prebera vstup; OK gesto je tym ukoncene
    }
    private fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, markUnlock: Boolean = true, channelIndex: Int? = null) {
        pin.request(onOk, onCancel, markUnlock, channelIndex)
    }
    // Dialog "Obnovit prehravanie" — D-pad obsluha v dispatchKeyEvent (na boxe nemal fokus)
    private val resumePromptState = androidx.compose.runtime.mutableStateOf(false)
    private val resumeSelState = androidx.compose.runtime.mutableStateOf(1)   // 0=Nie, 1=Ano (predvolba)
    private val resumeAnswerState = androidx.compose.runtime.mutableStateOf(0) // 0=ziadna, 1=Ano, 2=Nie
    // Vyber pri archivovanom kanali (nazivo / od zaciatku) priamo v prehravaci
    private val archiveChoiceIdxState = androidx.compose.runtime.mutableStateOf(-1) // index kanala cakajuci na vyber, -1 = ziadny
    private val archiveChoiceSelState = androidx.compose.runtime.mutableStateOf(0)   // 0=nazivo, 1=od zaciatku (D-pad)
    private val recInProgressByChan = androidx.compose.runtime.mutableStateOf<Map<String, sk.tvhclient.shared.model.DvrEntry>>(emptyMap())
    // Navrat na povodny zivy kanal po zatvoreni DVR prehravaca spusteneho cez "od zaciatku"
    private var returnLiveUuid: String? = null
    private var returnLiveTitle: String? = null

    // DVR scrub (M597/M598) — ScrubController.kt (M646); pristup priamo cez scrub
    private val scrub: ScrubController by lazy {
        ScrubController(lifecycleScope,
            barMs = { if (dvr.recording) (dvr.durationMs - 45_000L).coerceAtLeast(1L) else dvr.durationMs },
            durMs = { if (dvr.durationMs > 0) dvr.durationMs else (if (engine.ready) engine.player.length else 0L) },
            playheadMs = { dvr.playheadMsState.value },
            seekable = { engine.ready && seekablePlayback },
            seekAbsolute = { ms -> dvrSeek.seekAbsolute(ms) },
            poke = { pokeControls() })
    }

    // M651: klavesy pri beznom prehravani (blok 4 dispatchKeyEvent) v PlaybackKeys.kt
    private val playbackKeys: PlaybackKeys by lazy {
        PlaybackKeys(this, live, scrub, numEntry, controlNavState,
            seekable = { seekablePlayback },
            controlsShown = { controlsShown },
            modernTvActive = { modernTvActive() },
            controlOrder = { canZap ->
                playerControlOrder(canZap, seekablePlayback, pipButtonVisible(), timeshift.engaged.value,
                    profileSwitchAvailable(), dvrRecordVisible(), teletextVisible())
            },
            actions = object : PlaybackKeys.Actions {
                override fun switchLive(delta: Int) { this@PlayerActivity.switchLive(delta) }
                override fun showZapBar() { this@PlayerActivity.showZapBar() }
                override fun openModernOverlayAtCurrent() { modernOv.openAtCurrent() }
                override fun openModernOverlay() { modernOv.open() }
                override fun showControlsFocused() { this@PlayerActivity.showControlsFocused() }
                override fun pokeControls() { this@PlayerActivity.pokeControls() }
                override fun activateControl(id: String?) { this@PlayerActivity.activateControl(id) }
                override fun togglePlayPause() { this@PlayerActivity.togglePlayPause() }
                override fun openChannelListLong() {
                    okLongFired = true; openChannelList()  // okLongFired prehltne nasledne OK-up
                }
                override fun modernPlaybackOk(down: Boolean, event: android.view.KeyEvent): Boolean =
                    modernOv.handlePlaybackOk(down, event) {
                        okLongFired = true   // prehltne OK-up, inak by up hned potvrdil kanal a zoznam zavrel
                        openChannelList()
                    }
                override fun beginScrub(dir: Int) { this@PlayerActivity.beginScrub(dir) }
                override fun initScrub() { scrub.init() }
            })
    }

    /**
     * M598: sipka pri skrytom ovladani v archive. Doteraz hned pretocila (-15 s / +30 s)
     * — obraz sekol pri kazdom stlaceni aj pri drzani, hoci pouzivatel este len hladal
     * miesto. Teraz sa otvori lista s kurzorom, kurzor sa posunie o krok a samotne
     * pretocenie sa vykona az po ustaleni (M597) alebo po OK.
     */
    private fun beginScrub(dir: Int) {
        val order = playerControlOrder(
            !seekablePlayback && live.uuids.size > 1, seekablePlayback, pipButtonVisible(),
            timeshift.engaged.value, profileSwitchAvailable(), dvrRecordVisible(), teletextVisible()
        )
        val seekIdx = order.indexOf("seek")
        if (seekIdx < 0) { showControlsFocused(); return }
        val wasOnSeek = controlsShown && controlNavState.value == seekIdx
        controlNavState.value = seekIdx
        if (!wasOnSeek) scrub.init()
        scrub.step(dir)
        scrub.scheduleAuto()
        pokeControls()
    }

    // Pocitadlo na obnovu ikon zamku v in-player zozname po zmene zamku.
    private val lockTickState = androidx.compose.runtime.mutableStateOf(0)

    /** Zamkne/odomkne kanal v zozname prehravaca (ako dlhy klik na telefone). Chrani PINom. */
    private fun toggleLockAt(idx: Int) {
        val srv = live.server ?: return
        val uuid = live.uuids.getOrNull(idx) ?: return
        val doToggle: () -> Unit = {
            val now = ParentalLock.isChannelLocked(this, srv.id, uuid)
            ParentalLock.setChannelLocked(this, srv.id, uuid, !now)
            lockTickState.value = lockTickState.value + 1
        }
        // ak je zamok aktivny a sme mimo okna, najprv over PIN; po zadani plati grace okno
        // (rovnake pravidlo "po odomknuti nepytat X min" ako pri prepinani) -> markUnlock = true
        if (ParentalLock.needsPin(this)) requestPin(onOk = doToggle, onCancel = { }, markUnlock = true)
        else doToggle()
    }

    /**
     * M544: callbacky pre PlayerUi, ktore sa odovzdavaju PODMIENENE (`if (...) cb else null`),
     * su pevne polia aktivity, nie lambdy vytvorene v kompozicii. Compose lambdu v
     * argumente memoizuje do slotu; pri prepnuti podmienky (htspStreamState, canZap)
     * vnutri `key(videoSurfaceGen)` sa sloty posunuli a pri rekompozicii sa v slote
     * ocakavanom pre Function1 nasla ina lambda -> ClassCastException
     * „$$ExternalSyntheticLambda7 cannot be cast to Function1" (Pixel 9, 1.0.5).
     * Pole ziadny slot nezabera, takze sa nema co posunut.
     */
    private val pickHtspSpuCb: (Int) -> Unit = { id -> trackMenu.pickHtspSpu(id) }
    private val prevChannelCb: () -> Unit = { switchLive(-1) }
    private val nextChannelCb: () -> Unit = { switchLive(+1) }

    // --- Kontextove menu kanala v prehravaci (long-press OK / dlhy klik) — ChannelContextMenu.kt (M641) ---
    private val ctxMenu: ChannelContextMenu by lazy {
        ChannelContextMenu(this, live, groups,
            epgUpcoming = { epg.upcoming.value },
            recInProgress = { recInProgressByChan.value },
            canRecord = { dvrRec.canRecordState.value },
            navIndex = navChannelIndexState,
            okLongFired = { okLongFired },
            actions = object : ChannelContextMenu.Actions {
                override fun showInfo(idx: Int) = info.show(idx)
                override fun playFromStart(rec: sk.tvhclient.shared.model.DvrEntry, nowStart: Long, nowStop: Long) =
                    switcher.playRecordingFromStart(rec, nowStart, nowStop)
                override fun switchTo(idx: Int) = switcher.switchToIndex(idx)
                override fun toggleLock(idx: Int) = toggleLockAt(idx)
                override fun record(ch: LivePlaylist.LiveChannel, ev: sk.tvhclient.shared.model.EpgEvent) = dvrRec.recordFromCtxMenu(ch, ev)
                override fun enterReorder() = reorder.enter()
            })
    }

    // ===== M541 / M638: rezim usporiadania oblubenych (D-pad) — FavReorder.kt =====
    private val reorder: FavReorder by lazy {
        FavReorder(this,
            serverId = { (live.server ?: Tvh.store.active())?.id },
            liveUuids = { live.uuids },
            liveChannels = live.channelsState,
            navIndex = navChannelIndexState,
            groupLabel = activeGroupLabelState,
            groupLabelFor = { key -> groups.labelFor(key) },
            reapplyFavGroup = { groups.refreshFavOrder(); groups.apply(LivePlaylist.GROUP_FAV) },
            okLongFired = { okLongFired })
    }

    // --- Info o relacii (detail) v prehravaci — ChannelInfo.kt (M643) ---
    private val info: ChannelInfo by lazy {
        ChannelInfo(lifecycleScope, live,
            epgUpcoming = { epg.upcoming.value },
            cacheChannelEpg = { uuid, list -> epg.cacheChannelEpg(uuid, list) },
            dvrRecordVisible = { dvrRecordVisible() },
            toggleRecord = { toggleRecordCurrent() },
            onShown = { zapBar.hide() })
    }
    // M280: potvrdenie ukoncenia ziveho prehravania (BACK) — ako exit dialog v menu
    private val exitConfirmState = androidx.compose.runtime.mutableStateOf(false)
    private val exitConfirmSelState = androidx.compose.runtime.mutableStateOf(0) // 0=Zrusit, 1=Ukoncit

    // ---- M430 / M628: kompaktny zap pas — stav aj vykreslenie v ZapBar.kt ----
    private val zapBar: ZapBar by lazy {
        ZapBar(lifecycleScope,
            fmtRange = { a, b -> ChannelInfo.fmtRange(a, b) },
            suppressed = { controlsShown || modernOv.visible.value || info.visible.value })
    }
    private fun showZapBar() { zapBar.show(live.channelsState.value.getOrNull(live.indexState.value)) }

    private val listKeys: ChannelListKeys by lazy {
        ChannelListKeys(this, live, groups, search, reorder, navChannelIndexState, groupPickerState,
            object : ChannelListKeys.Actions {
                override var okLongFired: Boolean
                    get() = this@PlayerActivity.okLongFired
                    set(v) { this@PlayerActivity.okLongFired = v }
                override fun openContextMenu(idx: Int) { ctxMenu.open(idx) }
                override fun closeList() { closeChannelList() }
                override fun switchDelayed(idx: Int) {
                    // M600-fix: pockaj, kym sa video vrati z nahladoveho obdlznika na celu obrazovku
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(320)
                        switcher.switchToIndex(idx, poke = false)
                    }
                }
                override fun selectOrArchive(idx: Int) { switcher.selectChannelOrArchive(idx, poke = false) }
                override fun reselectCurrent() {
                    closeChannelList()
                    if (modernTvActive()) modernOv.open() else showControlsFocused()
                }
            })
    }
    /** M605: dlazdica „TV kanaly" / „Radia" otvorila prehravac so zoznamom hned pri starte. */
    private var listFirst = false

    private fun openChannelList() {
        // M371: otvor aj s 1 kanalom, ak su skupiny na prepnutie (napr. Oblubene s 1 kanalom),
        // inak by sa filtrovany zoznam uz nedal otvorit ani prepnut spat.
        groups.refreshFavOrder()   // M541: oblubene sa mohli zmenit v zozname Kanaly
        if (live.uuids.size < 2 && groups.keys().size <= 1) return
        groupPickerState.value = false
        search.deactivateSilently()
        activeGroupLabelState.value =
            if (groups.keys().size > 1) groups.labelFor(LivePlaylist.activeGroupKey) else ""
        navChannelIndexState.value = live.index.coerceAtLeast(0)
        listKeys.openedAt = android.os.SystemClock.uptimeMillis()
        openChannelListState.value = openChannelListState.value + 1
    }
    private fun closeChannelList() {
        reorder.exit()   // M541
        groupPickerState.value = false
        search.deactivateSilently()
        closeChannelListState.value = closeChannelListState.value + 1
    }
    private fun closeOptions() {
        closeOptionsState.value = closeOptionsState.value + 1
    }

    /** Otvori vyber dlzky casovaca uspatia (dostupne dotykom aj D-padom). */
    private fun openSleepMenu() {
        optionsNavState.value = 0
        openOptionsState.value = openOptionsState.value + 1
    }

    /** Vyber dlzky casovaca uspatia. */
    private fun selectOption(idx: Int) {
        sleep.set(sleep.durations.getOrElse(idx) { 0 })
        closeOptions()
    }

    // --- Track menu (audio/titulky) riadene z Activity; stav a pomocne funkcie v TrackState (M637) ---
    // M671: akcie track menu (HTSP titulky, profil, D-pad vyber) v TrackMenuController.kt
    private val trackMenu: TrackMenuController by lazy {
        TrackMenuController(this, lifecycleScope, tracks, live, stream,
            player = { if (engine.ready) engine.player else null },
            hooks = object : TrackMenuController.Hooks {
                override fun subtitleReset() { subOverlay?.reset() }
                override fun restartCurrentChannel() {
                    val i = live.index
                    if (i >= 0) { live.index = -1; switcher.switchToIndex(i, poke = false) }
                }
            })
    }

    private fun applyPendingSpuRestore() { tracks.applyPendingSpuRestore(stream.htspStream, seekablePlayback) }

    /** M383: prepinac profilu ma zmysel len pri HTTP live (nie HTSP, nie DVR,
     *  nie externa URL — tam profil neexistuje alebo sa neda menit). */
    private fun profileSwitchAvailable(): Boolean = tracks.profileSwitch.value

    // --- Aktivacia zvyrazneneho prvku ovladacieho panela ---
    private fun activateControl(id: String?) {
        when (id) {
            "close" -> closePlayer()
            "list" -> openChannelList()
            "prev" -> { switchLive(-1); pokeControls() }
            "play" -> { togglePlayPause(); pokeControls() }
            "next" -> { switchLive(+1); pokeControls() }
            "tsrew" -> { timeshiftSkip(-30); pokeControls() }
            "tsff" -> { timeshiftSkip(+30); pokeControls() }
            "audio" -> tracks.openAudioMenu()
            "subs" -> tracks.openSpuMenu()
            "profile" -> trackMenu.openProfileMenu()
            "epg" -> openEpgInApp()
            "pip" -> enterPipAndMinimize()
            "info" -> { toggleInfo(); pokeControls() }
            "sleep" -> openSleepMenu()
            "rec" -> { toggleRecordCurrent(); pokeControls() }   // M490
            "txt" -> openTeletext()   // M553
        }
    }

    private fun isCommonKey(c: Int): Boolean {
        return c in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ||
            c in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 ||
            c == android.view.KeyEvent.KEYCODE_DPAD_UP ||
            c == android.view.KeyEvent.KEYCODE_DPAD_DOWN ||
            c == android.view.KeyEvent.KEYCODE_DPAD_LEFT ||
            c == android.view.KeyEvent.KEYCODE_DPAD_RIGHT ||
            c == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            c == android.view.KeyEvent.KEYCODE_ENTER ||
            c == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
            c == android.view.KeyEvent.KEYCODE_BACK ||
            c == android.view.KeyEvent.KEYCODE_DEL ||
            c == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            c == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
            c == android.view.KeyEvent.KEYCODE_VOLUME_MUTE ||
            c == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
    }

    // M658: retaz strazi dispatchKeyEvent v PlayerKeyRouter.kt (poradie = spravanie)
    private val keyRouter: PlayerKeyRouter by lazy {
        PlayerKeyRouter(this, search, ttx, pin, ctxMenu, info, listKeys, modernOv, tracks, sleep, engine, playbackKeys,
            resumePromptState, resumeSelState, resumeAnswerState,
            dvrRec.askState, dvrRec.askSelState, archiveChoiceIdxState, archiveChoiceSelState,
            exitConfirmState, exitConfirmSelState, isPlayingState, optionsNavState,
            actions = object : PlayerKeyRouter.Actions {
                override val remoteDebug: Boolean get() = this@PlayerActivity.remoteDebug
                override var okLongFired: Boolean
                    get() = this@PlayerActivity.okLongFired
                    set(v) { this@PlayerActivity.okLongFired = v }
                override val seekablePlayback: Boolean get() = this@PlayerActivity.seekablePlayback
                override val liveIndex: Int get() = live.index
                override val channelListOpen: Boolean get() = this@PlayerActivity.channelListOpen
                override val returnLiveUuid: String? get() = this@PlayerActivity.returnLiveUuid
                override val optionsOpen: Boolean get() = this@PlayerActivity.optionsOpen
                override val trackMenuOpen: Boolean get() = this@PlayerActivity.trackMenuOpen
                override val htspStream: Boolean get() = stream.htspStream
                override fun isCommonKey(kc: Int): Boolean = this@PlayerActivity.isCommonKey(kc)
                override fun resolveDvrAsk(name: String?) { dvrRec.resolveAsk(name) }
                override fun resolveArchiveChoice(fromStart: Boolean) { switcher.resolveArchiveChoice(fromStart) }
                override fun finish() { this@PlayerActivity.finish() }
                override fun openEpgInApp() { this@PlayerActivity.openEpgInApp() }
                override fun openSpuMenu() { tracks.openSpuMenu() }
                override fun openAudioMenu() { tracks.openAudioMenu() }
                override fun modernTvActive(): Boolean = this@PlayerActivity.modernTvActive()
                override fun openModernOverlay() { modernOv.open() }
                override fun showControlsFocused() { this@PlayerActivity.showControlsFocused() }
                override fun toggleInfo() { this@PlayerActivity.toggleInfo() }
                override fun togglePlayPause() { this@PlayerActivity.togglePlayPause() }
                override fun pokeControls() { this@PlayerActivity.pokeControls() }
                override fun scrubSeek(seconds: Int) { this@PlayerActivity.scrubSeek(seconds) }
                override fun toggleFavoriteAt(idx: Int, announce: Boolean) { ctxMenu.toggleFavoriteAt(idx, announce) }
                override fun closePlayer() { this@PlayerActivity.closePlayer() }
                override fun openChannelList() { this@PlayerActivity.openChannelList() }
                override fun seekRelative(deltaMs: Long) { dvrSeek.seekRelative(deltaMs) }
                override fun selectOption(idx: Int) { this@PlayerActivity.selectOption(idx) }
                override fun closeOptions() { this@PlayerActivity.closeOptions() }
                override fun selectTrackAtNav() { trackMenu.selectAtNav() }
                override fun closeTrackMenu() { tracks.closeMenu() }
                override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
                    this@PlayerActivity.dispatchKeyEvent(event)
            })
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        val kc = event.keyCode
        keyRouter.handle(kc, down, event)?.let { return it }
        return super.dispatchKeyEvent(event)
    }

    // DVR progress (sledovanie pozicie pre archiv) — M665: stav v DvrPlayback.kt (M677: pristup priamo cez dvr)
    private val dvr = DvrPlayback(this)

    private fun saveDvrProgress() {
        dvr.saveProgress(if (engine.ready && !engine.tornDown) engine.player else null)
    }

    /** M623: radio s volbou "Radio hra na pozadi" (telefon aj TV) — odchod z prehravaca
     *  na pozadie (zhasnutie, zamok, domovska obrazovka, ina appka) radio nepozastavi.
     *  V samotnom prehravaci obrazovka svieti dalej (KEEP_SCREEN_ON ako pri TV). */
    private fun radioBackground(): Boolean =
        live.playKind == "radio" && RadioBackgroundPref.get(this)

    private fun keepScreenOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** M658: pripojenie video layoutu z PlayerUi (povodne onAttach lambda v setContent). */
    private fun attachVideo(layout: VLCVideoLayout) {
        videoLayout = layout
        engine.player.attachViews(layout, null, false, false)
        // M539-fix2: novy prehravac cakal na svoj (novy) surface — spusti ho teraz
        if (engine.onSurfaceAttached()) {
            layout.post { runCatching { if (!engine.tornDown) engine.player.play() } }
        }
        // vlastny titulkovy overlay nad videom (DVB titulky dekódujeme sami,
        // do libVLC nejdu) — synchronizovany na cas prehravaca
        subOverlay?.let { old ->
            old.stopTicker()
            (old.parent as? ViewGroup)?.removeView(old)   // nenechaj zamrznuty stary overlay (dvojity text)
        }
        val ov = SubtitleOverlayView(layout.context)
        ov.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        layout.addView(ov)
        subOverlay = ov
        ov.start(
            clockSource = { if (engine.ready) engine.player.time else 0L },
            aspectSource = {
                val vt = if (engine.ready) runCatching { engine.player.currentVideoTrack }.getOrNull() else null
                if (vt != null && vt.width > 0 && vt.height > 0) {
                    val sn = if (vt.sarNum > 0) vt.sarNum else 1
                    val sd = if (vt.sarDen > 0) vt.sarDen else 1
                    (vt.width.toFloat() * sn) / (vt.height.toFloat() * sd)
                } else 16f / 9f
            }
        )
    }

    /** M675: orientácia, keep-screen-on, stream locky a immersive fullscreen (vyňaté z onCreate). */
    private fun setupWindow() {
        // predvolene otacanie obrazovky podla nastavenia (auto = fullUser ako v manifeste)
        runCatching {
            requestedOrientation = when (OrientationPref.get(this)) {
                OrientationPref.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationPref.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        }
        remoteDebug = RemoteDebugPref.isEnabled(this)
        // Drz obrazovku zapnutu od startu prehravaca (setric/ambient na boxoch sa nesmie spustit)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamLocks.acquire()  // M452

        // Immersive fullscreen — skry status aj navigacnu listu, nech
        // neprekryvaju ovladanie. Listy sa daju vytiahnut potiahnutim.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** M675: príprava live-zapping stavu a state holderov pred setContent (vyňaté z onCreate). */
    private fun setupLiveState(
        args: PlayerArgs,
        server: sk.tvhclient.shared.model.TvhServer,
        channelUuid: String?,
        channelTitle: String,
        directUrl: String?,
        streamUrl: String,
        progStart: Long,
        progStop: Long,
        progTitle: String
    ) {
        // Live zapping: priprav zoznam susednych kanalov
        if (directUrl == null && channelUuid != null && LivePlaylist.channels.isNotEmpty()) {
            live.uuids = LivePlaylist.channels.map { it.uuid }
            live.names = LivePlaylist.channels.map { it.name }
            live.index = LivePlaylist.index.takeIf { it in live.uuids.indices }
                ?: live.uuids.indexOf(channelUuid)
            live.server = server
            switcher.saveLastLive(server.id, channelUuid)
            epg.hydrateEpgFromDisk(server)   // M275: nacitaj EPG z disku (prezije restart boxu)
        }
        switcher.rememberPlayback()   // M494: uz pri starte, nie az po prvom prepnuti
        live.channelsState.value = LivePlaylist.channels
        // M281: hned dopln now/next z cache (disk/proces) na viditelny zoznam, nech sa nazvy
        // relacii pod kanalmi ukazu okamzite aj po restarte (predtym cakali na sietovy refresh).
        epg.applyCachedEpgToChannels()
        // M605-fix: zoznam najprv — posledny kanal sa spusti normalne (hra za zoznamom
        // ako nahlad) a zoznam sa otvori hned; povodne nehralo nic, co pouzivatel nechcel
        listFirst = args.listFirst && live.uuids.size > 1
        live.indexState.value = live.index
        live.titleState.value = channelTitle
        live.uuidState.value = channelUuid
        live.progStartState.value = progStart
        live.progStopState.value = progStop
        live.progTitleState.value = progTitle
        val canZap = directUrl == null && live.uuids.size > 1
        seekablePlayback = directUrl != null
        // predvolene zvyraznenie ovladacieho panela = play (nie krizik)
        controlNavState.value = playerControlOrder(canZap, seekablePlayback, pipButtonVisible(), timeshift.engaged.value, profileSwitchAvailable(), dvrRecordVisible(), teletextVisible()).indexOf("play").coerceAtLeast(0)
        stream.currentStreamUrl = streamUrl
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mini radio (M340) nesmie hrat popri plnom prehravaci
        RadioPlayerService.stop(this)
        // Zavri predoslu instanciu prehravaca (napr. visiacu v PiP so starym kanalom),
        // nech pri prepnuti kanala nezostane stara PiP visiet. Nova sa otvori na celu obrazovku.
        // M427: ak stara instancia visi v PiP, obycajny finish() zavrie aktivitu,
        // ale pripnute PiP okno (pinned task) moze ostat visiet ako prazdna karta
        // — systemu treba povedat, nech odstrani cely task. Mimo PiP staci finish().
        liveInstance?.get()?.let { old ->
            if (old !== this) runCatching {
                if (old.isInPictureInPictureMode) old.finishAndRemoveTask() else old.finish()
            }
        }
        liveInstance = java.lang.ref.WeakReference(this)
        val args = PlayerArgs.from(intent)   // M658: vsetky intent extra na jednom mieste
        // Navrat na povodny zivy kanal po zatvoreni (pri "Prehrat od zaciatku" z prehravaca)
        returnLiveUuid = args.returnLiveUuid
        returnLiveTitle = args.returnLiveTitle
        setupWindow()

        val channelUuid = args.channelUuid
        val channelTitle = args.channelTitle
        val directUrl = args.directUrl
        live.playKind = args.playKind
        val durationMs = args.durationMs
        val progStart = args.progStart
        val progStop = args.progStop
        val progTitle = args.progTitle
        dvr.uuid = args.dvrUuid
        val progStartFrac = args.progStartFrac
        val progStopFrac = args.progStopFrac
        dvr.durationMs = durationMs
        dvr.durationState.value = durationMs
        dvr.recording = args.dvrRecording
        dvr.progStartSec = args.dvrProgStartSec
        dvr.progStopSec = args.dvrProgStopSec
        dvr.realStartSec = args.dvrRealStartSec
        // Prebiehajuca relacia: dlzka rastie k zivej hrane; bar musi byt VZDY viditelny.
        // Ak mame hranice relacie, dopocitavame relativne k jej zaciatku (cap dlzkou relacie).
        // Ak hranice chybaju (nahravka nema vyplnene start/stop), drzime krok s dlzkou z VLC.
        // M658: vypocet a sekundovy cyklus su v DvrDurationTicker (M528 vnutri).
        if (dvr.recording) {
            DvrDurationTicker(
                scope = lifecycleScope,
                playerLength = { if (engine.ready) engine.player.length else 0L },
                isRecording = { dvr.recording },
                current = { dvr.durationMs },
                set = { dvr.durationMs = it; dvr.durationState.value = it }
            ).start(durationMs, dvr.progStartSec, dvr.progStopSec)
        }
        val server = Tvh.store.active()
        if (server == null || (channelUuid == null && directUrl == null)) {
            finish()
            return
        }
        dvr.serverId = server.id

        // Ulozena pozicia: ponuknut obnovenie ak nie je dopozerane a nie je
        // tesne na zaciatku/konci
        val saved = dvr.uuid?.let { WatchProgress.get(this, server.id, it) }
        val resumeMs = if (saved != null && !saved.completed && saved.posMs > 30_000 &&
            (durationMs <= 0 || durationMs - saved.posMs > 60_000)
        ) saved.posMs else 0L

        engine.create()   // M539: libVLC + MediaPlayer + listener (znovupouzitelne pri obnove po zaseknuti zvuku)
        stall.start()

        // DVR: priame dvrfile URL (s creds). Live: profil servera (M383 — per-kanal
        // override zruseny, profil sa da prepnut priamo v prehravaci).
        val streamUrl = directUrl ?: Tvh.liveUrl(
            server, channelUuid!!, channelTitle,
            server.profile.ifBlank { "pass" }
        )

        // Server je potrebny aj v DVR rezime (seekDvrTo / reopenDvrLive cez feeder).
        // Live-zapping nizsie zavisi od liveUuids (pri DVR prazdne), nie od liveServer.
        live.server = server
        tracks.currentProfile.value = server.profile.ifBlank { "pass" }
        // M476: prepinac profilu plati aj pre HTSP — protokol ho podporuje od v16
        tracks.profileSwitch.value = directUrl == null && channelUuid != null
        // M383: prednacitaj zoznam profilov (dotykove tlacidlo otvara menu priamo,
        // bez openProfileMenu) — fallback hned, servrovy zoznam async
        if (tracks.profileItems.value.isEmpty()) {
            tracks.profileItems.value =
                ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() }
            lifecycleScope.launch {
                val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    sk.tvhclient.shared.Tvh.streamProfiles(server)
                }
                if (list.isNotEmpty()) tracks.profileItems.value = list
            }
        }
        setupLiveState(args, server, channelUuid, channelTitle, directUrl, streamUrl, progStart, progStop, progTitle)
        val canZap = directUrl == null && live.uuids.size > 1

        setContent {
            val pThemeMode = PlayerThemePref.stateOf(this).value
            val pDark = when (pThemeMode) {
                PlayerThemePref.DARK -> true
                PlayerThemePref.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(
                colorScheme = when {
                    UiModePref.get(this) == UiModePref.MODERN && pDark -> modernColorScheme()
                    UiModePref.get(this) == UiModePref.MODERN -> modernLightColorScheme()
                    pDark -> darkColorScheme()
                    else -> lightColorScheme()
                }
            ) {
            // M539-fix4: cela PlayerUi je klucovana na generaciu prehravaca — po vymene
            // MediaPlayera (zaseknuty zvuk) sa zlozi nanovo s novym `player`. Bez toho
            // bezali LaunchedEffect-y (napr. auto-vyber audio stopy) so starym, uz
            // uvolnenym objektom -> IllegalStateException „can't get VLCObject instance".
            androidx.compose.runtime.key(videoSurfaceGen.value) {
            PlayerUi(
                title = live.titleState.value,
                player = engine.player,
                flags = PlaybackFlags(
                    seekable = directUrl != null,  // DVR nahravka = da sa pretacat; live nie
                    inPip = inPipState.value,
                    pipSupported = pip.supported,
                    pipButton = pipButtonVisible(),
                    hasVideo = vlcEvents.hasVideo.value,
                    reconnecting = reconnect.reconnecting.value,
                    seeking = seekingState.value,
                    playing = isPlayingState.value,
                    sleepDeadline = sleep.deadlineState.value,
                    timeshiftEngaged = timeshift.engaged.value,
                    tsMaxMs = timeshift.maxRewindMs(),
                    timeshiftOffsetMs = timeshift.offsetMs.value,
                    returnLiveOnBack = returnLiveUuid != null,
                ),
                dvr = DvrSeekArgs(
                    knownDurationMs = dvr.durationState.value,  // dlzka z DVR entry; pri prebiehajucej nahravke rastie k zivej hrane
                    resumeMs = resumeMs,
                    uuid = dvr.uuid,
                    onSkipBack = { timeshiftSkip(-30) },
                    onSkipFwd = { timeshiftSkip(+30) },
                    onDoubleTapSeek = { fwd -> doubleTapSeek(fwd) },
                    onScrubSeek = { secs -> scrubSeek(secs) },
                    seekHint = dvrSeek.hint.value,
                    scrubFrac = scrub.fraction.value,
                    recordingLive = dvr.recording,
                    recordingStopSec = dvr.progStopSec,
                    recordingOffsetMs = if (dvr.progStartSec > 0 && dvr.realStartSec in 1 until dvr.progStartSec)
                        (dvr.progStartSec - dvr.realStartSec) * 1000 else 0L,
                    onPlayheadMs = { dvr.playheadMsState.value = it },
                    seekSeedMs = dvr.seekSeedState.value,
                    onSeekSeedHandled = { dvr.seekSeedState.value = -1L },
                    onSeekToMs = { ms -> dvrSeek.seekAbsolute(ms) },
                    resumeSel = resumeSelState.value,
                    resumeAnswer = resumeAnswerState.value,
                    onAskResumeChange = {
                        resumePromptState.value = it
                        if (it) { resumeSelState.value = 1; resumeAnswerState.value = 0 }
                    },
                    onResumeAnswerHandled = { resumeAnswerState.value = 0 },
                ),
                programme = ProgrammeArgs(
                    startFrac = progStartFrac,
                    stopFrac = progStopFrac,
                    startSec = live.progStartState.value,
                    stopSec = live.progStopState.value,
                    title = live.progTitleState.value,
                    centerLogoUrl = live.channelsState.value.getOrNull(live.indexState.value)?.piconUrl,
                    nextTitle = live.nextTitleState.value,
                    nextStart = live.nextStartState.value,
                    nextStop = live.nextStopState.value,
                ),
                server = server,
                liveChannelUuid = if (directUrl == null) live.uuidState.value else null,
                preferredAudio = AudioPref.get(this),
                serverId = server.id,
                htspSpuItems = if (stream.htspStreamState.value) {
                    @Suppress("UNUSED_EXPRESSION") tracks.listVersion.value  // refresh ked pribudne stopa
                    tracks.htspSpuItems()
                } else null,
                htspSpuCurrentId = tracks.selectedSubEs.value,
                onPickHtspSpu = if (stream.htspStreamState.value) pickHtspSpuCb else null,   // M544: bez lambdy v kompozicii
                onPickHttpSpu = { id -> tracks.httpSpuUserPick(id) },
                callbacks = PlayerCallbacks(
                    onAttach = { layout -> attachVideo(layout) },
                    onStart = {
                        // M658: HTSP/HTTP/DVR vetvenie prveho spustenia — ChannelSwitcher.playInitial
                        val doPlay: () -> Unit = { switcher.playInitial(server, channelUuid, directUrl, streamUrl) }
                        // rodicovsky zamok: pri KAZDOM otvoreni prehravaca so zamknutym kanalom
                        // vypytaj PIN (bez ohladu na grace okno). Grace ("nepytat X min") plati len
                        // pri prepinani v ramci otvoreneho prehravaca (zoznam / pozadie / cislice).
                        // M605: dlazdica so zoznamom najprv — zoznam sa otvori hned po starte
                        if (listFirst) window.decorView.post { openChannelList() }
                        if (ParentalLock.channelLockedProtected(this, server.id, channelUuid)) {
                            // M263: zrus stare grace okno, nech zamknuty kanal v tomto sedeni
                            // naozaj vyzaduje PIN (aj keby sa pouzivatel cez vyzvu prepol prec a vratil sa).
                            ParentalLock.clearGrace(this)
                            requestPin(onOk = doPlay, onCancel = { finish() }, channelIndex = live.index)
                        } else doPlay()
                    },
                    onOpenEpg = { openEpgInApp() },
                    onEnterPip = { enterPipAndMinimize() },
                    onOpenSleep = { openSleepMenu() },
                    onTrackMenuChange = { kind ->
                        // M349-fix: composable hlasi aj DRUH menu — bez toho ostal
                        // trackMenuKind "audio" z minula a vyber titulkov cez D-pad
                        // omylom prepinal zvukovu stopu
                        trackMenuOpen = kind != null
                        if (kind != null) { tracks.menuKind = kind; tracks.navIndex.value = 0 }
                        // M383: poistka — profile menu otvorene dotykom bez zoznamu
                        if (kind == "profile" && tracks.profileItems.value.isEmpty()) {
                            tracks.profileItems.value =
                                ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() }
                        }
                    },
                    onOptionsSelect = { idx -> selectOption(idx) },
                    onOptionsChange = { optionsOpen = it },
                    onControlsVisibleChange = { controlsShown = it },
                    onOrientationLockChange = { locked ->
                        runCatching {
                            requestedOrientation =
                                if (locked) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                                else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                        }
                    },
                    onPrevChannel = if (canZap) prevChannelCb else null,   // M544
                    onNextChannel = if (canZap) nextChannelCb else null,   // M544
                    onTogglePlay = { togglePlayPause() },
                    onRequestExit = {
                        // M344: hrajuce radio v modernom nekonci — ide do mini prehravaca,
                        // takze potvrdzovacia otazka nema zmysel; TV live ju ma dalej
                        if (!radioHandoffIfPossible()) {
                            exitConfirmSelState.value = 0; exitConfirmState.value = true
                        }
                    },
                    onClose = { closePlayer() },
                ),
                channelList = ChannelListArgs(
                    navIndex = navChannelIndexState.value,
                    groupLabel = activeGroupLabelState.value,
                    groupPicker = groupPickerState.value,
                    onOpenChange = {
                        channelListOpen = it
                        if (it) navChannelIndexState.value = live.index.coerceAtLeast(0)
                    },
                    onLoadEpg = { uuid, cb ->
                        val cached = epg.upcoming.value[uuid]
                        if (!cached.isNullOrEmpty()) {
                            cb(cached)
                        } else {
                            lifecycleScope.launch {
                                val list = runCatching {
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        Tvh.fetchEpgForChannel(server, Tvh.apiFor(server), uuid)
                                    }
                                }.getOrDefault(emptyList())
                                epg.cacheChannelEpg(uuid, list)   // M274: memoizuj pre dalsie zobrazenia/reopen
                                cb(list)
                            }
                        }
                    },
                    channels = if (canZap) live.channelsState.value else emptyList(),
                    currentIndex = live.indexState.value,
                    onSelect = { idx -> switcher.selectChannelOrArchive(idx) },
                    onLongPress = { idx -> ctxMenu.open(idx) },
                    onRefreshEpg = {
                        lifecycleScope.launch { epg.refreshOverlayEpg() }
                    },
                    onRefreshEpgInitial = { epg.refreshOverlayEpgInitial() },
                    onPrefetchEpg = { epg.prefetchEpgIfStale() },
                    epgLoading = epg.loading.value,
                ),
                search = ChannelSearchArgs(
                    active = search.activeState.value,
                    query = search.queryState.value,
                    onQueryChange = { search.setQuery(it) },
                    fieldFocused = search.fieldFocusedState.value,
                    hits = if (search.isActive) search.results() else emptyList(),
                    navIndex = search.navIndexState.value,
                    focusSignal = search.focusSignalState.value,
                ),
                profile = ProfileArgs(
                    openSignal = tracks.openProfileSignal.value,
                    items = tracks.profileItems.value,
                    current = tracks.currentProfile.value,
                    switchAvailable = profileSwitchAvailable(),
                    onPick = { p -> trackMenu.applyProfileChange(p) },
                ),
                modern = ModernOverlayArgs(
                    moreIdList = modernOv.moreIds(),
                    visible = modernOv.visible.value,
                    row = modernOv.row.value,
                    card = modernOv.card.value,
                    strip = modernOv.strip.value,
                    poke = modernOv.poke.value,
                    exec = modernOv.exec.value,
                    execId = modernOv.execId.value,
                    recNames = recInProgressByChan.value.keys,
                    stripIds = modernOv.stripIds(),
                    moreVisible = modernOv.moreVisible.value,
                    moreIndex = modernOv.moreIdx.value,
                    onMorePick = { i -> modernOv.morePick(i) },
                    onMoreDismiss = { modernOv.moreDismiss() },
                    onDismiss = { modernOv.close() },
                ),
                signals = UiSignals(
                    controlsPoke = controlsPokeState.value,
                    infoPoke = infoPokeState.value,
                    openList = openChannelListState.value,
                    closeList = closeChannelListState.value,
                    openOptions = openOptionsState.value,
                    closeOptions = closeOptionsState.value,
                    optionsNavIndex = optionsNavState.value,
                    controlNavIndex = controlNavState.value,
                    trackNavIndex = tracks.navIndex.value,
                    trackListVersion = tracks.listVersion.value,
                    closeMenu = tracks.closeMenuSignal.value,
                    openAudio = tracks.openAudioSignal.value,
                    openSpu = tracks.openSpuSignal.value,
                    lockTick = lockTickState.value,
                    numberEntry = numEntry.entryState.value,
                    zapPoke = live.zapPokeState.value,
                ),
                pin = PinArgs(
                    prompt = pin.promptState.value,
                    len = pin.entryState.value.length,
                    error = pin.errorState.value,
                    onDigit = { d -> pin.digit(d) },
                    onBack = { pin.del() },
                    onCancel = { pin.cancel() },
                    onOpenList = { pin.openList() },
                    gridRow = pin.gridRowState.value,
                    gridCol = pin.gridColState.value,
                )
            )
            }
            // Vyber pri archivovanom kanali (nazivo / od zaciatku) — overlay v style prehravaca
            // M553: teletext — nad prehrávačom, mimo PlayerUi
            if (ttx.openState.value) {
                TeletextOverlay(
                    session = teletext,
                    pageNumber = ttx.pageState.value,
                    subpage = ttx.subState.value,
                    entry = ttx.entryState.value,
                    transparent = ttx.transparentState.value,
                    reveal = ttx.revealState.value,
                    isHttp = !ttx.isHtspLiveServer(),
                    onClose = { closeTeletext() },
                    onStep = { d -> ttx.step(d) },
                    onToggleTransparent = { ttx.toggleTransparent() },
                    touchUi = !isTvDevice(),                 // M559: dotykove ovladanie na telefone
                    onSubStep = { d -> ttx.subStep(d) },
                    onDigit = { d -> ttx.digit(d) }
                )
            }
            if (dvrRec.askState.value.isNotEmpty()) {
                // M606: vyber DVR profilu pred nahravanim
                DvrProfilePickDialog(
                    options = dvrRec.askState.value,
                    subtitle = dvrRec.askTarget?.let { it.first.name + " · " + it.second.title } ?: live.progTitleState.value,
                    lastUsed = Tvh.store.active()?.let { DvrAskPref.lastUsed(this@PlayerActivity, it.id) },
                    selected = dvrRec.askSelState.value,
                    onPick = { dvrRec.resolveAsk(it) },
                    onDismiss = { dvrRec.resolveAsk(null) }
                )
            }
            if (archiveChoiceIdxState.value >= 0) {
                val aCh = live.channelsState.value.getOrNull(archiveChoiceIdxState.value)
                if (aCh != null) {
                    val aSel = archiveChoiceSelState.value
                    Box(
                        Modifier.fillMaxSize().background(Color(0xCC0B1220)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            Modifier.fillMaxWidth(0.8f).widthIn(max = 460.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color(0xFF1B2433))
                                .padding(horizontal = 24.dp, vertical = 28.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(aCh.name, color = Color.White,
                                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (aCh.nowTitle.isNotBlank()) {
                                Spacer(Modifier.height(8.dp))
                                Text(aCh.nowTitle, color = Color(0xFFB9C2D0),
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(androidx.compose.ui.res.stringResource(R.string.channel_archived),
                                    color = Color(0xFFB9C2D0), style = MaterialTheme.typography.bodyMedium)
                                Spacer(Modifier.width(6.dp))
                                androidx.compose.material3.Icon(
                                    Icons.Default.Voicemail, contentDescription = null,
                                    tint = Color(0xFFE53935),
                                    modifier = Modifier.size(18.dp).scale(scaleX = 1f, scaleY = -1f))
                            }
                            Spacer(Modifier.height(26.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .background(if (aSel == 0) Color(0x553B82F6) else Color.Transparent)
                                        .border(1.dp, if (aSel == 0) Color(0xFF3B82F6) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                        .clickable { switcher.resolveArchiveChoice(false) }
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    Text(androidx.compose.ui.res.stringResource(R.string.play_live),
                                        color = if (aSel == 0) Color.White else Color(0xFFB9C2D0),
                                        fontWeight = FontWeight.SemiBold)
                                }
                                Box(
                                    Modifier.clip(RoundedCornerShape(12.dp))
                                        .background(if (aSel == 1) Color(0x553B82F6) else Color.Transparent)
                                        .border(1.dp, if (aSel == 1) Color(0xFF3B82F6) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                        .clickable { switcher.resolveArchiveChoice(true) }
                                        .padding(horizontal = 20.dp, vertical = 12.dp)
                                ) {
                                    Text(androidx.compose.ui.res.stringResource(R.string.play_from_start),
                                        color = if (aSel == 1) Color.White else Color(0xFFB9C2D0),
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            // Kontextove menu kanala (long-press) — overlay v style prehravaca
            if (ctxMenu.idxState.value >= 0) {
                val cIdx = ctxMenu.idxState.value
                val cCh = live.channelsState.value.getOrNull(cIdx)
                val cKeys = ctxMenu.keys(cIdx)
                if (cCh != null && cKeys.isNotEmpty()) {
                    val cSel = ctxMenu.selState.value.coerceIn(0, cKeys.size - 1)
                    val cLocked = remember(lockTickState.value, cCh.uuid) {
                        ParentalLock.isChannelLocked(this@PlayerActivity, live.server?.id, cCh.uuid)
                    }
                    val ctxModern = isModernUi()
                    val ctxAccent = playerAccent()
                    Box(
                        Modifier.fillMaxSize().background(Color(0xCC0B1220))
                            .clickable { ctxMenu.close() },   // ťuknutie mimo zatvori + blokuje pozadie
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            Modifier
                                .then(
                                    if (ctxModern) Modifier.widthIn(min = 300.dp, max = 360.dp)
                                    else Modifier.fillMaxWidth(0.7f).widthIn(max = 440.dp)
                                )
                                .clip(RoundedCornerShape(if (ctxModern) 18.dp else 20.dp))
                                .background(if (ctxModern) Color(0xFF0F1E3D) else Color(0xFF1B2433))
                                .then(
                                    if (ctxModern) Modifier.border(
                                        1.dp, Color(0xFF27407A), RoundedCornerShape(18.dp)
                                    ) else Modifier
                                )
                                .padding(horizontal = 20.dp, vertical = 22.dp)
                        ) {
                            Text(cCh.name, color = Color.White,
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (cCh.nowTitle.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(cCh.nowTitle, color = Color(0xFFB9C2D0),
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.height(18.dp))
                            cKeys.forEachIndexed { i, key ->
                                val label = when (key) {
                                    "info" -> androidx.compose.ui.res.stringResource(R.string.menu_program)
                                    "fromstart" -> androidx.compose.ui.res.stringResource(R.string.play_from_start)
                                    "lock" -> if (cLocked) androidx.compose.ui.res.stringResource(R.string.plock_unlock)
                                              else androidx.compose.ui.res.stringResource(R.string.plock_lock)
                                    "fav" -> {
                                        val sid = (live.server ?: Tvh.store.active())?.id
                                        val isFav = sid != null && Favorites.isFav(this@PlayerActivity, sid, cCh.uuid)
                                        if (isFav) androidx.compose.ui.res.stringResource(R.string.fav_remove)
                                        else androidx.compose.ui.res.stringResource(R.string.fav_add)
                                    }
                                    "rec" -> androidx.compose.ui.res.stringResource(R.string.dvr_rec_button)   // M607
                                    "hide" -> androidx.compose.ui.res.stringResource(R.string.ch_hide)
                                    "unhide" -> androidx.compose.ui.res.stringResource(R.string.ch_unhide_player)  // M541-fix
                                    "reorder" -> androidx.compose.ui.res.stringResource(R.string.fav_reorder)  // M541
                                    else -> key
                                }
                                val rowSel = i == cSel
                                val selBg = if (ctxModern) ctxAccent.copy(alpha = 0.28f) else Color(0x553B82F6)
                                val selBorder = if (ctxModern) ctxAccent else Color(0xFF3B82F6)
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(if (rowSel) selBg else Color.Transparent)
                                        .border(1.dp, if (rowSel) selBorder else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                        .clickable { ctxMenu.selState.value = i; ctxMenu.activate(key) }
                                        .padding(horizontal = 16.dp, vertical = 13.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (ctxModern) {
                                        androidx.compose.material3.Icon(
                                            when (key) {
                                                "info" -> androidx.compose.material.icons.Icons.Default.GridView
                                                "fromstart" -> androidx.compose.material.icons.Icons.Default.PlayArrow
                                                "fav" -> androidx.compose.material.icons.Icons.Default.Star
                                                "rec" -> androidx.compose.material.icons.Icons.Default.FiberManualRecord   // M607
                                                "hide" -> androidx.compose.material.icons.Icons.Default.VisibilityOff
                                                "unhide" -> androidx.compose.material.icons.Icons.Default.Visibility   // M541
                                                "reorder" -> androidx.compose.material.icons.Icons.Default.SwapVert    // M541
                                                else -> androidx.compose.material.icons.Icons.Default.Lock
                                            },
                                            contentDescription = null,
                                            tint = if (rowSel) ctxAccent else Color(0xFFB9C2D0),
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(Modifier.width(12.dp))
                                    }
                                    Text(label,
                                        color = if (rowSel) Color.White else Color(0xFFB9C2D0),
                                        fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                    }
                }
            }
            // M280: Potvrdenie ukoncenia ziveho prehravania (BACK) — styl ako exit dialog v menu.
            // Navigaciu D-pad/OK/BACK riesi dispatchKeyEvent (sekcia 0e); tu len vizual + dotyk.
            if (exitConfirmState.value) {
                val eSel = exitConfirmSelState.value
                Box(
                    Modifier.fillMaxSize().background(Color(0xCC0B1220))
                        .clickable { exitConfirmState.value = false },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        Modifier.fillMaxWidth(0.7f).widthIn(max = 440.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1B2433))
                            .padding(horizontal = 28.dp, vertical = 28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.player_exit_title),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.player_exit_msg),
                            color = Color(0xFFB9C2D0),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Spacer(Modifier.height(24.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Box(
                                Modifier.clip(RoundedCornerShape(12.dp))
                                    .background(if (eSel == 0) Color(0x553B82F6) else Color.Transparent)
                                    .border(1.dp, if (eSel == 0) Color(0xFF3B82F6) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                    .clickable { exitConfirmState.value = false }
                                    .padding(horizontal = 22.dp, vertical = 12.dp)
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.exit_no),
                                    color = if (eSel == 0) Color.White else Color(0xFFB9C2D0),
                                    fontWeight = FontWeight.SemiBold)
                            }
                            Box(
                                Modifier.clip(RoundedCornerShape(12.dp))
                                    .background(if (eSel == 1) Color(0x55FF6B6B) else Color.Transparent)
                                    .border(1.dp, if (eSel == 1) Color(0xFFFF6B6B) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                                    .clickable { finish() }
                                    .padding(horizontal = 22.dp, vertical = 12.dp)
                            ) {
                                Text(androidx.compose.ui.res.stringResource(R.string.exit_yes), color = Color(0xFFFF6B6B),
                                    fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
            // M430 / M628: kompaktny zap pas — cislo · kanal / program · cas / priebeh
            if (zapBar.visible.value && !info.visible.value) ZapBarOverlay(zapBar)
            // Info o relacii (detail) — overlay v style prehravaca (M630: ChannelInfoOverlay)
            if (info.visible.value) {
                ChannelInfoOverlay(
                    channel = info.channel.value,
                    title = info.title.value,
                    time = info.time.value,
                    desc = info.desc.value,
                    recordLabel = if (dvrRecordVisible()) androidx.compose.ui.res.stringResource(
                        if (dvrExistingState.value != null) R.string.dvr_rec_cancel_button else R.string.dvr_rec_button
                    ) else null,
                    recordActive = dvrExistingState.value != null,
                    recordSelected = info.recSel.value,
                    onClose = { info.close() },
                    onRecord = { toggleRecordCurrent() }
                )
            }
            }
        }
    }

    // ---- Picture-in-Picture (M653: PipController.kt) ----
    private val pip: PipController by lazy {
        PipController(this,
            isPlaying = { isPlayingState.value },
            playerReady = { engine.ready },
            isTv = { isTvDevice() },
            togglePlayPause = { togglePlayPause() },
            close = { closeFromPip() })
    }

    /** Spusti PiP (okno plava nad plochou / inou appkou). M349-fix4: ziadny
     *  moveTaskToBack — presun tasku na pozadie hned po vstupe do PiP na
     *  mnohych zariadeniach cerstve PiP okno zrusil (prehravac sa "len zavrel").
     *  Vstup do PiP sam zbali aktivitu do plavajuceho okna, nic dalsie netreba —
     *  auto-PiP cesta to robi rovnako a funguje. */
    private fun enterPipAndMinimize() {
        // M431-fix: BACK cez Compose BackHandler vola tuto funkciu priamo (mimo
        // closePlayer/autoPipIfPossible), takze radio brana musi byt aj tu —
        // inak radio konci v PiP okne. Handoff na pozadie / zatvorenie.
        if (live.playKind == "radio") {
            if (!radioHandoffIfPossible()) finish()
            return
        }
        pip.enterIfPossible()
    }

    /**
     * Auto-PiP pri navigacii v ramci appky (EPG / navrat domov).
     * Vstupi do PiP len na telefonoch (pipSupported), ak hra a este nie je v PiP.
     * Vrati true, ak presiel do PiP (volajuci moze podla toho preskocit finish()).
     */
    private fun autoPipIfPossible(): Boolean {
        // M431: radio do PiP nepatri (zvuk bez obrazu v okne). Namiesto PiP handoff
        // do RadioPlayerService (moderny rezim); v klasiku vrati false a volajuci
        // pokracuje bez PiP (zatvorenie/EPG) — povodne spravanie klasiku bez okna.
        if (live.playKind == "radio") return radioHandoffIfPossible()
        return pip.autoEnterIfPossible()
    }

    // aktualizuj ikonu play/pauza v PiP podla skutocneho stavu prehravania

    private fun closeFromPip() {
        LastPlayback.clear(this)
        finish()
    }

    /** In-progress nahravka dobehla na koniec zapisanych dat (EOF na rastucom HTTP subore).
     *  Po chvili (nech pribudne dalsi blok) znovu otvor stream a vrat sa na poziciu z
     *  prehravacich hodin (offset + prehrany cas relacie) - tak sa pokracuje do novsich dat.
     *  Backoff proti slucke ked nic nove nepribuda (ReconnectController); resetuje sa pri Playing evente. */
    private fun reopenDvrLive() {
        if (!seekablePlayback || !dvr.recording) return
        val url = stream.currentStreamUrl ?: return
        if (!engine.ready) return
        val offsetMs = if (dvr.progStartSec > 0 && dvr.realStartSec in 1 until dvr.progStartSec)
            (dvr.progStartSec - dvr.realStartSec) * 1000 else 0L
        // pozicia v subore = offset + prehrany cas relacie, par sekund vzad ako rezerva
        val startSec = ((offsetMs + dvr.playheadMsState.value) / 1000 - 3).coerceAtLeast(0)
        reconnect.reopenDvrLive { opener.reopenDvrAt(url, startSec) }   // M670
    }

    /** Naplanuje znovupripojenie zivého streamu po vypadku (narastajuce oneskorenie — ReconnectController). */
    private fun scheduleReconnect() {
        if (seekablePlayback) return  // DVR nahravka sa neobnovuje (in-progress riesi reopenDvrLive)
        reconnect.scheduleReconnect { attempt -> opener.reconnectAttempt(attempt, seekablePlayback) }   // M670
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipState.value = isInPictureInPictureMode
        // M383-fix: odlozene otvorenie EPG — az ked je PiP prechod hotovy
        if (isInPictureInPictureMode && pendingEpgAfterPip) {
            pendingEpgAfterPip = false
            launchEpgActivity()
        }
        pip.onModeChanged(isInPictureInPictureMode)   // M578 session + receiver akcii (M653)
        if (!isInPictureInPictureMode) {
            // PiP okno zatvorene pouzivatelom kym bola appka na pozadi: aktivita je uz STOPnuta
            // (stav CREATED, onStop uz prebehol a nechal video bezat). Tu doraz zastav prehravanie,
            // inak by zvuk hral dalej. Ak pouzivatel PiP rozbalil na celu obrazovku, stav je
            // STARTED/RESUMED a prehravac nezastavujeme.
            if (lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED &&
                engine.ready
            ) {
                runCatching { if (engine.player.isPlaying) engine.player.pause() }
                runCatching { engine.player.detachViews() }
            }
        }
    }

    // M672: odchod na pozadie / navrat (M540 standby, M263 PIN, M623 radio na pozadi) v BackgroundResume.kt
    private val bg: BackgroundResume by lazy {
        BackgroundResume(this, engine, live, object : BackgroundResume.Hooks {
            override fun seekable(): Boolean = seekablePlayback
            override fun isTvDevice(): Boolean = this@PlayerActivity.isTvDevice()
            override fun pinPromptShown(): Boolean = pin.promptState.value
            override fun inPip(): Boolean = inPipState.value
            override fun videoLayout(): VLCVideoLayout? = this@PlayerActivity.videoLayout
            override fun saveDvrProgress() { this@PlayerActivity.saveDvrProgress() }
            override fun teardownPlayerAsync() { this@PlayerActivity.teardownPlayerAsync() }
            override fun radioBackground(): Boolean = this@PlayerActivity.radioBackground()
            override fun radioHandoffIfPossible(): Boolean = this@PlayerActivity.radioHandoffIfPossible()
            override fun recreatePlayer() { engine.recreate() }
            override fun replayCurrentLive() { opener.replayCurrentLive() }
            override fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, channelIndex: Int) {
                this@PlayerActivity.requestPin(onOk = onOk, onCancel = onCancel, channelIndex = channelIndex)
            }
        })
    }

    override fun onStart() {
        super.onStart()
        bg.onStart()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-PiP na telefonoch pri odchode z aplikacie.
        // M429: povodny predpoklad "TV boxy nemaju FEATURE_PICTURE_IN_PICTURE" NEPLATI
        // (Homatics, Shield, Raspberry Pi ju maju) — auto-PiP pri HOME sa tam spustal
        // tiez a miniatura ostavala visiet nad launcherom/YouTube (hlasenie z Redditu).
        // Na TV preto pri odchode z appky do PiP nevstupujeme; PiP na TV zostava len
        // vnutri appky (BACK -> miniatura nad TV programom).
        // M431: HOME pocas radia — ziadne PiP okno; v modernom rezime handoff na pozadie
        if (live.playKind == "radio") { radioHandoffIfPossible(); return }
        if (!isTvDevice() && AutoPipPref.get(this) && pip.supported && isPlayingState.value &&
            !(android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)) {
            pip.enterIfPossible()
        }
    }

    override fun onStop() {
        if (bg.onStopBeforeSuper()) { super.onStop(); return }
        super.onStop()
        bg.onStopAfterSuper()
    }

    // ------------------------------------------------------------------
    // M539: vytvorenie prehravaca + obnova po zaseknutom zvukovom vystupe
    // ------------------------------------------------------------------

    // M650: udalosti libVLC v VlcEvents.kt (rovnaka instancia pre kazdy (znovu)vytvoreny prehravac)
    private val vlcEvents: VlcEvents by lazy {
        VlcEvents(
            player = { if (!engine.tornDown && engine.ready) engine.player else null },
            seekable = { seekablePlayback },
            dvrRecording = { dvr.recording },
            htspStream = { stream.htspStream },
            dvrProgStopSec = { dvr.progStopSec },
            actions = object : VlcEvents.Actions {
                override fun scheduleReconnect() { this@PlayerActivity.scheduleReconnect() }
                override fun cancelReconnect() { reconnect.cancel() }
                override fun resetDvrReopen() { reconnect.resetDvrReopen() }
                override fun hideReconnecting() { reconnect.reconnecting.value = false }
                override fun reopenDvrLive() { this@PlayerActivity.reopenDvrLive() }
                override fun recoverAfterSeek(): Boolean = dvrSeek.recoverAfterSeek()
                override fun setPlaying(playing: Boolean) { isPlayingState.value = playing }
                override fun refreshPipIfActive() { pip.refreshIfActive() }
                override fun onPlayingForSeek() { dvrSeek.onPlaying() }
                override fun onPlayingForStall() { stall.onPlaying() }
                override fun applyPendingSpuRestore() { lifecycleScope.launch { this@PlayerActivity.applyPendingSpuRestore() } }
                override fun applyDesiredSpu() { lifecycleScope.launch { tracks.applyDesiredSpu() } }
                override fun maybeApplyAfr() { afr.apply() }
                override fun keepScreenOn(on: Boolean) { this@PlayerActivity.keepScreenOn(on) }
                override fun hideSeekSpinner() { this@PlayerActivity.hideSeekSpinner() }
                override fun scheduleTrackRefresh() { tracks.scheduleRefresh() }
                override fun maybeReparseForTracks() { this@PlayerActivity.maybeReparseForTracks() }
                override fun bumpTrackList() { tracks.bumpListVersion() }
                override fun saveDvrProgress() { this@PlayerActivity.saveDvrProgress() }
                override fun onReachedEnd() { dvr.reachedEnd = true }
                override fun showPlaybackError() {
                    Toast.makeText(this@PlayerActivity, getString(R.string.playback_error, "VLC"), Toast.LENGTH_LONG).show()
                }
            })
    }

    // M539 / M649: hlidac zaseknuteho zvukoveho vystupu v StallWatchdog.kt
    private val stall: StallWatchdog by lazy {
        StallWatchdog(this,
            player = { if (!engine.tornDown && engine.ready) engine.player else null },
            recreateAllowed = { !seekablePlayback && !reconnect.reconnecting.value },
            onRecreate = { engine.recreate(); opener.replayCurrentLive() })
    }

    /** Pred kazdym novym mediom: ak je vystup zaseknuty, vymen prehravac (bez cakania). */
    private fun ensureHealthyPlayer() {
        if (!engine.ready) return
        if (!stall.outputStalled()) return
        CrashLogger.report(this, "PlayerActivity.stall", "media change on stalled output -> new player")
        engine.recreate()
    }

    /** M539-fix4: prve spustenie prehravania (onStart z VideoSurface) prebehlo. */
    internal var initialStartDone = false

    /** M535: stop/release libVLC na pracovnom vlakne (VlcEngine.teardownAsync); feedery a hlidac ako prve. */
    private fun teardownPlayerAsync() {
        engine.teardownAsync {
            stall.destroy()   // M539
            stream.htspFeeder?.stop(); stream.htspFeeder = null
            stream.httpFeeder?.stop(); stream.httpFeeder = null
        }
    }

    // --- Doplnenie stop po starte (audio jazyky / DVB titulky) ---
    // Pri prvom napojeni streamu libVLC este nema doparsovane doplnkove ES; jazyky audio
    // M637: obnova zoznamu stop po starte a jednorazovy re-parse v TrackState
    private fun maybeReparseForTracks() {
        tracks.maybeReparse(htspStream = { stream.htspStream }, seekable = { seekablePlayback }, reconnect = { scheduleReconnect() })
    }

    // ---- M626: AFR (M346) a zamky streamu (M452) vyclenene do AfrController / StreamLocks ----
    private val afr: AfrController by lazy {
        AfrController(this, isTvBox,
            player = { if (engine.ready && !engine.tornDown) engine.player else null },
            videoLayout = { videoLayout })
    }
    private val streamLocks: StreamLocks by lazy { StreamLocks(this, "HeadentClient:stream") }

    override fun onDestroy() {
        pip.destroy() // M578 session + receiver (M653)
        teletext.stopHttp()   // M552
        streamLocks.release()  // M452
        epg.flushEpgPersist()     // M456
        afr.clear()
        zap.cancel()   // M407
        saveDvrProgress()
        super.onDestroy()
        // uvolni odkaz, len ak stale ukazuje na tuto instanciu (nie na novsiu)
        if (liveInstance?.get() === this) liveInstance = null
        vlcEvents.destroy()   // M650
        reconnect.destroy()
        sleep.cancel()
        subOverlay?.stopTicker()   // zastav titulkovy ticker skor nez uvolnis mediaPlayer
        timeshift.destroy()
        tracks.cancelRefresh()
        tracks.destroy()
        // M535: stop/release libVLC na pracovnom vlakne (bezne uz prebehlo v onStop
        // pri isFinishing; tu je poistka pre destroy bez predchadzajuceho stop,
        // napr. zabitie systemom pri nedostatku pamate).
        teardownPlayerAsync()
        engine.allowRelease()   // pracovne vlakno smie release()
    }

    companion object {
        /** M622: uvolnenie libVLC z pracovneho vlakna — VlcEngine.releaseVlc. */
        fun releaseVlc(ctx: android.content.Context, mp: org.videolan.libvlc.MediaPlayer, lib: org.videolan.libvlc.LibVLC?, where: String) = VlcEngine.releaseVlc(ctx, mp, lib, where)

        /** M539-fix2: generacia video surface (kluc AndroidView) — zvysenie = novy SurfaceView. */
        val videoSurfaceGen = androidx.compose.runtime.mutableStateOf(0)
        // M539: hlidac zaseknuteho vystupu (sekundove vzorky)
        const val EXTRA_UUID = "channel_uuid"
        const val EXTRA_TITLE = "channel_title"
        const val EXTRA_RETURN_UUID = "return_live_uuid"
        const val EXTRA_RETURN_TITLE = "return_live_title"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_KIND = "play_kind"
        /** M605: otvor prehravac so zoznamom kanalov a BEZ streamu — kanal sa spusti az po vybere. */
        const val EXTRA_LIST_FIRST = "list_first"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_PROG_START = "prog_start"
        const val EXTRA_PROG_STOP = "prog_stop"
        const val EXTRA_PROG_TITLE = "prog_title"
        const val EXTRA_DVR_UUID = "dvr_uuid"
        const val EXTRA_PROG_START_FRAC = "prog_start_frac"
        const val EXTRA_PROG_STOP_FRAC = "prog_stop_frac"
        const val EXTRA_REQUIRE_PIN = "require_pin"
        const val EXTRA_DVR_RECORDING = "dvr_recording"
        const val EXTRA_DVR_PROG_START_SEC = "dvr_prog_start_sec"
        const val EXTRA_DVR_PROG_STOP_SEC = "dvr_prog_stop_sec"
        const val EXTRA_DVR_REAL_START_SEC = "dvr_real_start_sec"

        // Odkaz na prave zijucu instanciu prehravaca. Pri otvoreni noveho kanala zavrieme predoslu
        // (aj tu visiacu v PiP), inak by stara PiP zostala visiet so starym kanalom.
        private var liveInstance: java.lang.ref.WeakReference<PlayerActivity>? = null
        /** M394-fix: zavri beziaci TV prehravac (aj PiP) pred startom radia —
         *  stream drzi jediny slot a ucet s limitom 1 pripojenia by radio odmietol. */
        fun closeActive(): Boolean {
            val a = liveInstance?.get() ?: return false
            if (a.isFinishing || a.isDestroyed) return false
            a.runOnUiThread { runCatching { a.finish() } }
            return true
        }

        /** M429: zavri prehravac, ak visi v PiP miniature — vratane pripnuteho okna.
         *  Vola MainActivity.onStop na TV: ked pouzivatel odide z appky (ina appka,
         *  HOME), miniatura nad cudzim obsahom nema co robit. */
        fun closeIfInPip(): Boolean {
            val a = liveInstance?.get() ?: return false
            if (a.isFinishing || a.isDestroyed) return false
            if (android.os.Build.VERSION.SDK_INT < 24 || !a.inPipState.value) return false
            a.runOnUiThread { runCatching { a.finishAndRemoveTask() } }
            return true
        }
    }
}

/**
 * M537: ma BACK pri cistom zivom prehravani ukazat potvrdenie ukoncenia?
 * Zariadenia bez PiP (telefony bez PiP, Strong): vzdy. TV/leanback s PiP
 * (Homatics, Shield, RPi): ano, pokial nema prednost auto-PiP handler
 * (zapnuty auto-PiP -> BACK = miniatura). Telefon s PiP: nie.
 * Samostatna composable, aby nerastla PlayerUi (64 KB limit metody).
 */
@Composable
internal fun exitConfirmOnBack(pipSupported: Boolean, autoPipEnabled: Boolean): Boolean {
    if (!pipSupported) return true
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isTvUi = remember {
        (ctx.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    return isTvUi && !autoPipEnabled
}


/**
 * M539-fix2: video surface prehravaca. `PlayerActivity.videoSurfaceGen` meni kluc —
 * po vymene MediaPlayera (zaseknuty zvuk po standby) dostane novy prehravac
 * uplne novy SurfaceView. Zdielanie stareho surface s novym prehravacom
 * skoncilo zamrznutym obrazom: stary vout ho este drzal ako producenta a novy
 * MediaCodec sa nan nepripojil. onStart (prve spustenie prehravania) bezi len
 * pri prvom surface.
 */
@Composable
private fun VideoSurface(
    modifier: Modifier,
    onAttach: (VLCVideoLayout) -> Unit,
    onStart: () -> Unit
) {
    // M539-fix4: priznak prveho spustenia je v aktivite (remember by sa pri
    // rekompozicii cez key(videoSurfaceGen) vynuloval a onStart by bezal znova)
    val act = androidx.compose.ui.platform.LocalContext.current as? PlayerActivity
    val gen = PlayerActivity.videoSurfaceGen.value
    androidx.compose.runtime.key(gen) {
        AndroidView(
            modifier = modifier,
            factory = { ctx ->
                val layout = VLCVideoLayout(ctx)
                layout.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                onAttach(layout)
                // M264: branu (rodicovsky zamok pri otvoreni) spusti az po pripojeni surface
                // na cistom looper tiku. Zapis pinPromptState priamo v Compose layout faze
                // sa pri studenom starte (prve otvorenie) niekedy stratil -> PIN sa nevypytal.
                if (act == null || !act.initialStartDone) {
                    act?.initialStartDone = true
                    layout.post { onStart() }
                }
                layout
            }
        )
    }
}

/** Jedna stopa (audio alebo titulky) z libVLC. */
internal data class TrackItem(val id: Int, val name: String)

/** ISO-639-2 (3-pismenove, B aj T varianty) -> ISO-639-1 pre caste jazyky. */
private val ISO639_2to1 = mapOf(
    "slo" to "sk", "slk" to "sk", "cze" to "cs", "ces" to "cs",
    "eng" to "en", "ger" to "de", "deu" to "de", "hun" to "hu",
    "pol" to "pl", "rus" to "ru", "fre" to "fr", "fra" to "fr",
    "spa" to "es", "ita" to "it", "dut" to "nl", "nld" to "nl",
    "por" to "pt", "rum" to "ro", "ron" to "ro", "ukr" to "uk",
    "gre" to "el", "ell" to "el", "hrv" to "hr", "srp" to "sr",
    "tur" to "tr", "ara" to "ar", "jpn" to "ja", "kor" to "ko",
    "zho" to "zh", "chi" to "zh", "mkd" to "mk", "mac" to "mk",
    "slv" to "sl", "bul" to "bg", "scc" to "sr", "scr" to "hr"
)

/** ISO-639 kod jazyka (napr. "slo","eng") -> citatelny nazov v jazyku zariadenia.
 *  Vracia null ak je kod prazdny / neznamy ("und"), aby sa pouzil fallback. */
internal fun langDisplay(code: String?): String? {
    val c = code?.lowercase()?.trim() ?: return null
    if (c.isEmpty() || c == "und" || c == "unknown" || c == "qaa") return null
    val iso2 = ISO639_2to1[c] ?: if (c.length == 2) c else null
    return try {
        if (iso2 != null) {
            val n = java.util.Locale(iso2).displayLanguage
            if (n.isNotBlank() && !n.equals(iso2, ignoreCase = true))
                n.replaceFirstChar { it.uppercase() }
            else c.uppercase()
        } else c.uppercase()
    } catch (_: Throwable) { c.uppercase() }
}

/** ISO-639 kod -> ANGLICKY nazov jazyka. libVLC pomenuva DVB titulky anglicky
 *  ("DVB subtitles - [Czech]") a netaguje ich kodom, takze vyber z metadat parujeme
 *  na realnu libVLC stopu cez tento anglicky nazov. null ak sa neda urcit. */
/** Mapa ES id -> jazyk z metadat aktualneho media (audio aj titulky maju language). */
internal fun MediaPlayer.trackLanguages(): Map<Int, String?> {
    val out = HashMap<Int, String?>()
    val m = media ?: return out
    try {
        val count = m.trackCount
        for (i in 0 until count) {
            val t = m.getTrack(i) ?: continue
            out[t.id] = t.language
        }
    } catch (_: Throwable) {
    } finally {
        runCatching { m.release() }
    }
    return out
}

/**
 * M527: nazov stopy, ked ju libVLC nepomenovala ani neuviedla jazyk.
 * Beri text z prekladov — tieto funkcie nie su @Composable, takze
 * stringResource tu nejde a citame ho cez ulozeny kontext aplikacie.
 */
private fun trackFallbackName(resId: Int, id: Int): String =
    runCatching {
        sk.tvhclient.shared.storage.AppContextHolder.context.getString(resId) + " " + id
    }.getOrDefault("#$id")

internal fun MediaPlayer.audioTrackItems(): List<TrackItem> {
    val descs = audioTracks ?: return emptyList()
    val langs = trackLanguages()
    // id < 0 je vstavana "Disable" polozka libVLC — preskoc (audio sa nevypina)
    return descs.filter { it.id >= 0 }.map { d ->
        val disp = langDisplay(langs[d.id])
        val base = d.name
        val name = when {
            disp != null -> disp
            !base.isNullOrBlank() -> base
            // M527: nazov stopy z prekladov — natvrdo pisany text sa zobrazoval
        // po slovensky aj v inojazycnom rozhrani
        else -> trackFallbackName(R.string.track_audio, d.id)
        }
        TrackItem(d.id, name)
    }
}

internal fun MediaPlayer.spuTrackItems(): List<TrackItem> {
    val descs = spuTracks ?: return emptyList()
    val langs = trackLanguages()
    // id < 0 je vstavana "Disable" polozka libVLC — preskoc; vypnutie titulkov
    // riesi TrackMenu vlastnym riadkom "Vypnute" (allowOff), inak by boli dva
    return descs.filter { it.id >= 0 }.map { d ->
        val disp = langDisplay(langs[d.id])
        val base = d.name
        val name = when {
            disp != null -> disp
            !base.isNullOrBlank() -> base
            else -> trackFallbackName(R.string.track_subtitles, d.id)   // M527
        }
        TrackItem(d.id, name)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlayerUi(
    title: String,
    player: MediaPlayer,
    flags: PlaybackFlags,
    dvr: DvrSeekArgs,
    programme: ProgrammeArgs = ProgrammeArgs(),
    server: sk.tvhclient.shared.model.TvhServer? = null,
    liveChannelUuid: String? = null,
    preferredAudio: List<String> = emptyList(),
    serverId: String? = null,
    htspSpuItems: List<TrackItem>? = null,   // != null => HTSP: kompletny zoznam titulkov z metadat
    htspSpuCurrentId: Int = -1,
    onPickHtspSpu: ((Int) -> Unit)? = null,
    onPickHttpSpu: ((Int) -> Unit)? = null,
    callbacks: PlayerCallbacks,
    modern: ModernOverlayArgs = ModernOverlayArgs(),
    channelList: ChannelListArgs = ChannelListArgs(),
    signals: UiSignals = UiSignals(),
    search: ChannelSearchArgs = ChannelSearchArgs(),
    // M383: prepinac stream profilu
    profile: ProfileArgs = ProfileArgs(),
    pin: PinArgs = PinArgs()
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    // Moderny rezim (telefon): vysuvaci panel "Viac" (zvuk/titulky/casovac/zamok/info)
    var showMoreSheet by remember { mutableStateOf(false) }
    // M473: nahravanie prave beziacej relacie z panela "Viac".
    // Composable je mimo triedy aktivity, takze sa k nej dostaneme cez kontext.
    val dvrActivity = LocalContext.current as? PlayerActivity
    // M490: stav drzi Activity (dvrCanRecordState / dvrEventIdState /
    // dvrExistingState), aby ho videl aj klasicky bar a TV overlay.
    // Pri otvoreni panela ho este raz osviezime — relacia sa mohla prepnut.
    LaunchedEffect(showMoreSheet) {
        if (showMoreSheet) dvrActivity?.refreshDvrState()
    }
    // odpocet casovaca uspatia (aktualizuje sa kym je casovac aktivny)
    var sleepNow by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(flags.sleepDeadline) {
        while (flags.sleepDeadline > 0) {
            sleepNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(20_000)
        }
    }
    val sleepLeftMin = if (flags.sleepDeadline > 0)
        (((flags.sleepDeadline - sleepNow) + 59_999) / 60_000).coerceAtLeast(0) else 0L
    var showChannelList by remember { mutableStateOf(false) }
    // vizualne vysunutie zoznamu zhora: 0 = zatvoreny, 1 = otvoreny (pocas tahania sleduje prst)
    var listFrac by remember { mutableStateOf(0f) }
    val listScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(showChannelList) {
        androidx.compose.animation.core.animate(
            initialValue = listFrac,
            targetValue = if (showChannelList) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(220)
        ) { v, _ -> listFrac = v }
    }
    // MX Player gesta (len telefon): overlaye pre hlasitost / jas / seek; -1 = skryte
    var volPctState by remember { mutableStateOf(-1) }
    var brightPctState by remember { mutableStateOf(-1) }
    var scrubSecState by remember { mutableStateOf(Int.MIN_VALUE) }   // MIN_VALUE = skryte
    val ctxTvGest = androidx.compose.ui.platform.LocalContext.current
    val isTvGest = remember {
        val um = ctxTvGest.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    LaunchedEffect(volPctState) { if (volPctState >= 0) { kotlinx.coroutines.delay(700); volPctState = -1 } }
    LaunchedEffect(brightPctState) { if (brightPctState >= 0) { kotlinx.coroutines.delay(700); brightPctState = -1 } }
    LaunchedEffect(scrubSecState) { if (scrubSecState != Int.MIN_VALUE) { kotlinx.coroutines.delay(700); scrubSecState = Int.MIN_VALUE } }
    var isPlaying by remember { mutableStateOf(true) }
    // Live okno: meraná pozícia náhľadového boxu v EPG browseri (na presun videopovrchu)
    var previewRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val density = LocalDensity.current
    var orientationLocked by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // menu: null = ziadne, "audio" = audio stopy, "spu" = titulky
    var menu by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }

    // D-pad / dialkove poslalo signal -> zobraz ovladanie (navigaciu panela riesi Activity)
    LaunchedEffect(signals.controlsPoke) {
        if (signals.controlsPoke > 0) controlsVisible = true
    }
    // INFO signal -> prepni okno s detailom relacie
    LaunchedEffect(signals.infoPoke) {
        if (signals.infoPoke > 0) showInfo = !showInfo
    }
    // v PiP rezime skry vsetky ovladacie prvky (okno je male)
    LaunchedEffect(flags.inPip) {
        if (flags.inPip) {
            controlsVisible = false; showInfo = false; showMoreSheet = false
            showChannelList = false; menu = null; showOptions = false
        }
    }
    // oznam Activity ci je ovladanie zobrazene (vtedy D-pad navigaciu riesi Activity)
    LaunchedEffect(controlsVisible) { callbacks.onControlsVisibleChange(controlsVisible) }
    // oznam Activity stav prekryti (kvoli D-pad smerovaniu)
    LaunchedEffect(menu) { callbacks.onTrackMenuChange(menu) }
    LaunchedEffect(showChannelList) { channelList.onOpenChange(showChannelList) }
    LaunchedEffect(showOptions) { callbacks.onOptionsChange(showOptions) }
    // Activity ziada otvorit/zavriet zoznam kanalov (podrzanie OK)
    LaunchedEffect(signals.openList) {
        if (signals.openList > 0) { showChannelList = true; controlsVisible = false }
    }
    LaunchedEffect(signals.closeList) {
        if (signals.closeList > 0) showChannelList = false
    }
    // Moznosti (Zvuk/Titulky/SW) cez D-pad DOLE / MENU
    LaunchedEffect(signals.openOptions) {
        if (signals.openOptions > 0) { showOptions = true; controlsVisible = false }
    }
    LaunchedEffect(signals.closeOptions) {
        if (signals.closeOptions > 0) showOptions = false
    }
    LaunchedEffect(signals.openAudio) { if (signals.openAudio > 0) { menu = "audio"; controlsVisible = false } }
    LaunchedEffect(signals.openSpu) { if (signals.openSpu > 0) { menu = "spu"; controlsVisible = false } }
    LaunchedEffect(profile.openSignal) { if (profile.openSignal > 0) { menu = "profile"; controlsVisible = false } }
    LaunchedEffect(signals.closeMenu) { if (signals.closeMenu > 0) menu = null }
    // ikona play/pause podla skutocneho stavu prehravaca
    LaunchedEffect(flags.playing) { isPlaying = flags.playing }
    // seek stav (len pre DVR). TS subor nenese dlzku, takze pouzivame:
    //  - dlzku z DVR entry (knownDurationMs), fallback player.length
    //  - position (zlomok 0..1) na zobrazenie aj pretacanie (na TS spolahlivejsie nez setTime)
    var posFraction by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    // Aktualny cas prehravania v ms (player.time) - plynuly zdroj pozicie pre lavu stranu.
    var posTimeMs by remember { mutableStateOf(0L) }
    // player.time/position su pre rastuci TS nespolahlive (raz bezia, raz stoja, niekedy
    // odpocet offsetu vynuluje lavu stranu). Lavu stranu preto pocitame ako prehraty cas:
    // od zaciatku relacie (0) pridavame realny uplynuly cas, kym sa prehrava - rovnaky
    // wall-clock princip akym spolahlivo funguje prava strana (lengthMs).
    var lastPlayTickMs by remember { mutableStateOf(0L) }
    // Jednorazovy skok na zaciatok relacie v subore (prebiehajuca nahravka s predprogramovym
    // obsahom), aby "od zaciatku" hralo od zaciatku relacie a prehravacie hodiny od 0 sedeli.
    var initialSeekDone by remember { mutableStateOf(false) }
    // M495: pretocenie DVR prebuduje medium s :start-time, takze player.position
    // od tej chvile ukazuje poziciu v NOVOM mediu (zacina na mieste skoku), nie
    // v celom subore. Prepocet suborovej pozicie na cas relacie je odvtedy
    // neplatny — hodiny musia ist z wall-clocku od seedu.
    var rebuiltBySeek by remember { mutableStateOf(false) }

    // Dlzka baru = uplynuty cas relacie (knownDurationMs, plynulo rastie 1s/s).
    // Nepouzivame player.length do skaly - VLC ju pre rastuci TS hlasi v hrubych
    // skokoch, co rozhadzovalo lavu stranu casomiery. Fallback na VLC dlzku len ked
    // EPG cas nemame.
    val lengthMs = if (dvr.knownDurationMs > 0) dvr.knownDurationMs else player.length.coerceAtLeast(0L)
    // Cerstva dlzka pre ticker (LaunchedEffect(Unit) inak zachyti hodnotu zo startu a
    // lava strana by sa nezmestila nad uroven zivej hrany pri starte).
    val lengthMsLive = androidx.compose.runtime.rememberUpdatedState(lengthMs)
    val offsetMsLive = androidx.compose.runtime.rememberUpdatedState(dvr.recordingOffsetMs)
    // M495-fix: to iste plati pre seed z pretocenia. Ticker bezi v LaunchedEffect(Unit),
    // takze si hodnotu parametra zapamata pri PRVEJ kompozicii a novu uz nikdy neuvidi —
    // seed teda nikdy nedorazil, hodiny sa na ciel neprepli a resync ich zrazil takmer
    // na nulu (odtial "0:59" hned po skoku na 40. minutu).
    val seekSeedLive = androidx.compose.runtime.rememberUpdatedState(dvr.seekSeedMs)
    val onSeekSeedHandledLive = androidx.compose.runtime.rememberUpdatedState(dvr.onSeekSeedHandled)
    // Pri prebiehajucej nahravke nedovol pretocit az na zivu hranu (koniec dostupnych dat).
    // Zapisane data zaostavaju za EPG casom (prava strana) o cca 20-30 s, takze rezerva
    // pocitana z EPG casu musi byt vacsia, inak playhead skoci do este nezapisanej zony,
    // narazi na EOF a TS zamrzne. Vacsia rezerva = playhead ostava v spolahlivo nahranych
    // datach. Hltavy koniec doriesi este aj automaticke znovu-otvorenie streamu.
    val liveMarginMs = 45_000L
    // Dlzka pre seekbar = dosiahnutelny rozsah (bez 45 s rezervy pri prebiehajucej nahravke).
    // Tak playhead dosiahne koniec baru bez viditeľnej medzery/"bariery" - rezerva je skryta.
    val barLengthMs = if (dvr.recordingLive) (lengthMs - liveMarginMs).coerceAtLeast(1L) else lengthMs

    // Obnovenie pozicie (len DVR): spytaj sa, a po potvrdeni pretoc ked je
    // media nacitana
    var askResume by remember { mutableStateOf(dvr.resumeMs > 0) }
    var pendingResumeMs by remember { mutableStateOf(0L) }
    // Most na D-pad obsluhu dialogu v Activity: nahlas viditelnost a reaguj na odpoved
    LaunchedEffect(askResume) { dvr.onAskResumeChange(askResume) }
    LaunchedEffect(dvr.resumeAnswer) {
        if (dvr.resumeAnswer != 0 && askResume) {
            if (dvr.resumeAnswer == 1) pendingResumeMs = dvr.resumeMs
            askResume = false
            dvr.onResumeAnswerHandled()
        }
    }

    // Aktualizuj poziciu kazdu sekundu (len ked je seekable a netiahneme)
    // M662: telo tickera vyclanene do DvrPositionTicker.kt (JVM 64 KB limit metody).
    if (flags.seekable) {
        DvrPositionTicker(
            player = player,
            ctx = ctx,
            lengthMsLive = lengthMsLive,
            offsetMsLive = offsetMsLive,
            seekSeedLive = seekSeedLive,
            onSeekSeedHandledLive = onSeekSeedHandledLive,
            recordingLive = dvr.recordingLive,
            liveMarginMs = liveMarginMs,
            dvrUuid = dvr.uuid,
            serverId = serverId,
            posTimeMs = { posTimeMs },
            onPosTimeMsSet = { posTimeMs = it },
            posFraction = { posFraction },
            onPosFractionSet = { posFraction = it },
            initialSeekDone = { initialSeekDone },
            onInitialSeekDoneSet = { initialSeekDone = it },
            rebuiltBySeek = { rebuiltBySeek },
            onRebuiltBySeekSet = { rebuiltBySeek = it },
            pendingResumeMs = { pendingResumeMs },
            onPendingResumeMsSet = { pendingResumeMs = it },
            lastPlayTickMs = { lastPlayTickMs },
            onLastPlayTickMsSet = { lastPlayTickMs = it },
            askResume = { askResume },
            dragging = { dragging },
            onSeekToMs = dvr.onSeekToMs,
            onPlayheadMs = dvr.onPlayheadMs,
        )
    }

    // Live priebeh aktualnej relacie (z EPG): tika po sekundach
    var liveNowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    // Aktualna relacia (mutable — pri dobehnuti sa nacita dalsia)
    var progStart by remember(liveChannelUuid) { mutableStateOf(programme.startSec) }
    var progStop by remember(liveChannelUuid) { mutableStateOf(programme.stopSec) }
    var progTitle by remember(liveChannelUuid) { mutableStateOf(programme.title) }
    var progDesc by remember(liveChannelUuid) { mutableStateOf("") }
    var nextTitle by remember(liveChannelUuid) { mutableStateOf(programme.nextTitle) }
    var nextStart by remember(liveChannelUuid) { mutableStateOf(programme.nextStart) }
    var nextStop by remember(liveChannelUuid) { mutableStateOf(programme.nextStop) }
    val hasLiveProg = !flags.seekable && progStart > 0 && progStop > progStart

    // M663: tik a nacitanie EPG relacie v LiveProgrammeEffects.kt (podmienky a kluce zhodne)
    LiveProgrammeEffects(
        seekable = flags.seekable,
        liveChannelUuid = liveChannelUuid,
        server = server,
        hasLiveProg = hasLiveProg,
        progStart = { progStart },
        progStop = { progStop },
        onTick = { liveNowSec = it },
        onProgramme = { cur, nx ->
            progStart = cur.start; progStop = cur.stop
            progTitle = cur.title
            progDesc = cur.bestDescription
            if (nx != null) {
                nextTitle = nx.title; nextStart = nx.start; nextStop = nx.stop
            }
        }
    )

    // M662: auto-vyber audio stopy (M378) je v AudioAutoSelect.kt
    AudioAutoSelectEffect(
        player = player,
        ctx = ctx,
        liveChannelUuid = liveChannelUuid,
        serverId = serverId,
        preferredAudio = preferredAudio
    )

    LaunchedEffect(controlsVisible, menu, signals.controlsPoke, dragging) {
        if (controlsVisible && menu == null && !dragging) {
            kotlinx.coroutines.delay(3000)
            controlsVisible = false
        }
    }

    // M663: retaz BackHandler-ov v PlayerBackHandlers.kt (poradie = priorita, zachovane)
    val autoPipEnabled = remember { AutoPipPref.get(ctx) }
    PlayerBackHandlers(
        autoPipEnabled = autoPipEnabled,
        pipSupported = flags.pipSupported,
        playing = flags.playing,
        seekable = flags.seekable,
        controlsVisible = controlsVisible,
        menu = menu,
        showChannelList = showChannelList,
        showOptions = showOptions,
        showInfo = showInfo,
        returnLiveOnBack = flags.returnLiveOnBack,
        onEnterPip = callbacks.onEnterPip,
        onClose = callbacks.onClose,
        onRequestExit = callbacks.onRequestExit,
        setShowChannelList = { showChannelList = it },
        setMenu = { menu = it },
        setControlsVisible = { controlsVisible = it }
    )

    // M662: EPG efekty (M266 prefetch + M522/M525 periodicky refresh) su v PlayerEpgEffects.kt
    PlayerEpgEffects(
        showChannelList = showChannelList,
        controlsVisible = controlsVisible,
        modernOvVisible = modern.visible,
        onPrefetchEpg = channelList.onPrefetchEpg,
        onRefreshEpgInitial = channelList.onRefreshEpgInitial,
        onRefreshEpg = channelList.onRefreshEpg
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // M664: MX Player gesta (seek / hlasitost / jas / vysunutie zoznamu) v PlayerGestures.kt
            .playerGestures(
                ctx = ctx,
                isTvGest = isTvGest,
                seekable = flags.seekable,
                timeshiftEngaged = flags.timeshiftEngaged,
                controlsVisible = controlsVisible,
                overlayOpen = { showChannelList || showMoreSheet || menu != null || showOptions },
                listScope = listScope,
                listFrac = { listFrac },
                setListFrac = { listFrac = it },
                scrubSec = { scrubSecState },
                setScrubSec = { scrubSecState = it },
                setVolPct = { volPctState = it },
                setBrightPct = { brightPctState = it },
                setShowChannelList = { showChannelList = it },
                onScrubSeek = dvr.onScrubSeek
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (menu != null) menu = null else controlsVisible = !controlsVisible },
                    onDoubleTap = { off -> dvr.onDoubleTapSeek(off.x > size.width / 2f) }
                )
            }
    ) {
        val inPreview = showChannelList && isTvGest && channelList.channels.isNotEmpty() && previewRect != null
        // M539-fix2: AndroidView je v samostatnej composable (mensia PlayerUi + vymena surface)
        VideoSurface(
            modifier = if (inPreview) {
                val r = previewRect!!
                Modifier
                    .absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            } else Modifier.fillMaxSize(),
            onAttach = callbacks.onAttach,
            onStart = callbacks.onStart
        )

        // M630: male prekryvy v PlayerOverlays.kt (poradie zachovane)
        // Audio-only (rozhlas): namiesto ciernej vycentrovane logo; na TV so zoznamom v nahlade
        if (!flags.hasVideo) RadioCenterLogo(programme.centerLogoUrl, server, if (inPreview) previewRect else null)
        // indikator opätovného pripájania (vypadok siete pri zivom vysielani)
        if (flags.reconnecting) ReconnectingOverlay()
        // koliesko v strede pocas pretacania timeshiftu (resync)
        if (flags.seeking && !flags.reconnecting) SeekingSpinner()
        // YouTube-style hint pri dvojkliku (skok o 10 s)
        if (dvr.seekHint != 0) SeekHintOverlay(dvr.seekHint)
        // MX Player overlaye: hlasitost / jas (vystredene), seek-scrub (hore v strede)
        if (volPctState >= 0 || brightPctState >= 0) GestureLevelOverlay(volPctState, brightPctState)
        if (scrubSecState != Int.MIN_VALUE) ScrubSecondsOverlay(scrubSecState)
        // prekrytie s prave zadavanym cislom kanala
        if (signals.numberEntry.isNotEmpty()) NumberEntryOverlay(signals.numberEntry)

        // M661: ovladaci pruh (horny info blok + seekbar + tlacidla) je v PlayerControlBar.kt
        PlayerControlsOverlay(
            controlsVisible = controlsVisible,
            onControlsVisibleSet = { controlsVisible = it },
            ctx = ctx,
            dvrActivity = dvrActivity,
            title = title,
            seekable = flags.seekable,
            pipButton = flags.pipButton,
            pipSupported = flags.pipSupported,
            timeshiftEngaged = flags.timeshiftEngaged,
            profileSwitch = profile.switchAvailable,
            controlNavIndex = signals.controlNavIndex,
            liveChannels = channelList.channels,
            liveCurrentIndex = channelList.currentIndex,
            server = server,
            liveNowSec = liveNowSec,
            progStart = progStart,
            progStop = progStop,
            progTitle = progTitle,
            progDesc = progDesc,
            nextTitle = nextTitle,
            nextStart = nextStart,
            nextStop = nextStop,
            sleepLeftMin = sleepLeftMin,
            timeshiftOffsetMs = flags.timeshiftOffsetMs,
            barLengthMs = barLengthMs,
            lengthMs = lengthMs,
            recordingOffsetMs = dvr.recordingOffsetMs,
            scrubFrac = dvr.scrubFrac,
            progStartFrac = programme.startFrac,
            progStopFrac = programme.stopFrac,
            dragging = dragging,
            onDraggingSet = { dragging = it },
            dragValue = dragValue,
            onDragValueSet = { dragValue = it },
            posTimeMs = posTimeMs,
            onPosTimeMsSet = { posTimeMs = it },
            onPosFractionSet = { posFraction = it },
            onSeekToMs = dvr.onSeekToMs,
            isPlaying = isPlaying,
            menu = menu,
            onMenuSet = { menu = it },
            onShowChannelListSet = { showChannelList = it },
            showInfo = showInfo,
            onShowInfoSet = { showInfo = it },
            onShowMoreSheetSet = { showMoreSheet = it },
            orientationLocked = orientationLocked,
            onOrientationLockedSet = { orientationLocked = it },
            onOrientationLockChange = callbacks.onOrientationLockChange,
            onPrevChannel = callbacks.onPrevChannel,
            onNextChannel = callbacks.onNextChannel,
            onTogglePlay = callbacks.onTogglePlay,
            onSkipBack = dvr.onSkipBack,
            onSkipFwd = dvr.onSkipFwd,
            onOpenEpg = callbacks.onOpenEpg,
            onEnterPip = callbacks.onEnterPip,
            onOpenSleep = callbacks.onOpenSleep,
            onClose = callbacks.onClose
        )

        // "Viac" panel moderneho rezimu (telefon) — M663: ModernOverlayEffects.kt
        if (showMoreSheet) {
            PlayerMoreSheetHost(
                ctx = ctx,
                pipSupported = flags.pipSupported,
                pipButton = flags.pipButton,
                profileSwitch = profile.switchAvailable,
                orientationLocked = orientationLocked,
                dvrActivity = dvrActivity,
                onEnterPip = callbacks.onEnterPip,
                onOpenSleep = callbacks.onOpenSleep,
                onOrientationLockChange = callbacks.onOrientationLockChange,
                setShowMoreSheet = { showMoreSheet = it },
                setMenu = { menu = it },
                setShowInfo = { showInfo = it },
                setOrientationLocked = { orientationLocked = it }
            )
        }

        // Moderny TV overlay (karty kanalov + ovladacia lista) — exkluzivita,
        // auto-hide a vykonanie akcii z listy (M663: ModernOverlayEffects.kt)
        ModernOverlayEffects(
            modernOvVisible = modern.visible,
            modernOvPoke = modern.poke,
            modernOvExec = modern.exec,
            modernOvExecId = modern.execId,
            modernOvCard = modern.card,
            onModernOvDismiss = modern.onDismiss,
            onSelectChannel = channelList.onSelect,
            onOpenSleep = callbacks.onOpenSleep,
            onOpenEpg = callbacks.onOpenEpg,
            closeOverlays = {
                controlsVisible = false; menu = null; showChannelList = false
                showInfo = false; showOptions = false
            },
            setMenu = { menu = it },
            setShowInfo = { showInfo = it }
        )
        if (modern.visible && isTvGest) {
            val ovSrv = remember { sk.tvhclient.shared.Tvh.store.active() }
            val ovLoader = remember(ovSrv?.id) { PiconImageLoader.get(ctx, ovSrv) }
            ModernTvOverlay(
                channels = channelList.channels,
                currentIndex = channelList.currentIndex,
                cardIndex = modern.card,
                focusRow = modern.row,
                stripIndex = modern.strip,
                stripIds = modern.stripIds,
                recNames = modern.recNames,
                isPlaying = isPlaying,
                tsEngaged = flags.timeshiftEngaged,
                tsOffsetMs = flags.timeshiftOffsetMs,
                tsMaxMs = flags.tsMaxMs,
                imageLoader = ovLoader,
            )
        }

        // Info okno: detail prave beziacej relacie (INFO kláves / tlacidlo) — M661: PlayerInfoWindow.kt
        if (showInfo) {
            PlayerInfoWindow(
                setShowInfo = { v -> showInfo = v },
                title = title,
                seekable = flags.seekable,
                progStart = progStart,
                progStop = progStop,
                progTitle = progTitle,
                progDesc = progDesc,
                nextTitle = nextTitle,
                nextStart = nextStart,
                nextStop = nextStop,
                liveNowSec = liveNowSec,
                liveChannels = channelList.channels,
                liveCurrentIndex = channelList.currentIndex,
                dvrActivity = dvrActivity
            )
        }

        // Overlay: zoznam kanalov priamo v prehravaci (vysuva sa zhora podla listFrac) — TELEFON (M631: PhoneChannelList.kt)
        if ((showChannelList || listFrac > 0.001f) && !isTvGest && channelList.channels.isNotEmpty()) {
            PhoneChannelListOverlay(
                listFrac = listFrac,
                liveChannels = channelList.channels,
                server = server,
                serverId = serverId,
                liveCurrentIndex = channelList.currentIndex,
                channelNavIndex = channelList.navIndex,
                epgLoading = channelList.epgLoading,
                lockTick = signals.lockTick,
                liveNowSec = liveNowSec,
                onSelectChannel = channelList.onSelect,
                onChannelLongPress = channelList.onLongPress,
                onClose = { showChannelList = false }
            )
        }
        // TV zoznam kanalov (M632: TvChannelList.kt)
        if (showChannelList && isTvGest && channelList.channels.isNotEmpty()) {
            TvChannelListOverlay(
                liveChannels = channelList.channels,
                server = server,
                serverId = serverId,
                liveCurrentIndex = channelList.currentIndex,
                channelNavIndex = channelList.navIndex,
                epgLoading = channelList.epgLoading,
                lockTick = signals.lockTick,
                liveNowSec = liveNowSec,
                onLoadChannelEpg = channelList.onLoadEpg,
                channelGroupLabel = channelList.groupLabel,
                channelGroupPicker = channelList.groupPicker,
                searchActive = search.active,
                searchQuery = search.query,
                onSearchQueryChange = search.onQueryChange,
                searchFieldFocused = search.fieldFocused,
                searchHits = search.hits,
                searchNavIndex = search.navIndex,
                searchFocusSignal = search.focusSignal,
                inPreview = inPreview,
                previewRect = previewRect,
                onPreviewRect = { r -> previewRect = r }
            )
        }

        // "Viac" menu modernej listy (M327): Kanaly / Casovac uspatia / Informacie (M633: PlayerMenus.kt)
        if (modern.moreVisible) {
            ModernMoreMenu(
                ids = modern.moreIdList,
                highlightIndex = if (isTvGest) modern.moreIndex else -1,   // M385-fix
                recActive = dvrActivity?.dvrExistingState?.value != null,
                onPick = modern.onMorePick,
                onDismiss = modern.onMoreDismiss
            )
        }

        // Vyber dlzky casovaca uspatia — vertikalne, navigacia z Activity (M633: PlayerMenus.kt)
        if (showOptions) {
            SleepOptionsMenu(
                highlightIndex = if (isTvGest) signals.optionsNavIndex else -1,   // M385-fix
                onSelect = callbacks.onOptionsSelect,
                onDismiss = { showOptions = false }
            )
        }

        // Menu stop (audio / titulky) — M661: TrackMenu.kt
        if (menu != null) {
            PlayerTrackMenu(
                menu = menu,
                setMenu = { v -> menu = v },
                ctx = ctx,
                player = player,
                trackListVersion = signals.trackListVersion,
                trackNavIndex = signals.trackNavIndex,
                profileItems = profile.items,
                currentProfile = profile.current,
                onPickProfile = profile.onPick,
                htspSpuItems = htspSpuItems,
                htspSpuCurrentId = htspSpuCurrentId,
                onPickHtspSpu = onPickHtspSpu,
                onPickHttpSpu = onPickHttpSpu,
                liveChannelUuid = liveChannelUuid,
                serverId = serverId
            )
        }

        // Dialog: obnovit prehravanie od poslednej pozicie? (M559-fix: vytiahnute z PlayerUi — limit 64 kB)
        if (askResume) {
            ResumeDialog(
                resumeMs = dvr.resumeMs, resumeSel = dvr.resumeSel,
                onNo = { askResume = false },
                onYes = { pendingResumeMs = dvr.resumeMs; askResume = false }
            )
        }

        // Rodicovsky zamok: zadanie PIN (cislice z dialkoveho riesi Activity; M633: PlayerMenus.kt)
        if (pin.prompt) {
            PlayerPinPanel(
                pinLen = pin.len, pinError = pin.error,
                gridRow = if (isTvGest) pin.gridRow else -1, gridCol = if (isTvGest) pin.gridCol else -1,
                onDigit = pin.onDigit, onBack = pin.onBack, onOpenList = pin.onOpenList
            )
        }
    }
}

/** M559-fix: dialog „Obnoviť prehrávanie“ — samostatný composable (PlayerUi je na limite veľkosti metódy). */
@Composable
private fun ResumeDialog(resumeMs: Long, resumeSel: Int, onNo: () -> Unit, onYes: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(playerScrimSoft())
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(min = 260.dp, max = 460.dp)   // M562
                .clip(RoundedCornerShape(12.dp))
                .background(playerScrim())
                .padding(20.dp)
        ) {
            Text(
                androidx.compose.ui.res.stringResource(R.string.resume_question),
                color = playerFg(),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                fmtMs(resumeMs),
                color = playerFgDim(),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.align(Alignment.End),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TextChip(androidx.compose.ui.res.stringResource(R.string.no), selected = resumeSel == 0) { onNo() }
                TextChip(androidx.compose.ui.res.stringResource(R.string.yes), selected = resumeSel == 1) { onYes() }
            }
        }
    }
}
