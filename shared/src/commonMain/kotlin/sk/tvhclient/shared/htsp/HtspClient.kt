package sk.tvhclient.shared.htsp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HTSP client for Tvheadend (port 9982) — the connection itself: handshake (hello), SHA1 digest
 * auth (the core is ported from the plugin, htsp.py), message framing and the one-shot metadata dump
 * used by the connection test. M693: everything else (metadata, EPG, recordings, the live stream) runs
 * over ONE shared connection — see HtspSession, which owns an HtspClient and its reader.
 *
 * Uses ktor-network coroutine sockets (they work on both Android and iOS).
 */
class HtspClient(
    private val host: String,
    private val port: Int = 9982,
    private val user: String = "",
    private val pwd: String = ""
) {
    private companion object {
        private const val MAX_MSG_LEN = 32L * 1024 * 1024
    }
    private var selector: SelectorManager? = null
    private var socket: Socket? = null
    private var read: ByteReadChannel? = null
    private var write: ByteWriteChannel? = null
    private var seq = 0
    private val writeMutex = Mutex()
    // M693: the stream state (subscription id, muxer, subtitle decoder) moved to HtspSubscription —
    // a stream now runs as one subscription on the shared HtspSession connection.

    var serverName: String? = null
        private set
    var serverVersion: Long? = null
        private set
    var serverSwVersion: String? = null
        private set
    var serverCapabilities: List<String> = emptyList()
        private set
    private var challenge: ByteArray? = null

    /**
     * M471: the logged-in user's rights from the asynchronous `accessUpdate` message.
     * Tvheadend sends it by itself after login (htsp_server.c). The `dvr` field
     * corresponds to the ACCESS_HTSP_RECORDER right — the app shows or
     * hides recording based on it. The server enforces the rights anyway, this is only for the UI.
     */
    data class Access(
        val admin: Boolean = false,
        val streaming: Boolean = false,
        val dvr: Boolean = false,
        val failedDvr: Boolean = false,
        val connLimitDvr: Int = 0
    )

    /** The last received rights (null = the server did not send them). */
    var access: Access? = null
        private set

    internal fun applyAccessUpdate(m: Map<String, Any?>) {
        fun flag(k: String) = ((m[k] as? Long) ?: 0L) == 1L
        access = Access(
            admin = flag("admin"),
            streaming = flag("streaming"),
            dvr = flag("dvr"),
            failedDvr = flag("faileddvr"),
            connLimitDvr = ((m["limitdvr"] as? Long) ?: 0L).toInt()
        )
    }

    data class Metadata(
        val channels: List<Map<String, Any?>>,
        val tags: List<Map<String, Any?>>,
        val events: List<Map<String, Any?>>,
        val dvr: List<Map<String, Any?>>,
        val syncDone: Boolean,
        val access: Access? = null   // M471
    )

    suspend fun connect() {
        try {
            val sel = SelectorManager(Dispatchers.Default)
            selector = sel
            val s = withTimeoutOrNull(8_000) {
                aSocket(sel).tcp().connect(host, port)
            } ?: run {
                sel.close()
                throw IllegalStateException("port $port unreachable (timeout) — is HTSP forwarded?")
            }
            socket = s
            read = s.openReadChannel()
            write = s.openWriteChannel(autoFlush = true)
            val ok = withTimeoutOrNull(8_000) {
                hello()
                auth()
            } ?: run {
                close()
                throw IllegalStateException("HTSP handshake timeout (is an HTSP server answering on that port?)")
            }
            if (!ok) {
                close()
                throw IllegalStateException("HTSP authentication failed")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Cancelled during connecting (the app is shutting down) — we tidy up the socket cleanly,
            // so it does not hang and take down FinalizerWatchdog. Then we rethrow the cancellation.
            withContext(NonCancellable) { close() }
            throw e
        } catch (e: Throwable) {
            withContext(NonCancellable) { close() }
            throw e
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Throwable) {}
        try { selector?.close() } catch (_: Throwable) {}
        socket = null; selector = null; read = null; write = null
    }

    /** M472: accessible from outside the class too — DVR commands (addDvrEntry and friends). */
    internal suspend fun send(method: String, args: Map<String, Any?> = emptyMap(), withSeq: Boolean = true): Int {
        val msg = HashMap<String, Any?>(args)
        msg["method"] = method
        return writeMutex.withLock {
            var s = -1
            if (withSeq) {
                seq += 1
                s = seq
                msg["seq"] = s.toLong()
            }
            write!!.writeByteArray(Htsmsg.serialize(msg))
            write!!.flush()
            s
        }
    }

    internal suspend fun recv(): Map<String, Any?> {
        val r = read!!
        val hdr = r.readByteArray(4)
        // length as a Long (unsigned 32-bit) — via Int the highest bit would give a negative number
        val len = (((hdr[0].toLong() and 0xFF) shl 24) or
                ((hdr[1].toLong() and 0xFF) shl 16) or
                ((hdr[2].toLong() and 0xFF) shl 8) or
                (hdr[3].toLong() and 0xFF))
        // Protection against OOM: a valid HTSP message realistically has no more than a few MB. If
        // the prefix reports a nonsensical length (corrupted / desynchronized data,
        // e.g. leftovers from an old connection on a fast app restart), it is a protocol
        // error — throw an exception (the connection will be re-established) instead of trying to
        // allocate a huge array, which used to crash the app (OutOfMemoryError).
        if (len < 0 || len > MAX_MSG_LEN) {
            throw IllegalStateException("HTSP: invalid message length=$len (corrupted stream)")
        }
        val body = if (len > 0) r.readByteArray(len.toInt()) else ByteArray(0)
        // Protection: parsing corrupted data (e.g. leftovers from an old connection on a
        // fast restart) can throw OutOfMemoryError. We catch it and turn it
        // into a normal exception, so the app does not crash — the connection will be re-established.
        return try {
            Htsmsg.deserializeMap(body)
        } catch (e: OutOfMemoryError) {
            throw IllegalStateException("HTSP: corrupted message (OOM while parsing)")
        } catch (e: Exception) {
            throw IllegalStateException("HTSP: message parsing error: ${e.message}")
        }
    }

    /** M472: reads one asynchronous message (e.g. accessUpdate) and processes it. */
    internal suspend fun pumpOnce() {
        val m = kotlinx.coroutines.withTimeoutOrNull(1_500L) { recv() } ?: return
        if ((m["method"] as? String) == "accessUpdate") applyAccessUpdate(m)
    }

    internal suspend fun recvReply(s: Int, maxN: Int = 400): Map<String, Any?> {
        repeat(maxN) {
            val m = recv()
            if ((m["seq"] as? Long)?.toInt() == s) return m
            // M693: the rights arrive asynchronously right after login — do not lose them here
            if ((m["method"] as? String) == "accessUpdate") applyAccessUpdate(m)
        }
        throw IllegalStateException("HTSP: no reply arrived for seq=$s")
    }

    private suspend fun hello() {
        // M511: `language` — without it the server uses its own default and the client may
        // get a different language variant of the EPG (e.g. OTA instead of XMLTV). Kodi sends it.
        val args = HashMap<String, Any?>()
        args["htspversion"] = 35L
        args["clientname"] = sk.tvhclient.shared.ClientIdent.userAgent
        args["clientversion"] = sk.tvhclient.shared.ClientIdent.version   // M470
        sk.tvhclient.shared.ClientIdent.lang2.takeIf { it.isNotBlank() }
            ?.let { args["language"] = it }
        val s = send("hello", args)
        val r = recvReply(s)
        serverVersion = r["htspversion"] as? Long
        serverSwVersion = r["serverversion"] as? String
        serverName = r["servername"] as? String
        challenge = (r["challenge"] as? Htsmsg.Bin)?.toByteArray()
        @Suppress("UNCHECKED_CAST")
        serverCapabilities = (r["servercapability"] as? List<Any?>)
            ?.mapNotNull { it as? String } ?: emptyList()
    }

    private suspend fun auth(): Boolean {
        val ch = challenge
        val s = if (pwd.isNotEmpty() && ch != null) {
            val digest = Sha1.digest(pwd.encodeToByteArray() + ch)
            send("authenticate", mapOf("username" to user, "digest" to digest))
        } else {
            send("authenticate", mapOf("username" to user))
        }
        val r = recvReply(s)
        // noaccess=1 => denied
        val denied = ((r["noaccess"] as? Long) ?: 0L) != 0L
        // M692: noaccess together with connlimit=1 = the credentials are fine, but the account's
        // connection limit is used up (typically by the stream that is playing). Reported separately —
        // it is not a wrong password and it must not be retried straight away.
        if (denied && ((r["connlimit"] as? Long) ?: 0L) != 0L) throw HtspConnLimitException()
        return !denied
    }

    /**
     * Loads metadata via enableAsyncMetadata. Reads messages until
     * initialSyncCompleted arrives (+ a short idle for EPG), then turns async off.
     * epgMaxDays limits the EPG (0 = no limit). channelsOnly = the fast path.
     */
    /**
     * Programme for one channel (HTSP getEvents) — a synchronous reply, far
     * faster than the whole async EPG dump. Returns a list of event maps.
     */
    suspend fun getEvents(channelId: Long, numFollowing: Int = 60, maxTime: Long = 0): List<Map<String, Any?>> {
        val args = HashMap<String, Any?>()
        args["channelId"] = channelId
        if (numFollowing > 0) args["numFollowing"] = numFollowing.toLong()
        if (maxTime > 0) args["maxTime"] = maxTime
        // M511: language preference for the per-channel query too
        sk.tvhclient.shared.ClientIdent.lang2.takeIf { it.isNotBlank() }
            ?.let { args["language"] = it }
        val s = send("getEvents", args)
        val r = recvReply(s)
        @Suppress("UNCHECKED_CAST")
        val evs = r["events"] as? List<Any?> ?: return emptyList()
        return evs.mapNotNull { it as? Map<String, Any?> }
    }

    /**
     * M690: the whole EPG schedule of one channel via `epgQuery` (an empty `query` matches every
     * title, `full=1` returns complete events).
     *
     * Unlike [getEvents] with a channelId it does not start at the channel's now/next pointer.
     * Tvheadend clears that pointer whenever a grabber replaces an overlapping event
     * (_epg_channel_rem_broadcast) and only restores it on the channel's next EPG timer — typically
     * on channels fed by both OTA EIT and XMLTV, where the two keep replacing each other. Until then
     * getEvents(channelId) returns nothing although the schedule is full (Kodi and other clients do
     * not notice, they read the async EPG dump). A server that does not know the method replies
     * with an error and no "events" — the result is simply empty.
     */
    suspend fun epgQueryChannel(channelId: Long): List<Map<String, Any?>> {
        val args = HashMap<String, Any?>()
        args["query"] = ""
        args["channelId"] = channelId
        args["full"] = 1L
        sk.tvhclient.shared.ClientIdent.lang2.takeIf { it.isNotBlank() }
            ?.let { args["language"] = it }
        val s = send("epgQuery", args)
        val r = recvReply(s)
        @Suppress("UNCHECKED_CAST")
        val evs = r["events"] as? List<Any?> ?: return emptyList()
        return evs.mapNotNull { it as? Map<String, Any?> }
    }

    suspend fun fetchMetadata(
        withEpg: Boolean = false,
        epgMaxDays: Int = 2,
        channelsOnly: Boolean = false,
        nowSec: Long,
        overallTimeoutMs: Long = if (withEpg) 120_000 else 45_000
    ): Metadata {
        val args = HashMap<String, Any?>()
        args["epg"] = if (withEpg) 1L else 0L
        if (withEpg && epgMaxDays > 0) {
            args["epgMaxTime"] = nowSec + epgMaxDays * 86400L
        }
        // M511: in the async EPG dump as well
        sk.tvhclient.shared.ClientIdent.lang2.takeIf { it.isNotBlank() }
            ?.let { args["language"] = it }
        send("enableAsyncMetadata", args, withSeq = false)

        val channels = ArrayList<Map<String, Any?>>()
        val tags = ArrayList<Map<String, Any?>>()
        val events = ArrayList<Map<String, Any?>>()
        val dvr = ArrayList<Map<String, Any?>>()
        var syncDone = false

        val result = withTimeoutOrNull(overallTimeoutMs) {
            // fast path (without EPG): finish right after initialSyncCompleted.
            // EPG path: read while messages keep coming; after 8s of silence -> finish.
            val idleMs = 8_000L
            while (true) {
                if (channelsOnly && channels.isNotEmpty() && dvr.isNotEmpty()) break
                if (syncDone && !withEpg) break
                val m = if (withEpg) withTimeoutOrNull(idleMs) { recv() } else recv()
                if (m == null) break  // idle timeout during EPG -> done
                when (m["method"] as? String) {
                    "channelAdd" -> channels.add(m)
                    "tagAdd" -> tags.add(m)
                    "eventAdd" -> events.add(m)
                    "dvrEntryAdd" -> dvr.add(m)
                    "accessUpdate" -> applyAccessUpdate(m)   // M471
                    "initialSyncCompleted" -> {
                        syncDone = true
                        if (!withEpg) break
                    }
                }
            }
            true
        }
        try { send("disableAsyncMetadata", emptyMap(), withSeq = false) } catch (_: Throwable) {}

        return Metadata(channels, tags, events, dvr, syncDone || result == true, access)
    }

    /** M408: keepalive — a lightweight request that keeps the connection alive. Tvheadend
     *  answers any method; getDiskSpace changes nothing and is cheap.
     *  We ignore the reply, the point is just to have traffic flowing on the connection. */
    suspend fun keepAlive() {
        // M408-fix: send it WITHOUT seq — the server then does not send a reply, which would otherwise
        // arrive in the stream's receive loop and jam it after a while (the channel
        // stopped coming up after a minute or two). Without seq it is enough to keep the connection alive.
        runCatching { send("getDiskSpace", withSeq = false) }
    }
}
