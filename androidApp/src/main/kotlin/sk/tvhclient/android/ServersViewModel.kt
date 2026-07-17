package sk.tvhclient.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ConnectionResult
import sk.tvhclient.shared.model.TvhServer

sealed class TestState {
    data object Idle : TestState()
    data object Running : TestState()
    data class Done(val result: ConnectionResult) : TestState()
}

class ServersViewModel : ViewModel() {

    private val store = Tvh.store

    private val _servers = MutableStateFlow(store.list())
    val servers: StateFlow<List<TvhServer>> = _servers

    private val _activeId = MutableStateFlow(store.activeId)
    val activeId: StateFlow<String?> = _activeId

    private val _testState = MutableStateFlow<TestState>(TestState.Idle)
    val testState: StateFlow<TestState> = _testState

    /**
     * M380: stream profily nacitane zo servera pre ponuku v nastaveniach.
     * Prazdny zoznam = zatial nenacitane / server nedostupny -> formular
     * pouzije predvoleny zoznam (ChannelPrefs.profileOptions).
     */
    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val profiles: StateFlow<List<String>> = _profiles

    /** Automaticke nacitanie profilov; chyby sa ignoruju (ostane fallback). */
    fun loadProfiles(server: TvhServer) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { Tvh.streamProfiles(server) }
            if (list.isNotEmpty()) _profiles.value = list
        }
    }

    fun clearProfiles() { _profiles.value = emptyList() }

    fun refresh() {
        _servers.value = store.list()
        _activeId.value = store.activeId
    }

    fun save(server: TvhServer) {
        store.upsert(server)
        // zmena konfiguracie servera (napr. sposob pripojenia) -> stara cache je neplatna
        sk.tvhclient.shared.htsp.HtspData.clear(server.id)
        refresh()
        TabController.dataReload.value++
    }

    fun delete(id: String) {
        sk.tvhclient.shared.htsp.HtspData.clear(id)
        store.delete(id)
        refresh()
        TabController.dataReload.value++
    }

    fun setActive(id: String) {
        store.activeId = id
        refresh()
        // iny aktivny server -> znovu nacitaj data
        TabController.dataReload.value++
    }

    fun newId(): String = Tvh.newServerId()

    fun test(server: TvhServer) {
        _testState.value = TestState.Running
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Tvh.testConnection(server) }
            _testState.value = TestState.Done(result)
        }
    }

    // Server s funkcnym rezimom po poslednom testAuto (HTSP <-> HTTP fallback). Ulozi sa tento.
    var resolvedServer: TvhServer? = null
        private set

    /** Test s auto-detekciou pripojenia (HTSP 9982 default -> ak nedostupne, poistka HTTP 9981). */
    fun testAuto(server: TvhServer) {
        _testState.value = TestState.Running
        resolvedServer = null
        viewModelScope.launch {
            val (result, working) = withContext(Dispatchers.IO) { Tvh.testConnectionAuto(server) }
            resolvedServer = working
            _testState.value = TestState.Done(result)
        }
    }

    fun resetTest() {
        _testState.value = TestState.Idle
    }
}
