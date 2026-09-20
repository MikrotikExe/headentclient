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
     * M380: stream profiles loaded from the server for the menu in settings.
     * An empty list = not loaded yet / server unreachable -> the form
     * uses the default list (ChannelPrefs.profileOptions).
     */
    private val _profiles = MutableStateFlow<List<String>>(emptyList())
    val profiles: StateFlow<List<String>> = _profiles

    /** Automatic loading of profiles; errors are ignored (the fallback stays). */
    fun loadProfiles(server: TvhServer) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { Tvh.streamProfiles(server) }
            if (list.isNotEmpty()) _profiles.value = list
        }
    }

    fun clearProfiles() { _profiles.value = emptyList() }

    /**
     * M486: DVR profiles (recording configurations) from the server. An empty list =
     * not loaded or the server does not offer them -> the choice is not shown in settings.
     */
    private val _dvrConfigs = MutableStateFlow<List<sk.tvhclient.shared.api.DvrConfig>>(emptyList())
    val dvrConfigs: StateFlow<List<sk.tvhclient.shared.api.DvrConfig>> = _dvrConfigs

    fun loadDvrConfigs(server: TvhServer) {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { Tvh.dvrConfigs(server) }
            if (list.isNotEmpty()) _dvrConfigs.value = list
        }
    }

    fun clearDvrConfigs() { _dvrConfigs.value = emptyList() }

    fun refresh() {
        _servers.value = store.list()
        _activeId.value = store.activeId
    }

    fun save(server: TvhServer) {
        val old = store.list().firstOrNull { it.id == server.id }
        store.upsert(server)
        // a change of server configuration (e.g. the connection method) -> the old cache is invalid
        sk.tvhclient.shared.htsp.HtspData.clear(server.id)
        // M557: a different connection method = different channel identifiers (HTSP channelId vs HTTP
        // uuid) -> the player's process playlist and the remembered "last watched" are invalid
        if (old != null && old.connectionMode != server.connectionMode) {
            LivePlaylist.reset()
            runCatching {
                val ctx = sk.tvhclient.shared.storage.AppContextHolder.context
                LastPlayback.clear(ctx)
                LastChannel.clear(ctx, server.id)
            }
        }
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
        // a different active server -> reload the data
        TabController.dataReload.value++
    }

    fun newId(): String = Tvh.newServerId()


    /**
     * Tests the server EXACTLY as it is configured in the form (the
     * "Test connection" button). Unlike [testAuto] it does not try the fallback between
     * HTSP and HTTP and does not change the mode — the user wants to know whether what
     * they have just entered works. Called by reference as `vm::test`.
     */
    fun test(server: TvhServer) {
        _testState.value = TestState.Running
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Tvh.testConnection(server) }
            _testState.value = TestState.Done(result)
        }
    }

    // The server with the mode that worked in the last testAuto (HTSP <-> HTTP fallback). This one gets saved.
    var resolvedServer: TvhServer? = null
        private set

    /** Test with connection auto-detection (HTSP 9982 default -> if unreachable, HTTP 9981 as a safety net). */
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
