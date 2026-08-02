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

    /** Zabudne zistene prava (po zmene servera alebo prihlasenia). */
    fun forget(serverId: String? = null) {
        if (serverId == null) accessCache.clear() else accessCache.remove(serverId)
    }

    suspend fun recordEvent(server: TvhServer, eventId: Long): DvrResult =
        runCatching { serviceFor(server).recordEvent(eventId) }
            .getOrElse { DvrResult.fail(it.message) }

    suspend fun cancel(server: TvhServer, id: String): DvrResult =
        runCatching { serviceFor(server).cancel(id) }
            .getOrElse { DvrResult.fail(it.message) }

    suspend fun delete(server: TvhServer, id: String): DvrResult =
        runCatching { serviceFor(server).delete(id) }
            .getOrElse { DvrResult.fail(it.message) }
}
