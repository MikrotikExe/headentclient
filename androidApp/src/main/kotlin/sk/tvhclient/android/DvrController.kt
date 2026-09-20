package sk.tvhclient.android

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
 * M472: choosing the right DVR path and caching rights.
 *
 * The app can be connected to the server via HTSP or HTTP — recording must
 * work in both cases. This layer decides which implementation to use, and
 * holds on to the rights it has determined so that they are not checked on
 * every opening of a programme detail.
 */
object DvrController {

    private val accessCache = HashMap<String, DvrAccess>()

    private fun serviceFor(server: TvhServer): DvrService =
        if (server.connectionMode == "htsp") HtspDvrService(server) else HttpDvrService(server)

    /**
     * User rights; determined from the server the first time, then held.
     *
     * M480: ALL calls run on Dispatchers.IO with a time cap. The UI launches them
     * from LaunchedEffect, i.e. from the main thread — opening an HTSP connection
     * and closing it are blocking operations, and the app stopped responding after
     * them (ANR). If the server does not answer within the limit, we pretend the
     * rights are unknown — better than freezing.
     */
    suspend fun access(server: TvhServer): DvrAccess {
        accessCache[server.id]?.let { return it }
        val a = withContext(Dispatchers.IO) {
            withTimeoutOrNull(8_000L) {
                runCatching { serviceFor(server).access() }.getOrNull()
            }
        }
        // M519: do NOT store failure permanently.
        //
        // The rights cache has no expiry, so when the first check failed or
        // did not make the 8 s limit, UNKNOWN was stored and the app believed
        // until a restart that recording was impossible — so the button showed up
        // once and not another time, depending on whether the first call after
        // start succeeded. We therefore do not remember failure and re-check the rights next time.
        if (a == null || !a.known) return DvrAccess.UNKNOWN
        accessCache[server.id] = a
        return a
    }

    // ---- M474: already scheduled recordings (so they are not offered twice) ----
    private class Sched(val ts: Long, val list: List<sk.tvhclient.shared.model.DvrEntry>)
    private val schedCache = HashMap<String, Sched>()
    private const val SCHED_TTL_MS = 60_000L

    /**
     * M484: local overlay over the list of recordings.
     *
     * HTSP metadata has its own 120 s cache (HtspData.metadata), so right after scheduling
     * or cancelling a recording the server still returns the old list and the UI would not
     * switch for two minutes. Discarding the whole metadata cache is not an option — the next
     * opening of the grid would pull the entire EPG again, which is expensive on weak boxes.
     * So we keep locally what we have just added and what we have cancelled, and apply that
     * to the list from the server. The overlay discards itself once the server confirms it.
     */
    private class Pending {
        val added = ArrayList<sk.tvhclient.shared.model.DvrEntry>()
        val removed = HashSet<String>()
    }
    private val pendingOps = HashMap<String, Pending>()

    /** The same programme? Same channel and time overlap — a DVR entry has no eventId. */
    private fun sameSlot(
        a: sk.tvhclient.shared.model.DvrEntry, b: sk.tvhclient.shared.model.DvrEntry
    ): Boolean = a.channelUuid.isNotBlank() && a.channelUuid == b.channelUuid &&
        a.start < b.stop && b.start < a.stop

    /**
     * What the server has already confirmed no longer needs overlaying.
     *
     * An empty list is ignored — we cannot tell "no recordings" from a failed
     * load (a timeout also returns empty), and discarding the overlay because
     * of a connection drop would return the UI to the old state.
     */
    private fun reconcile(serverId: String, fresh: List<sk.tvhclient.shared.model.DvrEntry>) {
        if (fresh.isEmpty()) return
        val p = pendingOps[serverId] ?: return
        p.added.removeAll { a -> fresh.any { sameSlot(it, a) } }
        p.removed.removeAll { id -> fresh.none { it.commandId == id } }
        if (p.added.isEmpty() && p.removed.isEmpty()) pendingOps.remove(serverId)
    }

    /** The list from the server + our changes that it has not managed to reflect yet. */
    private fun overlay(
        serverId: String, list: List<sk.tvhclient.shared.model.DvrEntry>
    ): List<sk.tvhclient.shared.model.DvrEntry> {
        val p = pendingOps[serverId] ?: return list
        val kept = list.filterNot { p.removed.contains(it.commandId) }
        if (p.added.isEmpty()) return kept
        return kept + p.added.filterNot { a -> kept.any { sameSlot(it, a) } }
    }

    /**
     * M608: running recordings from the server + the ones we have just scheduled and that are
     * already running (the programme has already started). In HTSP mode, metadata is not
     * refreshed during playback (M595 — a second connection would be refused by a server with
     * a limit of 1), so the red dot on a just-scheduled recording would otherwise only appear
     * after playback ended. The list from the server takes precedence, the overlay only adds to it.
     */
    fun overlayInProgress(
        serverId: String, list: List<sk.tvhclient.shared.model.DvrEntry>
    ): List<sk.tvhclient.shared.model.DvrEntry> {
        val p = pendingOps[serverId] ?: return list
        val nowSec = System.currentTimeMillis() / 1000
        val kept = list.filterNot { p.removed.contains(it.commandId) }
        val running = p.added.filter { it.start <= nowSec && nowSec < it.stop }
        if (running.isEmpty()) return kept
        return kept + running.filterNot { a -> kept.any { sameSlot(it, a) } }
    }

    /**
     * Scheduled/running recordings. Short cache — the list is used on every opening
     * of a programme detail and there is no point in loading the server because of that.
     */
    private suspend fun scheduled(server: TvhServer): List<sk.tvhclient.shared.model.DvrEntry> {
        val now = System.currentTimeMillis()
        schedCache[server.id]?.let {
            if (now - it.ts < SCHED_TTL_MS) return overlay(server.id, it.list)
        }
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
        }
        // M518: do NOT store failure in the cache.
        //
        // `null` = the 8 s limit expired or the call failed. Until now an empty
        // list was stored in such a case and the app believed for a whole minute
        // that no recordings existed — so the button showed up once as "Cancel" and
        // another time as "Record", depending on whether the load happened to succeed.
        // On failure we rather return the last known state and try again next time.
        if (list == null) return overlay(server.id, schedCache[server.id]?.list ?: emptyList())
        schedCache[server.id] = Sched(now, list)
        reconcile(server.id, list)          // M484
        return overlay(server.id, list)
    }

    /**
     * M475: the scheduled recording for a given programme (null = none). Returns the whole
     * entry so that it can be cancelled directly — that needs its id/uuid.
     */
    suspend fun scheduledFor(
        server: TvhServer, channelUuid: String, start: Long, stop: Long
    ): sk.tvhclient.shared.model.DvrEntry? = scheduled(server).firstOrNull { r ->
        val sameChannel = r.channelUuid.isNotBlank() && r.channelUuid == channelUuid
        sameChannel && r.start < stop && start < r.stop
    }

    /** After scheduling a recording, invalidate the list so it shows in the UI immediately. */
    fun invalidateScheduled(serverId: String? = null) {
        if (serverId == null) schedCache.clear() else schedCache.remove(serverId)
    }

    /**
     * M484: an optional description of the programme (channel and time) — after successful
     * scheduling the entry is reflected in the list immediately, so that the button switches
     * to "Cancel" without waiting for the metadata cache to refresh.
     */
    suspend fun recordEvent(
        server: TvhServer,
        eventId: Long,
        channelUuid: String = "",
        start: Long = 0,
        stop: Long = 0,
        title: String = "",
        profile: String? = null   // M606: the profile selected in the dialog (takes precedence)
    ): DvrResult {
        // M486: record into the profile selected in the server settings; empty
        // = let the server decide (the account's default)
        val cfg = profile?.takeIf { it.isNotBlank() } ?: server.dvrConfig.ifBlank { null }
        val r = ioResult { serviceFor(server).recordEvent(eventId, cfg) }
        if (r.success) {
            invalidateScheduled(server.id)   // M474
            if (channelUuid.isNotBlank() && stop > start) {
                val id = r.id.orEmpty()
                // M485: a programme that is already running starts recording immediately —
                // the state must match, otherwise the detail would report "Scheduled"
                val nowSec = System.currentTimeMillis() / 1000
                val live = nowSec in start until stop
                pendingOps.getOrPut(server.id) { Pending() }.added.add(
                    sk.tvhclient.shared.model.DvrEntry(
                        uuid = id, dvrId = id, dispTitle = title,
                        channelUuid = channelUuid, start = start, stop = stop,
                        status = if (live) "recording" else "scheduled"
                    )
                )
            }
        }
        return r
    }

    /**
     * M484: an already scheduled recording of the same programme (even on another channel).
     *
     * Tvheadend evaluates the new entry as a duplicate, deletes it and returns over HTSP only
     * a terse "Could not add dvrEntry" — so we look up the specific entry by title ourselves,
     * to be able to tell the user where the recording already is.
     */
    suspend fun duplicateOf(
        server: TvhServer, title: String
    ): sk.tvhclient.shared.model.DvrEntry? {
        val key = title.trim().lowercase()
        if (key.isEmpty()) return null
        return scheduled(server).firstOrNull { it.title.trim().lowercase() == key }
    }

    /** M480: the operation on the IO thread with a time cap; timeout = a readable error. */
    private suspend fun ioResult(block: suspend () -> DvrResult): DvrResult =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(15_000L) {
                runCatching { block() }.getOrElse { DvrResult.fail(it.message) }
            }
        // M491: DvrController is an object without a context, so it cannot translate the message.
        // It returns a timeout flag and the UI fills in the text.
        } ?: DvrResult.fail(null, timeout = true)

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

    /**
     * M483: cancelling/stopping and deleting by the whole entry.
     *
     * The caller has no way of knowing whether the server runs over HTSP (numeric id) or HTTP
     * (hex uuid) — `commandId` returns the right one. For finished HTSP recordings
     * `uuid` is hex (because of /dvrfile), so deleting via `uuid` would always fail.
     */
    suspend fun cancel(server: TvhServer, entry: sk.tvhclient.shared.model.DvrEntry): DvrResult =
        cancel(server, entry.commandId).also { if (it.success) forgetEntry(server.id, entry) }

    suspend fun delete(server: TvhServer, entry: sk.tvhclient.shared.model.DvrEntry): DvrResult =
        delete(server, entry.commandId).also { if (it.success) forgetEntry(server.id, entry) }

    /** M484: hide a cancelled/deleted entry until the server stops sending it. */
    private fun forgetEntry(serverId: String, entry: sk.tvhclient.shared.model.DvrEntry) {
        val p = pendingOps.getOrPut(serverId) { Pending() }
        p.added.removeAll { sameSlot(it, entry) }
        p.removed.add(entry.commandId)
    }
}
