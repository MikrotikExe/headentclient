package sk.tvhclient.android

import android.content.Context
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
        } ?: DvrAccess.UNKNOWN
        accessCache[server.id] = a
        return a
    }

    // ---- M474: uz naplanovane nahravky (aby sa neponukalo dvakrat) ----
    private class Sched(val ts: Long, val list: List<sk.tvhclient.shared.model.DvrEntry>)
    private val schedCache = HashMap<String, Sched>()
    private const val SCHED_TTL_MS = 60_000L

    /**
     * Naplanovane/beziace nahravky. Kratka cache — zoznam sa pouziva pri kazdom
     * otvoreni detailu relacie a nema zmysel kvoli tomu zatazovat server.
     */
    private suspend fun scheduled(server: TvhServer): List<sk.tvhclient.shared.model.DvrEntry> {
        val now = System.currentTimeMillis()
        schedCache[server.id]?.let { if (now - it.ts < SCHED_TTL_MS) return it.list }
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
        } ?: emptyList()
        schedCache[server.id] = Sched(now, list)
        return list
    }

    /**
     * Ma uz relacia na tomto kanali naplanovanu nahravku? Porovnava sa kanal a
     * casovy prekryv — DVR zaznam si eventId nedrzi, ale cas a kanal staci.
     */
    suspend fun isScheduled(
        server: TvhServer, channelUuid: String, start: Long, stop: Long
    ): Boolean = scheduledFor(server, channelUuid, start, stop) != null

    /**
     * M475: naplanovana nahravka pre danu relaciu (null = ziadna). Vracia cely
     * zaznam, aby sa dala rovno zrusit — na to treba jej id/uuid.
     */
    suspend fun scheduledFor(
        server: TvhServer, channelUuid: String, start: Long, stop: Long
    ): sk.tvhclient.shared.model.DvrEntry? = scheduled(server).firstOrNull { r ->
        val sameChannel = r.channelUuid.isNotBlank() && r.channelUuid == channelUuid
        sameChannel && r.start < stop && start < r.stop
    }

    /** Po naplanovani nahravky zoznam zneplatni, nech sa hned prejavi v UI. */
    fun invalidateScheduled(serverId: String? = null) {
        if (serverId == null) schedCache.clear() else schedCache.remove(serverId)
    }

    /** Zabudne zistene prava (po zmene servera alebo prihlasenia). */
    fun forget(serverId: String? = null) {
        if (serverId == null) accessCache.clear() else accessCache.remove(serverId)
    }

    suspend fun recordEvent(server: TvhServer, eventId: Long): DvrResult {
        val r = ioResult { serviceFor(server).recordEvent(eventId) }
        if (r.success) invalidateScheduled(server.id)   // M474
        return r
    }

    /** M480: operacia na IO vlakne s casovym stropom; timeout = citatelna chyba. */
    private suspend fun ioResult(block: suspend () -> DvrResult): DvrResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000L) {
                runCatching { block() }.getOrElse { DvrResult.fail(it.message) }
            }
        } ?: DvrResult.fail("Server neodpovedal včas")

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
}
