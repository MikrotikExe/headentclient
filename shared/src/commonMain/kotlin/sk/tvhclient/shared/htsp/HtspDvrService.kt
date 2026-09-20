package sk.tvhclient.shared.htsp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.api.DvrAccess
import sk.tvhclient.shared.api.DvrResult
import sk.tvhclient.shared.api.DvrService
import sk.tvhclient.shared.model.TvhServer

/**
 * M472: recording over HTSP (addDvrEntry, cancelDvrEntry, deleteDvrEntry).
 *
 * Every operation opens its own short-lived connection — the app does not keep an HTSP session
 * open and accounts with a connection limit would otherwise lose a slot. The commands are fast
 * (one request/reply), so the overhead is negligible.
 */
class HtspDvrService(private val server: TvhServer) : DvrService {

    private suspend fun <T> withClient(block: suspend (HtspClient) -> T): T {
        // M621: DVR commands (schedule, cancel, delete, list) also go through
        // connectWithRetry — three attempts and a fallback to the remembered IP. A DNS outage when
        // switching networks otherwise took the command down on the first attempt (UnresolvedAddressException).
        val c = HtspData.connectWithRetry(server)
        return try {
            block(c)
        } finally {
            withContext(NonCancellable) { c.close() }
        }
    }

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
        withClient { c ->
            // the rights arrive asynchronously right after login — a short wait is enough
            c.send("enableAsyncMetadata", mapOf("epg" to 0L))
            // M480: the rights arrive right after login; we wait for at most a few messages,
            // not 20 (each with a 1.5 s timeout = up to 30 s of waiting and an ANR).
            var acc = c.access
            var guard = 0
            while (acc == null && guard++ < 4) {
                c.pumpOnce()
                acc = c.access
            }
            runCatching { c.send("disableAsyncMetadata", emptyMap(), withSeq = false) }
            val a = acc ?: return@withClient DvrAccess.UNKNOWN
            DvrAccess(
                canRecord = a.dvr, canSeeFailed = a.failedDvr, isAdmin = a.admin,
                recordingLimit = a.connLimitDvr, known = true
            )
        }
    } catch (_: Throwable) {
        DvrAccess.UNKNOWN
    }

    override suspend fun recordEvent(eventId: Long, configId: String?): DvrResult = try {
        withClient { c ->
            val args = HashMap<String, Any?>()
            args["eventId"] = eventId
            if (!configId.isNullOrBlank()) args["configName"] = configId
            reply(c.recvReply(c.send("addDvrEntry", args)))
        }
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun cancel(id: String): DvrResult = try {
        val n = id.toLongOrNull() ?: return DvrResult.fail("Invalid recording ID")
        withClient { c -> reply(c.recvReply(c.send("cancelDvrEntry", mapOf("id" to n)))) }
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun delete(id: String): DvrResult = try {
        val n = id.toLongOrNull() ?: return DvrResult.fail("Invalid recording ID")
        withClient { c -> reply(c.recvReply(c.send("deleteDvrEntry", mapOf("id" to n)))) }
    } catch (e: Throwable) { DvrResult.fail(e.message) }
}
