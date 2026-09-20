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
    // channelOrder: channel name -> number (for sorting the archive as in the list)
    // channelPicons: channel name -> picon URL (the logo in the archive)
    data class Loaded(
        val entries: List<DvrEntry>,
        val channelOrder: Map<String, Int>,
        val channelPicons: Map<String, String?>,
        val recording: List<DvrEntry> = emptyList()
    ) : DvrState()
    data class Error(val message: String) : DvrState()
}

/**
 * DVR is a read-only archive (deleting/scheduling are admin functions, they are not here).
 * Loads all finished recordings + the list of channels (for the ordering) once;
 * navigation through folders is done by the screen in memory.
 */
class DvrViewModel : ViewModel() {

    private val _state = MutableStateFlow<DvrState>(DvrState.Loading)
    val state: StateFlow<DvrState> = _state

    private var loadedOnce = false
    private var reloadToken = -1

    // M589: a refresh in progress (the "Refresh" button) — without it nothing visible happened
    // with an unchanged archive and it looked as if the button did not work.
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing
    // a counter of failed refreshes (the data stays old) — the screen warns about it
    // with a message, otherwise a silent failure would look like success
    private val _refreshFailed = MutableStateFlow(0)
    val refreshFailed: StateFlow<Int> = _refreshFailed

    /** Loads only if we do not have data yet, or if the server has changed (reload token). */
    fun loadIfNeeded() {
        val tok = TabController.dataReload.value
        val changed = tok != reloadToken
        if (loadedOnce && _state.value is DvrState.Loaded && !changed) return
        reloadToken = tok
        load(showLoading = true)
    }

    /** A forced refresh (e.g. the button) — without flicker, keeps the old data. */
    fun refresh() {
        if (_refreshing.value) return   // M589: a double-click does not start a second load
        Tvh.store.active()?.let { sk.tvhclient.shared.htsp.HtspData.clear(it.id) }
        load(showLoading = false, refreshing = true)
    }

    fun load(showLoading: Boolean = true, refreshing: Boolean = false) {
        val server = Tvh.store.active()
        if (server == null) {
            _state.value = DvrState.NoServer
            _refreshing.value = false
            return
        }
        if (refreshing) _refreshing.value = true
        if (showLoading && _state.value !is DvrState.Loaded) {
            _state.value = DvrState.Loading
        }
        viewModelScope.launch {
            try {
                suspend fun fetch(): List<Any> = withContext(Dispatchers.IO) {
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
                // M588: the server occasionally closes an idle connection ("Software caused connection
                // abort" / reset) or the phone switches Wi-Fi<->LTE. The first such failure is
                // quietly retried — only the second failure goes into the log.
                val result = try {
                    fetch()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // only network failures (Ktor wraps them in its own types — we look for
                    // an IOException in the cause chain), not e.g. a bad login
                    val io = generateSequence(e as Throwable) { it.cause }
                        .any { it is java.io.IOException }
                    if (!io) throw e
                    sk.tvhclient.shared.htsp.HtspData.clear(server.id)
                    kotlinx.coroutines.delay(1000)
                    fetch()
                }
                @Suppress("UNCHECKED_CAST")
                _state.value = DvrState.Loaded(
                    result[0] as List<DvrEntry>,
                    result[1] as Map<String, Int>,
                    result[2] as Map<String, String?>,
                    result[3] as List<DvrEntry>
                )
                loadedOnce = true
            } catch (e: kotlinx.coroutines.CancellationException) {
                _refreshing.value = false   // M589
                throw e   // M588: leaving the screen / a new load is not an error
            } catch (e: Exception) {
                runCatching { CrashLogger.report(sk.tvhclient.shared.storage.AppContextHolder.context, "DvrViewModel.load", e) }   // M556: silent until now
                if (_state.value !is DvrState.Loaded) {
                    _state.value = DvrState.Error(e.message ?: "")   // M491: empty = the UI fills in the translation
                } else if (refreshing) {
                    _refreshFailed.value = _refreshFailed.value + 1   // M589: the old data stays
                }
            } finally {
                if (refreshing) _refreshing.value = false   // M589
            }
        }
    }
}
