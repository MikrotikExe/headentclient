package sk.tvhclient.shared.epg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import sk.tvhclient.shared.currentTimeSeconds
import sk.tvhclient.shared.api.TvhApi
import sk.tvhclient.shared.api.TvhHttpException
import sk.tvhclient.shared.model.TvhServer

/**
 * M720: keeps the HTTP server's [LiveEpgStore] current.
 *
 * The window (now .. now + [LiveEpg.WINDOW_SEC]) is loaded with one paged grid query, then the
 * server's comet mailbox is polled (`/comet/poll`, a 10 s long poll — what the web interface
 * does) and every `epg` notification (created / updated / deleted event ids, notify.c) is applied:
 * the changed events are loaded by id, the deleted ones dropped. The window is reloaded as a whole
 * every [FULL_RELOAD_SEC] anyway (the comet carries ids only, so a created event is loaded and the
 * store keeps it when it falls into the window).
 *
 * Accounts without the "Web interface" right get 401/403 on the comet: then the window is simply
 * reloaded every [POLL_RELOAD_SEC] (the store is "fresh" for 15 minutes after a load) — still
 * current for the channel list and the player, just not to the second.
 *
 * One feed per server, started lazily by the first reader ([ensure]) and stopped after
 * [IDLE_STOP_SEC] without a reader (the app in the background): the data stays in the store.
 */
object HttpEpgFeed {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    @Volatile private var jobs: Map<String, Job> = emptyMap()

    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* spin */ }
        try { return block() } finally { lock.unlock() }
    }

    /** Starts the feed for [server] if it is not running; a running one is only touched. */
    fun ensure(server: TvhServer) {
        val store = LiveEpg.store(server.id)
        store.touch()
        locked {
            val j = jobs[server.id]
            if (j != null && j.isActive) return
            jobs = jobs + (server.id to scope.launch { run(server, store) })
        }
    }

    /** Stops the server's feed (server edited / removed). */
    fun stop(serverId: String) {
        locked { jobs[serverId]?.cancel(); jobs = jobs - serverId }
    }

    private suspend fun run(server: TvhServer, store: LiveEpgStore) {
        val api = TvhApi(server, retryAttempts = 2)
        var boxid: String? = null
        var cometOk = true
        var lastFull = 0L
        var failures = 0
        val me = kotlin.coroutines.coroutineContext[Job]
        try {
            while (kotlin.coroutines.coroutineContext.isActive) {
                val now = currentTimeSeconds()
                if (now - store.lastUseSec > IDLE_STOP_SEC) break
                val reloadEvery = if (cometOk) FULL_RELOAD_SEC else POLL_RELOAD_SEC
                if (lastFull == 0L || now - lastFull >= reloadEvery) {
                    try {
                        val list = api.epgWindow(now + LiveEpg.WINDOW_SEC)
                        store.replaceAll(list, now)
                        store.markSynced(alive = cometOk, nowSec = now)
                        lastFull = now
                        failures = 0
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failures++
                        store.markFeedDead()
                        delay(minOf(60_000L, 5_000L * failures))
                        continue
                    }
                }
                if (cometOk) {
                    try {
                        val reply = api.cometPoll(boxid, immediate = boxid == null)
                        if (reply.boxid != null) boxid = reply.boxid
                        apply(reply.messages, api, store)
                        failures = 0
                    } catch (e: TvhHttpException) {
                        when (e.httpCode) {
                            401, 403, 404 -> {            // no "Web interface" right / no comet: polling only
                                cometOk = false
                                store.markSynced(alive = false, nowSec = currentTimeSeconds())
                            }
                            400 -> boxid = null           // the mailbox expired (server restart) -> a new one
                            else -> { failures++; delay(minOf(60_000L, 5_000L * failures)) }
                        }
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        failures++
                        if (failures >= 3) store.markFeedDead()
                        delay(minOf(60_000L, 5_000L * failures))
                    }
                } else {
                    delay(POLL_IDLE_MS)
                }
            }
        } finally {
            store.markFeedDead()
            runCatching { api.close() }
            locked { if (jobs[server.id] === me) jobs = jobs - server.id }
        }
    }

    /** The `epg` notifications: {"notificationClass":"epg","create":[ids],"update":[ids],"delete":[ids]}. */
    private suspend fun apply(messages: List<JsonObject>, api: TvhApi, store: LiveEpgStore) {
        if (messages.isEmpty()) return
        val changed = HashSet<Long>()
        val deleted = HashSet<Long>()
        for (m in messages) {
            if ((m["notificationClass"] as? JsonPrimitive)?.content != "epg") continue
            ids(m["create"]).let { changed.addAll(it) }
            ids(m["update"]).let { changed.addAll(it) }
            ids(m["delete"]).let { deleted.addAll(it) }
        }
        changed.removeAll(deleted)
        if (deleted.isNotEmpty()) store.delete(deleted)
        if (changed.isNotEmpty()) {
            // only what we hold or what starts inside the window matters; the store drops the rest
            val list = api.epgLoad(changed.take(MAX_LOAD_PER_POLL))
            if (list.isNotEmpty()) store.upsert(list)
        }
    }

    private fun ids(el: kotlinx.serialization.json.JsonElement?): List<Long> =
        (el as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toLongOrNull() } ?: emptyList()

    /** With the comet the window is reloaded this often (a safety net against a missed message). */
    const val FULL_RELOAD_SEC = 30 * 60L
    /** Without the comet (no "Web interface" right) the window is reloaded this often. */
    const val POLL_RELOAD_SEC = 10 * 60L
    const val POLL_IDLE_MS = 30_000L
    /** No reader for this long -> the feed stops (the data stays). */
    const val IDLE_STOP_SEC = 300L
    /** An EIT storm can list thousands of ids; one poll loads at most this many (the reload covers the rest). */
    const val MAX_LOAD_PER_POLL = 300
}
