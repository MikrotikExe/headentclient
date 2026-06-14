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

    fun refresh() {
        _servers.value = store.list()
        _activeId.value = store.activeId
    }

    fun save(server: TvhServer) {
        store.upsert(server)
        refresh()
    }

    fun delete(id: String) {
        store.delete(id)
        refresh()
    }

    fun setActive(id: String) {
        store.activeId = id
        refresh()
    }

    fun newId(): String = Tvh.newServerId()

    fun test(server: TvhServer) {
        _testState.value = TestState.Running
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { Tvh.testConnection(server) }
            _testState.value = TestState.Done(result)
        }
    }

    fun resetTest() {
        _testState.value = TestState.Idle
    }
}
