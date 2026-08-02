package sk.tvhclient.android

import android.content.Context
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

    /** Prava pouzivatela; prvykrat sa zistia zo servera, potom sa drzia. */
    suspend fun access(server: TvhServer): DvrAccess {
        accessCache[server.id]?.let { return it }
        val a = runCatching { serviceFor(server).access() }.getOrElse { DvrAccess.UNKNOWN }
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
        val list = runCatching {
            if (server.connectionMode == "htsp") {
                val meta = sk.tvhclient.shared.htsp.HtspData.metadata(
                    server, withEpg = false, nowSec = now / 1000
                )
                sk.tvhclient.shared.htsp.HtspData.dvrScheduled(meta)
            } else {
                val api = sk.tvhclient.shared.api.TvhApi(server)
                try { api.dvrUpcoming() } finally { api.close() }
            }
        }.getOrElse { emptyList() }
        schedCache[server.id] = Sched(now, list)
        return list
    }

    /**
     * Ma uz relacia na tomto kanali naplanovanu nahravku? Porovnava sa kanal a
     * casovy prekryv — DVR zaznam si eventId nedrzi, ale cas a kanal staci.
     */
    suspend fun isScheduled(
        server: TvhServer, channelUuid: String, start: Long, stop: Long
    ): Boolean = scheduled(server).any { r ->
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
        val r = runCatching { serviceFor(server).recordEvent(eventId) }
            .getOrElse { DvrResult.fail(it.message) }
        if (r.success) invalidateScheduled(server.id)   // M474
        return r
    }

    suspend fun cancel(server: TvhServer, id: String): DvrResult =
        runCatching { serviceFor(server).cancel(id) }
            .getOrElse { DvrResult.fail(it.message) }

    suspend fun delete(server: TvhServer, id: String): DvrResult =
        runCatching { serviceFor(server).delete(id) }
            .getOrElse { DvrResult.fail(it.message) }
}
