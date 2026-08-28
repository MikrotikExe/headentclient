package sk.tvhclient.android

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import sk.tvhclient.shared.api.DvrAccess
import sk.tvhclient.shared.api.DvrResult
import sk.tvhclient.shared.api.DvrService
import sk.tvhclient.shared.api.HttpDvrService
import sk.tvhclient.shared.htsp.HtspDvrService
import sk.tvhclient.shared.model.TvhServer

/**
 * M472: vyber spravnej DVR cesty a cache prav.
 *
 * Appka moze byt na server pripojena cez HTSP alebo HTTP — nahravanie musi
 * fungovat v oboch pripadoch. Tato vrstva rozhodne, ktoru implementaciu
 * pouzit, a drzi si zistene prava, aby sa neoverovali pri kazdom otvoreni
 * detailu programu.
 */
object DvrController {

    private val accessCache = HashMap<String, DvrAccess>()

    private fun serviceFor(server: TvhServer): DvrService =
        if (server.connectionMode == "htsp") HtspDvrService(server) else HttpDvrService(server)

    /**
     * Prava pouzivatela; prvykrat sa zistia zo servera, potom sa drzia.
     *
     * M480: VSETKY volania bezia na Dispatchers.IO s casovym stropom. UI ich
     * spusta z LaunchedEffect, teda z hlavneho vlakna — otvorenie HTSP spojenia
     * a jeho zatvorenie su blokujuce operacie a appka po nich prestala reagovat
     * (ANR). Ak server neodpovie do limitu, tvarime sa, ze prava nepoznamé
     * — radsej nez zamrznut.
     */
    suspend fun access(server: TvhServer): DvrAccess {
        accessCache[server.id]?.let { return it }
        val a = withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000L) {
                runCatching { serviceFor(server).access() }.getOrNull()
            }
        }
        // M519: NEUKLADAJ neuspech natrvalo.
        //
        // Cache prav nema expiraciu, takze ked prve zistenie zlyhalo alebo
        // nestihlo 8 s limit, ulozilo sa UNKNOWN a appka az do restartu verila,
        // ze nahravat sa neda — tlacidlo sa preto raz zobrazilo a inokedy nie,
        // podla toho, ci sa prve volanie po starte podarilo. Neuspech si preto
        // nepamatame a pri dalsom pokuse sa prava zistia znova.
        android.util.Log.d("tvhdvr", "access: mode=${server.connectionMode} " +
            "vysledok=${if (a == null) "TIMEOUT/CHYBA" else "canRecord=${a.canRecord} known=${a.known}"}")
        if (a == null || !a.known) return DvrAccess.UNKNOWN
        accessCache[server.id] = a
        return a
    }

    // ---- M474: uz naplanovane nahravky (aby sa neponukalo dvakrat) ----
    private class Sched(val ts: Long, val list: List<sk.tvhclient.shared.model.DvrEntry>)
    private val schedCache = HashMap<String, Sched>()
    private const val SCHED_TTL_MS = 60_000L

    /**
     * M484: lokalne prekrytie zoznamu nahravok.
     *
     * HTSP metadata maju vlastnu 120 s cache (HtspData.metadata), takze tesne po
     * naplanovani alebo zruseni nahravky chodi zo servera este stary zoznam a UI
     * by sa dve minuty neprepinalo. Zahodit celu metadata cache nejde — nasledne
     * otvorenie mriezky by znovu tahalo cele EPG, co je na slabych boxoch drahe.
     * Drzime si preto lokalne, co sme prave pridali a co zrusili, a na zoznam zo
     * servera to aplikujeme. Prekrytie sa samo zahodi, len co ho server potvrdi.
     */
    private class Pending {
        val added = ArrayList<sk.tvhclient.shared.model.DvrEntry>()
        val removed = HashSet<String>()
    }
    private val pendingOps = HashMap<String, Pending>()

    /** Ta ista relacia? Rovnaky kanal a casovy prekryv — DVR zaznam nema eventId. */
    private fun sameSlot(
        a: sk.tvhclient.shared.model.DvrEntry, b: sk.tvhclient.shared.model.DvrEntry
    ): Boolean = a.channelUuid.isNotBlank() && a.channelUuid == b.channelUuid &&
        a.start < b.stop && b.start < a.stop

    /**
     * Co uz server potvrdil, netreba dalej prekryvat.
     *
     * Prazdny zoznam sa ignoruje — nevieme rozlisit „ziadne nahravky" od
     * neuspesneho nacitania (timeout vracia tiez prazdno), a zahodit prekrytie
     * kvoli vypadku spojenia by vratilo UI do stareho stavu.
     */
    private fun reconcile(serverId: String, fresh: List<sk.tvhclient.shared.model.DvrEntry>) {
        if (fresh.isEmpty()) return
        val p = pendingOps[serverId] ?: return
        p.added.removeAll { a -> fresh.any { sameSlot(it, a) } }
        p.removed.removeAll { id -> fresh.none { it.commandId == id } }
        if (p.added.isEmpty() && p.removed.isEmpty()) pendingOps.remove(serverId)
    }

    /** Zoznam zo servera + nase zmeny, ktore este nestihol premietnut. */
    private fun overlay(
        serverId: String, list: List<sk.tvhclient.shared.model.DvrEntry>
    ): List<sk.tvhclient.shared.model.DvrEntry> {
        val p = pendingOps[serverId] ?: return list
        val kept = list.filterNot { p.removed.contains(it.commandId) }
        if (p.added.isEmpty()) return kept
        return kept + p.added.filterNot { a -> kept.any { sameSlot(it, a) } }
    }

    /**
     * Naplanovane/beziace nahravky. Kratka cache — zoznam sa pouziva pri kazdom
     * otvoreni detailu relacie a nema zmysel kvoli tomu zatazovat server.
     */
    private suspend fun scheduled(server: TvhServer): List<sk.tvhclient.shared.model.DvrEntry> {
        val now = System.currentTimeMillis()
        schedCache[server.id]?.let {
            if (now - it.ts < SCHED_TTL_MS) return overlay(server.id, it.list)
        }
        val list = withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000L) {
                runCatching {
            if (server.connectionMode == "htsp") {
                val meta = sk.tvhclient.shared.htsp.HtspData.metadata(
                    server, withEpg = false, nowSec = now / 1000
                )
                sk.tvhclient.shared.htsp.HtspData.dvrScheduled(meta)
            } else {
                val api = sk.tvhclient.shared.api.TvhApi(server)
                try { api.dvrUpcoming() } finally { api.close() }
            }
                }.getOrNull()
            }
        }
        // M518: NEUKLADAJ neuspech do cache.
        //
        // `null` = vyprsal 8 s limit alebo volanie zlyhalo. Doteraz sa v takom
        // pripade ulozil prazdny zoznam a appka celu minutu verila, ze ziadne
        // nahravky neexistuju — tlacidlo sa preto raz ukazalo ako „Zrusit" a
        // inokedy ako „Nahrat", podla toho, ci sa nacitanie prave podarilo.
        // Pri neuspechu radsej vratime posledny znamy stav a skusime nabuduce.
        android.util.Log.d("tvhdvr", "scheduled: mode=${server.connectionMode} " +
            "polozky=${list?.size ?: -1}" + if (list == null) " (TIMEOUT/CHYBA)" else "")
        if (list == null) return overlay(server.id, schedCache[server.id]?.list ?: emptyList())
        schedCache[server.id] = Sched(now, list)
        reconcile(server.id, list)          // M484
        return overlay(server.id, list)
    }

    /**
     * M475: naplanovana nahravka pre danu relaciu (null = ziadna). Vracia cely
     * zaznam, aby sa dala rovno zrusit — na to treba jej id/uuid.
     */
    suspend fun scheduledFor(
        server: TvhServer, channelUuid: String, start: Long, stop: Long
    ): sk.tvhclient.shared.model.DvrEntry? {
        val all = scheduled(server)
        val hit = all.firstOrNull { r ->
            val sameChannel = r.channelUuid.isNotBlank() && r.channelUuid == channelUuid
            sameChannel && r.start < stop && start < r.stop
        }
        android.util.Log.d("tvhdvr", "scheduledFor: kanal=$channelUuid rel=$start-$stop " +
            "zoznam=${all.size} najdene=${hit?.title ?: "NIC"}")
        // vypis LEN zaznamy pre tento kanal — ukaze, ci kanal nahravky vobec ma
        // a preco sa casy neprekryli (padding, iny cas, prazdne uuid)
        val sameCh = all.filter { it.channelUuid == channelUuid }
        android.util.Log.d("tvhdvr", "   pre tento kanal zaznamov=${sameCh.size}")
        sameCh.take(5).forEach {
            android.util.Log.d("tvhdvr", "   zaznam: ${it.start}-${it.stop} " +
                "real=${it.startReal}-${it.stopReal} stav=${it.status}/${it.schedStatus} ${it.title}")
        }
        return hit
    }

    /** Po naplanovani nahravky zoznam zneplatni, nech sa hned prejavi v UI. */
    fun invalidateScheduled(serverId: String? = null) {
        if (serverId == null) schedCache.clear() else schedCache.remove(serverId)
    }

    /**
     * M484: volitelny popis relacie (kanal a cas) — po uspesnom naplanovani sa
     * zaznam hned premietne do zoznamu, aby sa tlacidlo prepislo na „Zrusit"
     * bez cakania na to, kym sa obnovi cache metadat.
     */
    suspend fun recordEvent(
        server: TvhServer,
        eventId: Long,
        channelUuid: String = "",
        start: Long = 0,
        stop: Long = 0,
        title: String = ""
    ): DvrResult {
        // M486: nahravaj do profilu zvoleneho v nastaveniach servera; prazdne
        // = necha rozhodnut server (predvolba konta)
        val cfg = server.dvrConfig.ifBlank { null }
        val r = ioResult { serviceFor(server).recordEvent(eventId, cfg) }
        if (r.success) {
            invalidateScheduled(server.id)   // M474
            if (channelUuid.isNotBlank() && stop > start) {
                val id = r.id.orEmpty()
                // M485: relacia, ktora uz bezi, sa zacne nahravat okamzite —
                // stav musi sediet, inak by detail hlasil „Naplanovane"
                val nowSec = System.currentTimeMillis() / 1000
                val live = nowSec in start until stop
                pendingOps.getOrPut(server.id) { Pending() }.added.add(
                    sk.tvhclient.shared.model.DvrEntry(
                        uuid = id, dvrId = id, dispTitle = title,
                        channelUuid = channelUuid, start = start, stop = stop,
                        status = if (live) "recording" else "scheduled"
                    )
                )
            }
        }
        return r
    }

    /**
     * M484: uz naplanovana nahravka tej istej relacie (aj na inom kanali).
     *
     * Tvheadend novy zaznam vyhodnoti ako duplikat, zmaze ho a cez HTSP vrati len
     * strohe „Could not add dvrEntry" — konkretny zaznam si teda dohladame podla
     * nazvu sami, nech vieme pouzivatelovi povedat, kde uz nahravka je.
     */
    suspend fun duplicateOf(
        server: TvhServer, title: String
    ): sk.tvhclient.shared.model.DvrEntry? {
        val key = title.trim().lowercase()
        if (key.isEmpty()) return null
        return scheduled(server).firstOrNull { it.title.trim().lowercase() == key }
    }

    /** M480: operacia na IO vlakne s casovym stropom; timeout = citatelna chyba. */
    private suspend fun ioResult(block: suspend () -> DvrResult): DvrResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000L) {
                runCatching { block() }.getOrElse { DvrResult.fail(it.message) }
            }
        // M491: DvrController je objekt bez kontextu, takze hlasku nevie prelozit.
        // Vrati timeout priznak a text doplni UI.
        } ?: DvrResult.fail(null, timeout = true)

    suspend fun cancel(server: TvhServer, id: String): DvrResult {
        val r = ioResult { serviceFor(server).cancel(id) }
        if (r.success) invalidateScheduled(server.id)   // M475
        return r
    }

    suspend fun delete(server: TvhServer, id: String): DvrResult {
        val r = ioResult { serviceFor(server).delete(id) }
        if (r.success) invalidateScheduled(server.id)
        return r
    }

    /**
     * M483: zrusenie/zastavenie a mazanie podla celeho zaznamu.
     *
     * Volajuci nema ako vediet, ci server bezi cez HTSP (ciselne id) alebo HTTP
     * (hex uuid) — `commandId` vrati to spravne. Pri dokoncenych HTSP nahravkach
     * je `uuid` hex (kvoli /dvrfile), takze mazanie cez `uuid` by vzdy zlyhalo.
     */
    suspend fun cancel(server: TvhServer, entry: sk.tvhclient.shared.model.DvrEntry): DvrResult =
        cancel(server, entry.commandId).also { if (it.success) forgetEntry(server.id, entry) }

    suspend fun delete(server: TvhServer, entry: sk.tvhclient.shared.model.DvrEntry): DvrResult =
        delete(server, entry.commandId).also { if (it.success) forgetEntry(server.id, entry) }

    /** M484: zruseny/zmazany zaznam skry, kym ho server prestane posielat. */
    private fun forgetEntry(serverId: String, entry: sk.tvhclient.shared.model.DvrEntry) {
        val p = pendingOps.getOrPut(serverId) { Pending() }
        p.added.removeAll { sameSlot(it, entry) }
        p.removed.add(entry.commandId)
    }
}
