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
    // M696: recording rules (autorecEntryAdd / timerecEntryAdd) — their id is a uuid string
    private val autorecs = LinkedHashMap<String, MutableMap<String, Any?>>()
    private val timerecs = LinkedHashMap<String, MutableMap<String, Any?>>()
    private val syncDone = CompletableDeferred<Unit>()

    /** M715: recordings open over HTSP (HtspDvrFile) — they keep the connection in use like a stream. */
    @kotlin.concurrent.Volatile private var openFiles = 0
    internal suspend fun fileOpened() { lock.withLock { openFiles++ } }
    internal suspend fun fileClosed() { lock.withLock { if (openFiles > 0) openFiles-- } }
    internal fun hasActiveSubscriptions(): Boolean = subs.isNotEmpty() || openFiles > 0

    internal fun start() {
        scope.launch(Dispatchers.Default) { readLoop() }
        scope.launch {
            // M408: keepalive. M715: the server answers it even without seq (htsp_reply); such a
            // reply has no seq and no subscriptionId, so the reader passes it to applyMetadata,
            // which ignores a message without a known method
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
                client.epgLanguage.takeIf { it.isNotBlank() }   // M715
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
                    // M715: the reader must never wait for a stream — the same connection carries the
                    // EPG/DVR replies, which stalled behind a full inbox (a paused or slow libVLC) and
                    // after 10 s the stream died ("TS queue did not drain"). See HtspSubscription.fromReader.
                    sub?.fromReader(m)
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
        // M696: the same for string-keyed entries (autorec / timerec rules)
        fun upsertS(map: LinkedHashMap<String, MutableMap<String, Any?>>, merge: Boolean) {
            val id = m["id"] as? String ?: return
            val existing = map[id]
            if (merge && existing != null) existing.putAll(m) else map[id] = HashMap(m)
        }
        fun removeS(map: LinkedHashMap<String, MutableMap<String, Any?>>) {
            (m["id"] as? String)?.let { map.remove(it) }
        }
        lock.withLock {
            when (m["method"] as? String) {
                "channelAdd" -> upsert(channels, "channelId", merge = false)
                // M710: a full channelUpdate (with channelName) replaces the entry — optional fields
                // (icon, minor number) are left out when not set, a merge kept the old values.
                // The now/next form (channelId + eventId/nextEventId only) is merged.
                "channelUpdate" -> upsert(channels, "channelId", merge = !m.containsKey("channelName"))
                "channelDelete" -> remove(channels, "channelId")
                "tagAdd" -> upsert(tags, "tagId", merge = false)
                "tagUpdate" -> upsert(tags, "tagId", merge = !m.containsKey("tagName"))   // M710
                "tagDelete" -> remove(tags, "tagId")
                "dvrEntryAdd" -> upsert(dvr, "id", merge = false)
                // M710: a full dvrEntryUpdate (has "enabled") replaces the entry; the stats-only form
                // (htsp_dvr_entry_update_stats) is merged, but the fields it recomputes are dropped
                // first — they are sent only when set, so a cleared error or a removed file stays stale.
                "dvrEntryUpdate" -> if (m.containsKey("enabled")) upsert(dvr, "id", merge = false) else {
                    (m["id"] as? Long)?.let { id ->
                        dvr[id]?.let { e -> for (k in DVR_VOLATILE) e.remove(k) }
                    }
                    upsert(dvr, "id", merge = true)
                }
                "dvrEntryDelete" -> remove(dvr, "id")
                "autorecEntryAdd" -> upsertS(autorecs, merge = false)     // M696
                "autorecEntryUpdate" -> upsertS(autorecs, merge = false)   // M710: always a full entry
                "autorecEntryDelete" -> removeS(autorecs)
                "timerecEntryAdd" -> upsertS(timerecs, merge = false)
                "timerecEntryUpdate" -> upsertS(timerecs, merge = false)   // M710: always a full entry
                "timerecEntryDelete" -> removeS(timerecs)
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

    /**
     * M696: the recording rules the server pushed (autorecEntryAdd / timerecEntryAdd). Waits for the
     * initial sync like [metadata]; the server sends every add/update/delete afterwards, so the list is
     * always current without another request. Also carries the channel names for the rows.
     */
    internal suspend fun rules(timeoutMs: Long = 45_000): Rules {
        touch()
        withTimeoutOrNull(timeoutMs) { syncDone.await() }
            ?: throw IllegalStateException("HTSP: metadata sync timeout")
        if (!alive) throw IllegalStateException("HTSP: connection closed")
        return lock.withLock {
            Rules(
                autorecs = autorecs.values.map { HashMap(it) },
                timerecs = timerecs.values.map { HashMap(it) },
                channelNames = channels.values.associate {
                    ((it["channelId"] as? Long) ?: -1L) to ((it["channelName"] as? String) ?: "")
                }
            )
        }
    }

    /** M715: the channel's current "now" event id from the live metadata (0/none = no pointer). */
    internal suspend fun channelNowEventId(channelId: Long): Long? {
        withTimeoutOrNull(15_000) { syncDone.await() }   // a fresh connection: no channels yet
        return channelNowEventId0(channelId)
    }

    private suspend fun channelNowEventId0(channelId: Long): Long? = lock.withLock {
        (channels[channelId]?.get("eventId") as? Long)?.takeIf { it > 0 }
    }

    /** M715: the HTSP numeric id of the recording with the hex [uuid] (/dvrfile form), from the live metadata. */
    internal suspend fun dvrIdForUuid(uuid: String): Long? {
        // a fresh connection has no recordings until the initial sync is done
        withTimeoutOrNull(15_000) { syncDone.await() }
        return dvrIdForUuid0(uuid)
    }

    private suspend fun dvrIdForUuid0(uuid: String): Long? = lock.withLock {
        // the server sends the uuid as "idStr" (htsp_build_dvrentry); "uuid" kept for older builds
        dvr.values.firstOrNull {
            (it["idStr"] as? String).equals(uuid, ignoreCase = true) || (it["uuid"] as? String).equals(uuid, ignoreCase = true)
        }?.get("id") as? Long
    }

    /** M715: the EPG language list of this connection. */
    internal val epgLanguage: String get() = client.epgLanguage

    internal class Rules(
        val autorecs: List<Map<String, Any?>>,
        val timerecs: List<Map<String, Any?>>,
        val channelNames: Map<Long, String>
    )

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

        /** M710: dvrEntry fields Tvheadend sends only when set (htsp_build_dvrentry). */
        private val DVR_VOLATILE = listOf("error", "subscriptionError", "streamErrors", "dataErrors", "dataSize", "duplicate")
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

    /** [ignoreBackoff]: M694 — playback started by the user connects even inside the connlimit backoff. */
    suspend fun get(server: TvhServer, ignoreBackoff: Boolean = false): HtspSession = mutex.withLock {
        val existing = sessions[server.id]
        if (existing != null && existing.alive && sameTarget(existing.server, server)) return@withLock existing
        existing?.close()
        sessions.remove(server.id)
        val client = HtspData.connectWithRetry(server, ignoreBackoff)
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
