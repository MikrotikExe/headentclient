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

    // HTSP: kanal -> zoznam nadchadzajucich relacii (na auto-prechod na zozname).
    // M278: seed z diskovej „live" cache (rovnaka ako prehravac) — now/next naskoci hned
    // aj po restarte/obnove obrazovky, kym sa na pozadi dotiahnu cerstve data.
    private val _epgMap = MutableStateFlow<Map<String, List<sk.tvhclient.shared.model.EpgEvent>>>(
        EpgCache.loadLive(
            app.applicationContext,
            Tvh.store.active()?.id ?: "default",
            System.currentTimeMillis() / 1000,
            EpgRangePref.daysBack(app.applicationContext)
        )
    )
    val epgMap: StateFlow<Map<String, List<sk.tvhclient.shared.model.EpgEvent>>> = _epgMap

    private val _viewMode = MutableStateFlow(ChannelViewMode.LIST)
    val viewMode: StateFlow<ChannelViewMode> = _viewMode
    fun setViewMode(m: ChannelViewMode) { _viewMode.value = m }

    private var api: TvhApi? = null
    private var loadedOnce = false
    private var reloadToken = -1
    // M540: prave beziace nacitanie. Na TV volali loadIfNeeded() dva LaunchedEffect-y
    // naraz (prednacitanie + obnova posledneho kanala, M496) -> druhy load() zavrel
    // HTTP klienta prvemu (`api?.close()`), prvy skoncil chybou „Parent job is
    // Completed" (v diag logu pri kazdom starte) a data sa stahovali dvakrat.
    private var loadJob: kotlinx.coroutines.Job? = null

    fun setQuery(q: String) { _query.value = q }

    /** Nacita len ak este nebolo nacitane, alebo ak sa zmenil server (reload token). */
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
        // M540: bez force sa do beziaceho nacitania nezasahuje; s force sa stare zrusi
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

                // HTSP: now/next nie je v rychlom dumpe -> doplnime na pozadi
                if (server.connectionMode == "htsp") {
                    loadHtspNowNext(server)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // M540: zrusene force-reloadom — ziadna chyba, stav nastavi novy load
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
    private fun loadHtspNowNext(server: sk.tvhclient.shared.model.TvhServer, retry: Boolean = false) {
        if (!retry) nowNextRetries = 0
        viewModelScope.launch {
            sk.tvhclient.shared.htsp.HtspData.lastEpgError = null
            val map = try {
                withContext(Dispatchers.IO) { Tvh.fetchEpgUpcoming(server) }
            } catch (e: Exception) {
                CrashLogger.report(getApplication(), "ChannelsViewModel.nowNext", e)   // M551-fix2
                emptyMap()
            }
            // M551-fix2/fix3: diagnostika — kanály bez udalostí na serveri (empty) sú normálny
            // stav (kanály bez EPG), loguje sa len počet; opakuje sa len pri skutočnej chybe
            val empty = sk.tvhclient.shared.htsp.HtspData.lastEpgEmpty
            val failed = sk.tvhclient.shared.htsp.HtspData.lastEpgFailed
            if (failed > 0 || map.isEmpty()) {
                CrashLogger.report(
                    getApplication(), "ChannelsViewModel.nowNext",
                    "HTSP now/next: ${map.size} ok, ${empty.size} without EPG, failed=$failed, lastEpgError=" +
                        (sk.tvhclient.shared.htsp.HtspData.lastEpgError ?: "none")
                )
            }
            if ((failed > 0 || map.isEmpty()) && nowNextRetries < 3) {
                nowNextRetries++
                viewModelScope.launch {
                    kotlinx.coroutines.delay(20_000)
                    loadHtspNowNext(server, retry = true)
                }
            }
            if (map.isNotEmpty()) {
                _epgMap.value = _epgMap.value + map
                // M278: ulozit na disk (live cache), nech now/next prezije restart/obnovu
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
