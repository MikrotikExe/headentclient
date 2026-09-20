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
 * Live player on libVLC. Decodes MPEG-2 + MP2/AC3/EAC3/DTS in software.
 * The controls are a Compose overlay: play/pause, close, audio track selection
 * (language) and subtitles (libVLC get/setAudioTrack, get/setSpuTrack).
 */
class PlayerActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }


    // M656: libVLC + MediaPlayer lifecycle in VlcEngine.kt (M677: accessed directly via engine)
    private val engine: VlcEngine by lazy {
        VlcEngine(this, mediaFactory, vlcEvents,
            recreates = { stall.recreates },
            bumpSurfaceGen = { videoSurfaceGen.value = videoSurfaceGen.value + 1 },
            resetStall = { stall.reset() })
    }
    // M655: stream state (feeders, HTSP flags, URL) in StreamState.kt (M677: accessed directly via stream)
    private val stream = StreamState()
    // HTSP subtitles: we take the complete language list from the metadata (feeder.subtitleStreams),
    // not from libVLC (which only has the languages that have already "spoken"). We map the selection onto the real
    // libVLC track by the English language name (libVLC does not tag DVB subtitles with a code).
    // M637: track state (audio/subtitles/profile) in TrackState.kt
    private val tracks: TrackState by lazy {
        TrackState(this,
            player = { if (engine.ready && !engine.tornDown) engine.player else null },
            htspFeeder = { stream.htspFeeder })
    }
    // M392: the subtitle state from before the stream restart on a profile change (HTTP live) —
    // a new container (e.g. matroska) may have a default subtitle track that
    // libVLC would switch on by itself; after the restart we therefore restore the user's original choice.
    // M392-fix: the user's persistent choice for HTTP live subtitles. Default OFF
    // (same as HTSP, where null = off). Enforced on every ESAdded,
    // so even a late-registered default track (matroska on a slow box)
    // will not turn subtitles on. Only switching them on manually in the menu (D-pad or touch) clears it.
    // M262: whether the HTSP mode has already been determined for this session. doPlay (the initial
    // playback) sets it; if, however, the user switches channel before doPlay (e.g.
    // leaving a locked channel's PIN prompt), switchToIndex initialises HTSP.
    private var htspInitDone = false
    // M647: HTSP timeshift in TimeshiftController.kt (M677: accessed directly via timeshift)
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
    // timeshift "engaged" (after the first pause) -> only then do RW/FF and the double-tap make sense

    // ===== Modern TV overlay (channel cards + control bar) — ModernOverlayController.kt (M642) =====
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
                    okLongFired = true   // the guard swallows the OK-up (otherwise it would confirm a menu item)
                    ctxMenu.open(cardIndex)
                }
            })
    }

    private val isTvBox by lazy { isTvUiMode(this) }   // M679

    /** The modern overlay only makes sense on TV, in modern mode, on live with a channel list. */
    private fun modernTvActive(): Boolean =
        isTvBox && UiModePref.get(this) == UiModePref.MODERN &&
            !seekablePlayback && live.uuids.size > 1

    // ===== M490 / M669: recording the currently running programme — DvrRecordController.kt =====
    // Both the state and the actions are shared by all the entry points (the classic bar, the "More" panel, the TV overlay). M677: only
    // the public delegates the composables read via dvrActivity remain; the rest go directly through dvrRec.
    private val dvrRec: DvrRecordController by lazy {
        DvrRecordController(this, lifecycleScope, live,
            epgUpcoming = epg.upcoming,
            recInProgressByChan = recInProgressByChan,
            refreshRecordingOnly = { epg.refreshRecordingOnly() })
    }
    val dvrExistingState: androidx.compose.runtime.MutableState<sk.tvhclient.shared.model.DvrEntry?> get() = dvrRec.existingState
    /** Should the recording control be shown at all? */
    fun dvrRecordVisible(): Boolean = dvrRec.recordVisible()
    /** Determines the rights and the recording state for the currently watched programme (start, channel switch). */
    fun refreshDvrState() { dvrRec.refreshState() }
    /** Record the currently running programme, or cancel an already scheduled recording. */
    fun toggleRecordCurrent() { dvrRec.toggleRecordCurrent() }

    // M639: live playback state in LiveSession (M677: accessed directly via live)
    private val live = LiveSession()
    // for reattaching the video after returning from the background
    private var videoLayout: VLCVideoLayout? = null
    private var subOverlay: SubtitleOverlayView? = null
    // ===== M553 / M627: teletext — state and controls in TeletextController, drawing in TeletextOverlay =====
    private val ttx: TeletextController by lazy {
        TeletextController(this,
            liveServer = { live.server },
            liveUuid = { live.uuidState.value },
            seekable = { seekablePlayback },
            onOpened = { modernOv.close() })
    }
    /** M552: the current channel's teletext (HTSP: data from the feeder, HTTP: a separate branch of our own). */
    val teletext: TeletextSession get() = ttx.session
    fun teletextVisible(): Boolean = ttx.visible()
    fun openTeletext() { ttx.open() }
    fun closeTeletext() { ttx.close() }

    // Picture-in-Picture
    private val inPipState = androidx.compose.runtime.mutableStateOf(false)
    // false = audio-only (radio) -> show the logo instead of black
    // automatic reconnection of the live stream after a network dropout
    // M636: reconnect timing/state in ReconnectController.kt; what is actually done on an attempt is below
    private val reconnect: ReconnectController by lazy {
        ReconnectController(this,
            playerReady = { engine.ready },
            isPlaying = { engine.player.isPlaying })
    }
    // the spinner while seeking in timeshift (a short pipe -> libVLC resync)
    private val seekingState = androidx.compose.runtime.mutableStateOf(false)
    private var seekSpinnerJob: kotlinx.coroutines.Job? = null
    // YouTube-style double-tap seeking: accumulated seconds (+/-), 0 = hidden
    // M648: seek target calculation, M594 recovery and the double-tap in DvrSeek.kt
    private val dvrSeek: DvrSeek by lazy {
        DvrSeek(this, lifecycleScope,
            durMs = { if (dvr.durationMs > 0) dvr.durationMs else (if (engine.ready) engine.player.length else 0L) },
            recording = { dvr.recording },
            playheadMs = { dvr.playheadMsState.value },
            seekable = { engine.ready && seekablePlayback },
            performSeek = { target, from, dur -> seekDvrTo(target, from, dur) })
    }
    // M634: EPG now/next + cache in PlayerEpgStore.kt (M677: accessed directly via epg)
    private val epg: PlayerEpgStore by lazy {
        PlayerEpgStore(this,
            liveServer = { live.server },
            liveChannels = live.channelsState,
            recInProgress = recInProgressByChan,
            onDvrStateChanged = { refreshDvrState() })
    }

    // D-pad / remote: the signal to show the controls, info for seeking and the sw decoder
    private val controlsPokeState = androidx.compose.runtime.mutableStateOf(0)
    private val isPlayingState = androidx.compose.runtime.mutableStateOf(true)
    // D-pad navigation of the channel list in the player
    private val openChannelListState = androidx.compose.runtime.mutableStateOf(0)
    private val navChannelIndexState = androidx.compose.runtime.mutableStateOf(0)
    // M369: the active group filter in the channel list + a flag for whether focus is on the group pill.
    private val activeGroupLabelState = androidx.compose.runtime.mutableStateOf("")
    private val groupPickerState = androidx.compose.runtime.mutableStateOf(false)
    // M370 / M635: channel search by name — state and keys in ChannelSearch.kt
    private val search: ChannelSearch by lazy {
        ChannelSearch(this,
            onSelect = { uuid -> selectLiveByUuid(uuid) },
            onDeactivate = { groupPickerState.value = false })
    }
    private var seekablePlayback = false
    // Entering a channel by digits from the remote (M635: ChannelNumberEntry.kt)
    private val numEntry: ChannelNumberEntry by lazy {
        ChannelNumberEntry(lifecycleScope) { typed ->
            val idx = LivePlaylist.channels.indexOfFirst { it.number == typed }
            if (idx in live.uuids.indices) { switcher.switchToIndex(idx); pokeControls() }
        }
    }

    // M654: building the libVLC Media (URL / feeder, decoder, deinterlacing, demux) in MediaFactory.kt
    private val mediaFactory: MediaFactory by lazy { MediaFactory(this) { engine.libVlc } }

    // M655: opening the stream (HTTP / feeder / DVR / HTSP, auth probe) in StreamOpener.kt
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
        // Modern mode on TV with live: the old control panel is not shown (M325/M327)
        if (modernTvActive()) return
        controlsPokeState.value = controlsPokeState.value + 1
    }
    // INFO key / button -> a window with the current programme's details
    private val infoPokeState = androidx.compose.runtime.mutableStateOf(0)
    private fun toggleInfo() { infoPokeState.value = infoPokeState.value + 1 }
    // EPG key / button -> open the TV guide (the grid) in the main app
    // M383-fix: the EPG may only be opened AFTER the PiP transition has completed — a startActivity
    // fired during the PiP transition is swallowed by the system on many devices (you see
    // only the PiP window, the EPG "catches up" once it is expanded). Hence: enterPip -> wait for
    // onPictureInPictureModeChanged(true) -> only then startActivity.
    private var pendingEpgAfterPip = false

    private fun launchEpgActivity() {
        val i = android.content.Intent(this, MainActivity::class.java).apply {
            putExtra("open_epg", true)
            // M591: from the radio player the STATIONS guide should open, not the TV channels one
            if (live.playKind == "radio") putExtra("epg_radio", true)
            // remember the current live channel so BACK from the EPG returns to the player on it
            if (!seekablePlayback) live.uuids.getOrNull(live.index)?.let { putExtra("epg_return_uuid", it) }
        }
        runCatching { startActivity(i) }
    }

    private fun openEpgInApp() {
        // M601-fix: radio does not go into PiP — autoPipIfPossible does a handoff to the background
        // (and ends the activity), but it returned true, so the TV guide only opened after
        // the 1.2 s fallback; in the meantime the intro was visible. Open the guide immediately, the handoff
        // (modern mode) comes after it; in classic the player stays under the guide
        // as before.
        if (live.playKind == "radio") {
            launchEpgActivity()
            radioHandoffIfPossible()
            return
        }
        // on phones: enter PiP so the video runs in a floating window above the EPG
        if (autoPipIfPossible()) {
            pendingEpgAfterPip = true
            // fallback: if the callback does not arrive (PiP fails on the way), open the EPG anyway
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
        timeshift.flushNow()   // deliver the accumulated skip so the server stays consistent
        if (isPlayingState.value) {
            if (stream.htspStream) stream.htspFeeder?.pause()         // stop HTSP delivery (even without timeshift)
            if (stream.htspLive) {
                // the first pause "engages" timeshift: from here on the buffer and the red counter are counted
                timeshift.onPaused()
                // engaging timeshift adds the seek controls (tsrew before play) and shifts the
                // indices — re-anchor focus on play/pause so it does not "jump" onto seeking
                val ord = playerControlOrder(!seekablePlayback && live.uuids.size > 1, seekablePlayback, pipButtonVisible(), true, profileSwitchAvailable(), dvrRecordVisible())
                controlNavState.value = ord.indexOf("play").coerceAtLeast(0)
            }
            isPlayingState.value = false
            engine.player.pause()
        } else {
            if (stream.htspStream) stream.htspFeeder?.resume()
            if (stream.htspLive) timeshift.onResumed()
            isPlayingState.value = true
            reconnect.resetDvrReopen()   // manual play -> allow new attempts to load newer data
            engine.player.play()
        }
    }

    /** A new live start (a fresh subscription = live) -> clear the timeshift. */
    private fun resetTimeshift() {
        timeshift.reset()
        hideSeekSpinner()
        // M492: the double-tap accumulator and the hint have to be cleared too — otherwise after switching
        // media the starting point from the previous recording would remain. Clear the playhead for the same reason.
        dvrSeek.resetForNewMedia()
        dvr.playheadMsState.value = 0L
    }

    /** Relative skip within timeshift (seconds; negative = backwards). */
    private fun timeshiftSkip(seconds: Int) { if (stream.htspLive) timeshift.skip(seconds) }

    /** Turns off the seek spinner (Playing/Buffering reached 100 %, or a new live start). */
    private fun hideSeekSpinner() { seekSpinnerJob?.cancel(); seekingState.value = false }

    /** The spinner in the middle during a resync; a Playing/Buffering event turns it off, fallback after 4 s. */
    private fun showSeekSpinner() {
        seekingState.value = true
        seekSpinnerJob?.cancel()
        seekSpinnerJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(4000)
            seekingState.value = false
        }
    }

    /** Seek a DVR recording to the target programme-relative time by REBUILDING the stream.
     *  Direct URL -> a new Media with :start-time (libVLC seeks via HTTP Range).
     *  Feeder/pipe -> restart the HTTP feed at an estimated byte offset (a pipe cannot be seeked).
     *  In both cases it seeds the playhead clock to the target time. */
    private fun seekDvrTo(targetMs: Long, fromMs: Long, dur: Long) {
        val url = stream.currentStreamUrl ?: return
        if (!engine.ready) return
        val offsetMs = if (dvr.progStartSec > 0 && dvr.realStartSec in 1 until dvr.progStartSec)
            (dvr.progStartSec - dvr.realStartSec) * 1000 else 0L
        val fileMs = (offsetMs + targetMs).coerceAtLeast(0L)   // time in the file (0 = the real start of the recording)
        reconnect.clearPending()
        // An ordinary seek = a short stream restart; show only the light seek spinner, NOT "Reconnecting"
        // (that belongs only to a real dropout/reconnect). A Playing/Buffering event turns it off,
        // fallback after 6 s in case the event never arrives.
        seekingState.value = true
        seekSpinnerJob?.cancel()
        seekSpinnerJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(6000)
            seekingState.value = false
        }
        opener.seekDvrFile(url, fileMs, offsetMs, fromMs, dur)   // M670: the body is in StreamOpener
        // M594: the time and target of the last seek — when an end/error arrives right after it,
        // it is a hit EOF (the file is shorter than the duration from the EPG) and not a real end
        dvrSeek.markSeek(targetMs)
        // playhead straight to the target position + a seed for the clock (after the restart player.position is
        // invalid, the clock must not read it - it takes the seed and ticks on from there)
        dvr.playheadMsState.value = targetMs
        dvr.seekSeedState.value = targetMs
    }

    /** A double tap on the left/right side (YouTube-style): a 10 s skip.
     *  DVR -> a seek within the media (accumulated); active timeshift -> subscriptionSkip immediately. */
    private fun doubleTapSeek(forward: Boolean) {
        when {
            seekablePlayback -> dvrSeek.doubleTap(forward, applyImmediately = false)
            stream.htspLive -> {
                if (timeshift.maxRewindMs() <= 0L) return   // timeshift is engaged only by a pause, until then there is nothing to seek through
                timeshiftSkip(if (forward) 10 else -10)   // live timeshift: cheap, seek immediately
                dvrSeek.doubleTap(forward, applyImmediately = true)
            }
            else -> return   // no seeking (live without timeshift) -> ignore
        }
    }

    /** Horizontal dragging (MX Player) -> a skip of the given number of seconds (negative = backwards). */
    private fun scrubSeek(seconds: Int) {
        if (seconds == 0) return
        when {
            seekablePlayback -> dvrSeek.seekRelative(seconds.toLong() * 1000L)
            stream.htspLive -> { if (timeshift.maxRewindMs() > 0L) timeshiftSkip(seconds) }  // the same convention as seekRelative: negative = backwards
            else -> {}
        }
    }

    // M473 / M669: currentEventId / currentLiveEvent / runningRecordingHere / currentEventRecording in DvrRecordController

    /** TV/box (Android TV) — for detecting where the archive choice should be shown. */
    private fun isTvDevice(): Boolean = isTvUiMode(this)   // M679: DeviceKind.kt

    // ===== M657: the core of channel switching (selectChannelOrArchive, resolveArchiveChoice,
    // rememberPlayback, playRecordingFromStart, saveLastLive, switchToIndex) — ChannelSwitcher.kt
    // (M677: calls go directly through the switcher) =====
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

    /** Closing the player: if it was started via "from the start" from live TV, return to the original channel. */
    /** M342/M344: BACK from playing radio = a handoff to RadioPlayerService.
     *  Returns true if the handoff happened (the activity finished) — the radio keeps playing
     *  in the background with the mini bar. Modern: phone and TV. M624: classic on the
     *  phone too (the mini bar is now in classic as well); classic on TV as before — it has no
     *  panel, so the radio would play with no controls. */
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
        // M494: leaving the player = there is nothing left to restore (the user ended up
        // on the list, not on a channel). Returning to a live channel from the archive is not a departure.
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
        } else if (!autoPipIfPossible()) finish()   // M343: respect Auto-PiP being off — BACK = stop, not PiP
    }

    /** Switches to the neighbouring live channel (delta +1 / -1). */
    /** A short haptic tick on a channel switch — phone/tablet in modern mode only (M336). */
    private fun hapticChannelSwitch() {
        if (isTvBox) return
        if (UiModePref.get(this) != UiModePref.MODERN) return
        runCatching {
            window.decorView.performHapticFeedback(
                android.view.HapticFeedbackConstants.KEYBOARD_TAP
            )
        }
    }

    // M407 / M652: fast-zapping debounce in ZapDebounce.kt
    private val zap: ZapDebounce by lazy {
        ZapDebounce(live,
            haptic = { hapticChannelSwitch() },
            pokeOnCommit = { ZapOverlayPref.get(this) },
            commit = { target, poke -> switcher.switchToIndex(target, poke = poke) })
    }
    private fun switchLive(delta: Int) { zap.switchLive(delta) }

    // ===== M369 / M640: the group filter in the channel list — LiveGroups.kt =====
    private val groups: LiveGroups by lazy {
        LiveGroups(this, live,
            epgUpcoming = { epg.upcoming.value },
            navIndex = navChannelIndexState,
            groupLabel = activeGroupLabelState)
    }

    // ===== M370 / M635: channel search — ChannelSearch.kt =====
    /** Picking a channel from the search results: switches (the group too if needed) and starts playing. */
    private fun selectLiveByUuid(uuid: String) {
        okLongFired = true   // swallows the following OK-up, otherwise the list would confirm a different channel (index 0)
        search.close()
        closeChannelList()
        var i = live.uuids.indexOf(uuid)
        if (i < 0) { groups.apply(LivePlaylist.GROUP_ALL); i = live.uuids.indexOf(uuid) }
        if (i >= 0) switcher.selectChannelOrArchive(i, poke = false)
    }

    // overlay state (from Compose) — while one is open the D-pad is handled by us (the list) or by Compose (the menu)
    private var trackMenuOpen = false
    private var channelListOpen = false
    private val closeChannelListState = androidx.compose.runtime.mutableStateOf(0)
    // Options (Audio / Subtitles / SW decoding) — a vertical overlay, navigated from the Activity
    private var optionsOpen = false
    private var remoteDebug = false
    /** The PiP button in the controls (M349-fix2): show it only when Auto-PiP
     *  is OFF in settings — then the only way into PiP is manual. With
     *  Auto-PiP on the button is pointless (BACK enters PiP by itself).
     *  pipSupported also rules out TVs (they do not have FEATURE_PICTURE_IN_PICTURE). */
    // M575: on TV the button is not offered even when the box reports PiP — the window cannot
    // be controlled with the remote (issue #11)
    private fun pipButtonVisible(): Boolean = pip.supported && !isTvDevice() && !AutoPipPref.get(this)

    private var controlsShown = false
    private val openOptionsState = androidx.compose.runtime.mutableStateOf(0)
    private val closeOptionsState = androidx.compose.runtime.mutableStateOf(0)
    private val optionsNavState = androidx.compose.runtime.mutableStateOf(0)
    // Sleep timer
    // M629: the sleep timer in SleepTimer.kt
    private val sleep: SleepTimer by lazy { SleepTimer(this) { finish() } }
    // Control panel navigation (we drive focus from the Activity, not through Compose focus)
    private val controlNavState = androidx.compose.runtime.mutableStateOf(0)
    private var okLongFired = false

    // Parental lock (PIN) — state and keys in PinPrompt.kt (M629), PinDialog drawing in PlayerUi
    private val pin: PinPrompt by lazy {
        PinPrompt(this,
            isTv = { isTvDevice() },
            channelCount = { live.uuids.size },
            openChannelList = { openChannelList() },
            switchToIndex = { idx -> switcher.switchToIndex(idx) },
            onRequested = { okLongFired = false })   // the PIN prompt takes over input; the OK gesture is thereby ended
    }
    private fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, markUnlock: Boolean = true, channelIndex: Int? = null) {
        pin.request(onOk, onCancel, markUnlock, channelIndex)
    }
    // The "Resume playback" dialog — D-pad handling in dispatchKeyEvent (on a box it had no focus)
    private val resumePromptState = androidx.compose.runtime.mutableStateOf(false)
    private val resumeSelState = androidx.compose.runtime.mutableStateOf(1)   // 0=No, 1=Yes (default)
    private val resumeAnswerState = androidx.compose.runtime.mutableStateOf(0) // 0=none, 1=Yes, 2=No
    // The choice on an archived channel (live / from the start) right inside the player
    private val archiveChoiceIdxState = androidx.compose.runtime.mutableStateOf(-1) // index of the channel awaiting the choice, -1 = none
    private val archiveChoiceSelState = androidx.compose.runtime.mutableStateOf(0)   // 0=live, 1=from the start (D-pad)
    private val recInProgressByChan = androidx.compose.runtime.mutableStateOf<Map<String, sk.tvhclient.shared.model.DvrEntry>>(emptyMap())
    // Return to the original live channel after closing a DVR player started via "from the start"
    private var returnLiveUuid: String? = null
    private var returnLiveTitle: String? = null

    // DVR scrub (M597/M598) — ScrubController.kt (M646); accessed directly via scrub
    private val scrub: ScrubController by lazy {
        ScrubController(lifecycleScope,
            barMs = { if (dvr.recording) (dvr.durationMs - 45_000L).coerceAtLeast(1L) else dvr.durationMs },
            durMs = { if (dvr.durationMs > 0) dvr.durationMs else (if (engine.ready) engine.player.length else 0L) },
            playheadMs = { dvr.playheadMsState.value },
            seekable = { engine.ready && seekablePlayback },
            seekAbsolute = { ms -> dvrSeek.seekAbsolute(ms) },
            poke = { pokeControls() })
    }

    // M651: keys during normal playback (block 4 of dispatchKeyEvent) in PlaybackKeys.kt
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
                    okLongFired = true; openChannelList()  // okLongFired swallows the following OK-up
                }
                override fun modernPlaybackOk(down: Boolean, event: android.view.KeyEvent): Boolean =
                    modernOv.handlePlaybackOk(down, event) {
                        okLongFired = true   // swallows the OK-up, otherwise the up would immediately confirm the channel and close the list
                        openChannelList()
                    }
                override fun beginScrub(dir: Int) { this@PlayerActivity.beginScrub(dir) }
                override fun initScrub() { scrub.init() }
            })
    }

    /**
     * M598: an arrow with the controls hidden in the archive. Until now it seeked straight away (-15 s / +30 s)
     * — the picture stuttered on every press and while holding, even though the user was still only looking
     * for the spot. Now the bar opens with a cursor, the cursor moves one step and the seek itself
     * is performed only once it settles (M597) or after OK.
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

    // A counter for refreshing the lock icons in the in-player list after a lock change.
    private val lockTickState = androidx.compose.runtime.mutableStateOf(0)

    /** Locks/unlocks a channel in the player's list (like a long press on the phone). PIN-protected. */
    private fun toggleLockAt(idx: Int) {
        val srv = live.server ?: return
        val uuid = live.uuids.getOrNull(idx) ?: return
        val doToggle: () -> Unit = {
            val now = ParentalLock.isChannelLocked(this, srv.id, uuid)
            ParentalLock.setChannelLocked(this, srv.id, uuid, !now)
            lockTickState.value = lockTickState.value + 1
        }
        // if the lock is active and we are outside the window, verify the PIN first; once entered the grace window applies
        // (the same "do not ask for X min after unlocking" rule as when switching) -> markUnlock = true
        if (ParentalLock.needsPin(this)) requestPin(onOk = doToggle, onCancel = { }, markUnlock = true)
        else doToggle()
    }

    /**
     * M544: callbacks for PlayerUi that are passed CONDITIONALLY (`if (...) cb else null`)
     * are fixed fields of the activity, not lambdas created in the composition. Compose memoizes
     * a lambda argument into a slot; when the condition flipped (htspStreamState, canZap)
     * inside `key(videoSurfaceGen)` the slots shifted and on recomposition the slot
     * expected to hold a Function1 contained a different lambda -> ClassCastException
     * "$$ExternalSyntheticLambda7 cannot be cast to Function1" (Pixel 9, 1.0.5).
     * A field takes up no slot, so there is nothing to shift.
     */
    private val pickHtspSpuCb: (Int) -> Unit = { id -> trackMenu.pickHtspSpu(id) }
    private val prevChannelCb: () -> Unit = { switchLive(-1) }
    private val nextChannelCb: () -> Unit = { switchLive(+1) }

    // --- Channel context menu in the player (long-press OK / long press) — ChannelContextMenu.kt (M641) ---
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

    // ===== M541 / M638: favourites reordering mode (D-pad) — FavReorder.kt =====
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

    // --- Programme info (details) in the player — ChannelInfo.kt (M643) ---
    private val info: ChannelInfo by lazy {
        ChannelInfo(lifecycleScope, live,
            epgUpcoming = { epg.upcoming.value },
            cacheChannelEpg = { uuid, list -> epg.cacheChannelEpg(uuid, list) },
            dvrRecordVisible = { dvrRecordVisible() },
            toggleRecord = { toggleRecordCurrent() },
            onShown = { zapBar.hide() })
    }
    // M280: confirmation of ending live playback (BACK) — like the exit dialog in the menu
    private val exitConfirmState = androidx.compose.runtime.mutableStateOf(false)
    private val exitConfirmSelState = androidx.compose.runtime.mutableStateOf(0) // 0=Cancel, 1=Exit

    // ---- M430 / M628: the compact zapping bar — both state and drawing in ZapBar.kt ----
    private val zapBar: ZapBar by lazy {
        ZapBar(lifecycleScope,
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
                    // M600-fix: wait until the video returns from the preview rectangle to full screen
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
    /** M605: the "TV channels" / "Radio" tile opened the player with the list right at start. */
    private var listFirst = false

    private fun openChannelList() {
        // M371: open it even with 1 channel if there are groups to switch between (e.g. Favourites with 1 channel),
        // otherwise the filtered list could no longer be opened or switched back.
        groups.refreshFavOrder()   // M541: the favourites may have changed in the Channels list
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

    /** Opens the sleep timer duration picker (available by touch and by D-pad). */
    private fun openSleepMenu() {
        optionsNavState.value = 0
        openOptionsState.value = openOptionsState.value + 1
    }

    /** Sleep timer duration picker. */
    private fun selectOption(idx: Int) {
        sleep.set(sleep.durations.getOrElse(idx) { 0 })
        closeOptions()
    }

    // --- Track menu (audio/subtitles) driven from the Activity; state and helper functions in TrackState (M637) ---
    // M671: track menu actions (HTSP subtitles, profile, D-pad selection) in TrackMenuController.kt
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

    /** M383: the profile switcher only makes sense on HTTP live (not HTSP, not DVR,
     *  not an external URL — there the profile does not exist or cannot be changed). */
    private fun profileSwitchAvailable(): Boolean = tracks.profileSwitch.value

    // --- Activating the highlighted item of the control panel ---
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

    // M658: the chain of dispatchKeyEvent guards in PlayerKeyRouter.kt (order = behaviour)
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

    // DVR progress (position tracking for the archive) — M665: state in DvrPlayback.kt (M677: accessed directly via dvr)
    private val dvr = DvrPlayback(this)

    private fun saveDvrProgress() {
        dvr.saveProgress(if (engine.ready && !engine.tornDown) engine.player else null)
    }

    /** M623: radio with the "Radio plays in the background" option (phone and TV) — sending the player
     *  to the background (screen off, lock, home screen, another app) does not pause the radio.
     *  In the player itself the screen stays lit (KEEP_SCREEN_ON as on TV). */
    private fun radioBackground(): Boolean =
        live.playKind == "radio" && RadioBackgroundPref.get(this)

    private fun keepScreenOn(on: Boolean) {
        runOnUiThread {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** M658: attaching the video layout from PlayerUi (originally the onAttach lambda in setContent). */
    private fun attachVideo(layout: VLCVideoLayout) {
        videoLayout = layout
        engine.player.attachViews(layout, null, false, false)
        // M539-fix2: the new player was waiting for its own (new) surface — start it now
        if (engine.onSurfaceAttached()) {
            layout.post { runCatching { if (!engine.tornDown) engine.player.play() } }
        }
        // our own subtitle overlay above the video (we decode DVB subtitles ourselves,
        // they do not go into libVLC) — synchronised to the player's time
        subOverlay?.let { old ->
            old.stopTicker()
            (old.parent as? ViewGroup)?.removeView(old)   // do not leave a frozen old overlay behind (doubled text)
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

    /** M675: orientation, keep-screen-on, the stream locks and immersive fullscreen (extracted from onCreate). */
    private fun setupWindow() {
        // default screen rotation per the setting (auto = fullUser as in the manifest)
        runCatching {
            requestedOrientation = when (OrientationPref.get(this)) {
                OrientationPref.PORTRAIT -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                OrientationPref.LANDSCAPE -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                else -> android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
            }
        }
        remoteDebug = RemoteDebugPref.isEnabled(this)
        // Keep the screen on from player start (the screensaver/ambient mode on boxes must not kick in)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        streamLocks.acquire()  // M452

        // Immersive fullscreen — hide both the status and the navigation bar so they do
        // not cover the controls. The bars can be pulled out with a swipe.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        insetsController.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    /** M675: preparing the live-zapping state and the state holders before setContent (extracted from onCreate). */
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
        // Live zapping: prepare the list of neighbouring channels
        if (directUrl == null && channelUuid != null && LivePlaylist.channels.isNotEmpty()) {
            live.uuids = LivePlaylist.channels.map { it.uuid }
            live.names = LivePlaylist.channels.map { it.name }
            live.index = LivePlaylist.index.takeIf { it in live.uuids.indices }
                ?: live.uuids.indexOf(channelUuid)
            live.server = server
            switcher.saveLastLive(server.id, channelUuid)
            epg.hydrateEpgFromDisk(server)   // M275: load the EPG from disk (survives a box restart)
        }
        switcher.rememberPlayback()   // M494: already at start, not only after the first switch
        live.channelsState.value = LivePlaylist.channels
        // M281: fill in now/next from the cache (disk/process) for the visible list straight away, so the programme
        // names under the channels show at once even after a restart (they used to wait for a network refresh).
        epg.applyCachedEpgToChannels()
        // M605-fix: the list first — the last channel starts normally (playing behind the list
        // as a preview) and the list opens straight away; originally nothing played, which the user did not want
        listFirst = args.listFirst && live.uuids.size > 1
        live.indexState.value = live.index
        live.titleState.value = channelTitle
        live.uuidState.value = channelUuid
        live.progStartState.value = progStart
        live.progStopState.value = progStop
        live.progTitleState.value = progTitle
        val canZap = directUrl == null && live.uuids.size > 1
        seekablePlayback = directUrl != null
        // the control panel's default highlight = play (not the X)
        controlNavState.value = playerControlOrder(canZap, seekablePlayback, pipButtonVisible(), timeshift.engaged.value, profileSwitchAvailable(), dvrRecordVisible(), teletextVisible()).indexOf("play").coerceAtLeast(0)
        stream.currentStreamUrl = streamUrl
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The mini radio (M340) must not play alongside the full player
        RadioPlayerService.stop(this)
        // Close the previous player instance (e.g. one hanging in PiP with the old channel),
        // so that an old PiP does not stay hanging when the channel is switched. The new one opens full screen.
        // M427: if the old instance hangs in PiP, a plain finish() closes the activity,
        // but the pinned PiP window (pinned task) can stay hanging as an empty card
        // — the system has to be told to remove the whole task. Outside PiP finish() is enough.
        liveInstance?.get()?.let { old ->
            if (old !== this) runCatching {
                if (old.isInPictureInPictureMode) old.finishAndRemoveTask() else old.finish()
            }
        }
        liveInstance = java.lang.ref.WeakReference(this)
        val args = PlayerArgs.from(intent)   // M658: all the intent extras in one place
        // Return to the original live channel after closing (for "Play from start" from the player)
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
        // A programme in progress: the duration grows towards the live edge; the bar must ALWAYS be visible.
        // If we have the programme's boundaries we compute relative to its start (capped by the programme's length).
        // If the boundaries are missing (the recording has no start/stop filled in) we keep pace with the length from VLC.
        // M658: the calculation and the one-second loop are in DvrDurationTicker (M528 inside).
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

        // Saved position: offer to resume if it has not been watched to the end and is not
        // right at the start/end
        val saved = dvr.uuid?.let { WatchProgress.get(this, server.id, it) }
        val resumeMs = if (saved != null && !saved.completed && saved.posMs > 30_000 &&
            (durationMs <= 0 || durationMs - saved.posMs > 60_000)
        ) saved.posMs else 0L

        engine.create()   // M539: libVLC + MediaPlayer + listener (reusable when recovering from stalled audio)
        stall.start()

        // DVR: a direct dvrfile URL (with creds). Live: the server's profile (M383 — the per-channel
        // override was dropped, the profile can be switched right in the player).
        val streamUrl = directUrl ?: Tvh.liveUrl(
            server, channelUuid!!, channelTitle,
            server.profile.ifBlank { "pass" }
        )

        // The server is needed in DVR mode too (seekDvrTo / reopenDvrLive via the feeder).
        // The live zapping below depends on liveUuids (empty for DVR), not on liveServer.
        live.server = server
        tracks.currentProfile.value = server.profile.ifBlank { "pass" }
        // M476: the profile switcher applies to HTSP as well — the protocol has supported it since v16
        tracks.profileSwitch.value = directUrl == null && channelUuid != null
        // M383: preload the profile list (the touch button opens the menu directly,
        // without openProfileMenu) — fallback immediately, the server list async
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
            // M539-fix4: the whole of PlayerUi is keyed on the player generation — after replacing
            // the MediaPlayer (stalled audio) it is composed afresh with the new `player`. Without that
            // the LaunchedEffects (e.g. audio track auto-selection) ran against the old, already
            // released object -> IllegalStateException "can't get VLCObject instance".
            androidx.compose.runtime.key(videoSurfaceGen.value) {
            PlayerUi(
                title = live.titleState.value,
                player = engine.player,
                flags = PlaybackFlags(
                    seekable = directUrl != null,  // a DVR recording = seekable; live is not
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
                    knownDurationMs = dvr.durationState.value,  // duration from the DVR entry; for a recording in progress it grows towards the live edge
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
                    @Suppress("UNUSED_EXPRESSION") tracks.listVersion.value  // refresh when a track appears
                    tracks.htspSpuItems()
                } else null,
                htspSpuCurrentId = tracks.selectedSubEs.value,
                onPickHtspSpu = if (stream.htspStreamState.value) pickHtspSpuCb else null,   // M544: no lambda in the composition
                onPickHttpSpu = { id -> tracks.httpSpuUserPick(id) },
                callbacks = PlayerCallbacks(
                    onAttach = { layout -> attachVideo(layout) },
                    onStart = {
                        // M658: the HTSP/HTTP/DVR branching of the first start — ChannelSwitcher.playInitial
                        val doPlay: () -> Unit = { switcher.playInitial(server, channelUuid, directUrl, streamUrl) }
                        // parental lock: on EVERY opening of the player with a locked channel
                        // ask for the PIN (regardless of the grace window). The grace ("do not ask for X min") applies only
                        // when switching inside an open player (list / background / digits).
                        // M605: the tile with the list first — the list opens right after start
                        if (listFirst) window.decorView.post { openChannelList() }
                        if (ParentalLock.channelLockedProtected(this, server.id, channelUuid)) {
                            // M263: cancel the old grace window so that in this session a locked channel
                            // really does require the PIN (even if the user switched away through the prompt and came back).
                            ParentalLock.clearGrace(this)
                            requestPin(onOk = doPlay, onCancel = { finish() }, channelIndex = live.index)
                        } else doPlay()
                    },
                    onOpenEpg = { openEpgInApp() },
                    onEnterPip = { enterPipAndMinimize() },
                    onOpenSleep = { openSleepMenu() },
                    onTrackMenuChange = { kind ->
                        // M349-fix: the composable also reports the KIND of menu — without that
                        // trackMenuKind stayed "audio" from last time and selecting subtitles with the D-pad
                        // switched the audio track by mistake
                        trackMenuOpen = kind != null
                        if (kind != null) { tracks.menuKind = kind; tracks.navIndex.value = 0 }
                        // M383: a safeguard — the profile menu opened by touch without a list
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
                        // M344: playing radio in modern mode does not end — it goes to the mini player,
                        // so the confirmation question makes no sense; TV live still has it
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
                                epg.cacheChannelEpg(uuid, list)   // M274: memoize for further displays/reopens
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
            // The choice on an archived channel (live / from the start) — an overlay in the player's style
            // M553: teletext — above the player, outside PlayerUi
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
                    touchUi = !isTvDevice(),                 // M559: touch controls on the phone
                    onSubStep = { d -> ttx.subStep(d) },
                    onDigit = { d -> ttx.digit(d) }
                )
            }
            if (dvrRec.askState.value.isNotEmpty()) {
                // M606: DVR profile selection before recording
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
            // Channel context menu (long-press) — an overlay in the player's style
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
                            .clickable { ctxMenu.close() },   // a tap outside closes it + blocks the background
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
            // M280: Confirmation of ending live playback (BACK) — styled like the exit dialog in the menu.
            // D-pad/OK/BACK navigation is handled by dispatchKeyEvent (section 0e); here only the visuals + touch.
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
            // M430 / M628: the compact zapping bar — number · channel / programme · time / progress
            if (zapBar.visible.value && !info.visible.value) ZapBarOverlay(zapBar)
            // Programme info (details) — an overlay in the player's style (M630: ChannelInfoOverlay)
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

    /** Starts PiP (the window floats above the home screen / another app). M349-fix4: no
     *  moveTaskToBack — moving the task to the background right after entering PiP
     *  destroyed the fresh PiP window on many devices (the player "just closed").
     *  Entering PiP folds the activity into the floating window by itself, nothing else is needed —
     *  the auto-PiP path does exactly the same and works. */
    private fun enterPipAndMinimize() {
        // M431-fix: BACK via the Compose BackHandler calls this function directly (outside
        // closePlayer/autoPipIfPossible), so the radio gate has to be here as well —
        // otherwise the radio ends up in a PiP window. Handoff to the background / close.
        if (live.playKind == "radio") {
            if (!radioHandoffIfPossible()) finish()
            return
        }
        pip.enterIfPossible()
    }

    /**
     * Auto-PiP when navigating inside the app (EPG / back home).
     * It enters PiP only on phones (pipSupported), if it is playing and not already in PiP.
     * Returns true if it went into PiP (the caller can skip finish() accordingly).
     */
    private fun autoPipIfPossible(): Boolean {
        // M431: radio does not belong in PiP (audio with no picture in the window). Instead of PiP, a handoff
        // to RadioPlayerService (modern mode); in classic it returns false and the caller
        // carries on without PiP (close/EPG) — classic's original behaviour with no window.
        if (live.playKind == "radio") return radioHandoffIfPossible()
        return pip.autoEnterIfPossible()
    }

    // update the play/pause icon in PiP to match the real playback state

    private fun closeFromPip() {
        LastPlayback.clear(this)
        finish()
    }

    /** An in-progress recording has reached the end of the written data (EOF on a growing HTTP file).
     *  After a moment (to let another block be appended) reopen the stream and return to the position from
     *  the player clock (offset + the programme's played time) - that way it continues into the newer data.
     *  Backoff against a loop when nothing new is being appended (ReconnectController); reset on a Playing event. */
    private fun reopenDvrLive() {
        if (!seekablePlayback || !dvr.recording) return
        val url = stream.currentStreamUrl ?: return
        if (!engine.ready) return
        val offsetMs = if (dvr.progStartSec > 0 && dvr.realStartSec in 1 until dvr.progStartSec)
            (dvr.progStartSec - dvr.realStartSec) * 1000 else 0L
        // position in the file = offset + the programme's played time, a few seconds back as a margin
        val startSec = ((offsetMs + dvr.playheadMsState.value) / 1000 - 3).coerceAtLeast(0)
        reconnect.reopenDvrLive { opener.reopenDvrAt(url, startSec) }   // M670
    }

    /** Schedules a reconnect of the live stream after a dropout (increasing delay — ReconnectController). */
    private fun scheduleReconnect() {
        if (seekablePlayback) return  // a DVR recording is not reconnected (in-progress is handled by reopenDvrLive)
        reconnect.scheduleReconnect { attempt -> opener.reconnectAttempt(attempt, seekablePlayback) }   // M670
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPipState.value = isInPictureInPictureMode
        // M383-fix: deferred EPG opening — only once the PiP transition is done
        if (isInPictureInPictureMode && pendingEpgAfterPip) {
            pendingEpgAfterPip = false
            launchEpgActivity()
        }
        pip.onModeChanged(isInPictureInPictureMode)   // M578 session + action receiver (M653)
        if (!isInPictureInPictureMode) {
            // The PiP window closed by the user while the app was in the background: the activity is already STOPped
            // (state CREATED, onStop has already run and left the video playing). Stop playback for good here,
            // otherwise the audio would keep playing. If the user expanded the PiP to full screen the state is
            // STARTED/RESUMED and we do not stop the player.
            if (lifecycle.currentState < androidx.lifecycle.Lifecycle.State.STARTED &&
                engine.ready
            ) {
                runCatching { if (engine.player.isPlaying) engine.player.pause() }
                runCatching { engine.player.detachViews() }
            }
        }
    }

    // M672: going to the background / returning (M540 standby, M263 PIN, M623 radio in the background) in BackgroundResume.kt
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
        // Auto-PiP on phones when leaving the app.
        // M429: the original assumption "TV boxes do not have FEATURE_PICTURE_IN_PICTURE" DOES NOT HOLD
        // (Homatics, Shield, Raspberry Pi do have it) — auto-PiP on HOME kicked in there
        // as well and the thumbnail stayed hanging over the launcher/YouTube (reported on Reddit).
        // On TV we therefore do not enter PiP when leaving the app; PiP on TV stays only
        // inside the app (BACK -> a thumbnail over the TV guide).
        // M431: HOME during radio — no PiP window; in modern mode a handoff to the background
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
    // M539: creating the player + recovery after a stalled audio output
    // ------------------------------------------------------------------

    // M650: libVLC events in VlcEvents.kt (the same instance for every (re)created player)
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

    // M539 / M649: the stalled audio output watchdog in StallWatchdog.kt
    private val stall: StallWatchdog by lazy {
        StallWatchdog(this,
            player = { if (!engine.tornDown && engine.ready) engine.player else null },
            recreateAllowed = { !seekablePlayback && !reconnect.reconnecting.value },
            onRecreate = { engine.recreate(); opener.replayCurrentLive() })
    }

    /** Before every new media: if the output is stalled, replace the player (without waiting). */
    private fun ensureHealthyPlayer() {
        if (!engine.ready) return
        if (!stall.outputStalled()) return
        CrashLogger.report(this, "PlayerActivity.stall", "media change on stalled output -> new player")
        engine.recreate()
    }

    /** M539-fix4: the first playback start (onStart from VideoSurface) has happened. */
    internal var initialStartDone = false

    /** M535: stop/release libVLC on a worker thread (VlcEngine.teardownAsync); the feeders and the watchdog first. */
    private fun teardownPlayerAsync() {
        engine.teardownAsync {
            stall.destroy()   // M539
            stream.htspFeeder?.stop(); stream.htspFeeder = null
            stream.httpFeeder?.stop(); stream.httpFeeder = null
        }
    }

    // --- Filling in the tracks after start (audio languages / DVB subtitles) ---
    // On first attaching to the stream libVLC has not yet finished parsing the additional ES; the audio languages
    // M637: refreshing the track list after start and the one-off re-parse in TrackState
    private fun maybeReparseForTracks() {
        tracks.maybeReparse(htspStream = { stream.htspStream }, seekable = { seekablePlayback }, reconnect = { scheduleReconnect() })
    }

    // ---- M626: AFR (M346) and the stream locks (M452) split out into AfrController / StreamLocks ----
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
        // release the reference only if it still points to this instance (not to a newer one)
        if (liveInstance?.get() === this) liveInstance = null
        vlcEvents.destroy()   // M650
        reconnect.destroy()
        sleep.cancel()
        subOverlay?.stopTicker()   // stop the subtitle ticker before releasing the mediaPlayer
        timeshift.destroy()
        tracks.cancelRefresh()
        tracks.destroy()
        // M535: stop/release libVLC on a worker thread (normally this has already run in onStop
        // when isFinishing; this is a safeguard for a destroy without a preceding stop,
        // e.g. being killed by the system under memory pressure).
        teardownPlayerAsync()
        engine.allowRelease()   // the worker thread may call release()
    }

    companion object {
        /** M622: releasing libVLC from a worker thread — VlcEngine.releaseVlc. */
        fun releaseVlc(ctx: android.content.Context, mp: org.videolan.libvlc.MediaPlayer, lib: org.videolan.libvlc.LibVLC?, where: String) = VlcEngine.releaseVlc(ctx, mp, lib, where)

        /** M539-fix2: the video surface generation (the AndroidView key) — incrementing it = a new SurfaceView. */
        val videoSurfaceGen = androidx.compose.runtime.mutableStateOf(0)
        // M539: the stalled output watchdog (one-second samples)
        const val EXTRA_UUID = "channel_uuid"
        const val EXTRA_TITLE = "channel_title"
        const val EXTRA_RETURN_UUID = "return_live_uuid"
        const val EXTRA_RETURN_TITLE = "return_live_title"
        const val EXTRA_URL = "stream_url"
        const val EXTRA_KIND = "play_kind"
        /** M605: open the player with the channel list and WITHOUT a stream — a channel starts only once it is picked. */
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

        // A reference to the currently living player instance. When a new channel is opened we close the previous one
        // (including one hanging in PiP), otherwise the old PiP would stay hanging with the old channel.
        private var liveInstance: java.lang.ref.WeakReference<PlayerActivity>? = null
        /** M394-fix: close a running TV player (including PiP) before starting the radio —
         *  the stream holds the single slot and an account with a limit of 1 connection would refuse the radio. */
        fun closeActive(): Boolean {
            val a = liveInstance?.get() ?: return false
            if (a.isFinishing || a.isDestroyed) return false
            a.runOnUiThread { runCatching { a.finish() } }
            return true
        }

        /** M429: close the player if it is hanging in a PiP thumbnail — including a pinned window.
         *  Called by MainActivity.onStop on TV: when the user leaves the app (another app,
         *  HOME), the thumbnail has no business sitting over someone else's content. */
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
 * M537: should BACK during plain live playback show the exit confirmation?
 * Devices without PiP (phones without PiP, Strong): always. TV/leanback with PiP
 * (Homatics, Shield, RPi): yes, unless the auto-PiP handler takes precedence
 * (auto-PiP on -> BACK = thumbnail). A phone with PiP: no.
 * A separate composable so PlayerUi does not grow (the 64 KB method limit).
 */
@Composable
internal fun exitConfirmOnBack(pipSupported: Boolean, autoPipEnabled: Boolean): Boolean {
    if (!pipSupported) return true
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isTvUi = remember { isTvUiMode(ctx) }   // M679
    return isTvUi && !autoPipEnabled
}


/**
 * M539-fix2: the player's video surface. `PlayerActivity.videoSurfaceGen` changes the key —
 * after replacing the MediaPlayer (stalled audio after standby) the new player gets
 * a completely new SurfaceView. Sharing the old surface with the new player
 * ended in a frozen picture: the old vout still held it as the producer and the new
 * MediaCodec could not attach to it. onStart (the first playback start) runs only
 * on the first surface.
 */
@Composable
private fun VideoSurface(
    modifier: Modifier,
    onAttach: (VLCVideoLayout) -> Unit,
    onStart: () -> Unit
) {
    // M539-fix4: the first-start flag lives in the activity (a remember would be cleared on
    // recomposition through key(videoSurfaceGen) and onStart would run again)
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
                // M264: run the gate (the parental lock on opening) only after the surface is attached,
                // on a clean looper tick. Writing pinPromptState directly in the Compose layout phase
                // was sometimes lost on a cold start (the first opening) -> the PIN was never asked for.
                if (act == null || !act.initialStartDone) {
                    act?.initialStartDone = true
                    layout.post { onStart() }
                }
                layout
            }
        )
    }
}

/** A single track (audio or subtitles) from libVLC. */
internal data class TrackItem(val id: Int, val name: String)

/** ISO-639-2 (3-letter, both B and T variants) -> ISO-639-1 for the common languages. */
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

/** ISO-639 language code (e.g. "slo","eng") -> a readable name in the device's language.
 *  Returns null if the code is empty / unknown ("und"), so that the fallback is used. */
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

/** ISO-639 code -> the ENGLISH language name. libVLC names DVB subtitles in English
 *  ("DVB subtitles - [Czech]") and does not tag them with a code, so we match the selection from the metadata
 *  to the real libVLC track through this English name. null if it cannot be determined. */
/** Map of ES id -> language from the current media's metadata (both audio and subtitles have a language). */
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
 * M527: the track's name when libVLC neither named it nor gave a language.
 * Take the text from the translations — these functions are not @Composable, so
 * stringResource does not work here and we read it through the stored application context.
 */
private fun trackFallbackName(resId: Int, id: Int): String =
    runCatching {
        sk.tvhclient.shared.storage.AppContextHolder.context.getString(resId) + " " + id
    }.getOrDefault("#$id")

internal fun MediaPlayer.audioTrackItems(): List<TrackItem> {
    val descs = audioTracks ?: return emptyList()
    val langs = trackLanguages()
    // id < 0 is libVLC's built-in "Disable" item — skip it (audio is not switched off)
    return descs.filter { it.id >= 0 }.map { d ->
        val disp = langDisplay(langs[d.id])
        val base = d.name
        val name = when {
            disp != null -> disp
            !base.isNullOrBlank() -> base
            // M527: the track name from the translations — hardcoded text was shown
        // in Slovak even in a non-Slovak interface
        else -> trackFallbackName(R.string.track_audio, d.id)
        }
        TrackItem(d.id, name)
    }
}

internal fun MediaPlayer.spuTrackItems(): List<TrackItem> {
    val descs = spuTracks ?: return emptyList()
    val langs = trackLanguages()
    // id < 0 is libVLC's built-in "Disable" item — skip it; switching subtitles off
    // is handled by TrackMenu with its own "Off" row (allowOff), otherwise there would be two
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
    htspSpuItems: List<TrackItem>? = null,   // != null => HTSP: the complete subtitle list from the metadata
    htspSpuCurrentId: Int = -1,
    onPickHtspSpu: ((Int) -> Unit)? = null,
    onPickHttpSpu: ((Int) -> Unit)? = null,
    callbacks: PlayerCallbacks,
    modern: ModernOverlayArgs = ModernOverlayArgs(),
    channelList: ChannelListArgs = ChannelListArgs(),
    signals: UiSignals = UiSignals(),
    search: ChannelSearchArgs = ChannelSearchArgs(),
    // M383: the stream profile switcher
    profile: ProfileArgs = ProfileArgs(),
    pin: PinArgs = PinArgs()
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var showInfo by remember { mutableStateOf(false) }
    // Modern mode (phone): the sliding "More" panel (audio/subtitles/timer/lock/info)
    var showMoreSheet by remember { mutableStateOf(false) }
    // M473: recording the currently running programme from the "More" panel.
    // The composable is outside the activity class, so we reach it through the context.
    val dvrActivity = LocalContext.current as? PlayerActivity
    // M490: the state is held by the Activity (dvrCanRecordState / dvrEventIdState /
    // dvrExistingState) so the classic bar and the TV overlay can see it too.
    // When the panel opens we refresh it once more — the programme may have changed over.
    LaunchedEffect(showMoreSheet) {
        if (showMoreSheet) dvrActivity?.refreshDvrState()
    }
    // sleep timer countdown (updates while the timer is active)
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
    // visual slide-out of the list from the top: 0 = closed, 1 = open (follows the finger while dragging)
    var listFrac by remember { mutableStateOf(0f) }
    val listScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(showChannelList) {
        androidx.compose.animation.core.animate(
            initialValue = listFrac,
            targetValue = if (showChannelList) 1f else 0f,
            animationSpec = androidx.compose.animation.core.tween(220)
        ) { v, _ -> listFrac = v }
    }
    // MX Player gestures (phone only): overlays for volume / brightness / seek; -1 = hidden
    var volPctState by remember { mutableStateOf(-1) }
    var brightPctState by remember { mutableStateOf(-1) }
    var scrubSecState by remember { mutableStateOf(Int.MIN_VALUE) }   // MIN_VALUE = hidden
    val ctxTvGest = androidx.compose.ui.platform.LocalContext.current
    val isTvGest = remember { isTvUiMode(ctxTvGest) }   // M679
    LaunchedEffect(volPctState) { if (volPctState >= 0) { kotlinx.coroutines.delay(700); volPctState = -1 } }
    LaunchedEffect(brightPctState) { if (brightPctState >= 0) { kotlinx.coroutines.delay(700); brightPctState = -1 } }
    LaunchedEffect(scrubSecState) { if (scrubSecState != Int.MIN_VALUE) { kotlinx.coroutines.delay(700); scrubSecState = Int.MIN_VALUE } }
    var isPlaying by remember { mutableStateOf(true) }
    // Live window: the measured position of the preview box in the EPG browser (for moving the video surface)
    var previewRect by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    val density = LocalDensity.current
    var orientationLocked by remember { mutableStateOf(false) }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val ctx = androidx.compose.ui.platform.LocalContext.current
    // menu: null = none, "audio" = audio tracks, "spu" = subtitles
    var menu by remember { mutableStateOf<String?>(null) }
    var showOptions by remember { mutableStateOf(false) }

    // the D-pad / remote sent a signal -> show the controls (panel navigation is handled by the Activity)
    LaunchedEffect(signals.controlsPoke) {
        if (signals.controlsPoke > 0) controlsVisible = true
    }
    // INFO signal -> toggle the programme details window
    LaunchedEffect(signals.infoPoke) {
        if (signals.infoPoke > 0) showInfo = !showInfo
    }
    // in PiP mode hide all the controls (the window is small)
    LaunchedEffect(flags.inPip) {
        if (flags.inPip) {
            controlsVisible = false; showInfo = false; showMoreSheet = false
            showChannelList = false; menu = null; showOptions = false
        }
    }
    // tell the Activity whether the controls are shown (then D-pad navigation is handled by the Activity)
    LaunchedEffect(controlsVisible) { callbacks.onControlsVisibleChange(controlsVisible) }
    // tell the Activity the overlay state (because of D-pad routing)
    LaunchedEffect(menu) { callbacks.onTrackMenuChange(menu) }
    LaunchedEffect(showChannelList) { channelList.onOpenChange(showChannelList) }
    LaunchedEffect(showOptions) { callbacks.onOptionsChange(showOptions) }
    // the Activity asks to open/close the channel list (holding OK)
    LaunchedEffect(signals.openList) {
        if (signals.openList > 0) { showChannelList = true; controlsVisible = false }
    }
    LaunchedEffect(signals.closeList) {
        if (signals.closeList > 0) showChannelList = false
    }
    // Options (Audio/Subtitles/SW) via D-pad DOWN / MENU
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
    // play/pause icon per the player's real state
    LaunchedEffect(flags.playing) { isPlaying = flags.playing }
    // seek state (DVR only). A TS file carries no duration, so we use:
    //  - the duration from the DVR entry (knownDurationMs), fallback player.length
    //  - position (a fraction 0..1) for both display and seeking (more reliable on TS than setTime)
    var posFraction by remember { mutableStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableStateOf(0f) }
    // Current playback time in ms (player.time) - a smooth position source for the left-hand side.
    var posTimeMs by remember { mutableStateOf(0L) }
    // player.time/position are unreliable for a growing TS (sometimes they run, sometimes they stall, sometimes
    // subtracting the offset zeroes the left-hand side). We therefore compute the left-hand side as played time:
    // from the start of the programme (0) we add the real elapsed time while it plays - the same
    // wall-clock principle that makes the right-hand side (lengthMs) work reliably.
    var lastPlayTickMs by remember { mutableStateOf(0L) }
    // A one-off jump to the programme's start within the file (a recording in progress with pre-programme
    // content), so that "from the start" plays from the programme's start and the player clock matches from 0.
    var initialSeekDone by remember { mutableStateOf(false) }
    // M495: a DVR seek rebuilds the media with :start-time, so from that moment player.position
    // shows the position in the NEW media (it starts at the jump point), not
    // in the whole file. Converting the file position into programme time is from then on
    // invalid — the clock has to run off the wall clock from the seed.
    var rebuiltBySeek by remember { mutableStateOf(false) }

    // Length of the bar = the programme's elapsed time (knownDurationMs, grows smoothly at 1s/s).
    // We do not use player.length for the scale - VLC reports it for a growing TS in coarse
    // jumps, which threw the left-hand side of the timer off. Fall back to the VLC length only when
    // we have no EPG time.
    val lengthMs = if (dvr.knownDurationMs > 0) dvr.knownDurationMs else player.length.coerceAtLeast(0L)
    // A fresh duration for the ticker (otherwise LaunchedEffect(Unit) captures the value from start and
    // the left-hand side would not fit above the live-edge level at start).
    val lengthMsLive = androidx.compose.runtime.rememberUpdatedState(lengthMs)
    val offsetMsLive = androidx.compose.runtime.rememberUpdatedState(dvr.recordingOffsetMs)
    // M495-fix: the same applies to the seed from a seek. The ticker runs in LaunchedEffect(Unit),
    // so it remembers the parameter's value at the FIRST composition and never sees a new one —
    // the seed therefore never arrived, the clock never switched to the target and the resync knocked it almost
    // to zero (hence the "0:59" right after a jump to minute 40).
    val seekSeedLive = androidx.compose.runtime.rememberUpdatedState(dvr.seekSeedMs)
    val onSeekSeedHandledLive = androidx.compose.runtime.rememberUpdatedState(dvr.onSeekSeedHandled)
    // For a recording in progress do not allow seeking right up to the live edge (the end of the available data).
    // The written data lags the EPG time (the right-hand side) by roughly 20-30 s, so the margin
    // computed from the EPG time has to be larger, otherwise the playhead jumps into a zone not yet written,
    // hits EOF and the TS freezes. A larger margin = the playhead stays within reliably recorded
    // data. A greedy overrun at the end is further sorted out by the automatic stream reopen.
    val liveMarginMs = 45_000L
    // Length for the seekbar = the reachable range (without the 45 s margin for a recording in progress).
    // That way the playhead reaches the end of the bar with no visible gap/"barrier" - the margin is hidden.
    val barLengthMs = if (dvr.recordingLive) (lengthMs - liveMarginMs).coerceAtLeast(1L) else lengthMs

    // Position restore (DVR only): ask, and after confirmation seek once the
    // media is loaded
    var askResume by remember { mutableStateOf(dvr.resumeMs > 0) }
    var pendingResumeMs by remember { mutableStateOf(0L) }
    // A bridge to the dialog's D-pad handling in the Activity: report visibility and react to the answer
    LaunchedEffect(askResume) { dvr.onAskResumeChange(askResume) }
    LaunchedEffect(dvr.resumeAnswer) {
        if (dvr.resumeAnswer != 0 && askResume) {
            if (dvr.resumeAnswer == 1) pendingResumeMs = dvr.resumeMs
            askResume = false
            dvr.onResumeAnswerHandled()
        }
    }

    // Update the position every second (only when seekable and not dragging)
    // M662: the ticker's body split out into DvrPositionTicker.kt (the JVM 64 KB method limit).
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

    // Live progress of the current programme (from the EPG): ticks every second
    var liveNowSec by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    // The current programme (mutable — when it runs out the next one is loaded)
    var progStart by remember(liveChannelUuid) { mutableStateOf(programme.startSec) }
    var progStop by remember(liveChannelUuid) { mutableStateOf(programme.stopSec) }
    var progTitle by remember(liveChannelUuid) { mutableStateOf(programme.title) }
    var progDesc by remember(liveChannelUuid) { mutableStateOf("") }
    var nextTitle by remember(liveChannelUuid) { mutableStateOf(programme.nextTitle) }
    var nextStart by remember(liveChannelUuid) { mutableStateOf(programme.nextStart) }
    var nextStop by remember(liveChannelUuid) { mutableStateOf(programme.nextStop) }
    val hasLiveProg = !flags.seekable && progStart > 0 && progStop > progStart

    // M663: the tick and loading the EPG programme in LiveProgrammeEffects.kt (conditions and keys identical)
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

    // M662: audio track auto-selection (M378) is in AudioAutoSelect.kt
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

    // M663: the chain of BackHandlers in PlayerBackHandlers.kt (order = priority, preserved)
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

    // M662: the EPG effects (M266 prefetch + M522/M525 periodic refresh) are in PlayerEpgEffects.kt
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
            // M664: MX Player gestures (seek / volume / brightness / sliding the list out) in PlayerGestures.kt
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
        // M539-fix2: the AndroidView is in a separate composable (a smaller PlayerUi + surface replacement)
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

        // M630: the small overlays in PlayerOverlays.kt (order preserved)
        // Audio-only (radio): a centred logo instead of black; on TV with the list in preview
        if (!flags.hasVideo) RadioCenterLogo(programme.centerLogoUrl, server, if (inPreview) previewRect else null)
        // the reconnecting indicator (a network dropout during live broadcast)
        if (flags.reconnecting) ReconnectingOverlay()
        // the spinner in the middle while seeking in timeshift (resync)
        if (flags.seeking && !flags.reconnecting) SeekingSpinner()
        // YouTube-style hint on a double tap (a 10 s skip)
        if (dvr.seekHint != 0) SeekHintOverlay(dvr.seekHint)
        // MX Player overlays: volume / brightness (centred), seek-scrub (top centre)
        if (volPctState >= 0 || brightPctState >= 0) GestureLevelOverlay(volPctState, brightPctState)
        if (scrubSecState != Int.MIN_VALUE) ScrubSecondsOverlay(scrubSecState)
        // the overlay with the channel number currently being typed
        if (signals.numberEntry.isNotEmpty()) NumberEntryOverlay(signals.numberEntry)

        // M661: the control bar (the top info block + seekbar + buttons) is in PlayerControlBar.kt
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

        // The modern mode "More" panel (phone) — M663: ModernOverlayEffects.kt
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

        // Modern TV overlay (channel cards + control bar) — exclusivity,
        // auto-hide and running the bar's actions (M663: ModernOverlayEffects.kt)
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

        // Info window: details of the currently running programme (INFO key / button) — M661: PlayerInfoWindow.kt
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

        // Overlay: the channel list inside the player (slides down from the top per listFrac) — PHONE (M631: PhoneChannelList.kt)
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
        // TV channel list (M632: TvChannelList.kt)
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

        // The modern bar's "More" menu (M327): Channels / Sleep timer / Information (M633: PlayerMenus.kt)
        if (modern.moreVisible) {
            ModernMoreMenu(
                ids = modern.moreIdList,
                highlightIndex = if (isTvGest) modern.moreIndex else -1,   // M385-fix
                recActive = dvrActivity?.dvrExistingState?.value != null,
                onPick = modern.onMorePick,
                onDismiss = modern.onMoreDismiss
            )
        }

        // Sleep timer duration picker — vertical, navigated from the Activity (M633: PlayerMenus.kt)
        if (showOptions) {
            SleepOptionsMenu(
                highlightIndex = if (isTvGest) signals.optionsNavIndex else -1,   // M385-fix
                onSelect = callbacks.onOptionsSelect,
                onDismiss = { showOptions = false }
            )
        }

        // Track menu (audio / subtitles) — M661: TrackMenu.kt
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

        // Dialog: resume playback from the last position? (M559-fix: pulled out of PlayerUi — the 64 kB limit)
        if (askResume) {
            ResumeDialog(
                resumeMs = dvr.resumeMs, resumeSel = dvr.resumeSel,
                onNo = { askResume = false },
                onYes = { pendingResumeMs = dvr.resumeMs; askResume = false }
            )
        }

        // Parental lock: PIN entry (digits from the remote are handled by the Activity; M633: PlayerMenus.kt)
        if (pin.prompt) {
            PlayerPinPanel(
                pinLen = pin.len, pinError = pin.error,
                gridRow = if (isTvGest) pin.gridRow else -1, gridCol = if (isTvGest) pin.gridCol else -1,
                onDigit = pin.onDigit, onBack = pin.onBack, onOpenList = pin.onOpenList
            )
        }
    }
}

/** M559-fix: the "Resume playback" dialog — a separate composable (PlayerUi is at the method size limit). */
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
