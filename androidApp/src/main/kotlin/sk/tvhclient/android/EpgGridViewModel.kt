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
 * EPG pre mriezku. Drzi sa v cache (Activity-scoped ViewModel), takze prepnutie
 * kariet ani znovuotvorenie mriezky uz nestahuje to iste.
 *
 * Navyse je EPG perzistovane na disk (EpgCache) — appka si tak pamata uplynule dni
 * aj ked ich Tvheadend z EPG uz vymazal. Pri starte sa cache nacita (orezany podla
 * EpgRangePref.daysBack), cerstve data sa s nim zlucuju a priebezne ukladaju.
 *
 * HTTP: per-kanal na poziadanie (ensureChannel) ked je riadok viditelny.
 * HTSP: JEDNO spojenie, vsetky kanaly progresivne (loadHtsp).
 */
class EpgGridViewModel(app: Application) : AndroidViewModel(app) {

    private val appCtx = app.applicationContext

    private fun serverId(): String = Tvh.store.active()?.id ?: "default"
    private fun daysBack(): Int = EpgRangePref.daysBack(appCtx)
    private fun nowSec(): Long = System.currentTimeMillis() / 1000

    // M611: disková cache mriežky sa číta na pozadí (predtým synchrónne v konštruktore
    // na hlavnom vlákne — pri veľkom EPG ANR). Čerstvé dáta majú prednosť, cache dopĺňa.
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

    // generacia: po refresh sa zvysi a nacitanie sa spusti nanovo
    private val _gen = MutableStateFlow(0)
    val gen: StateFlow<Int> = _gen

    private val inFlight = HashSet<String>()
    private var htspStarted = false

    /** HTTP: nacita EPG pre jeden kanal, ak ho este nemame (volane pri zobrazeni riadku). */
    fun ensureChannel(uuid: String) {
        val server = Tvh.store.active() ?: return
        if (server.connectionMode == "htsp") return   // HTSP ide cez loadHtsp()
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

    /** Ulozi aktualny EPG na disk (orezany podla daysBack). */
    private fun persist() {
        val snapshot = _epg.value
        val sid = serverId()
        val db = daysBack()
        val n = nowSec()
        viewModelScope.launch(Dispatchers.IO) {
            EpgCache.save(appCtx, sid, snapshot, n, db)
        }
    }

    /** Vynutene obnovenie — z disku znova nacita pamatane dni a stiahne cerstve data. */
    fun refresh() {
        loadDiskAsync(replace = true)   // M611
        inFlight.clear()
        htspStarted = false
        _gen.value = _gen.value + 1
    }
}
