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
    init {
        viewModelScope.launch {
            val disk = withContext(Dispatchers.IO) {
                runCatching {
                    EpgCache.loadLive(appCtx, sid(), System.currentTimeMillis() / 1000, EpgRangePref.daysBack(appCtx))
                }.getOrDefault(emptyMap())
            }
            if (disk.isNotEmpty()) _epgMap.value = disk + _epgMap.value
        }
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
            if (map.isNotEmpty()) {
                _epgMap.value = _epgMap.value + map
                // M278: save to disk (live cache), so that now/next survives a restart/restore
                viewModelScope.launch(Dispatchers.IO) {
                    runCatching {
                        EpgCache.saveLive(appCtx, sid(), map, System.currentTimeMillis() / 1000, EpgRangePref.daysBack(appCtx))
                    }
                }
            }
        }
    }

    override fun onCleared() {
        api?.close()
        super.onCleared()
    }
}
