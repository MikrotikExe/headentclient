package sk.tvhclient.android

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * M490 / M669: nahrávanie práve bežiacej relácie z prehrávača (vyclenené z PlayerActivity).
 * Stav (práva, eventId, existujúca nahrávka, dialóg výberu profilu M606/M607) aj akcie
 * (nahrať / zrušiť, z kontextovej ponuky) zdieľajú všetky vstupy: klasický bar, telefónny
 * panel „Viac", moderný TV overlay, info okno. Aktivita k stavom pristupuje cez delegáty
 * s pôvodnými názvami (dvrCanRecordState…), lebo ich čítajú composables cez `dvrActivity`.
 */
internal class DvrRecordController(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val live: LiveSession,
    private val epgUpcoming: MutableState<Map<String, List<EpgEvent>>>,
    private val recInProgressByChan: MutableState<Map<String, DvrEntry>>,
    /** M608: červená bodka / kazeta hneď po naplánovaní (PlayerEpgStore.refreshRecordingOnly). */
    private val refreshRecordingOnly: () -> Unit
) {
    val canRecordState = mutableStateOf(false)
    val eventIdState = mutableStateOf<Long?>(null)
    val existingState = mutableStateOf<DvrEntry?>(null)

    // M606: dialog vyberu DVR profilu (zoznam moznosti; prazdny = zatvoreny) + kurzor
    val askState = mutableStateOf<List<String>>(emptyList())
    val askSelState = mutableStateOf(0)
    /** M607: ked dialog profilov patri kanalu z kontextovej ponuky (nie hrajucemu). */
    var askTarget: Pair<LivePlaylist.LiveChannel, EpgEvent>? = null
        private set

    /** Ma sa ovladac nahravania vobec ukazat? */
    fun recordVisible(): Boolean =
        canRecordState.value && (eventIdState.value != null || existingState.value != null)

    private fun currentChannel(): LivePlaylist.LiveChannel? = live.channelsState.value.getOrNull(live.indexState.value)

    fun currentEventId(): Long? {
        val ch = currentChannel() ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        return epgUpcoming.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop }
            ?.eventId
    }

    /**
     * M484: kanal a prave beziaca relacia — po naplanovani sa posle do
     * DvrController, aby sa nahravka hned premietla do zoznamu a tlacidlo sa
     * prepislo na „Zrusit" bez cakania na obnovu cache metadat.
     */
    fun currentLiveEvent(): Pair<String, EpgEvent>? {
        val ch = currentChannel() ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        val ev = epgUpcoming.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop } ?: return null
        return ch.uuid to ev
    }

    /** M521-fix: beziaca nahravka na prave sledovanom kanali z mapy cervených bodiek. */
    fun runningRecordingHere(): DvrEntry? {
        val ch = currentChannel() ?: return null
        return recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] }
    }

    /** M475: naplanovana/beziaca nahravka pre prave sledovanu relaciu (null = ziadna). */
    suspend fun currentEventRecording(server: TvhServer?): DvrEntry? {
        val srv = server ?: return null
        val ch = currentChannel() ?: return null
        val nowSec = System.currentTimeMillis() / 1000
        val ev = epgUpcoming.value[ch.uuid]
            ?.firstOrNull { it.start <= nowSec && nowSec < it.stop } ?: return null
        return DvrController.scheduledFor(srv, ch.uuid, ev.start, ev.stop)
    }

    /**
     * Zisti prava a stav nahravky pre prave sledovanu relaciu.
     *
     * Vola sa pri starte prehravaca a po prepnuti kanala — nie pri otvoreni
     * ovladania. Poradie ovladacov sa pocita z `playerControlOrder()`, takze
     * keby polozka pribudla az kym je lista otvorena, posunuli by sa indexy
     * pod rukou a dpad by aktivoval nieco ine.
     */
    fun refreshState() {
        // M521: zahod stav PREDCHADZAJUCEHO kanala hned, este pred nacitanim.
        // Nacitanie zoznamu nahravok trva cez HTTP sekundy (u velkych serverov
        // je to vyse tisic zaznamov) a dovtedy tlacidlo ukazovalo stav kanala,
        // z ktoreho pouzivatel prave odisiel — raz „Zrusit" tam, kde sa nenahrava,
        // inokedy „Nahrat" tam, kde nahravka bezi.
        existingState.value = null
        eventIdState.value = currentEventId()   // z lokalnej EPG cache, synchronne
        // M521-fix: prebiehajucu nahravku vezmi z toho isteho zdroja, z ktoreho sa
        // kreslia cervene bodky v zozname kanalov (fetchDvrInProgress). Je to mapa
        // uz nacitanych BEZIACICH nahravok — dostupna okamzite a spolahliva —
        // kym DvrController.scheduledFor() tahal cely zoznam naplanovanych
        // (u velkeho servera vyse tisic zaznamov) a kym dobehol, tlacidlo ukazovalo
        // nespravny stav.
        currentChannel()?.let { ch ->
            recInProgressByChan.value.let { it[ch.uuid] ?: it[ch.name] }
                ?.let { existingState.value = it }
        }
        scope.launch {
            val srv = Tvh.store.active()
            var eid = currentEventId()
            // najprv rychly a spolahlivy zdroj, az potom pomaly zoznam naplanovanych
            var rec = runningRecordingHere() ?: currentEventRecording(srv)
            // M520: ak sa EPG pre tento kanal este nestihlo nacitat, prehravac
            // nepozna beziacu relaciu — a bez nej sa tlacidlo nahravania vobec
            // nezobrazi. Prave preto sa objavovalo raz ano, raz nie, podla toho,
            // ci uz EPG doslo. Dohladame si ju teda priamo zo servera.
            if (eid == null && srv != null) {
                val uuid = currentChannel()?.uuid
                if (uuid != null) {
                    val evs = withContext(Dispatchers.IO) {
                        runCatching {
                            val api = Tvh.apiFor(srv)
                            try { Tvh.fetchEpgForChannel(srv, api, uuid) } finally { api.close() }
                        }.getOrDefault(emptyList())
                    }
                    if (evs.isNotEmpty()) {
                        // doplnime do cache, nech to dalsie otvorenie uz nemusi tahat
                        epgUpcoming.value = epgUpcoming.value + (uuid to evs)
                        val nowSec = System.currentTimeMillis() / 1000
                        val cur = evs.firstOrNull { it.start <= nowSec && nowSec < it.stop }
                        eid = cur?.eventId
                        if (rec == null && cur != null) {
                            rec = DvrController.scheduledFor(srv, uuid, cur.start, cur.stop)
                        }
                    }
                }
            }
            eventIdState.value = eid
            existingState.value = rec
            canRecordState.value = srv != null && DvrController.access(srv).canRecord
        }
    }

    /** Nahrat prave beziacu relaciu, alebo zrusit uz naplanovanu nahravku. */
    fun toggleRecordCurrent() {
        val srv = Tvh.store.active() ?: return
        scope.launch {
            val existing = existingState.value ?: currentEventRecording(srv)
            if (existing == null) {
                // M606: volitelny vyber profilu — az potom nahravanie
                val opts = DvrProfileAsk.options(ctx, srv)
                if (opts.isNotEmpty()) {
                    askTarget = null
                    askSelState.value = 0
                    askState.value = opts
                    return@launch
                }
            }
            recordCurrent(null)
        }
    }

    /** M606: vyber v dialogu profilov (OK / klik) alebo zrusenie (BACK). */
    fun resolveAsk(name: String?) {
        askState.value = emptyList()
        val target = askTarget
        askTarget = null
        if (name == null) return
        Tvh.store.active()?.let { DvrAskPref.setLastUsed(ctx, it.id, name) }
        scope.launch {
            if (target != null) recordEventOf(target.first, target.second, name) else recordCurrent(name)
        }
    }

    private suspend fun recordCurrent(profile: String?) {
        val srv = Tvh.store.active() ?: return
        run {
            val existing = existingState.value ?: currentEventRecording(srv)
            val eid = eventIdState.value ?: currentEventId()
            if (existing == null && eid == null) return
            val hint = currentLiveEvent()
            val r = if (existing != null) DvrController.cancel(srv, existing)
            else DvrController.recordEvent(
                srv, eid!!,
                hint?.first ?: "",
                hint?.second?.start ?: 0L,
                hint?.second?.stop ?: 0L,
                hint?.second?.title ?: "",
                profile
            )
            // M484: pri duplikate dohladaj, kde uz nahravka je
            val dup = if (r.success || existing != null) null
            else DvrController.duplicateOf(srv, hint?.second?.title ?: "")
            existingState.value = currentEventRecording(srv)
            if (r.success) refreshRecordingOnly()   // M608: cervena bodka / kazeta hned
            Toast.makeText(
                ctx,
                when {
                    r.success && existing != null -> ctx.getString(R.string.dvr_rec_cancelled)
                    r.success -> ctx.getString(R.string.dvr_rec_scheduled)
                    dup != null && dup.channelName.isNotBlank() -> ctx.getString(
                        R.string.dvr_rec_duplicate, dup.channelName,
                        sk.tvhclient.shared.formatDayLabel(dup.start) + " " +
                            sk.tvhclient.shared.formatTimeHm(dup.start)
                    )
                    else -> r.error ?: ctx.getString(
                        if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed
                    )
                },
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** M607: nahravanie z kontextovej ponuky — s volitelnym vyberom profilu (M606). */
    fun recordFromCtxMenu(ch: LivePlaylist.LiveChannel, ev: EpgEvent) {
        val srv = Tvh.store.active() ?: return
        scope.launch {
            val opts = DvrProfileAsk.options(ctx, srv)
            if (opts.isNotEmpty()) {
                askTarget = ch to ev
                askSelState.value = 0
                askState.value = opts
            } else recordEventOf(ch, ev, null)
        }
    }

    suspend fun recordEventOf(ch: LivePlaylist.LiveChannel, ev: EpgEvent, profile: String?) {
        val srv = Tvh.store.active() ?: return
        val eid = ev.eventId ?: return
        val r = DvrController.recordEvent(srv, eid, ch.uuid, ev.start, ev.stop, ev.title, profile)
        val dup = if (r.success) null else DvrController.duplicateOf(srv, ev.title)
        if (r.success) {
            refreshRecordingOnly()   // cervena bodka pri kanali
            if (ch.uuid == live.uuidState.value) existingState.value = currentEventRecording(srv)
        }
        Toast.makeText(
            ctx,
            when {
                r.success -> ctx.getString(R.string.dvr_rec_scheduled)
                dup != null && dup.channelName.isNotBlank() -> ctx.getString(
                    R.string.dvr_rec_duplicate, dup.channelName,
                    sk.tvhclient.shared.formatDayLabel(dup.start) + " " + sk.tvhclient.shared.formatTimeHm(dup.start)
                )
                else -> r.error ?: ctx.getString(if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed)
            },
            Toast.LENGTH_LONG
        ).show()
    }
}
