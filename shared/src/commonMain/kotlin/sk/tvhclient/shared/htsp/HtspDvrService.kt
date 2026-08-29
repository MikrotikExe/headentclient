package sk.tvhclient.shared.htsp

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.api.DvrAccess
import sk.tvhclient.shared.api.DvrResult
import sk.tvhclient.shared.api.DvrService
import sk.tvhclient.shared.model.TvhServer

/**
 * M472: nahravanie cez HTSP (addDvrEntry, cancelDvrEntry, deleteDvrEntry).
 *
 * Kazda operacia si otvara vlastne kratke spojenie — appka nedrzi HTSP session
 * otvorenu a ucty s limitom pripojeni by inak prisli o slot. Prikazy su rychle
 * (jeden request/reply), takze rezia je zanedbatelna.
 */
class HtspDvrService(private val server: TvhServer) : DvrService {

    private suspend fun <T> withClient(block: suspend (HtspClient) -> T): T {
        val c = HtspClient(server.host, server.htspPort, server.username, server.password)
        c.connect()
        return try {
            block(c)
        } finally {
            withContext(NonCancellable) { c.close() }
        }
    }

    /** Odpoved servera: success=1, alebo error s citatelnym textom. */
    private fun reply(r: Map<String, Any?>): DvrResult {
        val ok = ((r["success"] as? Long) ?: 0L) == 1L
        if (ok) {
            val id = (r["id"] as? Long)?.toString() ?: (r["id"] as? String)
            return DvrResult(true, id = id)
        }
        return DvrResult.fail((r["error"] as? String) ?: "Server odmietol požiadavku")
    }

    override suspend fun access(): DvrAccess = try {
        withClient { c ->
            // prava chodia asynchronne hned po prihlaseni — staci chvilu pockat
            c.send("enableAsyncMetadata", mapOf("epg" to 0L))
            // M480: prava chodia hned po prihlaseni; cakame najviac par sprav,
            // nie 20 (kazda s 1,5 s timeoutom = az 30 s cakania a ANR).
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
        val n = id.toLongOrNull() ?: return DvrResult.fail("Neplatné ID nahrávky")
        withClient { c -> reply(c.recvReply(c.send("cancelDvrEntry", mapOf("id" to n)))) }
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun delete(id: String): DvrResult = try {
        val n = id.toLongOrNull() ?: return DvrResult.fail("Neplatné ID nahrávky")
        withClient { c -> reply(c.recvReply(c.send("deleteDvrEntry", mapOf("id" to n)))) }
    } catch (e: Throwable) { DvrResult.fail(e.message) }
}
