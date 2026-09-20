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
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.storage.EpgCacheCodec

/**
 * EPG for the grid. Kept in a cache (Activity-scoped ViewModel), so switching
 * tabs or reopening the grid no longer downloads the same thing again.
 *
 * On top of that the EPG is persisted to disk (EpgCache) — the app thus remembers past days
 * even when Tvheadend has already deleted them from its EPG. On startup the cache is loaded (trimmed by
 * EpgRangePref.daysBack), fresh data is merged into it and saved as it arrives.
 *
 * HTTP: per channel on demand (ensureChannel) when the row is visible.
 * HTSP: ONE connection, all channels progressively (loadHtsp).
 */
class EpgGridViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app.applicationContext

    private fun serverId(): String = Tvh.store.active()?.id ?: "default"
    private fun daysBack(): Int = EpgRangePref.daysBack(appCtx)
    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    // M611: the grid's disk cache is read in the background (previously synchronously in the constructor
    // on the main thread — ANR with a large EPG). Fresh data takes precedence, the cache fills in the gaps.
    private val _epg = MutableStateFlow<Map<String, List<EpgEvent>>>(emptyMap())
    val epg: StateFlow<Map<String, List<EpgEvent>>> = _epg
    private fun loadDiskAsync(replace: Boolean) {
        viewModelScope.launch {
            val disk = withContext(Dispatchers.IO) {
                runCatching { EpgCache.load(appCtx, serverId(), nowSec(), daysBack()) }.getOrDefault(emptyMap())
            }
            if (replace) _epg.value = disk
            else if (disk.isNotEmpty()) _epg.value = disk + _epg.value
        }
    }
    init { loadDiskAsync(replace = false) }

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // generation: bumped after a refresh and loading restarts from scratch
    private val _gen = MutableStateFlow(0)
    val gen: StateFlow<Int> = _gen

    private val inFlight = HashSet<String>()
    private var htspStarted = false

    /** HTTP: loads the EPG for a single channel if we do not have it yet (called when the row is shown). */
    fun ensureChannel(uuid: String) {
        val server = Tvh.store.active() ?: return
        if (server.connectionMode == "htsp") return   // HTSP goes through loadHtsp()
        if (inFlight.contains(uuid)) return
        inFlight.add(uuid)
        _loading.value = true
        viewModelScope.launch {
            try {
                val evs = withContext(Dispatchers.IO) {
                    val api = Tvh.apiFor(server)
                    try { Tvh.fetchEpgForChannel(server, api, uuid) } finally { api.close() }
                }
                _epg.value = EpgCacheCodec.mergeChannel(_epg.value, uuid, evs)
            } catch (e: Exception) {
                CrashLogger.report(getApplication(), "EpgGridViewModel", e)
            } finally {
                inFlight.remove(uuid)
                if (inFlight.isEmpty()) {
                    _loading.value = false
                    persist()
                }
            }
        }
    }

    /** HTSP: one connection, all channels progressively (events arrive channel by channel). */
    fun loadHtsp() {
        val server = Tvh.store.active() ?: return
        if (server.connectionMode != "htsp") return
        if (htspStarted) return
        htspStarted = true
        _loading.value = true
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    Tvh.fetchEpgGridProgressive(server) { uuid, evs ->
                        _epg.value = EpgCacheCodec.mergeChannel(_epg.value, uuid, evs)
                    }
                }
            } catch (e: Exception) {
                CrashLogger.report(getApplication(), "EpgGridViewModel", e)
            } finally {
                _loading.value = false
                persist()
            }
        }
    }

    /** Saves the current EPG to disk (trimmed by daysBack). */
    private fun persist() {
        val snapshot = _epg.value
        val sid = serverId()
        val db = daysBack()
        val n = nowSec()
        viewModelScope.launch(Dispatchers.IO) {
            EpgCache.save(appCtx, sid, snapshot, n, db)
        }
    }

    /** Forced refresh — reloads the remembered days from disk and downloads fresh data. */
    fun refresh() {
        loadDiskAsync(replace = true)   // M611
        inFlight.clear()
        htspStarted = false
        _gen.value = _gen.value + 1
    }
}
