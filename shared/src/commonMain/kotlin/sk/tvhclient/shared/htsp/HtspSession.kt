package sk.tvhclient.shared.htsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import sk.tvhclient.shared.currentTimeSeconds
import sk.tvhclient.shared.model.TvhServer

/**
 * M693: ONE persistent HTSP connection per server for the whole app — the way Kodi (pvr.hts) works.
 *
 * Until now the app opened a short-lived HTSP connection for every data request (channels, EPG,
 * recordings, profiles, capabilities) and the stream had one more of its own. Tvheadend counts them
 * against the account's connection limit, so with a limit of 1 every data request during playback was
 * held for 5 s and refused — thousands of "multiple connections are not allowed" a day (M692 stopped
 * the loops, this removes the cause).
 *
 * Now:
 *  - the session connects once (HtspData.connectWithRetry: retries, remembered IP, connlimit backoff),
 *  - a single reader coroutine reads every message: replies go to the waiting [request] by `seq`,
 *    subscription messages to their [HtspSubscription], async metadata to [model],
 *  - channels, tags and recordings come as async metadata (enableAsyncMetadata, epg=0) and the server
 *    keeps them up to date by itself; EPG is asked for on demand with getEvents / epgQuery over the same
 *    connection (a full EPG push of all channels was dropped earlier for speed and memory on weak boxes),
 *  - a stream is a subscription on the same connection — Tvheadend only re-launches the connection as
 *    a streaming one, so it still counts as one,
 *  - a keepalive every 10 s (M408) keeps NAT/routers from dropping the idle connection,
 *  - the connection is closed after [IDLE_CLOSE_SEC] without use and without a stream; a dead
 *    connection fails all waiting requests and subscriptions and the next use reconnects.
 */
class HtspSession internal constructor(
    val server: TvhServer,
    private val client: HtspClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val pending = HashMap<Int, CompletableDeferred<Map<String, Any?>>>()
    private val subs = HashMap<Int, HtspSubscription>()
    private var nextSubId = 1_000_000   // well away from the request seq numbers (readability of logs)

    @kotlin.concurrent.Volatile var alive = true
        private set
    @kotlin.concurrent.Volatile private var lastRecvSec = currentTimeSeconds()
    @kotlin.concurrent.Volatile internal var lastUseSec = currentTimeSeconds()
        private set

    val serverCapabilities: List<String> get() = client.serverCapabilities
    val serverVersion: Long? get() = client.serverVersion
    val serverSwVersion: String? get() = client.serverSwVersion
    val serverName: String? get() = client.serverName
    /** M471: the rights from accessUpdate (arrives right after login and on every change). */
    val access: HtspClient.Access? get() = client.access

    // ---- async metadata model (channels, tags, recordings) ----
    private val channels = LinkedHashMap<Long, MutableMap<String, Any?>>()
    private val tags = LinkedHashMap<Long, MutableMap<String, Any?>>()
    private val dvr = LinkedHashMap<Long, MutableMap<String, Any?>>()
    private val syncDone = CompletableDeferred<Unit>()

    internal fun hasActiveSubscriptions(): Boolean = subs.isNotEmpty()

    internal fun start() {
        scope.launch(Dispatchers.Default) { readLoop() }
        scope.launch {
            // M408: keepalive — without seq, the server does not answer it
            while (isActive && alive) {
                delay(10_000)
                try { client.send("getDiskSpace", withSeq = false) }
                catch (e: Throwable) { if (e !is kotlinx.coroutines.CancellationException) die(e) }
            }
        }
        scope.launch {
            try {
                val args = HashMap<String, Any?>()
                args["epg"] = 0L
                // M511: the language preference for the metadata as well
                sk.tvhclient.shared.ClientIdent.lang2.takeIf { it.isNotBlank() }
                    ?.let { args["language"] = it }
                client.send("enableAsyncMetadata", args, withSeq = false)
            } catch (e: Throwable) { if (e !is kotlinx.coroutines.CancellationException) die(e) }
        }
    }

    private suspend fun readLoop() {
        try {
            while (alive) {
                val m = client.recv()
                lastRecvSec = currentTimeSeconds()
                val seq = (m["seq"] as? Long)?.toInt()
                if (seq != null) {
                    val d = lock.withLock { pending.remove(seq) }
                    d?.complete(m)
                    continue
                }
                val sid = (m["subscriptionId"] as? Long)?.toInt()
                if (sid != null) {
                    val sub = lock.withLock { subs[sid] }
                    // bounded channel: waits when the stream consumer is behind (back pressure)
                    if (sub != null) runCatching { sub.inbox.send(m) }
                    continue
                }
                applyMetadata(m)
            }
        } catch (e: Throwable) {
            die(e)
        }
    }

    private suspend fun applyMetadata(m: Map<String, Any?>) {
        fun upsert(map: LinkedHashMap<Long, MutableMap<String, Any?>>, key: String, merge: Boolean) {
            val id = m[key] as? Long ?: return
            val existing = map[id]
            if (merge && existing != null) existing.putAll(m) else map[id] = HashMap(m)
        }
        fun remove(map: LinkedHashMap<Long, MutableMap<String, Any?>>, key: String) {
            (m[key] as? Long)?.let { map.remove(it) }
        }
        lock.withLock {
            when (m["method"] as? String) {
                "channelAdd" -> upsert(channels, "channelId", merge = false)
                "channelUpdate" -> upsert(channels, "channelId", merge = true)   // only the changed fields
                "channelDelete" -> remove(channels, "channelId")
                "tagAdd" -> upsert(tags, "tagId", merge = false)
                "tagUpdate" -> upsert(tags, "tagId", merge = true)
                "tagDelete" -> remove(tags, "tagId")
                "dvrEntryAdd" -> upsert(dvr, "id", merge = false)
                "dvrEntryUpdate" -> upsert(dvr, "id", merge = true)
                "dvrEntryDelete" -> remove(dvr, "id")
                "accessUpdate" -> client.applyAccessUpdate(m)
                "initialSyncCompleted" -> syncDone.complete(Unit)
            }
        }
    }

    /**
     * A snapshot of the metadata in the shape the rest of the app knows (the channelAdd/tagAdd/
     * dvrEntryAdd maps). Waits for the initial sync on a new connection.
     */
    internal suspend fun metadata(timeoutMs: Long = 45_000): HtspClient.Metadata {
        touch()
        withTimeoutOrNull(timeoutMs) { syncDone.await() }
            ?: throw IllegalStateException("HTSP: metadata sync timeout")
        if (!alive) throw IllegalStateException("HTSP: connection closed")
        return lock.withLock {
            HtspClient.Metadata(
                channels = channels.values.map { HashMap(it) },
                tags = tags.values.map { HashMap(it) },
                events = emptyList(),
                dvr = dvr.values.map { HashMap(it) },
                syncDone = true,
                access = client.access
            )
        }
    }

    /** A request with a reply (getEvents, addDvrEntry...). Throws on a dead connection or a timeout. */
    internal suspend fun request(
        method: String,
        args: Map<String, Any?> = emptyMap(),
        timeoutMs: Long = 20_000
    ): Map<String, Any?> {
        touch()
        if (!alive) throw IllegalStateException("HTSP: connection closed")
        val d = CompletableDeferred<Map<String, Any?>>()
        // the seq is assigned inside send under the write lock; register it right after — the reply
        // cannot be dispatched before we register, because the reader takes the same [lock]
        val reg = lock.withLock {
            val s = client.send(method, args, withSeq = true)
            pending[s] = d
            s
        }
        val r = try {
            withTimeoutOrNull(timeoutMs) { d.await() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                lock.withLock { pending.remove(reg) }   // the caller left — no leftover waiter
            }
            throw e
        }
        if (r == null) {
            lock.withLock { pending.remove(reg) }
            // no reply and nothing at all has arrived for a while — the connection is most likely
            // dead (Wi-Fi <-> LTE, NAT timeout); drop it so that the next use reconnects. While a
            // stream is flowing the connection is fine and the server is only slow.
            if (currentTimeSeconds() - lastRecvSec >= 15) die(IllegalStateException("HTSP: no reply"))
            throw IllegalStateException("HTSP: no reply arrived for $method")
        }
        return r
    }

    /** A message without a reply (subscriptionSpeed / Skip...). */
    internal suspend fun sendNoReply(method: String, args: Map<String, Any?>) {
        touch()
        if (!alive) return
        try { client.send(method, args, withSeq = false) }
        catch (e: Throwable) { if (e !is kotlinx.coroutines.CancellationException) die(e) }
    }

    /**
     * Starts a live subscription. The caller runs [HtspSubscription.run]; it ends on subscriptionStop,
     * on a dead connection or by cancelling its coroutine (then it unsubscribes).
     */
    suspend fun subscribe(channelId: Long, timeshiftPeriodSec: Int = 0, profile: String? = null): HtspSubscription {
        touch()
        if (!alive) throw IllegalStateException("HTSP: connection closed")
        val sub = lock.withLock {
            val id = nextSubId++
            HtspSubscription(this, id).also { subs[id] = it }
        }
        val args = HashMap<String, Any?>()
        args["channelId"] = channelId
        args["subscriptionId"] = sub.subscriptionId.toLong()
        args["90khz"] = 1L
        args["normts"] = 1L
        if (timeshiftPeriodSec > 0) args["timeshiftPeriod"] = timeshiftPeriodSec.toLong()
        if (!profile.isNullOrBlank()) args["profile"] = profile
        // With seq: a refused subscribe (e.g. the connection limit is used up by another device)
        // comes back as a reply with noaccess/connlimit instead of a subscriptionStart.
        val r = try {
            request("subscribe", args)
        } catch (e: Throwable) {
            // cancelled or no reply: the subscribe may already be on its way to the server —
            // unsubscribe, otherwise the server would keep streaming into an inbox nobody reads
            endSubscription(sub, unsubscribe = true)
            throw e
        }
        if (((r["noaccess"] as? Long) ?: 0L) != 0L) {
            endSubscription(sub, unsubscribe = false)
            if (((r["connlimit"] as? Long) ?: 0L) != 0L) throw HtspConnLimitException()
            throw IllegalStateException("HTSP: subscription refused")
        }
        (r["error"] as? String)?.let {
            endSubscription(sub, unsubscribe = false)
            throw IllegalStateException("HTSP: $it")
        }
        return sub
    }

    /** Called by the subscription when it ends; never suspends (it runs in `finally`). */
    internal fun endSubscription(sub: HtspSubscription, unsubscribe: Boolean = true) {
        scope.launch {
            val removed = lock.withLock { subs.remove(sub.subscriptionId) }
            removed?.inbox?.close()
            if (unsubscribe && alive) {
                runCatching {
                    client.send("unsubscribe", mapOf("subscriptionId" to sub.subscriptionId.toLong()), withSeq = false)
                }
            }
            touch()
        }
    }

    private fun touch() { lastUseSec = currentTimeSeconds() }

    /** The connection is gone: fail everyone who waits, end the subscriptions, close the socket. */
    internal fun die(cause: Throwable) {
        if (!alive) return
        alive = false
        HtspSessions.forget(this)
        scope.launch {
            val (ps, ss) = lock.withLock {
                val p = pending.values.toList(); pending.clear()
                val s = subs.values.toList(); subs.clear()
                p to s
            }
            val err = IllegalStateException("HTSP: connection lost (${cause.message ?: cause::class.simpleName})")
            ps.forEach { it.completeExceptionally(err) }
            ss.forEach { it.inbox.close() }
            if (!syncDone.isCompleted) syncDone.completeExceptionally(err)
            runCatching { client.close() }
            scope.cancel()
        }
    }

    /** Closes the session deliberately (idle, server changed). */
    internal fun close() = die(IllegalStateException("closed"))

    companion object {
        /** No request and no stream for this long -> the connection is closed. */
        const val IDLE_CLOSE_SEC = 300L
    }
}

/**
 * M693: the registry of the shared sessions — one per server. [get] reuses a live session or
 * connects a new one (serialised, so parallel callers never open two connections).
 */
object HtspSessions {
    private val mutex = Mutex()
    private val sessions = HashMap<String, HtspSession>()
    private val janitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var janitor: Job? = null

    private fun sameTarget(a: TvhServer, b: TvhServer) =
        a.host == b.host && a.htspPort == b.htspPort && a.username == b.username && a.password == b.password

    suspend fun get(server: TvhServer): HtspSession = mutex.withLock {
        val existing = sessions[server.id]
        if (existing != null && existing.alive && sameTarget(existing.server, server)) return@withLock existing
        existing?.close()
        sessions.remove(server.id)
        val client = HtspData.connectWithRetry(server)
        val s = HtspSession(server, client)
        s.start()
        sessions[server.id] = s
        ensureJanitor()
        s
    }

    /** The live session for the server without connecting (null = none). */
    fun peek(serverId: String): HtspSession? = sessions[serverId]?.takeIf { it.alive }

    internal fun forget(s: HtspSession) {
        janitorScope.launch {
            mutex.withLock { if (sessions[s.server.id] === s) sessions.remove(s.server.id) }
        }
    }

    /** Closes the server's session (e.g. after the server was edited or removed). */
    fun close(serverId: String) {
        janitorScope.launch { mutex.withLock { sessions.remove(serverId) }?.close() }
    }

    private fun ensureJanitor() {
        if (janitor?.isActive == true) return
        janitor = janitorScope.launch {
            while (isActive) {
                delay(60_000)
                val now = currentTimeSeconds()
                val idle = mutex.withLock {
                    sessions.values.filter {
                        !it.hasActiveSubscriptions() && now - it.lastUseSec >= HtspSession.IDLE_CLOSE_SEC
                    }.onEach { sessions.remove(it.server.id) }
                }
                idle.forEach { it.close() }
            }
        }
    }
}
