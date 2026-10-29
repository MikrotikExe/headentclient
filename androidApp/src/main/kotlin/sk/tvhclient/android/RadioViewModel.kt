package sk.tvhclient.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ChannelCategory
import sk.tvhclient.shared.api.ChannelRow

sealed class RadioState {
    data object Loading : RadioState()
    data object NoServer : RadioState()
    /** M505: `categories` = split by tags for filtering in the tab. */
    data class Loaded(
        val rows: List<ChannelRow>,
        val categories: List<ChannelCategory> = emptyList()
    ) : RadioState()
    data class Error(val message: String) : RadioState()
}

class RadioViewModel : ViewModel() {

    private val _state = MutableStateFlow<RadioState>(RadioState.Loading)
    val state: StateFlow<RadioState> = _state

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query
    fun setQuery(q: String) { _query.value = q }

    private var loadedOnce = false
    private var reloadToken = -1

    /** Loads only if not loaded yet, or if the server changed (reload token). */
    fun loadIfNeeded() {
        val tok = TabController.dataReload.value
        val changed = tok != reloadToken
        if (loadedOnce && _state.value is RadioState.Loaded && !changed) return
        reloadToken = tok
        load()
    }

    private var loadJob: kotlinx.coroutines.Job? = null   // M540
    private var loadingFor: String? = null   // M738

    fun load() {
        val server = Tvh.store.active()
        if (server == null) {
            _state.value = RadioState.NoServer
            return
        }
        // M540: already running (TV start calls load() twice) — M738 (audit B14): only when it loads the
        // SAME server; a load of the previous server is cancelled (it used to swallow the reload for B)
        if (loadJob?.isActive == true && loadingFor == server.id) return
        loadJob?.cancel()
        loadingFor = server.id
        _state.value = RadioState.Loading
        loadJob = viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    val api = Tvh.apiFor(server)
                    try {
                        val repo = Tvh.channelRepository(server, api)
                        repo.radioRows() to repo.radioCategories()   // M505
                    } finally {
                        api.close()
                    }
                }
                if (Tvh.store.active()?.id != server.id) return@launch   // M738: switched meanwhile
                _state.value = RadioState.Loaded(data.first, data.second)
                loadedOnce = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // M588: cancellation (leaving the screen) is not a station error
            } catch (e: Exception) {
                _state.value = RadioState.Error(e.message ?: "")   // M491: empty = UI fills in the translation
            }
        }
    }
}
