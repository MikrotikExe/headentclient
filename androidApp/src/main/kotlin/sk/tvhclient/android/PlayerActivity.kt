package sk.tvhclient.android

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.htsp.HtspData
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


    // M656: libVLC + MediaPlayer zivotny cyklus vo VlcEngine.kt; tu delegaty pod povodnymi nazvami
    private val engine: VlcEngine by lazy {
        VlcEngine(this, mediaFactory, vlcEvents,
            recreates = { stall.recreates },
            bumpSurfaceGen = { videoSurfaceGen.value = videoSurfaceGen.value + 1 },
            resetStall = { resetStallState() })
    }
    private val libVlc: LibVLC get() = engine.libVlc
    private val playerTornDown: Boolean get() = engine.tornDown
    // M655: stav streamu (feedery, HTSP priznaky, URL) v StreamState.kt; tu delegaty pod povodnymi nazvami
    private val stream = StreamState()
    private var htspFeeder: HtspTsFeeder?
        get() = stream.htspFeeder
        set(v) { stream.htspFeeder = v }
    private var httpFeeder: HttpTsFeeder?
        get() = stream.httpFeeder
        set(v) { stream.httpFeeder = v }
    private var dvrViaFeeder: Boolean
        get() = stream.dvrViaFeeder
        set(v) { stream.dvrViaFeeder = v }
    private var htspLive: Boolean
        get() = stream.htspLive
        set(v) { stream.htspLive = v }
    private val htspStreamState: androidx.compose.runtime.MutableState<Boolean> get() = stream.htspStreamState
    private var htspStream: Boolean
        get() = stream.htspStream
        set(v) { stream.htspStream = v }
    // HTSP titulky: kompletny zoznam jazykov berieme z metadat (feeder.subtitleStreams),
    // nie z libVLC (to ma len jazyky, ktore uz "prehovorili"). Vyber mapujeme na realnu
    // libVLC stopu podla anglickeho nazvu jazyka (libVLC DVB titulky netaguje kodom).
    // M637: stav stop (zvuk/titulky/profil) v TrackState.kt
    private val tracks: TrackState by lazy {
        TrackState(this,
            player = { if (engine.ready && !playerTornDown) mediaPlayer else null },
            htspFeeder = { htspFeeder })
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
    private val htspLiveState: androidx.compose.runtime.MutableState<Boolean> get() = stream.htspLiveState
    // M647: HTSP timeshift v TimeshiftController.kt; tu delegaty pod povodnymi nazvami
    private val timeshift: TimeshiftController by lazy {
        TimeshiftController(lifecycleScope,
            feeder = { htspFeeder },
            onResumePlayback = {
                htspFeeder?.resume()
                isPlayingState.value = true
                if (engine.ready && !mediaPlayer.isPlaying) mediaPlayer.play()
            },
            onSeekSpinner = { showSeekSpinner() })
    }
    private val timeshiftOffsetState: androidx.compose.runtime.MutableState<Long> get() = timeshift.offsetMs
    // timeshift "zapnuty" (po prvej pauze) -> az vtedy davaju zmysel RW/FF a dvojklik
    private val timeshiftEngagedState: androidx.compose.runtime.MutableState<Boolean> get() = timeshift.engaged

    // ===== Moderny TV overlay (karty kanalov + ovladacia lista) — ModernOverlayController.kt (M642) =====
    private val modernOv: ModernOverlayController by lazy {
        ModernOverlayController(live,
            seekable = { seekablePlayback },
            timeshiftEngaged = { timeshiftEngagedState.value },
            profileSwitchAvailable = { profileSwitchAvailable() },
            dvrRecordVisible = { dvrRecordVisible() },
            teletextVisible = { teletextVisible() },
            actions = object : ModernOverlayController.Actions {
                override fun hideZapBar() = this@PlayerActivity.hideZapBar()
                override fun togglePlayPause() = this@PlayerActivity.togglePlayPause()
                override fun timeshiftSkip(seconds: Int) = this@PlayerActivity.timeshiftSkip(seconds)
                override fun switchLive(dir: Int) = this@PlayerActivity.switchLive(dir)
                override fun openChannelList() = this@PlayerActivity.openChannelList()
                override fun openSleepMenu() = this@PlayerActivity.openSleepMenu()
                override fun toggleInfo() = this@PlayerActivity.toggleInfo()
                override fun openProfileMenu() = this@PlayerActivity.openProfileMenu()
                override fun toggleRecordCurrent() = this@PlayerActivity.toggleRecordCurrent()
                override fun openTeletext() = this@PlayerActivity.openTeletext()
                override fun openChannelContextMenu(cardIndex: Int) {
                    okLongFired = true   // guard prehltne OK-up (inak by potvrdil polozku menu)
                    this@PlayerActivity.openChannelContextMenu(cardIndex)
                }
            })
    }
    private val modernOvState: androidx.compose.runtime.MutableState<Boolean> get() = modernOv.visible

    private val isTvBox by lazy {
        (getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Moderny overlay ma zmysel len na TV, v modernom rezime, pri zivom so zoznamom. */
    private fun modernTvActive(): Boolean =
        isTvBox && UiModePref.get(this) == UiModePref.MODERN &&
            !seekablePlayback && liveUuids.size > 1

    private fun modernStripIds(): List<String> = modernOv.stripIds()

    // ===== M490: nahravanie prave beziacej relacie =====
    // Logika zila od M473 vpisana priamo v telefonnom paneli „Viac", takze
    // klasicky bar ani moderny TV overlay ju nemali odkial zavolat. Stav aj
    // akcia su teraz na Activity a zdielaju ich vsetky vstupy.
    val dvrCanRecordState = androidx.compose.runtime.mutableStateOf(false)
    val dvrEventIdState = androidx.compose.runtime.mutableStateOf<Long?>(null)
    val dvrExistingState =
        androidx.compose.runtime.mutableStateOf<sk.tvhclient.shared.model.DvrEntry?>(null)

    /** Ma sa ovladac nahravania vobec ukazat? */
    fun dvrRecordVisible(): Boolean =
        dvrCanRecordState.value && (dvrEventIdState.value != null || dvrExistingState.value != null)

    /**
     * Zisti prava a stav nahravky pre prave sledovanu relaciu.
     *
     * Vola sa pri starte prehravaca a po prepnuti kanala — nie pri otvoreni
     * ovladania. Poradie ovladacov sa pocita z `playerControlOrder()`, takze
     * keby polozka pribudla az kym je lista otvorena, posunuli by sa indexy
     * pod rukou a dpad by aktivoval nieco ine.
     */
    fun refreshDvrState() {
        // M521: zahod stav PREDCHADZAJUCEHO kanala hned, este pred nacitanim.
        // Nacitanie zoznamu nahravok trva cez HTTP sekundy (u velkych serverov
        // je to vyse tisic zaznamov) a dovtedy tlacidlo ukazovalo stav kanala,
        // z ktoreho pouzivatel prave odisiel — raz „Zrusit" tam, kde sa nenahrava,
        // inokedy „Nahrat" tam, kde nahravka bezi.
        dvrExistingState.value = null
        dvrEventIdState.value = currentEventId()   // z lokalnej EPG cache, synchronne
        // M521-fix: prebiehajucu nahravku vezmi z toho isteho zdroja, z ktoreho sa
        // kreslia cervene bodky v zozname kanalov (fetchDvrInProgress). Je to mapa
        // uz nacitanych BEZIACICH nahravok — dostupna okamzite a spolahliva —
        // kym DvrController.scheduledFor() tahal cely zoznam naplanovanych
        // (u velkeho servera vyse tisic zaznamov) a kym dobehol, tlacidlo ukazovalo
        // nespravny stav.
        liveChannelsState.value.getOrNull(liveIndexState.value)?.let { ch ->
            recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] }
                ?.let { dvrExistingState.value = it }
        }
        lifecycleScope.launch {
            val srv = Tvh.store.active()
            var eid = currentEventId()
            // najprv rychly a spolahlivy zdroj, az potom pomaly zoznam naplanovanych
            var rec = runningRecordingHere() ?: currentEventRecording(srv)
            // M520: ak sa EPG pre tento kanal este nestihlo nacitat, prehravac
            // nepozna beziacu relaciu — a bez nej sa tlacidlo nahravania vobec
            // nezobrazi. Prave preto sa objavovalo raz ano, raz nie, podla toho,
            // ci uz EPG doslo. Dohladame si ju teda priamo zo servera.
            if (eid == null && srv != null) {
                val uuid = liveChannelsState.value.getOrNull(liveIndexState.value)?.uuid
                if (uuid != null) {
                    val evs = withContext(Dispatchers.IO) {
                        runCatching {
                            val api = Tvh.apiFor(srv)
                            try { Tvh.fetchEpgForChannel(srv, api, uuid) } finally { api.close() }
                        }.getOrDefault(emptyList())
                    }
                    if (evs.isNotEmpty()) {
                        // doplnime do cache, nech to dalsie otvorenie uz nemusi tahat
                        epgUpcomingState.value = epgUpcomingState.value + (uuid to evs)
                        val nowSec = System.currentTimeMillis() / 1000
                        val cur = evs.firstOrNull { it.start <= nowSec && nowSec < it.stop }
                        eid = cur?.eventId
                        if (rec == null && cur != null) {
                            rec = DvrController.scheduledFor(srv, uuid, cur.start, cur.stop)
                        }
                    }
                }
            }
            dvrEventIdState.value = eid
            dvrExistingState.value = rec
            dvrCanRecordState.value = srv != null && DvrController.access(srv).canRecord
        }
    }

    // M606: dialog vyberu DVR profilu (zoznam moznosti; prazdny = zatvoreny) + kurzor
    private val dvrAskState = androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    private val dvrAskSelState = androidx.compose.runtime.mutableStateOf(0)
    /** M607: ked dialog profilov patri kanalu z kontextovej ponuky (nie hrajucemu). */
    private var dvrAskTarget: Pair<LivePlaylist.LiveChannel, sk.tvhclient.shared.model.EpgEvent>? = null

    /** Nahrat prave beziacu relaciu, alebo zrusit uz naplanovanu nahravku. */
    fun toggleRecordCurrent() {
        val srv = Tvh.store.active() ?: return
        lifecycleScope.launch {
            val existing = dvrExistingState.value ?: currentEventRecording(srv)
            if (existing == null) {
                // M606: volitelny vyber profilu — az potom nahravanie
                val opts = DvrProfileAsk.options(this@PlayerActivity, srv)
                if (opts.isNotEmpty()) {
                    dvrAskTarget = null
                    dvrAskSelState.value = 0
                    dvrAskState.value = opts
                    return@launch
                }
            }
            recordCurrent(null)
        }
    }

    /** M606: vyber v dialogu profilov (OK / klik) alebo zrusenie (BACK). */
    private fun resolveDvrAsk(name: String?) {
        dvrAskState.value = emptyList()
        val target = dvrAskTarget
        dvrAskTarget = null
        if (name == null) return
        Tvh.store.active()?.let { DvrAskPref.setLastUsed(this, it.id, name) }
        lifecycleScope.launch {
            if (target != null) recordEventOf(target.first, target.second, name) else recordCurrent(name)
        }
    }

    private suspend fun recordCurrent(profile: String?) {
        val srv = Tvh.store.active() ?: return
        run {
            val existing = dvrExistingState.value ?: currentEventRecording(srv)
            val eid = dvrEventIdState.value ?: currentEventId()
            if (existing == null && eid == null) return
            val hint = currentLiveEvent()
            val r = if (existing != null) DvrController.cancel(srv, existing)
            else DvrController.recordEvent(
                srv, eid!!,
                hint?.first ?: "",
                hint?.second?.start ?: 0L,
                hint?.second?.stop ?: 0L,
                hint?.second?.title ?: "",
                profile
            )
            // M484: pri duplikate dohladaj, kde uz nahravka je
            val dup = if (r.success || existing != null) null
            else DvrController.duplicateOf(srv, hint?.second?.title ?: "")
            dvrExistingState.value = currentEventRecording(srv)
            if (r.success) refreshRecordingOnly()   // M608: cervena bodka / kazeta hned
            android.widget.Toast.makeText(
                this@PlayerActivity,
                when {
                    r.success && existing != null -> getString(R.string.dvr_rec_cancelled)
                    r.success -> getString(R.string.dvr_rec_scheduled)
                    dup != null && dup.channelName.isNotBlank() -> getString(
                        R.string.dvr_rec_duplicate, dup.channelName,
                        sk.tvhclient.shared.formatDayLabel(dup.start) + " " +
                            sk.tvhclient.shared.formatTimeHm(dup.start)
                    )
                    else -> r.error ?: getString(
                        if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed
                    )
                },
                android.widget.Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun modernMoreIds(): List<String> = modernOv.moreIds()
    private fun openModernOverlay() { modernOv.open() }
    private fun closeModernOverlay() { modernOv.close() }

    private val mediaPlayer: MediaPlayer get() = engine.player   // M656

    // M639: stav ziveho prehravania v LiveSession; tu delegaty pod povodnymi nazvami
    private val live = LiveSession()
    private var liveUuids: List<String>
        get() = live.uuids
        set(v) { live.uuids = v }
    private var liveNames: List<String>
        get() = live.names
        set(v) { live.names = v }
    private var liveIndex: Int
        get() = live.index
        set(v) { live.index = v }
    private var playKind: String
        get() = live.playKind
        set(v) { live.playKind = v }
    // pre opatovne pripojenie videa po navrate z pozadia
    private var videoLayout: VLCVideoLayout? = null
    private var subOverlay: SubtitleOverlayView? = null
    // ===== M553 / M627: teletext — stav a ovládanie v TeletextController, vykreslenie v TeletextOverlay =====
    private val ttx: TeletextController by lazy {
        TeletextController(this,
            liveServer = { liveServer },
            liveUuid = { liveUuidState.value },
            seekable = { seekablePlayback },
            onOpened = { closeModernOverlay() })
    }
    /** M552: teletext aktuálneho kanála (HTSP: dáta z feedera, HTTP: vlastná odbočka). */
    val teletext: TeletextSession get() = ttx.session
    val teletextOpenState: androidx.compose.runtime.MutableState<Boolean> get() = ttx.openState
    fun teletextVisible(): Boolean = ttx.visible()
    fun openTeletext() { ttx.open() }
    fun closeTeletext() { ttx.close() }

    private var wasPlaying: Boolean = false
    // Picture-in-Picture (obraz v obraze)
    private val inPipState = androidx.compose.runtime.mutableStateOf(false)
    // false = audio-only (rozhlas) -> zobraz logo namiesto ciernej
    private val hasVideoState: androidx.compose.runtime.MutableState<Boolean> get() = vlcEvents.hasVideo   // M650
    // automaticke znovupripojenie zivého streamu po vypadku siete
    // M636: casovanie/stav reconnectu v ReconnectController.kt; co sa pri pokuse spravi, je nizsie
    private val reconnect: ReconnectController by lazy {
        ReconnectController(this,
            playerReady = { engine.ready },
            isPlaying = { mediaPlayer.isPlaying })
    }
    private val reconnectingState: androidx.compose.runtime.MutableState<Boolean> get() = reconnect.reconnecting
    // tocenie pri pretacani timeshiftu (kratky resync pipe -> libVLC)
    private val seekingState = androidx.compose.runtime.mutableStateOf(false)
    private var seekSpinnerJob: kotlinx.coroutines.Job? = null
    // YouTube-style dvojklik pretacanie: nazbierane sekundy (+/-), 0 = skryte
    // M648: vypocet ciela pretacania, M594 zotavenie a dvojklik v DvrSeek.kt
    private val dvrSeek: DvrSeek by lazy {
        DvrSeek(this, lifecycleScope,
            durMs = { if (dvrDurationMs > 0) dvrDurationMs else (if (engine.ready) mediaPlayer.length else 0L) },
            recording = { dvrRecording },
            playheadMs = { dvrPlayheadMsState.value },
            seekable = { engine.ready && seekablePlayback },
            performSeek = { target, from, dur -> seekDvrTo(target, from, dur) })
    }
    private val seekHintState: androidx.compose.runtime.MutableState<Int> get() = dvrSeek.hint
    private var liveServer: sk.tvhclient.shared.model.TvhServer?
        get() = live.server
        set(v) { live.server = v }
    private val liveTitleState get() = live.titleState
    private val liveUuidState get() = live.uuidState
    private val liveProgStartState get() = live.progStartState
    private val liveProgStopState get() = live.progStopState
    private val liveProgTitleState get() = live.progTitleState
    private val liveNextTitleState get() = live.nextTitleState
    private val liveNextStartState get() = live.nextStartState
    private val liveNextStopState get() = live.nextStopState
    private val zapPokeState get() = live.zapPokeState
    private val liveIndexState get() = live.indexState
    private val liveChannelsState get() = live.channelsState
    // M634: EPG now/next + cache v PlayerEpgStore.kt; tu len delegaty pod povodnymi nazvami
    private val epg: PlayerEpgStore by lazy {
        PlayerEpgStore(this,
            liveServer = { liveServer },
            liveChannels = liveChannelsState,
            recInProgress = recInProgressByChan,
            onDvrStateChanged = { refreshDvrState() })
    }
    private val epgUpcomingState: androidx.compose.runtime.MutableState<Map<String, List<sk.tvhclient.shared.model.EpgEvent>>> get() = epg.upcoming
    private val epgLoadingState: androidx.compose.runtime.MutableState<Boolean> get() = epg.loading
    private suspend fun refreshOverlayEpg() { epg.refreshOverlayEpg() }
    private fun refreshOverlayEpgInitial() { epg.refreshOverlayEpgInitial() }
    private fun refreshRecordingOnly() { epg.refreshRecordingOnly() }
    private fun cacheChannelEpg(uuid: String, list: List<sk.tvhclient.shared.model.EpgEvent>) { epg.cacheChannelEpg(uuid, list) }
    private fun hydrateEpgFromDisk(srv: sk.tvhclient.shared.model.TvhServer) { epg.hydrateEpgFromDisk(srv) }
    private fun applyCachedEpgToChannels() { epg.applyCachedEpgToChannels() }
    private fun flushEpgPersist() { epg.flushEpgPersist() }
    private fun prefetchEpgIfStale() { epg.prefetchEpgIfStale() }

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
    private var currentStreamUrl: String?
        get() = stream.currentStreamUrl
        set(v) { stream.currentStreamUrl = v }
    // Zadavanie kanala cislami z dialkoveho ovladaca (M635: ChannelNumberEntry.kt)
    private val numEntry: ChannelNumberEntry by lazy {
        ChannelNumberEntry(lifecycleScope) { typed ->
            val idx = LivePlaylist.channels.indexOfFirst { it.number == typed }
            if (idx in liveUuids.indices) { switchToIndex(idx); pokeControls() }
        }
    }

    // M654: stavba libVLC Media (URL / feeder, dekodér, deinterlacing, demux) v MediaFactory.kt
    private val mediaFactory: MediaFactory by lazy { MediaFactory(this) { libVlc } }
    private fun userAgent(): String = mediaFactory.userAgent()
    private fun stripCreds(url: String): String = MediaFactory.stripCreds(url)
    private fun buildMedia(url: String): Media = mediaFactory.forUrl(url)
    private fun deinterlaceSpec(): Pair<String, String?> = mediaFactory.deinterlaceSpec()

    // M655: otvaranie streamu (HTTP / feeder / DVR / HTSP, auth sonda) v StreamOpener.kt
    private val opener: StreamOpener by lazy {
        StreamOpener(this, lifecycleScope, stream, live, mediaFactory, tracks,
            player = { mediaPlayer },
            hooks = object : StreamOpener.Hooks {
                override fun ensureHealthyPlayer() { this@PlayerActivity.ensureHealthyPlayer() }
                override fun startPlayback() { this@PlayerActivity.startPlayback() }
                override fun resetTimeshift() { this@PlayerActivity.resetTimeshift() }
                override fun resetTeletext() { closeTeletext(); teletext.reset() }   // M552/M553
                override fun teletextSetHtspAvailable(available: Boolean) { teletext.setHtspAvailable(available) }
                override fun teletextFeedHtsp(es: ByteArray) { teletext.feedHtsp(es) }
                override fun subtitlePage(page: sk.tvhclient.shared.htsp.DvbSubtitleDecoder.DecodedPage, ms: Long) { subOverlay?.onPage(page, ms) }
                override fun subtitleReset() { subOverlay?.reset() }
                override fun fallbackTitle(): String? = intent.getStringExtra(EXTRA_TITLE)
            })
    }
    private fun playLiveViaFeeder(server: sk.tvhclient.shared.model.TvhServer, url: String) { opener.playLiveViaFeeder(server, url) }
    private fun playLiveAuto(server: sk.tvhclient.shared.model.TvhServer, url: String) { opener.playLiveAuto(server, url) }
    private fun playHttp(url: String) { opener.playHttp(url) }
    private fun playDvrViaFeeder(server: sk.tvhclient.shared.model.TvhServer, url: String, startByte: Long = 0L) { opener.playDvrViaFeeder(server, url, startByte) }
    private fun playHtspLive(server: sk.tvhclient.shared.model.TvhServer, channelId: Long, timeshift: Boolean): Boolean = opener.playHtspLive(server, channelId, timeshift)
    private var liveNeedsFeeder: Boolean?
        get() = stream.liveNeedsFeeder
        set(v) { stream.liveNeedsFeeder = v }

    private fun pokeControls() {
        hideZapBar()  // M446
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
            if (playKind == "radio") putExtra("epg_radio", true)
            // zapamataj aktualny zivy kanal, nech BACK z EPG vrati do prehravaca nan
            if (!seekablePlayback) liveUuids.getOrNull(liveIndex)?.let { putExtra("epg_return_uuid", it) }
        }
        runCatching { startActivity(i) }
    }

    private fun openEpgInApp() {
        // M601-fix: radio nejde do PiP — autoPipIfPossible urobi handoff na pozadie
        // (a aktivitu ukonci), ale vratil true, takze sa TV program otvoril az po
        // 1,2 s poistke; medzitym bol vidiet uvod. Program otvor hned, handoff
        // (moderny rezim) pride po nom; v klasiku ostane prehravac pod programom
        // ako doteraz.
        if (playKind == "radio") {
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
        hideZapBar()  // M446
        val order = playerControlOrder(!seekablePlayback && liveUuids.size > 1, seekablePlayback, pipButtonVisible(), timeshiftEngagedState.value, profileSwitchAvailable(), dvrRecordVisible(), teletextVisible())
        controlNavState.value = order.indexOf("play").coerceAtLeast(0)
        pokeControls()
    }

    private fun togglePlayPause() {
        if (!engine.ready) return
        timeshift.flushNow()   // doruc nazbierany skok, nech je server konzistentny
        if (isPlayingState.value) {
            if (htspStream) htspFeeder?.pause()         // zastav HTSP delivery (aj bez timeshiftu)
            if (htspLive) {
                // prva pauza "zapne" timeshift: odtialto sa rata buffer aj cervene pocitadlo
                timeshift.onPaused()
                // zapnutim timeshiftu pribudnu ovladace pretacania (tsrew pred play) a posunu sa
                // indexy — re-ukotvi fokus na play/pause, nech "neskoci" na pretacanie
                val ord = playerControlOrder(!seekablePlayback && liveUuids.size > 1, seekablePlayback, pipButtonVisible(), true, profileSwitchAvailable(), dvrRecordVisible())
                controlNavState.value = ord.indexOf("play").coerceAtLeast(0)
            }
            isPlayingState.value = false
            mediaPlayer.pause()
        } else {
            if (htspStream) htspFeeder?.resume()
            if (htspLive) timeshift.onResumed()
            isPlayingState.value = true
            reconnect.resetDvrReopen()   // manualny play -> povol nove pokusy o nacitanie novsich dat
            mediaPlayer.play()
        }
    }

    /** Novy zivy zaciatok (cerstva subscription = na zivo) -> vynuluj timeshift. */
    private fun resetTimeshift() {
        timeshift.reset()
        hideSeekSpinner()
        // M492: akumulator dvojkliku a hint sa nulovat musia tiez — inak by po prepnuti
        // media ostal vychodzi bod z predchadzajucej nahravky. Playhead vynuluj z rovnakeho dovodu.
        dvrSeek.resetForNewMedia()
        dvrPlayheadMsState.value = 0L
    }

    private fun maxRewindMs(): Long = timeshift.maxRewindMs()

    /** Relativny skok v timeshifte (sekundy; zaporne = vzad). */
    private fun timeshiftSkip(seconds: Int) { if (htspLive) timeshift.skip(seconds) }

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

    /** Pretacanie pre DVR (live TS sa pretacat neda). TS subor nenese dlzku,
     *  preto pouzivame dlzku z DVR entry a poziciu ako zlomok (na TS spolahlive). */
    private fun recoverAfterSeek(): Boolean = dvrSeek.recoverAfterSeek()
    private fun seekDvrAbsolute(targetMs: Long) { dvrSeek.seekAbsolute(targetMs) }
    private fun seekRelative(deltaMs: Long) { dvrSeek.seekRelative(deltaMs) }

    /** Pretoc DVR nahravku na cielovy program-relativny cas PREBUDOVANIM streamu.
     *  Priame URL -> nova Media s :start-time (libVLC seekuje cez HTTP Range).
     *  Feeder/pipe -> restart HTTP feedu na odhadnutom byte-offsete (pipe sa neseekuje).
     *  V oboch pripadoch naseeduje playhead hodiny na cielovy cas. */
    private fun seekDvrTo(targetMs: Long, fromMs: Long, dur: Long) {
        val url = currentStreamUrl ?: return
        if (!engine.ready) return
        val offsetMs = if (dvrProgStartSec > 0 && dvrRealStartSec in 1 until dvrProgStartSec)
            (dvrProgStartSec - dvrRealStartSec) * 1000 else 0L
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
        runCatching {
            if (dvrViaFeeder) {
                val srv = liveServer ?: return
                val feeder = httpFeeder
                // Presny prepocet cas->byte z GLOBALNEHO priemeru: celkova velkost suboru
                // (Content-Range "/N") / celkovy cas suboru (offset + nahrate trvanie).
                // Lokalny odhad z bytesWritten/playhead je nespolahlivy (byte vs cas nesedi).
                val total = feeder?.totalBytes ?: 0L
                val fileDurMs = offsetMs + dur            // dur = aktualne nahrate trvanie relacie
                val targetByte: Long = if (total > 0 && fileDurMs > 0) {
                    (total.toDouble() / fileDurMs * fileMs).toLong().coerceIn(0L, total - 1)
                } else {
                    // fallback: lokalny odhad ak este nepoznam celkovu velkost
                    val bytes = feeder?.bytesWritten ?: 0L
                    val fromFileMs = (offsetMs + fromMs).coerceAtLeast(1L)
                    val bpms = if (bytes > 0) bytes.toDouble() / fromFileMs else 0.0
                    if (bpms > 0) (bpms * fileMs).toLong().coerceAtLeast(0L) else 0L
                }
                playDvrViaFeeder(srv, url, targetByte)
            } else {
                ensureHealthyPlayer()   // M539
                val m = buildMedia(url)
                m.addOption(":start-time=${fileMs / 1000}")
                mediaPlayer.media = m
                m.release()
                startPlayback()   // M539-fix2
            }
        }
        // M594: cas a ciel posledneho pretocenia — ked hned po nom pride koniec/chyba,
        // je to trafeny EOF (subor kratsi nez trvanie z EPG) a nie skutocny koniec
        dvrSeek.markSeek(targetMs)
        // playhead hned na cielovu poziciu + seed pre hodiny (po restarte je player.position
        // neplatna, hodiny ju nesmu citat - prevezmu seed a tikaju dalej z neho)
        dvrPlayheadMsState.value = targetMs
        dvrSeekSeedState.value = targetMs
    }

    /** Dvojklik na lavu/pravu stranu (YouTube-style): skok o 10 s.
     *  DVR -> seek v medii (akumulovane); aktivny timeshift -> subscriptionSkip hned. */
    private fun doubleTapSeek(forward: Boolean) {
        when {
            seekablePlayback -> dvrSeek.doubleTap(forward, applyImmediately = false)
            htspLive -> {
                if (maxRewindMs() <= 0L) return   // timeshift sa zapne az pauzou, dovtedy niet co pretacat
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
            seekablePlayback -> seekRelative(seconds.toLong() * 1000L)
            htspLive -> { if (maxRewindMs() > 0L) timeshiftSkip(seconds) }  // konvencia ako seekRelative: zaporne = vzad
            else -> {}
        }
    }

    /** M473: eventId prave beziacej relacie na aktualnom kanali (null = nevieme). */
    fun currentEventId(): Long? {
        val ch = liveChannelsState.value.getOrNull(liveIndexState.value) ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        return epgUpcomingState.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop }
            ?.eventId
    }

    /** M475: naplanovana/beziaca nahravka pre prave sledovanu relaciu (null = ziadna). */
    /**
     * M484: kanal a prave beziaca relacia — po naplanovani sa posle do
     * DvrController, aby sa nahravka hned premietla do zoznamu a tlacidlo sa
     * prepislo na „Zrusit" bez cakania na obnovu cache metadat.
     */
    fun currentLiveEvent(): Pair<String, sk.tvhclient.shared.model.EpgEvent>? {
        val ch = liveChannelsState.value.getOrNull(liveIndexState.value) ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        val ev = epgUpcomingState.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop } ?: return null
        return ch.uuid to ev
    }

    /** M521-fix: beziaca nahravka na prave sledovanom kanali z mapy cervených bodiek. */
    fun runningRecordingHere(): sk.tvhclient.shared.model.DvrEntry? {
        val ch = liveChannelsState.value.getOrNull(liveIndexState.value) ?: return null
        return recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] }
    }

    suspend fun currentEventRecording(
        server: sk.tvhclient.shared.model.TvhServer?
    ): sk.tvhclient.shared.model.DvrEntry? {
        val srv = server ?: return null
        val ch = liveChannelsState.value.getOrNull(liveIndexState.value) ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        val ev = epgUpcomingState.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop } ?: return null
        return DvrController.scheduledFor(srv, ch.uuid, ev.start, ev.stop)
    }


    /** TV/box (Android TV) — na detekciu kde sa ma archivny vyber zobrazovat. */
    private fun isTvDevice(): Boolean {
        val um = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    // ===== M657: jadro prepinania kanalov (selectChannelOrArchive, resolveArchiveChoice,
    // rememberPlayback, playRecordingFromStart, saveLastLive, switchToIndex) — ChannelSwitcher.kt;
    // tu delegaty pod povodnymi nazvami =====
    private val switcher: ChannelSwitcher by lazy {
        ChannelSwitcher(this, lifecycleScope, live, stream, tracks, object : ChannelSwitcher.Actions {
            override fun isTvDevice(): Boolean = this@PlayerActivity.isTvDevice()
            override fun pokeControls() { this@PlayerActivity.pokeControls() }
            override fun closeChannelList() { this@PlayerActivity.closeChannelList() }
            override fun refreshDvrState() { this@PlayerActivity.refreshDvrState() }
            override fun cancelReconnect() { this@PlayerActivity.cancelReconnect() }
            override fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, channelIndex: Int?) {
                this@PlayerActivity.requestPin(onOk = onOk, onCancel = onCancel, channelIndex = channelIndex)
            }
            override fun playHtspLive(server: sk.tvhclient.shared.model.TvhServer, channelId: Long, timeshift: Boolean): Boolean =
                this@PlayerActivity.playHtspLive(server, channelId, timeshift)
            override fun playLiveAuto(server: sk.tvhclient.shared.model.TvhServer, url: String) { this@PlayerActivity.playLiveAuto(server, url) }
            override fun playDvrViaFeeder(server: sk.tvhclient.shared.model.TvhServer, url: String) { this@PlayerActivity.playDvrViaFeeder(server, url) }
            override fun playHttp(url: String) { this@PlayerActivity.playHttp(url) }
            override fun setHasVideo(v: Boolean) { hasVideoState.value = v }
            override fun htspInitDone(): Boolean = this@PlayerActivity.htspInitDone
            override fun setHtspInitDone(v: Boolean) { this@PlayerActivity.htspInitDone = v }
            override fun archiveChoiceIdx(): Int = archiveChoiceIdxState.value
            override fun setArchiveChoiceIdx(v: Int) { archiveChoiceIdxState.value = v }
            override fun setArchiveChoiceSel(v: Int) { archiveChoiceSelState.value = v }
            override fun recInProgressByChan(): Map<String, sk.tvhclient.shared.model.DvrEntry> = this@PlayerActivity.recInProgressByChan.value
            override fun dvrUuid(): String? = this@PlayerActivity.dvrUuid
            override fun intentUuid(): String? = intent.getStringExtra(EXTRA_UUID)
            override fun startActivity(i: android.content.Intent) { this@PlayerActivity.startActivity(i) }
        })
    }
    private fun selectChannelOrArchive(idx: Int, poke: Boolean = true) { switcher.selectChannelOrArchive(idx, poke) }
    private fun resolveArchiveChoice(fromStart: Boolean) { switcher.resolveArchiveChoice(fromStart) }
    private fun rememberPlayback() { switcher.rememberPlayback() }
    private fun playRecordingFromStart(rec: sk.tvhclient.shared.model.DvrEntry, progStart: Long, progStop: Long) { switcher.playRecordingFromStart(rec, progStart, progStop) }
    private fun saveLastLive(serverId: String?, uuid: String?) { switcher.saveLastLive(serverId, uuid) }
    private fun switchToIndex(i: Int, poke: Boolean = true) { switcher.switchToIndex(i, poke) }

    /** Zatvorenie prehravaca: ak bol spusteny cez "od zaciatku" zo zivej TV, vrat sa na povodny kanal. */
    /** M342/M344: BACK z hrajuceho radia = handoff do RadioPlayerService.
     *  Vrati true, ak handoff prebehol (aktivita sa ukoncila) — radio hra dalej
     *  na pozadi s mini listou. Moderny: telefon aj TV. M624: aj klasik na
     *  telefone (mini lista je uz aj v klasiku); klasik na TV povodne — nema
     *  panel, radio by hralo bez ovladania. */
    private fun radioHandoffIfPossible(): Boolean {
        if (playKind != "radio") return false
        if (UiModePref.get(this) != UiModePref.MODERN && isTvDevice()) return false
        val uuid = liveUuids.getOrNull(liveIndexState.value) ?: return false
        val server = liveServer ?: sk.tvhclient.shared.Tvh.store.active() ?: return false
        if (!engine.ready || !mediaPlayer.isPlaying) return false
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
            commit = { target, poke -> switchToIndex(target, poke = poke) })
    }
    private fun switchLive(delta: Int) { zap.switchLive(delta) }

    // ===== M369 / M640: filter skupin v zozname kanalov — LiveGroups.kt =====
    private val groups: LiveGroups by lazy {
        LiveGroups(this, live,
            epgUpcoming = { epgUpcomingState.value },
            navIndex = navChannelIndexState,
            groupLabel = activeGroupLabelState)
    }
    private fun groupLabelFor(key: String): String = groups.labelFor(key)
    private fun groupKeys(): List<String> = groups.keys()
    private fun refreshFavOrder() { groups.refreshFavOrder() }
    private fun applyGroup(key: String) { groups.apply(key) }
    private fun cycleGroup(dir: Int) { groups.cycle(dir) }

    // ===== M370 / M635: hladanie kanala — ChannelSearch.kt =====
    /** Vyber kanala z vysledkov hladania: prepne (aj skupinu ak treba) a pusti. */
    private fun selectLiveByUuid(uuid: String) {
        okLongFired = true   // prehltne nasledne OK-up, inak by zoznam potvrdil iny kanal (index 0)
        search.close()
        closeChannelList()
        var i = liveUuids.indexOf(uuid)
        if (i < 0) { applyGroup(LivePlaylist.GROUP_ALL); i = liveUuids.indexOf(uuid) }
        if (i >= 0) selectChannelOrArchive(i, poke = false)
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
    private fun pipButtonVisible(): Boolean = pipSupported && !isTvDevice() && !AutoPipPref.get(this)

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
            channelCount = { liveUuids.size },
            openChannelList = { openChannelList() },
            switchToIndex = { idx -> switchToIndex(idx) },
            onRequested = { okLongFired = false })   // PIN vyzva prebera vstup; OK gesto je tym ukoncene
    }
    private val pinPromptState: androidx.compose.runtime.MutableState<Boolean> get() = pin.promptState
    private fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, markUnlock: Boolean = true, channelIndex: Int? = null) {
        pin.request(onOk, onCancel, markUnlock, channelIndex)
    }
    private fun closePin() { pin.close() }
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

    // DVR scrub (M597/M598) — ScrubController.kt (M646); tu delegaty pod povodnymi nazvami
    private val scrub: ScrubController by lazy {
        ScrubController(lifecycleScope,
            barMs = { if (dvrRecording) (dvrDurationMs - 45_000L).coerceAtLeast(1L) else dvrDurationMs },
            durMs = { if (dvrDurationMs > 0) dvrDurationMs else (if (engine.ready) mediaPlayer.length else 0L) },
            playheadMs = { dvrPlayheadMsState.value },
            seekable = { engine.ready && seekablePlayback },
            seekAbsolute = { ms -> seekDvrAbsolute(ms) },
            poke = { pokeControls() })
    }
    private val scrubFractionState: androidx.compose.runtime.MutableState<Float> get() = scrub.fraction
    private fun scheduleScrubAuto() { scrub.scheduleAuto() }
    private fun initScrub() { scrub.init() }

    // M651: klavesy pri beznom prehravani (blok 4 dispatchKeyEvent) v PlaybackKeys.kt
    private val playbackKeys: PlaybackKeys by lazy {
        PlaybackKeys(this, live, scrub, numEntry, controlNavState,
            seekable = { seekablePlayback },
            controlsShown = { controlsShown },
            modernTvActive = { modernTvActive() },
            controlOrder = { canZap ->
                playerControlOrder(canZap, seekablePlayback, pipButtonVisible(), timeshiftEngagedState.value,
                    profileSwitchAvailable(), dvrRecordVisible(), teletextVisible())
            },
            actions = object : PlaybackKeys.Actions {
                override fun switchLive(delta: Int) { this@PlayerActivity.switchLive(delta) }
                override fun showZapBar() { this@PlayerActivity.showZapBar() }
                override fun openModernOverlayAtCurrent() { modernOv.openAtCurrent() }
                override fun openModernOverlay() { this@PlayerActivity.openModernOverlay() }
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
                override fun initScrub() { this@PlayerActivity.initScrub() }
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
            !seekablePlayback && liveUuids.size > 1, seekablePlayback, pipButtonVisible(),
            timeshiftEngagedState.value, profileSwitchAvailable(), dvrRecordVisible(), teletextVisible()
        )
        val seekIdx = order.indexOf("seek")
        if (seekIdx < 0) { showControlsFocused(); return }
        val wasOnSeek = controlsShown && controlNavState.value == seekIdx
        controlNavState.value = seekIdx
        if (!wasOnSeek) initScrub()
        scrub.step(dir)
        scheduleScrubAuto()
        pokeControls()
    }

    // Pocitadlo na obnovu ikon zamku v in-player zozname po zmene zamku.
    private val lockTickState = androidx.compose.runtime.mutableStateOf(0)

    /** Zamkne/odomkne kanal v zozname prehravaca (ako dlhy klik na telefone). Chrani PINom. */
    private fun toggleLockAt(idx: Int) {
        val srv = liveServer ?: return
        val uuid = liveUuids.getOrNull(idx) ?: return
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
    private val pickHtspSpuCb: (Int) -> Unit = { id -> onPickHtspSpu(id) }
    private val prevChannelCb: () -> Unit = { switchLive(-1) }
    private val nextChannelCb: () -> Unit = { switchLive(+1) }

    // --- Kontextove menu kanala v prehravaci (long-press OK / dlhy klik) — ChannelContextMenu.kt (M641) ---
    private val ctxMenu: ChannelContextMenu by lazy {
        ChannelContextMenu(this, live, groups,
            epgUpcoming = { epgUpcomingState.value },
            recInProgress = { recInProgressByChan.value },
            canRecord = { dvrCanRecordState.value },
            navIndex = navChannelIndexState,
            okLongFired = { okLongFired },
            actions = object : ChannelContextMenu.Actions {
                override fun showInfo(idx: Int) = showChannelInfo(idx)
                override fun playFromStart(rec: sk.tvhclient.shared.model.DvrEntry, nowStart: Long, nowStop: Long) =
                    playRecordingFromStart(rec, nowStart, nowStop)
                override fun switchTo(idx: Int) = switchToIndex(idx)
                override fun toggleLock(idx: Int) = toggleLockAt(idx)
                override fun record(ch: LivePlaylist.LiveChannel, ev: sk.tvhclient.shared.model.EpgEvent) = recordFromCtxMenu(ch, ev)
                override fun enterReorder() = enterReorderMode()
            })
    }
    private val ctxMenuIdxState: androidx.compose.runtime.MutableState<Int> get() = ctxMenu.idxState
    private val ctxMenuSelState: androidx.compose.runtime.MutableState<Int> get() = ctxMenu.selState
    private fun ctxMenuKeys(idx: Int): List<String> = ctxMenu.keys(idx)
    private fun openChannelContextMenu(idx: Int) { ctxMenu.open(idx) }
    private fun closeChannelContextMenu() { ctxMenu.close() }
    private fun toggleFavoriteAt(idx: Int, announce: Boolean) { ctxMenu.toggleFavoriteAt(idx, announce) }
    private fun activateCtxMenu(key: String) { ctxMenu.activate(key) }

    /** M607: nahravanie z kontextovej ponuky — s volitelnym vyberom profilu (M606). */
    private fun recordFromCtxMenu(ch: LivePlaylist.LiveChannel, ev: sk.tvhclient.shared.model.EpgEvent) {
        val srv = Tvh.store.active() ?: return
        lifecycleScope.launch {
            val opts = DvrProfileAsk.options(this@PlayerActivity, srv)
            if (opts.isNotEmpty()) {
                dvrAskTarget = ch to ev
                dvrAskSelState.value = 0
                dvrAskState.value = opts
            } else recordEventOf(ch, ev, null)
        }
    }

    private suspend fun recordEventOf(ch: LivePlaylist.LiveChannel, ev: sk.tvhclient.shared.model.EpgEvent, profile: String?) {
        val srv = Tvh.store.active() ?: return
        val eid = ev.eventId ?: return
        val r = DvrController.recordEvent(srv, eid, ch.uuid, ev.start, ev.stop, ev.title, profile)
        val dup = if (r.success) null else DvrController.duplicateOf(srv, ev.title)
        if (r.success) {
            refreshRecordingOnly()   // cervena bodka pri kanali
            if (ch.uuid == liveUuidState.value) dvrExistingState.value = currentEventRecording(srv)
        }
        android.widget.Toast.makeText(
            this@PlayerActivity,
            when {
                r.success -> getString(R.string.dvr_rec_scheduled)
                dup != null && dup.channelName.isNotBlank() -> getString(
                    R.string.dvr_rec_duplicate, dup.channelName,
                    sk.tvhclient.shared.formatDayLabel(dup.start) + " " + sk.tvhclient.shared.formatTimeHm(dup.start)
                )
                else -> r.error ?: getString(if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed)
            },
            android.widget.Toast.LENGTH_LONG
        ).show()
    }

    // ===== M541 / M638: rezim usporiadania oblubenych (D-pad) — FavReorder.kt =====
    private val reorder: FavReorder by lazy {
        FavReorder(this,
            serverId = { (liveServer ?: Tvh.store.active())?.id },
            liveUuids = { liveUuids },
            liveChannels = liveChannelsState,
            navIndex = navChannelIndexState,
            groupLabel = activeGroupLabelState,
            groupLabelFor = { key -> groupLabelFor(key) },
            reapplyFavGroup = { refreshFavOrder(); applyGroup(LivePlaylist.GROUP_FAV) },
            okLongFired = { okLongFired })
    }
    private fun enterReorderMode() { reorder.enter() }
    private fun exitReorderMode() { reorder.exit() }

    // --- Info o relacii (detail) v prehravaci — ChannelInfo.kt (M643) ---
    private val info: ChannelInfo by lazy {
        ChannelInfo(lifecycleScope, live,
            epgUpcoming = { epgUpcomingState.value },
            cacheChannelEpg = { uuid, list -> cacheChannelEpg(uuid, list) },
            dvrRecordVisible = { dvrRecordVisible() },
            toggleRecord = { toggleRecordCurrent() },
            onShown = { hideZapBar() })
    }
    private val infoVisibleState: androidx.compose.runtime.MutableState<Boolean> get() = info.visible
    private val infoRecSelState: androidx.compose.runtime.MutableState<Boolean> get() = info.recSel
    private val infoChannelState: androidx.compose.runtime.MutableState<String> get() = info.channel
    private val infoTitleState: androidx.compose.runtime.MutableState<String> get() = info.title
    private val infoTimeState: androidx.compose.runtime.MutableState<String> get() = info.time
    private val infoDescState: androidx.compose.runtime.MutableState<String> get() = info.desc
    // M280: potvrdenie ukoncenia ziveho prehravania (BACK) — ako exit dialog v menu
    private val exitConfirmState = androidx.compose.runtime.mutableStateOf(false)
    private val exitConfirmSelState = androidx.compose.runtime.mutableStateOf(0) // 0=Zrusit, 1=Ukoncit

    private fun fmtRange(a: Long, b: Long): String = ChannelInfo.fmtRange(a, b)
    private fun showChannelInfo(idx: Int) { info.show(idx) }
    private fun closeChannelInfo() { info.close() }

    // ---- M430 / M628: kompaktny zap pas — stav aj vykreslenie v ZapBar.kt ----
    private val zapBar: ZapBar by lazy {
        ZapBar(lifecycleScope,
            fmtRange = { a, b -> fmtRange(a, b) },
            suppressed = { controlsShown || modernOvState.value || infoVisibleState.value })
    }
    private fun hideZapBar() { zapBar.hide() }
    private fun showZapBar() { zapBar.show(liveChannelsState.value.getOrNull(liveIndexState.value)) }

    private val listKeys: ChannelListKeys by lazy {
        ChannelListKeys(this, live, groups, search, reorder, navChannelIndexState, groupPickerState,
            object : ChannelListKeys.Actions {
                override var okLongFired: Boolean
                    get() = this@PlayerActivity.okLongFired
                    set(v) { this@PlayerActivity.okLongFired = v }
                override fun openContextMenu(idx: Int) { openChannelContextMenu(idx) }
                override fun closeList() { closeChannelList() }
                override fun switchDelayed(idx: Int) {
                    // M600-fix: pockaj, kym sa video vrati z nahladoveho obdlznika na celu obrazovku
                    lifecycleScope.launch {
                        kotlinx.coroutines.delay(320)
                        switchToIndex(idx, poke = false)
                    }
                }
                override fun selectOrArchive(idx: Int) { selectChannelOrArchive(idx, poke = false) }
                override fun reselectCurrent() {
                    closeChannelList()
                    if (modernTvActive()) openModernOverlay() else showControlsFocused()
                }
            })
    }
    /** M605: dlazdica „TV kanaly" / „Radia" otvorila prehravac so zoznamom hned pri starte. */
    private var listFirst = false

    private fun openChannelList() {
        // M371: otvor aj s 1 kanalom, ak su skupiny na prepnutie (napr. Oblubene s 1 kanalom),
        // inak by sa filtrovany zoznam uz nedal otvorit ani prepnut spat.
        refreshFavOrder()   // M541: oblubene sa mohli zmenit v zozname Kanaly
        if (liveUuids.size < 2 && groupKeys().size <= 1) return
        groupPickerState.value = false
        search.deactivateSilently()
        activeGroupLabelState.value =
            if (groupKeys().size > 1) groupLabelFor(LivePlaylist.activeGroupKey) else ""
        navChannelIndexState.value = liveIndex.coerceAtLeast(0)
        listKeys.openedAt = android.os.SystemClock.uptimeMillis()
        openChannelListState.value = openChannelListState.value + 1
    }
    private fun closeChannelList() {
        exitReorderMode()   // M541
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
    /** HTSP vyber titulku: zapamataj zelany jazyk a skus ho hned nastavit v libVLC; ak stopa
     *  este nie je (jazyk nehovoril), aplikuje sa pri ESAdded. id < 0 = Vypnute. */
    private fun onPickHtspSpu(esIndex: Int) {
        tracks.selectedSubEs.value = esIndex
        // DVB titulky dekódujeme a renderujeme sami; do libVLC nejdu. Vyber = ktory ES dekódovat.
        subOverlay?.reset()
        htspFeeder?.selectSubtitle(esIndex)
    }

    private fun applyDesiredSpu() { tracks.applyDesiredSpu() }
    private fun applyPendingSpuRestore() { tracks.applyPendingSpuRestore(htspStream, seekablePlayback) }

    /** M383: prepinac profilu ma zmysel len pri HTTP live (nie HTSP, nie DVR,
     *  nie externa URL — tam profil neexistuje alebo sa neda menit). */
    private fun profileSwitchAvailable(): Boolean = tracks.profileSwitch.value

    private fun openProfileMenu() {
        val srv = liveServer ?: return
        // okamzity fallback, server moze zoznam vzapati nahradit vlastnym
        if (tracks.profileItems.value.isEmpty()) {
            tracks.profileItems.value =
                ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() }
        }
        lifecycleScope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sk.tvhclient.shared.Tvh.streamProfiles(srv)
            }
            if (list.isNotEmpty()) tracks.profileItems.value = list
        }
        tracks.openProfileMenu()
    }

    /** M383: novy profil = nova predvolba SERVERA (plati pre vsetky dalsie kanaly,
     *  drzi po restarte; ta ista hodnota je v Nastavenia -> server -> Upravit).
     *  Stream sa restartuje s novou URL. */
    private fun applyProfileChange(profile: String) {
        val srv = liveServer ?: return
        if (profile.isBlank() || profile == srv.profile) return
        // M392: zosulad zelanie so skutocnym stavom pred restartom (pokryva aj
        // pripad, ked pouzivatel medzitym prepol titulky dotykovym menu)
        if (!htspStream) tracks.captureHttpSpuFromPlayer()
        val updated = srv.copy(profile = profile)
        sk.tvhclient.shared.Tvh.store.upsert(updated)
        liveServer = updated
        tracks.currentProfile.value = profile
        val i = liveIndex
        if (i >= 0) { liveIndex = -1; switchToIndex(i, poke = false) }
    }

    private fun openAudioMenu() { tracks.openAudioMenu() }
    private fun openSpuMenu() { tracks.openSpuMenu() }
    private fun closeTrackMenu() { tracks.closeMenu() }
    private fun selectTrackAtNav() {
        if (!engine.ready) return
        val ids = tracks.menuIds(htspStream)
        val id = ids.getOrNull(tracks.navIndex.value) ?: return
        when {
            tracks.menuKind == "profile" -> {
                tracks.profileItems.value.getOrNull(id)?.let { applyProfileChange(it) }
            }
            tracks.menuKind == "audio" -> {
                mediaPlayer.audioTrack = id
                // M378: zapamataj rucny vyber pre kanal aj z TV menu (D-pad);
                // predtym sa ukladal len z dotykoveho menu, takze na TV sa
                // volba po prepnuti kanala "zabudla"
                val sid = sk.tvhclient.shared.Tvh.store.active()?.id
                val uuid = liveUuidState.value
                if (sid != null && uuid != null) {
                    val name = mediaPlayer.audioTrackItems().firstOrNull { it.id == id }?.name
                    if (!name.isNullOrBlank()) ChannelPrefs.setLastAudio(this, sid, uuid, name)
                }
            }
            htspStream -> onPickHtspSpu(id)
            else -> {
                mediaPlayer.spuTrack = id
                tracks.httpSpuUserPick(id)   // M392-fix: prepise trvale zelanie
            }
        }
        closeTrackMenu()
    }

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
            "audio" -> openAudioMenu()
            "subs" -> openSpuMenu()
            "profile" -> openProfileMenu()
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
            dvrAskState, dvrAskSelState, archiveChoiceIdxState, archiveChoiceSelState,
            exitConfirmState, exitConfirmSelState, isPlayingState, optionsNavState,
            actions = object : PlayerKeyRouter.Actions {
                override val remoteDebug: Boolean get() = this@PlayerActivity.remoteDebug
                override var okLongFired: Boolean
                    get() = this@PlayerActivity.okLongFired
                    set(v) { this@PlayerActivity.okLongFired = v }
                override val seekablePlayback: Boolean get() = this@PlayerActivity.seekablePlayback
                override val liveIndex: Int get() = this@PlayerActivity.liveIndex
                override val channelListOpen: Boolean get() = this@PlayerActivity.channelListOpen
                override val returnLiveUuid: String? get() = this@PlayerActivity.returnLiveUuid
                override val optionsOpen: Boolean get() = this@PlayerActivity.optionsOpen
                override val trackMenuOpen: Boolean get() = this@PlayerActivity.trackMenuOpen
                override val htspStream: Boolean get() = this@PlayerActivity.htspStream
                override fun isCommonKey(kc: Int): Boolean = this@PlayerActivity.isCommonKey(kc)
                override fun resolveDvrAsk(name: String?) { this@PlayerActivity.resolveDvrAsk(name) }
                override fun resolveArchiveChoice(fromStart: Boolean) { this@PlayerActivity.resolveArchiveChoice(fromStart) }
                override fun finish() { this@PlayerActivity.finish() }
                override fun openEpgInApp() { this@PlayerActivity.openEpgInApp() }
                override fun openSpuMenu() { this@PlayerActivity.openSpuMenu() }
                override fun openAudioMenu() { this@PlayerActivity.openAudioMenu() }
                override fun modernTvActive(): Boolean = this@PlayerActivity.modernTvActive()
                override fun openModernOverlay() { this@PlayerActivity.openModernOverlay() }
                override fun showControlsFocused() { this@PlayerActivity.showControlsFocused() }
                override fun toggleInfo() { this@PlayerActivity.toggleInfo() }
                override fun togglePlayPause() { this@PlayerActivity.togglePlayPause() }
                override fun pokeControls() { this@PlayerActivity.pokeControls() }
                override fun scrubSeek(seconds: Int) { this@PlayerActivity.scrubSeek(seconds) }
                override fun toggleFavoriteAt(idx: Int, announce: Boolean) { this@PlayerActivity.toggleFavoriteAt(idx, announce) }
                override fun closePlayer() { this@PlayerActivity.closePlayer() }
                override fun openChannelList() { this@PlayerActivity.openChannelList() }
                override fun seekRelative(deltaMs: Long) { this@PlayerActivity.seekRelative(deltaMs) }
                override fun selectOption(idx: Int) { this@PlayerActivity.selectOption(idx) }
                override fun closeOptions() { this@PlayerActivity.closeOptions() }
                override fun selectTrackAtNav() { this@PlayerActivity.selectTrackAtNav() }
                override fun closeTrackMenu() { this@PlayerActivity.closeTrackMenu() }
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

    // DVR progress (sledovanie pozicie pre archiv)
    private var dvrUuid: String? = null
    private var dvrServerId: String? = null
    private var dvrDurationMs: Long = 0
    // Prebiehajuca relacia: dlzku dopocitavame relativne k zaciatku RELACIE (nie suboru),
    // obmedzenu dlzkou relacie. Seekbar tak ukazuje uplynutu cast relacie, nie cely archiv.
    private var dvrRecording = false
    private var dvrProgStartSec: Long = 0
    private var dvrProgStopSec: Long = 0
    private var dvrRealStartSec: Long = 0
    private val dvrDurationState = mutableStateOf(0L)
    private var reachedEnd = false
    // Playhead v case relacie (ms) zrkadleny z wall-clock prehravacich hodin v PlayerUi -
    // spolahlivy zdroj pre znovu-otvorenie streamu (player.time je pre rastuci TS nestabilny).
    private val dvrPlayheadMsState = mutableStateOf(0L)
    // Po pretoceni DVR: cielovy (program-relativny) cas, ktory maju playhead hodiny prevziat.
    // -1 = ziadny cakajuci seek. Pri feeder/pipe je player.position po restarte neplatna,
    // takze hodiny sa nemozu resync-nut z pozicie - seed im da spravny vychodzi bod.
    private val dvrSeekSeedState = mutableStateOf(-1L)

    private fun saveDvrProgress() {
        val uuid = dvrUuid ?: return
        val sid = dvrServerId ?: return
        if (!engine.ready || playerTornDown) return   // M535: player uz moze byt uvolneny
        val dur = if (dvrDurationMs > 0) dvrDurationMs else mediaPlayer.length
        if (dur <= 0) return
        if (reachedEnd && !dvrRecording) {
            WatchProgress.markCompleted(this, sid, uuid, dur)
            return
        }
        // Program-relativny cas z playhead hodin je jediny spolahlivy zdroj:
        // po pretoceni sa stream restartuje (:start-time) a mediaPlayer.position
        // je relativna k NOVEMU streamu — ukladali sa nezmyselne male pozicie,
        // rozpozeranost sa stracala a 95% prah "dopozerane" sa nikdy nedosiahol.
        val playheadMs = dvrPlayheadMsState.value
        val posMs = if (playheadMs > 0) {
            playheadMs.coerceAtMost(dur)
        } else {
            val pos = mediaPlayer.position
            // neprepisuj dobru poziciu nulou (napr. ked sa media este nenacitala)
            if (pos > 0.001f && pos <= 1f) (pos * dur).toLong() else return
        }
        if (posMs > 0) WatchProgress.save(this, sid, uuid, posMs, dur)
    }

    /** M623: radio s volbou "Radio hra na pozadi" (telefon aj TV) — odchod z prehravaca
     *  na pozadie (zhasnutie, zamok, domovska obrazovka, ina appka) radio nepozastavi.
     *  V samotnom prehravaci obrazovka svieti dalej (KEEP_SCREEN_ON ako pri TV). */
    private fun radioBackground(): Boolean =
        playKind == "radio" && RadioBackgroundPref.get(this)

    private fun keepScreenOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** M658: pripojenie video layoutu z PlayerUi (povodne onAttach lambda v setContent). */
    private fun attachVideo(layout: VLCVideoLayout) {
        videoLayout = layout
        mediaPlayer.attachViews(layout, null, false, false)
        // M539-fix2: novy prehravac cakal na svoj (novy) surface — spusti ho teraz
        if (engine.onSurfaceAttached()) {
            layout.post { runCatching { if (!playerTornDown) mediaPlayer.play() } }
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
            clockSource = { if (engine.ready) mediaPlayer.time else 0L },
            aspectSource = {
                val vt = if (engine.ready) runCatching { mediaPlayer.currentVideoTrack }.getOrNull() else null
                if (vt != null && vt.width > 0 && vt.height > 0) {
                    val sn = if (vt.sarNum > 0) vt.sarNum else 1
                    val sd = if (vt.sarDen > 0) vt.sarDen else 1
                    (vt.width.toFloat() * sn) / (vt.height.toFloat() * sd)
                } else 16f / 9f
            }
        )
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
        acquireStreamLocks()  // M452

        // Immersive fullscreen — skry status aj navigacnu listu, nech
        // neprekryvaju ovladanie. Listy sa daju vytiahnut potiahnutim.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val channelUuid = args.channelUuid
        val channelTitle = args.channelTitle
        val directUrl = args.directUrl
        playKind = args.playKind
        val durationMs = args.durationMs
        val progStart = args.progStart
        val progStop = args.progStop
        val progTitle = args.progTitle
        dvrUuid = args.dvrUuid
        val progStartFrac = args.progStartFrac
        val progStopFrac = args.progStopFrac
        dvrDurationMs = durationMs
        dvrDurationState.value = durationMs
        dvrRecording = args.dvrRecording
        dvrProgStartSec = args.dvrProgStartSec
        dvrProgStopSec = args.dvrProgStopSec
        dvrRealStartSec = args.dvrRealStartSec
        // Prebiehajuca relacia: dlzka rastie k zivej hrane; bar musi byt VZDY viditelny.
        // Ak mame hranice relacie, dopocitavame relativne k jej zaciatku (cap dlzkou relacie).
        // Ak hranice chybaju (nahravka nema vyplnene start/stop), drzime krok s dlzkou z VLC.
        // M658: vypocet a sekundovy cyklus su v DvrDurationTicker (M528 vnutri).
        if (dvrRecording) {
            DvrDurationTicker(
                scope = lifecycleScope,
                playerLength = { if (engine.ready) mediaPlayer.length else 0L },
                isRecording = { dvrRecording },
                current = { dvrDurationMs },
                set = { dvrDurationMs = it; dvrDurationState.value = it }
            ).start(durationMs, dvrProgStartSec, dvrProgStopSec)
        }
        val server = Tvh.store.active()
        if (server == null || (channelUuid == null && directUrl == null)) {
            finish()
            return
        }
        dvrServerId = server.id

        // Ulozena pozicia: ponuknut obnovenie ak nie je dopozerane a nie je
        // tesne na zaciatku/konci
        val saved = dvrUuid?.let { WatchProgress.get(this, server.id, it) }
        val resumeMs = if (saved != null && !saved.completed && saved.posMs > 30_000 &&
            (durationMs <= 0 || durationMs - saved.posMs > 60_000)
        ) saved.posMs else 0L

        createPlayer()   // M539: libVLC + MediaPlayer + listener (znovupouzitelne pri obnove po zaseknuti zvuku)
        startStallWatch()

        // DVR: priame dvrfile URL (s creds). Live: profil servera (M383 — per-kanal
        // override zruseny, profil sa da prepnut priamo v prehravaci).
        val streamUrl = directUrl ?: Tvh.liveUrl(
            server, channelUuid!!, channelTitle,
            server.profile.ifBlank { "pass" }
        )

        // Server je potrebny aj v DVR rezime (seekDvrTo / reopenDvrLive cez feeder).
        // Live-zapping nizsie zavisi od liveUuids (pri DVR prazdne), nie od liveServer.
        liveServer = server
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
        // Live zapping: priprav zoznam susednych kanalov
        if (directUrl == null && channelUuid != null && LivePlaylist.channels.isNotEmpty()) {
            liveUuids = LivePlaylist.channels.map { it.uuid }
            liveNames = LivePlaylist.channels.map { it.name }
            liveIndex = LivePlaylist.index.takeIf { it in liveUuids.indices }
                ?: liveUuids.indexOf(channelUuid)
            liveServer = server
            saveLastLive(server.id, channelUuid)
            hydrateEpgFromDisk(server)   // M275: nacitaj EPG z disku (prezije restart boxu)
        }
        rememberPlayback()   // M494: uz pri starte, nie az po prvom prepnuti
        liveChannelsState.value = LivePlaylist.channels
        // M281: hned dopln now/next z cache (disk/proces) na viditelny zoznam, nech sa nazvy
        // relacii pod kanalmi ukazu okamzite aj po restarte (predtym cakali na sietovy refresh).
        applyCachedEpgToChannels()
        // M605-fix: zoznam najprv — posledny kanal sa spusti normalne (hra za zoznamom
        // ako nahlad) a zoznam sa otvori hned; povodne nehralo nic, co pouzivatel nechcel
        listFirst = args.listFirst && liveUuids.size > 1
        liveIndexState.value = liveIndex
        liveTitleState.value = channelTitle
        liveUuidState.value = channelUuid
        liveProgStartState.value = progStart
        liveProgStopState.value = progStop
        liveProgTitleState.value = progTitle
        val canZap = directUrl == null && liveUuids.size > 1
        seekablePlayback = directUrl != null
        // predvolene zvyraznenie ovladacieho panela = play (nie krizik)
        controlNavState.value = playerControlOrder(canZap, seekablePlayback, pipButtonVisible(), timeshiftEngagedState.value, profileSwitchAvailable(), dvrRecordVisible(), teletextVisible()).indexOf("play").coerceAtLeast(0)
        currentStreamUrl = streamUrl

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
                title = liveTitleState.value,
                player = mediaPlayer,
                seekable = directUrl != null,  // DVR nahravka = da sa pretacat; live nie
                knownDurationMs = dvrDurationState.value,  // dlzka z DVR entry; pri prebiehajucej nahravke rastie k zivej hrane
                progStartFrac = progStartFrac,
                progStopFrac = progStopFrac,
                progStartSec = liveProgStartState.value,
                progStopSec = liveProgStopState.value,
                progTitleArg = liveProgTitleState.value,
                server = server,
                liveChannelUuid = if (directUrl == null) liveUuidState.value else null,
                preferredAudio = AudioPref.get(this),
                resumeMs = resumeMs,
                dvrUuid = dvrUuid,
                serverId = server.id,
                htspSpuItems = if (htspStreamState.value) {
                    @Suppress("UNUSED_EXPRESSION") tracks.listVersion.value  // refresh ked pribudne stopa
                    tracks.htspSpuItems()
                } else null,
                htspSpuCurrentId = tracks.selectedSubEs.value,
                onPickHtspSpu = if (htspStreamState.value) pickHtspSpuCb else null,   // M544: bez lambdy v kompozicii
                onPickHttpSpu = { id -> tracks.httpSpuUserPick(id) },
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
                        requestPin(onOk = doPlay, onCancel = { finish() }, channelIndex = liveIndex)
                    } else doPlay()
                },
                controlsPoke = controlsPokeState.value,
                infoPoke = infoPokeState.value,
                inPip = inPipState.value,
                pipSupported = pipSupported,
                pipButton = pipButtonVisible(),
                hasVideo = hasVideoState.value,
                reconnecting = reconnectingState.value,
                seeking = seekingState.value,
                centerLogoUrl = liveChannelsState.value.getOrNull(liveIndexState.value)?.piconUrl,
                onOpenEpg = { openEpgInApp() },
                onEnterPip = { enterPipAndMinimize() },
                onOpenSleep = { openSleepMenu() },
                playing = isPlayingState.value,
                channelNavIndex = navChannelIndexState.value,
                channelGroupLabel = activeGroupLabelState.value,
                channelGroupPicker = groupPickerState.value,
                searchActive = search.activeState.value,
                searchQuery = search.queryState.value,
                onSearchQueryChange = { search.setQuery(it) },
                searchFieldFocused = search.fieldFocusedState.value,
                searchHits = if (search.isActive) search.results() else emptyList(),
                searchNavIndex = search.navIndexState.value,
                searchFocusSignal = search.focusSignalState.value,
                openListSignal = openChannelListState.value,
                closeListSignal = closeChannelListState.value,
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
                onChannelListChange = {
                    channelListOpen = it
                    if (it) navChannelIndexState.value = liveIndex.coerceAtLeast(0)
                },
                openOptionsSignal = openOptionsState.value,
                closeOptionsSignal = closeOptionsState.value,
                optionsNavIndex = optionsNavState.value,
                sleepDeadline = sleep.deadlineState.value,
                onOptionsSelect = { idx -> selectOption(idx) },
                controlNavIndex = controlNavState.value,
                trackNavIndex = tracks.navIndex.value,
                trackListVersion = tracks.listVersion.value,
                closeMenuSignal = tracks.closeMenuSignal.value,
                openAudioSignal = tracks.openAudioSignal.value,
                openSpuSignal = tracks.openSpuSignal.value,
                openProfileSignal = tracks.openProfileSignal.value,
                profileItems = tracks.profileItems.value,
                currentProfile = tracks.currentProfile.value,
                profileSwitch = profileSwitchAvailable(),
                onPickProfile = { p -> applyProfileChange(p) },
                modernMoreIdList = modernMoreIds(),
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
                timeshiftEngaged = timeshiftEngagedState.value,
                modernOvVisible = modernOv.visible.value,
                modernOvRow = modernOv.row.value,
                modernOvCard = modernOv.card.value,
                modernOvStrip = modernOv.strip.value,
                modernOvPoke = modernOv.poke.value,
                modernOvExec = modernOv.exec.value,
                modernOvExecId = modernOv.execId.value,
                modernOvRecNames = recInProgressByChan.value.keys,
                modernStripIds = modernStripIds(),
                modernMoreVisible = modernOv.moreVisible.value,
                modernMoreIndex = modernOv.moreIdx.value,
                onMorePick = { i -> modernOv.morePick(i) },
                onMoreDismiss = { modernOv.moreDismiss() },
                tsMaxMs = maxRewindMs(),
                onModernOvDismiss = { closeModernOverlay() },
                onSkipBack = { timeshiftSkip(-30) },
                onSkipFwd = { timeshiftSkip(+30) },
                onDoubleTapSeek = { fwd -> doubleTapSeek(fwd) },
                onScrubSeek = { secs -> scrubSeek(secs) },
                onLoadChannelEpg = { uuid, cb ->
                    val cached = epgUpcomingState.value[uuid]
                    if (!cached.isNullOrEmpty()) {
                        cb(cached)
                    } else {
                        lifecycleScope.launch {
                            val list = runCatching {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    Tvh.fetchEpgForChannel(server, Tvh.apiFor(server), uuid)
                                }
                            }.getOrDefault(emptyList())
                            cacheChannelEpg(uuid, list)   // M274: memoizuj pre dalsie zobrazenia/reopen
                            cb(list)
                        }
                    }
                },
                seekHint = seekHintState.value,
                liveChannels = if (canZap) liveChannelsState.value else emptyList(),
                liveCurrentIndex = liveIndexState.value,
                onSelectChannel = { idx -> selectChannelOrArchive(idx) },
                onChannelLongPress = { idx -> openChannelContextMenu(idx) },
                lockTick = lockTickState.value,
                onRefreshEpg = {
                    lifecycleScope.launch { refreshOverlayEpg() }
                },
                onRefreshEpgInitial = { refreshOverlayEpgInitial() },
                onPrefetchEpg = { prefetchEpgIfStale() },
                epgLoading = epgLoadingState.value,
                numberEntry = numEntry.entryState.value,
                timeshiftOffsetMs = timeshiftOffsetState.value,
                pinPrompt = pin.promptState.value,
                pinLen = pin.entryState.value.length,
                pinError = pin.errorState.value,
                onPinDigit = { d -> pin.digit(d) },
                onPinBack = { pin.del() },
                onPinCancel = { pin.cancel() },
                onPinOpenList = { pin.openList() },
                pinGridRow = pin.gridRowState.value,
                pinGridCol = pin.gridColState.value,
                scrubFrac = scrubFractionState.value,
                progNextTitle = liveNextTitleState.value,
                progNextStart = liveNextStartState.value,
                progNextStop = liveNextStopState.value,
                zapPoke = zapPokeState.value,
                recordingLive = dvrRecording,
                recordingStopSec = dvrProgStopSec,
                recordingOffsetMs = if (dvrProgStartSec > 0 && dvrRealStartSec in 1 until dvrProgStartSec)
                    (dvrProgStartSec - dvrRealStartSec) * 1000 else 0L,
                onPlayheadMs = { dvrPlayheadMsState.value = it },
                seekSeedMs = dvrSeekSeedState.value,
                onSeekSeedHandled = { dvrSeekSeedState.value = -1L },
                onSeekToMs = { ms -> seekDvrAbsolute(ms) },
                resumeSel = resumeSelState.value,
                resumeAnswer = resumeAnswerState.value,
                onAskResumeChange = {
                    resumePromptState.value = it
                    if (it) { resumeSelState.value = 1; resumeAnswerState.value = 0 }
                },
                onResumeAnswerHandled = { resumeAnswerState.value = 0 },
                onRequestExit = {
                    // M344: hrajuce radio v modernom nekonci — ide do mini prehravaca,
                    // takze potvrdzovacia otazka nema zmysel; TV live ju ma dalej
                    if (!radioHandoffIfPossible()) {
                        exitConfirmSelState.value = 0; exitConfirmState.value = true
                    }
                },
                onClose = { closePlayer() },
                returnLiveOnBack = returnLiveUuid != null
            )
            }
            // Vyber pri archivovanom kanali (nazivo / od zaciatku) — overlay v style prehravaca
            // M553: teletext — nad prehrávačom, mimo PlayerUi
            if (teletextOpenState.value) {
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
            if (dvrAskState.value.isNotEmpty()) {
                // M606: vyber DVR profilu pred nahravanim
                DvrProfilePickDialog(
                    options = dvrAskState.value,
                    subtitle = dvrAskTarget?.let { it.first.name + " · " + it.second.title } ?: liveProgTitleState.value,
                    lastUsed = Tvh.store.active()?.let { DvrAskPref.lastUsed(this@PlayerActivity, it.id) },
                    selected = dvrAskSelState.value,
                    onPick = { resolveDvrAsk(it) },
                    onDismiss = { resolveDvrAsk(null) }
                )
            }
            if (archiveChoiceIdxState.value >= 0) {
                val aCh = liveChannelsState.value.getOrNull(archiveChoiceIdxState.value)
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
                                        .clickable { resolveArchiveChoice(false) }
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
                                        .clickable { resolveArchiveChoice(true) }
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
            if (ctxMenuIdxState.value >= 0) {
                val cIdx = ctxMenuIdxState.value
                val cCh = liveChannelsState.value.getOrNull(cIdx)
                val cKeys = ctxMenuKeys(cIdx)
                if (cCh != null && cKeys.isNotEmpty()) {
                    val cSel = ctxMenuSelState.value.coerceIn(0, cKeys.size - 1)
                    val cLocked = remember(lockTickState.value, cCh.uuid) {
                        ParentalLock.isChannelLocked(this@PlayerActivity, liveServer?.id, cCh.uuid)
                    }
                    val ctxModern = isModernUi()
                    val ctxAccent = playerAccent()
                    Box(
                        Modifier.fillMaxSize().background(Color(0xCC0B1220))
                            .clickable { closeChannelContextMenu() },   // ťuknutie mimo zatvori + blokuje pozadie
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
                                        val sid = (liveServer ?: Tvh.store.active())?.id
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
                                        .clickable { ctxMenuSelState.value = i; activateCtxMenu(key) }
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
            if (zapBar.visible.value && !infoVisibleState.value) ZapBarOverlay(zapBar)
            // Info o relacii (detail) — overlay v style prehravaca (M630: ChannelInfoOverlay)
            if (infoVisibleState.value) {
                ChannelInfoOverlay(
                    channel = infoChannelState.value,
                    title = infoTitleState.value,
                    time = infoTimeState.value,
                    desc = infoDescState.value,
                    recordLabel = if (dvrRecordVisible()) androidx.compose.ui.res.stringResource(
                        if (dvrExistingState.value != null) R.string.dvr_rec_cancel_button else R.string.dvr_rec_button
                    ) else null,
                    recordActive = dvrExistingState.value != null,
                    recordSelected = infoRecSelState.value,
                    onClose = { closeChannelInfo() },
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
    private val pipSupported: Boolean get() = pip.supported

    private fun enterPipIfPossible(): Boolean = pip.enterIfPossible()

    /** Spusti PiP (okno plava nad plochou / inou appkou). M349-fix4: ziadny
     *  moveTaskToBack — presun tasku na pozadie hned po vstupe do PiP na
     *  mnohych zariadeniach cerstve PiP okno zrusil (prehravac sa "len zavrel").
     *  Vstup do PiP sam zbali aktivitu do plavajuceho okna, nic dalsie netreba —
     *  auto-PiP cesta to robi rovnako a funguje. */
    private fun enterPipAndMinimize() {
        // M431-fix: BACK cez Compose BackHandler vola tuto funkciu priamo (mimo
        // closePlayer/autoPipIfPossible), takze radio brana musi byt aj tu —
        // inak radio konci v PiP okne. Handoff na pozadie / zatvorenie.
        if (playKind == "radio") {
            if (!radioHandoffIfPossible()) finish()
            return
        }
        enterPipIfPossible()
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
        if (playKind == "radio") return radioHandoffIfPossible()
        return pip.autoEnterIfPossible()
    }

    // aktualizuj ikonu play/pauza v PiP podla skutocneho stavu prehravania
    private fun refreshPipIfActive() { pip.refreshIfActive() }

    private fun closeFromPip() {
        LastPlayback.clear(this)
        finish()
    }

    /** Zrusi naplanovane znovupripojenie a skryje indikator. */
    private fun cancelReconnect() { reconnect.cancel() }

    /** In-progress nahravka dobehla na koniec zapisanych dat (EOF na rastucom HTTP subore).
     *  Po chvili (nech pribudne dalsi blok) znovu otvor stream a vrat sa na poziciu z
     *  prehravacich hodin (offset + prehrany cas relacie) - tak sa pokracuje do novsich dat.
     *  Backoff proti slucke ked nic nove nepribuda (ReconnectController); resetuje sa pri Playing evente. */
    private fun reopenDvrLive() {
        if (!seekablePlayback || !dvrRecording) return
        val url = currentStreamUrl ?: return
        if (!engine.ready) return
        val offsetMs = if (dvrProgStartSec > 0 && dvrRealStartSec in 1 until dvrProgStartSec)
            (dvrProgStartSec - dvrRealStartSec) * 1000 else 0L
        // pozicia v subore = offset + prehrany cas relacie, par sekund vzad ako rezerva
        val startSec = ((offsetMs + dvrPlayheadMsState.value) / 1000 - 3).coerceAtLeast(0)
        reconnect.reopenDvrLive {
            if (dvrViaFeeder) {
                // pokracuj od miesta kam sme dosli (rastuci subor) cez HTTP Range
                val srv = liveServer ?: return@reopenDvrLive
                val from = httpFeeder?.bytesWritten ?: 0L
                playDvrViaFeeder(srv, url, from)
            } else {
                ensureHealthyPlayer()   // M539
                val m = buildMedia(url)
                m.addOption(":start-time=$startSec")
                mediaPlayer.media = m
                m.release()
                startPlayback()   // M539-fix2
            }
        }
    }

    /** Naplanuje znovupripojenie zivého streamu po vypadku (narastajuce oneskorenie — ReconnectController). */
    private fun scheduleReconnect() {
        if (seekablePlayback) return  // DVR nahravka sa neobnovuje (in-progress riesi reopenDvrLive)
        reconnect.scheduleReconnect { attempt ->
            val srv = liveServer
            val cid = liveUuids.getOrNull(liveIndex)?.toLongOrNull()
            val url = currentStreamUrl
            if (htspStream && srv != null && cid != null) {
                // HTSP kanal -> znovu napoj cez HTSP (zachova HTSP/timeshift)
                playHtspLive(srv, cid, htspLive)
            } else if (liveNeedsFeeder == true && srv != null && url != null) {
                playLiveViaFeeder(srv, url)   // HTTP digest-only -> feeder
            } else if (url != null) {
                // M390: priame HTTP live na niektorych boxoch pada v libVLC (auth/transport),
                // hoci feeder (OkHttp -> pipe) funguje — po 2. neuspesnom pokuse prepni na feeder.
                if (attempt >= 2 && !seekablePlayback && srv != null && srv.username.isNotEmpty()) {
                    liveNeedsFeeder = true
                    playLiveViaFeeder(srv, url)
                } else {
                    ensureHealthyPlayer()   // M539
                    val m = buildMedia(url)       // bezne HTTP
                    mediaPlayer.media = m
                    m.release()
                    startPlayback()   // M539-fix2
                }
            }
        }
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
                runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
                runCatching { mediaPlayer.detachViews() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // navrat z pozadia: znova pripoj video na surface a obnov prehravanie
        if (engine.ready) {
            val curUuid = liveUuids.getOrNull(liveIndex)
            val locked = wasPlaying && !seekablePlayback && !pinPromptState.value &&
                ParentalLock.channelLockedProtected(this, liveServer?.id ?: Tvh.store.active()?.id, curUuid)
            // M540: navrat po standby (obrazovka zhasla, kym sme boli zastaveni) — na
            // Amlogicu je stary AudioTrack po prebudeni mrtvy (M539). Namiesto 5 s
            // cakania na hlidac rovno novy prehravac a znovunaladenie; PIN plati dalej.
            if (wasPlaying && !seekablePlayback && !playerTornDown && isTvDevice() &&
                WakeTracker.screenWentOffSince(stoppedAt)
            ) {
                CrashLogger.report(this, "PlayerActivity.wake", "resume after standby -> new player")
                recreatePlayer()
                if (locked) {
                    ParentalLock.clearGrace(this)
                    requestPin(
                        onOk = { replayCurrentLive() },
                        onCancel = { finish() },
                        channelIndex = liveIndex
                    )
                } else {
                    replayCurrentLive()
                }
                return
            }
            videoLayout?.let { runCatching { mediaPlayer.attachViews(it, null, false, false) } }
            // rodicovsky zamok: ak sa vraciame z pozadia na zamknuty ZIVY kanal,
            // vyziadaj PIN znova (kazdy navrat do prehravaca = PIN, ako pri starte).
            if (locked) {
                runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
                ParentalLock.clearGrace(this)   // M263: rovnako ako pri starte
                requestPin(
                    onOk = { runCatching { mediaPlayer.play() } },
                    onCancel = { finish() },
                    channelIndex = liveIndex
                )
            } else if (wasPlaying) {
                runCatching { mediaPlayer.play() }
            }
        }
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
        if (playKind == "radio") { radioHandoffIfPossible(); return }
        if (!isTvDevice() && AutoPipPref.get(this) && pipSupported && isPlayingState.value &&
            !(android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)) {
            enterPipIfPossible()
        }
    }

    /** M540: kedy sme naposledy isli do pozadia (elapsedRealtime). */
    private var stoppedAt = 0L

    override fun onStop() {
        stoppedAt = android.os.SystemClock.elapsedRealtime()
        saveDvrProgress()
        // PiP okno nechaj hrat LEN ak sme realne v PiP (vlastny priznak z callbacku, nie zivy
        // isInPictureInPictureMode - ten pri zatvarani PiP casto este hlasi true) a appka len ide
        // na pozadie. Ak sa aktivita ukoncuje, prepadni dole a zastav prehravanie.
        if (android.os.Build.VERSION.SDK_INT >= 24 && inPipState.value && !isFinishing) {
            super.onStop(); return
        }
        wasPlaying = engine.ready && mediaPlayer.isPlaying
        super.onStop()
        if (engine.ready) {
            if (isFinishing) {
                // M535: stop() sa NESMIE volat na hlavnom vlakne — pozri teardownPlayerAsync
                teardownPlayerAsync()
                return
            }
            // M623: "Radio hra na pozadi" — zhasnutie/zamok/ina appka radio nepozastavi.
            // HOME riesi onUserLeaveHint (handoff + finish -> vetva vyssie); sem sa
            // dostane zhasnutie obrazovky a prekrytie inou aktivitou. Moderny
            // rezim (M624: aj klasik na telefone): handoff do RadioPlayerService
            // (notifikacia + mini lista; finish() -> onDestroy uvolni tento prehravac).
            // Kde handoff nie je (TV), ostane hrat samotny prehravac — wake/wifi lock
            // (M452) drzi az do onDestroy; surface neodpajame, po navrate onStart len
            // znova attachViews (runCatching).
            // TV: ziadny handoff — TvHomeHost nema mini listu, radio by hralo bez ovladania;
            // prehravac ostane hrat sam (napr. prekryty inou appkou; po standby M540
            // v onStart prehravac obnovi a naladi znova).
            if (wasPlaying && radioBackground()) {
                if (isTvDevice() || !radioHandoffIfPossible()) {
                    CrashLogger.report(this, "PlayerActivity.radioBg", "keep playing in background (tv=${isTvDevice()})")
                }
                return
            }
            if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
            }
            // uvolni surface, nech sa po navrate da znova pripojit (inak cierna obrazovka)
            runCatching { mediaPlayer.detachViews() }
        }
    }


    // ------------------------------------------------------------------
    // M539: vytvorenie prehravaca + obnova po zaseknutom zvukovom vystupe
    // ------------------------------------------------------------------

    /** Vytvori LibVLC + MediaPlayer (VlcEngine.create). Volane z onCreate; recreate ide cez engine. */
    private fun createPlayer() { engine.create() }

    // M650: udalosti libVLC v VlcEvents.kt (rovnaka instancia pre kazdy (znovu)vytvoreny prehravac)
    private val vlcEvents: VlcEvents by lazy {
        VlcEvents(
            player = { if (!playerTornDown && engine.ready) mediaPlayer else null },
            seekable = { seekablePlayback },
            dvrRecording = { dvrRecording },
            htspStream = { htspStream },
            dvrProgStopSec = { dvrProgStopSec },
            actions = object : VlcEvents.Actions {
                override fun scheduleReconnect() { this@PlayerActivity.scheduleReconnect() }
                override fun cancelReconnect() { this@PlayerActivity.cancelReconnect() }
                override fun resetDvrReopen() { reconnect.resetDvrReopen() }
                override fun hideReconnecting() { reconnectingState.value = false }
                override fun reopenDvrLive() { this@PlayerActivity.reopenDvrLive() }
                override fun recoverAfterSeek(): Boolean = this@PlayerActivity.recoverAfterSeek()
                override fun setPlaying(playing: Boolean) { isPlayingState.value = playing }
                override fun refreshPipIfActive() { this@PlayerActivity.refreshPipIfActive() }
                override fun onPlayingForSeek() { dvrSeek.onPlaying() }
                override fun onPlayingForStall() { stall.onPlaying() }
                override fun applyPendingSpuRestore() { lifecycleScope.launch { this@PlayerActivity.applyPendingSpuRestore() } }
                override fun applyDesiredSpu() { lifecycleScope.launch { this@PlayerActivity.applyDesiredSpu() } }
                override fun maybeApplyAfr() { this@PlayerActivity.maybeApplyAfr() }
                override fun keepScreenOn(on: Boolean) { this@PlayerActivity.keepScreenOn(on) }
                override fun hideSeekSpinner() { this@PlayerActivity.hideSeekSpinner() }
                override fun scheduleTrackRefresh() { this@PlayerActivity.scheduleTrackRefresh() }
                override fun maybeReparseForTracks() { this@PlayerActivity.maybeReparseForTracks() }
                override fun bumpTrackList() { tracks.bumpListVersion() }
                override fun saveDvrProgress() { this@PlayerActivity.saveDvrProgress() }
                override fun onReachedEnd() { reachedEnd = true }
                override fun showPlaybackError() {
                    Toast.makeText(this@PlayerActivity, getString(R.string.playback_error, "VLC"), Toast.LENGTH_LONG).show()
                }
            })
    }

    // M539 / M649: hlidac zaseknuteho zvukoveho vystupu v StallWatchdog.kt
    private val stall: StallWatchdog by lazy {
        StallWatchdog(this,
            player = { if (!playerTornDown && engine.ready) mediaPlayer else null },
            recreateAllowed = { !seekablePlayback && !reconnectingState.value },
            onRecreate = { recreatePlayer(); replayCurrentLive() })
    }
    private fun startStallWatch() { stall.start() }
    private fun resetStallState() { stall.reset() }
    /** Vystup nereaguje (zvuk sa pri prehravani nehybe) — stop() by zablokoval hlavne vlakno. */
    private fun outputStalled(): Boolean = stall.outputStalled()

    /** Pred kazdym novym mediom: ak je vystup zaseknuty, vymen prehravac (bez cakania). */
    private fun ensureHealthyPlayer() {
        if (!engine.ready) return
        if (!outputStalled()) return
        CrashLogger.report(this, "PlayerActivity.stall", "media change on stalled output -> new player")
        recreatePlayer()
    }

    /** Vymeni libVLC + MediaPlayer za nove; stare uvolni na pracovnom vlakne (VlcEngine). */
    private fun recreatePlayer() { engine.recreate() }
    /** M539-fix4: prve spustenie prehravania (onStart z VideoSurface) prebehlo. */
    internal var initialStartDone = false
    private fun startPlayback() { engine.startPlayback() }

    /** Znovu spusti aktualny zivy kanal tou istou cestou (HTSP / feeder / HTTP). */
    private fun replayCurrentLive() {
        val srv = liveServer
        val cid = liveUuids.getOrNull(liveIndex)?.toLongOrNull()
        val url = currentStreamUrl
        runCatching {
            if (htspStream && srv != null && cid != null) {
                playHtspLive(srv, cid, htspLive)
            } else if (liveNeedsFeeder == true && srv != null && url != null) {
                playLiveViaFeeder(srv, url)
            } else if (url != null) {
                playHttp(url)
            }
        }
    }

    /** M535: stop/release libVLC na pracovnom vlakne (VlcEngine.teardownAsync); feedery a hlidac ako prve. */
    private fun teardownPlayerAsync() {
        engine.teardownAsync {
            stall.destroy()   // M539
            htspFeeder?.stop(); htspFeeder = null
            httpFeeder?.stop(); httpFeeder = null
        }
    }

    // --- Doplnenie stop po starte (audio jazyky / DVB titulky) ---
    // Pri prvom napojeni streamu libVLC este nema doparsovane doplnkove ES; jazyky audio
    // M637: obnova zoznamu stop po starte a jednorazovy re-parse v TrackState
    private fun scheduleTrackRefresh() { tracks.scheduleRefresh() }
    private fun cancelTrackRefresh() { tracks.cancelRefresh() }
    private fun maybeReparseForTracks() {
        tracks.maybeReparse(htspStream = { htspStream }, seekable = { seekablePlayback }, reconnect = { scheduleReconnect() })
    }

    // ---- M626: AFR (M346) a zamky streamu (M452) vyclenene do AfrController / StreamLocks ----
    private val afr: AfrController by lazy {
        AfrController(this, isTvBox,
            player = { if (engine.ready && !playerTornDown) mediaPlayer else null },
            videoLayout = { videoLayout })
    }
    private val streamLocks: StreamLocks by lazy { StreamLocks(this, "HeadentClient:stream") }

    private fun maybeApplyAfr() { afr.apply() }
    private fun clearAfr() { afr.clear() }
    private fun acquireStreamLocks() { streamLocks.acquire() }
    private fun releaseStreamLocks() { streamLocks.release() }

    override fun onDestroy() {
        pip.destroy() // M578 session + receiver (M653)
        teletext.stopHttp()   // M552
        releaseStreamLocks()  // M452
        flushEpgPersist()     // M456
        clearAfr()
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
        cancelTrackRefresh()
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
private fun exitConfirmOnBack(pipSupported: Boolean, autoPipEnabled: Boolean): Boolean {
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
    seekable: Boolean,
    knownDurationMs: Long,
    progStartFrac: Float = 0f,
    progStopFrac: Float = 1f,
    progStartSec: Long = 0,
    progStopSec: Long = 0,
    progTitleArg: String = "",
    server: sk.tvhclient.shared.model.TvhServer? = null,
    liveChannelUuid: String? = null,
    preferredAudio: List<String> = emptyList(),
    resumeMs: Long = 0,
    dvrUuid: String? = null,
    serverId: String? = null,
    htspSpuItems: List<TrackItem>? = null,   // != null => HTSP: kompletny zoznam titulkov z metadat
    htspSpuCurrentId: Int = -1,
    onPickHtspSpu: ((Int) -> Unit)? = null,
    onPickHttpSpu: ((Int) -> Unit)? = null,
    onAttach: (VLCVideoLayout) -> Unit,
    onStart: () -> Unit,
    onPrevChannel: (() -> Unit)? = null,
    onNextChannel: (() -> Unit)? = null,
    onTogglePlay: () -> Unit = {},
    timeshiftEngaged: Boolean = false,
    modernOvVisible: Boolean = false,
    modernOvRow: Int = 0,
    modernOvCard: Int = 0,
    modernOvStrip: Int = 0,
    modernOvPoke: Int = 0,
    modernOvExec: Int = 0,
    modernOvExecId: String = "",
    modernOvRecNames: Set<String> = emptySet(),
    modernStripIds: List<String> = emptyList(),
    modernMoreVisible: Boolean = false,
    modernMoreIndex: Int = 0,
    onMorePick: (Int) -> Unit = {},
    onMoreDismiss: () -> Unit = {},
    tsMaxMs: Long = 0L,
    onModernOvDismiss: () -> Unit = {},
    onSkipBack: () -> Unit = {},
    onSkipFwd: () -> Unit = {},
    onDoubleTapSeek: (Boolean) -> Unit = {},
    onScrubSeek: (Int) -> Unit = {},
    onLoadChannelEpg: (String, (List<sk.tvhclient.shared.model.EpgEvent>) -> Unit) -> Unit = { _, _ -> },
    seekHint: Int = 0,
    liveChannels: List<LivePlaylist.LiveChannel> = emptyList(),
    liveCurrentIndex: Int = -1,
    onSelectChannel: (Int) -> Unit = {},
    onChannelLongPress: (Int) -> Unit = {},
    lockTick: Int = 0,
    onRefreshEpg: () -> Unit = {},
    onRefreshEpgInitial: () -> Unit = {},
    onPrefetchEpg: () -> Unit = {},
    epgLoading: Boolean = false,
    numberEntry: String = "",
    timeshiftOffsetMs: Long = 0L,
    controlsPoke: Int = 0,
    infoPoke: Int = 0,
    inPip: Boolean = false,
    pipSupported: Boolean = false,
    // PiP tlacidlo v paneli (M349-fix3): oddelene od pipSupported, ktory
    // gate-uje auto-PiP BackHandler a exit-confirm (tie potrebuju schopnost,
    // nie viditelnost tlacidla)
    pipButton: Boolean = false,
    hasVideo: Boolean = true,
    reconnecting: Boolean = false,
    seeking: Boolean = false,
    centerLogoUrl: String? = null,
    onOpenEpg: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    playing: Boolean = true,
    channelNavIndex: Int = -1,
    channelGroupLabel: String = "",
    channelGroupPicker: Boolean = false,
    searchActive: Boolean = false,
    searchQuery: String = "",
    onSearchQueryChange: (String) -> Unit = {},
    searchFieldFocused: Boolean = true,
    searchHits: List<LivePlaylist.LiveChannel> = emptyList(),
    searchNavIndex: Int = 0,
    searchFocusSignal: Int = 0,
    openListSignal: Int = 0,
    closeListSignal: Int = 0,
    onTrackMenuChange: (String?) -> Unit = {},
    onChannelListChange: (Boolean) -> Unit = {},
    openOptionsSignal: Int = 0,
    closeOptionsSignal: Int = 0,
    optionsNavIndex: Int = 0,
    sleepDeadline: Long = 0,
    onOptionsSelect: (Int) -> Unit = {},
    controlNavIndex: Int = 0,
    trackNavIndex: Int = 0,
    trackListVersion: Int = 0,
    closeMenuSignal: Int = 0,
    openAudioSignal: Int = 0,
    openSpuSignal: Int = 0,
    // M383: prepinac stream profilu
    openProfileSignal: Int = 0,
    profileItems: List<String> = emptyList(),
    currentProfile: String = "",
    profileSwitch: Boolean = false,
    onPickProfile: (String) -> Unit = {},
    modernMoreIdList: List<String> = listOf("list", "sleep", "info"),
    onOptionsChange: (Boolean) -> Unit = {},
    onControlsVisibleChange: (Boolean) -> Unit = {},
    pinPrompt: Boolean = false,
    pinLen: Int = 0,
    pinError: Boolean = false,
    onPinDigit: (Int) -> Unit = {},
    onPinBack: () -> Unit = {},
    onPinCancel: () -> Unit = {},
    onPinOpenList: () -> Unit = {},
    pinGridRow: Int = 0,
    pinGridCol: Int = 0,
    scrubFrac: Float = 0f,
    progNextTitle: String = "",
    progNextStart: Long = 0,
    progNextStop: Long = 0,
    zapPoke: Int = 0,
    recordingLive: Boolean = false,
    recordingStopSec: Long = 0,
    recordingOffsetMs: Long = 0,
    onPlayheadMs: (Long) -> Unit = {},
    seekSeedMs: Long = -1L,
    onSeekSeedHandled: () -> Unit = {},
    onSeekToMs: (Long) -> Unit = {},
    resumeSel: Int = 1,
    resumeAnswer: Int = 0,
    onAskResumeChange: (Boolean) -> Unit = {},
    onResumeAnswerHandled: () -> Unit = {},
    onOrientationLockChange: (Boolean) -> Unit = {},
    returnLiveOnBack: Boolean = false,
    onRequestExit: () -> Unit = {},
    onClose: () -> Unit
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
    LaunchedEffect(sleepDeadline) {
        while (sleepDeadline > 0) {
            sleepNow = System.currentTimeMillis()
            kotlinx.coroutines.delay(20_000)
        }
    }
    val sleepLeftMin = if (sleepDeadline > 0)
        (((sleepDeadline - sleepNow) + 59_999) / 60_000).coerceAtLeast(0) else 0L
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
    LaunchedEffect(controlsPoke) {
        if (controlsPoke > 0) controlsVisible = true
    }
    // INFO signal -> prepni okno s detailom relacie
    LaunchedEffect(infoPoke) {
        if (infoPoke > 0) showInfo = !showInfo
    }
    // v PiP rezime skry vsetky ovladacie prvky (okno je male)
    LaunchedEffect(inPip) {
        if (inPip) {
            controlsVisible = false; showInfo = false; showMoreSheet = false
            showChannelList = false; menu = null; showOptions = false
        }
    }
    // oznam Activity ci je ovladanie zobrazene (vtedy D-pad navigaciu riesi Activity)
    LaunchedEffect(controlsVisible) { onControlsVisibleChange(controlsVisible) }
    // oznam Activity stav prekryti (kvoli D-pad smerovaniu)
    LaunchedEffect(menu) { onTrackMenuChange(menu) }
    LaunchedEffect(showChannelList) { onChannelListChange(showChannelList) }
    LaunchedEffect(showOptions) { onOptionsChange(showOptions) }
    // Activity ziada otvorit/zavriet zoznam kanalov (podrzanie OK)
    LaunchedEffect(openListSignal) {
        if (openListSignal > 0) { showChannelList = true; controlsVisible = false }
    }
    LaunchedEffect(closeListSignal) {
        if (closeListSignal > 0) showChannelList = false
    }
    // Moznosti (Zvuk/Titulky/SW) cez D-pad DOLE / MENU
    LaunchedEffect(openOptionsSignal) {
        if (openOptionsSignal > 0) { showOptions = true; controlsVisible = false }
    }
    LaunchedEffect(closeOptionsSignal) {
        if (closeOptionsSignal > 0) showOptions = false
    }
    LaunchedEffect(openAudioSignal) { if (openAudioSignal > 0) { menu = "audio"; controlsVisible = false } }
    LaunchedEffect(openSpuSignal) { if (openSpuSignal > 0) { menu = "spu"; controlsVisible = false } }
    LaunchedEffect(openProfileSignal) { if (openProfileSignal > 0) { menu = "profile"; controlsVisible = false } }
    LaunchedEffect(closeMenuSignal) { if (closeMenuSignal > 0) menu = null }
    // ikona play/pause podla skutocneho stavu prehravaca
    LaunchedEffect(playing) { isPlaying = playing }
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
    val lengthMs = if (knownDurationMs > 0) knownDurationMs else player.length.coerceAtLeast(0L)
    // Cerstva dlzka pre ticker (LaunchedEffect(Unit) inak zachyti hodnotu zo startu a
    // lava strana by sa nezmestila nad uroven zivej hrany pri starte).
    val lengthMsLive = androidx.compose.runtime.rememberUpdatedState(lengthMs)
    val offsetMsLive = androidx.compose.runtime.rememberUpdatedState(recordingOffsetMs)
    // M495-fix: to iste plati pre seed z pretocenia. Ticker bezi v LaunchedEffect(Unit),
    // takze si hodnotu parametra zapamata pri PRVEJ kompozicii a novu uz nikdy neuvidi —
    // seed teda nikdy nedorazil, hodiny sa na ciel neprepli a resync ich zrazil takmer
    // na nulu (odtial "0:59" hned po skoku na 40. minutu).
    val seekSeedLive = androidx.compose.runtime.rememberUpdatedState(seekSeedMs)
    val onSeekSeedHandledLive = androidx.compose.runtime.rememberUpdatedState(onSeekSeedHandled)
    // Pri prebiehajucej nahravke nedovol pretocit az na zivu hranu (koniec dostupnych dat).
    // Zapisane data zaostavaju za EPG casom (prava strana) o cca 20-30 s, takze rezerva
    // pocitana z EPG casu musi byt vacsia, inak playhead skoci do este nezapisanej zony,
    // narazi na EOF a TS zamrzne. Vacsia rezerva = playhead ostava v spolahlivo nahranych
    // datach. Hltavy koniec doriesi este aj automaticke znovu-otvorenie streamu.
    val liveMarginMs = 45_000L
    // Dlzka pre seekbar = dosiahnutelny rozsah (bez 45 s rezervy pri prebiehajucej nahravke).
    // Tak playhead dosiahne koniec baru bez viditeľnej medzery/"bariery" - rezerva je skryta.
    val barLengthMs = if (recordingLive) (lengthMs - liveMarginMs).coerceAtLeast(1L) else lengthMs

    // Obnovenie pozicie (len DVR): spytaj sa, a po potvrdeni pretoc ked je
    // media nacitana
    var askResume by remember { mutableStateOf(resumeMs > 0) }
    var pendingResumeMs by remember { mutableStateOf(0L) }
    // Most na D-pad obsluhu dialogu v Activity: nahlas viditelnost a reaguj na odpoved
    LaunchedEffect(askResume) { onAskResumeChange(askResume) }
    LaunchedEffect(resumeAnswer) {
        if (resumeAnswer != 0 && askResume) {
            if (resumeAnswer == 1) pendingResumeMs = resumeMs
            askResume = false
            onResumeAnswerHandled()
        }
    }

    // Aktualizuj poziciu kazdu sekundu (len ked je seekable a netiahneme)
    // M662: telo tickera vyclanene do DvrPositionTicker.kt (JVM 64 KB limit metody).
    if (seekable) {
        DvrPositionTicker(
            player = player,
            ctx = ctx,
            lengthMsLive = lengthMsLive,
            offsetMsLive = offsetMsLive,
            seekSeedLive = seekSeedLive,
            onSeekSeedHandledLive = onSeekSeedHandledLive,
            recordingLive = recordingLive,
            liveMarginMs = liveMarginMs,
            dvrUuid = dvrUuid,
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
            onSeekToMs = onSeekToMs,
            onPlayheadMs = onPlayheadMs,
        )
    }

    // Live priebeh aktualnej relacie (z EPG): tika po sekundach
    var liveNowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    // Aktualna relacia (mutable — pri dobehnuti sa nacita dalsia)
    var progStart by remember(liveChannelUuid) { mutableStateOf(progStartSec) }
    var progStop by remember(liveChannelUuid) { mutableStateOf(progStopSec) }
    var progTitle by remember(liveChannelUuid) { mutableStateOf(progTitleArg) }
    var progDesc by remember(liveChannelUuid) { mutableStateOf("") }
    var nextTitle by remember(liveChannelUuid) { mutableStateOf(progNextTitle) }
    var nextStart by remember(liveChannelUuid) { mutableStateOf(progNextStart) }
    var nextStop by remember(liveChannelUuid) { mutableStateOf(progNextStop) }
    val hasLiveProg = !seekable && progStart > 0 && progStop > progStart

    if (!seekable && liveChannelUuid != null && server != null) {
        LaunchedEffect(Unit) {
            // tik kazdu sekundu
            while (true) {
                liveNowSec = System.currentTimeMillis() / 1000
                kotlinx.coroutines.delay(1000)
            }
        }
        LaunchedEffect(liveChannelUuid) {
            // hned po prepnuti nacitaj plne EPG (popis + dalsia relacia),
            // potom obnovuj ked aktualna relacia dobehne
            var firstDone = false
            while (true) {
                val now = System.currentTimeMillis() / 1000
                if (!firstDone || progStart == 0L || progStop == 0L || now >= progStop) {
                    val list = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val api = Tvh.apiFor(server)
                            try { Tvh.fetchEpgForChannel(server, api, liveChannelUuid) }
                            finally { api.close() }
                        }
                    } catch (e: Exception) { emptyList() }
                    val cur = list.firstOrNull { it.start <= now && now < it.stop }
                    if (cur != null) {
                        progStart = cur.start; progStop = cur.stop
                        progTitle = cur.title
                        progDesc = cur.bestDescription
                        val nx = list.firstOrNull { it.start >= cur.stop }
                        if (nx != null) {
                            nextTitle = nx.title; nextStart = nx.start; nextStop = nx.stop
                        }
                    }
                    firstDone = true
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    } else if (hasLiveProg) {
        LaunchedEffect(Unit) {
            while (true) {
                liveNowSec = System.currentTimeMillis() / 1000
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    // M662: auto-vyber audio stopy (M378) je v AudioAutoSelect.kt
    AudioAutoSelectEffect(
        player = player,
        ctx = ctx,
        liveChannelUuid = liveChannelUuid,
        serverId = serverId,
        preferredAudio = preferredAudio
    )

    LaunchedEffect(controlsVisible, menu, controlsPoke, dragging) {
        if (controlsVisible && menu == null && !dragging) {
            kotlinx.coroutines.delay(3000)
            controlsVisible = false
        }
    }

    // telefon: BACK z cisteho prehravania -> PiP (odkryje domovsku obrazovku), nie ukoncenie.
    // skomponovany ako prvy => ma najnizsiu prioritu, specifickejsie handlery nizsie maju prednost.
    // riadi sa nastavenim automatickeho PiP.
    val autoPipEnabled = remember { AutoPipPref.get(ctx) }
    androidx.activity.compose.BackHandler(
        enabled = autoPipEnabled && pipSupported && playing && !controlsVisible && menu == null && !showChannelList && !showOptions
    ) { onEnterPip() }
    androidx.activity.compose.BackHandler(enabled = showChannelList) { showChannelList = false }
    androidx.activity.compose.BackHandler(enabled = menu != null) { menu = null }
    androidx.activity.compose.BackHandler(
        enabled = controlsVisible && menu == null && !showChannelList && !showOptions
    ) { controlsVisible = false }
    // "Prehrat od zaciatku" zo zivej TV: Spat (ked nie je nic otvorene) vrati na povodny zivy kanal
    androidx.activity.compose.BackHandler(
        enabled = returnLiveOnBack && !controlsVisible && menu == null && !showChannelList && !showOptions
    ) { onClose() }
    // M280: BACK pri cistom zivom prehravani (mimo PiP) -> potvrdenie ukoncenia (ako exit v menu),
    // aby nechcene stlacenie Spat hned neukoncilo prehravanie.
    // M280-fix: LEN na TV (zariadenia bez PiP). Na mobile/tablete (pipSupported) sa
    // potvrdenie nezobrazuje vobec — BACK tam riesi PiP / bezne spravanie.
    // M537: „TV" sa NESMIE odvodzovat z !pipSupported — TV boxy s PiP (Homatics,
    // Shield, Raspberry Pi; pozri M429) potvrdenie nedostali a BACK ukoncil
    // prehravanie hned. Rozhoduje rezim UI (leanback): na TV sa potvrdenie
    // zobrazi vzdy, okrem pripadu, ked ma prednost auto-PiP handler vyssie
    // (zapnuty auto-PiP na boxe s PiP -> BACK = miniatura, ako doteraz).
    // (M537-fix: vypocet je v samostatnej composable — PlayerUi je na 64 KB limite metody.)
    androidx.activity.compose.BackHandler(
        enabled = exitConfirmOnBack(pipSupported, autoPipEnabled) && !seekable && !controlsVisible && menu == null
                  && !showChannelList && !showOptions && !returnLiveOnBack && !showInfo
    ) { onRequestExit() }

    // M662: EPG efekty (M266 prefetch + M522/M525 periodicky refresh) su v PlayerEpgEffects.kt
    PlayerEpgEffects(
        showChannelList = showChannelList,
        controlsVisible = controlsVisible,
        modernOvVisible = modernOvVisible,
        onPrefetchEpg = onPrefetchEpg,
        onRefreshEpgInitial = onRefreshEpgInitial,
        onRefreshEpg = onRefreshEpg
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isTvGest, seekable, timeshiftEngaged, controlsVisible) {
                val audio = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                val act = ctx as? android.app.Activity
                val maxVol = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var mode = 0          // 0=nerozhodnute, 1=seek(H), 2=hlasitost(V vpravo), 3=jas(V vlavo), 4=otvor zoznam (V zhora)
                    var startVol = 0
                    var startBright = 0.5f
                    val guardTop = 48.dp.toPx()              // odsadenie od hornej hrany (systemova lista/shade)
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dx = ch.position.x - down.position.x
                        val dy = ch.position.y - down.position.y
                        // Gesta zachovaj po ploche; zakaz ich len v oblasti spodneho baru
                        // (ovladanie/slider), ked je viditelny - tam pretacas cez slider.
                        val inBar = controlsVisible && down.position.y > size.height * 0.6f
                        // M565: gesta prehravaca (hlasitost, jas, seek, vysunutie zoznamu) su vypnute,
                        // kym je otvorene akekolvek menu — tento detektor nerespektuje consume()
                        // deti (awaitFirstDown(requireUnconsumed = false)), takze ho treba vypnut tu
                        val overlayOpen = showChannelList || showMoreSheet || menu != null || showOptions
                        if (mode == 0 && !overlayOpen && !inBar && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                            mode = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                                if (seekable || timeshiftEngaged) 1 else 0   // seek len ked je co pretacat
                            } else if (isTvGest) {
                                0                                            // na TV ziadne gesta
                            } else if (down.position.y <= guardTop) {
                                0                                            // horny okraj (systemova lista/wifi) -> ziadne vertikalne gesto
                            } else if (down.position.x < size.width * 0.25f) {
                                val cur = act?.window?.attributes?.screenBrightness ?: -1f
                                startBright = if (cur in 0f..1f) cur else 0.5f; 3                              // lavych 25% = jas
                            } else if (down.position.x >= size.width * 0.75f) {
                                startVol = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC); 2   // pravych 25% = hlasitost
                            } else if (dy > 0) {
                                4                                            // stred 50% (0.25-0.75), tah dole -> otvor zoznam
                            } else {
                                0                                            // ine -> nic
                            }
                        }
                        if (mode != 0) ch.consume()
                        when (mode) {
                            1 -> scrubSecState = (dx / size.width * 90f).toInt()
                            4 -> listFrac = (dy / (size.height * 0.5f) * 0.7f).coerceIn(0f, 1f)   // vysuvanie zhora za prstom (o 30% pomalsie)
                            2 -> {
                                val nv = (startVol - dy / size.height * maxVol).toInt().coerceIn(0, maxVol)
                                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, nv, 0)
                                volPctState = nv * 100 / maxVol
                            }
                            3 -> {
                                val nb = (startBright - dy / size.height).coerceIn(0.01f, 1f)
                                act?.window?.let { w -> val lp = w.attributes; lp.screenBrightness = nb; w.attributes = lp }
                                brightPctState = (nb * 100).toInt()
                            }
                        }
                    }
                    if (mode == 1) {
                        val secs = scrubSecState
                        if (secs != Int.MIN_VALUE && secs != 0) onScrubSeek(secs)
                    }
                    if (mode == 4) {
                        val open = listFrac > 0.33f
                        showChannelList = open        // open -> LaunchedEffect dotiahne na 1
                        if (!open) listScope.launch {
                            androidx.compose.animation.core.animate(listFrac, 0f) { v, _ -> listFrac = v }
                        }
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (menu != null) menu = null else controlsVisible = !controlsVisible },
                    onDoubleTap = { off -> onDoubleTapSeek(off.x > size.width / 2f) }
                )
            }
    ) {
        val inPreview = showChannelList && isTvGest && liveChannels.isNotEmpty() && previewRect != null
        // M539-fix2: AndroidView je v samostatnej composable (mensia PlayerUi + vymena surface)
        VideoSurface(
            modifier = if (inPreview) {
                val r = previewRect!!
                Modifier
                    .absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            } else Modifier.fillMaxSize(),
            onAttach = onAttach,
            onStart = onStart
        )

        // M630: male prekryvy v PlayerOverlays.kt (poradie zachovane)
        // Audio-only (rozhlas): namiesto ciernej vycentrovane logo; na TV so zoznamom v nahlade
        if (!hasVideo) RadioCenterLogo(centerLogoUrl, server, if (inPreview) previewRect else null)
        // indikator opätovného pripájania (vypadok siete pri zivom vysielani)
        if (reconnecting) ReconnectingOverlay()
        // koliesko v strede pocas pretacania timeshiftu (resync)
        if (seeking && !reconnecting) SeekingSpinner()
        // YouTube-style hint pri dvojkliku (skok o 10 s)
        if (seekHint != 0) SeekHintOverlay(seekHint)
        // MX Player overlaye: hlasitost / jas (vystredene), seek-scrub (hore v strede)
        if (volPctState >= 0 || brightPctState >= 0) GestureLevelOverlay(volPctState, brightPctState)
        if (scrubSecState != Int.MIN_VALUE) ScrubSecondsOverlay(scrubSecState)
        // prekrytie s prave zadavanym cislom kanala
        if (numberEntry.isNotEmpty()) NumberEntryOverlay(numberEntry)

        // M661: ovladaci pruh (horny info blok + seekbar + tlacidla) je v PlayerControlBar.kt
        PlayerControlsOverlay(
            controlsVisible = controlsVisible,
            onControlsVisibleSet = { controlsVisible = it },
            ctx = ctx,
            dvrActivity = dvrActivity,
            title = title,
            seekable = seekable,
            pipButton = pipButton,
            pipSupported = pipSupported,
            timeshiftEngaged = timeshiftEngaged,
            profileSwitch = profileSwitch,
            controlNavIndex = controlNavIndex,
            liveChannels = liveChannels,
            liveCurrentIndex = liveCurrentIndex,
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
            timeshiftOffsetMs = timeshiftOffsetMs,
            barLengthMs = barLengthMs,
            lengthMs = lengthMs,
            recordingOffsetMs = recordingOffsetMs,
            scrubFrac = scrubFrac,
            progStartFrac = progStartFrac,
            progStopFrac = progStopFrac,
            dragging = dragging,
            onDraggingSet = { dragging = it },
            dragValue = dragValue,
            onDragValueSet = { dragValue = it },
            posTimeMs = posTimeMs,
            onPosTimeMsSet = { posTimeMs = it },
            onPosFractionSet = { posFraction = it },
            onSeekToMs = onSeekToMs,
            isPlaying = isPlaying,
            menu = menu,
            onMenuSet = { menu = it },
            onShowChannelListSet = { showChannelList = it },
            showInfo = showInfo,
            onShowInfoSet = { showInfo = it },
            onShowMoreSheetSet = { showMoreSheet = it },
            orientationLocked = orientationLocked,
            onOrientationLockedSet = { orientationLocked = it },
            onOrientationLockChange = onOrientationLockChange,
            onPrevChannel = onPrevChannel,
            onNextChannel = onNextChannel,
            onTogglePlay = onTogglePlay,
            onSkipBack = onSkipBack,
            onSkipFwd = onSkipFwd,
            onOpenEpg = onOpenEpg,
            onEnterPip = onEnterPip,
            onOpenSleep = onOpenSleep,
            onClose = onClose
        )

        // "Viac" panel moderneho rezimu (telefon)
        if (showMoreSheet) {
            androidx.activity.compose.BackHandler { showMoreSheet = false }
            val lockVis = pipSupported && OrientationPref.get(ctx) == OrientationPref.AUTO
            ModernMoreSheet(
                lockVisible = lockVis,
                orientationLocked = orientationLocked,
                pipVisible = pipButton,
                profileVisible = profileSwitch,
                onProfile = { showMoreSheet = false; menu = "profile" },
                onPip = { showMoreSheet = false; onEnterPip() },
                onSubs = { showMoreSheet = false; menu = "spu" },
                // M490: rovnaky stav aj akcia ako klasicky bar a TV overlay
                recordVisible = dvrActivity?.dvrRecordVisible() == true,
                recordIsCancel = dvrActivity?.dvrExistingState?.value != null,
                onRecord = {
                    showMoreSheet = false
                    dvrActivity?.toggleRecordCurrent()
                },
                teletextVisible = dvrActivity?.teletextVisible() == true,   // M559
                onTeletext = { showMoreSheet = false; dvrActivity?.openTeletext() },
                onSleep = { showMoreSheet = false; onOpenSleep() },
                onLockToggle = {
                    orientationLocked = !orientationLocked
                    onOrientationLockChange(orientationLocked)
                },
                onInfo = { showMoreSheet = false; showInfo = true },
                onDismiss = { showMoreSheet = false },
            )
        }

        // Moderny TV overlay (karty kanalov + ovladacia lista) — exkluzivita,
        // auto-hide a vykonanie akcii z listy (signal z Activity key handlera)
        if (modernOvVisible) {
            LaunchedEffect(Unit) {
                controlsVisible = false; menu = null; showChannelList = false
                showInfo = false; showOptions = false
            }
        }
        LaunchedEffect(modernOvVisible, modernOvPoke) {
            if (modernOvVisible) {
                kotlinx.coroutines.delay(6000)
                onModernOvDismiss()
            }
        }
        LaunchedEffect(modernOvExec) {
            if (modernOvExec > 0) when (modernOvExecId) {
                "card" -> onSelectChannel(modernOvCard)
                "audio" -> menu = "audio"
                "subs" -> menu = "spu"
                "sleep" -> onOpenSleep()
                "epg" -> onOpenEpg()
                "info" -> showInfo = true
            }
        }
        if (modernOvVisible && isTvGest) {
            val ovSrv = remember { sk.tvhclient.shared.Tvh.store.active() }
            val ovLoader = remember(ovSrv?.id) { PiconImageLoader.get(ctx, ovSrv) }
            ModernTvOverlay(
                channels = liveChannels,
                currentIndex = liveCurrentIndex,
                cardIndex = modernOvCard,
                focusRow = modernOvRow,
                stripIndex = modernOvStrip,
                stripIds = modernStripIds,
                recNames = modernOvRecNames,
                isPlaying = isPlaying,
                tsEngaged = timeshiftEngaged,
                tsOffsetMs = timeshiftOffsetMs,
                tsMaxMs = tsMaxMs,
                imageLoader = ovLoader,
            )
        }

        // Info okno: detail prave beziacej relacie (INFO kláves / tlacidlo) — M661: PlayerInfoWindow.kt
        if (showInfo) {
            PlayerInfoWindow(
                setShowInfo = { v -> showInfo = v },
                title = title,
                seekable = seekable,
                progStart = progStart,
                progStop = progStop,
                progTitle = progTitle,
                progDesc = progDesc,
                nextTitle = nextTitle,
                nextStart = nextStart,
                nextStop = nextStop,
                liveNowSec = liveNowSec,
                liveChannels = liveChannels,
                liveCurrentIndex = liveCurrentIndex,
                dvrActivity = dvrActivity
            )
        }

        // Overlay: zoznam kanalov priamo v prehravaci (vysuva sa zhora podla listFrac) — TELEFON (M631: PhoneChannelList.kt)
        if ((showChannelList || listFrac > 0.001f) && !isTvGest && liveChannels.isNotEmpty()) {
            PhoneChannelListOverlay(
                listFrac = listFrac,
                liveChannels = liveChannels,
                server = server,
                serverId = serverId,
                liveCurrentIndex = liveCurrentIndex,
                channelNavIndex = channelNavIndex,
                epgLoading = epgLoading,
                lockTick = lockTick,
                liveNowSec = liveNowSec,
                onSelectChannel = onSelectChannel,
                onChannelLongPress = onChannelLongPress,
                onClose = { showChannelList = false }
            )
        }
        // TV zoznam kanalov (M632: TvChannelList.kt)
        if (showChannelList && isTvGest && liveChannels.isNotEmpty()) {
            TvChannelListOverlay(
                liveChannels = liveChannels,
                server = server,
                serverId = serverId,
                liveCurrentIndex = liveCurrentIndex,
                channelNavIndex = channelNavIndex,
                epgLoading = epgLoading,
                lockTick = lockTick,
                liveNowSec = liveNowSec,
                onLoadChannelEpg = onLoadChannelEpg,
                channelGroupLabel = channelGroupLabel,
                channelGroupPicker = channelGroupPicker,
                searchActive = searchActive,
                searchQuery = searchQuery,
                onSearchQueryChange = onSearchQueryChange,
                searchFieldFocused = searchFieldFocused,
                searchHits = searchHits,
                searchNavIndex = searchNavIndex,
                searchFocusSignal = searchFocusSignal,
                inPreview = inPreview,
                previewRect = previewRect,
                onPreviewRect = { r -> previewRect = r }
            )
        }

        // "Viac" menu modernej listy (M327): Kanaly / Casovac uspatia / Informacie (M633: PlayerMenus.kt)
        if (modernMoreVisible) {
            ModernMoreMenu(
                ids = modernMoreIdList,
                highlightIndex = if (isTvGest) modernMoreIndex else -1,   // M385-fix
                recActive = dvrActivity?.dvrExistingState?.value != null,
                onPick = onMorePick,
                onDismiss = onMoreDismiss
            )
        }

        // Vyber dlzky casovaca uspatia — vertikalne, navigacia z Activity (M633: PlayerMenus.kt)
        if (showOptions) {
            SleepOptionsMenu(
                highlightIndex = if (isTvGest) optionsNavIndex else -1,   // M385-fix
                onSelect = onOptionsSelect,
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
                trackListVersion = trackListVersion,
                trackNavIndex = trackNavIndex,
                profileItems = profileItems,
                currentProfile = currentProfile,
                onPickProfile = onPickProfile,
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
                resumeMs = resumeMs, resumeSel = resumeSel,
                onNo = { askResume = false },
                onYes = { pendingResumeMs = resumeMs; askResume = false }
            )
        }

        // Rodicovsky zamok: zadanie PIN (cislice z dialkoveho riesi Activity; M633: PlayerMenus.kt)
        if (pinPrompt) {
            PlayerPinPanel(
                pinLen = pinLen, pinError = pinError,
                gridRow = if (isTvGest) pinGridRow else -1, gridCol = if (isTvGest) pinGridCol else -1,
                onDigit = onPinDigit, onBack = onPinBack, onOpenList = onPinOpenList
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
