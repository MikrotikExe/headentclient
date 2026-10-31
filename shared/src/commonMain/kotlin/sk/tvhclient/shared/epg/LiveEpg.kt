package sk.tvhclient.shared.epg

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.Volatile
import sk.tvhclient.shared.currentTimeSeconds
import sk.tvhclient.shared.model.EpgEvent

/**
 * M720: the live EPG window — the guide kept current by the server, the way Kodi (pvr.hts) does it.
 *
 * Until now every screen asked for now/next on its own (hundreds of getEvents over HTSP, the
 * channel dump over HTTP), only while it was open, and a programme that ended while the player
 * ran stayed on screen until the next round. Now there is ONE store per server with the programmes
 * of every channel from now until now + [LiveEpg.WINDOW_SEC]:
 *  - HTSP: `enableAsyncMetadata` with `epg=1` and `epgMaxTime` — the server pushes `eventAdd` /
 *    `eventUpdate` / `eventDelete` for the window and slides it forward every 30 s by itself
 *    (htsp_epg_window_cb). Requested only after the channels have arrived, so the channel list is
 *    not held up by the EPG (HtspSession).
 *  - HTTP: the window is loaded with one paged `api/epg/events/grid` query and then kept current
 *    through the server's comet mailbox (`/comet/poll`, the same push the web interface uses:
 *    class `epg` with the ids of created / updated / deleted events). Without the "Web interface"
 *    right the window is simply reloaded every few minutes (HttpEpgFeed).
 *
 * Readers never block: [snapshot] is an immutable map replaced as a whole, [revision] ticks when it
 * changes (Compose / ViewModels collect it). The window is short on purpose — bounded memory on a
 * weak box (hundreds of channels x a handful of programmes); the daily guide keeps loading the rest
 * on demand exactly as before.
 */
class LiveEpgStore internal constructor(val serverId: String) {
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // eventId -> event; channelUuid -> ids; channelUuid -> the server's "now" pointer (HTSP)
    private val events = HashMap<Long, EpgEvent>()
    private val byChannel = HashMap<String, HashSet<Long>>()
    private val nowPointer = HashMap<String, Long>()

    /** channelUuid -> programmes in the window sorted by start (immutable, replaced as a whole). */
    @Volatile var snapshot: Map<String, List<EpgEvent>> = emptyMap()
        private set
    private val _revision = MutableStateFlow(0L)
    /** Ticks on every published change — collect it to refresh a screen. */
    val revision: StateFlow<Long> get() = _revision
    private val _syncedAt = MutableStateFlow(0L)
    /** Epoch seconds of the last complete window (0 = never). */
    val syncedAt: Long get() = _syncedAt.value
    /** A live feed (HTSP push / comet) is keeping the window current right now. */
    @Volatile var feedAlive = false
        private set
    /** When a reader last used the store (HttpEpgFeed stops after a long idle). M731 (audit R8): only
     *  the entry points touch it (Tvh.liveEpg / HttpEpgFeed.ensure, called from the foreground), not
     *  every read — a player in the background kept the comet feed alive for ever. */
    @Volatile var lastUseSec = currentTimeSeconds()
        private set
    /** M731 (audit B6): HTTP loads a FIXED window (load time + WINDOW_SEC) that does not slide until
     *  the next reload; 0 = the server slides it (HTSP). [windowEnd] never claims more than this. */
    @Volatile private var loadedUntil = 0L
    /** M731 (audit R10): the ids held right now (replaced with every publish) — the comet feed loads
     *  updates only for these. */
    @Volatile var knownIds: Set<Long> = emptySet()
        private set

    private var dirty = false
    /** M746 (audit R5): ids received during the current HTSP window burst (null = no burst running). */
    private var burstSeen: HashSet<Long>? = null
    private var flushJob: Job? = null
    private var tickJob: Job? = null

    /** Fresh = a complete window exists and either a feed is alive or it is recent (polling mode). */
    fun isFresh(nowSec: Long = currentTimeSeconds()): Boolean {
        val s = _syncedAt.value
        return s > 0 && (feedAlive || nowSec - s < POLL_FRESH_SEC)
    }

    /** Waits until a complete window has arrived; false on timeout. M731 (audit R9): after a lost
     *  feed it waits for the NEXT sync (the old syncedAt is no longer fresh) instead of returning at
     *  once — otherwise a reconnect sent a full getEvents round behind the arriving burst. */
    suspend fun awaitFresh(timeoutMs: Long): Boolean {
        if (isFresh()) return true
        val before = _syncedAt.value
        withTimeoutOrNull(timeoutMs) { _syncedAt.first { it > 0 && it != before } }
        return isFresh()
    }

    fun touch() { lastUseSec = currentTimeSeconds() }

    // ---- writers (the feeds) ----

    /** Adds or replaces events; events outside the window (far future, long over) are ignored. */
    suspend fun upsert(list: List<EpgEvent>, nowSec: Long = currentTimeSeconds()) {
        if (list.isEmpty()) return
        mutex.withLock {
            val maxStart = nowSec + LiveEpg.WINDOW_SEC + WINDOW_MARGIN_SEC
            for (e in list) {
                val id = e.eventId ?: continue
                val ch = e.channelUuid ?: continue
                // outside the window: far future, or over (unless it is the channel's "now" pointer —
                // an overrunning programme the server still holds as running, M715)
                if (e.start > maxStart) continue
                if (e.stop < nowSec - PAST_KEEP_SEC && !(nowPointer[ch] == id && e.stop >= nowSec - EpgEvent.MAX_OVERRUN_SEC)) continue
                burstSeen?.add(id)
                val old = events.put(id, e)
                if (old != null && old.channelUuid != ch) byChannel[old.channelUuid]?.remove(id)
                byChannel.getOrPut(ch) { HashSet() }.add(id)
                dirty = true
            }
        }
        scheduleFlush()
    }

    suspend fun delete(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        mutex.withLock {
            for (id in ids) {
                val old = events.remove(id) ?: continue
                old.channelUuid?.let { byChannel[it]?.remove(id) }
                dirty = true
            }
        }
        scheduleFlush()
    }

    /**
     * M746 (audit R5): a new HTSP connection asks for the window again and the server sends every
     * event in it. Events deleted on the server while we were disconnected never get an eventDelete,
     * so they stayed in the window for up to its whole length (6 h). [beginBurst] starts noting the
     * ids that arrive; [endBurst] with complete=true (the sentinel reply came) drops the still-upcoming
     * events of the window that the burst did not contain. Programmes already over and the overrunning
     * "now" ones are not touched, and a suspicious result (nothing received, or most of the window
     * missing) prunes nothing.
     */
    suspend fun beginBurst() { mutex.withLock { burstSeen = HashSet() } }

    suspend fun endBurst(complete: Boolean, windowEnd: Long, nowSec: Long = currentTimeSeconds()) {
        var pruned = false
        mutex.withLock {
            val seen = burstSeen
            burstSeen = null
            if (!complete || seen == null || seen.isEmpty()) return@withLock
            val stale = events.values.filter { e ->
                val id = e.eventId
                id != null && id !in seen && e.stop > nowSec && e.start < windowEnd &&
                    e.channelUuid?.let { nowPointer[it] } != id
            }
            if (stale.isEmpty() || stale.size * 5 > events.size) return@withLock
            for (e in stale) {
                val id = e.eventId ?: continue
                events.remove(id)
                e.channelUuid?.let { byChannel[it]?.remove(id) }
            }
            dirty = true
            pruned = true
        }
        if (pruned) scheduleFlush()
    }

    /** The whole window at once (HTTP load): everything not in [list] is dropped. */
    suspend fun replaceAll(list: List<EpgEvent>, nowSec: Long = currentTimeSeconds()) {
        mutex.withLock {
            events.clear(); byChannel.clear()
            dirty = true
        }
        loadedUntil = nowSec + LiveEpg.WINDOW_SEC   // M731 (B6): a fixed window until the next reload
        upsert(list, nowSec)
    }

    /** HTSP: the channel's current event id from channelAdd/channelUpdate (0 / null = cleared). */
    suspend fun setNowPointer(channelUuid: String, eventId: Long?) {
        mutex.withLock {
            val old = nowPointer[channelUuid]
            if (eventId == null || eventId <= 0) { if (old != null) { nowPointer.remove(channelUuid); dirty = true } }
            else if (old != eventId) { nowPointer[channelUuid] = eventId; dirty = true }
        }
        scheduleFlush()
    }

    /** The window is complete; [alive] = a push feed keeps it current from now on. */
    suspend fun markSynced(alive: Boolean, nowSec: Long = currentTimeSeconds()) {
        feedAlive = alive
        publish(nowSec)
        _syncedAt.value = nowSec
        startTick()
    }

    /** M731 (audit R11): the `running` flag of an overrunning programme (M715) was baked into the
     *  snapshot only when something changed; on a quiet server it was never set, or stayed set
     *  past the 15-minute limit. A minute tick rebuilds the snapshot while a server pointer exists. */
    private fun startTick() {
        if (tickJob?.isActive == true) return
        tickJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                if (_syncedAt.value == 0L) continue
                val hasPointer = mutex.withLock { nowPointer.isNotEmpty() }
                if (hasPointer) publish(currentTimeSeconds(), force = true)
                else publish(currentTimeSeconds())   // the trim alone (events that ended)
            }
        }
    }

    /** The feed is gone (connection lost / idle close) — the data stays, only freshness changes. */
    fun markFeedDead() { feedAlive = false }

    /** Everything away (server changed / data reset). */
    suspend fun clear() {
        mutex.withLock { events.clear(); byChannel.clear(); nowPointer.clear(); dirty = true }
        feedAlive = false
        loadedUntil = 0L
        _syncedAt.value = 0L
        publish(currentTimeSeconds())
    }

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        // a burst (the initial window, a wave of eventUpdate) is published once, not per message
        flushJob = scope.launch {
            delay(FLUSH_DELAY_MS)
            publish(currentTimeSeconds())
        }
    }

    /** Trims the window and publishes a new [snapshot] (if anything changed, or [force]). */
    suspend fun publish(nowSec: Long, force: Boolean = false) {
        mutex.withLock {
            if (force) dirty = true
            // trim: long over (except the channel's pointer — an overrunning programme), far future
            val maxStart = nowSec + LiveEpg.WINDOW_SEC + WINDOW_MARGIN_SEC
            val it = events.values.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val pointer = e.channelUuid?.let { c -> nowPointer[c] } == e.eventId
                val over = e.stop < nowSec - PAST_KEEP_SEC && !(pointer && e.stop >= nowSec - EpgEvent.MAX_OVERRUN_SEC)
                if (over || e.start > maxStart) {
                    it.remove()
                    e.channelUuid?.let { c -> byChannel[c]?.remove(e.eventId) }
                    dirty = true
                }
            }
            if (dirty) {
                dirty = false
                val m = HashMap<String, List<EpgEvent>>(byChannel.size * 2)
                for ((ch, ids) in byChannel) {
                    if (ids.isEmpty()) continue
                    val l = ArrayList<EpgEvent>(ids.size)
                    for (id in ids) events[id]?.let { ev -> l.add(markRunning(ev, ch, nowSec)) }
                    l.sortBy { it.start }
                    m[ch] = l
                }
                // M731 (audit R11): published inside the lock — two publishers (the flush and
                // markSynced) could otherwise install the older snapshot last
                knownIds = HashSet(events.keys)
                snapshot = m
                _revision.value = _revision.value + 1
            }
        }
    }

    /** M715 semantics: the server's pointer stays on a programme past its end -> running. */
    private fun markRunning(ev: EpgEvent, ch: String, nowSec: Long): EpgEvent {
        if (nowPointer[ch] != ev.eventId) return ev
        val over = nowSec - ev.stop
        return if (over >= EpgEvent.OVERRUN_MIN_SEC && over < EpgEvent.MAX_OVERRUN_SEC) ev.copy(running = true, runningAt = nowSec) else ev
    }

    // ---- readers (never block) ----

    /** The programme on air on [channelUuid] (time rule + the running pointer), or null. */
    fun currentFor(channelUuid: String, nowSec: Long = currentTimeSeconds()): EpgEvent? {
        val l = snapshot[channelUuid] ?: return null
        return l.firstOrNull { it.running && it.isCurrentAt(nowSec) } ?: l.firstOrNull { it.isCurrentAt(nowSec) }
    }

    /** The current programme followed by the next ones (at most [max]); empty = nothing known. */
    fun nowNext(channelUuid: String, nowSec: Long = currentTimeSeconds(), max: Int = 6): List<EpgEvent> {
        val l = snapshot[channelUuid] ?: return emptyList()
        val cur = currentFor(channelUuid, nowSec)
        val from = cur?.stop ?: nowSec
        val out = ArrayList<EpgEvent>(max)
        if (cur != null) out.add(cur)
        for (e in l) {
            if (out.size >= max) break
            if (e !== cur && e.start >= from && e.eventId != cur?.eventId) out.add(e)
        }
        return out
    }

    /** channelUuid -> current + following programmes for every channel in the window (the now/next map). */
    fun upcomingMap(nowSec: Long = currentTimeSeconds(), max: Int = 6): Map<String, List<EpgEvent>> {
        val snap = snapshot
        val out = HashMap<String, List<EpgEvent>>(snap.size * 2)
        for (ch in snap.keys) {
            val l = nowNext(ch, nowSec, max)
            if (l.isNotEmpty()) out[ch] = l
        }
        return out
    }

    /** channelUuid -> the programme on air (what api/epg/events/grid?mode=now returns). */
    fun nowMap(nowSec: Long = currentTimeSeconds()): Map<String, EpgEvent> {
        val out = HashMap<String, EpgEvent>()
        for (ch in snapshot.keys) currentFor(ch, nowSec)?.let { out[ch] = it }
        return out
    }

    /** The end of the window: as the server slides it (HTSP), or where the last HTTP load ended
     *  (M731, audit B6 — between reloads the fixed HTTP window is up to 30 min shorter than
     *  now + 6 h, and mergeWindow cut the cached guide there, leaving a hole). */
    fun windowEnd(nowSec: Long = currentTimeSeconds()): Long {
        val slide = nowSec + LiveEpg.WINDOW_SEC
        val fixed = loadedUntil
        return if (fixed > 0L && fixed < slide) fixed else slide
    }

    companion object {
        /** How long a complete window counts as fresh without a live feed (HTTP without comet). */
        const val POLL_FRESH_SEC = 15 * 60L
        /** Events that ended more than this ago are dropped (small margin for clock skew). */
        const val PAST_KEEP_SEC = 120L
        /** Creates beyond the window may arrive a little early (the server's 30 s slide). */
        const val WINDOW_MARGIN_SEC = 600L
        const val FLUSH_DELAY_MS = 400L
        /** M731: the running-flag / trim tick. */
        const val TICK_MS = 60_000L
    }
}

/** The registry of the live stores — one per server id. */
object LiveEpg {
    /** The window kept current: enough for now/next and the channel list, small on a weak box. */
    const val WINDOW_SEC = 6 * 3600L

    private val lock = Mutex()
    // readers are synchronous (Compose): the map is replaced as a whole, so a lookup needs no lock
    @Volatile private var view: Map<String, LiveEpgStore> = emptyMap()

    /** Non-suspending critical section (creation is a few microseconds; tryLock spins instead of suspending). */
    private inline fun <T> locked(block: () -> T): T {
        while (!lock.tryLock()) { /* spin */ }
        try { return block() } finally { lock.unlock() }
    }

    fun store(serverId: String): LiveEpgStore {
        view[serverId]?.let { return it }
        return locked {
            view[serverId] ?: LiveEpgStore(serverId).also { view = view + (serverId to it) }
        }
    }

    /** Drops the server's window (data reset / server edited or removed). M731 (audit B5): the store
     *  object stays registered — ChannelsViewModel, PlayerEpgStore and the HTSP session hold it and
     *  collect its revision; a NEW object for the same id left them listening to an orphan (now/next
     *  froze until the ViewModel restarted, the HTSP session filled a store nobody read). */
    suspend fun forget(serverId: String) {
        view[serverId]?.clear()
    }

    /**
     * Merges a fresh window into a longer per-channel list (e.g. the daily guide a screen cached):
     * the window's events replace the same time span, programmes beyond the window are kept.
     */
    fun mergeWindow(existing: List<EpgEvent>?, window: List<EpgEvent>, windowEnd: Long): List<EpgEvent> {
        if (existing.isNullOrEmpty()) return window
        if (window.isEmpty()) return existing
        val lastStop = window.maxOf { it.stop }
        val cut = maxOf(windowEnd, lastStop)
        val ids = window.mapNotNullTo(HashSet()) { it.eventId }
        val tail = existing.filter { it.start >= cut && it.eventId !in ids }
        return if (tail.isEmpty()) window else (window + tail).sortedBy { it.start }
    }
}
