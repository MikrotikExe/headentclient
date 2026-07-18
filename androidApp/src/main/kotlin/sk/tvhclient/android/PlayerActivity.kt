package sk.tvhclient.android

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.focus.focusRequester
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Forward30
import coil.request.ImageRequest
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.htsp.HtspData

/**
 * Live prehravac na libVLC. Dekoduje MPEG-2 + MP2/AC3/EAC3/DTS softverovo.
 * Ovladanie je Compose overlay: play/pause, zavriet, vyber audio stopy
 * (jazyk) a titulkov (libVLC get/setAudioTrack, get/setSpuTrack).
 */
class PlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    private lateinit var libVlc: LibVLC
    private var htspFeeder: HtspTsFeeder? = null
    private var httpFeeder: HttpTsFeeder? = null
    private var dvrViaFeeder = false
    private var htspLive = false
    private val htspStreamState = androidx.compose.runtime.mutableStateOf(false)
    private var htspStream: Boolean
        get() = htspStreamState.value
        set(v) { htspStreamState.value = v }
    // HTSP titulky: kompletny zoznam jazykov berieme z metadat (feeder.subtitleStreams),
    // nie z libVLC (to ma len jazyky, ktore uz "prehovorili"). Vyber mapujeme na realnu
    // libVLC stopu podla anglickeho nazvu jazyka (libVLC DVB titulky netaguje kodom).
    private val selectedSubEsState = androidx.compose.runtime.mutableStateOf(-1)  // -1 = Vypnute
    private var desiredSubName: String? = null   // anglicky nazov zvoleneho jazyka (null = vypnute)
    // M262: ci uz prebehlo urcenie HTSP rezimu pre toto sedenie. doPlay (startovacie
    // prehratie) ho nastavi; ak vsak pouzivatel prepne kanal este pred doPlay (napr.
    // odchod z PIN vyzvy zamknuteho kanala), inicializuje HTSP switchToIndex.
    private var htspInitDone = false
    private val htspLiveState = androidx.compose.runtime.mutableStateOf(false)
    private val timeshiftOffsetState = androidx.compose.runtime.mutableStateOf(0L)
    // timeshift "zapnuty" (po prvej pauze) -> az vtedy davaju zmysel RW/FF a dvojklik
    private val timeshiftEngagedState = androidx.compose.runtime.mutableStateOf(false)

    // ===== Moderny TV overlay (karty kanalov + ovladacia lista) =====
    private val modernOvState = androidx.compose.runtime.mutableStateOf(false)
    private val modernOvRow = androidx.compose.runtime.mutableStateOf(0)      // 0 = karty, 1 = lista
    private val modernOvCard = androidx.compose.runtime.mutableStateOf(0)
    private val modernOvStrip = androidx.compose.runtime.mutableStateOf(0)
    private val modernOvPoke = androidx.compose.runtime.mutableStateOf(0)
    private val modernOvExec = androidx.compose.runtime.mutableStateOf(0)     // signal pre composable
    private val modernOvExecId = androidx.compose.runtime.mutableStateOf("")
    private var modernOkLong = false
    // OK z prehravania: overlay otvarame az na OK-UP, aby pri podrzani nepreblikol (M328)
    private var modernOkPending = false

    private val isTvBox by lazy {
        (getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager)
            ?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Moderny overlay ma zmysel len na TV, v modernom rezime, pri zivom so zoznamom. */
    private fun modernTvActive(): Boolean =
        isTvBox && UiModePref.get(this) == UiModePref.MODERN &&
            !seekablePlayback && liveUuids.size > 1

    /** Polozky ovladacej listy overlayu (transport v strede; pretacanie len pri timeshiftu). */
    private fun modernStripIds(): List<String> = buildList {
        add("epg"); add("audio")
        // prepinanie kanalov priamo z listy (M323) — len pri live s viac kanalmi
        val zap = !seekablePlayback && liveUuids.size > 1
        if (zap) add("chprev")
        if (timeshiftEngagedState.value) add("tsrew")
        add("play")
        if (timeshiftEngagedState.value) add("tsff")
        if (zap) add("chnext")
        add("subs"); add("more")
    }

    // "Viac" menu listy (M327): menej pouzivane polozky — rezerva pre dlhsie preklady
    private val modernMoreState = androidx.compose.runtime.mutableStateOf(false)
    private val modernMoreIdx = androidx.compose.runtime.mutableStateOf(0)
    // M383: "profile" pribudne len ked je prepinac dostupny (HTTP live)
    private fun modernMoreIds(): List<String> = buildList {
        add("list"); add("sleep"); add("info")
        if (profileSwitchAvailable()) add("profile")
    }
    private fun modernMoreActivate() {
        val id = modernMoreIds().getOrNull(modernMoreIdx.value) ?: return
        modernMoreState.value = false
        when (id) {
            "list" -> { closeModernOverlay(); openChannelList() }
            "sleep" -> { closeModernOverlay(); openSleepMenu() }
            "info" -> { closeModernOverlay(); toggleInfo() }
            "profile" -> { closeModernOverlay(); openProfileMenu() }
        }
    }

    private fun openModernOverlay() {
        modernOvCard.value = liveIndexState.value.coerceAtLeast(0)
        modernOvStrip.value = modernStripIds().indexOf("play").coerceAtLeast(0)
        modernOvRow.value = 0
        modernOvPoke.value++
        modernOvState.value = true
    }

    private fun closeModernOverlay() { modernOvState.value = false }

    /** OK v overlayi: karta -> prepni kanal; lista -> vykonaj akciu. */
    private fun modernOvActivate() {
        if (modernOvRow.value == 0) {
            modernOvExecId.value = "card"; modernOvExec.value++
            closeModernOverlay()
        } else when (modernStripIds().getOrNull(modernOvStrip.value)) {
            "play" -> { togglePlayPause(); modernOvPoke.value++ }
            "tsrew" -> { timeshiftSkip(-30); modernOvPoke.value++ }
            "tsff" -> { timeshiftSkip(+30); modernOvPoke.value++ }
            "more" -> { modernMoreIdx.value = 0; modernMoreState.value = true }
            "chprev" -> { switchLive(-1); modernOvCard.value = liveIndexState.value.coerceAtLeast(0); modernOvPoke.value++ }
            "chnext" -> { switchLive(+1); modernOvCard.value = liveIndexState.value.coerceAtLeast(0); modernOvPoke.value++ }
            null -> {}
            else -> {
                modernOvExecId.value = modernStripIds()[modernOvStrip.value]; modernOvExec.value++
                closeModernOverlay()
            }
        }
    }
    private var tsAccumMs = 0L
    private var tsPauseStartedAt = 0L
    private var htspStartedAt = 0L
    private var pendingSkipMs = 0L
    private var skipFlushJob: kotlinx.coroutines.Job? = null
    private var timeshiftTickerJob: kotlinx.coroutines.Job? = null
    private lateinit var mediaPlayer: MediaPlayer

    // Live zapping (prepinanie kanalov v prehravaci)
    private var liveUuids: List<String> = emptyList()
    private var liveNames: List<String> = emptyList()
    private var liveIndex: Int = -1
    private var playKind: String = "tv"
    // pre opatovne pripojenie videa po navrate z pozadia
    private var videoLayout: VLCVideoLayout? = null
    private var subOverlay: SubtitleOverlayView? = null
    private var wasPlaying: Boolean = false
    // Picture-in-Picture (obraz v obraze)
    private val inPipState = androidx.compose.runtime.mutableStateOf(false)
    // false = audio-only (rozhlas) -> zobraz logo namiesto ciernej
    private val hasVideoState = androidx.compose.runtime.mutableStateOf(true)
    private val videoCheckHandler = android.os.Handler(android.os.Looper.getMainLooper())
    // automaticke znovupripojenie zivého streamu po vypadku siete
    private val reconnectHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnectingState = androidx.compose.runtime.mutableStateOf(false)
    // tocenie pri pretacani timeshiftu (kratky resync pipe -> libVLC)
    private val seekingState = androidx.compose.runtime.mutableStateOf(false)
    private var seekSpinnerJob: kotlinx.coroutines.Job? = null
    // YouTube-style dvojklik pretacanie: nazbierane sekundy (+/-), 0 = skryte
    private val seekHintState = androidx.compose.runtime.mutableStateOf(0)
    private var seekHintJob: kotlinx.coroutines.Job? = null
    // Akumulovany dvojklik (DVR): vychodzi playhead serie klikov + odlozeny commit, aby
    // viac klikov za sebou pretocilo RAZ (kazdy klik = plny feeder restart, nedaju sa tlct).
    private var seekAccumBaseMs: Long = -1L
    private var seekCommitJob: kotlinx.coroutines.Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectAttempts = 8
    private var pipReceiver: android.content.BroadcastReceiver? = null
    private val PIP_ACTION = "sk.tvhclient.android.PIP_TOGGLE"
    private var liveServer: sk.tvhclient.shared.model.TvhServer? = null
    private val liveTitleState = androidx.compose.runtime.mutableStateOf("")
    private val liveUuidState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val liveProgStartState = androidx.compose.runtime.mutableStateOf(0L)
    private val liveProgStopState = androidx.compose.runtime.mutableStateOf(0L)
    private val liveProgTitleState = androidx.compose.runtime.mutableStateOf("")
    private val liveNextTitleState = androidx.compose.runtime.mutableStateOf("")
    private val liveNextStartState = androidx.compose.runtime.mutableStateOf(0L)
    private val liveNextStopState = androidx.compose.runtime.mutableStateOf(0L)
    private val zapPokeState = androidx.compose.runtime.mutableStateOf(0)
    private val liveIndexState = androidx.compose.runtime.mutableStateOf(-1)
    private val liveChannelsState =
        androidx.compose.runtime.mutableStateOf<List<LivePlaylist.LiveChannel>>(emptyList())
    // cache mapa kanal(uuid) -> aktualna + najblizsie relacie (pre EPG browser na TV)
    private val epgUpcomingState =
        androidx.compose.runtime.mutableStateOf<Map<String, List<sk.tvhclient.shared.model.EpgEvent>>>(LivePlaylist.epgUpcoming)
    // M270: spinner pri prvom/zastaranom nacitani EPG v zozname kanalov
    private val epgLoadingState = androidx.compose.runtime.mutableStateOf(false)
    // M271: cas poslednej obnovy nacitavame z procesovej cache, aby reopen nesťahoval znova
    private var epgLastOkMs = LivePlaylist.epgLastOkMs

    // D-pad / diaľkové: signál na zobrazenie ovládania, info pre seek a sw dekóder
    private val controlsPokeState = androidx.compose.runtime.mutableStateOf(0)
    private val isPlayingState = androidx.compose.runtime.mutableStateOf(true)
    // D-pad navigacia zoznamu kanalov v prehravaci
    private val openChannelListState = androidx.compose.runtime.mutableStateOf(0)
    private val navChannelIndexState = androidx.compose.runtime.mutableStateOf(0)
    // M369: aktivny filter skupiny v zozname kanalov + priznak, ci je fokus na pilulke skupiny.
    private val activeGroupLabelState = androidx.compose.runtime.mutableStateOf("")
    private val groupPickerState = androidx.compose.runtime.mutableStateOf(false)
    // M370: hladanie kanala podla nazvu v zozname kanalov (TV: systemova klavesnica).
    private val searchActiveState = androidx.compose.runtime.mutableStateOf(false)
    private val searchQueryState = androidx.compose.runtime.mutableStateOf("")
    private val searchFieldFocusedState = androidx.compose.runtime.mutableStateOf(true)
    private val searchNavIndexState = androidx.compose.runtime.mutableStateOf(0)
    private val searchFocusSignalState = androidx.compose.runtime.mutableStateOf(0)
    private var seekablePlayback = false
    private var currentStreamUrl: String? = null
    // Zadavanie kanala cislami z dialkoveho ovladaca
    private val numEntryState = androidx.compose.runtime.mutableStateOf("")
    private var numEntry = ""
    private var numJob: kotlinx.coroutines.Job? = null

    /** Vytvori Media s HW/SW dekoderom podla preferencie (lacne boxy = SW). */
    private fun userAgent(): String {
        val v = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        return "HeadentClient/$v"
    }

    /** Odstrani user:pass@ z URL (pre feeder/probe — auth riesi OkHttp hlavickou). */
    private fun stripCreds(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return url
        val rest = url.substring(i + 3)
        val at = rest.indexOf('@')
        val slash = rest.indexOf('/')
        if (at < 0 || (slash in 0 until at)) return url
        return url.substring(0, i + 3) + rest.substring(at + 1)
    }

    private fun buildMedia(url: String): Media {
        val m = Media(libVlc, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        // User-Agent: nech server vidi, ze sa pripaja HeadentClient
        m.addOption(":http-user-agent=" + userAgent())
        applyDeinterlace(m)
        return m
    }

    /** Rezim deinterlacingu z nastaveni -> (hodnota --deinterlace, mod alebo null).
     *  -1 = automaticky (deinterlacuje len prekladany zdroj), 0 = vypnute, 1 = zapnute. */
    private fun deinterlaceSpec(): Pair<String, String?> = when (DeinterlacePref.get(this)) {
        DeinterlacePref.OFF -> "0" to null
        DeinterlacePref.BOB -> "1" to "bob"
        DeinterlacePref.YADIF -> "1" to "yadif"
        DeinterlacePref.YADIF2X -> "1" to "yadif2x"
        DeinterlacePref.X -> "1" to "x"
        else -> "-1" to "yadif"   // AUTO
    }

    /** Aplikuje deinterlacing na dane medium (riesi hrebenove pasy / combing pri
     *  prekladanom DVB videu na rychlych zaberoch). */
    private fun applyDeinterlace(m: Media) {
        val (en, mode) = deinterlaceSpec()
        m.addOption(":deinterlace=$en")
        if (mode != null) m.addOption(":deinterlace-mode=$mode")
    }

    /**
     * M381: demuxer pre feeder cestu. Feeder posiela bajty cez fd, takze libVLC
     * nema nazov suboru ani MIME a kontajner musi uhadnut. Pri MPEG-TS profiloch
     * (pass, htsp, *-mpegts) mu ho dame natvrdo — TS sa chyta aj uprostred toku
     * a probing tam byva pomaly. Pri ostatnych (matroska, webm, mp4) natvrdo ts
     * znamenalo, ze sa stream vobec neotvoril; tam necháme VLC probing (EBML /
     * ftyp hlavicka je na zaciatku toku, takze sa kontajner urci spolahlivo).
     */
    private fun applyFeederDemux(media: Media, url: String) {
        val prof = Regex("[?&]profile=([^&]*)")
            .find(url)?.groupValues?.get(1)?.lowercase().orEmpty()
        val isTs = prof.isEmpty() || prof == "pass" || prof == "htsp" || prof.endsWith("mpegts")
        if (isTs) media.addOption(":demux=ts")
    }

    /** M255 — live cez HTTP na digest-only serveri: stiahnut cez feeder (rovnako
     *  ako DVR), lebo libVLC digest cez URL nezvlada. Pre live netreba seek. */
    private fun playLiveViaFeeder(server: sk.tvhclient.shared.model.TvhServer, url: String) {
        htspFeeder?.stop(); htspFeeder = null
        httpFeeder?.stop()
        htspStream = false
        htspLive = false
        htspLiveState.value = false
        resetTimeshift()
        currentStreamUrl = url
        val feeder = HttpTsFeeder(server, stripCreds(url), 0L)
        httpFeeder = feeder
        val fd = feeder.start(lifecycleScope)
        val media = Media(libVlc, fd)
        media.setHWDecoderEnabled(true, false)
        applyFeederDemux(media, url)
        media.addOption(":file-caching=1500")
        applyDeinterlace(media)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    /** Cache: vyzaduje live HTTP na tomto serveri feeder (digest-only)? */
    private var liveNeedsFeeder: Boolean? = null

    /** Live HTTP s auto-detekciou auth: digest-only -> feeder, inak priama cesta. */
    private fun playLiveAuto(server: sk.tvhclient.shared.model.TvhServer, url: String) {
        if (server.username.isEmpty()) { playHttp(url); return }
        val cached = liveNeedsFeeder
        if (cached != null) {
            if (cached) playLiveViaFeeder(server, url) else playHttp(url)
            return
        }
        lifecycleScope.launch {
            val nf = withContext(Dispatchers.IO) { DvrAuthProbe.needsFeeder(server, stripCreds(url)) }
            liveNeedsFeeder = nf
            if (nf) playLiveViaFeeder(server, url) else playHttp(url)
        }
    }

    /** Bezne HTTP prehravanie (zastavi pripadny HTSP feed). */
    private fun playHttp(url: String) {
        htspFeeder?.stop(); htspFeeder = null
        httpFeeder?.stop(); httpFeeder = null
        htspStream = false
        htspLive = false
        htspLiveState.value = false
        resetTimeshift()
        currentStreamUrl = url
        val media = buildMedia(url)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    /**
     * M253 — DVR/archiv cez HttpTsFeeder: appka stiahne dvrfile s digest auth
     * (OkHttp + DigestAuthenticator) a podava libVLC cez pipe. Rovny princip ako
     * HTSP live; rieši digest-only servery kde creds v URL (user:pass@host)
     * libVLC nezvladne. startByte = pripadny offset pre resume cez HTTP Range.
     */
    private fun playDvrViaFeeder(server: sk.tvhclient.shared.model.TvhServer, url: String, startByte: Long = 0L) {
        htspFeeder?.stop(); htspFeeder = null
        httpFeeder?.stop()
        htspStream = false
        htspLive = false
        htspLiveState.value = false
        resetTimeshift()
        currentStreamUrl = url
        val feeder = HttpTsFeeder(server, stripCreds(url), startByte)
        httpFeeder = feeder
        val fd = feeder.start(lifecycleScope)
        val media = Media(libVlc, fd)
        media.setHWDecoderEnabled(true, false)
        media.addOption(":demux=ts")
        media.addOption(":file-caching=1500")
        applyDeinterlace(media)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    /**
     * M162 — zivy kanal cez HTSP (premuxovany na MPEG-TS, podavany libVLC cez pipe).
     * Vracia true ak sa podarilo spustit. Pouzite len ak je timeshift zapnuty a server
     * ho podporuje; inak ostava HTTP cesta.
     */
    private fun playHtspLive(server: sk.tvhclient.shared.model.TvhServer, channelId: Long, timeshift: Boolean): Boolean {
        return try {
            htspFeeder?.stop()
            httpFeeder?.stop(); httpFeeder = null
            val feeder = HtspTsFeeder(server, if (timeshift) 3600 else 0)
            htspFeeder = feeder
            // vlastne titulky: dekódovanu stranku posli do overlay-u (synchronizuje sa na cas)
            feeder.onSubtitlePage = { page, ms -> subOverlay?.onPage(page, ms) }
            subOverlay?.reset()
            // novy kanal = novy zoznam titulkov, vynuluj zvoleny jazyk
            selectedSubEsState.value = -1
            desiredSubName = null
            resetTimeshift()
            val fd = feeder.start(channelId, lifecycleScope)
            val media = Media(libVlc, fd)
            media.setHWDecoderEnabled(true, false)
            media.addOption(":demux=ts")
            media.addOption(":file-caching=1500")
            applyDeinterlace(media)
            mediaPlayer.media = media
            media.release()
            mediaPlayer.play()
            true
        } catch (e: Throwable) {
            htspFeeder?.stop()
            htspFeeder = null
            false
        }
    }

    private fun pokeControls() {
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
            // zapamataj aktualny zivy kanal, nech BACK z EPG vrati do prehravaca nan
            if (!seekablePlayback) liveUuids.getOrNull(liveIndex)?.let { putExtra("epg_return_uuid", it) }
        }
        runCatching { startActivity(i) }
    }

    private fun openEpgInApp() {
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
        val order = playerControlOrder(!seekablePlayback && liveUuids.size > 1, seekablePlayback, pipButtonVisible(), timeshiftEngagedState.value, profileSwitchAvailable())
        controlNavState.value = order.indexOf("play").coerceAtLeast(0)
        pokeControls()
    }

    private fun togglePlayPause() {
        if (!::mediaPlayer.isInitialized) return
        skipFlushJob?.cancel(); flushSkip()   // doruc nazbierany skok, nech je server konzistentny
        if (isPlayingState.value) {
            if (htspStream) htspFeeder?.pause()         // zastav HTSP delivery (aj bez timeshiftu)
            if (htspLive) {
                // prva pauza "zapne" timeshift: odtialto sa rata buffer aj cervene pocitadlo
                if (htspStartedAt <= 0L) htspStartedAt = System.currentTimeMillis()
                timeshiftEngagedState.value = true
                // zapnutim timeshiftu pribudnu ovladace pretacania (tsrew pred play) a posunu sa
                // indexy — re-ukotvi fokus na play/pause, nech "neskoci" na pretacanie
                val ord = playerControlOrder(!seekablePlayback && liveUuids.size > 1, seekablePlayback, pipButtonVisible(), true, profileSwitchAvailable())
                controlNavState.value = ord.indexOf("play").coerceAtLeast(0)
                tsPauseStartedAt = System.currentTimeMillis()
                startTimeshiftTicker()
            }
            isPlayingState.value = false
            mediaPlayer.pause()
        } else {
            if (htspStream) htspFeeder?.resume()
            if (htspLive) {
                if (tsPauseStartedAt > 0L) {
                    tsAccumMs += System.currentTimeMillis() - tsPauseStartedAt
                    tsPauseStartedAt = 0L
                }
                stopTimeshiftTicker()
                timeshiftOffsetState.value = tsAccumMs
            }
            isPlayingState.value = true
            dvrReopenAttempts = 0   // manualny play -> povol nove pokusy o nacitanie novsich dat
            mediaPlayer.play()
        }
    }

    /** Pocas pauzy rastie posun za zivym (1 s/s); aktualizuje ukazovatel kazdu sekundu. */
    private fun startTimeshiftTicker() {
        timeshiftTickerJob?.cancel()
        timeshiftTickerJob = lifecycleScope.launch {
            while (true) {
                val extra = if (tsPauseStartedAt > 0L) System.currentTimeMillis() - tsPauseStartedAt else 0L
                timeshiftOffsetState.value = tsAccumMs + extra
                kotlinx.coroutines.delay(1000)
            }
        }
    }

    private fun stopTimeshiftTicker() {
        timeshiftTickerJob?.cancel()
        timeshiftTickerJob = null
    }

    /** Novy zivy zaciatok (cerstva subscription = na zivo) -> vynuluj timeshift. */
    private fun resetTimeshift() {
        stopTimeshiftTicker()
        skipFlushJob?.cancel(); skipFlushJob = null
        seekSpinnerJob?.cancel(); seekingState.value = false
        seekHintJob?.cancel(); seekHintState.value = 0
        pendingSkipMs = 0L
        tsAccumMs = 0L
        tsPauseStartedAt = 0L
        htspStartedAt = 0L   // timeshift sa "zapne" az prvou pauzou (Tvheadend pred tym nema buffer)
        timeshiftEngagedState.value = false
        timeshiftOffsetState.value = 0L
    }

    /** Reálna hĺbka bufferu = čas od otvorenia kanála, najviac timeshiftPeriod (3600 s). */
    private fun maxRewindMs(): Long {
        if (htspStartedAt <= 0L) return 0L
        val elapsed = System.currentTimeMillis() - htspStartedAt
        return elapsed.coerceAtMost(3600_000L)
    }

    /** Relativny skok v timeshifte (sekundy; zaporne = vzad). Aktualizuje aj ukazovatel. */
    private fun timeshiftSkip(seconds: Int) {
        if (!htspLive) return
        // ak je pauza, po skoku spusti prehravanie (nech vidno vysledok skoku)
        if (tsPauseStartedAt > 0L) {
            val now = System.currentTimeMillis()
            tsAccumMs += now - tsPauseStartedAt
            tsPauseStartedAt = 0L
            stopTimeshiftTicker()
            htspFeeder?.resume()
            isPlayingState.value = true
            if (::mediaPlayer.isInitialized && !mediaPlayer.isPlaying) mediaPlayer.play()
        }
        // cielova pozicia za zivym, orezana na <0 .. hlbka bufferu>
        val target = (tsAccumMs - seconds.toLong() * 1000L).coerceIn(0L, maxRewindMs())
        val deltaMs = target - tsAccumMs
        if (deltaMs == 0L) return                   // niet kam (zaciatok bufferu alebo zive)
        tsAccumMs = target
        timeshiftOffsetState.value = tsAccumMs
        // ukazovatel reaguje hned, ale realny skok posli az ked prestane tukanie —
        // viac skokov za sebou inak nuti libVLC stale resynchronizovat (trha to)
        pendingSkipMs += deltaMs
        skipFlushJob?.cancel()
        skipFlushJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(350)
            flushSkip()
        }
    }

    /** Posle nazbierany skok jedným relativnym subscriptionSkip. Na zive sa vracia
     *  skokom dopredu (NIE subscriptionLive, ktory padal, ani restartom, ktory by
     *  vynuloval buffer) — tak ostava ta ista subscription aj buffer a da sa pretacat aj potom. */
    private fun flushSkip() {
        val net = pendingSkipMs
        pendingSkipMs = 0L
        if (net != 0L) {
            htspFeeder?.skip((-net / 1000L).toInt())   // dozadu => zaporne, dopredu => kladne
            // koliesko v strede pocas resyncu; zhasne ho Playing/Buffering event,
            // poistka ho zhasne aj keby event neprisiel
            seekingState.value = true
            seekSpinnerJob?.cancel()
            seekSpinnerJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(4000)
                seekingState.value = false
            }
        }
    }


    /** Pretacanie pre DVR (live TS sa pretacat neda). TS subor nenese dlzku,
     *  preto pouzivame dlzku z DVR entry a poziciu ako zlomok (na TS spolahlive). */
    /** Absolutny seek na program-relativny cas (spodna lista / D-pad). Cez seekDvrTo,
     *  takze funguje aj pre feeder/pipe (player.position tam nic nerobi). */
    private fun seekDvrAbsolute(targetMs: Long) {
        if (!::mediaPlayer.isInitialized || !seekablePlayback) return
        val dur = if (dvrDurationMs > 0) dvrDurationMs else mediaPlayer.length
        if (dur <= 0) return
        val maxMs = if (dvrRecording) (dur - 45_000L).coerceAtLeast(0L) else dur
        val curMs = dvrPlayheadMsState.value.coerceIn(0L, dur)
        val tgt = targetMs.coerceIn(0L, maxMs)
        if (kotlin.math.abs(tgt - curMs) < 1000L) return
        seekDvrTo(tgt, curMs, dur)
    }

    private fun seekRelative(deltaMs: Long) {
        if (!::mediaPlayer.isInitialized || !seekablePlayback) return
        val dur = if (dvrDurationMs > 0) dvrDurationMs else mediaPlayer.length
        if (dur <= 0) return
        // Pri prebiehajucej nahravke nechaj rezervu ~45 s od zivej hrany (zapisane data
        // zaostavaju za EPG casom; mensia rezerva = EOF a zamrznutie TS).
        val maxMs = if (dvrRecording) (dur - 45_000L).coerceAtLeast(0L) else dur
        // Aktualna pozicia = nas playhead (spolahlivy pre obe cesty; player.position je
        // pre rastuci TS aj pre pipe nestabilna).
        val curMs = dvrPlayheadMsState.value.coerceIn(0L, dur)
        val targetMs = (curMs + deltaMs).coerceIn(0L, maxMs)
        if (kotlin.math.abs(targetMs - curMs) < 1000L) return
        seekDvrTo(targetMs, curMs, dur)
    }

    /** Pretoc DVR nahravku na cielovy program-relativny cas PREBUDOVANIM streamu.
     *  Priame URL -> nova Media s :start-time (libVLC seekuje cez HTTP Range).
     *  Feeder/pipe -> restart HTTP feedu na odhadnutom byte-offsete (pipe sa neseekuje).
     *  V oboch pripadoch naseeduje playhead hodiny na cielovy cas. */
    private fun seekDvrTo(targetMs: Long, fromMs: Long, dur: Long) {
        val url = currentStreamUrl ?: return
        if (!::mediaPlayer.isInitialized) return
        val offsetMs = if (dvrProgStartSec > 0 && dvrRealStartSec in 1 until dvrProgStartSec)
            (dvrProgStartSec - dvrRealStartSec) * 1000 else 0L
        val fileMs = (offsetMs + targetMs).coerceAtLeast(0L)   // cas v subore (0 = realny zaciatok nahravky)
        reconnectHandler.removeCallbacksAndMessages(null)
        dvrReopenAttempts = 0
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
                val m = buildMedia(url)
                m.addOption(":start-time=${fileMs / 1000}")
                mediaPlayer.media = m
                m.release()
                mediaPlayer.play()
            }
        }
        // playhead hned na cielovu poziciu + seed pre hodiny (po restarte je player.position
        // neplatna, hodiny ju nesmu citat - prevezmu seed a tikaju dalej z neho)
        dvrPlayheadMsState.value = targetMs
        dvrSeekSeedState.value = targetMs
    }

    /** Dvojklik na lavu/pravu stranu (YouTube-style): skok o 10 s.
     *  DVR -> seek v medii; aktivny timeshift -> subscriptionSkip. Hint sa akumuluje. */
    private fun doubleTapSeek(forward: Boolean) {
        val step = if (forward) 10 else -10
        when {
            seekablePlayback -> {
                // zafixuj vychodzi playhead na zaciatku serie klikov (dalsie kliky len pridavaju)
                if (seekAccumBaseMs < 0L) seekAccumBaseMs = dvrPlayheadMsState.value
            }
            htspLive -> {
                if (maxRewindMs() <= 0L) return   // timeshift sa zapne az pauzou, dovtedy niet co pretacat
                timeshiftSkip(step)               // live timeshift: lacne, pretoc hned
            }
            else -> return   // ziadne pretacanie (zive bez timeshiftu) -> ignoruj
        }
        val cur = seekHintState.value
        val acc = if (cur != 0 && (cur > 0) == forward) cur + step else step
        seekHintState.value = acc
        seekHintJob?.cancel()
        seekHintJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(800)
            seekHintState.value = 0
        }
        // DVR: pretoc az ~0,5 s po poslednom kliku na akumulovany sucet (1 restart namiesto N)
        if (seekablePlayback) {
            seekCommitJob?.cancel()
            seekCommitJob = lifecycleScope.launch {
                kotlinx.coroutines.delay(450)
                val target = seekAccumBaseMs + acc * 1000L
                seekAccumBaseMs = -1L
                seekHintJob?.cancel()
                seekHintState.value = 0
                seekDvrAbsolute(target)
            }
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

    /** Obnovi now/next pre vsetky kanaly v zozname (kym je otvoreny). */
    private suspend fun refreshOverlayEpg() {
        val srv = liveServer ?: return
        val cur = liveChannelsState.value
        if (cur.isEmpty()) return
        val nowS = System.currentTimeMillis() / 1000
        try {
            // prebiehajuce nahravky -> ktore kanaly sa prave nahravaju (cervena bodka/kazeta + vyber archiv)
            val recList: List<sk.tvhclient.shared.model.DvrEntry> =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try { Tvh.fetchDvrInProgress(srv, api) }
                    catch (e: Exception) { emptyList() }
                    finally { api.close() }
                }
            val recMap = recList.associateBy { it.channelUuid.ifBlank { it.channelName } }
            recInProgressByChan.value = recMap
            if (srv.connectionMode == "htsp") {
                val map = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Tvh.fetchEpgUpcoming(srv)
                }
                if (map.isNotEmpty()) epgUpcomingState.value = map
                val enrichHtsp: (LivePlaylist.LiveChannel) -> LivePlaylist.LiveChannel = { ch ->
                    val ev = map[ch.uuid]?.firstOrNull { it.start <= nowS && nowS < it.stop }
                    val b = if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
                    b.copy(recording = (b.uuid in recMap || b.name in recMap))
                }
                val updated = cur.map(enrichHtsp)
                liveChannelsState.value = updated
                LivePlaylist.channels = updated
                // M370-fix3: obohat aj cely zoznam, nech prepnutie tagu nestrati EPG v zozname
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map(enrichHtsp)
            } else {
                // HTTP: now/next je v dumpe kanalov -> nacitaj nanovo
                val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try {
                        val repo = Tvh.channelRepository(srv, api)
                        repo.load(true)
                        repo.allRows(false).associateBy { it.channel.uuid }
                    } finally {
                        api.close()
                    }
                }
                val enrichHttp: (LivePlaylist.LiveChannel) -> LivePlaylist.LiveChannel = { ch ->
                    val r = rows[ch.uuid]
                    val b = if (r != null) ch.copy(
                        nowTitle = r.nowTitle ?: "",
                        nowStart = r.nowStart,
                        nowStop = r.nowStop
                    ) else ch
                    b.copy(recording = (b.uuid in recMap || b.name in recMap))
                }
                val updated = cur.map(enrichHttp)
                liveChannelsState.value = updated
                LivePlaylist.channels = updated
                // M370-fix3: obohat aj cely zoznam, nech prepnutie tagu nestrati EPG v zozname
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map(enrichHttp)
            }
            epgLastOkMs = System.currentTimeMillis()
            // M271: zapis do procesovej cache, nech reopen prehravaca nesťahuje znova
            LivePlaylist.epgLastOkMs = epgLastOkMs
            LivePlaylist.epgUpcoming = epgUpcomingState.value
            persistEpg(epgUpcomingState.value)   // M275: na disk, nech prezije restart boxu
        } catch (e: Exception) {
        }
    }

    /** M270: EPG je zastarane (treba spinner pri prvom nacitani) ak este nikdy nebezalo,
     *  je starsie nez 3 h (dlho vypnuty box), alebo je cache prazdna v HTSP rezime. */
    private fun epgIsStale(): Boolean {
        if (epgLastOkMs == 0L) return true
        if (System.currentTimeMillis() - epgLastOkMs > 3L * 60 * 60 * 1000) return true
        return liveServer?.connectionMode == "htsp" && epgUpcomingState.value.isEmpty()
    }

    /** M270: prve nacitanie EPG po otvoreni zoznamu. Spinner ukaze LEN ak je cache
     *  prazdna/zastarana a nacitanie trva dlhsie nez prah (350 ms) — pri rychlom serveri
     *  ani pri prepinani/periodickom refreshe sa neobjavi. */
    private fun refreshOverlayEpgInitial() {
        lifecycleScope.launch {
            // M271: ak mame cerstve EPG (cache z nedavneho otvorenia), nesťahuj znova —
            // odpadne otravne nacitavanie pri kazdom reopene. Fetch len ked je stale.
            if (!epgIsStale()) {
                // M281: EPG je cerstve (now/next uz mame z cache), ale nahravaci priznak
                // (cervena bodka) sa meni casto — osvez len ten malym DVR dotazom, bez EPG.
                refreshRecordingOnly()
                return@launch
            }
            val spinJob = launch {
                kotlinx.coroutines.delay(350)
                epgLoadingState.value = true
            }
            refreshOverlayEpg()
            spinJob.cancel()
            epgLoadingState.value = false
        }
    }

    /** M281: rychle osvezenie len nahravacich priznakov (cervena bodka) bez EPG fetchu.
     *  Pouzite pri reopene s cerstvym EPG — now/next uz mame z cache, ale prebiehajuce
     *  nahravky sa medzicasom mohli zmenit. Jeden maly DVR dotaz, ziadny EPG churn. */
    private fun refreshRecordingOnly() {
        val srv = liveServer ?: return
        lifecycleScope.launch {
            val recList: List<sk.tvhclient.shared.model.DvrEntry> =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try { Tvh.fetchDvrInProgress(srv, api) }
                    catch (e: Exception) { emptyList() }
                    finally { api.close() }
                }
            val recMap = recList.associateBy { it.channelUuid.ifBlank { it.channelName } }
            recInProgressByChan.value = recMap
            val cur = liveChannelsState.value
            if (cur.isNotEmpty()) {
                val updated = cur.map { it.copy(recording = (it.uuid in recMap || it.name in recMap)) }
                liveChannelsState.value = updated
                LivePlaylist.channels = updated
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map {
                        it.copy(recording = (it.uuid in recMap || it.name in recMap))
                    }
            }
        }
    }

    /** M274: ulozenie per-kanaloveho EPG do cache (a do procesovej cache LivePlaylist),
     *  aby opatovne zobrazenie toho kanala — aj po zatvoreni/otvoreni prehravaca — bolo
     *  okamzite z cache, nie zo siete. Funguje aj v HTTP rezime, kde bulk mapa chyba. */
    private fun cacheChannelEpg(uuid: String, list: List<sk.tvhclient.shared.model.EpgEvent>) {
        if (list.isEmpty()) return
        val m = epgUpcomingState.value.toMutableMap()
        m[uuid] = list
        epgUpcomingState.value = m
        LivePlaylist.epgUpcoming = m
        if (epgLastOkMs == 0L) {
            epgLastOkMs = System.currentTimeMillis()
            LivePlaylist.epgLastOkMs = epgLastOkMs
        }
        persistEpg(m)   // M275: zapis na disk, nech prezije restart boxu
    }

    /** M275: nacitanie EPG z disku do procesovej cache pri starte (ak je process cache
     *  prazdna — napr. po restarte boxu/appky). Zobrazi now/next okamzite; cerstvost
     *  riesi epgIsStale (>3h -> refresh na pozadi). */
    private fun hydrateEpgFromDisk(srv: sk.tvhclient.shared.model.TvhServer) {
        if (LivePlaylist.epgUpcoming.isNotEmpty()) {
            // M281: proces cache prezila (Activity recreate) — synchronizuj Activity stav,
            // aby applyCachedEpgToChannels() vedel hned naplnit zoznam.
            if (epgUpcomingState.value.isEmpty()) epgUpcomingState.value = LivePlaylist.epgUpcoming
            if (epgLastOkMs == 0L) epgLastOkMs = LivePlaylist.epgLastOkMs
            return
        }
        try {
            val nowSec = System.currentTimeMillis() / 1000
            val daysBack = EpgRangePref.daysBack(this)
            val disk = EpgCache.loadLive(this, srv.id, nowSec, daysBack)
            if (disk.isNotEmpty()) {
                epgUpcomingState.value = disk
                LivePlaylist.epgUpcoming = disk
                val ts = EpgCache.lastSavedLive(this, srv.id)
                epgLastOkMs = ts
                LivePlaylist.epgLastOkMs = ts
            }
        } catch (e: Exception) {
        }
    }

    /** M281: aplikuj nacachovane now/next (z disku/procesu cez epgUpcomingState) na viditelny
     *  zoznam kanalov, aby sa nazvy relacii pod kanalmi zobrazili OKAMZITE aj po restarte/reopene
     *  — bez cakania na sietovy refreshOverlayEpg. Nahravaci priznak (cervena bodka) sa doplni
     *  az ked dobehne fetchDvrInProgress (recInProgressByChan); tu sa neprepisuje, ak je prazdny. */
    private fun applyCachedEpgToChannels() {
        val map = epgUpcomingState.value
        if (map.isEmpty()) return
        val cur = liveChannelsState.value
        if (cur.isEmpty()) return
        val nowS = System.currentTimeMillis() / 1000
        val recMap = recInProgressByChan.value
        val updated = cur.map { ch ->
            val ev = map[ch.uuid]?.firstOrNull { it.start <= nowS && nowS < it.stop }
            val b = if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
            if (recMap.isEmpty()) b else b.copy(recording = (b.uuid in recMap || b.name in recMap))
        }
        liveChannelsState.value = updated
        LivePlaylist.channels = updated
    }

    /** M275: asynchronny zapis EPG cache na disk (per server). */
    private fun persistEpg(map: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>) {
        val srv = liveServer ?: return
        if (map.isEmpty()) return
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val nowSec = System.currentTimeMillis() / 1000
                EpgCache.saveLive(this@PlayerActivity, srv.id, map, nowSec, EpgRangePref.daysBack(this@PlayerActivity))
            }
        }
    }

    /** M274: prefetch EPG na pozadi LEN ak je cache prazdna/zastarana (prvy start, >3h).
     *  Pri reopene s cerstvou cache sa nerobi zbytocny refresh (ziadny lag/churn). */
    private fun prefetchEpgIfStale() {
        if (!epgIsStale()) return
        lifecycleScope.launch { refreshOverlayEpg() }
    }

    /** TV/box (Android TV) — na detekciu kde sa ma archivny vyber zobrazovat. */
    private fun isTvDevice(): Boolean {
        val um = getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        return um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }

    /** Vyber kanala zo zoznamu: ak sa archivuje, ponukni nazivo/od zaciatku, inak prepni. */
    private fun selectChannelOrArchive(idx: Int, poke: Boolean = true) {
        val ch = liveChannelsState.value.getOrNull(idx)
        val rec = ch?.let { c -> recInProgressByChan.value.let { it[c.uuid] ?: it[c.name] } }
        if (rec != null && isTvDevice() && ArchiveChoicePref.get(this)) {
            archiveChoiceSelState.value = 0
            archiveChoiceIdxState.value = idx
            closeChannelList()
        } else if (idx != liveIndex) switchToIndex(idx, poke) else pokeControls()
    }

    /** Vyriesi vyber pri archivovanom kanali: nazivo (prepne) alebo od zaciatku (spusti nahravku). */
    private fun resolveArchiveChoice(fromStart: Boolean) {
        val idx = archiveChoiceIdxState.value
        archiveChoiceIdxState.value = -1
        if (idx < 0) return
        val ch = liveChannelsState.value.getOrNull(idx) ?: LivePlaylist.channels.getOrNull(idx) ?: return
        if (!fromStart) {
            if (idx != liveIndex) switchToIndex(idx) else pokeControls()
            return
        }
        val rec = recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] }
        if (rec == null) {
            if (idx != liveIndex) switchToIndex(idx)
            return
        }
        playRecordingFromStart(rec, ch.nowStart, ch.nowStop)
    }

    /** Zatvorenie prehravaca: ak bol spusteny cez "od zaciatku" zo zivej TV, vrat sa na povodny kanal. */
    /** M342/M344: BACK z hrajuceho radia v modernom = handoff do RadioPlayerService.
     *  Vrati true, ak handoff prebehol (aktivita sa ukoncila) — radio hra dalej
     *  na pozadi s mini listou. Plati pre telefon aj TV; klasik povodne. */
    private fun radioHandoffIfPossible(): Boolean {
        if (playKind != "radio" || UiModePref.get(this) != UiModePref.MODERN) return false
        val uuid = liveUuids.getOrNull(liveIndexState.value) ?: return false
        val server = liveServer ?: sk.tvhclient.shared.Tvh.store.active() ?: return false
        if (!::mediaPlayer.isInitialized || !mediaPlayer.isPlaying) return false
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

    /** Spusti prebiehajucu nahravku od zaciatku (novy PlayerActivity v DVR rezime). */
    private fun playRecordingFromStart(rec: sk.tvhclient.shared.model.DvrEntry, progStart: Long, progStop: Long) {
        val srv = liveServer ?: return
        val url = Tvh.dvrUrl(srv, rec.uuid)
        val pStart = if (progStart > 0) progStart else rec.start
        val pStop = if (progStop > progStart && progStop > 0) progStop else rec.stop
        val nowSec = System.currentTimeMillis() / 1000
        val inProgress = pStart > 0 && nowSec < pStop
        val i = android.content.Intent(this, PlayerActivity::class.java).apply {
            putExtra(EXTRA_URL, url)
            putExtra(EXTRA_TITLE, rec.title)
            putExtra(EXTRA_DURATION_MS, rec.durationSec * 1000)
            putExtra(EXTRA_DVR_UUID, rec.uuid)
            putExtra(EXTRA_DVR_RECORDING, inProgress)
            putExtra(EXTRA_DVR_PROG_START_SEC, pStart)
            putExtra(EXTRA_DVR_PROG_STOP_SEC, pStop)
            putExtra(EXTRA_DVR_REAL_START_SEC, rec.realStartSec)
            // odkial sme prisli (zivy kanal) -> navrat sem po Spat
            liveUuids.getOrNull(liveIndex)?.let { putExtra(EXTRA_RETURN_UUID, it) }
            putExtra(EXTRA_RETURN_TITLE, liveNames.getOrElse(liveIndex) { "" })
        }
        runCatching { startActivity(i) }
    }

    /** Prepne na konkretny kanal podla indexu, prebuduje URL a nacita. */
    private fun saveLastLive(serverId: String?, uuid: String?) {
        if (serverId == null || uuid == null) return
        if (playKind == "radio") LastRadio.set(this, serverId, uuid) else LastChannel.set(this, serverId, uuid)
    }

    private fun switchToIndex(i: Int, poke: Boolean = true) {
        if (i < 0 || i >= liveUuids.size) return
        if (i == liveIndex) { if (poke) pokeControls(); return }  // ten isty kanal -> nenacitavaj znova
        val srv = liveServer ?: return
        val uuid = liveUuids[i]
        // rodicovsky zamok: zamknuty kanal mimo 5-min okna -> vypytaj PIN
        if (ParentalLock.channelNeedsPin(this, srv.id, uuid)) {
            requestPin(onOk = { switchToIndex(i, poke) }, onCancel = { }, channelIndex = i)
            return
        }
        liveIndex = i
        liveIndexState.value = i
        val name = liveNames.getOrElse(i) { "" }
        liveTitleState.value = name
        liveUuidState.value = uuid
        saveLastLive(srv.id, uuid)
        // novy kanal = neznama relacia; skry progress bar starej relacie
        val ch = LivePlaylist.channels.getOrNull(i)
        liveProgStartState.value = ch?.nowStart ?: 0L
        liveProgStopState.value = ch?.nowStop ?: 0L
        liveProgTitleState.value = ch?.nowTitle ?: ""
        liveNextTitleState.value = ch?.nextTitle ?: ""
        liveNextStartState.value = ch?.nextStart ?: 0L
        liveNextStopState.value = ch?.nextStop ?: 0L
        zapPokeState.value = zapPokeState.value + 1
        // M383: profil je jednotny pre cely server (per-kanal override zruseny)
        val prof = srv.profile.ifBlank { "pass" }
        val url = Tvh.liveUrl(srv, uuid, name, prof)
        currentStreamUrl = url
        cancelReconnect()  // nove pripojenie -> zrus stare pokusy
        trackReparseDone = false  // novy kanal -> povol jednorazovy re-parse stop
        trackReparseHandler.removeCallbacksAndMessages(null)
        hasVideoState.value = true  // predpokladaj video; kontrola po Playing to opravi
        val cid = uuid.toLongOrNull()
        // M262: ak HTSP rezim este nebol urceny (prepnutie pred doPlay, napr. odchod
        // z PIN vyzvy zamknuteho startovacieho kanala), urci ho tu rovnako ako doPlay,
        // aby aj prvy prepnuty kanal mal HTSP/timeshift a nie len HTTP.
        if (srv.connectionMode == "htsp" && cid != null && !htspInitDone) {
            htspInitDone = true
            lifecycleScope.launch {
                val ts = TimeshiftPref.get(this@PlayerActivity) && withContext(Dispatchers.IO) {
                    runCatching {
                        HtspData.timeshiftAvailable(srv, System.currentTimeMillis() / 1000)
                    }.getOrDefault(false)
                }
                if (playHtspLive(srv, cid, ts)) {
                    htspStream = true; htspLive = ts; htspLiveState.value = ts
                } else {
                    htspStream = false; htspLive = false; htspLiveState.value = false
                    playLiveAuto(srv, url)
                }
                if (poke) pokeControls()
            }
            return
        }
        if (htspStream && cid != null && playHtspLive(srv, cid, htspLive)) {
            if (poke) pokeControls()
            return
        }
        playLiveAuto(srv, url)
        if (poke) pokeControls()
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

    private fun switchLive(delta: Int) {
        hapticChannelSwitch()
        postZapResyncKick()
        if (liveUuids.size < 2 || liveIndex < 0) return
        val n = liveUuids.size
        switchToIndex(((liveIndex + delta) % n + n) % n)
    }

    // ===== M369: filter skupin v zozname kanalov (a tym aj CH+/-) =====
    private fun groupLabelFor(key: String): String = when (key) {
        LivePlaylist.GROUP_ALL -> getString(R.string.all_channels)
        LivePlaylist.GROUP_FAV -> getString(R.string.favorites)
        else -> LivePlaylist.groups.firstOrNull { it.key == key }?.label
            ?: getString(R.string.all_channels)
    }

    /** Poradie skupin pre cyklenie: Vsetky, Oblubene (ak su nejake), potom tagy. */
    private fun groupKeys(): List<String> {
        val keys = mutableListOf(LivePlaylist.GROUP_ALL)
        val srvId = (liveServer ?: Tvh.store.active())?.id
        val hasFav = srvId != null && Favorites.all(this, srvId).isNotEmpty()
        if (hasFav) keys.add(LivePlaylist.GROUP_FAV)
        LivePlaylist.groups.forEach { keys.add(it.key) }
        return keys
    }

    /** Prestavi live zoznam na zvolenu skupinu; CH+/-, karty aj zoznam potom idu v ramci nej. */
    private fun applyGroup(key: String) {
        val all = LivePlaylist.allChannels
        if (all.isEmpty()) return
        val srvId = (liveServer ?: Tvh.store.active())?.id
        val allow: Set<String>? = when (key) {
            LivePlaylist.GROUP_ALL -> null
            LivePlaylist.GROUP_FAV -> if (srvId != null) Favorites.all(this, srvId) else emptySet()
            else -> LivePlaylist.groups.firstOrNull { it.key == key }?.uuids ?: return
        }
        val filteredRaw = if (allow == null) all else all.filter { it.uuid in allow }
        if (filteredRaw.isEmpty()) return   // prazdna skupina -> necham stav
        // Dopln "teraz" z procesovej EPG cache — nech prepnutie tagu nikdy nestrati program,
        // aj keby allChannels este nebol obohateny.
        val nowSec = System.currentTimeMillis() / 1000
        val epg = epgUpcomingState.value
        val filtered = filteredRaw.map { ch ->
            if (ch.nowTitle.isNotBlank()) ch
            else {
                val ev = epg[ch.uuid]?.firstOrNull { it.start <= nowSec && nowSec < it.stop }
                if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
            }
        }
        LivePlaylist.activeGroupKey = key
        LivePlaylist.channels = filtered
        liveChannelsState.value = filtered
        liveUuids = filtered.map { it.uuid }
        liveNames = filtered.map { it.name }
        val ni = liveUuids.indexOf(liveUuidState.value)
        liveIndex = if (ni >= 0) ni else 0     // ak aktualny kanal nie je v skupine, CH+/- zacne od 0
        liveIndexState.value = liveIndex
        navChannelIndexState.value = liveIndex
        activeGroupLabelState.value = groupLabelFor(key)
    }

    /** Prepne na susednu skupinu (dir +1 / -1). */
    private fun cycleGroup(dir: Int) {
        val keys = groupKeys()
        if (keys.size < 2) return
        val cur = keys.indexOf(LivePlaylist.activeGroupKey).coerceAtLeast(0)
        val next = ((cur + dir) % keys.size + keys.size) % keys.size
        applyGroup(keys[next])
    }

    // ===== M370: hladanie kanala podla nazvu (napriec vsetkymi kanalmi) =====
    fun searchResults(): List<LivePlaylist.LiveChannel> {
        val q = searchQueryState.value.trim()
        if (q.isEmpty()) return emptyList()
        return LivePlaylist.allChannels.filter { it.name.contains(q, ignoreCase = true) }
    }
    private fun openSearch() {
        groupPickerState.value = false
        searchQueryState.value = ""
        searchNavIndexState.value = 0
        searchFieldFocusedState.value = true
        searchActiveState.value = true
        searchFocusSignalState.value = searchFocusSignalState.value + 1
        // Immersive okno prehravaca inak systemovu klavesnicu nepusti — vynutime ju.
        runCatching {
            window.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .show(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }
    private fun closeSearch() {
        searchActiveState.value = false
        searchFieldFocusedState.value = true
        searchQueryState.value = ""
        runCatching {
            androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
                .hide(androidx.core.view.WindowInsetsCompat.Type.ime())
        }
    }
    /** Vyber kanala z vysledkov hladania: prepne (aj skupinu ak treba) a pusti. */
    private fun selectLiveByUuid(uuid: String) {
        okLongFired = true   // prehltne nasledne OK-up, inak by zoznam potvrdil iny kanal (index 0)
        closeSearch()
        closeChannelList()
        var i = liveUuids.indexOf(uuid)
        if (i < 0) { applyGroup(LivePlaylist.GROUP_ALL); i = liveUuids.indexOf(uuid) }
        if (i >= 0) selectChannelOrArchive(i, poke = false)
    }

    /** M262 — prepnutie pocas zobrazenej PIN vyzvy: zrusi vyzvu zamknuteho kanala
     *  (bez ukoncenia prehravaca) a prepne na susedny relativne k blokovanemu kanalu.
     *  switchToIndex znova vyhodnoti zamok: volny kanal -> hra, dalsi zamknuty -> opat PIN. */
    private fun switchFromPin(fromIndex: Int, delta: Int) {
        val n = liveUuids.size
        if (n < 2) return
        closePin()
        switchToIndex(((fromIndex + delta) % n + n) % n)
    }

    /** Zadanie cisla kanala z dialkoveho: nazbieraj cislice, po 1,5 s sa prepne. */
    private fun onChannelDigit(d: Int) {
        if (liveUuids.isEmpty()) return
        numEntry = (numEntry + d).takeLast(4)
        numEntryState.value = numEntry
        numJob?.cancel()
        numJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(1500)
            commitChannelNumber()
        }
    }

    private fun commitChannelNumber() {
        val typed = numEntry.toIntOrNull()
        numEntry = ""
        numEntryState.value = ""
        if (typed == null) return
        val idx = LivePlaylist.channels.indexOfFirst { it.number == typed }
        if (idx in liveUuids.indices) { switchToIndex(idx); pokeControls() }
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
    private fun pipButtonVisible(): Boolean = pipSupported && !AutoPipPref.get(this)

    private val pipSupported: Boolean by lazy {
        android.os.Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }
    private var controlsShown = false
    private val openOptionsState = androidx.compose.runtime.mutableStateOf(0)
    private val closeOptionsState = androidx.compose.runtime.mutableStateOf(0)
    private val optionsNavState = androidx.compose.runtime.mutableStateOf(0)
    private val openAudioMenuState = androidx.compose.runtime.mutableStateOf(0)
    private val openSpuMenuState = androidx.compose.runtime.mutableStateOf(0)
    // M383: prepinac stream profilu v prehravaci (len HTTP live)
    private val openProfileMenuState = androidx.compose.runtime.mutableStateOf(0)
    private val profileItemsState = androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    private val currentProfileState = androidx.compose.runtime.mutableStateOf("")
    // M383-fix: dostupnost MUSI byt compose state — obycajna funkcia sa vyhodnoti
    // len pri prvej kompozicii (pred spustenim prehravania) a UI by sa o zmene
    // nikdy nedozvedelo; presne preto tlacidlo nebolo vidno
    private val profileSwitchState = androidx.compose.runtime.mutableStateOf(false)
    // Casovac uspatia
    private val sleepMinutesState = androidx.compose.runtime.mutableStateOf(0)
    private val sleepDeadlineState = androidx.compose.runtime.mutableStateOf(0L)
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepDurations = listOf(0, 15, 30, 45, 60, 90)
    // Navigacia ovladacieho panela (focus riadime z Activity, nie cez Compose focus)
    private val controlNavState = androidx.compose.runtime.mutableStateOf(0)
    // Navigacia track menu (audio/titulky)
    private val trackNavState = androidx.compose.runtime.mutableStateOf(0)
    // Verzia zoznamu stop — zvysi sa ked libVLC prida/ubere stopu (ESAdded/ESDeleted).
    // DVB titulky a viacjazycne audio sa objavia az par sekund po starte streamu;
    // toto vynuti obnovu otvoreneho track menu, nech sa stopy doplnia automaticky.
    private val trackListVersionState = androidx.compose.runtime.mutableStateOf(0)
    private val closeMenuState = androidx.compose.runtime.mutableStateOf(0)
    private var trackMenuKind = "audio"
    private var okLongFired = false

    // Rodicovsky zamok (PIN) — Activity-driven overlay
    private val pinPromptState = androidx.compose.runtime.mutableStateOf(false)
    private val pinEntryState = androidx.compose.runtime.mutableStateOf("")
    private val pinErrorState = androidx.compose.runtime.mutableStateOf(false)
    private var pinOnSuccess: (() -> Unit)? = null
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
    private var pinOnCancel: (() -> Unit)? = null

    // DVR scrub focus: nahlad pozicie pri vybere casu sipkami (potvrdenie OK)
    private val scrubFractionState = androidx.compose.runtime.mutableStateOf(0f)
    private fun initScrub() {
        // zlomok v ramci DOSIAHNUTELNEHO rozsahu baru (rovnaka skala ako seekbar) z
        // playheadu prehravacich hodin - nie z player.position (na rastucom TS nespolahliva).
        val bar = if (dvrRecording) (dvrDurationMs - 45_000L).coerceAtLeast(1L) else dvrDurationMs
        scrubFractionState.value = if (bar > 0)
            (dvrPlayheadMsState.value.toFloat() / bar).coerceIn(0f, 1f) else 0f
    }

    // M265: vyber v PIN mriezke (in-player vyzva, D-pad). Mriezka 1-9 / del 0 x.
    private val pinGridRowState = androidx.compose.runtime.mutableStateOf(0)
    private val pinGridColState = androidx.compose.runtime.mutableStateOf(0)
    private fun activatePinGridKey() {
        val grid = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("del", "0", "list")
        )
        val r = pinGridRowState.value.coerceIn(0, 3)
        val c = pinGridColState.value.coerceIn(0, 2)
        when (val label = grid[r][c]) {
            "del" -> pinDel()
            "list" -> pinOpenChannelList()
            else -> pinDigit(label.toInt())
        }
    }

    private var pinMarkUnlock = true
    // M262: index kanala, ktoreho prehravanie PIN vyzva blokuje (null = ine pouzitie,
    // napr. zamykanie z menu). Umoznuje pocas vyzvy prepnut na susedny kanal.
    private var pinChannelIndex: Int? = null
    private fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, markUnlock: Boolean = true, channelIndex: Int? = null) {
        okLongFired = false   // PIN vyzva preberá vstup; OK gesto je tým ukoncene
        pinMarkUnlock = markUnlock
        pinChannelIndex = channelIndex
        pinOnSuccess = onOk; pinOnCancel = onCancel
        pinEntryState.value = ""; pinErrorState.value = false
        pinGridRowState.value = 0; pinGridColState.value = 0
        pinPromptState.value = true
    }
    private fun closePin() {
        pinPromptState.value = false; pinEntryState.value = ""; pinErrorState.value = false
        pinOnSuccess = null; pinOnCancel = null
        pinMarkUnlock = true
        pinChannelIndex = null
    }
    /** M267: z PIN vyzvy zamknuteho kanala otvor zoznam kanalov, nech si pouzivatel vyberie
     *  iny (nezamknuty) kanal. Vyzvu zatvorime bez onCancel (teda bez finish), aby prehravac
     *  nezhasol. Ak je len jeden kanal, niet kam prepnut -> sprav cancel (finish). */
    private fun pinOpenChannelList() {
        if (liveUuids.size < 2) { cancelPin(); return }
        closePin()
        openChannelList()
    }
    private fun pinDigit(d: Int) {
        if (pinEntryState.value.length >= 4) return
        pinEntryState.value += d
        pinErrorState.value = false
        if (pinEntryState.value.length == 4) {
            if (ParentalLock.checkPin(this, pinEntryState.value)) {
                if (pinMarkUnlock) ParentalLock.markUnlocked(this)
                val ok = pinOnSuccess
                closePin(); ok?.invoke()
            } else { pinErrorState.value = true; pinEntryState.value = "" }
        }
    }
    private fun cancelPin() {
        val c = pinOnCancel
        closePin(); c?.invoke()
    }
    private fun pinDel() {
        if (pinEntryState.value.isNotEmpty()) pinEntryState.value = pinEntryState.value.dropLast(1)
        pinErrorState.value = false
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

    // --- Kontextove menu kanala v prehravaci (long-press OK / dlhy klik) ---
    private val ctxMenuIdxState = androidx.compose.runtime.mutableStateOf(-1)  // index kanala, -1 = zatvorene
    private val ctxMenuSelState = androidx.compose.runtime.mutableStateOf(0)    // zvyraznena polozka

    /** Polozky menu pre dany kanal (v poradi). "lock" len ak je zamok zapnuty,
     *  "fromstart" len ak sa relacia prave nahrava (da sa prehrat od zaciatku). */
    private fun ctxMenuKeys(idx: Int): List<String> {
        val ch = liveChannelsState.value.getOrNull(idx) ?: return emptyList()
        val keys = mutableListOf("info")
        if (recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] } != null) keys.add("fromstart")
        // M368: oblubene a skrytie kanala aj na TV (predtym len na telefone)
        keys.add("fav")
        if (ParentalLock.isEnabled(this)) keys.add("lock")
        keys.add("hide")
        return keys
    }

    private fun openChannelContextMenu(idx: Int) {
        if (idx < 0 || idx >= liveChannelsState.value.size) return
        if (ctxMenuKeys(idx).isEmpty()) return
        ctxMenuSelState.value = 0
        ctxMenuIdxState.value = idx
    }
    private fun closeChannelContextMenu() { ctxMenuIdxState.value = -1 }

    private fun activateCtxMenu(key: String) {
        val idx = ctxMenuIdxState.value
        val ch = liveChannelsState.value.getOrNull(idx)
        closeChannelContextMenu()
        if (ch == null) return
        when (key) {
            "info" -> showChannelInfo(idx)                       // detail relacie priamo v prehravaci
            "fromstart" -> {
                val rec = recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] }
                if (rec != null) playRecordingFromStart(rec, ch.nowStart, ch.nowStop)
                else if (idx != liveIndex) switchToIndex(idx)     // ak kanal este nehra a nie je archiv -> aspon prepni nazivo
            }
            "lock" -> toggleLockAt(idx)                           // uz riesi PIN + grace okno
            "fav" -> {
                val sid = (liveServer ?: Tvh.store.active())?.id
                if (sid != null) Favorites.toggle(this, sid, ch.uuid)
            }
            "hide" -> {
                val sid = (liveServer ?: Tvh.store.active())?.id
                if (sid != null) {
                    HiddenChannels.setHidden(this, sid, ch.uuid, true)
                    // Skryty kanal hned odstranit zo zap zoznamu (ak prave nehra);
                    // posun liveIndex, aby CH+/- dalej sedeli.
                    if (idx != liveIndex) {
                        val cur = liveChannelsState.value.toMutableList()
                        if (idx in cur.indices) {
                            cur.removeAt(idx)
                            liveChannelsState.value = cur
                            if (idx < liveIndex) liveIndex--
                        }
                    }
                }
            }
        }
    }

    // --- Info o relacii (detail) v prehravaci ---
    private val infoVisibleState = androidx.compose.runtime.mutableStateOf(false)
    // M280: potvrdenie ukoncenia ziveho prehravania (BACK) — ako exit dialog v menu
    private val exitConfirmState = androidx.compose.runtime.mutableStateOf(false)
    private val exitConfirmSelState = androidx.compose.runtime.mutableStateOf(0) // 0=Zrusit, 1=Ukoncit
    private val infoChannelState = androidx.compose.runtime.mutableStateOf("")
    private val infoTitleState = androidx.compose.runtime.mutableStateOf("")
    private val infoTimeState = androidx.compose.runtime.mutableStateOf("")
    private val infoDescState = androidx.compose.runtime.mutableStateOf("")

    private fun fmtClock(s: Long): String =
        if (s <= 0) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date(s * 1000))
    private fun fmtRange(a: Long, b: Long): String {
        val sa = fmtClock(a); val sb = fmtClock(b)
        return if (sa.isNotBlank() && sb.isNotBlank()) "$sa - $sb" else sa
    }

    private fun applyInfo(ev: sk.tvhclient.shared.model.EpgEvent) {
        if (ev.title.isNotBlank()) infoTitleState.value = ev.title
        if (ev.start > 0) infoTimeState.value = fmtRange(ev.start, ev.stop)
        infoDescState.value = ev.bestDescription
    }

    /** Zobrazi detail aktualnej relacie kanala (z EPG); okamzite ukaze now-polia, popis doplni async. */
    private fun showChannelInfo(idx: Int) {
        val ch = liveChannelsState.value.getOrNull(idx) ?: return
        infoChannelState.value = ch.name
        infoTitleState.value = ch.nowTitle
        infoTimeState.value = fmtRange(ch.nowStart, ch.nowStop)
        infoDescState.value = ""
        infoVisibleState.value = true
        val srv = Tvh.store.active() ?: return
        val nowSec = System.currentTimeMillis() / 1000
        fun pick(list: List<sk.tvhclient.shared.model.EpgEvent>) =
            list.firstOrNull { it.start <= nowSec && nowSec < it.stop } ?: list.minByOrNull { it.start }
        val cached = epgUpcomingState.value[ch.uuid]
        if (!cached.isNullOrEmpty()) {
            pick(cached)?.let { applyInfo(it) }
        } else {
            lifecycleScope.launch {
                val list = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        Tvh.fetchEpgForChannel(srv, Tvh.apiFor(srv), ch.uuid)
                    }
                }.getOrDefault(emptyList())
                cacheChannelEpg(ch.uuid, list)   // M274: memoizuj pre dalsie zobrazenia/reopen
                if (infoVisibleState.value) pick(list)?.let { applyInfo(it) }
            }
        }
    }
    private fun closeChannelInfo() { infoVisibleState.value = false }

    // Kedy sa zoznam otvoril — OK eventy tesne po otvoreni (zvysky otvaracieho
    // dlheho stlacenia, ghost DOWN/UP pary z IR/CEC ovladacov) sa ignoruju (M330-fix2)
    private var channelListOpenedAt = 0L

    private fun openChannelList() {
        // M371: otvor aj s 1 kanalom, ak su skupiny na prepnutie (napr. Oblubene s 1 kanalom),
        // inak by sa filtrovany zoznam uz nedal otvorit ani prepnut spat.
        if (liveUuids.size < 2 && groupKeys().size <= 1) return
        groupPickerState.value = false
        searchActiveState.value = false
        activeGroupLabelState.value =
            if (groupKeys().size > 1) groupLabelFor(LivePlaylist.activeGroupKey) else ""
        navChannelIndexState.value = liveIndex.coerceAtLeast(0)
        channelListOpenedAt = android.os.SystemClock.uptimeMillis()
        openChannelListState.value = openChannelListState.value + 1
    }
    private fun closeChannelList() {
        groupPickerState.value = false
        searchActiveState.value = false
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

    /** Nastavi casovac uspatia (0 = vypnut). Po uplynuti zastavi a zavrie prehravac. */
    private fun setSleepTimer(minutes: Int) {
        sleepHandler.removeCallbacksAndMessages(null)
        sleepMinutesState.value = minutes
        if (minutes <= 0) {
            sleepDeadlineState.value = 0L
            Toast.makeText(this, getString(R.string.sleep_off), Toast.LENGTH_SHORT).show()
            return
        }
        sleepDeadlineState.value = System.currentTimeMillis() + minutes * 60_000L
        sleepHandler.postDelayed({
            runCatching { if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) mediaPlayer.stop() }
            finish()
        }, minutes * 60_000L)
        Toast.makeText(this, getString(R.string.sleep_set, minutes), Toast.LENGTH_SHORT).show()
    }

    /** Vyber dlzky casovaca uspatia. */
    private fun selectOption(idx: Int) {
        setSleepTimer(sleepDurations.getOrElse(idx) { 0 })
        closeOptions()
    }

    // --- Track menu (audio/titulky) riadene z Activity ---
    private fun trackMenuIds(): List<Int> {
        if (!::mediaPlayer.isInitialized) return emptyList()
        if (trackMenuKind == "profile") return profileItemsState.value.indices.toList()
        return if (trackMenuKind == "audio") {
            mediaPlayer.audioTrackItems().map { it.id }
        } else {
            val spu = if (htspStream) htspSpuItemsList() else mediaPlayer.spuTrackItems()
            listOf(-1) + spu.map { it.id }  // -1 = Vypnute
        }
    }

    /** HTSP: kompletny zoznam titulkovych jazykov z metadat (rovnaky na kazdom zariadeni,
     *  nezavisle od toho ci jazyk uz "prehovoril"). id = HTSP stream index. */
    private fun htspSpuItemsList(): List<TrackItem> {
        val subs = htspFeeder?.subtitleStreams ?: return emptyList()
        return subs.map { TrackItem(it.esIndex, langDisplay(it.language) ?: "DVB titulky") }
    }

    /** HTSP vyber titulku: zapamataj zelany jazyk a skus ho hned nastavit v libVLC; ak stopa
     *  este nie je (jazyk nehovoril), aplikuje sa pri ESAdded. id < 0 = Vypnute. */
    private fun onPickHtspSpu(esIndex: Int) {
        selectedSubEsState.value = esIndex
        // DVB titulky dekódujeme a renderujeme sami; do libVLC nejdu. Vyber = ktory ES dekódovat.
        subOverlay?.reset()
        htspFeeder?.selectSubtitle(esIndex)
    }

    /** Nastavi libVLC titulkovu stopu podla zelaneho (anglickeho) nazvu jazyka, ak uz existuje. */
    private fun applyDesiredSpu() {
        if (!::mediaPlayer.isInitialized) return
        val want = desiredSubName ?: return
        val tracks = mediaPlayer.spuTracks ?: return
        val m = tracks.firstOrNull {
            it.id >= 0 && (it.name?.contains(want, ignoreCase = true) == true)
        } ?: return
        if (mediaPlayer.spuTrack != m.id) mediaPlayer.spuTrack = m.id
    }
    /** M383: prepinac profilu ma zmysel len pri HTTP live (nie HTSP, nie DVR,
     *  nie externa URL — tam profil neexistuje alebo sa neda menit). */
    private fun profileSwitchAvailable(): Boolean = profileSwitchState.value

    private fun openProfileMenu() {
        val srv = liveServer ?: return
        trackMenuKind = "profile"; trackNavState.value = 0
        // okamzity fallback, server moze zoznam vzapati nahradit vlastnym
        if (profileItemsState.value.isEmpty()) {
            profileItemsState.value =
                ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() }
        }
        lifecycleScope.launch {
            val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                sk.tvhclient.shared.Tvh.streamProfiles(srv)
            }
            if (list.isNotEmpty()) profileItemsState.value = list
        }
        openProfileMenuState.value = openProfileMenuState.value + 1
    }

    /** M383: novy profil = nova predvolba SERVERA (plati pre vsetky dalsie kanaly,
     *  drzi po restarte; ta ista hodnota je v Nastavenia -> server -> Upravit).
     *  Stream sa restartuje s novou URL. */
    private fun applyProfileChange(profile: String) {
        val srv = liveServer ?: return
        if (profile.isBlank() || profile == srv.profile) return
        val updated = srv.copy(profile = profile)
        sk.tvhclient.shared.Tvh.store.upsert(updated)
        liveServer = updated
        currentProfileState.value = profile
        val i = liveIndex
        if (i >= 0) { liveIndex = -1; switchToIndex(i, poke = false) }
    }

    private fun openAudioMenu() {
        trackMenuKind = "audio"; trackNavState.value = 0
        openAudioMenuState.value = openAudioMenuState.value + 1
    }
    private fun openSpuMenu() {
        trackMenuKind = "spu"; trackNavState.value = 0
        openSpuMenuState.value = openSpuMenuState.value + 1
    }
    private fun closeTrackMenu() { closeMenuState.value = closeMenuState.value + 1 }
    private fun selectTrackAtNav() {
        if (!::mediaPlayer.isInitialized) return
        val ids = trackMenuIds()
        val id = ids.getOrNull(trackNavState.value) ?: return
        when {
            trackMenuKind == "profile" -> {
                profileItemsState.value.getOrNull(id)?.let { applyProfileChange(it) }
            }
            trackMenuKind == "audio" -> {
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
            else -> mediaPlayer.spuTrack = id
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

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val down = event.action == android.view.KeyEvent.ACTION_DOWN
        val kc = event.keyCode

        // DIAGNOSTIKA (volitelna v nastaveniach): kod nezvycajneho klavesu
        if (remoteDebug && down && !isCommonKey(kc)) {
            Toast.makeText(
                this,
                "Klávesa: $kc (${android.view.KeyEvent.keyCodeToString(kc)})",
                Toast.LENGTH_SHORT
            ).show()
        }

        // M370: aktivne hladanie s fokusom na textovom poli -> text spracuje system/IME;
        // zachytime len BACK (zavri hladanie) a DOLE (prejdi na vysledky).
        if (searchActiveState.value && searchFieldFocusedState.value) {
            if (down) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_BACK -> { closeSearch(); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (searchResults().isNotEmpty()) {
                            searchFieldFocusedState.value = false
                            searchNavIndexState.value = 0
                        }
                        return true
                    }
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // 0) PIN rodicovskeho zamku -> cislice zadavame my; na TV aj D-pad mriezka
        if (pinPromptState.value) {
            if (down) {
                val digit = when (kc) {
                    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                        kc - android.view.KeyEvent.KEYCODE_0
                    in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 ->
                        kc - android.view.KeyEvent.KEYCODE_NUMPAD_0
                    else -> -1
                }
                // priame cislice z dialkoveho (ak ich ovladac ma)
                if (digit >= 0) { pinDigit(digit); return true }
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DEL -> { pinDel(); return true }
                    // M267-fix: sipka Spat pocas PIN vyzvy zamknuteho kanala vrati pouzivatela
                    // k zoznamu kanalov (nech si vyberie nezamknuty), neukoncuje prehravac.
                    // Plati LEN pocas PIN vyzvy (mimo nej ma BACK svoju beznu funkciu nizsie).
                    android.view.KeyEvent.KEYCODE_BACK -> { pinOpenChannelList(); return true }
                }
                // M262: pocas vyzvy sa da prepnut na iny kanal — len hardverove CHANNEL +/-
                // (D-pad teraz ovlada PIN mriezku). Volny kanal sa zacne hrat, dalsi zamknuty
                // si opat vypyta PIN.
                val pci = pinChannelIndex
                if (pci != null && liveUuids.size > 1 && event.repeatCount == 0) {
                    when (kc) {
                        android.view.KeyEvent.KEYCODE_CHANNEL_UP -> { switchFromPin(pci, +1); return true }
                        android.view.KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchFromPin(pci, -1); return true }
                    }
                }
                // M265: D-pad mriezka na zadanie PIN — pre ovladace bez ciselnych klaves.
                if (isTvDevice()) {
                    when (kc) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { pinGridColState.value = (pinGridColState.value - 1 + 3) % 3; return true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { pinGridColState.value = (pinGridColState.value + 1) % 3; return true }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> { pinGridRowState.value = (pinGridRowState.value - 1 + 4) % 4; return true }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { pinGridRowState.value = (pinGridRowState.value + 1) % 4; return true }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> { activatePinGridKey(); return true }
                    }
                }
            }
            return true
        }

        // 0a) Dialog "Obnovit prehravanie" -> sipky vlavo/vpravo + OK riesime my (na boxe inak bez fokusu)
        if (resumePromptState.value) {
            if (down) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                        { resumeSelState.value = 1 - resumeSelState.value; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        { if (event.repeatCount == 0) resumeAnswerState.value = if (resumeSelState.value == 1) 1 else 2; return true }
                    android.view.KeyEvent.KEYCODE_BACK ->
                        { resumeAnswerState.value = 2; return true }
                }
            }
            return true
        }

        // 0b) Vyber pri archivovanom kanali -> sipky vlavo/vpravo + OK + BACK riesime my
        if (archiveChoiceIdxState.value >= 0) {
            if (down) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                        { archiveChoiceSelState.value = 1 - archiveChoiceSelState.value; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        { if (event.repeatCount == 0) resolveArchiveChoice(archiveChoiceSelState.value == 1); return true }
                    android.view.KeyEvent.KEYCODE_BACK ->
                        { archiveChoiceIdxState.value = -1; return true }
                }
            }
            return true
        }
        val okKey = kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
            kc == android.view.KeyEvent.KEYCODE_ENTER ||
            kc == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
        if (okKey && !down && okLongFired) { okLongFired = false; return true }

        // 0c) Kontextove menu kanala (long-press v zozname) -> hore/dole + OK (na uvolnenie) + BACK
        if (ctxMenuIdxState.value >= 0) {
            val keys = ctxMenuKeys(ctxMenuIdxState.value)
            val cnt = keys.size
            val isOkC = kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                kc == android.view.KeyEvent.KEYCODE_ENTER ||
                kc == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (isOkC) {
                if (okLongFired) return true                 // prehltni up z otvaracieho long-pressu
                if (!down && cnt > 0) activateCtxMenu(keys.getOrElse(ctxMenuSelState.value) { keys.first() })
                return true                                   // OK aktivuje az na uvolnenie
            }
            if (down && cnt > 0) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        { ctxMenuSelState.value = (ctxMenuSelState.value - 1 + cnt) % cnt; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        { ctxMenuSelState.value = (ctxMenuSelState.value + 1) % cnt; return true }
                    android.view.KeyEvent.KEYCODE_BACK,
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                        { closeChannelContextMenu(); return true }
                }
            }
            return true
        }

        // 0d) Info o relacii (detail) -> hociktore OK/BACK/vlavo zatvori
        if (infoVisibleState.value) {
            if (down) when (kc) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER,
                android.view.KeyEvent.KEYCODE_BACK,
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { closeChannelInfo(); return true }
            }
            return true
        }

        // 0e) Potvrdenie ukoncenia ziveho prehravania (BACK) -> sipky + OK + BACK riesime my
        if (exitConfirmState.value) {
            if (down) when (kc) {
                android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                    { exitConfirmSelState.value = 1 - exitConfirmSelState.value; return true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 0) {
                        if (exitConfirmSelState.value == 1) finish() else exitConfirmState.value = false
                    }
                    return true
                }
                android.view.KeyEvent.KEYCODE_BACK ->
                    { exitConfirmState.value = false; return true }
            }
            return true
        }

        if (down) {
            when (kc) {
                // EPG klavesy roznych ovladacov (M345)
                android.view.KeyEvent.KEYCODE_GUIDE,
                android.view.KeyEvent.KEYCODE_TV_DATA_SERVICE,
                android.view.KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                android.view.KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU -> { openEpgInApp(); return true }
                // Titulkovy klaves -> titulky (predtym omylom otvaral EPG)
                android.view.KeyEvent.KEYCODE_CAPTIONS -> { openSpuMenu(); return true }
                // Audio klaves (na mnohych TV/box ovladacoch) -> zvukove stopy
                android.view.KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK -> { openAudioMenu(); return true }
                // MENU klaves -> OSD/ovladanie pocas prehravania
                android.view.KeyEvent.KEYCODE_MENU -> {
                    if (modernTvActive()) openModernOverlay() else showControlsFocused()
                    return true
                }
                android.view.KeyEvent.KEYCODE_INFO -> { toggleInfo(); return true }
            }
            // media RW/FF -> timeshift skok (ak je HTSP zivy)
            if (htspLive) when (kc) {
                android.view.KeyEvent.KEYCODE_MEDIA_REWIND -> { timeshiftSkip(-30); pokeControls(); return true }
                android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { timeshiftSkip(+30); pokeControls(); return true }
            }
        }

        // 1) Otvoreny zoznam kanalov -> navigujeme my
        if (channelListOpen) {
            val n = liveUuids.size
            // M370: aktivne hladanie, fokus na vysledkoch (pole riesi skory bypass vyssie)
            if (searchActiveState.value) {
                val res = searchResults()
                val n2 = res.size
                if (down) {
                    when (kc) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                            if (searchNavIndexState.value <= 0) {
                                searchFieldFocusedState.value = true
                                searchFocusSignalState.value = searchFocusSignalState.value + 1
                            } else searchNavIndexState.value = searchNavIndexState.value - 1
                            return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (n2 > 0) searchNavIndexState.value =
                                (searchNavIndexState.value + 1).coerceAtMost(n2 - 1)
                            return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            res.getOrNull(searchNavIndexState.value)?.let { selectLiveByUuid(it.uuid) }
                            return true
                        }
                        android.view.KeyEvent.KEYCODE_BACK -> {
                            searchFieldFocusedState.value = true
                            searchFocusSignalState.value = searchFocusSignalState.value + 1
                            return true
                        }
                    }
                }
                when (kc) {
                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                    android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
                }
                return true
            }
            val isOk = kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                kc == android.view.KeyEvent.KEYCODE_ENTER ||
                kc == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            // M369: fokus na pilulke skupiny (nad zoznamom) — VLAVO/VPRAVO meni skupinu,
            // DOLE/OK naspat do zoznamu, HORE nic, BACK zavrie pilulku.
            if (groupPickerState.value) {
                if (down) {
                    when (kc) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { cycleGroup(-1); return true }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { cycleGroup(+1); return true }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN,
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> { groupPickerState.value = false; return true }
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> { openSearch(); return true }
                        android.view.KeyEvent.KEYCODE_BACK -> { groupPickerState.value = false; return true }
                    }
                }
                when (kc) {
                    android.view.KeyEvent.KEYCODE_VOLUME_UP,
                    android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                    android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
                }
                return true
            }
            if (isOk) {
                // pocas drzania otvaracieho OK (a jeho opakovani) nereaguj
                if (okLongFired) return true
                // debounce po otvoreni: niektore ovladace (IR/CEC) poslu po dlhom
                // stlaceni este ghost DOWN/UP par — ten by okamzite potvrdil kanal
                // a zoznam zavrel; vsetko OK do 400 ms od otvorenia sa zahodi
                if (android.os.SystemClock.uptimeMillis() - channelListOpenedAt < 400L) return true
                if (down) {
                    // podrzanie OK v zozname = kontextove menu kanala (Info / od zaciatku / zamok)
                    if (event.isLongPress && n > 0) {
                        okLongFired = true               // OK-up sa potom prehltne (nevyberie kanal)
                        openChannelContextMenu(navChannelIndexState.value)
                    }
                    return true                          // na DOWN nevyberaj (cakame na uvolnenie)
                } else if (n > 0) {
                    // uvolnenie OK (kratky klik) = vyber/prepnutie kanala
                    if (navChannelIndexState.value == liveIndexState.value) {
                        closeChannelList()
                        if (modernTvActive()) openModernOverlay() else showControlsFocused()
                    }
                    else selectChannelOrArchive(navChannelIndexState.value, poke = false)
                }
                return true
            }
            if (down && n > 0) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        // z vrchu zoznamu HORE -> fokus na pilulku skupiny (ak su nejake skupiny)
                        if (navChannelIndexState.value == 0 && groupKeys().size > 1) {
                            groupPickerState.value = true
                        } else {
                            navChannelIndexState.value = (navChannelIndexState.value - 1 + n) % n
                        }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        { navChannelIndexState.value = (navChannelIndexState.value + 1) % n; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                        { navChannelIndexState.value = (navChannelIndexState.value - 7).coerceIn(0, n - 1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                        { navChannelIndexState.value = (navChannelIndexState.value + 7).coerceIn(0, n - 1); return true }
                    android.view.KeyEvent.KEYCODE_BACK ->
                        { closeChannelList(); return true }
                }
            }
            when (kc) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        // 2) Otvoreny vyber casovaca uspatia -> vertikalna navigacia
        if (optionsOpen) {
            val count = sleepDurations.size
            if (down) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        { optionsNavState.value = (optionsNavState.value + count - 1) % count; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        { optionsNavState.value = (optionsNavState.value + 1) % count; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        selectOption(optionsNavState.value)
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_BACK -> {
                        closeOptions()
                        return true
                    }
                }
            }
            when (kc) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        // 3) Otvorene track menu (audio/titulky) -> navigujeme my (hore/dole + OK)
        if (trackMenuOpen) {
            val ids = trackMenuIds()
            val n = ids.size
            if (down && n > 0) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        { trackNavState.value = (trackNavState.value - 1 + n) % n; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        { trackNavState.value = (trackNavState.value + 1) % n; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        { selectTrackAtNav(); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_BACK ->
                        { closeTrackMenu(); return true }
                }
            }
            when (kc) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        // 3b0) "Viac" menu nad modernym overlayom (M327)
        if (modernMoreState.value) {
            if (down) when (kc) {
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                    modernMoreIdx.value = (modernMoreIdx.value + 1) % modernMoreIds().size; return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                    modernMoreIdx.value = (modernMoreIdx.value - 1 + modernMoreIds().size) % modernMoreIds().size; return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (event.repeatCount == 0) modernMoreActivate(); return true
                }
                android.view.KeyEvent.KEYCODE_BACK -> { modernMoreState.value = false; return true }
            }
            if (!down && (kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    kc == android.view.KeyEvent.KEYCODE_ENTER ||
                    kc == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ||
                    kc == android.view.KeyEvent.KEYCODE_BACK)) return true
            return true
        }
        // 3b) Moderny TV overlay (karty kanalov + ovladacia lista) -> navigujeme my
        if (modernOvState.value) {
            val ids = modernStripIds()
            if (down) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        if (modernOvRow.value == 0) {
                            val n = liveUuids.size
                            if (n > 0) modernOvCard.value = (modernOvCard.value - 1 + n) % n
                        } else modernOvStrip.value = (modernOvStrip.value - 1 + ids.size) % ids.size
                        modernOvPoke.value++; return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        if (modernOvRow.value == 0) {
                            val n = liveUuids.size
                            if (n > 0) modernOvCard.value = (modernOvCard.value + 1) % n
                        } else modernOvStrip.value = (modernOvStrip.value + 1) % ids.size
                        modernOvPoke.value++; return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                        if (modernOvRow.value == 0) {
                            modernOvRow.value = 1
                            modernOvStrip.value = ids.indexOf("play").coerceAtLeast(0)
                        }
                        modernOvPoke.value++; return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                        if (modernOvRow.value == 1) modernOvRow.value = 0
                        modernOvPoke.value++; return true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (event.repeatCount == 1) {
                            // Podrzanie OK v overlay = moznosti FOKUSOVANEHO kanala
                            // (Info / Prehrat od zaciatku / Zamok) — M338. Velky zoznam
                            // ostava cez Viac -> Kanaly a dlhe OK z cisteho prehravania.
                            // okLongFired: guard prehltne OK-up (inak by potvrdil polozku menu)
                            okLongFired = true
                            modernOkLong = false
                            openChannelContextMenu(modernOvCard.value)
                        }
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_BACK -> { closeModernOverlay(); return true }
                }
            } else {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                        if (!modernOkLong) modernOvActivate()
                        modernOkLong = false
                        return true
                    }
                    android.view.KeyEvent.KEYCODE_BACK -> return true
                }
            }
            when (kc) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        // 4) Bezne prehravanie
        if (::mediaPlayer.isInitialized) {
            val canZap = !seekablePlayback && liveUuids.size > 1
            // prepinanie kanalov: Channel+/-, Page+/-, aj sipky hore/dole = zap
            when (kc) {
                android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP,
                android.view.KeyEvent.KEYCODE_DPAD_UP ->
                    if (down && canZap && event.repeatCount == 0) {
                        switchLive(+1)
                        if (modernTvActive()) { modernOvCard.value = liveIndexState.value.coerceAtLeast(0); openModernOverlay() } else showControlsFocused()
                        return true
                    }
                android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (down && canZap && event.repeatCount == 0) {
                        switchLive(-1)
                        if (modernTvActive()) { modernOvCard.value = liveIndexState.value.coerceAtLeast(0); openModernOverlay() } else showControlsFocused()
                        return true
                    }
            }
            // cislice 0-9 (aj numericka klavesnica) = volba kanala cislom
            run {
                val digit = when (kc) {
                    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                        kc - android.view.KeyEvent.KEYCODE_0
                    in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 ->
                        kc - android.view.KeyEvent.KEYCODE_NUMPAD_0
                    else -> -1
                }
                if (digit >= 0) { if (down) onChannelDigit(digit); return true }
            }
            // rozpisane cislo kanala + OK => potvrd hned (rychlejsie prepnutie,
            // netreba cakat na 1,5 s casovac)
            if (numEntry.isNotEmpty() && (
                    kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    kc == android.view.KeyEvent.KEYCODE_ENTER ||
                    kc == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER)
            ) {
                if (down && event.repeatCount == 0) { numJob?.cancel(); commitChannelNumber() }
                return true
            }
            // ovladanie zobrazene -> vlavo/vpravo naviguju panel, OK aktivuje
            // zvyrazneny prvok (hore/dole prepinaju kanal vyssie)
            if (controlsShown) {
                val order = playerControlOrder(canZap, seekablePlayback, pipButtonVisible(), timeshiftEngagedState.value, profileSwitchAvailable())
                val n = order.size
                if (seekablePlayback) {
                    val onSeek = order.getOrNull(controlNavState.value) == "seek"
                    val dur = if (dvrDurationMs > 0) dvrDurationMs else mediaPlayer.length
                    val stepFrac = if (dur > 0) 30_000f / dur else 0.02f
                    when (kc) {
                        android.view.KeyEvent.KEYCODE_DPAD_UP -> if (down) {
                            controlNavState.value = (controlNavState.value - 1 + n) % n
                            if (order.getOrNull(controlNavState.value) == "seek") initScrub()
                            pokeControls(); return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                            controlNavState.value = (controlNavState.value + 1) % n
                            if (order.getOrNull(controlNavState.value) == "seek") initScrub()
                            pokeControls(); return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                            if (onSeek) scrubFractionState.value = (scrubFractionState.value - stepFrac).coerceIn(0f, 1f)
                            else {
                                controlNavState.value = (controlNavState.value - 1 + n) % n
                                if (order.getOrNull(controlNavState.value) == "seek") initScrub()
                            }
                            pokeControls(); return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                            if (onSeek) scrubFractionState.value = (scrubFractionState.value + stepFrac).coerceIn(0f, 1f)
                            else {
                                controlNavState.value = (controlNavState.value + 1) % n
                                if (order.getOrNull(controlNavState.value) == "seek") initScrub()
                            }
                            pokeControls(); return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (down && event.repeatCount == 0) {
                                if (onSeek) {
                                    if (::mediaPlayer.isInitialized) {
                                        // scrubFrac je zlomok dosiahnutelneho rozsahu baru;
                                        // seek cez seekDvrTo (funguje aj pre feeder/pipe)
                                        val bar = if (dvrRecording) (dvrDurationMs - 45_000L).coerceAtLeast(1L) else dvrDurationMs
                                        val progMs = (scrubFractionState.value.coerceIn(0f, 1f) * bar).toLong()
                                        seekDvrAbsolute(progMs)
                                    }
                                    pokeControls()
                                } else activateControl(order.getOrNull(controlNavState.value))
                            }
                            return true
                        }
                    }
                } else {
                    // live: vlavo/vpravo naviguju panel (hore/dole prepinaju kanal vyssie)
                    when (kc) {
                        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                            controlNavState.value = (controlNavState.value - 1 + n) % n
                            pokeControls(); return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                            controlNavState.value = (controlNavState.value + 1) % n
                            pokeControls(); return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (down && event.repeatCount == 0) activateControl(order.getOrNull(controlNavState.value))
                            return true
                        }
                    }
                }
                // BACK necháme Compose BackHandler (skryje ovladanie); volume/ostatne tiez
                return super.dispatchKeyEvent(event)
            }
            // ovladanie skryte
            when (kc) {
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    if (seekablePlayback) {
                        if (down && event.repeatCount == 0) { togglePlayPause(); showControlsFocused() }
                        return true
                    }
                    if (modernTvActive()) {
                        // Kratke OK -> overlay az na UP; podrzanie -> rovno velky zoznam.
                        // Overlay sa pri podrzani vobec neotvori, ziadny preblik.
                        if (down && event.repeatCount == 0) { modernOkPending = true; return true }
                        if (down && event.repeatCount == 1 && modernOkPending) {
                            modernOkPending = false
                            okLongFired = true   // prehltne OK-up, inak by up hned potvrdil kanal a zoznam zavrel
                            openChannelList()
                            return true
                        }
                        if (down) return true
                        if (modernOkPending) { modernOkPending = false; openModernOverlay() }
                        return true
                    }
                    if (down && event.repeatCount == 0) {
                        okLongFired = true; openChannelList()  // okLongFired prehltne nasledne OK-up
                        return true
                    }
                    if (down) return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                    if (seekablePlayback) { seekRelative(-15_000); pokeControls(); return true }
                    if (modernTvActive()) openModernOverlay() else showControlsFocused(); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                    if (seekablePlayback) { seekRelative(+30_000); pokeControls(); return true }
                    if (modernTvActive()) openModernOverlay() else showControlsFocused(); return true
                }
                // hore/dole sem prides len ak sa neda zapovat (napr. DVR) -> otvor panel
                android.view.KeyEvent.KEYCODE_DPAD_UP,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (down) { showControlsFocused(); return true }
            }
        }
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
    private var dvrReopenAttempts = 0

    private fun saveDvrProgress() {
        val uuid = dvrUuid ?: return
        val sid = dvrServerId ?: return
        if (!::mediaPlayer.isInitialized) return
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

    private fun keepScreenOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Mini radio (M340) nesmie hrat popri plnom prehravaci
        RadioPlayerService.stop(this)
        // Zavri predoslu instanciu prehravaca (napr. visiacu v PiP so starym kanalom),
        // nech pri prepnuti kanala nezostane stara PiP visiet. Nova sa otvori na celu obrazovku.
        liveInstance?.get()?.let { old -> if (old !== this) runCatching { old.finish() } }
        liveInstance = java.lang.ref.WeakReference(this)
        // Navrat na povodny zivy kanal po zatvoreni (pri "Prehrat od zaciatku" z prehravaca)
        returnLiveUuid = intent.getStringExtra(EXTRA_RETURN_UUID)
        returnLiveTitle = intent.getStringExtra(EXTRA_RETURN_TITLE)
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

        // Immersive fullscreen — skry status aj navigacnu listu, nech
        // neprekryvaju ovladanie. Listy sa daju vytiahnut potiahnutim.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        val channelUuid = intent.getStringExtra(EXTRA_UUID)
        val channelTitle = intent.getStringExtra(EXTRA_TITLE) ?: ""
        val directUrl = intent.getStringExtra(EXTRA_URL)
        playKind = intent.getStringExtra(EXTRA_KIND) ?: "tv"
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val progStart = intent.getLongExtra(EXTRA_PROG_START, 0L)
        val progStop = intent.getLongExtra(EXTRA_PROG_STOP, 0L)
        val progTitle = intent.getStringExtra(EXTRA_PROG_TITLE) ?: ""
        dvrUuid = intent.getStringExtra(EXTRA_DVR_UUID)
        val progStartFrac = intent.getFloatExtra(EXTRA_PROG_START_FRAC, 0f)
        val progStopFrac = intent.getFloatExtra(EXTRA_PROG_STOP_FRAC, 1f)
        dvrDurationMs = durationMs
        dvrDurationState.value = durationMs
        dvrRecording = intent.getBooleanExtra(EXTRA_DVR_RECORDING, false)
        dvrProgStartSec = intent.getLongExtra(EXTRA_DVR_PROG_START_SEC, 0L)
        dvrProgStopSec = intent.getLongExtra(EXTRA_DVR_PROG_STOP_SEC, 0L)
        dvrRealStartSec = intent.getLongExtra(EXTRA_DVR_REAL_START_SEC, 0L)
        // Prebiehajuca relacia: dlzka rastie k zivej hrane; bar musi byt VZDY viditelny.
        // Ak mame hranice relacie, dopocitavame relativne k jej zaciatku (cap dlzkou relacie).
        // Ak hranice chybaju (nahravka nema vyplnene start/stop), drzime krok s dlzkou z VLC.
        if (dvrRecording) {
            val haveBounds = dvrProgStartSec > 0 && dvrProgStopSec > dvrProgStartSec
            val progDurMs = if (haveBounds) (dvrProgStopSec - dvrProgStartSec) * 1000 else 0L
            dvrDurationMs = if (haveBounds)
                ((System.currentTimeMillis() / 1000 - dvrProgStartSec) * 1000).coerceIn(1000L, progDurMs)
            else
                maxOf(durationMs, 1000L)   // aspon 1s, nech sa bar zobrazi
            dvrDurationState.value = dvrDurationMs
            lifecycleScope.launch {
                while (true) {
                    val nowSec = System.currentTimeMillis() / 1000
                    val live = if (haveBounds)
                        ((minOf(nowSec, dvrProgStopSec) - dvrProgStartSec) * 1000).coerceIn(1000L, progDurMs)
                    else
                        maxOf(dvrDurationMs, if (::mediaPlayer.isInitialized) mediaPlayer.length else 0L)
                    if (live > dvrDurationMs) { dvrDurationMs = live; dvrDurationState.value = live }
                    if (haveBounds && nowSec >= dvrProgStopSec) break  // relacia skoncila
                    kotlinx.coroutines.delay(1000)
                }
            }
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

        val options = arrayListOf(
            "--network-caching=1500",
            "--no-drop-late-frames",
            "--no-skip-frames",
            "--quiet",
            "--no-stats",
            "--http-user-agent=" + userAgent()
        )
        // Deinterlacing (globalne, nech plati uz na prvom otvoreni; per-medium
        // sa nastavi znova pri kazdom prepnuti kanala)
        val (dEn, dMode) = deinterlaceSpec()
        options.add("--deinterlace=$dEn")
        if (dMode != null) options.add("--deinterlace-mode=$dMode")
        libVlc = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVlc)
        // Zvukovy vystup z nastaveni. Modul (telefon: AudioTrack/OpenSL ES) aj
        // zariadenie (TV: passthrough/pcm/stereo) sa musia nastavit pred prehravanim;
        // menia sa az pri (znovu)otvoreni prehravaca.
        AudioModulePref.module(this)?.let { aout -> runCatching { mediaPlayer.setAudioOutput(aout) } }
        AudioOutputPref.deviceId(this)?.let { dev -> runCatching { mediaPlayer.setAudioOutputDevice(dev) } }

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EncounteredError -> {
                    // zivé vysielanie: skus znovu pripojit (vypadok siete)
                    if (!seekablePlayback) {
                        scheduleReconnect()
                    } else if (dvrRecording) {
                        // DVR seek/feeder zlyhal -> znovu otvor stream na aktualnom playheade
                        // (reopenDvrLive ma backoff a po vycerpani pokusov vycisti spinner),
                        // nech to neostane zaseknute na "Opatovne pripajanie"
                        reopenDvrLive()
                    } else {
                        reconnectingState.value = false
                        Toast.makeText(
                            this,
                            getString(R.string.playback_error, "VLC"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                MediaPlayer.Event.Playing -> {
                    isPlayingState.value = true; refreshPipIfActive()
                    maybeApplyAfr()  // AFR (M346): prepni Hz displeja podla fps streamu
                    applyAudioDelay()  // kompenzacia sink latencie (M349, ako Kodi)
                    keepScreenOn(true)  // pocas prehravania nedovol setric/ambient na boxoch
                    cancelReconnect()  // uspesne pripojenie -> vynuluj pokusy
                    dvrReopenAttempts = 0  // uspesne pokracovanie -> vynuluj pokusy o znovu-otvorenie
                    seekSpinnerJob?.cancel(); seekingState.value = false  // resync po skoku dobehol
                    // po nabehnuti zisti ci stream ma video; ak nie -> rozhlas (logo)
                    videoCheckHandler.removeCallbacksAndMessages(null)
                    videoCheckHandler.postDelayed({
                        val n = runCatching { mediaPlayer.videoTracksCount }.getOrNull()
                        if (n != null && n >= 0) hasVideoState.value = n > 0
                    }, 1500)
                    // doplnenie audio jazykov / DVB titulkov, ktore libVLC doparsuje az po starte
                    scheduleTrackRefresh()
                    maybeReparseForTracks()
                }
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering >= 100f) { seekSpinnerJob?.cancel(); seekingState.value = false }
                }
                MediaPlayer.Event.Paused -> { isPlayingState.value = false; keepScreenOn(false); refreshPipIfActive() }
                MediaPlayer.Event.Stopped -> { isPlayingState.value = false; keepScreenOn(false); refreshPipIfActive() }
                MediaPlayer.Event.Vout -> { if (event.voutCount > 0) hasVideoState.value = true }
                MediaPlayer.Event.ESAdded,
                MediaPlayer.Event.ESDeleted -> {
                    // libVLC priebezne registruje stopy (DVB titulky / audio jazyky sa
                    // objavia az par sekund po starte) -> obnov otvorene track menu
                    trackListVersionState.value = trackListVersionState.value + 1
                    // ak pouzivatel zvolil titulkovy jazyk, ktory este nebol k dispozicii,
                    // nastav ho hned ako jeho stopa pribudne (mimo libVLC callbacku)
                    if (htspStream) lifecycleScope.launch { applyDesiredSpu() }
                }
                MediaPlayer.Event.EndReached -> {
                    isPlayingState.value = false
                    if (!seekablePlayback) {
                        // zivý stream "skoncil" = vypadok -> znovu pripojit
                        scheduleReconnect()
                    } else if (dvrRecording &&
                        (dvrProgStopSec <= 0 || System.currentTimeMillis() / 1000 < dvrProgStopSec)) {
                        // prebiehajuca nahravka dobehla na koniec zapisanych dat -> znovu otvor
                        // stream (novy GET prinesie novsie data), nie koniec prehravania
                        saveDvrProgress()
                        reopenDvrLive()
                    } else {
                        reachedEnd = true
                        keepScreenOn(false)
                        saveDvrProgress()
                    }
                }
            }
        }

        // DVR: priame dvrfile URL (s creds). Live: profil servera (M383 — per-kanal
        // override zruseny, profil sa da prepnut priamo v prehravaci).
        val streamUrl = directUrl ?: Tvh.liveUrl(
            server, channelUuid!!, channelTitle,
            server.profile.ifBlank { "pass" }
        )

        // Server je potrebny aj v DVR rezime (seekDvrTo / reopenDvrLive cez feeder).
        // Live-zapping nizsie zavisi od liveUuids (pri DVR prazdne), nie od liveServer.
        liveServer = server
        currentProfileState.value = server.profile.ifBlank { "pass" }
        profileSwitchState.value =
            directUrl == null && channelUuid != null && server.connectionMode != "htsp"
        // M383: prednacitaj zoznam profilov (dotykove tlacidlo otvara menu priamo,
        // bez openProfileMenu) — fallback hned, servrovy zoznam async
        if (server.connectionMode != "htsp" && profileItemsState.value.isEmpty()) {
            profileItemsState.value =
                ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() }
            lifecycleScope.launch {
                val list = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    sk.tvhclient.shared.Tvh.streamProfiles(server)
                }
                if (list.isNotEmpty()) profileItemsState.value = list
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
        liveChannelsState.value = LivePlaylist.channels
        // M281: hned dopln now/next z cache (disk/proces) na viditelny zoznam, nech sa nazvy
        // relacii pod kanalmi ukazu okamzite aj po restarte (predtym cakali na sietovy refresh).
        applyCachedEpgToChannels()
        liveIndexState.value = liveIndex
        liveTitleState.value = channelTitle
        liveUuidState.value = channelUuid
        liveProgStartState.value = progStart
        liveProgStopState.value = progStop
        liveProgTitleState.value = progTitle
        val canZap = directUrl == null && liveUuids.size > 1
        seekablePlayback = directUrl != null
        // predvolene zvyraznenie ovladacieho panela = play (nie krizik)
        controlNavState.value = playerControlOrder(canZap, seekablePlayback, pipButtonVisible(), timeshiftEngagedState.value, profileSwitchAvailable()).indexOf("play").coerceAtLeast(0)
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
                    @Suppress("UNUSED_EXPRESSION") trackListVersionState.value  // refresh ked pribudne stopa
                    htspSpuItemsList()
                } else null,
                htspSpuCurrentId = selectedSubEsState.value,
                onPickHtspSpu = if (htspStreamState.value) ({ id -> onPickHtspSpu(id) }) else null,
                onAttach = { layout ->
                    videoLayout = layout
                    mediaPlayer.attachViews(layout, null, false, false)
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
                        clockSource = { if (::mediaPlayer.isInitialized) mediaPlayer.time else 0L },
                        aspectSource = {
                            val vt = if (::mediaPlayer.isInitialized) runCatching { mediaPlayer.currentVideoTrack }.getOrNull() else null
                            if (vt != null && vt.width > 0 && vt.height > 0) {
                                val sn = if (vt.sarNum > 0) vt.sarNum else 1
                                val sd = if (vt.sarDen > 0) vt.sarDen else 1
                                (vt.width.toFloat() * sn) / (vt.height.toFloat() * sd)
                            } else 16f / 9f
                        }
                    )
                },
                onStart = {
                    val doPlay: () -> Unit = {
                        val cid = channelUuid?.toLongOrNull()
                        val htspMode = server.connectionMode == "htsp"
                        if (cid != null && directUrl == null && htspMode) {
                            // stream cez HTSP (9982). Timeshift funkcie len ak je pref zapnuty a server podporuje.
                            currentStreamUrl = streamUrl  // HTTP fallback pre reconnect/reparse stop
                            lifecycleScope.launch {
                                val ts = TimeshiftPref.get(this@PlayerActivity) && withContext(Dispatchers.IO) {
                                    runCatching {
                                        HtspData.timeshiftAvailable(server, System.currentTimeMillis() / 1000)
                                    }.getOrDefault(false)
                                }
                                if (playHtspLive(server, cid, ts)) {
                                    htspStream = true
                                    htspLive = ts
                                    htspLiveState.value = ts
                                } else {
                                    htspStream = false
                                    htspLive = false
                                    htspLiveState.value = false
                                    playLiveAuto(server, streamUrl)
                                }
                                htspInitDone = true
                                pokeControls()
                            }
                        } else {
                            if (directUrl != null && server.username.isNotEmpty()) {
                                // M254: auto-detekcia auth. Digest-only server -> feeder
                                // (libVLC digest cez URL nevie); basic/ziadna -> priama
                                // seekovatelna cesta.
                                lifecycleScope.launch {
                                    val useFeeder = withContext(Dispatchers.IO) {
                                        DvrAuthProbe.needsFeeder(server, stripCreds(streamUrl))
                                    }
                                    dvrViaFeeder = useFeeder
                                    if (useFeeder) playDvrViaFeeder(server, streamUrl)
                                    else playHttp(streamUrl)
                                    pokeControls()
                                }
                            } else {
                                dvrViaFeeder = false
                                playLiveAuto(server, streamUrl)
                                pokeControls()
                            }
                        }
                    }
                    // rodicovsky zamok: pri KAZDOM otvoreni prehravaca so zamknutym kanalom
                    // vypytaj PIN (bez ohladu na grace okno). Grace ("nepytat X min") plati len
                    // pri prepinani v ramci otvoreneho prehravaca (zoznam / pozadie / cislice).
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
                searchActive = searchActiveState.value,
                searchQuery = searchQueryState.value,
                onSearchQueryChange = { searchQueryState.value = it; searchNavIndexState.value = 0 },
                searchFieldFocused = searchFieldFocusedState.value,
                searchHits = if (searchActiveState.value) searchResults() else emptyList(),
                searchNavIndex = searchNavIndexState.value,
                searchFocusSignal = searchFocusSignalState.value,
                openListSignal = openChannelListState.value,
                closeListSignal = closeChannelListState.value,
                onTrackMenuChange = { kind ->
                    // M349-fix: composable hlasi aj DRUH menu — bez toho ostal
                    // trackMenuKind "audio" z minula a vyber titulkov cez D-pad
                    // omylom prepinal zvukovu stopu
                    trackMenuOpen = kind != null
                    if (kind != null) { trackMenuKind = kind; trackNavState.value = 0 }
                    // M383: poistka — profile menu otvorene dotykom bez zoznamu
                    if (kind == "profile" && profileItemsState.value.isEmpty()) {
                        profileItemsState.value =
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
                sleepDeadline = sleepDeadlineState.value,
                onOptionsSelect = { idx -> selectOption(idx) },
                controlNavIndex = controlNavState.value,
                trackNavIndex = trackNavState.value,
                trackListVersion = trackListVersionState.value,
                closeMenuSignal = closeMenuState.value,
                openAudioSignal = openAudioMenuState.value,
                openSpuSignal = openSpuMenuState.value,
                openProfileSignal = openProfileMenuState.value,
                profileItems = profileItemsState.value,
                currentProfile = currentProfileState.value,
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
                onPrevChannel = if (canZap) ({ switchLive(-1) }) else null,
                onNextChannel = if (canZap) ({ switchLive(+1) }) else null,
                onTogglePlay = { togglePlayPause() },
                timeshiftEngaged = timeshiftEngagedState.value,
                modernOvVisible = modernOvState.value,
                modernOvRow = modernOvRow.value,
                modernOvCard = modernOvCard.value,
                modernOvStrip = modernOvStrip.value,
                modernOvPoke = modernOvPoke.value,
                modernOvExec = modernOvExec.value,
                modernOvExecId = modernOvExecId.value,
                modernOvRecNames = recInProgressByChan.value.keys,
                modernStripIds = modernStripIds(),
                modernMoreVisible = modernMoreState.value,
                modernMoreIndex = modernMoreIdx.value,
                onMorePick = { i -> modernMoreIdx.value = i; modernMoreActivate() },
                onMoreDismiss = { modernMoreState.value = false },
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
                numberEntry = numEntryState.value,
                timeshiftOffsetMs = timeshiftOffsetState.value,
                pinPrompt = pinPromptState.value,
                pinLen = pinEntryState.value.length,
                pinError = pinErrorState.value,
                onPinDigit = { d -> pinDigit(d) },
                onPinBack = { pinDel() },
                onPinCancel = { cancelPin() },
                onPinOpenList = { pinOpenChannelList() },
                pinGridRow = pinGridRowState.value,
                pinGridCol = pinGridColState.value,
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
            // Vyber pri archivovanom kanali (nazivo / od zaciatku) — overlay v style prehravaca
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
                                    "hide" -> androidx.compose.ui.res.stringResource(R.string.ch_hide)
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
                                                "hide" -> androidx.compose.material.icons.Icons.Default.VisibilityOff
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
            // Info o relacii (detail) — overlay v style prehravaca
            if (infoVisibleState.value) {
                Box(
                    Modifier.fillMaxSize().background(Color(0xCC0B1220))
                        .clickable { closeChannelInfo() },
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        Modifier.fillMaxWidth(0.78f).widthIn(max = 560.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1B2433))
                            .padding(horizontal = 24.dp, vertical = 24.dp)
                    ) {
                        if (infoChannelState.value.isNotBlank()) {
                            Text(infoChannelState.value, color = Color(0xFF6699FF),
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.height(6.dp))
                        }
                        Text(infoTitleState.value.ifBlank { infoChannelState.value },
                            color = Color.White, style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        if (infoTimeState.value.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(infoTimeState.value, color = Color(0xFFB9C2D0),
                                style = MaterialTheme.typography.titleMedium)
                        }
                        if (infoDescState.value.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Text(infoDescState.value, color = Color(0xFFD7DEE8),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier
                                    .heightIn(max = 260.dp)
                                    .verticalScroll(androidx.compose.foundation.rememberScrollState()))
                        }
                    }
                }
            }
            }
        }
    }

    // ---- Picture-in-Picture ----
    @androidx.annotation.RequiresApi(26)
    private fun buildPipParams(): android.app.PictureInPictureParams {
        val playing = isPlayingState.value
        val icon = android.graphics.drawable.Icon.createWithResource(
            this,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val label = if (playing) getString(R.string.pip_pause) else getString(R.string.pip_play)
        val pi = android.app.PendingIntent.getBroadcast(
            this, 1,
            android.content.Intent(PIP_ACTION).setPackage(packageName),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = android.app.RemoteAction(icon, label, label, pi)
        return android.app.PictureInPictureParams.Builder()
            .setActions(listOf(action))
            .setAspectRatio(android.util.Rational(16, 9))
            .build()
    }

    private fun enterPipIfPossible(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            ::mediaPlayer.isInitialized
        ) {
            return runCatching { enterPictureInPictureMode(buildPipParams()) }.getOrDefault(false)
        }
        return false
    }

    /** Spusti PiP (okno plava nad plochou / inou appkou). M349-fix4: ziadny
     *  moveTaskToBack — presun tasku na pozadie hned po vstupe do PiP na
     *  mnohych zariadeniach cerstve PiP okno zrusil (prehravac sa "len zavrel").
     *  Vstup do PiP sam zbali aktivitu do plavajuceho okna, nic dalsie netreba —
     *  auto-PiP cesta to robi rovnako a funguje. */
    private fun enterPipAndMinimize() {
        enterPipIfPossible()
    }

    /**
     * Auto-PiP pri navigacii v ramci appky (EPG / navrat domov).
     * Vstupi do PiP len na telefonoch (pipSupported), ak hra a este nie je v PiP.
     * Vrati true, ak presiel do PiP (volajuci moze podla toho preskocit finish()).
     */
    private fun autoPipIfPossible(): Boolean {
        if (AutoPipPref.get(this) && pipSupported && isPlayingState.value &&
            android.os.Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            ::mediaPlayer.isInitialized &&
            !isInPictureInPictureMode
        ) {
            // enterPictureInPictureMode vrati true, ak realne vstupil do PiP (nespoliehaj sa
            // na isInPictureInPictureMode hned po volani - aktualizuje sa az asynchronne)
            return runCatching { enterPictureInPictureMode(buildPipParams()) }.getOrDefault(false)
        }
        return false
    }

    // aktualizuj ikonu play/pauza v PiP podla skutocneho stavu prehravania
    private fun refreshPipIfActive() {
        if (android.os.Build.VERSION.SDK_INT >= 26 && isInPictureInPictureMode) {
            runCatching { setPictureInPictureParams(buildPipParams()) }
        }
    }

    /** Zrusi naplanovane znovupripojenie a skryje indikator. */
    private fun cancelReconnect() {
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectAttempts = 0
        reconnectingState.value = false
    }

    /** In-progress nahravka dobehla na koniec zapisanych dat (EOF na rastucom HTTP subore).
     *  Po chvili (nech pribudne dalsi blok) znovu otvor stream a vrat sa na poziciu z
     *  prehravacich hodin (offset + prehrany cas relacie) - tak sa pokracuje do novsich dat.
     *  Backoff proti slucke ked nic nove nepribuda; resetuje sa pri Playing evente. */
    private fun reopenDvrLive() {
        if (!seekablePlayback || !dvrRecording) return
        val url = currentStreamUrl ?: return
        if (!::mediaPlayer.isInitialized) return
        if (dvrReopenAttempts >= 5) {
            reconnectingState.value = false
            return
        }
        dvrReopenAttempts++
        val offsetMs = if (dvrProgStartSec > 0 && dvrRealStartSec in 1 until dvrProgStartSec)
            (dvrProgStartSec - dvrRealStartSec) * 1000 else 0L
        // pozicia v subore = offset + prehrany cas relacie, par sekund vzad ako rezerva
        val startSec = ((offsetMs + dvrPlayheadMsState.value) / 1000 - 3).coerceAtLeast(0)
        reconnectingState.value = true
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            if (!::mediaPlayer.isInitialized) return@postDelayed
            runCatching {
                if (dvrViaFeeder) {
                    // pokracuj od miesta kam sme dosli (rastuci subor) cez HTTP Range
                    val srv = liveServer ?: return@runCatching
                    val from = httpFeeder?.bytesWritten ?: 0L
                    playDvrViaFeeder(srv, url, from)
                } else {
                    val m = buildMedia(url)
                    m.addOption(":start-time=$startSec")
                    mediaPlayer.media = m
                    m.release()
                    mediaPlayer.play()
                }
            }
        }, 2500)
    }

    /** Naplanuje znovupripojenie zivého streamu po vypadku (narastajuce oneskorenie). */
    private fun scheduleReconnect() {
        if (seekablePlayback) return  // DVR nahravka sa neobnovuje (in-progress riesi reopenDvrLive)
        if (!::mediaPlayer.isInitialized) return
        if (reconnectAttempts >= maxReconnectAttempts) {
            reconnectingState.value = false
            Toast.makeText(this, getString(R.string.reconnect_failed), Toast.LENGTH_LONG).show()
            return
        }
        reconnectAttempts++
        reconnectingState.value = true
        val delay = (1500L * reconnectAttempts).coerceAtMost(8000L)
        reconnectHandler.removeCallbacksAndMessages(null)
        reconnectHandler.postDelayed({
            if (!::mediaPlayer.isInitialized) return@postDelayed
            val srv = liveServer
            val cid = liveUuids.getOrNull(liveIndex)?.toLongOrNull()
            val url = currentStreamUrl
            runCatching {
                if (htspStream && srv != null && cid != null) {
                    // HTSP kanal -> znovu napoj cez HTSP (zachova HTSP/timeshift)
                    playHtspLive(srv, cid, htspLive)
                } else if (liveNeedsFeeder == true && srv != null && url != null) {
                    playLiveViaFeeder(srv, url)   // HTTP digest-only -> feeder
                } else if (url != null) {
                    val m = buildMedia(url)       // bezne HTTP
                    mediaPlayer.media = m
                    m.release()
                    mediaPlayer.play()
                }
            }
            // watchdog: ak sa do 12 s neobjavi prehravanie (spinner ostal), skus znova;
            // po vycerpani pokusov scheduleReconnect ohlasi chybu -> ziadne trvale zaseknutie.
            // 12 s nechava HTSP subscription cas nabehnut a nabufrovat (kratsie sa dvojilo)
            reconnectHandler.postDelayed({
                if (::mediaPlayer.isInitialized && reconnectingState.value && !mediaPlayer.isPlaying) {
                    scheduleReconnect()
                }
            }, 12000)
        }, delay)
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
        if (isInPictureInPictureMode) {
            if (pipReceiver == null) {
                pipReceiver = object : android.content.BroadcastReceiver() {
                    override fun onReceive(c: android.content.Context?, i: android.content.Intent?) {
                        if (i?.action == PIP_ACTION) togglePlayPause()
                    }
                }
                val filter = android.content.IntentFilter(PIP_ACTION)
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    registerReceiver(pipReceiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    registerReceiver(pipReceiver, filter)
                }
            }
        } else {
            pipReceiver?.let { runCatching { unregisterReceiver(it) } }
            pipReceiver = null
            // PiP okno zatvorene pouzivatelom kym bola appka na pozadi: aktivita je uz STOPnuta
            // (stav CREATED, onStop uz prebehol a nechal video bezat). Tu doraz zastav prehravanie,
            // inak by zvuk hral dalej. Ak pouzivatel PiP rozbalil na celu obrazovku, stav je
            // STARTED/RESUMED a prehravac nezastavujeme.
            if (lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED &&
                ::mediaPlayer.isInitialized
            ) {
                runCatching { if (mediaPlayer.isPlaying) mediaPlayer.pause() }
                runCatching { mediaPlayer.detachViews() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // navrat z pozadia: znova pripoj video na surface a obnov prehravanie
        if (::mediaPlayer.isInitialized) {
            videoLayout?.let { runCatching { mediaPlayer.attachViews(it, null, false, false) } }
            // rodicovsky zamok: ak sa vraciame z pozadia na zamknuty ZIVY kanal,
            // vyziadaj PIN znova (kazdy navrat do prehravaca = PIN, ako pri starte).
            val curUuid = liveUuids.getOrNull(liveIndex)
            if (wasPlaying && !seekablePlayback && !pinPromptState.value &&
                ParentalLock.channelLockedProtected(this, liveServer?.id ?: Tvh.store.active()?.id, curUuid)
            ) {
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
        // TV boxy nemaju FEATURE_PICTURE_IN_PICTURE, takze pipSupported = telefon/tablet.
        if (AutoPipPref.get(this) && pipSupported && isPlayingState.value &&
            !(android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode)) {
            enterPipIfPossible()
        }
    }

    override fun onStop() {
        saveDvrProgress()
        // PiP okno nechaj hrat LEN ak sme realne v PiP (vlastny priznak z callbacku, nie zivy
        // isInPictureInPictureMode - ten pri zatvarani PiP casto este hlasi true) a appka len ide
        // na pozadie. Ak sa aktivita ukoncuje, prepadni dole a zastav prehravanie.
        if (android.os.Build.VERSION.SDK_INT >= 24 && inPipState.value && !isFinishing) {
            super.onStop(); return
        }
        wasPlaying = ::mediaPlayer.isInitialized && mediaPlayer.isPlaying
        super.onStop()
        if (::mediaPlayer.isInitialized) {
            if (isFinishing) {
                runCatching { mediaPlayer.stop() }
            } else if (mediaPlayer.isPlaying) {
                mediaPlayer.pause()
            }
            // uvolni surface, nech sa po navrate da znova pripojit (inak cierna obrazovka)
            runCatching { mediaPlayer.detachViews() }
        }
    }

    // --- Doplnenie stop po starte (audio jazyky / DVB titulky) ---
    // Pri prvom napojeni streamu libVLC este nema doparsovane doplnkove ES; jazyky audio
    // stop a DVB titulkove stopy sa objavia az par sekund po starte. ESAdded udalost na
    // niektorych streamoch nechodi spolahlivo, preto po Event.Playing kratko pollujeme a
    // obnovujeme pripadne otvorene track menu (zvysenim trackListVersionState), kym sa
    // stopy doplnia. Bez prerusenia prehravania — len precitanie zoznamu nanovo.
    private val trackRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun scheduleTrackRefresh() {
        trackRefreshHandler.removeCallbacksAndMessages(null)
        // niekolko vln v priebehu ~8 s — staci aby sa stihli doparsovat jazyky aj DVB titulky
        for (delay in longArrayOf(800L, 1600L, 2600L, 4000L, 6000L, 8000L)) {
            trackRefreshHandler.postDelayed({
                trackListVersionState.value = trackListVersionState.value + 1
            }, delay)
        }
    }
    private fun cancelTrackRefresh() {
        trackRefreshHandler.removeCallbacksAndMessages(null)
    }

    // Jednorazove znovu-napojenie streamu kvoli stopam. Ak po starte ziadna audio stopa
    // nema jazyk, libVLC vytvoril ES skor nez doparsoval PMT s jazykovymi deskriptormi
    // (caste na multi-audio TS). Tieto ES uz jazyk nedostanu a DVB titulky sa neobjavia —
    // pomoze len cerstve napojenie streamu (rovnaky efekt ako navrat z pozadia). Spravime
    // ho RAZ na kanal a LEN ked jazyky naozaj chybaju (inak ziadny zbytocny blik).
    private var trackReparseDone = false
    private val trackReparseHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun maybeReparseForTracks() {
        if (trackReparseDone || seekablePlayback || htspStream) return  // HTSP berie jazyky z PMT
        trackReparseHandler.removeCallbacksAndMessages(null)
        trackReparseHandler.postDelayed({
            if (trackReparseDone || seekablePlayback || htspStream || !::mediaPlayer.isInitialized) return@postDelayed
            val langs = runCatching { mediaPlayer.trackLanguages() }.getOrDefault(emptyMap())
            val anyLang = langs.values.any { !it.isNullOrBlank() && !it.equals("und", true) }
            trackReparseDone = true  // tak ci tak skus len raz
            // znovu napojenie cez OVERENU reconnect cestu (sama sa zotavi, naplni stopy);
            // vlastny re-open cez playHtspLive sa zasekaval na stop+start HTSP subscription
            if (!anyLang) scheduleReconnect()
        }, 1800)
    }

    // =====================================================================
    // AFR — automaticka obnovovacia frekvencia (M346)
    // Precita fps z video stopy (libVLC frameRateNum/Den) a vyberie rezim
    // displeja s rovnakym rozlisenim, ktoreho Hz je celociselnym nasobkom fps
    // (25 fps -> 50 Hz; 60 Hz sa odmietne, 60/25 = 2.4). Preferuje 2x nasobok,
    // potom 1x. Predvolene vypnute (AfrPref), len TV/box. Pri odchode sa
    // preferencia vrati systemu.
    // =====================================================================
    private var afrRetryPosted = false

    private fun maybeApplyAfr() {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        if (!AfrPref.get(this)) return
        if (!::mediaPlayer.isInitialized) return
        val vt = runCatching { mediaPlayer.currentVideoTrack }.getOrNull()
        val num = vt?.frameRateNum ?: 0
        val den = vt?.frameRateDen ?: 0
        if (num <= 0 || den <= 0) {
            // stopa este nie je pripravena — jeden odlozeny pokus
            if (!afrRetryPosted) {
                afrRetryPosted = true
                window.decorView.postDelayed({ afrRetryPosted = false; maybeApplyAfr() }, 900L)
            }
            return
        }
        val fps = num.toFloat() / den
        if (fps < 10f) return
        // Telefon/tablet (M348): Surface.setFrameRate — system sam rozhodne,
        // ci panel prepne (LTPO plynulo, bez resyncu); ziadna pauza netreba.
        // Rovnake API pouziva Netflix/YouTube. TV/box ide display-mode cestou.
        if (!isTvBox) {
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                findVideoSurface()?.let { surf ->
                    runCatching {
                        surf.setFrameRate(
                            fps,
                            android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE
                        )
                    }
                }
            }
            return
        }
        val disp = if (android.os.Build.VERSION.SDK_INT >= 30) display
            else @Suppress("DEPRECATION") windowManager.defaultDisplay
        val cur = disp?.mode ?: return
        val candidates = disp.supportedModes.filter {
            it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight
        }
        fun score(m: android.view.Display.Mode): Int {
            val k = m.refreshRate / fps
            val kr = kotlin.math.round(k)
            if (kr < 1f || kotlin.math.abs(k - kr) > 0.02f * kr) return Int.MIN_VALUE
            return when (kr.toInt()) { 2 -> 3; 1 -> 2; else -> 1 }  // 2x (50 Hz pre 25 fps) > 1x > vyssie
        }
        val best = candidates.maxByOrNull { score(it) } ?: return
        if (score(best) == Int.MIN_VALUE) return
        if (best.modeId == cur.modeId) return
        // Prepnutie do HDR vypnute -> ziadne tvrde prepnutie rezimu (to vyvolava
        // HDMI re-sync a HDR flip firmwaru). Namiesto toho len plynula ziadost
        // o frekvenciu; system ju splni iba ak to panel zvladne bez re-syncu.
        if (!AfrHdrSwitchPref.get(this)) {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                findVideoSurface()?.let { surf ->
                    runCatching {
                        surf.setFrameRate(
                            fps,
                            android.view.Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE,
                            android.view.Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
                        )
                    }
                }
            }
            return
        }
        runCatching {
            val lp = window.attributes
            lp.preferredDisplayModeId = best.modeId
            window.attributes = lp
            // Pauza po zmene rezimu (M347, ako Kodi): pocas HDMI resyncu TV
            // nic neukazuje ani nehra — pauza zabrani stratenemu zaciatku
            // a audio desyncu. Po uplynuti sa prehravanie samo obnovi.
            val delaySec = AfrDelayPref.get(this)
            if (delaySec > 0 && mediaPlayer.isPlaying) {
                mediaPlayer.pause()
                window.decorView.postDelayed({
                    runCatching { if (::mediaPlayer.isInitialized) mediaPlayer.play() }
                }, delaySec * 1000L)
            }
        }
    }

    /** Najde SurfaceView videa vo VLCVideoLayout (rekurzivne) — pre setFrameRate. */
    private fun findVideoSurface(): android.view.Surface? {
        fun find(v: android.view.View): android.view.SurfaceView? {
            if (v is android.view.SurfaceView) return v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) find(v.getChildAt(i))?.let { return it }
            }
            return null
        }
        val layout = videoLayout ?: return null
        val sv = find(layout) ?: return null
        val surf = sv.holder.surface
        return if (surf != null && surf.isValid) surf else null
    }

    /** Kompenzacia oneskorenia zvuku (M349) — pri passthrough dekoduje TV/AVR
     *  a pridava latenciu, ktoru prehravac nevidi; zaporny delay zvuk zarovna. */
    private fun applyAudioDelay() {
        if (!::mediaPlayer.isInitialized) return
        runCatching { mediaPlayer.setAudioDelay(AudioDelayPref.effectiveUs(this)) }
    }

    /** Resync kick (M349, mechanizmus Kodi c.1): po prepnuti kanala pri
     *  passthrough sa audio pipeline TV/AVR nestihne zrovnat — kratke
     *  pause/play po nabehnuti flushne vystup a VLC zrovna hodiny. */
    private fun postZapResyncKick() {
        if (seekablePlayback) return
        if (AudioOutputPref.get(this) != AudioOutputPref.PASSTHROUGH) return
        window.decorView.postDelayed({
            runCatching {
                if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
                    mediaPlayer.pause()
                    window.decorView.postDelayed({
                        runCatching { if (::mediaPlayer.isInitialized) mediaPlayer.play() }
                    }, 120L)
                }
            }
        }, 1200L)
    }

    private fun clearAfr() {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        runCatching {
            val lp = window.attributes
            if (lp.preferredDisplayModeId != 0) {
                lp.preferredDisplayModeId = 0
                window.attributes = lp
            }
        }
    }

    override fun onDestroy() {
        clearAfr()
        saveDvrProgress()
        super.onDestroy()
        // uvolni odkaz, len ak stale ukazuje na tuto instanciu (nie na novsiu)
        if (liveInstance?.get() === this) liveInstance = null
        videoCheckHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        sleepHandler.removeCallbacksAndMessages(null)
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        pipReceiver = null
        subOverlay?.stopTicker()   // zastav titulkovy ticker skor nez uvolnis mediaPlayer
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
        }
        htspFeeder?.stop()
        htspFeeder = null
        httpFeeder?.stop()
        httpFeeder = null
        stopTimeshiftTicker()
        skipFlushJob?.cancel()
        cancelTrackRefresh()
        trackReparseHandler.removeCallbacksAndMessages(null)
        if (::libVlc.isInitialized) {
            libVlc.release()
        }
    }

    companion object {
        const val EXTRA_UUID = "channel_uuid"
        const val EXTRA_TITLE = "channel_title"
        const val EXTRA_RETURN_UUID = "return_live_uuid"
        const val EXTRA_RETURN_TITLE = "return_live_title"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_KIND = "play_kind"
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
private fun langDisplay(code: String?): String? {
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
private fun MediaPlayer.trackLanguages(): Map<Int, String?> {
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

private fun MediaPlayer.audioTrackItems(): List<TrackItem> {
    val descs = audioTracks ?: return emptyList()
    val langs = trackLanguages()
    // id < 0 je vstavana "Disable" polozka libVLC — preskoc (audio sa nevypina)
    return descs.filter { it.id >= 0 }.map { d ->
        val disp = langDisplay(langs[d.id])
        val base = d.name
        val name = when {
            disp != null -> disp
            !base.isNullOrBlank() -> base
            else -> "Audio ${d.id}"
        }
        TrackItem(d.id, name)
    }
}

private fun MediaPlayer.spuTrackItems(): List<TrackItem> {
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
            else -> "Titulky ${d.id}"
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

    // Dlzka baru = uplynuty cas relacie (knownDurationMs, plynulo rastie 1s/s).
    // Nepouzivame player.length do skaly - VLC ju pre rastuci TS hlasi v hrubych
    // skokoch, co rozhadzovalo lavu stranu casomiery. Fallback na VLC dlzku len ked
    // EPG cas nemame.
    val lengthMs = if (knownDurationMs > 0) knownDurationMs else player.length.coerceAtLeast(0L)
    // Cerstva dlzka pre ticker (LaunchedEffect(Unit) inak zachyti hodnotu zo startu a
    // lava strana by sa nezmestila nad uroven zivej hrany pri starte).
    val lengthMsLive = androidx.compose.runtime.rememberUpdatedState(lengthMs)
    val offsetMsLive = androidx.compose.runtime.rememberUpdatedState(recordingOffsetMs)
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
    if (seekable) {
        LaunchedEffect(Unit) {
            var sinceSave = 0
            while (true) {
                val nowMs = System.currentTimeMillis()
                val curLen = lengthMsLive.value
                val curOff = offsetMsLive.value
                // dosiahnutelny rozsah (bez rezervy) - playhead ani znovu-otvorenie nejde do nej
                val curBar = if (recordingLive) (curLen - liveMarginMs).coerceAtLeast(1L) else curLen
                // Po pretoceni (seekDvrTo): prevezmi cielovy cas ako playhead. Pri feeder/pipe
                // je player.position po restarte neplatna, tak ju nasledny resync nesmie citat -
                // seed da hodinam spravny bod a tikaju dalej z neho (initialSeekDone=true zaroven
                // umlci jednorazovy skok na zaciatok relacie).
                if (seekSeedMs >= 0L) {
                    posTimeMs = seekSeedMs.coerceIn(0L, curBar)
                    val denom = (curOff + curLen).coerceAtLeast(1L)
                    posFraction = ((curOff + posTimeMs).toFloat() / denom).coerceIn(0f, 1f)
                    initialSeekDone = true
                    onSeekSeedHandled()
                }
                // obnovenie po potvrdeni (ma prednost pred skokom na zaciatok relacie).
                // POZOR: priame player.position pri pipe/feeder DVR nefunguje (a
                // isSeekable byva false, takze sa skok nikdy nevykonal a nahravka
                // isla od zaciatku) — pouzi tu istu cestu ako pouzivatelske
                // pretocenie (onSeekToMs -> seekDvrAbsolute: restart s :start-time,
                // playhead hodiny prevezmu seed).
                if (pendingResumeMs > 0 && curLen > 0) {
                    val tgt = pendingResumeMs.coerceIn(0L, curBar)
                    onSeekToMs(tgt)
                    posTimeMs = tgt
                    posFraction = ((curOff + tgt).toFloat() / (curOff + curLen).coerceAtLeast(1L)).coerceIn(0f, 1f)
                    pendingResumeMs = 0
                    initialSeekDone = true
                }
                // Jednorazovy skok na zaciatok relacie v subore (prebiehajuca nahravka s
                // predprogramovym obsahom). Seekujeme POZICIOU (zlomok), nie setTime - na
                // rastucom TS je to spolahlivejsie. Zlomok = offset / (offset + uplynuty cas
                // relacie) = poloha zaciatku relacie v aktualnom buffri.
                if (!initialSeekDone && recordingLive && curOff > 0 &&
                    !askResume && pendingResumeMs == 0L && curLen > 0 && player.isSeekable) {
                    val f = (curOff.toFloat() / (curOff + curLen)).coerceIn(0f, 1f)
                    player.position = f
                    posFraction = f
                    posTimeMs = 0L
                    initialSeekDone = true
                }
                if (!dragging) {
                    val p = player.position
                    if (p in 0f..1f) {
                        // Skok pozicie = doslo k seeku (slider/D-pad/dvojklik) -> zosulad
                        // prehravacie hodiny so skutocnou poziciou. Mapujeme subor->cas relacie:
                        // (p * (offset + dlzka)) - offset. Pri normalnom prehravani sa p meni
                        // plynulo (<<5%), takze sa to nespusti a hodiny tikaju z wall-clocku.
                        // p > 0.02: ignoruj falosne nulove citanie pozicie (caste na rastucom TS
                        // aj tesne po znovu-otvoreni), nech hodiny neskocia na 0.
                        if (initialSeekDone && curLen > 0 && p > 0.02f && player.isSeekable &&
                            kotlin.math.abs(p - posFraction) > 0.05f) {
                            posTimeMs = (p * (curOff + curLen) - curOff).toLong()
                                .coerceIn(0L, curBar)
                        }
                        posFraction = p
                    }
                    // Prehravacie hodiny: kym sa prehrava, pridavaj realny uplynuly cas.
                    if (lastPlayTickMs > 0L && player.isPlaying) {
                        val d = (nowMs - lastPlayTickMs).coerceIn(0L, 3000L)
                        posTimeMs = (posTimeMs + d).coerceIn(0L, curBar)
                    }
                    // zrkadli playhead do Activity (pre spolahlive znovu-otvorenie in-progress streamu)
                    if (recordingLive) onPlayheadMs(posTimeMs)
                }
                lastPlayTickMs = nowMs
                // Koniec dostupnych dat in-progress nahravky (EOF) riesi reopenDvrLive (znovu
                // otvori stream a pokracuje do novsich dat). Seek clamp (45 s) drzi playhead
                // bezpecne za zivou hranou, takze pri normalnom prehravani sa na EOF nenarazi.
                // priebezne ukladaj poziciu (kazdych ~5s) - z prehravacich hodin (spolahlive)
                sinceSave++
                if (sinceSave >= 5 && !askResume) {
                    sinceSave = 0
                    if (posTimeMs > 1000L && curLen > 0 && dvrUuid != null && serverId != null) {
                        WatchProgress.save(ctx, serverId, dvrUuid, posTimeMs.coerceAtMost(curLen), curLen)
                    }
                }
                kotlinx.coroutines.delay(1000)
            }
        }
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

    // Auto-vyber audio stopy po nacitani: 1) zapamatana pre kanal, 2) jazykove
    // priority (AD/narrated stopy preskakujeme), 3) fallback mimo AD stopy.
    // M378: kluc = liveChannelUuid, nech vyber prebehne aj pri prepnuti kanala
    // v ramci prehravaca (predtym LaunchedEffect(Unit) bezal len raz).
    LaunchedEffect(liveChannelUuid) {
        repeat(30) {
            kotlinx.coroutines.delay(500)
            val real = player.audioTracks?.filter { it.id >= 0 } ?: emptyList()
            if (real.size >= 2) {
                val remembered = if (liveChannelUuid != null && serverId != null)
                    ChannelPrefs.getLastAudio(ctx, serverId, liveChannelUuid) else ""
                if (remembered.isNotBlank()) {
                    val m = real.firstOrNull { (it.name ?: "") == remembered }
                        ?: real.firstOrNull { (it.name ?: "").contains(remembered) }
                    if (m != null) {
                        if (player.audioTrack != m.id) player.audioTrack = m.id
                        return@LaunchedEffect
                    }
                }
                for (code in preferredAudio) {
                    // M378: v ramci jazyka preferuj beznu stopu pred AD/narrated
                    // (obe casto nesu rovnaky jazykovy kod, napr. "English" a
                    // "English AD" — predtym vyhrala ta, co bola v zozname prva)
                    val cands = real.filter { AudioPref.matches(it.name ?: "", code) }
                    val m = cands.firstOrNull { !AudioPref.isDescriptive(it.name ?: "") }
                        ?: cands.firstOrNull()
                    if (m != null) {
                        if (player.audioTrack != m.id) player.audioTrack = m.id
                        return@LaunchedEffect
                    }
                }
                // M378: ziadna jazykova zhoda — ak by default (aktualna stopa)
                // bol AD/narrated, prepni na prvu beznu stopu. Riesi kanaly,
                // kde je AD stopa prva v poradi a vyhrala by ako default.
                val curName = real.firstOrNull { it.id == player.audioTrack }?.name ?: ""
                if (AudioPref.isDescriptive(curName)) {
                    val plain = real.firstOrNull { !AudioPref.isDescriptive(it.name ?: "") }
                    if (plain != null && player.audioTrack != plain.id) player.audioTrack = plain.id
                }
                return@LaunchedEffect
            }
        }
    }

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
    androidx.activity.compose.BackHandler(
        enabled = !pipSupported && !seekable && !controlsVisible && menu == null
                  && !showChannelList && !showOptions && !returnLiveOnBack && !showInfo
    ) { onRequestExit() }

    // M266: predbezne nacitanie EPG (now/next) na pozadi kratko po starte prehravaca,
    // aby prvy otvoreny zoznam kanalov mal data uz z cache (epgUpcomingState) bez sietoveho
    // cakania. Bezi na IO (refreshOverlayEpg), stream nabehne prvy a UI sa neblokuje.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1200)
        onPrefetchEpg()   // M274: refresh len ak je cache prazdna/zastarana
    }

    // Kym je zoznam kanalov otvoreny, obnovuj EPG (now/next) aby relacie
    // postupne prechadzali na dalsie
    LaunchedEffect(showChannelList) {
        if (showChannelList) {
            onRefreshEpgInitial()   // M270: prve nacitanie so spinnerom (len ak je cache prazdna/zastarana)
            while (true) {
                kotlinx.coroutines.delay(60_000)
                onRefreshEpg()      // periodicky refresh bez spinnera
            }
        }
    }

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
                        if (mode == 0 && !showChannelList && !inBar && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
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
        AndroidView(
            modifier = if (inPreview) {
                val r = previewRect!!
                Modifier
                    .absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            } else Modifier.fillMaxSize(),
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
                layout.post { onStart() }
                layout
            }
        )

        // Audio-only (rozhlas): namiesto ciernej zobraz vycentrovane logo
        if (!hasVideo) {
            val ctxLogo = androidx.compose.ui.platform.LocalContext.current
            val cfgLogo = androidx.compose.ui.platform.LocalConfiguration.current
            val logoLoader = remember(server?.id) { PiconImageLoader.get(ctxLogo, server) }
            // Ked je otvoreny zoznam kanalov (TV), presun logo do nahladoveho obdlznika;
            // inak vycentrovane na celu obrazovku.
            val side = if (inPreview) {
                with(density) { (minOf(previewRect!!.width, previewRect!!.height) * 0.55f).toDp() }
            } else (minOf(cfgLogo.screenWidthDp, cfgLogo.screenHeightDp) * 0.42f).dp
            val logoBoxMod = if (inPreview) {
                val r = previewRect!!
                Modifier
                    .absoluteOffset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() })
            } else Modifier.fillMaxSize()
            Box(logoBoxMod, contentAlignment = Alignment.Center) {
                var logoOk by remember(centerLogoUrl) {
                    androidx.compose.runtime.mutableStateOf(centerLogoUrl != null)
                }
                if (centerLogoUrl != null && logoOk) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctxLogo).data(centerLogoUrl).build(),
                        contentDescription = null,
                        imageLoader = logoLoader,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        onState = { st ->
                            if (st is coil.compose.AsyncImagePainter.State.Error) logoOk = false
                        },
                        modifier = Modifier.size(side)
                    )
                } else {
                    // Predvolena grafika radia (ked stanica nema picon alebo sa nenacita)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .size(side * 0.7f)
                                .clip(RoundedCornerShape(side.value.dp * 0.12f))
                                .background(
                                    androidx.compose.ui.graphics.Brush.verticalGradient(
                                        listOf(playerTrack(), playerTrack())
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Default.Radio,
                                contentDescription = null,
                                tint = playerFg(),
                                modifier = Modifier.size(side * 0.42f)
                            )
                        }
                    }
                }
            }
        }

        // indikator opätovného pripájania (vypadok siete pri zivom vysielani)
        if (reconnecting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .background(Color(0xCC000000), RoundedCornerShape(14.dp))
                        .padding(horizontal = 28.dp, vertical = 22.dp)
                ) {
                    androidx.compose.material3.CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.reconnecting),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        // koliesko v strede pocas pretacania timeshiftu (resync)
        if (seeking && !reconnecting) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(color = playerFg())
            }
        }

        // YouTube-style hint pri dvojkliku (skok o 10 s), na strane kliknutia, akumuluje sa
        if (seekHint != 0) {
            val fwd = seekHint > 0
            val label = if (fwd) "+$seekHint  ›" else "‹  $seekHint"
            Box(
                Modifier.fillMaxSize().padding(horizontal = 44.dp),
                contentAlignment = if (fwd) Alignment.CenterEnd else Alignment.CenterStart
            ) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color(0xB3000000),
                            blurRadius = 14f
                        )
                    )
                )
            }
        }

        // MX Player overlaye: hlasitost / jas (vystredene), seek-scrub (hore v strede)
        if (volPctState >= 0 || brightPctState >= 0) {
            val isVol = volPctState >= 0
            val pct = if (isVol) volPctState else brightPctState
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                        .background(Color(0xAA000000))
                        .padding(horizontal = 22.dp, vertical = 16.dp)
                ) {
                    val gLabel = androidx.compose.ui.res.stringResource(
                        if (isVol) R.string.player_volume else R.string.player_brightness
                    )
                    Text(
                        "$gLabel  $pct%",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { pct / 100f },
                        modifier = Modifier.width(170.dp).padding(top = 10.dp),
                        color = playerAccent(),
                        trackColor = Color(0x55FFFFFF)
                    )
                }
            }
        }
        if (scrubSecState != Int.MIN_VALUE) {
            val s = scrubSecState
            val a = kotlin.math.abs(s)
            val mm = a / 60
            val ss = a % 60
            val core = if (mm > 0) "$mm:" + ss.toString().padStart(2, '0') else "${ss}s"
            val label = (if (s >= 0) "+" else "\u2212") + core
            Box(
                Modifier.fillMaxSize().padding(top = 56.dp),
                contentAlignment = Alignment.TopCenter
            ) {
                Text(
                    label,
                    color = Color.White,
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.SemiBold,
                        shadow = androidx.compose.ui.graphics.Shadow(
                            color = Color(0xB3000000),
                            blurRadius = 14f
                        )
                    )
                )
            }
        }

        // prekrytie s prave zadavanym cislom kanala
        if (numberEntry.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(playerScrim())
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(numberEntry, color = playerFg(), fontSize = 48.sp)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                val order = playerControlOrder(onPrevChannel != null, seekable, pipButton, timeshiftEngaged, profileSwitch)
                // fokusove zvyraznenie len na TV (D-pad); na telefone (dotyk) ziadne "vybrate" tlacidlo
                val isTvDevice = remember {
                    val um = ctx.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
                    um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                }
                val selCtrl = if (isTvDevice) order.getOrNull(controlNavIndex) else null
                val curCh = liveChannels.getOrNull(liveCurrentIndex)
                val infoLoader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
                fun clock(sec: Long): String =
                    if (sec <= 0) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                        .format(java.util.Date(sec * 1000))
                val dateTime = java.text.SimpleDateFormat("EEE d. M., HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(liveNowSec * 1000))
                val hasNow = !seekable && progStart > 0 && progStop > progStart
                val total = (progStop - progStart).coerceAtLeast(1)
                val elapsed = (liveNowSec - progStart).coerceIn(0, total)
                val fracNow = elapsed.toFloat() / total.toFloat()
                val remainMin = if (hasNow) ((progStop - liveNowSec) / 60).coerceAtLeast(0) else 0
                // skalovanie podla rozlisenia boxu (kompaktny, citatelny pruh)
                // Meraj skutocnu sirku okna (BoxWithConstraints), nie Configuration.screenWidthDp —
                // ten na niektorych zariadeniach hlasi zlu hodnotu (kompaktny layout na sirku).
                // maxWidth odraza realne pixely okna, takze siroke okno vzdy dostane landscape layout.
                BoxWithConstraints(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                ) {
                    val k = (maxWidth.value / 640f).coerceIn(0.9f, 1.25f)
                    val portrait = maxWidth < 600.dp

                    // Jeden spolocny info+ovladaci pruh dole
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(playerScrim())
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                    Row(verticalAlignment = Alignment.Top) {
                        // cislo + logo + nazov kanala (len live; pri DVR netreba)
                        if (!seekable) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width((76 * k).dp)
                        ) {
                            if ((curCh?.number ?: 0) > 0) {
                                Text(
                                    "${curCh?.number}",
                                    color = playerFg(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (24 * k).sp
                                )
                            }
                            if (curCh?.piconUrl != null) {
                                Spacer(Modifier.height(2.dp))
                                AsyncImage(
                                    model = ImageRequest.Builder(ctx).data(curCh.piconUrl).build(),
                                    contentDescription = null,
                                    imageLoader = infoLoader,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.size((56 * k).dp, (32 * k).dp)
                                )
                            }
                            Text(
                                title,
                                color = playerFgDim(),
                                fontSize = (11 * k).sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        }
                        // popis relacie: nazov, cas, priebeh, popis, dalej
                        Column(Modifier.weight(1f)) {
                            val headline = if (seekable) title else progTitle
                            Row(verticalAlignment = Alignment.Top) {
                                Text(
                                    headline,
                                    color = playerFg(),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (16 * k).sp,
                                    maxLines = if (seekable) 2 else 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        dateTime,
                                        color = playerFgDim(),
                                        fontSize = (12 * k).sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    if (sleepLeftMin > 0) {
                                        Text(
                                            "\u23F2 ${sleepLeftMin} min",
                                            color = Color(0xCC8AB4F8),
                                            fontSize = (12 * k).sp,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                            if (hasNow) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        clock(progStart) + " \u2013 " + clock(progStop),
                                        color = playerFgDim(),
                                        fontSize = (12 * k).sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { fracNow },
                                        modifier = Modifier
                                            .width((88 * k).dp)
                                            .padding(horizontal = 8.dp),
                                        trackColor = playerTrack()
                                    )
                                    Text(
                                        "$remainMin min",
                                        color = playerFgDim(),
                                        fontSize = (12 * k).sp, maxLines = 1, softWrap = false
                                    )
                                    if (timeshiftOffsetMs > 0L) {
                                        Spacer(Modifier.width(8.dp))
                                        Text(
                                            "\u2212" + fmtMs(timeshiftOffsetMs),
                                            color = androidx.compose.ui.graphics.Color(0xFFFF3B30),
                                            fontWeight = FontWeight.Bold,
                                            fontSize = (12 * k).sp, maxLines = 1, softWrap = false
                                        )
                                    }
                                }
                            }
                            if (progDesc.isNotBlank()) {
                                Text(
                                    progDesc,
                                    color = playerFgDim(),
                                    fontSize = (12 * k).sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            if (nextTitle.isNotBlank()) {
                                Text(
                                    clock(nextStart) + " \u2013 " + clock(nextStop) + "  " + nextTitle,
                                    color = playerFgFaint(),
                                    fontSize = (12 * k).sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                    }
                    Spacer(Modifier.height((4 * k).dp))
                    // DVR: pretacacia lista (zvyraznena pri vybere "seek")
                    if (seekable && barLengthMs > 0) {
                        val seekFocused = selCtrl == "seek"
                        val frac = when {
                            seekFocused -> scrubFrac
                            dragging -> dragValue
                            else -> (posTimeMs.toFloat() / barLengthMs).coerceIn(0f, 1f)
                        }
                        // Lava strana: pocas tahania/vyberu cielovy cas, inak skutocny cas prehravania
                        val cur = if (dragging || seekFocused) (frac * barLengthMs).toLong() else posTimeMs
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                                .then(
                                    if (seekFocused) Modifier.border(
                                        2.dp, playerFg(), RoundedCornerShape(8.dp)
                                    ) else Modifier
                                )
                                .padding(horizontal = 4.dp)
                        ) {
                            Text(fmtMs(cur), color = playerFg(),
                                style = MaterialTheme.typography.bodySmall)
                            Box(
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                androidx.compose.material3.Slider(
                                    value = frac.coerceIn(0f, 1f),
                                    onValueChange = { dragging = true; dragValue = it },
                                    onValueChangeFinished = {
                                        // ciel v case relacie (v ramci dosiahnutelneho rozsahu)
                                        val progMs = (dragValue.coerceIn(0f, 1f) * barLengthMs).toLong()
                                            .coerceIn(0L, barLengthMs)
                                        posTimeMs = progMs               // okamzita odozva UI
                                        posFraction = if (lengthMs > 0)
                                            ((recordingOffsetMs + progMs).toFloat() /
                                                (recordingOffsetMs + lengthMs)).coerceIn(0f, 1f)
                                        else 0f
                                        dragging = false
                                        // skutocny seek prebudovanim streamu (feeder byte-restart /
                                        // direct :start-time) - player.position na pipe nefunguje
                                        onSeekToMs(progMs)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                // Znacky relacie: cervena = zaciatok (koniec okraja pred),
                                // svetlejsia = koniec relacie (zaciatok okraja po).
                                if (progStartFrac > 0.002f || progStopFrac < 0.998f) {
                                    androidx.compose.foundation.Canvas(
                                        modifier = Modifier.matchParentSize()
                                    ) {
                                        val thumb = 10.dp.toPx()
                                        val usable = (size.width - 2 * thumb).coerceAtLeast(0f)
                                        val w = 3.dp.toPx()
                                        // vyska presne cez listu (~16 dp track), vystredene
                                        val half = 8.dp.toPx()
                                        val cy = size.height / 2f
                                        fun tick(f: Float, c: Color) {
                                            val x = thumb + f.coerceIn(0f, 1f) * usable
                                            drawLine(c, Offset(x, cy - half), Offset(x, cy + half), w)
                                        }
                                        if (progStopFrac < 0.998f)
                                            tick(progStopFrac, Color(0x80FF5252))
                                        if (progStartFrac > 0.002f)
                                            tick(progStartFrac, Color(0xFFFF1744))
                                    }
                                }
                            }
                            Text(fmtMs(barLengthMs), color = playerFg(),
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Spacer(Modifier.height((6 * k).dp))
                    // Tlacidla: zavriet, zoznam, prev, play, next, audio, titulky, sw
                    val bk = if (portrait) 0.95f else (0.78f * k)
                    // tlacidlo zamku otacania ma zmysel len ked je orientacia automaticka;
                    // pri pevnej orientacii (na vysku/sirku) ho skry
                    val lockVisible = remember {
                        pipSupported && OrientationPref.get(ctx) == OrientationPref.AUTO
                    }
                    fun has(id: String) = order.contains(id)
                    // jedno tlacidlo podla id (zachytava okolity stav)
                    @Composable
                    fun barCtrl(c: String) {
                        when (c) {
                            "close" -> CircleButton(Icons.AutoMirrored.Filled.ArrowBack, selected = selCtrl == "close", scale = bk, onClick = onClose)
                            "list" -> CircleButton(
                                icon = Icons.AutoMirrored.Filled.List, selected = selCtrl == "list", scale = bk,
                                onClick = { showChannelList = true; controlsVisible = false }
                            )
                            "prev" -> if (onPrevChannel != null) CircleButton(
                                icon = Icons.Default.SkipPrevious, selected = selCtrl == "prev", scale = bk, onClick = onPrevChannel
                            )
                            "play" -> PlayPauseButton(
                                isPlaying = isPlaying,
                                selected = selCtrl == "play",
                                scale = bk,
                                onClick = onTogglePlay
                            )
                            "tsrew" -> CircleButton(
                                icon = Icons.Default.Replay30, selected = selCtrl == "tsrew", scale = bk, onClick = onSkipBack
                            )
                            "tsff" -> CircleButton(
                                icon = Icons.Default.Forward30, selected = selCtrl == "tsff", scale = bk, onClick = onSkipFwd
                            )
                            "next" -> if (onNextChannel != null) CircleButton(
                                icon = Icons.Default.SkipNext, selected = selCtrl == "next", scale = bk, onClick = onNextChannel
                            )
                            "epg" -> CircleButton(
                                icon = Icons.Default.GridView, selected = selCtrl == "epg", scale = bk, onClick = onOpenEpg
                            )
                            "pip" -> CircleButton(
                                icon = Icons.Default.PictureInPictureAlt, selected = selCtrl == "pip", scale = bk, onClick = onEnterPip
                            )
                            "info" -> CircleButton(
                                icon = Icons.Default.Info, selected = selCtrl == "info", scale = bk,
                                onClick = { showInfo = !showInfo }
                            )
                            "sleep" -> CircleButton(
                                icon = Icons.Default.Timer, selected = selCtrl == "sleep", scale = bk,
                                onClick = onOpenSleep
                            )
                            "audio" -> CircleButton(
                                icon = Icons.Default.MusicNote, selected = selCtrl == "audio", scale = bk,
                                onClick = { menu = if (menu == "audio") null else "audio" }
                            )
                            "subs" -> CircleButton(
                                icon = Icons.Default.ClosedCaption, selected = selCtrl == "subs", scale = bk,
                                onClick = { menu = if (menu == "spu") null else "spu" }
                            )
                            "profile" -> CircleButton(
                                icon = Icons.Default.Tune, selected = selCtrl == "profile", scale = bk,
                                onClick = { menu = if (menu == "profile") null else "profile" }
                            )
                            "lock" -> CircleButton(
                                icon = Icons.Default.Lock, selected = orientationLocked, scale = bk,
                                onClick = {
                                    orientationLocked = !orientationLocked
                                    onOrientationLockChange(orientationLocked)
                                }
                            )
                        }
                    }
                    val gap = Arrangement.spacedBy((8 * k).dp)
                    if (isModernUi() && !isTvDevice) {
                        // Moderny rezim (telefon): 3 hlavne tlacidla + pas s popiskami;
                        // zvysne funkcie su vo vysuvacom paneli "Viac" (showMoreSheet).
                        ModernPhoneControls(
                            isPlaying = isPlaying,
                            timeshiftEngaged = timeshiftEngaged,
                            hasPrev = has("prev") && onPrevChannel != null,
                            hasNext = has("next") && onNextChannel != null,
                            hasList = has("list") && liveChannels.isNotEmpty(),
                            hasEpg = has("epg"),
                            onClose = onClose,
                            onAudio = { menu = "audio" },
                            onList = { showChannelList = true; controlsVisible = false },
                            onEpg = onOpenEpg,
                            onTogglePlay = onTogglePlay,
                            onPrev = onPrevChannel,
                            onNext = onNextChannel,
                            onSkipBack = onSkipBack,
                            onSkipFwd = onSkipFwd,
                            onMore = { showMoreSheet = true },
                        )
                    } else if (portrait) {
                        // PORTRET: tlacidla vo viacerych radoch a vacsie (jeden rad bol nepouzitelne maly).
                        // Rad 1: navigacia/okno, Rad 2: prehravanie (play v strede), Rad 3: zvuk/extra.
                        val rowGap = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(horizontalArrangement = rowGap, verticalAlignment = Alignment.CenterVertically) {
                                barCtrl("close")
                                if (has("pip")) barCtrl("pip")
                                if (has("list") && liveChannels.isNotEmpty()) barCtrl("list")
                                if (has("epg")) barCtrl("epg")
                                barCtrl("info")
                            }
                            Row(horizontalArrangement = rowGap, verticalAlignment = Alignment.CenterVertically) {
                                if (timeshiftEngaged) barCtrl("tsrew")
                                if (has("prev")) barCtrl("prev")
                                barCtrl("play")
                                if (has("next")) barCtrl("next")
                                if (timeshiftEngaged) barCtrl("tsff")
                            }
                            Row(horizontalArrangement = rowGap, verticalAlignment = Alignment.CenterVertically) {
                                barCtrl("audio")
                                barCtrl("subs")
                                if (has("profile")) barCtrl("profile")
                                barCtrl("sleep")
                                if (lockVisible) barCtrl("lock")
                            }
                        }
                    } else
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // vlavo: zavriet, zoznam, EPG
                        Row(
                            horizontalArrangement = gap,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            barCtrl("close")
                            if (has("pip")) barCtrl("pip")
                            if (has("list") && liveChannels.isNotEmpty()) barCtrl("list")
                            if (has("epg")) barCtrl("epg")
                        }
                        // stred: prepinanie + play/stop
                        Row(
                            horizontalArrangement = gap,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (timeshiftEngaged) barCtrl("tsrew")
                            if (has("prev")) barCtrl("prev")
                            barCtrl("play")
                            if (has("next")) barCtrl("next")
                            if (timeshiftEngaged) barCtrl("tsff")
                        }
                        // vpravo: audio, titulky, info, SW
                        Row(
                            horizontalArrangement = gap,
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f),
                            // zarovnaj k pravej hrane
                        ) {
                            Spacer(Modifier.weight(1f))
                            barCtrl("audio")
                            barCtrl("subs")
                            if (has("profile")) barCtrl("profile")
                            barCtrl("sleep")
                            barCtrl("info")
                            if (lockVisible) barCtrl("lock")
                        }
                    }
                }
                }
            }
        }

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

        // Info okno: detail prave beziacej relacie (INFO kláves / tlacidlo)
        if (showInfo) {
            androidx.activity.compose.BackHandler { showInfo = false }
            val clk: (Long) -> String = { sec ->
                if (sec <= 0) "" else java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(sec * 1000))
            }
            val tRange = if (progStart > 0 && progStop > progStart)
                clk(progStart) + " \u2013 " + clk(progStop) else ""
            Box(
                Modifier
                    .fillMaxSize()
                    .background(playerScrim())
                    .clickable { showInfo = false },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Surface(
                    color = playerScrim(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(0.72f).widthIn(max = 560.dp)
                ) {
                    Column(
                        Modifier
                            .padding(28.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState())
                    ) {
                        // hlavicka kanala (len pri zivom vysielani)
                        val infoCh = liveChannels.getOrNull(liveCurrentIndex)
                        if (infoCh != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (infoCh.number > 0) {
                                    Text(
                                        "${infoCh.number}",
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 18.sp
                                    )
                                    Spacer(Modifier.width(10.dp))
                                }
                                Text(
                                    infoCh.name,
                                    color = playerFgDim(),
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                        Text(
                            progTitle.ifBlank { title },
                            color = playerFg(),
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                        if (tRange.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(tRange, color = playerFgDim(), fontSize = 15.sp)
                        }
                        // priebeh + zostavajuci cas (len zive vysielanie)
                        if (!seekable && progStart > 0 && progStop > progStart) {
                            val totalI = (progStop - progStart).coerceAtLeast(1)
                            val fracI = ((liveNowSec - progStart).toFloat() / totalI.toFloat())
                                .coerceIn(0f, 1f)
                            val remainI = ((progStop - liveNowSec) / 60).coerceAtLeast(0)
                            Spacer(Modifier.height(12.dp))
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { fracI },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                trackColor = playerTrack()
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.time_remaining, remainI), color = playerFgFaint(), fontSize = 13.sp)
                        }
                        if (progDesc.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Text(progDesc, color = playerFgDim(), fontSize = 16.sp, lineHeight = 22.sp)
                        }
                        if (nextTitle.isNotBlank()) {
                            Spacer(Modifier.height(16.dp))
                            val nr = when {
                                nextStart > 0 && nextStop > nextStart ->
                                    clk(nextStart) + " \u2013 " + clk(nextStop) + "  "
                                nextStart > 0 -> clk(nextStart) + "  "
                                else -> ""
                            }
                            Text(
                                "Nasleduje: " + nr + nextTitle,
                                color = playerFgFaint(),
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }
        }

        // Overlay: zoznam kanalov priamo v prehravaci (vysuva sa zhora podla listFrac) — TELEFON
        if ((showChannelList || listFrac > 0.001f) && !isTvGest && liveChannels.isNotEmpty()) {
            val loader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = liveCurrentIndex.coerceAtLeast(0)
            )
            // efektivny vyber: pri D-pad navigacii navIndex, inak aktualny kanal
            val sel = if (channelNavIndex >= 0) channelNavIndex else liveCurrentIndex
            LaunchedEffect(channelNavIndex) {
                val i = channelNavIndex
                if (i in liveChannels.indices) {
                    val vis = listState.layoutInfo.visibleItemsInfo
                    val first = vis.firstOrNull()?.index ?: 0
                    val last = vis.lastOrNull()?.index ?: 0
                    // skoc len ked je ciel mimo obrazovky — okamzite, bez pretacania cez vsetky polozky
                    if (vis.isEmpty() || i < first || i > last) listState.scrollToItem(i)
                }
            }
            BoxWithConstraints(Modifier.fillMaxSize()) {
              val fullH = maxHeight
              Column(
                Modifier
                    .fillMaxWidth()
                    .height(fullH * listFrac.coerceIn(0f, 1f))   // rastie zhora nadol
                    .align(Alignment.TopStart)
                    .clipToBounds()
                    .background(playerScrim())
              ) {
                Column(Modifier.fillMaxWidth().height(fullH)) {   // obsah v plnej vyske, klipovany zhora
                // hlavicka = uchyt: tah hore zatvori (nebrani rolovaniu zoznamu), klik tiez zatvori
                Column(
                    Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            var dyh = 0f
                            detectVerticalDragGestures(
                                onDragStart = { dyh = 0f },
                                onDragEnd = { if (dyh < -60f) showChannelList = false }
                            ) { _, amount -> dyh += amount }
                        }
                        .clickable { showChannelList = false }
                ) {
                    Box(
                        Modifier
                            .padding(top = 8.dp)
                            .align(Alignment.CenterHorizontally)
                            .size(width = 40.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(playerFgDim())
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("\u2039", color = playerFg(), fontSize = 24.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.player_channel_list),
                            color = playerFg(),
                            style = MaterialTheme.typography.titleMedium
                        )
                        if (epgLoading) {
                            Spacer(Modifier.weight(1f))
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = playerAccent(),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .pointerInput(Unit) {
                                // Spodnych ~10% je vyhradenych na zatvaranie: tah zdola hore tam
                                // zoznam zatvori (namiesto rolovania). Klik na kanal aj rolovanie
                                // inde ostavaju zachovane - gesto citame na Initial passe a berieme
                                // ho LEN ked tah zacne v spodnej zone a ide nahor.
                                awaitPointerEventScope {
                                    while (true) {
                                        val down = awaitPointerEvent(
                                            androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                        ).changes.firstOrNull() ?: continue
                                        if (!(down.pressed && !down.previousPressed)) continue
                                        if (down.position.y < size.height * 0.90f) continue
                                        val pid = down.id
                                        var totalDy = 0f
                                        var decided = false
                                        var closing = false
                                        while (true) {
                                            val ev = awaitPointerEvent(
                                                androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                            )
                                            val ch = ev.changes.firstOrNull { it.id == pid } ?: break
                                            if (!ch.pressed) break
                                            totalDy += ch.position.y - ch.previousPosition.y
                                            if (!decided && kotlin.math.abs(totalDy) > 12f) {
                                                decided = true
                                                closing = totalDy < 0f   // tah nahor -> zatvarame
                                            }
                                            if (closing) ch.consume()     // zober gesto LazyColumnu
                                        }
                                        if (closing && totalDy < -60f) showChannelList = false
                                    }
                                }
                            }
                    ) {
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = listFrac >= 0.999f,   // rolovat az ked je zoznam uplne otvoreny
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(liveChannels) { idx, ch ->
                            val selected = idx == sel
                            val locked = remember(lockTick, ch.uuid, serverId) {
                                ParentalLock.isChannelLocked(ctx, serverId, ch.uuid)
                            }
                            if (isModernUi()) {
                                // moderny riadok: ina stavba (picon velky, "Dalej:", minuty) — zdielany komponent
                                ModernPlayerChannelRow(
                                    ch = ch,
                                    selected = selected,
                                    locked = locked,
                                    nowSec = liveNowSec,
                                    imageLoader = loader,
                                    onClick = { onSelectChannel(idx); showChannelList = false },
                                    onLongClick = { onChannelLongPress(idx) },
                                )
                            } else {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selected) Color(0x553B82F6) else Color.Transparent)
                                    .combinedClickable(
                                        onClick = { onSelectChannel(idx); showChannelList = false },
                                        onLongClick = { onChannelLongPress(idx) }
                                    )
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    if (ch.number > 0) ch.number.toString() else "",
                                    color = if (selected) playerFg() else Color(0xFF6699FF),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(34.dp)
                                )
                                Box(
                                    Modifier
                                        .size(48.dp, 40.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(piconBackground()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (ch.piconUrl != null) {
                                        val req = remember(ch.piconUrl) {
                                            ImageRequest.Builder(ctx).data(ch.piconUrl).size(120).build()
                                        }
                                        AsyncImage(
                                            model = req,
                                            contentDescription = null,
                                            imageLoader = loader,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize().padding(2.dp)
                                        )
                                    } else {
                                        Text(
                                            ch.name.take(3).uppercase(),
                                            color = playerFg(),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            ch.name,
                                            color = playerFg(),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f, fill = false)
                                        )
                                        if (ch.recording) {
                                            Spacer(Modifier.width(6.dp))
                                            Box(
                                                Modifier.size(8.dp)
                                                    .clip(androidx.compose.foundation.shape.CircleShape)
                                                    .background(Color(0xFFE53935))
                                            )
                                        }
                                        if (locked) {
                                            Spacer(Modifier.width(6.dp))
                                            androidx.compose.material3.Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Filled.Lock,
                                                contentDescription = null,
                                                tint = playerFgDim(),
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                    if (ch.nowTitle.isNotBlank()) {
                                        Text(
                                            ch.nowTitle,
                                            color = playerFgDim(),
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (ch.nowStop > ch.nowStart) {
                                            val total = (ch.nowStop - ch.nowStart).coerceAtLeast(1)
                                            val frac = (liveNowSec - ch.nowStart)
                                                .coerceIn(0, total).toFloat() / total
                                            androidx.compose.material3.LinearProgressIndicator(
                                                progress = { frac },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 4.dp),
                                                trackColor = playerTrack()
                                            )
                                        }
                                    }
                                }
                            }
                            }
                        }
                    }
                    }
                }
              }
            }
        }
        if (showChannelList && isTvGest && liveChannels.isNotEmpty()) {
            val loaderT = remember(server?.id) { PiconImageLoader.get(ctx, server) }
            val selT = channelNavIndex.coerceIn(0, liveChannels.size - 1)
            // strankovanie po 7: zobraz presne aktualnu sedmicku, po prekroceni sa preklopi dalsia
            val pageSizeT = 7
            val pageStartT = (selT / pageSizeT) * pageSizeT
            val pageItemsT = liveChannels.drop(pageStartT).take(pageSizeT)
            // pravy panel (EPG, nahlad, relacie) sleduje HRANY kanal — meni sa az po prepnuti (OK)
            val detT = liveCurrentIndex.coerceIn(0, liveChannels.size - 1)
            val detUuid = liveChannels.getOrNull(detT)?.uuid
            var epgT by remember { mutableStateOf<List<sk.tvhclient.shared.model.EpgEvent>>(emptyList()) }
            // M370-fix: kluc na UUID (nie index) — pri zmene tagu ostava rovnaky kanal,
            // takze sa EPG zbytocne nenacitava znova.
            LaunchedEffect(detUuid) {
                val uuid = detUuid ?: return@LaunchedEffect
                epgT = emptyList()
                onLoadChannelEpg(uuid) { list -> epgT = list }
            }
            val nowT = liveNowSec
            val curT = epgT.firstOrNull { it.start <= nowT && nowT < it.stop }
            val nextT = epgT.filter { it.start >= nowT }.sortedBy { it.start }.take(4)
            fun hhmm(s: Long): String =
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(s * 1000))
            val dateStr = java.text.SimpleDateFormat("EEEE d. MMMM", java.util.Locale.getDefault())
                .format(java.util.Date(nowT * 1000)).replaceFirstChar { it.uppercase() }
            val accentC = playerAccent()
            val borderC = playerBorder()
            val cardC = playerCard()
            val selTintC = playerSelTint()

            val scrimC = playerScrim()
            // M370: fokus pre textove pole hladania (ziadame pri otvoreni/navrate na pole)
            val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
            val keyboardCtrl = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
            LaunchedEffect(searchFocusSignal, searchFieldFocused, searchActive) {
                if (searchActive && searchFieldFocused) {
                    runCatching { searchFocus.requestFocus() }
                    kotlinx.coroutines.delay(60)
                    runCatching { keyboardCtrl?.show() }
                }
            }
            Column(
                Modifier
                    .fillMaxSize()
                    // M266: offscreen buffer je drahy a treba ho LEN pre BlendMode.Clear
                    // (vyrez nahladu). Bez nahladu ho nealokujeme -> svizne prve otvorenie.
                    .then(
                        if (inPreview)
                            Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        else Modifier
                    )
                    .drawBehind {
                        drawRect(scrimC)
                        val r = previewRect
                        if (inPreview && r != null)
                            drawRect(
                                androidx.compose.ui.graphics.Color.Transparent,
                                topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
                                size = Size(r.width, r.height),
                                blendMode = BlendMode.Clear
                            )
                    }
            ) {
                // horna lista: datum vlavo, hodiny vpravo
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(dateStr, color = playerFgDim(), style = MaterialTheme.typography.titleMedium)
                    if (searchActive) {
                        // M370: pole hladania (systemova klavesnica na TV)
                        Spacer(Modifier.width(14.dp))
                        androidx.compose.material3.OutlinedTextField(
                            value = searchQuery,
                            onValueChange = onSearchQueryChange,
                            singleLine = true,
                            placeholder = {
                                Text(androidx.compose.ui.res.stringResource(R.string.search_channels),
                                    color = playerFgDim())
                            },
                            leadingIcon = {
                                androidx.compose.material3.Icon(
                                    androidx.compose.material.icons.Icons.Default.Search, null,
                                    tint = playerFgDim())
                            },
                            modifier = Modifier.weight(1f).focusRequester(searchFocus)
                        )
                    } else {
                        // M369b: pilulka filtra skupiny v hornom pruhu (neukrojuje vysku zoznamu)
                        if (channelGroupLabel.isNotEmpty()) {
                            Spacer(Modifier.width(14.dp))
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (channelGroupPicker) selTintC else cardC)
                                    .then(
                                        if (channelGroupPicker)
                                            Modifier.border(2.dp, accentC, RoundedCornerShape(16.dp))
                                        else Modifier
                                    )
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (channelGroupPicker) {
                                    Text("\u2039", color = accentC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    channelGroupLabel,
                                    color = playerFg(),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (channelGroupPicker) {
                                    Spacer(Modifier.width(8.dp))
                                    Text("\u203A", color = accentC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            // M370-fix2: viditelna lupa vedla pilulky (hladanie sa otvori sipkou HORE)
                            Spacer(Modifier.width(10.dp))
                            androidx.compose.material3.Icon(
                                androidx.compose.material.icons.Icons.Default.Search,
                                contentDescription = null,
                                tint = if (channelGroupPicker) accentC else playerFgDim(),
                                modifier = Modifier.size(22.dp)
                            )
                            if (channelGroupPicker) {
                                Spacer(Modifier.width(6.dp))
                                Text("\u25B2 " + androidx.compose.ui.res.stringResource(R.string.search_channels),
                                    color = accentC, style = MaterialTheme.typography.labelMedium)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    if (epgLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = accentC,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(hhmm(nowT), color = playerFg(),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    // LAVA: zoznam kanalov (karty s ramikom)
                    Column(
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.46f)
                            .padding(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                      if (searchActive) {
                        // M370: vysledky hladania (napriec vsetkymi kanalmi)
                        val hits = searchHits
                        if (hits.isEmpty()) {
                            Text(
                                if (searchQuery.isBlank())
                                    androidx.compose.ui.res.stringResource(R.string.search_channels)
                                else androidx.compose.ui.res.stringResource(R.string.no_channels),
                                color = playerFgDim(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        } else {
                            val lsSearch = rememberLazyListState()
                            LaunchedEffect(searchNavIndex) {
                                runCatching { lsSearch.scrollToItem(searchNavIndex.coerceAtLeast(0)) }
                            }
                            LazyColumn(state = lsSearch, modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(hits, key = { _, c -> c.uuid }) { i, ch ->
                                    val selRow = i == searchNavIndex && !searchFieldFocused
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 4.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(if (selRow) selTintC else cardC)
                                            .border(1.dp, if (selRow) accentC else borderC, RoundedCornerShape(12.dp))
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(piconBackground()),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (ch.piconUrl != null) {
                                                AsyncImage(
                                                    model = remember(ch.piconUrl) { ImageRequest.Builder(ctx).data(ch.piconUrl).size(120).build() },
                                                    contentDescription = null, imageLoader = loaderT,
                                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                                    modifier = Modifier.fillMaxSize().padding(3.dp)
                                                )
                                            } else Text(ch.name.take(3).uppercase(), color = playerFg(), style = MaterialTheme.typography.labelMedium)
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f)) {
                                            Text(ch.name, color = playerFg(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                            if (ch.nowTitle.isNotBlank())
                                                Text(ch.nowTitle, color = playerFgDim(), style = MaterialTheme.typography.bodySmall,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(Modifier.width(8.dp))
                                        if (serverId != null && Favorites.isFav(ctx, serverId, ch.uuid)) {
                                            androidx.compose.material3.Icon(
                                                imageVector = androidx.compose.material.icons.Icons.Filled.Star,
                                                contentDescription = null,
                                                tint = Color(0xFFF2C14E),
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(if (ch.number > 0) ch.number.toString() else "", color = accentC,
                                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                    }
                                }
                            }
                        }
                      } else {
                        pageItemsT.forEachIndexed { localIdx, ch ->
                            val idx = pageStartT + localIdx
                            val selRow = idx == selT
                            val lockedRow = remember(lockTick, ch.uuid, serverId) {
                                ParentalLock.isChannelLocked(ctx, serverId, ch.uuid)
                            }
                            val favRow = serverId != null && Favorites.isFav(ctx, serverId, ch.uuid)
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selRow) selTintC else cardC)
                                    .border(1.dp, if (selRow) accentC else borderC, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(piconBackground()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (ch.piconUrl != null) {
                                        AsyncImage(
                                            model = remember(ch.piconUrl) { ImageRequest.Builder(ctx).data(ch.piconUrl).size(120).build() },
                                            contentDescription = null, imageLoader = loaderT,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize().padding(3.dp)
                                        )
                                    } else Text(ch.name.take(3).uppercase(), color = playerFg(), style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(ch.name, color = playerFg(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.weight(1f, fill = false))
                                        if (ch.recording) {
                                            Spacer(Modifier.width(6.dp))
                                            Box(Modifier.size(8.dp)
                                                .clip(androidx.compose.foundation.shape.CircleShape)
                                                .background(Color(0xFFE53935)))
                                        }
                                    }
                                    if (ch.nowTitle.isNotBlank())
                                        Text(ch.nowTitle, color = playerFgDim(), style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(8.dp))
                                if (favRow) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFF2C14E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                if (lockedRow) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = playerFgDim(),
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (ch.number > 0) ch.number.toString() else "", color = accentC,
                                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                      }
                    }
                    // PRAVA: detail vybraneho + nahlad hraneho + dalsie programy
                    Column(Modifier.fillMaxHeight().weight(1f).padding(horizontal = 22.dp, vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(
                                (curT?.title?.takeIf { it.isNotBlank() }) ?: liveChannels.getOrNull(detT)?.nowTitle ?: "",
                                color = playerFg(), style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold, maxLines = 1,
                                modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE)
                            )
                            if (liveChannels.getOrNull(detT)?.recording == true) {
                                Spacer(Modifier.width(8.dp))
                                androidx.compose.material3.Icon(
                                    Icons.Default.Voicemail, contentDescription = null,
                                    tint = Color(0xFFE53935),
                                    modifier = Modifier.size(22.dp).scale(scaleX = 1f, scaleY = -1f)
                                )
                            }
                        }
                        if (curT != null)
                            Text(hhmm(curT.start) + " – " + hhmm(curT.stop), color = accentC,
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp))
                        // nahlad: zive video hraneho kanala — VLC povrch presvita cez dieru v scrime
                        Box(
                            Modifier.padding(top = 12.dp).height(156.dp).aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(10.dp))
                                .onGloballyPositioned { c ->
                                    val p = c.positionInRoot()
                                    previewRect = androidx.compose.ui.geometry.Rect(
                                        p.x, p.y,
                                        p.x + c.size.width.toFloat(), p.y + c.size.height.toFloat()
                                    )
                                }
                                .border(1.dp, borderC, RoundedCornerShape(10.dp))
                        )
                        // popis: max 3 riadky, orezany
                        val desc = curT?.bestDescription ?: ""
                        if (desc.isNotBlank())
                            Text(desc, color = playerFgDim(), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 12.dp))
                        // relacie hned pod popisom (prirodzeny tok zhora)
                        if (nextT.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            nextT.forEach { ev ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(hhmm(ev.start), color = accentC,
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(58.dp))
                                    Text(ev.title, color = playerFg(), style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE))
                                }
                            }
                        }
                    }
                }
            }
        }

        // "Viac" menu modernej listy (M327): Kanaly / Casovac uspatia / Informacie
        if (modernMoreVisible) {
            // M383: zoznam idcok prichadza z Activity (moze obsahovat "profile")
            val moreLabels = modernMoreIdList.map { id ->
                when (id) {
                    "list" -> stringResource(R.string.tab_channels)
                    "sleep" -> stringResource(R.string.sleep_timer)
                    "profile" -> stringResource(R.string.field_profile)
                    else -> stringResource(R.string.pm_info)
                }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(playerScrimSoft())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { onMoreDismiss() },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .widthIn(min = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(playerScrim())
                        .padding(8.dp)
                ) {
                    Text(
                        stringResource(R.string.pm_more),
                        color = playerFg(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                    moreLabels.forEachIndexed { idx, label ->
                        // M385-fix: D-pad zvyraznenie len na TV (na telefone svietil vrchny riadok)
                        val sel = isTvGest && idx == modernMoreIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Color(0x553B82F6) else Color.Transparent)
                                .clickable { onMorePick(idx) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = playerFg())
                        }
                    }
                }
            }
        }

        // Vyber dlzky casovaca uspatia — vertikalne, navigacia z Activity
        if (showOptions) {
            val opts = listOf(
                stringResource(R.string.sleep_off),
                "15 min", "30 min", "45 min", "60 min", "90 min"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(playerScrimSoft())
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { showOptions = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .widthIn(min = 300.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(playerScrim())
                        .padding(8.dp)
                ) {
                    Text(
                        stringResource(R.string.sleep_timer),
                        color = playerFg(),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                    opts.forEachIndexed { idx, label ->
                        // M385-fix: D-pad zvyraznenie len na TV (na telefone svietil vrchny riadok)
                        val sel = isTvGest && idx == optionsNavIndex
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (sel) Color(0x553B82F6) else Color.Transparent)
                                .clickable { onOptionsSelect(idx) }
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(label, color = playerFg())
                        }
                    }
                }
            }
        }

        // Menu stop (audio / titulky)
        if (menu != null) {
            // trackListVersion: cita sa zamerne, nech sa zoznam prerenderuje, ked
            // libVLC prida stopu (DVB titulky / audio jazyky sa objavia az po starte).
            @Suppress("UNUSED_EXPRESSION") trackListVersion
            val htspSpu = menu == "spu" && onPickHtspSpu != null
            val items = when {
                menu == "profile" -> profileItems.mapIndexed { i, name -> TrackItem(i, name) }
                menu == "audio" -> player.audioTrackItems()
                htspSpu -> htspSpuItems ?: emptyList()
                else -> player.spuTrackItems()
            }
            val currentId = when {
                menu == "profile" -> profileItems.indexOf(currentProfile)
                menu == "audio" -> player.audioTrack
                htspSpu -> htspSpuCurrentId
                else -> player.spuTrack
            }
            TrackMenu(
                header = when (menu) {
                    "profile" -> stringResource(R.string.field_profile)
                    "audio" -> stringResource(R.string.track_audio)
                    else -> stringResource(R.string.track_subtitles)
                },
                items = items,
                currentId = currentId,
                allowOff = (menu == "spu"),  // titulky sa daju vypnut (-1)
                navIndex = trackNavIndex,
                onPick = { id ->
                    if (menu == "profile") {
                        profileItems.getOrNull(id)?.let { onPickProfile(it) }
                    } else if (menu == "audio") {
                        player.audioTrack = id
                        // zapamataj vyber pre kanal (live)
                        if (liveChannelUuid != null && serverId != null) {
                            val name = items.firstOrNull { it.id == id }?.name
                            if (!name.isNullOrBlank()) {
                                ChannelPrefs.setLastAudio(ctx, serverId, liveChannelUuid, name)
                            }
                        }
                    } else if (htspSpu) {
                        onPickHtspSpu!!(id)
                    } else {
                        player.spuTrack = id
                    }
                    menu = null
                },
                onDismiss = { menu = null }
            )
        }

        // Dialog: obnovit prehravanie od poslednej pozicie?
        if (askResume) {
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
                        .widthIn(min = 260.dp)
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
                        TextChip(androidx.compose.ui.res.stringResource(R.string.no),
                            selected = resumeSel == 0) {
                            askResume = false
                        }
                        TextChip(androidx.compose.ui.res.stringResource(R.string.yes),
                            selected = resumeSel == 1) {
                            pendingResumeMs = resumeMs
                            askResume = false
                        }
                    }
                }
            }
        }

        // Rodicovsky zamok: zadanie PIN (cislice z dialkoveho riesi Activity)
        if (pinPrompt) {
            Box(
                Modifier.fillMaxSize().background(Color(0x990B1220))
                    .pointerInput(Unit) { detectTapGestures { } },   // blokuj vstup do pozadia
                contentAlignment = Alignment.Center
            ) {
                // M273: kompaktny panel ako pri vytvarani PINu (PinDialogGrid), nie cela obrazovka.
                androidx.compose.material3.Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1B2433),
                    contentColor = Color.White,
                    tonalElevation = 6.dp
                ) {
                    Column(
                        Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.plock_enter),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(Modifier.height(20.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            repeat(4) { i ->
                                Box(
                                    Modifier.size(18.dp).clip(CircleShape).background(
                                        if (i < pinLen) MaterialTheme.colorScheme.primary else Color(0x44FFFFFF)
                                    )
                                )
                            }
                        }
                        if (pinError) {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                androidx.compose.ui.res.stringResource(R.string.plock_wrong),
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        // Ciselna mriezka: na telefone dotykova, na TV ovladana D-padom
                        // (zvyraznenie vybraneho klavesu) — pre ovladace bez ciselnych klaves.
                        val padKeys = listOf(
                            listOf("1", "2", "3"),
                            listOf("4", "5", "6"),
                            listOf("7", "8", "9"),
                            listOf("del", "0", "list")
                        )
                        padKeys.forEachIndexed { r, rowKeys ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                rowKeys.forEachIndexed { c, label ->
                                    val selected = isTvGest && r == pinGridRow && c == pinGridCol
                                    Box(
                                        Modifier.size(width = 64.dp, height = 44.dp)
                                            .clip(RoundedCornerShape(22.dp))
                                            .background(
                                                if (selected) MaterialTheme.colorScheme.primary
                                                else Color(0x22FFFFFF)
                                            )
                                            .border(
                                                2.dp,
                                                if (selected) Color.White else Color(0x55FFFFFF),
                                                RoundedCornerShape(22.dp)
                                            )
                                            .clickable {
                                                when (label) {
                                                    "del" -> onPinBack()
                                                    "list" -> onPinOpenList()
                                                    else -> onPinDigit(label.toInt())
                                                }
                                            },
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            when (label) { "del" -> "\u232B"; "list" -> "\u2630"; else -> label },
                                            color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}
