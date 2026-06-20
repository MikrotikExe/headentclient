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
import androidx.compose.foundation.clickable
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
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Forward30
import coil.request.ImageRequest
import androidx.compose.foundation.layout.height
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
import androidx.compose.foundation.gestures.scrollBy
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
    private var htspLive = false
    private var htspStream = false
    private val htspLiveState = androidx.compose.runtime.mutableStateOf(false)
    private val timeshiftOffsetState = androidx.compose.runtime.mutableStateOf(0L)
    // timeshift "zapnuty" (po prvej pauze) -> az vtedy davaju zmysel RW/FF a dvojklik
    private val timeshiftEngagedState = androidx.compose.runtime.mutableStateOf(false)
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
    // pre opatovne pripojenie videa po navrate z pozadia
    private var videoLayout: VLCVideoLayout? = null
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
        androidx.compose.runtime.mutableStateOf<Map<String, List<sk.tvhclient.shared.model.EpgEvent>>>(emptyMap())

    // D-pad / diaľkové: signál na zobrazenie ovládania, info pre seek a sw dekóder
    private val controlsPokeState = androidx.compose.runtime.mutableStateOf(0)
    private val isPlayingState = androidx.compose.runtime.mutableStateOf(true)
    // D-pad navigacia zoznamu kanalov v prehravaci
    private val openChannelListState = androidx.compose.runtime.mutableStateOf(0)
    private val navChannelIndexState = androidx.compose.runtime.mutableStateOf(0)
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

    private fun buildMedia(url: String): Media {
        val m = Media(libVlc, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        // User-Agent: nech server vidi, ze sa pripaja HeadentClient
        m.addOption(":http-user-agent=" + userAgent())
        return m
    }

    /** Bezne HTTP prehravanie (zastavi pripadny HTSP feed). */
    private fun playHttp(url: String) {
        htspFeeder?.stop(); htspFeeder = null
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
     * M162 — zivy kanal cez HTSP (premuxovany na MPEG-TS, podavany libVLC cez pipe).
     * Vracia true ak sa podarilo spustit. Pouzite len ak je timeshift zapnuty a server
     * ho podporuje; inak ostava HTTP cesta.
     */
    private fun playHtspLive(server: sk.tvhclient.shared.model.TvhServer, channelId: Long, timeshift: Boolean): Boolean {
        return try {
            htspFeeder?.stop()
            val feeder = HtspTsFeeder(server, if (timeshift) 3600 else 0)
            htspFeeder = feeder
            resetTimeshift()
            val fd = feeder.start(channelId, lifecycleScope)
            val media = Media(libVlc, fd)
            media.setHWDecoderEnabled(true, false)
            media.addOption(":demux=ts")
            media.addOption(":file-caching=1500")
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

    private fun pokeControls() { controlsPokeState.value = controlsPokeState.value + 1 }
    // INFO kláves / tlacidlo -> okno s detailom aktualnej relacie
    private val infoPokeState = androidx.compose.runtime.mutableStateOf(0)
    private fun toggleInfo() { infoPokeState.value = infoPokeState.value + 1 }
    // EPG kláves / tlacidlo -> otvor TV program (mriezku) v hlavnej aplikacii
    private fun openEpgInApp() {
        // na telefonoch: vstup do PiP, aby video bezalo v plavajucom okne nad EPG
        autoPipIfPossible()
        val i = android.content.Intent(this, MainActivity::class.java).apply {
            putExtra("open_epg", true)
            // zapamataj aktualny zivy kanal, nech BACK z EPG vrati do prehravaca nan
            if (!seekablePlayback) liveUuids.getOrNull(liveIndex)?.let { putExtra("epg_return_uuid", it) }
        }
        runCatching { startActivity(i) }
    }
    private fun showControlsFocused() {
        val order = playerControlOrder(!seekablePlayback && liveUuids.size > 1, seekablePlayback, pipSupported, timeshiftEngagedState.value)
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
                val ord = playerControlOrder(!seekablePlayback && liveUuids.size > 1, seekablePlayback, pipSupported, true)
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
    private fun seekRelative(deltaMs: Long) {
        if (!::mediaPlayer.isInitialized || !seekablePlayback) return
        val dur = if (dvrDurationMs > 0) dvrDurationMs else mediaPlayer.length
        if (dur <= 0) return
        val curMs = (mediaPlayer.position.coerceIn(0f, 1f) * dur).toLong()
        val targetMs = (curMs + deltaMs).coerceIn(0, dur)
        mediaPlayer.position = (targetMs.toFloat() / dur).coerceIn(0f, 1f)
    }

    /** Dvojklik na lavu/pravu stranu (YouTube-style): skok o 10 s.
     *  DVR -> seek v medii; aktivny timeshift -> subscriptionSkip. Hint sa akumuluje. */
    private fun doubleTapSeek(forward: Boolean) {
        val step = if (forward) 10 else -10
        when {
            seekablePlayback -> seekRelative(step * 1000L)
            htspLive -> {
                if (maxRewindMs() <= 0L) return   // timeshift sa zapne az pauzou, dovtedy niet co pretacat
                timeshiftSkip(step)
            }
            else -> return   // ziadne pretacanie (zive bez timeshiftu) -> ignoruj
        }
        val cur = seekHintState.value
        seekHintState.value = if (cur != 0 && (cur > 0) == forward) cur + step else step
        seekHintJob?.cancel()
        seekHintJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(800)
            seekHintState.value = 0
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
            if (srv.connectionMode == "htsp") {
                val map = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Tvh.fetchEpgUpcoming(srv)
                }
                if (map.isNotEmpty()) {
                    epgUpcomingState.value = map
                    val updated = cur.map { ch ->
                        val ev = map[ch.uuid]?.firstOrNull { it.start <= nowS && nowS < it.stop }
                        if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop)
                        else ch
                    }
                    liveChannelsState.value = updated
                    LivePlaylist.channels = updated
                }
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
                val updated = cur.map { ch ->
                    val r = rows[ch.uuid]
                    if (r != null) ch.copy(
                        nowTitle = r.nowTitle ?: "",
                        nowStart = r.nowStart,
                        nowStop = r.nowStop
                    ) else ch
                }
                liveChannelsState.value = updated
                LivePlaylist.channels = updated
            }
        } catch (e: Exception) {
        }
    }

    /** Prepne na konkretny kanal podla indexu, prebuduje URL a nacita. */
    private fun switchToIndex(i: Int, poke: Boolean = true) {
        if (i < 0 || i >= liveUuids.size) return
        if (i == liveIndex) { if (poke) pokeControls(); return }  // ten isty kanal -> nenacitavaj znova
        val srv = liveServer ?: return
        val uuid = liveUuids[i]
        // rodicovsky zamok: zamknuty kanal mimo 5-min okna -> vypytaj PIN
        if (ParentalLock.channelNeedsPin(this, srv.id, uuid)) {
            requestPin(onOk = { switchToIndex(i, poke) }, onCancel = { })
            return
        }
        liveIndex = i
        liveIndexState.value = i
        val name = liveNames.getOrElse(i) { "" }
        liveTitleState.value = name
        liveUuidState.value = uuid
        // novy kanal = neznama relacia; skry progress bar starej relacie
        val ch = LivePlaylist.channels.getOrNull(i)
        liveProgStartState.value = ch?.nowStart ?: 0L
        liveProgStopState.value = ch?.nowStop ?: 0L
        liveProgTitleState.value = ch?.nowTitle ?: ""
        liveNextTitleState.value = ch?.nextTitle ?: ""
        liveNextStartState.value = ch?.nextStart ?: 0L
        liveNextStopState.value = ch?.nextStop ?: 0L
        zapPokeState.value = zapPokeState.value + 1
        val prof = ChannelPrefs.getProfile(this, srv.id, uuid)
            .ifBlank { srv.profile.ifBlank { "pass" } }
        val url = Tvh.liveUrl(srv, uuid, name, prof)
        currentStreamUrl = url
        cancelReconnect()  // nove pripojenie -> zrus stare pokusy
        hasVideoState.value = true  // predpokladaj video; kontrola po Playing to opravi
        val cid = uuid.toLongOrNull()
        if (htspStream && cid != null && playHtspLive(srv, cid, htspLive)) {
            if (poke) pokeControls()
            return
        }
        playHttp(url)
        if (poke) pokeControls()
    }

    /** Prepne na susedny live kanal (delta +1 / -1). */
    private fun switchLive(delta: Int) {
        if (liveUuids.size < 2 || liveIndex < 0) return
        val n = liveUuids.size
        switchToIndex(((liveIndex + delta) % n + n) % n)
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
    // Casovac uspatia
    private val sleepMinutesState = androidx.compose.runtime.mutableStateOf(0)
    private val sleepDeadlineState = androidx.compose.runtime.mutableStateOf(0L)
    private val sleepHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val sleepDurations = listOf(0, 15, 30, 45, 60, 90)
    // Navigacia ovladacieho panela (focus riadime z Activity, nie cez Compose focus)
    private val controlNavState = androidx.compose.runtime.mutableStateOf(0)
    // Navigacia track menu (audio/titulky)
    private val trackNavState = androidx.compose.runtime.mutableStateOf(0)
    private val closeMenuState = androidx.compose.runtime.mutableStateOf(0)
    private var trackMenuKind = "audio"
    private var okLongFired = false

    // Rodicovsky zamok (PIN) — Activity-driven overlay
    private val pinPromptState = androidx.compose.runtime.mutableStateOf(false)
    private val pinEntryState = androidx.compose.runtime.mutableStateOf("")
    private val pinErrorState = androidx.compose.runtime.mutableStateOf(false)
    private var pinOnSuccess: (() -> Unit)? = null
    private var pinOnCancel: (() -> Unit)? = null

    // DVR scrub focus: nahlad pozicie pri vybere casu sipkami (potvrdenie OK)
    private val scrubFractionState = androidx.compose.runtime.mutableStateOf(0f)
    private fun initScrub() {
        scrubFractionState.value = if (::mediaPlayer.isInitialized)
            mediaPlayer.position.coerceIn(0f, 1f) else 0f
    }

    private fun requestPin(onOk: () -> Unit, onCancel: () -> Unit) {
        pinOnSuccess = onOk; pinOnCancel = onCancel
        pinEntryState.value = ""; pinErrorState.value = false
        pinPromptState.value = true
    }
    private fun closePin() {
        pinPromptState.value = false; pinEntryState.value = ""; pinErrorState.value = false
        pinOnSuccess = null; pinOnCancel = null
    }
    private fun pinDigit(d: Int) {
        if (pinEntryState.value.length >= 4) return
        pinEntryState.value += d
        pinErrorState.value = false
        if (pinEntryState.value.length == 4) {
            if (ParentalLock.checkPin(this, pinEntryState.value)) {
                ParentalLock.markUnlocked(this)
                val ok = pinOnSuccess
                closePin(); ok?.invoke()
            } else { pinErrorState.value = true; pinEntryState.value = "" }
        }
    }
    private fun cancelPin() {
        val c = pinOnCancel
        closePin(); c?.invoke()
    }

    private fun openChannelList() {
        if (liveUuids.size < 2) return
        navChannelIndexState.value = liveIndex.coerceAtLeast(0)
        openChannelListState.value = openChannelListState.value + 1
    }
    private fun closeChannelList() {
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
        return if (trackMenuKind == "audio") {
            mediaPlayer.audioTrackItems().map { it.id }
        } else {
            listOf(-1) + mediaPlayer.spuTrackItems().map { it.id }  // -1 = Vypnute
        }
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
        if (trackMenuKind == "audio") mediaPlayer.audioTrack = id else mediaPlayer.spuTrack = id
        closeTrackMenu()
    }

    // --- Aktivacia zvyrazneneho prvku ovladacieho panela ---
    private fun activateControl(id: String?) {
        when (id) {
            "close" -> { if (!enterPipIfPossible()) finish() }
            "list" -> openChannelList()
            "prev" -> { switchLive(-1); pokeControls() }
            "play" -> { togglePlayPause(); pokeControls() }
            "next" -> { switchLive(+1); pokeControls() }
            "tsrew" -> { timeshiftSkip(-30); pokeControls() }
            "tsff" -> { timeshiftSkip(+30); pokeControls() }
            "audio" -> openAudioMenu()
            "subs" -> openSpuMenu()
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

        // 0) PIN rodicovskeho zamku -> cislice zadavame my
        if (pinPromptState.value) {
            if (down) {
                val digit = when (kc) {
                    in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                        kc - android.view.KeyEvent.KEYCODE_0
                    in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 ->
                        kc - android.view.KeyEvent.KEYCODE_NUMPAD_0
                    else -> -1
                }
                when {
                    digit >= 0 -> { pinDigit(digit); return true }
                    kc == android.view.KeyEvent.KEYCODE_DEL ->
                        { if (pinEntryState.value.isNotEmpty()) pinEntryState.value = pinEntryState.value.dropLast(1); return true }
                    kc == android.view.KeyEvent.KEYCODE_BACK ||
                        kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { cancelPin(); return true }
                }
            }
            return true
        }

        // 0b) EPG kláves -> TV program; INFO kláves -> okno s relaciou (vzdy)
        if (down) {
            when (kc) {
                android.view.KeyEvent.KEYCODE_GUIDE,
                android.view.KeyEvent.KEYCODE_CAPTIONS,
                android.view.KeyEvent.KEYCODE_TV_DATA_SERVICE,
                android.view.KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                android.view.KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU -> { openEpgInApp(); return true }
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
            val isOk = kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                kc == android.view.KeyEvent.KEYCODE_ENTER ||
                kc == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
            if (isOk) {
                // ak je toto dotahovanie podrzania OK, ktore zoznam otvorilo -> ignoruj kym nepustis
                if (okLongFired) { if (!down) okLongFired = false; return true }
                if (down && n > 0) {
                    if (navChannelIndexState.value == liveIndexState.value) closeChannelList()  // uz hra vybrany -> cela obrazovka
                    else switchToIndex(navChannelIndexState.value, poke = false)              // prepni prehravany kanal, ostan v zozname (bez listy)
                }
                return true
            }
            if (down && n > 0) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        { navChannelIndexState.value = (navChannelIndexState.value - 1 + n) % n; return true }
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

        // 4) Bezne prehravanie
        if (::mediaPlayer.isInitialized) {
            val canZap = !seekablePlayback && liveUuids.size > 1
            // prepinanie kanalov: Channel+/-, Page+/-, aj sipky hore/dole = zap
            when (kc) {
                android.view.KeyEvent.KEYCODE_CHANNEL_UP,
                android.view.KeyEvent.KEYCODE_PAGE_UP,
                android.view.KeyEvent.KEYCODE_DPAD_UP ->
                    if (down && canZap && event.repeatCount == 0) { switchLive(+1); pokeControls(); return true }
                android.view.KeyEvent.KEYCODE_CHANNEL_DOWN,
                android.view.KeyEvent.KEYCODE_PAGE_DOWN,
                android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                    if (down && canZap && event.repeatCount == 0) { switchLive(-1); pokeControls(); return true }
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
                val order = playerControlOrder(canZap, seekablePlayback, pipSupported, timeshiftEngagedState.value)
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
                            else seekRelative(-15_000)
                            pokeControls(); return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                            if (onSeek) scrubFractionState.value = (scrubFractionState.value + stepFrac).coerceIn(0f, 1f)
                            else seekRelative(+30_000)
                            pokeControls(); return true
                        }
                        android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                        android.view.KeyEvent.KEYCODE_ENTER,
                        android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (down && event.repeatCount == 0) {
                                if (onSeek) {
                                    if (::mediaPlayer.isInitialized) mediaPlayer.position = scrubFractionState.value
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
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> if (down && event.repeatCount == 0) {
                    // OK pri zivom = zoznam kanalov; pri DVR (bez zoznamu) = play/pause
                    if (seekablePlayback) { togglePlayPause(); showControlsFocused() }
                    else { okLongFired = true; openChannelList() }  // okLongFired prehltne nasledne OK-up
                    return true
                } else if (down) return true
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                    if (seekablePlayback) { seekRelative(-15_000); pokeControls(); return true }
                    showControlsFocused(); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                    if (seekablePlayback) { seekRelative(+30_000); pokeControls(); return true }
                    showControlsFocused(); return true
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
    private var reachedEnd = false

    private fun saveDvrProgress() {
        val uuid = dvrUuid ?: return
        val sid = dvrServerId ?: return
        if (!::mediaPlayer.isInitialized) return
        val dur = if (dvrDurationMs > 0) dvrDurationMs else mediaPlayer.length
        if (dur <= 0) return
        if (reachedEnd) {
            WatchProgress.markCompleted(this, sid, uuid, dur)
            return
        }
        val pos = mediaPlayer.position
        // neprepisuj dobru poziciu nulou (napr. ked sa media este nenacitala)
        if (pos > 0.001f && pos <= 1f) {
            WatchProgress.save(this, sid, uuid, (pos * dur).toLong(), dur)
        }
    }

    private fun keepScreenOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Zavri predoslu instanciu prehravaca (napr. visiacu v PiP so starym kanalom),
        // nech pri prepnuti kanala nezostane stara PiP visiet. Nova sa otvori na celu obrazovku.
        liveInstance?.get()?.let { old -> if (old !== this) runCatching { old.finish() } }
        liveInstance = java.lang.ref.WeakReference(this)
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
        val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
        val progStart = intent.getLongExtra(EXTRA_PROG_START, 0L)
        val progStop = intent.getLongExtra(EXTRA_PROG_STOP, 0L)
        val progTitle = intent.getStringExtra(EXTRA_PROG_TITLE) ?: ""
        dvrUuid = intent.getStringExtra(EXTRA_DVR_UUID)
        val progStartFrac = intent.getFloatExtra(EXTRA_PROG_START_FRAC, 0f)
        val progStopFrac = intent.getFloatExtra(EXTRA_PROG_STOP_FRAC, 1f)
        dvrDurationMs = durationMs
        val requirePin = intent.getBooleanExtra(EXTRA_REQUIRE_PIN, false)

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
            "--http-user-agent=" + userAgent()
        )
        libVlc = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVlc)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EncounteredError -> {
                    // zivé vysielanie: skus znovu pripojit (vypadok siete); inak nahlas chybu
                    if (!seekablePlayback) {
                        scheduleReconnect()
                    } else {
                        Toast.makeText(
                            this,
                            getString(R.string.playback_error, "VLC"),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                MediaPlayer.Event.Playing -> {
                    isPlayingState.value = true; refreshPipIfActive()
                    keepScreenOn(true)  // pocas prehravania nedovol setric/ambient na boxoch
                    cancelReconnect()  // uspesne pripojenie -> vynuluj pokusy
                    seekSpinnerJob?.cancel(); seekingState.value = false  // resync po skoku dobehol
                    // po nabehnuti zisti ci stream ma video; ak nie -> rozhlas (logo)
                    videoCheckHandler.removeCallbacksAndMessages(null)
                    videoCheckHandler.postDelayed({
                        val n = runCatching { mediaPlayer.videoTracksCount }.getOrNull()
                        if (n != null && n >= 0) hasVideoState.value = n > 0
                    }, 1500)
                }
                MediaPlayer.Event.Buffering -> {
                    if (event.buffering >= 100f) { seekSpinnerJob?.cancel(); seekingState.value = false }
                }
                MediaPlayer.Event.Paused -> { isPlayingState.value = false; keepScreenOn(false); refreshPipIfActive() }
                MediaPlayer.Event.Stopped -> { isPlayingState.value = false; keepScreenOn(false); refreshPipIfActive() }
                MediaPlayer.Event.Vout -> { if (event.voutCount > 0) hasVideoState.value = true }
                MediaPlayer.Event.EndReached -> {
                    isPlayingState.value = false
                    if (!seekablePlayback) {
                        // zivý stream "skoncil" = vypadok -> znovu pripojit
                        scheduleReconnect()
                    } else {
                        reachedEnd = true
                        keepScreenOn(false)
                        saveDvrProgress()
                    }
                }
            }
        }

        // DVR: priame dvrfile URL (s creds). Live: zostav z kanala s per-kanal
        // profilom (ak je nastaveny), inak profil servera.
        val chProfile = if (channelUuid != null)
            ChannelPrefs.getProfile(this, server.id, channelUuid) else ""
        val streamUrl = directUrl ?: Tvh.liveUrl(
            server, channelUuid!!, channelTitle,
            chProfile.ifBlank { server.profile.ifBlank { "pass" } }
        )

        // Live zapping: priprav zoznam susednych kanalov
        if (directUrl == null && channelUuid != null && LivePlaylist.channels.isNotEmpty()) {
            liveUuids = LivePlaylist.channels.map { it.uuid }
            liveNames = LivePlaylist.channels.map { it.name }
            liveIndex = LivePlaylist.index.takeIf { it in liveUuids.indices }
                ?: liveUuids.indexOf(channelUuid)
            liveServer = server
        }
        liveChannelsState.value = LivePlaylist.channels
        liveIndexState.value = liveIndex
        liveTitleState.value = channelTitle
        liveUuidState.value = channelUuid
        liveProgStartState.value = progStart
        liveProgStopState.value = progStop
        liveProgTitleState.value = progTitle
        val canZap = directUrl == null && liveUuids.size > 1
        seekablePlayback = directUrl != null
        // predvolene zvyraznenie ovladacieho panela = play (nie krizik)
        controlNavState.value = playerControlOrder(canZap, seekablePlayback, pipSupported, timeshiftEngagedState.value).indexOf("play").coerceAtLeast(0)
        currentStreamUrl = streamUrl

        setContent {
            val pThemeMode = PlayerThemePref.stateOf(this).value
            val pDark = when (pThemeMode) {
                PlayerThemePref.DARK -> true
                PlayerThemePref.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            MaterialTheme(
                colorScheme = if (pDark) darkColorScheme() else lightColorScheme()
            ) {
            PlayerUi(
                title = liveTitleState.value,
                player = mediaPlayer,
                seekable = directUrl != null,  // DVR nahravka = da sa pretacat; live nie
                knownDurationMs = durationMs,  // dlzka z DVR entry (TS subor ju nenese)
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
                onAttach = { layout -> videoLayout = layout; mediaPlayer.attachViews(layout, null, false, false) },
                onStart = {
                    val doPlay: () -> Unit = {
                        val cid = channelUuid?.toLongOrNull()
                        val htspMode = server.connectionMode == "htsp"
                        if (cid != null && directUrl == null && htspMode) {
                            // stream cez HTSP (9982). Timeshift funkcie len ak je pref zapnuty a server podporuje.
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
                                    playHttp(streamUrl)
                                }
                                pokeControls()
                            }
                        } else {
                            playHttp(streamUrl)
                            pokeControls()
                        }
                    }
                    if (requirePin && ParentalLock.needsPin(this) && ParentalLock.protectChannels(this)) {
                        requestPin(onOk = doPlay, onCancel = { finish() })
                    } else doPlay()
                },
                controlsPoke = controlsPokeState.value,
                infoPoke = infoPokeState.value,
                inPip = inPipState.value,
                pipSupported = pipSupported,
                hasVideo = hasVideoState.value,
                reconnecting = reconnectingState.value,
                seeking = seekingState.value,
                centerLogoUrl = liveChannelsState.value.getOrNull(liveIndexState.value)?.piconUrl,
                onOpenEpg = { openEpgInApp() },
                onEnterPip = { enterPipAndMinimize() },
                onOpenSleep = { openSleepMenu() },
                playing = isPlayingState.value,
                channelNavIndex = navChannelIndexState.value,
                openListSignal = openChannelListState.value,
                closeListSignal = closeChannelListState.value,
                onTrackMenuChange = { trackMenuOpen = it },
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
                closeMenuSignal = closeMenuState.value,
                openAudioSignal = openAudioMenuState.value,
                openSpuSignal = openSpuMenuState.value,
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
                            cb(list)
                        }
                    }
                },
                seekHint = seekHintState.value,
                liveChannels = if (canZap) liveChannelsState.value else emptyList(),
                liveCurrentIndex = liveIndexState.value,
                onSelectChannel = { idx -> if (idx != liveIndex) switchToIndex(idx) else pokeControls() },
                onRefreshEpg = {
                    lifecycleScope.launch { refreshOverlayEpg() }
                },
                numberEntry = numEntryState.value,
                timeshiftOffsetMs = timeshiftOffsetState.value,
                pinPrompt = pinPromptState.value,
                pinLen = pinEntryState.value.length,
                pinError = pinErrorState.value,
                scrubFrac = scrubFractionState.value,
                progNextTitle = liveNextTitleState.value,
                progNextStart = liveNextStartState.value,
                progNextStop = liveNextStopState.value,
                zapPoke = zapPokeState.value,
                onClose = { if (!enterPipIfPossible()) finish() }
            )
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

    /** Spusti PiP a minimalizuje appku (okno plava nad plochou / inou appkou). */
    private fun enterPipAndMinimize() {
        if (enterPipIfPossible()) {
            runCatching { moveTaskToBack(true) }
        }
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

    /** Naplanuje znovupripojenie zivého streamu po vypadku (narastajuce oneskorenie). */
    private fun scheduleReconnect() {
        if (seekablePlayback) return  // DVR nahravka sa neobnovuje
        val url = currentStreamUrl ?: return
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
            runCatching {
                val m = buildMedia(url)
                mediaPlayer.media = m
                m.release()
                mediaPlayer.play()
            }
        }, delay)
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipState.value = isInPictureInPictureMode
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
            if (wasPlaying) { runCatching { mediaPlayer.play() } }
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

    override fun onDestroy() {
        saveDvrProgress()
        super.onDestroy()
        // uvolni odkaz, len ak stale ukazuje na tuto instanciu (nie na novsiu)
        if (liveInstance?.get() === this) liveInstance = null
        videoCheckHandler.removeCallbacksAndMessages(null)
        reconnectHandler.removeCallbacksAndMessages(null)
        sleepHandler.removeCallbacksAndMessages(null)
        pipReceiver?.let { runCatching { unregisterReceiver(it) } }
        pipReceiver = null
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
        }
        htspFeeder?.stop()
        htspFeeder = null
        stopTimeshiftTicker()
        skipFlushJob?.cancel()
        if (::libVlc.isInitialized) {
            libVlc.release()
        }
    }

    companion object {
        const val EXTRA_UUID = "channel_uuid"
        const val EXTRA_TITLE = "channel_title"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_DURATION_MS = "duration_ms"
        const val EXTRA_PROG_START = "prog_start"
        const val EXTRA_PROG_STOP = "prog_stop"
        const val EXTRA_PROG_TITLE = "prog_title"
        const val EXTRA_DVR_UUID = "dvr_uuid"
        const val EXTRA_PROG_START_FRAC = "prog_start_frac"
        const val EXTRA_PROG_STOP_FRAC = "prog_stop_frac"
        const val EXTRA_REQUIRE_PIN = "require_pin"

        // Odkaz na prave zijucu instanciu prehravaca. Pri otvoreni noveho kanala zavrieme predoslu
        // (aj tu visiacu v PiP), inak by stara PiP zostala visiet so starym kanalom.
        private var liveInstance: java.lang.ref.WeakReference<PlayerActivity>? = null
    }
}

/** Jedna stopa (audio alebo titulky) z libVLC. */
internal data class TrackItem(val id: Int, val name: String)

private fun MediaPlayer.audioTrackItems(): List<TrackItem> =
    audioTracks?.map { TrackItem(it.id, it.name ?: "Audio ${it.id}") } ?: emptyList()

private fun MediaPlayer.spuTrackItems(): List<TrackItem> =
    spuTracks?.map { TrackItem(it.id, it.name ?: "Titulky ${it.id}") } ?: emptyList()

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
    onAttach: (VLCVideoLayout) -> Unit,
    onStart: () -> Unit,
    onPrevChannel: (() -> Unit)? = null,
    onNextChannel: (() -> Unit)? = null,
    onTogglePlay: () -> Unit = {},
    timeshiftEngaged: Boolean = false,
    onSkipBack: () -> Unit = {},
    onSkipFwd: () -> Unit = {},
    onDoubleTapSeek: (Boolean) -> Unit = {},
    onScrubSeek: (Int) -> Unit = {},
    onLoadChannelEpg: (String, (List<sk.tvhclient.shared.model.EpgEvent>) -> Unit) -> Unit = { _, _ -> },
    seekHint: Int = 0,
    liveChannels: List<LivePlaylist.LiveChannel> = emptyList(),
    liveCurrentIndex: Int = -1,
    onSelectChannel: (Int) -> Unit = {},
    onRefreshEpg: () -> Unit = {},
    numberEntry: String = "",
    timeshiftOffsetMs: Long = 0L,
    controlsPoke: Int = 0,
    infoPoke: Int = 0,
    inPip: Boolean = false,
    pipSupported: Boolean = false,
    hasVideo: Boolean = true,
    reconnecting: Boolean = false,
    seeking: Boolean = false,
    centerLogoUrl: String? = null,
    onOpenEpg: () -> Unit = {},
    onEnterPip: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
    playing: Boolean = true,
    channelNavIndex: Int = -1,
    openListSignal: Int = 0,
    closeListSignal: Int = 0,
    onTrackMenuChange: (Boolean) -> Unit = {},
    onChannelListChange: (Boolean) -> Unit = {},
    openOptionsSignal: Int = 0,
    closeOptionsSignal: Int = 0,
    optionsNavIndex: Int = 0,
    sleepDeadline: Long = 0,
    onOptionsSelect: (Int) -> Unit = {},
    controlNavIndex: Int = 0,
    trackNavIndex: Int = 0,
    closeMenuSignal: Int = 0,
    openAudioSignal: Int = 0,
    openSpuSignal: Int = 0,
    onOptionsChange: (Boolean) -> Unit = {},
    onControlsVisibleChange: (Boolean) -> Unit = {},
    pinPrompt: Boolean = false,
    pinLen: Int = 0,
    pinError: Boolean = false,
    scrubFrac: Float = 0f,
    progNextTitle: String = "",
    progNextStart: Long = 0,
    progNextStop: Long = 0,
    zapPoke: Int = 0,
    onOrientationLockChange: (Boolean) -> Unit = {},
    onClose: () -> Unit
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
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
            controlsVisible = false; showInfo = false
            showChannelList = false; menu = null; showOptions = false
        }
    }
    // oznam Activity ci je ovladanie zobrazene (vtedy D-pad navigaciu riesi Activity)
    LaunchedEffect(controlsVisible) { onControlsVisibleChange(controlsVisible) }
    // oznam Activity stav prekryti (kvoli D-pad smerovaniu)
    LaunchedEffect(menu) { onTrackMenuChange(menu != null) }
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
    LaunchedEffect(closeMenuSignal) { if (closeMenuSignal > 0) menu = null }
    // ikona play/pause podla skutocneho stavu prehravaca
    LaunchedEffect(playing) { isPlaying = playing }
    // seek stav (len pre DVR). TS subor nenese dlzku, takze pouzivame:
    //  - dlzku z DVR entry (knownDurationMs), fallback player.length
    //  - position (zlomok 0..1) na zobrazenie aj pretacanie (na TS spolahlivejsie nez setTime)
    var posFraction by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }

    // Dlzka: primarne z DVR entry, fallback co hlasi VLC
    val lengthMs = if (knownDurationMs > 0) knownDurationMs else player.length

    // Obnovenie pozicie (len DVR): spytaj sa, a po potvrdeni pretoc ked je
    // media nacitana
    var askResume by remember { mutableStateOf(resumeMs > 0) }
    var pendingResumeMs by remember { mutableStateOf(0L) }

    // Aktualizuj poziciu kazdu sekundu (len ked je seekable a netiahneme)
    if (seekable) {
        LaunchedEffect(Unit) {
            var sinceSave = 0
            while (true) {
                // obnovenie po potvrdeni
                if (pendingResumeMs > 0 && lengthMs > 0 && player.isSeekable) {
                    val f = (pendingResumeMs.toFloat() / lengthMs).coerceIn(0f, 1f)
                    player.position = f
                    posFraction = f
                    pendingResumeMs = 0
                }
                if (!dragging) {
                    val p = player.position
                    if (p in 0f..1f) posFraction = p
                }
                // priebezne ukladaj poziciu (kazdych ~5s)
                sinceSave++
                if (sinceSave >= 5 && !askResume) {
                    sinceSave = 0
                    val p = player.position
                    if (p > 0.001f && p <= 1f && lengthMs > 0 && dvrUuid != null && serverId != null) {
                        WatchProgress.save(ctx, serverId, dvrUuid, (p * lengthMs).toLong(), lengthMs)
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

    // Auto-vyber audio stopy po nacitani: 1) zapamatana pre kanal, 2) jazykove priority
    LaunchedEffect(Unit) {
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
                    val m = real.firstOrNull { AudioPref.matches(it.name ?: "", code) }
                    if (m != null) {
                        if (player.audioTrack != m.id) player.audioTrack = m.id
                        return@LaunchedEffect
                    }
                }
                return@LaunchedEffect
            }
        }
    }

    LaunchedEffect(controlsVisible, menu, controlsPoke) {
        if (controlsVisible && menu == null) {
            kotlinx.coroutines.delay(4000)
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

    // Kym je zoznam kanalov otvoreny, obnovuj EPG (now/next) aby relacie
    // postupne prechadzali na dalsie
    LaunchedEffect(showChannelList) {
        if (showChannelList) {
            while (true) {
                onRefreshEpg()
                kotlinx.coroutines.delay(60_000)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(isTvGest, seekable, timeshiftEngaged) {
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
                        if (mode == 0 && !showChannelList && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
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
                onStart()
                layout
            }
        )

        // Audio-only (rozhlas): namiesto ciernej zobraz vycentrovane logo
        if (!hasVideo) {
            val ctxLogo = androidx.compose.ui.platform.LocalContext.current
            val cfgLogo = androidx.compose.ui.platform.LocalConfiguration.current
            val side = (minOf(cfgLogo.screenWidthDp, cfgLogo.screenHeightDp) * 0.42f).dp
            val logoLoader = remember(server?.id) { PiconImageLoader.get(ctxLogo, server) }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator(color = playerFg())
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.reconnecting),
                        color = playerFg(),
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
                        color = Color(0xFF1E88E5),
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
                val order = playerControlOrder(onPrevChannel != null, seekable, pipSupported, timeshiftEngaged)
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
                    if (seekable && lengthMs > 0) {
                        val seekFocused = selCtrl == "seek"
                        val frac = when {
                            seekFocused -> scrubFrac
                            dragging -> dragValue
                            else -> posFraction
                        }
                        val cur = (frac * lengthMs).toLong()
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
                                        player.position = dragValue
                                        posFraction = dragValue
                                        dragging = false
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
                            Text(fmtMs(lengthMs), color = playerFg(),
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
                    if (portrait) {
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
                            barCtrl("sleep")
                            barCtrl("info")
                            if (lockVisible) barCtrl("lock")
                        }
                    }
                }
                }
            }
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
                    modifier = Modifier.fillMaxWidth(0.72f)
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
                    }
                }
                    LazyColumn(
                        state = listState,
                        userScrollEnabled = listFrac >= 0.999f,   // rolovat az ked je zoznam uplne otvoreny
                        modifier = Modifier.weight(1f)
                    ) {
                        itemsIndexed(liveChannels) { idx, ch ->
                            val selected = idx == sel
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .background(if (selected) Color(0x553B82F6) else Color.Transparent)
                                    .clickable { onSelectChannel(idx); showChannelList = false }
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
                                    Text(
                                        ch.name,
                                        color = playerFg(),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
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

        // Overlay: TV/STB EPG browser (cely displej, OK otvori) — vlavo zoznam, vpravo detail + nahlad + dalsie
        if (showChannelList && isTvGest && liveChannels.isNotEmpty()) {
            val loaderT = remember(server?.id) { PiconImageLoader.get(ctx, server) }
            val selT = channelNavIndex.coerceIn(0, liveChannels.size - 1)
            val listStateT = rememberLazyListState(initialFirstVisibleItemIndex = selT)
            LaunchedEffect(channelNavIndex) {
                val i = channelNavIndex
                if (i in liveChannels.indices) {
                    val info = listStateT.layoutInfo
                    val item = info.visibleItemsInfo.firstOrNull { it.index == i }
                    if (item == null) {
                        listStateT.scrollToItem(i)
                    } else {
                        val startGap = item.offset - info.viewportStartOffset
                        val endOverflow = (item.offset + item.size) - info.viewportEndOffset
                        if (startGap < 0) listStateT.scrollBy(startGap.toFloat())            // orezany hore -> posun nahor
                        else if (endOverflow > 0) listStateT.scrollBy(endOverflow.toFloat())  // orezany dole -> posun nadol (vybrany cely vidno)
                    }
                }
            }
            // pravy panel (EPG, nahlad, relacie) sleduje HRANY kanal — meni sa az po prepnuti (OK)
            val detT = liveCurrentIndex.coerceIn(0, liveChannels.size - 1)
            var epgT by remember { mutableStateOf<List<sk.tvhclient.shared.model.EpgEvent>>(emptyList()) }
            LaunchedEffect(detT) {
                val uuid = liveChannels.getOrNull(detT)?.uuid ?: return@LaunchedEffect
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
            Column(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
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
                    Spacer(Modifier.weight(1f))
                    Text(hhmm(nowT), color = playerFg(),
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
                Row(Modifier.fillMaxWidth().weight(1f)) {
                    // LAVA: zoznam kanalov (karty s ramikom)
                    LazyColumn(
                        state = listStateT,
                        modifier = Modifier.fillMaxHeight().fillMaxWidth(0.46f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                    ) {
                        itemsIndexed(liveChannels) { idx, ch ->
                            val selRow = idx == selT
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
                                Text(if (ch.number > 0) ch.number.toString() else "", color = accentC,
                                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    // PRAVA: detail vybraneho + nahlad hraneho + dalsie programy
                    Column(Modifier.fillMaxHeight().weight(1f).padding(horizontal = 22.dp, vertical = 6.dp)) {
                        Text(
                            (curT?.title?.takeIf { it.isNotBlank() }) ?: liveChannels.getOrNull(detT)?.nowTitle ?: "",
                            color = playerFg(), style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        if (curT != null)
                            Text(hhmm(curT.start) + " – " + hhmm(curT.stop), color = accentC,
                                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 4.dp))
                        // nahlad: zive video hraneho kanala — VLC povrch presvita cez dieru v scrime
                        Box(
                            Modifier.padding(top = 14.dp).fillMaxWidth(0.7f).height(180.dp)
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
                        val desc = curT?.bestDescription ?: ""
                        if (desc.isNotBlank())
                            Text(desc, color = playerFgDim(), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 14.dp))
                        if (nextT.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            nextT.forEach { ev ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                    Text(hhmm(ev.start), color = accentC,
                                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.width(58.dp))
                                    Text(ev.title, color = playerFg(), style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
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
                        val sel = idx == optionsNavIndex
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
            val items = if (menu == "audio") player.audioTrackItems() else player.spuTrackItems()
            val currentId = if (menu == "audio") player.audioTrack else player.spuTrack
            TrackMenu(
                header = if (menu == "audio") stringResource(R.string.track_audio) else stringResource(R.string.track_subtitles),
                items = items,
                currentId = currentId,
                allowOff = (menu == "spu"),  // titulky sa daju vypnut (-1)
                navIndex = trackNavIndex,
                onPick = { id ->
                    if (menu == "audio") {
                        player.audioTrack = id
                        // zapamataj vyber pre kanal (live)
                        if (liveChannelUuid != null && serverId != null) {
                            val name = items.firstOrNull { it.id == id }?.name
                            if (!name.isNullOrBlank()) {
                                ChannelPrefs.setLastAudio(ctx, serverId, liveChannelUuid, name)
                            }
                        }
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
                        TextChip(androidx.compose.ui.res.stringResource(R.string.no)) {
                            askResume = false
                        }
                        TextChip(androidx.compose.ui.res.stringResource(R.string.yes)) {
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
                Modifier.fillMaxSize().background(playerScrim()),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.plock_enter),
                        color = playerFg(),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(4) { i ->
                            Box(
                                Modifier.size(20.dp).clip(CircleShape).background(
                                    if (i < pinLen) Color(0xFF3B82F6) else playerTrack()
                                )
                            )
                        }
                    }
                    if (pinError) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.plock_wrong),
                            color = Color(0xFFFF6B6B)
                        )
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.plock_hint),
                        color = playerFgDim(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
