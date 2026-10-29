package sk.tvhclient.android

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ChannelCategory
import sk.tvhclient.shared.api.ChannelRow
import sk.tvhclient.shared.api.TvhApi

sealed class ChannelsState {
    data object Loading : ChannelsState()
    data class Loaded(
        val categories: List<ChannelCategory>,
        val allRows: List<ChannelRow>
    ) : ChannelsState()
    data class Error(val message: String) : ChannelsState()
    data object NoServer : ChannelsState()
}

enum class ChannelViewMode { LIST, GRID, TILES }

class ChannelsViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app.applicationContext
    private fun sid(): String = Tvh.store.active()?.id ?: "default"

    private val _state = MutableStateFlow<ChannelsState>(ChannelsState.Loading)
    val state: StateFlow<ChannelsState> = _state

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    // HTSP: channel -> the list of upcoming programmes (for the auto-transition in the list).
    // M278: seed from the disk "live" cache (the same one as the player) — now/next appears immediately
    // even after a restart/screen restore, while fresh data is fetched in the background.
    // M611: the disk cache is read in the background — a synchronous read in the constructor
    // (the main thread) took seconds with a large EPG and Play reported an ANR
    // (EpgEvent deserialize / EpgCache.readStreamed / "I/O on the main thread").
    // Network data takes precedence: the cache is only filled in underneath what has already arrived.
    private val _epgMap = MutableStateFlow<Map<String, List<sk.tvhclient.shared.model.EpgEvent>>>(emptyMap())
    val epgMap: StateFlow<Map<String, List<sk.tvhclient.shared.model.EpgEvent>>> = _epgMap
    init { seedEpgFromDisk(sid()) }

    /** M738: the server whose now/next [_epgMap] holds (the ViewModel lives for the whole activity). */
    private var epgServerId: String? = Tvh.store.active()?.id

    private fun seedEpgFromDisk(serverId: String) {
        viewModelScope.launch {
            val disk = withContext(Dispatchers.IO) {
                runCatching {
                    EpgCache.loadLive(appCtx, serverId, System.currentTimeMillis() / 1000, EpgRangePref.daysBack(appCtx))
                }.getOrDefault(emptyMap())
            }
            if (disk.isNotEmpty() && Tvh.store.active()?.id == serverId) _epgMap.value = disk + _epgMap.value
        }
    }

    /**
     * M738 (audit K10): another active server -> its own now/next. Until now the map was never cleared:
     * with two HTSP servers (channel ids 1, 2, 3… on both) the tiles and the list showed server A's
     * programmes on server B's channels, and the next save wrote A's events into B's disk cache.
     */
    private fun switchEpgServerIfNeeded(serverId: String) {
        if (epgServerId == serverId) return
        epgServerId = serverId
        _epgMap.value = emptyMap()
        nowNextRetries = 0
        streamWaitJob?.cancel()
        seedEpgFromDisk(serverId)
    }

    private val _viewMode = MutableStateFlow(ChannelViewMode.LIST)
    val viewMode: StateFlow<ChannelViewMode> = _viewMode
    fun setViewMode(m: ChannelViewMode) { _viewMode.value = m }

    private var api: TvhApi? = null
    private var loadedOnce = false
    private var reloadToken = -1
    // M540: the load currently running. On TV two LaunchedEffects called loadIfNeeded()
    // at once (preloading + restoring the last channel, M496) -> the second load() closed
    // the HTTP client on the first (`api?.close()`), the first ended with the error "Parent job is
    // Completed" (in the diag log on every start) and the data was downloaded twice.
    private var loadJob: kotlinx.coroutines.Job? = null

    fun setQuery(q: String) { _query.value = q }

    /** Loads only if it has not been loaded yet, or if the server changed (reload token). */
    fun loadIfNeeded() {
        val tok = TabController.dataReload.value
        val changed = tok != reloadToken
        if (loadedOnce && _state.value is ChannelsState.Loaded && !changed) return
        reloadToken = tok
        load(force = loadedOnce && changed)
    }

    fun load(force: Boolean = false) {
        val server = Tvh.store.active()
        if (server == null) {
            _state.value = ChannelsState.NoServer
            return
        }
        switchEpgServerIfNeeded(server.id)   // M738
        // M540: without force a running load is not interfered with; with force the old one is cancelled
        if (loadJob?.isActive == true) {
            if (!force) return
            loadJob?.cancel()
        }
        _state.value = ChannelsState.Loading
        if (force && server.connectionMode == "htsp") {
            sk.tvhclient.shared.htsp.HtspData.clear(server.id)
        }
        loadJob = viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val a = Tvh.apiFor(server)
                    val old = api
                    api = a
                    old?.close()
                    val repo = Tvh.channelRepository(server, a)
                    val cats = repo.load(force)
                    val all = repo.allRows(false)
                    cats to all
                }
                _state.value = ChannelsState.Loaded(result.first, result.second)
                loadedOnce = true
                startLive(server)   // M720: now/next from the live EPG window (HTSP push / HTTP comet)

                // HTSP: now/next is not in the quick dump -> we fill it in in the background
                if (server.connectionMode == "htsp") {
                    loadHtspNowNext(server)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // M540: cancelled by a force reload — no error, the state is set by the new load
            } catch (e: Exception) {
                CrashLogger.report(getApplication(), "ChannelsViewModel.load", e)
                _state.value = ChannelsState.Error(
                    e.message ?: getApplication<android.app.Application>()
                        .getString(R.string.load_error)   // M491
                )
            }
        }
    }

    private var nowNextRetries = 0
    private var streamWaitJob: kotlinx.coroutines.Job? = null   // M603
    private fun loadHtspNowNext(server: sk.tvhclient.shared.model.TvhServer, retry: Boolean = false) {
        if (!retry) nowNextRetries = 0
        viewModelScope.launch {
            sk.tvhclient.shared.htsp.HtspData.lastEpgError = null
            val map = try {
                withContext(Dispatchers.IO) { Tvh.fetchEpgUpcoming(server) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // M581-fix: leaving the screen is not an error, it does not belong in the log
            } catch (e: Exception) {
                CrashLogger.report(getApplication(), "ChannelsViewModel.nowNext", e)   // M551-fix2
                emptyMap()
            }
            // M551-fix2/fix3: diagnostics — channels with no events on the server (empty) are a normal
            // state (channels without EPG), only the count is logged; it is retried only on a real error
            val empty = sk.tvhclient.shared.htsp.HtspData.lastEpgEmpty
            val failed = sk.tvhclient.shared.htsp.HtspData.lastEpgFailed
            // M603: the round was skipped because of a running transfer (M595) — only the
            // cache was returned. This is not an error: no log entry ("0 ok, 226 without EPG" was
            // from the previous round) and no retry; it is fetched once playback ends.
            val skipped = sk.tvhclient.shared.htsp.HtspData.lastEpgSkipped
            if (!skipped && (failed > 0 || map.isEmpty())) {
                CrashLogger.report(
                    getApplication(), "ChannelsViewModel.nowNext",
                    "HTSP now/next: ${map.size} ok, ${empty.size} without EPG, failed=$failed, lastEpgError=" +
                        (sk.tvhclient.shared.htsp.HtspData.lastEpgError ?: "none")
                )
            }
            // M572: retry with a growing interval (20 s, 60 s, 180 s). With an
            // unreachable network / an unknown server name there is no point knocking every
            // 20 seconds — the user saw it as repeated errors in the log.
            // A server that legitimately has no EPG (failed=0, all channels empty) is not retried.
            if (skipped && map.isEmpty()) {
                // M603: the cache was empty (the app started straight into playback) —
                // wait until the transfer ends and fetch now/next afterwards. A single wait,
                // at most 2 hours, with no log entry.
                if (streamWaitJob?.isActive != true) streamWaitJob = viewModelScope.launch {
                    var waited = 0L
                    while (sk.tvhclient.shared.htsp.HtspData.streaming && waited < 2 * 60 * 60_000L) {
                        kotlinx.coroutines.delay(5_000); waited += 5_000
                    }
                    if (!sk.tvhclient.shared.htsp.HtspData.streaming) {
                        kotlinx.coroutines.delay(1_500)   // so the server releases the connection after playback
                        loadHtspNowNext(server, retry = true)
                    }
                }
            }
            if (!skipped && (failed > 0 || (map.isEmpty() && empty.isEmpty())) && nowNextRetries < 3) {
                nowNextRetries++
                val wait = when (nowNextRetries) { 1 -> 20_000L; 2 -> 60_000L; else -> 180_000L }
                viewModelScope.launch {
                    kotlinx.coroutines.delay(wait)
                    loadHtspNowNext(server, retry = true)
                }
            }
            if (map.isNotEmpty() && epgServerId == server.id) {   // M738: not a late answer of the previous server
                _epgMap.value = _epgMap.value + map
                // M278: save to disk (live cache), so that now/next survives a restart/restore
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        EpgCache.saveLive(appCtx, server.id, map, System.currentTimeMillis() / 1000, EpgRangePref.daysBack(appCtx))
                    }
                }
            }
        }
    }

    // ---- M720: the live EPG window ----

    private var liveJob: kotlinx.coroutines.Job? = null
    private var liveServerId: String? = null
    private var liveSavedMs = 0L

    /**
     * M720: the home screens read [epgMap] — fed from the live window (LiveEpg) the server keeps
     * current, so the programme names on the tiles change the moment a programme ends or the EPG
     * is corrected, in HTTP mode too (so far HTTP tiles only knew the dump's now/next). The disk
     * live cache is refreshed from it as well (at most once a minute).
     */
    private fun startLive(server: sk.tvhclient.shared.model.TvhServer) {
        if (liveJob?.isActive == true && liveServerId == server.id) return
        liveJob?.cancel()
        liveServerId = server.id
        val live = Tvh.liveEpg(server)
        liveJob = viewModelScope.launch {
            launch {
                var lastApply = 0L
                live.revision.collect {
                    if (live.syncedAt == 0L) return@collect
                    if (epgServerId != server.id) return@collect   // M738: the active server changed
                    val since = System.currentTimeMillis() - lastApply   // at most every 2 s (conflated)
                    if (since < 2_000) kotlinx.coroutines.delay(2_000 - since)
                    lastApply = System.currentTimeMillis()
                    val nowSec = System.currentTimeMillis() / 1000
                    val win = live.snapshot
                    if (win.isEmpty()) return@collect
                    val end = live.windowEnd(nowSec)
                    // M731 (audit R11): the merge of hundreds of channels runs off the main thread
                    val base = _epgMap.value
                    val m = withContext(Dispatchers.Default) {
                        val mm = base.toMutableMap()
                        for ((ch, l) in win) mm[ch] = sk.tvhclient.shared.epg.LiveEpg.mergeWindow(mm[ch], l, end)
                        mm
                    }
                    _epgMap.value = m
                    val nowMs = System.currentTimeMillis()
                    if (nowMs - liveSavedMs >= 60_000L) {
                        liveSavedMs = nowMs
                        launch(Dispatchers.IO) {
                            runCatching { EpgCache.saveLive(appCtx, server.id, m, nowSec, EpgRangePref.daysBack(appCtx)) }   // M738
                        }
                    }
                }
            }
            while (true) {
                kotlinx.coroutines.delay(60_000)
                if (AppForeground.isForeground) Tvh.liveEpg(server)   // in front: keep the feed/connection alive, restart it when it is gone
            }
        }
    }

    override fun onCleared() {
        api?.close()
        super.onCleared()
    }
}
