package sk.tvhclient.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.EpgEvent

/**
 * EPG pre mriezku. Drzi sa v cache (Activity-scoped ViewModel), takze prepnutie
 * kariet ani znovuotvorenie mriezky uz nestahuje to iste.
 *
 * HTTP: per-kanal na poziadanie (ensureChannel) ked je riadok viditelny.
 * HTSP: JEDNO spojenie, vsetky kanaly progresivne (loadHtsp) — server nezvlada
 *       spojenie per kanal, preto sa to robi naraz na jednom spojeni, ale
 *       eventy pribudaju priebezne (po kanaloch).
 */
class EpgGridViewModel : ViewModel() {

    private val _epg = MutableStateFlow<Map<String, List<EpgEvent>>>(emptyMap())
    val epg: StateFlow<Map<String, List<EpgEvent>>> = _epg

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    // generacia: po refresh sa zvysi a nacitanie sa spusti nanovo
    private val _gen = MutableStateFlow(0)
    val gen: StateFlow<Int> = _gen

    private val inFlight = HashSet<String>()
    private var htspStarted = false

    /** HTTP: nacita EPG pre jeden kanal, ak ho este nemame (volane pri zobrazeni riadku). */
    fun ensureChannel(uuid: String) {
        val server = Tvh.store.active() ?: return
        if (server.connectionMode == "htsp") return   // HTSP ide cez loadHtsp()
        if (_epg.value.containsKey(uuid) || inFlight.contains(uuid)) return
        inFlight.add(uuid)
        _loading.value = true
        viewModelScope.launch {
            try {
                val evs = withContext(Dispatchers.IO) {
                    val api = Tvh.apiFor(server)
                    try { Tvh.fetchEpgForChannel(server, api, uuid) } finally { api.close() }
                }
                _epg.value = _epg.value + (uuid to evs)
            } catch (_: Exception) {
            } finally {
                inFlight.remove(uuid)
                if (inFlight.isEmpty()) _loading.value = false
            }
        }
    }

    /** HTSP: jedno spojenie, vsetky kanaly progresivne (eventy pribudaju po kanaloch). */
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
                        _epg.value = _epg.value + (uuid to evs)
                    }
                }
            } catch (_: Exception) {
            } finally {
                _loading.value = false
            }
        }
    }

    /** Vynutene obnovenie - zahodi cache, nacitanie sa spusti nanovo. */
    fun refresh() {
        _epg.value = emptyMap()
        inFlight.clear()
        htspStarted = false
        _gen.value = _gen.value + 1
    }
}
