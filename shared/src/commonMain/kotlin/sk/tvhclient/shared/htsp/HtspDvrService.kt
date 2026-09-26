package sk.tvhclient.shared.htsp

import sk.tvhclient.shared.api.DvrAccess
import sk.tvhclient.shared.api.DvrResult
import sk.tvhclient.shared.api.DvrService
import sk.tvhclient.shared.model.TvhServer

/**
 * M472: recording over HTSP (addDvrEntry, cancelDvrEntry, deleteDvrEntry).
 *
 * M693: the commands go over the app's shared HTSP connection (HtspSession), the same one the stream
 * uses — like Kodi. A new recording then arrives as dvrEntryAdd in the session's metadata by itself.
 */
class HtspDvrService(private val server: TvhServer) : DvrService {

    /** M693: DVR commands go over the shared connection (HtspSession) — no connection of their own. */
    private suspend fun session(): HtspSession = HtspSessions.get(server)

    /** The server's response: success=1, or an error with readable text. */
    private fun reply(r: Map<String, Any?>): DvrResult {
        val ok = ((r["success"] as? Long) ?: 0L) == 1L
        if (ok) {
            val id = (r["id"] as? Long)?.toString() ?: (r["id"] as? String)
            return DvrResult(true, id = id)
        }
        return DvrResult.fail((r["error"] as? String) ?: "The server rejected the request")
    }

    override suspend fun access(): DvrAccess = try {
        val s = session()
        // M471/M480: the rights arrive asynchronously right after login (accessUpdate) — the session's
        // reader keeps them; on a brand-new connection wait for them briefly
        var acc = s.access
        var guard = 0
        while (acc == null && guard++ < 10) {
            kotlinx.coroutines.delay(200)
            acc = s.access
        }
        val a = acc
        if (a == null) DvrAccess.UNKNOWN
        else DvrAccess(
            canRecord = a.dvr, canSeeFailed = a.failedDvr, isAdmin = a.admin,
            recordingLimit = a.connLimitDvr, known = true
        )
    } catch (_: Throwable) {
        DvrAccess.UNKNOWN
    }

    override suspend fun recordEvent(eventId: Long, configId: String?): DvrResult = try {
        val args = HashMap<String, Any?>()
        args["eventId"] = eventId
        if (!configId.isNullOrBlank()) args["configName"] = configId
        reply(session().request("addDvrEntry", args))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun cancel(id: String): DvrResult = try {
        val n = id.toLongOrNull() ?: return DvrResult.fail("Invalid recording ID")
        reply(session().request("cancelDvrEntry", mapOf("id" to n)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun delete(id: String): DvrResult = try {
        val n = id.toLongOrNull() ?: return DvrResult.fail("Invalid recording ID")
        reply(session().request("deleteDvrEntry", mapOf("id" to n)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }
}
