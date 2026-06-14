package sk.tvhclient.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.DvrClassifier
import sk.tvhclient.shared.model.DvrEntry

data class DvrGroup(val categoryKey: String, val entries: List<DvrEntry>)

sealed class DvrState {
    data object Loading : DvrState()
    data object NoServer : DvrState()
    data class Loaded(val groups: List<DvrGroup>) : DvrState()
    data class Error(val message: String) : DvrState()
}

class DvrViewModel : ViewModel() {

    private val _state = MutableStateFlow<DvrState>(DvrState.Loading)
    val state: StateFlow<DvrState> = _state

    fun load() {
        val server = Tvh.store.active()
        if (server == null) {
            _state.value = DvrState.NoServer
            return
        }
        _state.value = DvrState.Loading
        viewModelScope.launch {
            try {
                val groups = withContext(Dispatchers.IO) {
                    val api = Tvh.apiFor(server)
                    try {
                        val entries = api.dvrFinished()
                        // Klasifikuj a zoskup podla kategorie, v poradi DvrClassifier.order
                        val byCat = entries.groupBy { DvrClassifier.classify(it) }
                        DvrClassifier.order.mapNotNull { key ->
                            val list = byCat[key] ?: return@mapNotNull null
                            // v ramci kategorie najnovsie hore
                            DvrGroup(key, list.sortedByDescending { it.start })
                        }
                    } finally {
                        api.close()
                    }
                }
                _state.value = DvrState.Loaded(groups)
            } catch (e: Exception) {
                _state.value = DvrState.Error(e.message ?: "Chyba načítania")
            }
        }
    }

    fun delete(uuid: String) {
        val server = Tvh.store.active() ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val api = Tvh.apiFor(server)
                try { api.dvrRemove(uuid) } finally { api.close() }
            }
            load()
        }
    }
}
