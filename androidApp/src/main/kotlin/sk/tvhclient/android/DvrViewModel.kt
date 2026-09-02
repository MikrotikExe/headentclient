package sk.tvhclient.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.DvrEntry

sealed class DvrState {
    data object Loading : DvrState()
    data object NoServer : DvrState()
    // channelOrder: meno kanala -> cislo (pre zoradenie archivu ako v zozname)
    // channelPicons: meno kanala -> picon URL (logo v archive)
    data class Loaded(
        val entries: List<DvrEntry>,
        val channelOrder: Map<String, Int>,
        val channelPicons: Map<String, String?>,
        val recording: List<DvrEntry> = emptyList()
    ) : DvrState()
    data class Error(val message: String) : DvrState()
}

/**
 * DVR je read-only archiv (mazanie/planovanie su admin funkcie, tu nie su).
 * Nacita vsetky dokoncene nahravky + zoznam kanalov (pre poradie) raz;
 * navigaciu cez zlozky robi obrazovka v pamati.
 */
class DvrViewModel : ViewModel() {

    private val _state = MutableStateFlow<DvrState>(DvrState.Loading)
    val state: StateFlow<DvrState> = _state

    private var loadedOnce = false
    private var reloadToken = -1

    /** Nacita len ak este nemame data, alebo ak sa zmenil server (reload token). */
    fun loadIfNeeded() {
        val tok = TabController.dataReload.value
        val changed = tok != reloadToken
        if (loadedOnce && _state.value is DvrState.Loaded && !changed) return
        reloadToken = tok
        load(showLoading = true)
    }

    /** Vynutene obnovenie (napr. tlacidlo) — bez blikania, drzi stare data. */
    fun refresh() {
        Tvh.store.active()?.let { sk.tvhclient.shared.htsp.HtspData.clear(it.id) }
        load(showLoading = false)
    }

    fun load(showLoading: Boolean = true) {
        val server = Tvh.store.active()
        if (server == null) {
            _state.value = DvrState.NoServer
            return
        }
        if (showLoading && _state.value !is DvrState.Loaded) {
            _state.value = DvrState.Loading
        }
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    val api = Tvh.apiFor(server)
                    try {
                        val e = Tvh.fetchDvrFinished(server, api)
                        val rec = Tvh.fetchDvrInProgress(server, api)
                        val channels = Tvh.fetchChannels(server, api)
                        val order = channels
                            .filter { it.number != null }
                            .associate { it.name to it.number!! }
                        val picons = channels.associate {
                            it.name to Tvh.piconUrl(server, it.iconPublicUrl)
                        }
                        listOf(e, order, picons, rec)
                    } finally {
                        api.close()
                    }
                }
                @Suppress("UNCHECKED_CAST")
                _state.value = DvrState.Loaded(
                    result[0] as List<DvrEntry>,
                    result[1] as Map<String, Int>,
                    result[2] as Map<String, String?>,
                    result[3] as List<DvrEntry>
                )
                loadedOnce = true
            } catch (e: Exception) {
                runCatching { CrashLogger.report(sk.tvhclient.shared.storage.AppContextHolder.context, "DvrViewModel.load", e) }   // M556: doteraz potichu
                if (_state.value !is DvrState.Loaded) {
                    _state.value = DvrState.Error(e.message ?: "")   // M491: prazdne = UI doplni preklad
                }
            }
        }
    }
}
