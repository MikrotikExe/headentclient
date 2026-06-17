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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import coil.compose.AsyncImage
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
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
import androidx.compose.ui.res.stringResource
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
        return "TVH Client/$v"
    }

    private fun buildMedia(url: String): Media {
        val m = Media(libVlc, Uri.parse(url))
        m.setHWDecoderEnabled(true, false)
        // User-Agent: nech server vidi, ze sa pripaja TVH Client
        m.addOption(":http-user-agent=" + userAgent())
        return m
    }

    private fun pokeControls() { controlsPokeState.value = controlsPokeState.value + 1 }
    // INFO kláves / tlacidlo -> okno s detailom aktualnej relacie
    private val infoPokeState = androidx.compose.runtime.mutableStateOf(0)
    private fun toggleInfo() { infoPokeState.value = infoPokeState.value + 1 }
    // EPG kláves / tlacidlo -> otvor TV program (mriezku) v hlavnej aplikacii
    private fun openEpgInApp() {
        val i = android.content.Intent(this, MainActivity::class.java).apply {
            putExtra("open_epg", true)
        }
        runCatching { startActivity(i) }
    }
    private fun showControlsFocused() {
        val order = playerControlOrder(!seekablePlayback && liveUuids.size > 1, seekablePlayback)
        controlNavState.value = order.indexOf("play").coerceAtLeast(0)
        pokeControls()
    }

    private fun togglePlayPause() {
        if (!::mediaPlayer.isInitialized) return
        if (isPlayingState.value) mediaPlayer.pause() else mediaPlayer.play()
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
        val uuid = liveUuids[i]
        // rodicovsky zamok: zamknuty kanal mimo 5-min okna -> vypytaj PIN
        if (ParentalLock.channelNeedsPin(this, srv.id, uuid)) {
            requestPin(onOk = { switchToIndex(i) }, onCancel = { })
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
        val media = buildMedia(url)
        mediaPlayer.media = media
        media.release()
        mediaPlayer.play()
        pokeControls()
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
            "close" -> finish()
            "list" -> openChannelList()
            "prev" -> { switchLive(-1); pokeControls() }
            "play" -> { togglePlayPause(); pokeControls() }
            "next" -> { switchLive(+1); pokeControls() }
            "audio" -> openAudioMenu()
            "subs" -> openSpuMenu()
            "epg" -> openEpgInApp()
            "pip" -> enterPipIfPossible()
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
                android.view.KeyEvent.KEYCODE_TV_DATA_SERVICE,
                android.view.KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                android.view.KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU -> { openEpgInApp(); return true }
                android.view.KeyEvent.KEYCODE_INFO -> { toggleInfo(); return true }
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
                if (down && n > 0) { switchToIndex(navChannelIndexState.value); closeChannelList() }
                return true
            }
            if (down && n > 0) {
                when (kc) {
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        { navChannelIndexState.value = (navChannelIndexState.value - 1 + n) % n; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_DOWN ->
                        { navChannelIndexState.value = (navChannelIndexState.value + 1) % n; return true }
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT ->
                        { navChannelIndexState.value = (navChannelIndexState.value - 8).coerceIn(0, n - 1); return true }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT ->
                        { navChannelIndexState.value = (navChannelIndexState.value + 8).coerceIn(0, n - 1); return true }
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
            // ovladanie zobrazene -> vlavo/vpravo naviguju panel, OK aktivuje
            // zvyrazneny prvok (hore/dole prepinaju kanal vyssie)
            if (controlsShown) {
                val order = playerControlOrder(canZap, seekablePlayback)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        remoteDebug = RemoteDebugPref.isEnabled(this)

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
                    cancelReconnect()  // uspesne pripojenie -> vynuluj pokusy
                    // po nabehnuti zisti ci stream ma video; ak nie -> rozhlas (logo)
                    videoCheckHandler.removeCallbacksAndMessages(null)
                    videoCheckHandler.postDelayed({
                        val n = runCatching { mediaPlayer.videoTracksCount }.getOrNull()
                        if (n != null && n >= 0) hasVideoState.value = n > 0
                    }, 1500)
                }
                MediaPlayer.Event.Paused -> { isPlayingState.value = false; refreshPipIfActive() }
                MediaPlayer.Event.Stopped -> { isPlayingState.value = false; refreshPipIfActive() }
                MediaPlayer.Event.Vout -> { if (event.voutCount > 0) hasVideoState.value = true }
                MediaPlayer.Event.EndReached -> {
                    isPlayingState.value = false
                    if (!seekablePlayback) {
                        // zivý stream "skoncil" = vypadok -> znovu pripojit
                        scheduleReconnect()
                    } else {
                        reachedEnd = true
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
        controlNavState.value = playerControlOrder(canZap, seekablePlayback).indexOf("play").coerceAtLeast(0)
        currentStreamUrl = streamUrl

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
                onAttach = { layout -> videoLayout = layout; mediaPlayer.attachViews(layout, null, false, false) },
                onStart = {
                    val doPlay: () -> Unit = {
                        currentStreamUrl = streamUrl
                        val media = buildMedia(streamUrl)
                        mediaPlayer.media = media
                        media.release()
                        mediaPlayer.play()
                        pokeControls()
                    }
                    if (requirePin && ParentalLock.needsPin(this)) {
                        requestPin(onOk = doPlay, onCancel = { finish() })
                    } else doPlay()
                },
                controlsPoke = controlsPokeState.value,
                infoPoke = infoPokeState.value,
                inPip = inPipState.value,
                hasVideo = hasVideoState.value,
                reconnecting = reconnectingState.value,
                centerLogoUrl = liveChannelsState.value.getOrNull(liveIndexState.value)?.piconUrl,
                onOpenEpg = { openEpgInApp() },
                onEnterPip = { enterPipIfPossible() },
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
                onPrevChannel = if (canZap) ({ switchLive(-1) }) else null,
                onNextChannel = if (canZap) ({ switchLive(+1) }) else null,
                liveChannels = if (canZap) liveChannelsState.value else emptyList(),
                liveCurrentIndex = liveIndexState.value,
                onSelectChannel = { idx -> if (idx != liveIndex) switchToIndex(idx) else pokeControls() },
                onRefreshEpg = {
                    lifecycleScope.launch { refreshOverlayEpg() }
                },
                numberEntry = numEntryState.value,
                pinPrompt = pinPromptState.value,
                pinLen = pinEntryState.value.length,
                pinError = pinErrorState.value,
                scrubFrac = scrubFractionState.value,
                progNextTitle = liveNextTitleState.value,
                progNextStart = liveNextStartState.value,
                progNextStop = liveNextStopState.value,
                zapPoke = zapPokeState.value,
                onClose = { finish() }
            )
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

    private fun enterPipIfPossible() {
        if (android.os.Build.VERSION.SDK_INT >= 26 &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            ::mediaPlayer.isInitialized
        ) {
            runCatching { enterPictureInPictureMode(buildPipParams()) }
        }
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

    override fun onStop() {
        saveDvrProgress()
        // v PiP rezime nechaj video bezat (PiP okno je stale viditelne)
        if (android.os.Build.VERSION.SDK_INT >= 24 && isInPictureInPictureMode) {
            super.onStop(); return
        }
        wasPlaying = ::mediaPlayer.isInitialized && mediaPlayer.isPlaying
        super.onStop()
        if (::mediaPlayer.isInitialized) {
            if (mediaPlayer.isPlaying) mediaPlayer.pause()
            // uvolni surface, nech sa po navrate da znova pripojit (inak cierna obrazovka)
            runCatching { mediaPlayer.detachViews() }
        }
    }

    override fun onDestroy() {
        saveDvrProgress()
        super.onDestroy()
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
        const val EXTRA_REQUIRE_PIN = "require_pin"
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
    numberEntry: String = "",
    controlsPoke: Int = 0,
    infoPoke: Int = 0,
    inPip: Boolean = false,
    hasVideo: Boolean = true,
    reconnecting: Boolean = false,
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
    var isPlaying by remember { mutableStateOf(true) }
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
                                        listOf(Color(0x33FFFFFF), Color(0x11FFFFFF))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Default.Radio,
                                contentDescription = null,
                                tint = Color.White,
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

        // prekrytie s prave zadavanym cislom kanala
        if (numberEntry.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xCC000000))
                    .padding(horizontal = 28.dp, vertical = 14.dp)
            ) {
                Text(numberEntry, color = Color.White, fontSize = 48.sp)
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().systemBarsPadding()) {
                val order = playerControlOrder(onPrevChannel != null, seekable)
                val selCtrl = order.getOrNull(controlNavIndex)
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
                val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                val k = (cfg.screenWidthDp / 640f).coerceIn(0.9f, 1.25f)
                val portrait = cfg.screenHeightDp >= cfg.screenWidthDp

                // Jeden spolocny info+ovladaci pruh dole
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .background(Color(0xE6000000))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Row(verticalAlignment = Alignment.Top) {
                        // cislo + logo + nazov kanala
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width((76 * k).dp)
                        ) {
                            if ((curCh?.number ?: 0) > 0) {
                                Text(
                                    "${curCh?.number}",
                                    color = Color.White,
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
                                color = Color(0xCCFFFFFF),
                                fontSize = (11 * k).sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        Spacer(Modifier.width(14.dp))
                        // popis relacie: nazov, cas, priebeh, popis, dalej
                        Column(Modifier.weight(1f)) {
                            if (progTitle.isNotBlank()) {
                                Text(
                                    progTitle,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = (16 * k).sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            if (hasNow) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        clock(progStart) + " \u2013 " + clock(progStop),
                                        color = Color(0xCCFFFFFF),
                                        fontSize = (12 * k).sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    if (!portrait) {
                                        androidx.compose.material3.LinearProgressIndicator(
                                            progress = { fracNow },
                                            modifier = Modifier
                                                .width((90 * k).dp)
                                                .padding(horizontal = 8.dp),
                                            trackColor = Color(0x55FFFFFF)
                                        )
                                    } else {
                                        Spacer(Modifier.width(8.dp))
                                    }
                                    Text(
                                        "$remainMin min",
                                        color = Color(0xCCFFFFFF),
                                        fontSize = (12 * k).sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
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
                                        .clip(RoundedCornerShape(8.dp))
                                        .then(
                                            if (seekFocused) Modifier.border(
                                                2.dp, Color.White, RoundedCornerShape(8.dp)
                                            ) else Modifier
                                        )
                                        .padding(horizontal = 4.dp)
                                ) {
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
                            if (progDesc.isNotBlank()) {
                                Text(
                                    progDesc,
                                    color = Color(0xBBFFFFFF),
                                    fontSize = (12 * k).sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            if (nextTitle.isNotBlank()) {
                                Text(
                                    clock(nextStart) + " \u2013 " + clock(nextStop) + "  " + nextTitle,
                                    color = Color(0x99FFFFFF),
                                    fontSize = (12 * k).sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                dateTime,
                                color = Color(0xCCFFFFFF),
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
                    Spacer(Modifier.height((6 * k).dp))
                    // Tlacidla: zavriet, zoznam, prev, play, next, audio, titulky, sw
                    val bk = if (portrait) 0.6f else (0.78f * k)
                    fun has(id: String) = order.contains(id)
                    // jedno tlacidlo podla id (zachytava okolity stav)
                    @Composable
                    fun barCtrl(c: String) {
                        when (c) {
                            "close" -> CircleButton("\u2715", selected = selCtrl == "close", scale = bk, onClick = onClose)
                            "list" -> CircleButton(
                                label = "\u2630", selected = selCtrl == "list", scale = bk,
                                onClick = { showChannelList = true; controlsVisible = false }
                            )
                            "prev" -> if (onPrevChannel != null) CircleButton(
                                label = "\u23EE", selected = selCtrl == "prev", scale = bk, onClick = onPrevChannel
                            )
                            "play" -> PlayPauseButton(
                                isPlaying = isPlaying,
                                selected = selCtrl == "play",
                                scale = bk,
                                onClick = {
                                    if (player.isPlaying) { player.pause(); isPlaying = false }
                                    else { player.play(); isPlaying = true }
                                }
                            )
                            "next" -> if (onNextChannel != null) CircleButton(
                                label = "\u23ED", selected = selCtrl == "next", scale = bk, onClick = onNextChannel
                            )
                            "epg" -> CircleButton(
                                label = "\u25A6", selected = selCtrl == "epg", scale = bk, onClick = onOpenEpg
                            )
                            "pip" -> CircleButton(
                                label = "\u29C9", selected = selCtrl == "pip", scale = bk, onClick = onEnterPip
                            )
                            "info" -> CircleButton(
                                label = "\u24D8", selected = selCtrl == "info", scale = bk,
                                onClick = { showInfo = !showInfo }
                            )
                            "sleep" -> CircleButton(
                                label = "\u23F2", selected = selCtrl == "sleep", scale = bk,
                                onClick = onOpenSleep
                            )
                            "audio" -> if (portrait) CircleButton(
                                label = "\uD83D\uDD0A", selected = selCtrl == "audio", scale = bk,
                                onClick = { menu = if (menu == "audio") null else "audio" }
                            )
                            else TextChip("\uD83D\uDD0A Audio", selected = selCtrl == "audio", scale = bk) {
                                menu = if (menu == "audio") null else "audio"
                            }
                            "subs" -> if (portrait) CircleButton(
                                label = "\uD83D\uDCAC", selected = selCtrl == "subs", scale = bk,
                                onClick = { menu = if (menu == "spu") null else "spu" }
                            )
                            else TextChip("\uD83D\uDCAC Titulky", selected = selCtrl == "subs", scale = bk) {
                                menu = if (menu == "spu") null else "spu"
                            }
                        }
                    }
                    val gap = Arrangement.spacedBy((8 * k).dp)
                    if (portrait) {
                        // PORTRET: vsetky tlacidla v jednom vycentrovanom rade (zmensene, aby sa zmestili)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(
                                3.dp, Alignment.CenterHorizontally
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            barCtrl("close")
                            barCtrl("pip")
                            if (has("list") && liveChannels.isNotEmpty()) barCtrl("list")
                            if (has("epg")) barCtrl("epg")
                            if (has("prev")) barCtrl("prev")
                            barCtrl("play")
                            if (has("next")) barCtrl("next")
                            barCtrl("audio")
                            barCtrl("subs")
                            barCtrl("sleep")
                            barCtrl("info")
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
                            barCtrl("pip")
                            if (has("list") && liveChannels.isNotEmpty()) barCtrl("list")
                            if (has("epg")) barCtrl("epg")
                        }
                        // stred: prepinanie + play/stop
                        Row(
                            horizontalArrangement = gap,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (has("prev")) barCtrl("prev")
                            barCtrl("play")
                            if (has("next")) barCtrl("next")
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
                    .background(Color(0xCC000000))
                    .clickable { showInfo = false },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Surface(
                    color = Color(0xF21C1C1C),
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
                                    color = Color(0xCCFFFFFF),
                                    fontSize = 15.sp,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(14.dp))
                        }
                        Text(
                            progTitle.ifBlank { title },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                        if (tRange.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(tRange, color = Color(0xCCFFFFFF), fontSize = 15.sp)
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
                                trackColor = Color(0x55FFFFFF)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text("Ostáva $remainI min", color = Color(0x99FFFFFF), fontSize = 13.sp)
                        }
                        if (progDesc.isNotBlank()) {
                            Spacer(Modifier.height(14.dp))
                            Text(progDesc, color = Color(0xDDFFFFFF), fontSize = 16.sp, lineHeight = 22.sp)
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
                                color = Color(0x99FFFFFF),
                                fontSize = 14.sp
                            )
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

        // Vyber dlzky casovaca uspatia — vertikalne, navigacia z Activity
        if (showOptions) {
            val opts = listOf(
                stringResource(R.string.sleep_off),
                "15 min", "30 min", "45 min", "60 min", "90 min"
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0x99000000))
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
                        .background(Color(0xEE202020))
                        .padding(8.dp)
                ) {
                    Text(
                        stringResource(R.string.sleep_timer),
                        color = Color.White,
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
                            Text(label, color = Color.White)
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
                header = if (menu == "audio") "Zvuková stopa" else "Titulky",
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

        // Rodicovsky zamok: zadanie PIN (cislice z dialkoveho riesi Activity)
        if (pinPrompt) {
            Box(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        androidx.compose.ui.res.stringResource(R.string.plock_enter),
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        repeat(4) { i ->
                            Box(
                                Modifier.size(20.dp).clip(CircleShape).background(
                                    if (i < pinLen) Color(0xFF3B82F6) else Color(0x44FFFFFF)
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
                        color = Color(0xAAFFFFFF),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
