package sk.tvhclient.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.htsp.HtspData

/**
 * M657: the core of channel switching (split out of PlayerActivity): selecting a channel from the list
 * with the archive choice (live / from start), starting an ongoing recording from the start,
 * remembering the last channel (M494) and switchToIndex itself including the M262 initialisation
 * of the HTSP mode. The order of the steps is identical to the original code. The state is held by [LiveSession],
 * [StreamState] and [TrackState]; what stays in the activity (PIN, DVR state, controls, channel
 * list, opening the stream, archive compose states, intent) goes through [Actions].
 */
internal class ChannelSwitcher(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val live: LiveSession,
    private val stream: StreamState,
    private val tracks: TrackState,
    private val actions: Actions
) {
    interface Actions {
        /** TV/box (Android TV) — to detect where the archive choice should be shown. */
        fun isTvDevice(): Boolean
        fun pokeControls()
        fun closeChannelList()
        fun refreshDvrState()
        fun cancelReconnect()
        fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, channelIndex: Int?)
        fun playHtspLive(server: sk.tvhclient.shared.model.TvhServer, channelId: Long, timeshift: Boolean): Boolean
        fun playLiveAuto(server: sk.tvhclient.shared.model.TvhServer, url: String)
        /** M658: DVR recording via HttpTsFeeder (digest-only server) / the direct HTTP path (playInitial). */
        fun playDvrViaFeeder(server: sk.tvhclient.shared.model.TvhServer, url: String)
        fun playHttp(url: String)
        /** assume video; the check after Playing will correct it */
        fun setHasVideo(v: Boolean)
        /** M262: whether the HTSP mode has already been determined for this session (shared with doPlay). */
        fun htspInitDone(): Boolean
        fun setHtspInitDone(v: Boolean)
        /** index of the channel waiting for the archive choice, -1 = none */
        fun archiveChoiceIdx(): Int
        fun setArchiveChoiceIdx(v: Int)
        /** 0=live, 1=from start (D-pad) */
        fun setArchiveChoiceSel(v: Int)
        fun recInProgressByChan(): Map<String, sk.tvhclient.shared.model.DvrEntry>
        /** M497: the archive is not restored (dvrUuid != null). */
        fun dvrUuid(): String?
        /** EXTRA_UUID from the activity's intent (a substitute uuid for rememberPlayback). */
        fun intentUuid(): String?
        /** A new PlayerActivity in DVR mode (playRecordingFromStart). */
        fun startActivity(intent: android.content.Intent)
    }

    /**
     * M658: the first start after the activity starts (originally doPlay in the onStart lambda of PlayerUi).
     * HTSP channel -> subscription (with timeshift according to the pref + server support), otherwise HTTP;
     * a DVR recording with a login -> auto-detection of the auth (feeder / direct path).
     */
    fun playInitial(server: sk.tvhclient.shared.model.TvhServer, channelUuid: String?, directUrl: String?, streamUrl: String) {
        val cid = channelUuid?.toLongOrNull()
        val htspMode = server.connectionMode == "htsp"
        if (cid != null && directUrl == null && htspMode) {
            // stream over HTSP (9982). Timeshift features only if the pref is on and the server supports it.
            stream.currentStreamUrl = streamUrl  // HTTP fallback for the reconnect/reparse stop
            scope.launch {
                val ts = TimeshiftPref.get(ctx) && withContext(Dispatchers.IO) {
                    runCatching {
                        HtspData.timeshiftAvailable(server, System.currentTimeMillis() / 1000)
                    }.getOrDefault(false)
                }
                if (actions.playHtspLive(server, cid, ts)) {
                    stream.htspStream = true
                    stream.htspLive = ts
                    stream.htspLiveState.value = ts
                } else {
                    stream.htspStream = false
                    stream.htspLive = false
                    stream.htspLiveState.value = false
                    actions.playLiveAuto(server, streamUrl)
                }
                actions.setHtspInitDone(true)
                actions.pokeControls()
            }
        } else {
            if (directUrl != null && server.username.isNotEmpty()) {
                // M254: auto-detection of the auth. A digest-only server -> feeder
                // (libVLC cannot do digest via the URL); basic/none -> the direct
                // seekable path.
                scope.launch {
                    val useFeeder = withContext(Dispatchers.IO) {
                        DvrAuthProbe.needsFeeder(server, MediaFactory.stripCreds(streamUrl))
                    }
                    stream.dvrViaFeeder = useFeeder
                    if (useFeeder) actions.playDvrViaFeeder(server, streamUrl)
                    else actions.playHttp(streamUrl)
                    actions.pokeControls()
                }
            } else {
                stream.dvrViaFeeder = false
                actions.playLiveAuto(server, streamUrl)
                actions.pokeControls()
            }
        }
    }

    /** Selecting a channel from the list: if it is being archived, offer live/from start, otherwise switch. */
    fun selectChannelOrArchive(idx: Int, poke: Boolean = true) {
        val ch = live.channelsState.value.getOrNull(idx)
        val rec = ch?.let { c -> actions.recInProgressByChan().let { it[c.uuid] ?: it[c.name] } }
        if (rec != null && actions.isTvDevice() && ArchiveChoicePref.get(ctx)) {
            actions.setArchiveChoiceSel(0)
            actions.setArchiveChoiceIdx(idx)
            actions.closeChannelList()
        } else if (idx != live.index) switchToIndex(idx, poke) else actions.pokeControls()
    }

    /** Resolves the choice for an archived channel: live (switches) or from start (starts the recording). */
    fun resolveArchiveChoice(fromStart: Boolean) {
        val idx = actions.archiveChoiceIdx()
        actions.setArchiveChoiceIdx(-1)
        if (idx < 0) return
        val ch = live.channelsState.value.getOrNull(idx) ?: LivePlaylist.channels.getOrNull(idx) ?: return
        if (!fromStart) {
            if (idx != live.index) switchToIndex(idx) else actions.pokeControls()
            return
        }
        val rec = actions.recInProgressByChan().let { it[ch.uuid] ?: it[ch.name] }
        if (rec == null) {
            if (idx != live.index) switchToIndex(idx)
            return
        }
        playRecordingFromStart(rec, ch.nowStart, ch.nowStop)
    }

    /**
     * M494: remember what is currently playing (TV). It is written at start and on
     * every channel switch, so that after the box is turned off and on the app carries on
     * where the user left off.
     */
    fun rememberPlayback() {
        if (!actions.isTvDevice()) return
        if (actions.dvrUuid() != null) return              // M497: the archive is not restored
        val srvId = (live.server ?: Tvh.store.active())?.id ?: return
        val uuid = live.uuids.getOrNull(live.index) ?: actions.intentUuid()
        LastPlayback.setLive(ctx, srvId, uuid, live.playKind)
    }

    /** Starts an ongoing recording from the start (a new PlayerActivity in DVR mode). */
    fun playRecordingFromStart(rec: sk.tvhclient.shared.model.DvrEntry, progStart: Long, progStop: Long) {
        val srv = live.server ?: return
        val url = Tvh.dvrUrl(srv, rec.uuid)
        val pStart = if (progStart > 0) progStart else rec.start
        val pStop = if (progStop > progStart && progStop > 0) progStop else rec.stop
        val nowSec = System.currentTimeMillis() / 1000
        val inProgress = pStart > 0 && nowSec < pStop
        val i = android.content.Intent(ctx, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_URL, url)
            putExtra(PlayerActivity.EXTRA_TITLE, rec.title)
            putExtra(PlayerActivity.EXTRA_DURATION_MS, rec.durationSec * 1000)
            putExtra(PlayerActivity.EXTRA_DVR_UUID, rec.uuid)
            putExtra(PlayerActivity.EXTRA_DVR_RECORDING, inProgress)
            putExtra(PlayerActivity.EXTRA_DVR_PROG_START_SEC, pStart)
            putExtra(PlayerActivity.EXTRA_DVR_PROG_STOP_SEC, pStop)
            putExtra(PlayerActivity.EXTRA_DVR_REAL_START_SEC, rec.realStartSec)
            // where we came from (the live channel) -> back here after Back
            live.uuids.getOrNull(live.index)?.let { putExtra(PlayerActivity.EXTRA_RETURN_UUID, it) }
            putExtra(PlayerActivity.EXTRA_RETURN_TITLE, live.names.getOrElse(live.index) { "" })
        }
        runCatching { actions.startActivity(i) }
    }

    /** Switches to a specific channel by index, rebuilds the URL and loads it. */
    fun saveLastLive(serverId: String?, uuid: String?) {
        if (serverId == null || uuid == null) return
        if (live.playKind == "radio") LastRadio.set(ctx, serverId, uuid) else LastChannel.set(ctx, serverId, uuid)
    }

    fun switchToIndex(i: Int, poke: Boolean = true) {
        if (i < 0 || i >= live.uuids.size) return
        if (i == live.index) { if (poke) actions.pokeControls(); return }  // the same channel -> do not load it again
        rememberPlayback()  // M494: restore after an app restart
        val srv = live.server ?: return
        val uuid = live.uuids[i]
        // parental lock: a locked channel outside the 5-min window -> ask for the PIN
        if (ParentalLock.channelNeedsPin(ctx, srv.id, uuid)) {
            actions.requestPin(onOk = { switchToIndex(i, poke) }, onCancel = { }, channelIndex = i)
            return
        }
        // M392-fix3: the subtitle choice applies only to the current channel — when switching to ANOTHER
        // channel reset it to off (like HTSP: desiredSubName = null). A restart of the
        // same channel (profile change, applyProfileChange) comes here with the same uuid
        // via liveIndex=-1, that is why we compare uuids, not indexes.
        if (uuid != live.uuidState.value) {
            tracks.httpSpuWantOff = true
            tracks.httpSpuWantName = null
        }
        live.index = i
        live.indexState.value = i
        // M523: ONLY HERE, once the index already points to the NEW channel. The call at the start
        // of switchToIndex still read the old index, so the recording button
        // showed the state of the channel the user had just left — on a
        // recording channel "Record" and on a non-recording one "Cancel recording".
        actions.refreshDvrState()
        val name = live.names.getOrElse(i) { "" }
        live.titleState.value = name
        live.uuidState.value = uuid
        saveLastLive(srv.id, uuid)
        // a new channel = an unknown programme; hide the progress bar of the old programme
        live.showProgramme(LivePlaylist.channels.getOrNull(i))   // M652
        // M383: the profile is uniform for the whole server (the per-channel override was removed)
        val prof = srv.profile.ifBlank { "pass" }
        val url = Tvh.liveUrl(srv, uuid, name, prof)
        stream.currentStreamUrl = url
        actions.cancelReconnect()  // a new connection -> cancel the old attempts
        tracks.resetReparse()  // a new channel -> allow a one-off re-parse stop
        actions.setHasVideo(true)  // assume video; the check after Playing will correct it
        val cid = uuid.toLongOrNull()
        // M262: if the HTSP mode has not been determined yet (a switch before doPlay, e.g. leaving
        // the PIN prompt of a locked start-up channel), determine it here the same way as doPlay,
        // so that even the first switched channel gets HTSP/timeshift and not just HTTP.
        if (srv.connectionMode == "htsp" && cid != null && !actions.htspInitDone()) {
            actions.setHtspInitDone(true)
            scope.launch {
                val ts = TimeshiftPref.get(ctx) && withContext(Dispatchers.IO) {
                    runCatching {
                        HtspData.timeshiftAvailable(srv, System.currentTimeMillis() / 1000)
                    }.getOrDefault(false)
                }
                if (actions.playHtspLive(srv, cid, ts)) {
                    stream.htspStream = true; stream.htspLive = ts; stream.htspLiveState.value = ts
                } else {
                    stream.htspStream = false; stream.htspLive = false; stream.htspLiveState.value = false
                    actions.playLiveAuto(srv, url)
                }
                if (poke) actions.pokeControls()
            }
            return
        }
        if (stream.htspStream && cid != null && actions.playHtspLive(srv, cid, stream.htspLive)) {
            if (poke) actions.pokeControls()
            return
        }
        actions.playLiveAuto(srv, url)
        if (poke) actions.pokeControls()
    }
}
