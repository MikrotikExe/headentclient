package sk.tvhclient.android

import androidx.activity.ComponentActivity
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * M634: EPG now/next pre zoznam kanálov v prehrávači a jeho cache (vyclenené z PlayerActivity).
 *
 * Vlastní: [upcoming] (kanál -> aktuálna + nasledujúce relácie; M270 spinner [loading]),
 * čas poslednej úspešnej obnovy (M271, zrkadlený do LivePlaylist.epgLastOkMs), opakovanie pri
 * neúplnom HTSP EPG (M551-fix), zlučovaný zápis na disk (M275/M456) a čítanie z disku (M611).
 *
 * Z aktivity dostáva zdieľaný stav ako odkazy: [liveChannels] (obohacuje now/next a červenú
 * bodku, zapisuje aj do LivePlaylist), [recInProgress] (prebiehajúce nahrávky podľa kanála)
 * a [onDvrStateChanged] (aktivita prepočíta tlačidlo nahrávania, M526).
 */
class PlayerEpgStore(
    private val activity: ComponentActivity,
    private val liveServer: () -> TvhServer?,
    private val liveChannels: MutableState<List<LivePlaylist.LiveChannel>>,
    private val recInProgress: MutableState<Map<String, DvrEntry>>,
    private val onDvrStateChanged: () -> Unit
) {
    // cache mapa kanal(uuid) -> aktualna + najblizsie relacie (pre EPG browser na TV)
    val upcoming = mutableStateOf<Map<String, List<EpgEvent>>>(LivePlaylist.epgUpcoming)
    // M270: spinner pri prvom/zastaranom nacitani EPG v zozname kanalov
    val loading = mutableStateOf(false)
    // M271: cas poslednej obnovy nacitavame z procesovej cache, aby reopen nesťahoval znova
    private var epgLastOkMs = LivePlaylist.epgLastOkMs

    /** Obnovi now/next pre vsetky kanaly v zozname (kym je otvoreny). */
    suspend fun refreshOverlayEpg() {
        val srv = liveServer() ?: return
        val cur = liveChannels.value
        if (cur.isEmpty()) return
        val nowS = System.currentTimeMillis() / 1000
        epgPartial = false   // M551-fix
        var epgSkipped = false   // M603
        try {
            // prebiehajuce nahravky -> ktore kanaly sa prave nahravaju (cervena bodka/kazeta + vyber archiv)
            val recList: List<sk.tvhclient.shared.model.DvrEntry> =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try { Tvh.fetchDvrInProgress(srv, api) }
                    catch (e: Exception) { emptyList() }
                    finally { api.close() }
                }.let { DvrController.overlayInProgress(srv.id, it) }   // M608
            val recMap = recList.associateBy { it.channelUuid.ifBlank { it.channelName } }
            recInProgress.value = recMap
            // M526: mapa dorazila az teraz — prepocitaj stav tlacidla nahravania.
            // Pri PRVOM nacitani bezi refreshDvrState skor, nez je mapa k dispozicii,
            // takze tlacitko ostalo prazdne az do prveho prepnutia kanala.
            onDvrStateChanged()
            if (srv.connectionMode == "htsp") {
                sk.tvhclient.shared.htsp.HtspData.lastEpgError = null
                sk.tvhclient.shared.htsp.HtspData.lastEpgFailed = 0
                val map = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    Tvh.fetchEpgUpcoming(srv)
                }
                // M551-fix: neuplny vysledok (getEvents pre niektore kanaly zlyhalo) sa
                // zobrazi, ale NEpovazuje sa za cerstvy — inak by chybajuce kanaly ostali
                // bez EPG 3 hodiny (epgIsStale). Naplanuje sa opakovanie o 20 s (max 3x).
                val failed = sk.tvhclient.shared.htsp.HtspData.lastEpgFailed
                // M603: kolo sa preskocilo, lebo bezi prenos (M595) — dostali sme
                // len cache. Nie je to neuplne EPG: nelogovat, neplanovat opakovanie
                // (kazde by sa aj tak preskocilo). Cerstvost sa neobnovi, takze po
                // skonceni prehravania sa now/next stiahne znova.
                val skipped = sk.tvhclient.shared.htsp.HtspData.lastEpgSkipped
                epgSkipped = skipped
                epgPartial = !skipped && (map.isEmpty() || failed > 0)
                if (map.isNotEmpty()) upcoming.value = upcoming.value + map
                if (skipped) {
                    epgRetries = 0
                } else if (epgPartial) {
                    CrashLogger.report(
                        activity, "PlayerActivity.epg",
                        "HTSP EPG incomplete: ${map.size}/${cur.size} channels, failed=$failed, withoutEpg=${sk.tvhclient.shared.htsp.HtspData.lastEpgEmpty.size}, lastEpgError=" +
                            (sk.tvhclient.shared.htsp.HtspData.lastEpgError ?: "none")
                    )
                    scheduleEpgRetry()
                } else epgRetries = 0
                val enrichHtsp: (LivePlaylist.LiveChannel) -> LivePlaylist.LiveChannel = { ch ->
                    val ev = map[ch.uuid]?.firstOrNull { it.start <= nowS && nowS < it.stop }
                    val b = if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
                    b.copy(recording = (b.uuid in recMap || b.name in recMap))
                }
                val updated = cur.map(enrichHtsp)
                liveChannels.value = updated
                LivePlaylist.channels = updated
                // M370-fix3: obohat aj cely zoznam, nech prepnutie tagu nestrati EPG v zozname
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map(enrichHtsp)
            } else {
                // HTTP: now/next je v dumpe kanalov -> nacitaj nanovo
                val rows = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try {
                        val repo = Tvh.channelRepository(srv, api)
                        repo.load(true)
                        // M586: aj radia — inak zoznam v prehravaci pri rozhlase
                        // ostal bez „prave hra" (allRows radia vynechava)
                        (repo.allRows(false) + repo.radioRows(false)).associateBy { it.channel.uuid }
                    } finally {
                        api.close()
                    }
                }
                val enrichHttp: (LivePlaylist.LiveChannel) -> LivePlaylist.LiveChannel = { ch ->
                    val r = rows[ch.uuid]
                    val b = if (r != null) ch.copy(
                        nowTitle = r.nowTitle ?: "",
                        nowStart = r.nowStart,
                        nowStop = r.nowStop
                    ) else ch
                    b.copy(recording = (b.uuid in recMap || b.name in recMap))
                }
                val updated = cur.map(enrichHttp)
                liveChannels.value = updated
                LivePlaylist.channels = updated
                // M370-fix3: obohat aj cely zoznam, nech prepnutie tagu nestrati EPG v zozname
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map(enrichHttp)
            }
            // M603: preskocene kolo (bezi prenos) cerstvost neobnovi — po skonceni
            // prehravania sa now/next stiahne pri dalsom otvoreni zoznamu
            if (!epgPartial && !epgSkipped) epgLastOkMs = System.currentTimeMillis()   // M551-fix: neuplne = stale
            // M271: zapis do procesovej cache, nech reopen prehravaca nesťahuje znova
            LivePlaylist.epgLastOkMs = epgLastOkMs
            LivePlaylist.epgUpcoming = upcoming.value
            persistEpg(upcoming.value)   // M275: na disk, nech prezije restart boxu
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // M551-fix3: odchod z prehravaca pocas nacitania nie je chyba
        } catch (e: Exception) {
            CrashLogger.report(activity, "PlayerActivity.epg", e)   // M550-fix: diagnostika
        }
    }

    // M551-fix: neuplne HTSP EPG -> opakovany pokus na pozadi
    private var epgPartial = false
    private var epgRetries = 0
    private var epgRetryJob: kotlinx.coroutines.Job? = null
    private fun scheduleEpgRetry() {
        if (epgRetries >= 3 || epgRetryJob?.isActive == true) return
        epgRetries++
        epgRetryJob = activity.lifecycleScope.launch {
            kotlinx.coroutines.delay(20_000)
            refreshOverlayEpg()
        }
    }

    /** M270: EPG je zastarane (treba spinner pri prvom nacitani) ak este nikdy nebezalo,
     *  je starsie nez 3 h (dlho vypnuty box), alebo je cache prazdna v HTSP rezime. */
    fun epgIsStale(): Boolean {
        if (epgLastOkMs == 0L) return true
        if (System.currentTimeMillis() - epgLastOkMs > 3L * 60 * 60 * 1000) return true
        return liveServer()?.connectionMode == "htsp" && upcoming.value.isEmpty()
    }

    /** M270: prve nacitanie EPG po otvoreni zoznamu. Spinner ukaze LEN ak je cache
     *  prazdna/zastarana a nacitanie trva dlhsie nez prah (350 ms) — pri rychlom serveri
     *  ani pri prepinani/periodickom refreshe sa neobjavi. */
    fun refreshOverlayEpgInitial() {
        // M524: nahravaci priznak (cervena bodka) osviez VZDY, nezavisle od EPG.
        // Doteraz sa maly DVR dotaz robil len ked bola EPG cache cerstva; ked bola
        // zastarana, appka stahovala cele EPG a bodky sa objavili az po nom —
        // alebo vobec, kym pouzivatel neotvoril velky zoznam kanalov.
        refreshRecordingOnly()
        activity.lifecycleScope.launch {
            // M271: ak mame cerstve EPG (cache z nedavneho otvorenia), nesťahuj znova —
            // odpadne otravne nacitavanie pri kazdom reopene. Fetch len ked je stale.
            if (!epgIsStale()) {
                // M281/M524: EPG je cerstve; nahravaci priznak sa uz osviezil vyssie
                return@launch
            }
            val spinJob = launch {
                kotlinx.coroutines.delay(350)
                loading.value = true
            }
            refreshOverlayEpg()
            spinJob.cancel()
            loading.value = false
        }
    }

    /** M281: rychle osvezenie len nahravacich priznakov (cervena bodka) bez EPG fetchu.
     *  Pouzite pri reopene s cerstvym EPG — now/next uz mame z cache, ale prebiehajuce
     *  nahravky sa medzicasom mohli zmenit. Jeden maly DVR dotaz, ziadny EPG churn. */
    fun refreshRecordingOnly() {
        val srv = liveServer() ?: return
        activity.lifecycleScope.launch {
            val recList: List<sk.tvhclient.shared.model.DvrEntry> =
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val api = Tvh.apiFor(srv)
                    try { Tvh.fetchDvrInProgress(srv, api) }
                    catch (e: Exception) { emptyList() }
                    finally { api.close() }
                }.let { DvrController.overlayInProgress(srv.id, it) }   // M608
            val recMap = recList.associateBy { it.channelUuid.ifBlank { it.channelName } }
            recInProgress.value = recMap
            onDvrStateChanged()   // M526: to iste aj pri rychlom osvezeni
            val cur = liveChannels.value
            if (cur.isNotEmpty()) {
                val updated = cur.map { it.copy(recording = (it.uuid in recMap || it.name in recMap)) }
                liveChannels.value = updated
                LivePlaylist.channels = updated
                if (LivePlaylist.allChannels.isNotEmpty())
                    LivePlaylist.allChannels = LivePlaylist.allChannels.map {
                        it.copy(recording = (it.uuid in recMap || it.name in recMap))
                    }
            }
        }
    }

    /** M274: ulozenie per-kanaloveho EPG do cache (a do procesovej cache LivePlaylist),
     *  aby opatovne zobrazenie toho kanala — aj po zatvoreni/otvoreni prehravaca — bolo
     *  okamzite z cache, nie zo siete. Funguje aj v HTTP rezime, kde bulk mapa chyba. */
    fun cacheChannelEpg(uuid: String, list: List<sk.tvhclient.shared.model.EpgEvent>) {
        if (list.isEmpty()) return
        val m = upcoming.value.toMutableMap()
        m[uuid] = list
        upcoming.value = m
        onDvrStateChanged()   // M490: EPG je k dispozicii -> zisti stav nahravania
        LivePlaylist.epgUpcoming = m
        if (epgLastOkMs == 0L) {
            epgLastOkMs = System.currentTimeMillis()
            LivePlaylist.epgLastOkMs = epgLastOkMs
        }
        persistEpg(m)   // M275/M456: zapis na disk (zluceny)
    }

    /** M275: nacitanie EPG z disku do procesovej cache pri starte (ak je process cache
     *  prazdna — napr. po restarte boxu/appky). Zobrazi now/next okamzite; cerstvost
     *  riesi epgIsStale (>3h -> refresh na pozadi). */
    fun hydrateEpgFromDisk(srv: sk.tvhclient.shared.model.TvhServer) {
        if (LivePlaylist.epgUpcoming.isNotEmpty()) {
            // M281: proces cache prezila (Activity recreate) — synchronizuj Activity stav,
            // aby applyCachedEpgToChannels() vedel hned naplnit zoznam.
            if (upcoming.value.isEmpty()) upcoming.value = LivePlaylist.epgUpcoming
            if (epgLastOkMs == 0L) epgLastOkMs = LivePlaylist.epgLastOkMs
            return
        }
        // M611: citanie z disku na pozadi — synchronne v onCreate (hlavne vlakno) pri
        // velkom EPG trvalo sekundy a Play hlasil ANR pri starte prehravaca. Po nacitani
        // sa cache pouzije len ak medzitym neprisli cerstve data zo siete.
        activity.lifecycleScope.launch {
            val nowSec = System.currentTimeMillis() / 1000
            val daysBack = EpgRangePref.daysBack(activity)
            val disk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { EpgCache.loadLive(activity, srv.id, nowSec, daysBack) }.getOrDefault(emptyMap())
            }
            if (disk.isNotEmpty() && upcoming.value.isEmpty()) {
                upcoming.value = disk
                LivePlaylist.epgUpcoming = disk
                val ts = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { EpgCache.lastSavedLive(activity, srv.id) }.getOrDefault(0L)
                }
                if (epgLastOkMs == 0L) { epgLastOkMs = ts; LivePlaylist.epgLastOkMs = ts }
                applyCachedEpgToChannels()   // nazvy relacii pod kanalmi hned, ako su k dispozicii
                onDvrStateChanged()
            }
        }
    }

    /** M281: aplikuj nacachovane now/next (z disku/procesu cez upcoming) na viditelny
     *  zoznam kanalov, aby sa nazvy relacii pod kanalmi zobrazili OKAMZITE aj po restarte/reopene
     *  — bez cakania na sietovy refreshOverlayEpg. Nahravaci priznak (cervena bodka) sa doplni
     *  az ked dobehne fetchDvrInProgress (recInProgress); tu sa neprepisuje, ak je prazdny. */
    fun applyCachedEpgToChannels() {
        val map = upcoming.value
        if (map.isEmpty()) return
        val cur = liveChannels.value
        if (cur.isEmpty()) return
        val nowS = System.currentTimeMillis() / 1000
        val recMap = recInProgress.value
        val updated = cur.map { ch ->
            val ev = map[ch.uuid]?.firstOrNull { it.start <= nowS && nowS < it.stop }
            val b = if (ev != null) ch.copy(nowTitle = ev.title, nowStart = ev.start, nowStop = ev.stop) else ch
            if (recMap.isEmpty()) b else b.copy(recording = (b.uuid in recMap || b.name in recMap))
        }
        liveChannels.value = updated
        LivePlaylist.channels = updated
    }

    // ---- M456: zlucovanie zapisov EPG cache ----
    private var epgPersistJob: kotlinx.coroutines.Job? = null
    private var epgPersistPending: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>? = null
    private var epgLastPersistMs = 0L
    private val epgPersistMinGapMs = 30_000L

    /**
     * M275: asynchronny zapis EPG cache na disk (per server).
     *
     * M456: zapis sa ZLUCUJE. Povodne sa pri kazdej HTSP aktualizacii jedneho
     * kanala serializovala a zapisovala CELA mapa vsetkych kanalov — Tvheadend
     * posiela eventUpdate priebezne, takze pri velkej ponuke to bezalo niekolko
     * krat za sekundu. V profile to bola najdrahsia vec v celej appke
     * (EpgEvent$$serializer.serialize + FileOutputStream.write viac vzoriek nez
     * cely TS muxer) a na slabsom boxe to znamenalo rozdiel 136 % vs 42 % CPU
     * oproti HTTP ceste, kde sa EPG stiahne raz. Teraz sa zapisuje najviac raz
     * za 30 s a vzdy posledny stav; pri odchode z prehravaca sa docaka zvysok.
     */
    private fun persistEpg(map: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>) {
        val srv = liveServer() ?: return
        if (map.isEmpty()) return
        epgPersistPending = map
        if (epgPersistJob?.isActive == true) return
        val since = System.currentTimeMillis() - epgLastPersistMs
        val wait = if (since >= epgPersistMinGapMs) 0L else epgPersistMinGapMs - since
        epgPersistJob = activity.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            if (wait > 0) kotlinx.coroutines.delay(wait)
            val snapshot = epgPersistPending ?: return@launch
            epgPersistPending = null
            epgLastPersistMs = System.currentTimeMillis()
            runCatching {
                val nowSec = System.currentTimeMillis() / 1000
                EpgCache.saveLive(activity, srv.id, snapshot, nowSec, EpgRangePref.daysBack(activity))
            }
        }
    }

    /** M456: dopis EPG cache pri odchode, nech sa posledne zmeny nestratia. */
    fun flushEpgPersist() {
        val srv = liveServer() ?: return
        val snapshot = epgPersistPending ?: return
        epgPersistPending = null
        val app = activity.applicationContext
        val days = EpgRangePref.daysBack(activity)
        // samostatne vlakno — aktivita konci, jej scope by zapis zrusil
        Thread {
            runCatching {
                EpgCache.saveLive(app, srv.id, snapshot, System.currentTimeMillis() / 1000, days)
            }
        }.start()
    }

    /** M274: prefetch EPG na pozadi LEN ak je cache prazdna/zastarana (prvy start, >3h).
     *  Pri reopene s cerstvou cache sa nerobi zbytocny refresh (ziadny lag/churn). */
    fun prefetchEpgIfStale() {
        if (!epgIsStale()) return
        activity.lifecycleScope.launch { refreshOverlayEpg() }
    }
}
