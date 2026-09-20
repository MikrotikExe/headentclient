package sk.tvhclient.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * M634: EPG now/next for the channel list in the player and its cache (extracted from PlayerActivity).
 *
 * Owns: [upcoming] (channel -> current + upcoming programmes; M270 spinner [loading]),
 * the time of the last successful refresh (M271, mirrored into LivePlaylist.epgLastOkMs), the retry on
 * an incomplete HTSP EPG (M551-fix), the coalesced write to disk (M275/M456) and reading from disk (M611).
 *
 * From the activity it receives shared state as references: [liveChannels] (enriches now/next and the red
 * dot, writes into LivePlaylist too), [recInProgress] (recordings in progress by channel)
 * and [onDvrStateChanged] (the activity recomputes the recording button, M526).
 */
class PlayerEpgStore(
    private val activity: ComponentActivity,
    private val liveServer: () -> TvhServer?,
    private val liveChannels: MutableState<List<LivePlaylist.LiveChannel>>,
    private val recInProgress: MutableState<Map<String, DvrEntry>>,
    private val onDvrStateChanged: () -> Unit
) {
    // cache map channel(uuid) -> current + nearest programmes (for the EPG browser on TV)
    val upcoming = mutableStateOf<Map<String, List<EpgEvent>>>(LivePlaylist.epgUpcoming)
    // M270: spinner on the first/stale EPG load in the channel list
    val loading = mutableStateOf(false)
    // M271: we read the time of the last refresh from the process cache so that a reopen does not fetch again
    private var epgLastOkMs = LivePlaylist.epgLastOkMs

    /** Refreshes now/next for all channels in the list (while it is open). */
    suspend fun refreshOverlayEpg() {
        val srv = liveServer() ?: return
        val cur = liveChannels.value
        if (cur.isEmpty()) return
        val nowS = System.currentTimeMillis() / 1000
        epgPartial = false   // M551-fix
        var epgSkipped = false   // M603
        try {
            // recordings in progress -> which channels are being recorded right now (red dot/cassette + archive selection)
            val recList: List<sk.tvhclient.shared.model.DvrEntry> =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try { Tvh.fetchDvrInProgress(srv, api) }
                    catch (e: Exception) { emptyList() }
                    finally { api.close() }
                }.let { DvrController.overlayInProgress(srv.id, it) }   // M608
            val recMap = recList.associateBy { it.channelUuid.ifBlank { it.channelName } }
            recInProgress.value = recMap
            // M526: the map has only arrived now — recompute the state of the recording button.
            // On the FIRST load refreshDvrState runs before the map is available,
            // so the button stayed empty until the first channel switch.
            onDvrStateChanged()
            if (srv.connectionMode == "htsp") {
                sk.tvhclient.shared.htsp.HtspData.lastEpgError = null
                sk.tvhclient.shared.htsp.HtspData.lastEpgFailed = 0
                val map = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Tvh.fetchEpgUpcoming(srv)
                }
                // M551-fix: an incomplete result (getEvents failed for some channels) is
                // shown, but is NOT considered fresh — otherwise the missing channels would stay
                // without EPG for 3 hours (epgIsStale). A retry is scheduled in 20 s (max 3x).
                val failed = sk.tvhclient.shared.htsp.HtspData.lastEpgFailed
                // M603: the round was skipped because a transfer is running (M595) — we only got
                // the cache. This is not an incomplete EPG: do not log, do not schedule a retry
                // (each one would be skipped anyway). Freshness is not renewed, so after
                // playback ends now/next is fetched again.
                val skipped = sk.tvhclient.shared.htsp.HtspData.lastEpgSkipped
                epgSkipped = skipped
                epgPartial = !skipped && (map.isEmpty() || failed > 0)
                if (map.isNotEmpty()) upcoming.value = upcoming.value + map
                if (skipped) {
                    epgRetries = 0
                } else if (epgPartial) {
                    CrashLogger.report(
                        activity, "PlayerActivity.epg",
                        "HTSP EPG incomplete: ${map.size}/${cur.size} channels, failed=$failed, withoutEpg=${sk.tvhclient.shared.htsp.HtspData.lastEpgEmpty.size}, lastEpgError=" +
                            (sk.tvhclient.shared.htsp.HtspData.lastEpgError ?: "none")
                    )
                    scheduleEpgRetry()
                } else epgRetries = 0
                val enrichHtsp: (LivePlaylist.LiveChannel) -> LivePlaylist.LiveChannel = { ch ->
                    val ev = map[ch.uuid]?.firstOrNull { it.start <= nowS && nowS < it.stop }
                    val b = if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
                    b.copy(recording = (b.uuid in recMap || b.name in recMap))
                }
                val updated = cur.map(enrichHtsp)
                liveChannels.value = updated
                LivePlaylist.channels = updated
                // M370-fix3: enrich the whole list too, so that switching the tag does not lose the EPG in the list
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map(enrichHtsp)
            } else {
                // HTTP: now/next is in the channel dump -> reload
                val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try {
                        val repo = Tvh.channelRepository(srv, api)
                        repo.load(true)
                        // M586: radios too — otherwise the list in the player for radio
                        // stayed without "now playing" (allRows omits radios)
                        (repo.allRows(false) + repo.radioRows(false)).associateBy { it.channel.uuid }
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
                liveChannels.value = updated
                LivePlaylist.channels = updated
                // M370-fix3: enrich the whole list too, so that switching the tag does not lose the EPG in the list
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map(enrichHttp)
            }
            // M603: a skipped round (a transfer is running) does not renew freshness — after
            // playback ends now/next is fetched on the next opening of the list
            if (!epgPartial && !epgSkipped) epgLastOkMs = System.currentTimeMillis()   // M551-fix: incomplete = stale
            // M271: write into the process cache so that reopening the player does not fetch again
            LivePlaylist.epgLastOkMs = epgLastOkMs
            LivePlaylist.epgUpcoming = upcoming.value
            persistEpg(upcoming.value)   // M275: to disk, so that it survives a box restart
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // M551-fix3: leaving the player during loading is not an error
        } catch (e: Exception) {
            CrashLogger.report(activity, "PlayerActivity.epg", e)   // M550-fix: diagnostics
        }
    }

    // M551-fix: incomplete HTSP EPG -> retry in the background
    private var epgPartial = false
    private var epgRetries = 0
    private var epgRetryJob: kotlinx.coroutines.Job? = null
    private fun scheduleEpgRetry() {
        if (epgRetries >= 3 || epgRetryJob?.isActive == true) return
        epgRetries++
        epgRetryJob = activity.lifecycleScope.launch {
            kotlinx.coroutines.delay(20_000)
            refreshOverlayEpg()
        }
    }

    /** M270: the EPG is stale (a spinner is needed on the first load) if it has never run,
     *  is older than 3 h (the box was off for a long time), or the cache is empty in HTSP mode. */
    fun epgIsStale(): Boolean {
        if (epgLastOkMs == 0L) return true
        if (System.currentTimeMillis() - epgLastOkMs > 3L * 60 * 60 * 1000) return true
        return liveServer()?.connectionMode == "htsp" && upcoming.value.isEmpty()
    }

    /** M270: the first EPG load after opening the list. The spinner is shown ONLY if the cache is
     *  empty/stale and the load takes longer than the threshold (350 ms) — with a fast server
     *  it does not appear either when switching or on the periodic refresh. */
    fun refreshOverlayEpgInitial() {
        // M524: refresh the recording flag (red dot) ALWAYS, independently of the EPG.
        // So far the small DVR query was only made when the EPG cache was fresh; when it was
        // stale, the app downloaded the whole EPG and the dots only appeared after that —
        // or not at all, until the user opened the big channel list.
        refreshRecordingOnly()
        activity.lifecycleScope.launch {
            // M271: if we have a fresh EPG (cache from a recent opening), do not fetch again —
            // this removes the annoying loading on every reopen. Fetch only when it is stale.
            if (!epgIsStale()) {
                // M281/M524: the EPG is fresh; the recording flag has already been refreshed above
                return@launch
            }
            val spinJob = launch {
                kotlinx.coroutines.delay(350)
                loading.value = true
            }
            refreshOverlayEpg()
            spinJob.cancel()
            loading.value = false
        }
    }

    /** M281: a quick refresh of just the recording flags (red dot) without an EPG fetch.
     *  Used on a reopen with a fresh EPG — we already have now/next from the cache, but the
     *  recordings in progress may have changed in the meantime. One small DVR query, no EPG churn. */
    fun refreshRecordingOnly() {
        val srv = liveServer() ?: return
        activity.lifecycleScope.launch {
            val recList: List<sk.tvhclient.shared.model.DvrEntry> =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try { Tvh.fetchDvrInProgress(srv, api) }
                    catch (e: Exception) { emptyList() }
                    finally { api.close() }
                }.let { DvrController.overlayInProgress(srv.id, it) }   // M608
            val recMap = recList.associateBy { it.channelUuid.ifBlank { it.channelName } }
            recInProgress.value = recMap
            onDvrStateChanged()   // M526: the same on the quick refresh too
            val cur = liveChannels.value
            if (cur.isNotEmpty()) {
                val updated = cur.map { it.copy(recording = (it.uuid in recMap || it.name in recMap)) }
                liveChannels.value = updated
                LivePlaylist.channels = updated
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map {
                        it.copy(recording = (it.uuid in recMap || it.name in recMap))
                    }
            }
        }
    }

    /** M274: storing the per-channel EPG into the cache (and into the LivePlaylist process cache),
     *  so that showing that channel again — even after closing/opening the player — is
     *  immediate from the cache, not from the network. It works in HTTP mode too, where the bulk map is missing. */
    fun cacheChannelEpg(uuid: String, list: List<sk.tvhclient.shared.model.EpgEvent>) {
        if (list.isEmpty()) return
        val m = upcoming.value.toMutableMap()
        m[uuid] = list
        upcoming.value = m
        onDvrStateChanged()   // M490: the EPG is available -> find out the recording state
        LivePlaylist.epgUpcoming = m
        if (epgLastOkMs == 0L) {
            epgLastOkMs = System.currentTimeMillis()
            LivePlaylist.epgLastOkMs = epgLastOkMs
        }
        persistEpg(m)   // M275/M456: write to disk (coalesced)
    }

    /** M275: loading the EPG from disk into the process cache at start (if the process cache is
     *  empty — e.g. after a box/app restart). Shows now/next immediately; freshness is
     *  handled by epgIsStale (>3h -> background refresh). */
    fun hydrateEpgFromDisk(srv: sk.tvhclient.shared.model.TvhServer) {
        if (LivePlaylist.epgUpcoming.isNotEmpty()) {
            // M281: the process cache survived (Activity recreate) — synchronise the Activity state,
            // so that applyCachedEpgToChannels() can fill the list straight away.
            if (upcoming.value.isEmpty()) upcoming.value = LivePlaylist.epgUpcoming
            if (epgLastOkMs == 0L) epgLastOkMs = LivePlaylist.epgLastOkMs
            return
        }
        // M611: reading from disk in the background — synchronously in onCreate (main thread) with a
        // large EPG it took seconds and Play reported an ANR on player start. After loading,
        // the cache is only used if no fresh data arrived from the network in the meantime.
        activity.lifecycleScope.launch {
            val nowSec = System.currentTimeMillis() / 1000
            val daysBack = EpgRangePref.daysBack(activity)
            val disk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { EpgCache.loadLive(activity, srv.id, nowSec, daysBack) }.getOrDefault(emptyMap())
            }
            if (disk.isNotEmpty() && upcoming.value.isEmpty()) {
                upcoming.value = disk
                LivePlaylist.epgUpcoming = disk
                val ts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { EpgCache.lastSavedLive(activity, srv.id) }.getOrDefault(0L)
                }
                if (epgLastOkMs == 0L) { epgLastOkMs = ts; LivePlaylist.epgLastOkMs = ts }
                applyCachedEpgToChannels()   // programme names under the channels as soon as they are available
                onDvrStateChanged()
            }
        }
    }

    /** M281: apply the cached now/next (from disk/process via upcoming) to the visible
     *  channel list, so that the programme names under the channels appear IMMEDIATELY even after a restart/reopen
     *  — without waiting for the network refreshOverlayEpg. The recording flag (red dot) is filled in
     *  only once fetchDvrInProgress (recInProgress) completes; it is not overwritten here if it is empty. */
    fun applyCachedEpgToChannels() {
        val map = upcoming.value
        if (map.isEmpty()) return
        val cur = liveChannels.value
        if (cur.isEmpty()) return
        val nowS = System.currentTimeMillis() / 1000
        val recMap = recInProgress.value
        val updated = cur.map { ch ->
            val ev = map[ch.uuid]?.firstOrNull { it.start <= nowS && nowS < it.stop }
            val b = if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
            if (recMap.isEmpty()) b else b.copy(recording = (b.uuid in recMap || b.name in recMap))
        }
        liveChannels.value = updated
        LivePlaylist.channels = updated
    }

    // ---- M456: coalescing EPG cache writes ----
    private var epgPersistJob: kotlinx.coroutines.Job? = null
    private var epgPersistPending: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>? = null
    private var epgLastPersistMs = 0L
    private val epgPersistMinGapMs = 30_000L

    /**
     * M275: asynchronous write of the EPG cache to disk (per server).
     *
     * M456: the write is COALESCED. Originally, on every HTSP update of a single
     * channel, the WHOLE map of all channels was serialised and written — Tvheadend
     * sends eventUpdate continuously, so with a large line-up it ran several
     * times a second. In the profile it was the most expensive thing in the whole app
     * (EpgEvent$$serializer.serialize + FileOutputStream.write more samples than
     * the entire TS muxer) and on a weaker box it meant a difference of 136 % vs 42 % CPU
     * compared with the HTTP path, where the EPG is downloaded once. Now it is written at most once
     * every 30 s and always the latest state; on leaving the player the remainder is awaited.
     */
    private fun persistEpg(map: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>) {
        val srv = liveServer() ?: return
        if (map.isEmpty()) return
        epgPersistPending = map
        if (epgPersistJob?.isActive == true) return
        val since = System.currentTimeMillis() - epgLastPersistMs
        val wait = if (since >= epgPersistMinGapMs) 0L else epgPersistMinGapMs - since
        epgPersistJob = activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            val snapshot = epgPersistPending ?: return@launch
            epgPersistPending = null
            epgLastPersistMs = System.currentTimeMillis()
            runCatching {
                val nowSec = System.currentTimeMillis() / 1000
                EpgCache.saveLive(activity, srv.id, snapshot, nowSec, EpgRangePref.daysBack(activity))
            }
        }
    }

    /** M456: flush the EPG cache on leaving, so that the last changes are not lost. */
    fun flushEpgPersist() {
        val srv = liveServer() ?: return
        val snapshot = epgPersistPending ?: return
        epgPersistPending = null
        val app = activity.applicationContext
        val days = EpgRangePref.daysBack(activity)
        // a separate thread — the activity is finishing, its scope would cancel the write
        Thread {
            runCatching {
                EpgCache.saveLive(app, srv.id, snapshot, System.currentTimeMillis() / 1000, days)
            }
        }.start()
    }

    /** M274: background EPG prefetch ONLY if the cache is empty/stale (first start, >3h).
     *  On a reopen with a fresh cache no pointless refresh is done (no lag/churn). */
    fun prefetchEpgIfStale() {
        if (!epgIsStale()) return
        activity.lifecycleScope.launch { refreshOverlayEpg() }
    }
}
