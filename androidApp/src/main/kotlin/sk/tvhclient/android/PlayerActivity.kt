package sk.tvhclient.android

import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import coil.compose.AsyncImage
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import sk.tvhclient.shared.Tvh

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
    private lateinit var mediaPlayer: MediaPlayer

    // Live zapping (prepinanie kanalov v prehravaci)
    private var liveUuids: List<String> = emptyList()
    private var liveNames: List<String> = emptyList()
    private var liveIndex: Int = -1
    private var liveServer: sk.tvhclient.shared.model.TvhServer? = null
    private val liveTitleState = androidx.compose.runtime.mutableStateOf("")
    private val liveUuidState = androidx.compose.runtime.mutableStateOf<String?>(null)
    private val liveProgStartState = androidx.compose.runtime.mutableStateOf(0L)
    private val liveProgStopState = androidx.compose.runtime.mutableStateOf(0L)
    private val liveProgTitleState = androidx.compose.runtime.mutableStateOf("")
    private val liveIndexState = androidx.compose.runtime.mutableStateOf(-1)
    private val liveChannelsState =
        androidx.compose.runtime.mutableStateOf<List<LivePlaylist.LiveChannel>>(emptyList())

    // D-pad / diaľkové: signál na zobrazenie ovládania, info pre seek a sw dekóder
    private val controlsPokeState = androidx.compose.runtime.mutableStateOf(0)
    private val softwareDecodeState = androidx.compose.runtime.mutableStateOf(false)
    private val isPlayingState = androidx.compose.runtime.mutableStateOf(true)
    // D-pad navigacia zoznamu kanalov v prehravaci
    private val openChannelListState = androidx.compose.runtime.mutableStateOf(0)
    private val navChannelIndexState = androidx.compose.runtime.mutableStateOf(0)
    private var seekablePlayback = false
    private var currentStreamUrl: String? = null

    /** Vytvori Media s HW/SW dekoderom podla preferencie (lacne boxy = SW). */
    private fun buildMedia(url: String): Media {
        val m = Media(libVlc, Uri.parse(url))
        m.setHWDecoderEnabled(!DecoderPref.get(this), false)
        return m
    }

    private fun pokeControls() { controlsPokeState.value = controlsPokeState.value + 1 }

    private fun togglePlayPause() {
        if (!::mediaPlayer.isInitialized) return
        if (mediaPlayer.isPlaying) mediaPlayer.pause() else mediaPlayer.play()
    }

    /** Pretacanie pre DVR (live TS sa pretacat neda). */
    private fun seekRelative(deltaMs: Long) {
        if (!::mediaPlayer.isInitialized || !seekablePlayback) return
        val len = mediaPlayer.length
        if (len <= 0) return
        val target = (mediaPlayer.time + deltaMs).coerceIn(0, len)
        mediaPlayer.time = target
    }

    /** Znovu spusti aktualny stream (po zmene dekodera). */
    private fun restartPlayback() {
        val url = currentStreamUrl ?: return
        if (!::mediaPlayer.isInitialized) return
        val m = buildMedia(url)
        mediaPlayer.media = m
        m.release()
        mediaPlayer.play()
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
    private fun switchToIndex(i: Int) {
        if (i < 0 || i >= liveUuids.size) return
        val srv = liveServer ?: return
        liveIndex = i
        liveIndexState.value = i
        val uuid = liveUuids[i]
        val name = liveNames.getOrElse(i) { "" }
        liveTitleState.value = name
        liveUuidState.value = uuid
        // novy kanal = neznama relacia; skry progress bar starej relacie
        val ch = LivePlaylist.channels.getOrNull(i)
        liveProgStartState.value = ch?.nowStart ?: 0L
        liveProgStopState.value = ch?.nowStop ?: 0L
        liveProgTitleState.value = ch?.nowTitle ?: ""
        val prof = ChannelPrefs.getProfile(this, srv.id, uuid)
            .ifBlank { srv.profile.ifBlank { "pass" } }
        val url = Tvh.liveUrl(srv, uuid, name, prof)
        currentStreamUrl = url
        val media = buildMedia(url)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
    }

    /** Prepne na susedny live kanal (delta +1 / -1). */
    private fun switchLive(delta: Int) {
        if (liveUuids.size < 2 || liveIndex < 0) return
        val n = liveUuids.size
        switchToIndex(((liveIndex + delta) % n + n) % n)
    }

    // stav prekryti (z Compose) — kym je otvorene, D-pad riesime my (zoznam) alebo Compose (menu)
    private var trackMenuOpen = false
    private var channelListOpen = false
    private val closeChannelListState = androidx.compose.runtime.mutableStateOf(0)
    private var okDownAt = 0L
    private var okLongFired = false

    private fun openChannelList() {
        if (liveUuids.size < 2) return
        navChannelIndexState.value = liveIndex.coerceAtLeast(0)
        openChannelListState.value = openChannelListState.value + 1
    }
    private fun closeChannelList() {
        closeChannelListState.value = closeChannelListState.value + 1
    }

    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val down = event.action == android.view.KeyEvent.ACTION_DOWN

        // 1) Otvoreny zoznam kanalov -> navigujeme my
        if (channelListOpen) {
            val n = liveUuids.size
            if (down && n > 0) {
                when (event.keyCode) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        { navChannelIndexState.value = (navChannelIndexState.value - 1 + n) % n; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        { navChannelIndexState.value = (navChannelIndexState.value + 1) % n; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                    android.view.KeyEvent.KEYCODE_ENTER,
                    android.view.KeyEvent.KEYCODE_NUMPAD_ENTER ->
                        { switchToIndex(navChannelIndexState.value); closeChannelList(); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
                    android.view.KeyEvent.KEYCODE_BACK ->
                        { closeChannelList(); return true }
                }
            }
            // ostatne klavesy kym je zoznam otvoreny prehltni (okrem hlasitosti)
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_VOLUME_UP,
                android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
                android.view.KeyEvent.KEYCODE_VOLUME_MUTE -> return super.dispatchKeyEvent(event)
            }
            return true
        }

        // 2) Otvorene menu stop (audio/titulky) -> nechame Compose/dotyk
        if (trackMenuOpen) return super.dispatchKeyEvent(event)

        // 3) Bezne prehravanie
        if (::mediaPlayer.isInitialized) {
            val canZap = liveUuids.size > 1
            when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_CHANNEL_UP ->
                    if (down && canZap) { switchLive(+1); pokeControls(); return true }
                android.view.KeyEvent.KEYCODE_CHANNEL_DOWN ->
                    if (down && canZap) { switchLive(-1); pokeControls(); return true }
                android.view.KeyEvent.KEYCODE_DPAD_LEFT -> if (down) {
                    if (canZap) { switchLive(-1); pokeControls(); return true }
                    if (seekablePlayback) { seekRelative(-15_000); pokeControls(); return true }
                }
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> if (down) {
                    if (canZap) { switchLive(+1); pokeControls(); return true }
                    if (seekablePlayback) { seekRelative(+30_000); pokeControls(); return true }
                }
                android.view.KeyEvent.KEYCODE_DPAD_UP -> if (down) {
                    if (canZap) { openChannelList(); return true }
                    pokeControls(); return true
                }
                android.view.KeyEvent.KEYCODE_DPAD_DOWN -> if (down) { pokeControls(); return true }
                android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                android.view.KeyEvent.KEYCODE_ENTER,
                android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                    // kratke OK = play/pause, dlhe podrzanie OK = zoznam kanalov
                    if (down) {
                        if (event.repeatCount == 0) { okDownAt = System.currentTimeMillis(); okLongFired = false }
                        else if (!okLongFired && canZap &&
                            System.currentTimeMillis() - okDownAt >= 500
                        ) { okLongFired = true; openChannelList() }
                        return true
                    } else {
                        if (!okLongFired) { togglePlayPause(); pokeControls() }
                        okLongFired = false
                        return true
                    }
                }
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
        dvrDurationMs = durationMs

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
            "--no-skip-frames"
        )
        libVlc = LibVLC(this, options)
        mediaPlayer = MediaPlayer(libVlc)

        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.EncounteredError -> {
                    Toast.makeText(
                        this,
                        getString(R.string.playback_error, "VLC"),
                        Toast.LENGTH_LONG
                    ).show()
                }
                MediaPlayer.Event.Playing -> isPlayingState.value = true
                MediaPlayer.Event.Paused -> isPlayingState.value = false
                MediaPlayer.Event.Stopped -> isPlayingState.value = false
                MediaPlayer.Event.EndReached -> {
                    isPlayingState.value = false
                    reachedEnd = true
                    saveDvrProgress()
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
        currentStreamUrl = streamUrl
        softwareDecodeState.value = DecoderPref.get(this)

        setContent {
            PlayerUi(
                title = liveTitleState.value,
                player = mediaPlayer,
                seekable = directUrl != null,  // DVR nahravka = da sa pretacat; live nie
                knownDurationMs = durationMs,  // dlzka z DVR entry (TS subor ju nenese)
                progStartSec = liveProgStartState.value,
                progStopSec = liveProgStopState.value,
                progTitleArg = liveProgTitleState.value,
                server = server,
                liveChannelUuid = if (directUrl == null) liveUuidState.value else null,
                preferredAudio = AudioPref.get(this),
                resumeMs = resumeMs,
                dvrUuid = dvrUuid,
                serverId = server.id,
                onAttach = { layout -> mediaPlayer.attachViews(layout, null, false, false) },
                onStart = {
                    currentStreamUrl = streamUrl
                    val media = buildMedia(streamUrl)
                    mediaPlayer.media = media
                    media.release()
                    mediaPlayer.play()
                },
                controlsPoke = controlsPokeState.value,
                softwareDecode = softwareDecodeState.value,
                onToggleSoftwareDecode = {
                    val newVal = !DecoderPref.get(this)
                    DecoderPref.set(this, newVal)
                    softwareDecodeState.value = newVal
                    restartPlayback()
                },
                playing = isPlayingState.value,
                channelNavIndex = navChannelIndexState.value,
                openListSignal = openChannelListState.value,
                closeListSignal = closeChannelListState.value,
                onTrackMenuChange = { trackMenuOpen = it },
                onChannelListChange = {
                    channelListOpen = it
                    if (it) navChannelIndexState.value = liveIndex.coerceAtLeast(0)
                },
                onPrevChannel = if (canZap) ({ switchLive(-1) }) else null,
                onNextChannel = if (canZap) ({ switchLive(+1) }) else null,
                liveChannels = if (canZap) liveChannelsState.value else emptyList(),
                liveCurrentIndex = liveIndexState.value,
                onSelectChannel = { idx -> switchToIndex(idx) },
                onRefreshEpg = {
                    lifecycleScope.launch { refreshOverlayEpg() }
                },
                onClose = { finish() }
            )
        }
    }

    override fun onStop() {
        saveDvrProgress()
        super.onStop()
        if (::mediaPlayer.isInitialized && mediaPlayer.isPlaying) {
            mediaPlayer.pause()
        }
    }

    override fun onDestroy() {
        saveDvrProgress()
        super.onDestroy()
        if (::mediaPlayer.isInitialized) {
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            mediaPlayer.release()
        }
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
    }
}

/** Jedna stopa (audio alebo titulky) z libVLC. */
private data class TrackItem(val id: Int, val name: String)

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
    liveChannels: List<LivePlaylist.LiveChannel> = emptyList(),
    liveCurrentIndex: Int = -1,
    onSelectChannel: (Int) -> Unit = {},
    onRefreshEpg: () -> Unit = {},
    controlsPoke: Int = 0,
    softwareDecode: Boolean = false,
    onToggleSoftwareDecode: () -> Unit = {},
    playing: Boolean = true,
    channelNavIndex: Int = -1,
    openListSignal: Int = 0,
    closeListSignal: Int = 0,
    onTrackMenuChange: (Boolean) -> Unit = {},
    onChannelListChange: (Boolean) -> Unit = {},
    onClose: () -> Unit
) {
    var controlsVisible by remember { mutableStateOf(true) }
    var showChannelList by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var orientationLocked by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // menu: null = ziadne, "audio" = audio stopy, "spu" = titulky
    var menu by remember { mutableStateOf<String?>(null) }

    // D-pad / dialkove poslalo signal -> zobraz ovladanie
    LaunchedEffect(controlsPoke) {
        if (controlsPoke > 0) controlsVisible = true
    }
    // oznam Activity stav prekryti (kvoli D-pad smerovaniu)
    LaunchedEffect(menu) { onTrackMenuChange(menu != null) }
    LaunchedEffect(showChannelList) { onChannelListChange(showChannelList) }
    // Activity ziada otvorit/zavriet zoznam kanalov (D-pad / podrzanie OK)
    LaunchedEffect(openListSignal) {
        if (openListSignal > 0) { showChannelList = true; controlsVisible = false }
    }
    LaunchedEffect(closeListSignal) {
        if (closeListSignal > 0) showChannelList = false
    }
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
            // ak nemame relaciu alebo dobehla -> nacitaj aktualnu/dalsiu
            while (true) {
                val now = System.currentTimeMillis() / 1000
                if (progStart == 0L || progStop == 0L || now >= progStop) {
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
                    }
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

    LaunchedEffect(controlsVisible, menu) {
        if (controlsVisible && menu == null) {
            kotlinx.coroutines.delay(4000)
            controlsVisible = false
        }
    }

    androidx.activity.compose.BackHandler(enabled = showChannelList) { showChannelList = false }

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
            .pointerInput(liveChannels.isNotEmpty()) {
                if (liveChannels.isEmpty()) return@pointerInput
                var dx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { dx = 0f },
                    onDragEnd = {
                        if (dx > 100f) showChannelList = true        // potiahnutie doprava -> otvor
                        else if (dx < -100f) showChannelList = false  // dolava -> zavri
                    }
                ) { _, amount -> dx += amount }
            }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (menu != null) menu = null else controlsVisible = !controlsVisible
            }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
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

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().systemBarsPadding().background(Color(0x66000000))) {
                // Horny pruh: zavriet + nazov
                Row(
                    Modifier.fillMaxWidth().padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleButton("\u2715", onClick = onClose)
                    Column(
                        Modifier.padding(start = 12.dp).weight(1f)
                    ) {
                        Text(
                            title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1
                        )
                        if (progTitle.isNotBlank()) {
                            Text(
                                progTitle,
                                color = Color(0xCCFFFFFF),
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                    // Zoznam kanalov (overlay)
                    if (liveChannels.isNotEmpty()) {
                        CircleButton(
                            label = "\u2630",
                            onClick = { showChannelList = true; controlsVisible = false }
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    // Zamok orientacie: zamknuty = aktualna poloha, odomknuty = podla telefonu
                    CircleButton(
                        label = if (orientationLocked) "\uD83D\uDD12" else "\uD83D\uDD13",
                        onClick = {
                            orientationLocked = !orientationLocked
                            activity?.requestedOrientation = if (orientationLocked)
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LOCKED
                            else
                                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                        }
                    )
                }

                // Stred: (prev kanal) play/pause (next kanal)
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onPrevChannel != null) {
                        CircleButton(label = "\u23EE", onClick = onPrevChannel)
                        Spacer(Modifier.width(28.dp))
                    }
                    CircleButton(
                        label = if (isPlaying) "\u23F8" else "\u25B6",
                        big = true,
                        onClick = {
                            if (player.isPlaying) {
                                player.pause(); isPlaying = false
                            } else {
                                player.play(); isPlaying = true
                            }
                        }
                    )
                    if (onNextChannel != null) {
                        Spacer(Modifier.width(28.dp))
                        CircleButton(label = "\u23ED", onClick = onNextChannel)
                    }
                }

                // Dolna cast: seek lista (len DVR) + audio/titulky
                Column(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    if (hasLiveProg) {
                        val total = (progStop - progStart).coerceAtLeast(1)
                        val elapsed = (liveNowSec - progStart).coerceIn(0, total)
                        val frac = elapsed.toFloat() / total.toFloat()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtMs(elapsed * 1000), color = Color.White,
                                style = MaterialTheme.typography.bodySmall)
                            androidx.compose.material3.LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                                trackColor = Color(0x55FFFFFF)
                            )
                            Text("-" + fmtMs((total - elapsed) * 1000), color = Color.White,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (seekable && lengthMs > 0) {
                        val frac = if (dragging) dragValue else posFraction
                        val cur = (frac * lengthMs).toLong()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtMs(cur), color = Color.White,
                                style = MaterialTheme.typography.bodySmall)
                            androidx.compose.material3.Slider(
                                value = frac.coerceIn(0f, 1f),
                                onValueChange = { dragging = true; dragValue = it },
                                onValueChangeFinished = {
                                    player.position = dragValue
                                    posFraction = dragValue
                                    dragging = false
                                },
                                modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                            )
                            Text(fmtMs(lengthMs), color = Color.White,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Row(
                        Modifier.align(Alignment.End),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        TextChip("\uD83D\uDD0A Audio") { menu = if (menu == "audio") null else "audio" }
                        TextChip("\uD83D\uDCAC Titulky") { menu = if (menu == "spu") null else "spu" }
                        TextChip(if (softwareDecode) "\u2699 SW dekód: ZAP" else "\u2699 SW dekód: VYP") {
                            onToggleSoftwareDecode()
                        }
                    }
                }
            }
        }

        // Overlay: zoznam kanalov priamo v prehravaci (klik prepne)
        if (showChannelList && liveChannels.isNotEmpty()) {
            val loader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
            val listState = rememberLazyListState(
                initialFirstVisibleItemIndex = liveCurrentIndex.coerceAtLeast(0)
            )
            // efektivny vyber: pri D-pad navigacii navIndex, inak aktualny kanal
            val sel = if (channelNavIndex >= 0) channelNavIndex else liveCurrentIndex
            LaunchedEffect(channelNavIndex) {
                if (channelNavIndex in liveChannels.indices) listState.animateScrollToItem(channelNavIndex)
            }
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(0.52f)
                        .background(Color(0xDD0A0A0A))
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { showChannelList = false }
                            .padding(horizontal = 12.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("\u2039", color = Color.White, fontSize = 24.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(
                            androidx.compose.ui.res.stringResource(R.string.player_channel_list),
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
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
                                    color = if (selected) Color.White else Color(0xFF6699FF),
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(34.dp)
                                )
                                if (ch.piconUrl != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(ctx).data(ch.piconUrl).build(),
                                        contentDescription = null,
                                        imageLoader = loader,
                                        modifier = Modifier.size(40.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                } else {
                                    Spacer(Modifier.width(10.dp))
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        ch.name,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (ch.nowTitle.isNotBlank()) {
                                        Text(
                                            ch.nowTitle,
                                            color = Color(0xCCFFFFFF),
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
                                                trackColor = Color(0x55FFFFFF)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                // prava cast: klik mimo zoznam zavrie
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { showChannelList = false }
                )
            }
        }

        // Menu stop (audio / titulky)
        if (menu != null) {
            val items = if (menu == "audio") player.audioTrackItems() else player.spuTrackItems()
            val currentId = if (menu == "audio") player.audioTrack else player.spuTrack
            TrackMenu(
                header = if (menu == "audio") "Zvuková stopa" else "Titulky",
                items = items,
                currentId = currentId,
                allowOff = (menu == "spu"),  // titulky sa daju vypnut (-1)
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
                    .background(Color(0xAA000000))
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
                        .background(Color(0xEE202020))
                        .padding(20.dp)
                ) {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.resume_question),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        fmtMs(resumeMs),
                        color = Color(0xCCFFFFFF),
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
    }
}

@Composable
private fun TrackMenu(
    header: String,
    items: List<TrackItem>,
    currentId: Int,
    allowOff: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .widthIn(min = 240.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xEE202020))
                .padding(8.dp)
        ) {
            Text(
                header,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp)
            )
            if (allowOff) {
                TrackRow("Vypnuté", selected = currentId == -1) { onPick(-1) }
            }
            if (items.isEmpty() && !allowOff) {
                Text(
                    "Žiadne stopy",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(12.dp)
                )
            }
            items.forEach { t ->
                TrackRow(t.name, selected = t.id == currentId) { onPick(t.id) }
            }
        }
    }
}

@Composable
private fun TrackRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .dpadFocusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "\u2713  " else "    ",
            color = MaterialTheme.colorScheme.primary
        )
        Text(label, color = Color.White)
    }
}

@Composable
private fun TextChip(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .dpadFocusable(RoundedCornerShape(20.dp))
            .background(Color(0x88000000))
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, color = Color.White)
    }
}

@Composable
private fun CircleButton(
    label: String,
    onClick: () -> Unit,
    big: Boolean = false,
    modifier: Modifier = Modifier
) {
    val s = if (big) 76.dp else 44.dp
    Box(
        modifier
            .size(s)
            .clip(CircleShape)
            .dpadFocusable(CircleShape)
            .background(Color(0x88000000))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            textAlign = TextAlign.Center,
            fontSize = if (big) 34.sp else 20.sp
        )
    }
}

private fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "$h:" + m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
    } else {
        "$m:" + s.toString().padStart(2, '0')
    }
}
