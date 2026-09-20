package sk.tvhclient.shared.htsp

import sk.tvhclient.shared.model.Channel
import sk.tvhclient.shared.model.ChannelTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.currentTimeSeconds

/**
 * HTSP data source: connects over 9982, downloads the metadata (enableAsyncMetadata)
 * and maps the HTSP fields onto the app's models (the same ones as from HTTP /api). The mapping is
 * taken over from the plugin (_htsp_api.py). A simple TTL cache per server, so that
 * it does not connect for every card.
 *
 * Streaming and picons stay on HTTP (HTSP only handles data here).
 */
object HtspData {
    /** M550-fix: the last error of a per-channel getEvents (otherwise it is silently skipped) —
     *  the app can ask for it and write it into the diagnostic log. */
    var lastEpgError: String? = null
    /** M551-fix: how many channels failed in the last epgUpcomingMap (getEvents threw an exception). */
    @kotlin.concurrent.Volatile var lastEpgFailed: Int = 0
    /** M551-fix2: channels for which getEvents returned no current/next event. */
    @kotlin.concurrent.Volatile var lastEpgEmpty: List<Long> = emptyList()
    /**
     * M595: the number of RUNNING HTSP streams (live channel / timeshift). Tvheadend has
     * a per-user connection limit (usually 1); when the app opens a SECOND connection
     * during playback (now/next, daily programme, archive), the server refuses it —
     * "multiple connections are not allowed for user X (limit 1…)" — and in the worse
     * case it drops the one that is playing. The user sees it as the channel
     * or the recording stopping after a while. So while a stream is running, supplementary HTSP
     * queries are skipped and whatever we already have in the cache is used.
     */
    @kotlin.concurrent.Volatile private var streamCount: Int = 0
    val streaming: Boolean get() = streamCount > 0
    /** M603: the last epgUpcomingMap was SKIPPED because a stream was running (it only returned the cache).
     *  The caller must not take this as an incomplete EPG — do not log, do not retry, nothing failed. */
    @kotlin.concurrent.Volatile var lastEpgSkipped: Boolean = false
    fun streamStarted() { streamCount++ }
    fun streamStopped() { if (streamCount > 0) streamCount-- }

    private fun noteEpgError(e: Throwable) {
        lastEpgError = (e::class.simpleName ?: "Throwable") + ": " + (e.message ?: "")
    }

    /** M471: rights from the last HTSP connection (accessUpdate), translated into
     *  the common shape for the UI. */

    /**
     * M476: the list of stream profiles over HTSP (`getProfiles`, HTSPv16+).
     * Until now the profiles were read only over the HTTP API — on a pure HTSP connection
     * (only port 9982 open) the list was thus not available at all.
     */
    suspend fun streamProfiles(server: TvhServer): List<String> = runCatching {
        val c = connectWithRetry(server)   // M621
        try {
            val r = c.recvReply(c.send("getProfiles"))
            @Suppress("UNCHECKED_CAST")
            val list = (r["profiles"] as? List<Any?>) ?: emptyList()
            list.mapNotNull { p ->
                val m = p as? Map<String, Any?> ?: return@mapNotNull null
                (m["name"] as? String)?.takeIf { it.isNotBlank() }
            }
        } finally {
            withContext(NonCancellable) { c.close() }
        }
    }.getOrDefault(emptyList())

    /**
     * M486: DVR profiles (recording configurations) over HTSP `getDvrConfigs`.
     * Returns uuid/name pairs; the server's default profile has an empty name.
     * An error = an empty list, and the caller then does not show the profile picker.
     */
    suspend fun dvrConfigs(server: TvhServer): List<sk.tvhclient.shared.api.DvrConfig> = runCatching {
        val c = connectWithRetry(server)   // M621
        try {
            val r = c.recvReply(c.send("getDvrConfigs"))
            @Suppress("UNCHECKED_CAST")
            val list = (r["dvrconfigs"] as? List<Any?>) ?: emptyList()
            list.mapNotNull { p ->
                val m = p as? Map<String, Any?> ?: return@mapNotNull null
                val uuid = (m["uuid"] as? String) ?: ""
                val name = (m["name"] as? String) ?: ""
                sk.tvhclient.shared.api.DvrConfig(uuid, name)
            }
        } finally {
            withContext(NonCancellable) { c.close() }
        }
    }.getOrDefault(emptyList())

    private data class Cache(val ts: Long, val meta: HtspClient.Metadata, val withEpg: Boolean)
    private val cache = HashMap<String, Cache>()
    private data class NowCache(val ts: Long, val map: Map<String, List<EpgEvent>>)
    private val nowCache = HashMap<String, NowCache>()
    private data class CapCache(val ts: Long, val reachable: Boolean, val caps: List<String>)
    private val capCache = HashMap<String, CapCache>()

    private fun longOf(m: Map<String, Any?>, key: String): Long? = (m[key] as? Long)
    private fun strOf(m: Map<String, Any?>, key: String): String = (m[key] as? String) ?: ""

    suspend fun metadata(server: TvhServer, withEpg: Boolean, nowSec: Long, epgMaxDays: Int = 1): HtspClient.Metadata {
        val key = server.id
        val ttl = if (withEpg) 600 else 120
        val c = cache[key]
        if (c != null && nowSec - c.ts < ttl && (!withEpg || c.withEpg)) {
            return c.meta
        }
        // M595: do not open a second connection during playback — an older cache is preferable
        if (streaming && c != null && (!withEpg || c.withEpg)) return c.meta
        // M621: metadata (channels, DVR, archive) goes through connectWithRetry too. Until now
        // only the now/next path had retries and the fallback to the remembered IP (M581), so
        // a DNS outage when switching networks took down the channel list and the archive on the first attempt
        // (UnresolvedAddressException in the log), even though the app knew the server's IP.
        val client = connectWithRetry(server)
        val meta = try {
            client.fetchMetadata(withEpg = withEpg, epgMaxDays = epgMaxDays, nowSec = nowSec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // The app is shutting down during loading — CLOSE the socket CLEANLY (NonCancellable,
            // otherwise it hangs and FinalizerWatchdog crashes the app on the next start), and
            // rethrow the cancellation (the coroutine is supposed to terminate correctly).
            withContext(NonCancellable) { client.close() }
            throw e
        } catch (e: Throwable) {
            // A different error (e.g. corrupted data from a desynchronized connection on a
            // fast restart). WE DO NOT CRASH the app — if we have old cache data,
            // we return it; otherwise we throw the error so the caller tries again.
            // WE DO NOT STORE an empty result in the cache (otherwise the next start would show nothing).
            withContext(NonCancellable) { client.close() }
            c?.meta?.let { return it }
            throw e
        } finally {
            // Safety net: close the socket cleanly on a normal finish / cancellation too.
            withContext(NonCancellable) { client.close() }
        }
        // Complete data (finished sync) -> store in the cache and return.
        if (meta.syncDone && meta.channels.isNotEmpty()) {
            cache[key] = Cache(nowSec, meta, withEpg)
            return meta
        }
        // Incomplete (e.g. interrupted by the app shutting down during loading):
        // if we have old complete cache data, we would rather return that; otherwise we return
        // what there is (at least partial) and DO NOT cache it, so the next start downloads it again.
        return c?.meta ?: meta
    }

    /** M581-fix: the last working IP by server name (host -> ip, time). */
    private val hostIp = HashMap<String, Pair<String, Long>>()

    private fun isIpLiteral(h: String): Boolean =
        h.all { it.isDigit() || it == '.' } || h.contains(':')

    /**
     * M581: connecting with retries. On a mobile network (LTE) the DNS resolution of the server's
     * name occasionally fails (UnresolvedAddressException) — and not just for a second. Every attempt goes
     * through the name first; when that fails and we have a remembered IP from a previous successful connection
     * (M581-fix, valid for 6 h), the IP is tried directly. Three rounds: 0 s, 1 s, 3 s.
     */
    internal suspend fun connectWithRetry(server: TvhServer): HtspClient {
        var last: Throwable? = null
        val delays = longArrayOf(0L, 1_000L, 3_000L)
        val nowMs = currentTimeSeconds() * 1000
        val cached = hostIp[server.host]?.takeIf { nowMs - it.second < 6 * 3600_000L }?.first
        for (d in delays) {
            if (d > 0) kotlinx.coroutines.delay(d)
            val hosts = if (cached != null && cached != server.host) listOf(server.host, cached) else listOf(server.host)
            for (h in hosts) {
                val c = HtspClient(h, server.htspPort, server.username, server.password)
                try {
                    c.connect()
                    if (h == server.host && !isIpLiteral(h)) {
                        // the connection via the name went through -> remember the IP for worse times
                        withContext(Dispatchers.Default) { sk.tvhclient.shared.net.resolveHostBlocking(h) }
                            ?.let { hostIp[h] = it to nowMs }
                    }
                    return c
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: Throwable) { last = e; runCatching { c.close() } }
            }
        }
        throw last ?: IllegalStateException("connect failed")
    }

    /** now/next map: for each channel the currently running programme. Via getEvents
     *  on a single open connection — the async dump is unusable here, because it
     *  first sends thousands of DVR entries and the events do not make it in time. */
    /** Map channel -> list of upcoming programmes (the current one + the following ones).
     *  The list lets the client switch to the next programme without a new download. */
    suspend fun epgUpcomingMap(server: TvhServer, nowSec: Long): Map<String, List<EpgEvent>> {
        val nc = nowCache[server.id]
        lastEpgSkipped = false
        if (nc != null && nowSec - nc.ts < 600) return nc.map
        // M595: while a stream is running we do not ask for now/next — a server with
        // a limit of 1 would refuse the second connection and could take down playback too. The cache
        // is returned (even one older than 10 min); it is fetched once playback ends.
        // M603: a skip is NOT an error — the counters are zeroed and
        // lastEpgSkipped is set, otherwise the caller (player, channel list) wrote
        // "EPG incomplete: 0/497" into the log and retried it every 20 s.
        if (streaming) {
            lastEpgSkipped = true
            lastEpgFailed = 0
            lastEpgEmpty = emptyList()
            return nc?.map ?: emptyMap()
        }
        // M572: the counters always apply only to the round currently running — when a round ended
        // with an exception (e.g. an unreachable server), the log otherwise repeated the number
        // from an older round ("0 ok, failed=552")
        lastEpgFailed = 0
        lastEpgEmpty = emptyList()
        val meta = metadata(server, withEpg = false, nowSec = nowSec)
        val channelIds = meta.channels.mapNotNull { longOf(it, "channelId") }
        if (channelIds.isEmpty()) return emptyMap()
        var client = connectWithRetry(server)
        val out = HashMap<String, List<EpgEvent>>()
        var failed = 0
        val empty = ArrayList<Long>()
        // M572: now/next goes over one connection through hundreds of getEvents. When the connection
        // dies in the meantime (Broken pipe / the server disconnects the client), the existing code went
        // on and every remaining channel just added another error — in the log it looked
        // like "failed=552". Now, after three errors in a row the connection is re-established
        // (at most twice) and when it cannot be re-established, the round ends immediately.
        var streak = 0
        var reconnects = 0
        /** Records an error; returns false when there is no point in continuing (the connection
         *  could not be re-established or it was re-established too often). */
        suspend fun onFailure(e: Exception): Boolean {
            // M572-fix: cancelling the coroutine (leaving the screen) is not an EPG error —
            // it must not trigger a reconnect, it goes on to finally
            if (e is kotlinx.coroutines.CancellationException) throw e
            noteEpgError(e); failed++; streak++
            if (streak < 3) return true
            if (reconnects >= 2) return false
            reconnects++; streak = 0
            runCatching { client.close() }
            return try { client = connectWithRetry(server); true }
            catch (e2: Exception) {
                if (e2 is kotlinx.coroutines.CancellationException) throw e2
                noteEpgError(e2); false
            }
        }
        try {
            for (cid in channelIds) {
                var mapped = try {
                    client.getEvents(cid, numFollowing = 5, maxTime = 0)
                        .mapNotNull { mapEvent(it) }.filter { it.stop > nowSec }
                } catch (e: Exception) { if (onFailure(e)) continue else break }
                // M551-fix2: some channels return nothing without maxTime (the server has no "now"
                // pointer, e.g. a gap in the EPG) — a second attempt with a time window as
                // in the daily programme, which does return events for that same channel.
                // M619: the second attempt is now THE SAME query as in the grid (numFollowing
                // 80, a 3-day window, a channel filter and deduplication). Issue #13: for
                // numFollowing=5 the server returned five events from the anchor, which is on some
                // channels in the PAST (EPG with history) — all five had stop < now,
                // so after filtering nothing was left and the channel was logged as "without EPG",
                // even though the grid (numFollowing=80) did show data for that same channel.
                if (mapped.isEmpty()) {
                    mapped = try {
                        client.getEvents(cid, numFollowing = 80, maxTime = nowSec + 3 * 86400)
                            .mapNotNull { mapEvent(it) }
                            .filter { it.channelUuid == cid.toString() }   // M398
                            .distinctBy { it.eventId ?: "${it.start}-${it.title}" }
                            .filter { it.stop > nowSec }
                            .sortedBy { it.start }
                            .take(5)
                    } catch (e: Exception) { if (onFailure(e)) continue else break }
                }
                streak = 0
                if (mapped.isNotEmpty()) out[cid.toString()] = mapped.sortedBy { it.start }
                else empty.add(cid)
            }
        } finally {
            client.close()
        }
        lastEpgEmpty = empty
        // M551: an empty or incomplete result (getEvents failed) is NOT cached —
        // otherwise an empty map was returned for 10 minutes and the EPG in the player "got stuck"
        // (seen after switching the server from HTTP to HTSP mode while the app was running).
        if (out.isNotEmpty() && failed == 0) nowCache[server.id] = NowCache(nowSec, out)
        else nowCache.remove(server.id)
        lastEpgFailed = failed
        return out
    }

    fun clear(serverId: String) { cache.remove(serverId); nowCache.remove(serverId); capCache.remove(serverId) }

    /**
     * M160 — the HTSP server's capabilities from `hello` (servercapability). Connects
     * to server.htspPort (whatever the user sets, default 9982), reads the
     * capabilities and closes immediately. The result is cached per server (TTL). On any
     * failure (port off/firewall/auth) -> reachable=false, empty caps.
     */
    suspend fun capabilities(server: TvhServer, nowSec: Long, ttl: Long = 600): Pair<Boolean, List<String>> {
        val c = capCache[server.id]
        if (c != null && nowSec - c.ts < ttl) return c.reachable to c.caps
        // M621: DELIBERATELY without connectWithRetry here — this is a quick probe "is the server on
        // the HTSP port?" (e.g. when saving a server). Three attempts with waiting would turn
        // an unreachable server into a 4-second wait in the settings.
        val client = HtspClient(server.host, server.htspPort, server.username, server.password)
        val res = try {
            client.connect()
            true to client.serverCapabilities
        } catch (e: Throwable) {
            false to emptyList<String>()
        } finally {
            client.close()
        }
        capCache[server.id] = CapCache(nowSec, res.first, res.second)
        return res
    }

    /**
     * M160 — is timeshift available on the server? True only if the HTSP port is reachable,
     * auth went through and the server reports the "timeshift" capability. Otherwise false (timeshift
     * is not enabled in the player, the app keeps running through the main mode/9981).
     */
    suspend fun timeshiftAvailable(server: TvhServer, nowSec: Long): Boolean {
        val (reachable, caps) = capabilities(server, nowSec)
        return reachable && caps.contains("timeshift")
    }

    // ---- mapping ----

    fun channels(meta: HtspClient.Metadata): List<Channel> =
        meta.channels.mapNotNull { ch ->
            val cid = longOf(ch, "channelId") ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val tagIds = (ch["tags"] as? List<Any?>)?.mapNotNull { (it as? Long)?.toString() } ?: emptyList()
            // M504: `services` (HTSPv5+) carries the service type — it distinguishes
            // radio from TV the same way as Kodi, independently of how the tags are named.
            @Suppress("UNCHECKED_CAST")
            val svcTypes = (ch["services"] as? List<Any?>)?.mapNotNull { sv ->
                (sv as? Map<String, Any?>)?.get("type") as? String
            }?.filter { it.isNotBlank() } ?: emptyList()
            Channel(
                uuid = cid.toString(),
                name = strOf(ch, "channelName").ifBlank { cid.toString() },
                number = longOf(ch, "channelNumber")?.toInt(),
                iconPublicUrl = strOf(ch, "channelIcon").ifBlank { null },
                tags = tagIds,
                serviceTypes = svcTypes,   // M504
                enabled = true
            )
        }

    fun tags(meta: HtspClient.Metadata): List<ChannelTag> =
        meta.tags.mapNotNull { t ->
            val tid = longOf(t, "tagId") ?: return@mapNotNull null
            ChannelTag(
                uuid = tid.toString(),
                name = strOf(t, "tagName").ifBlank { tid.toString() },
                index = longOf(t, "tagIndex")?.toInt() ?: 0,
                enabled = true
            )
        }

    /**
     * M474: scheduled and currently running recordings (state "scheduled"/"recording").
     * Its purpose is to keep the app from offering to record a programme that already has a recording.
     */
    fun dvrScheduled(meta: HtspClient.Metadata): List<DvrEntry> =
        meta.dvr.mapNotNull { d ->
            val state = strOf(d, "state")
            if (state != "scheduled" && state != "recording") return@mapNotNull null
            val e = mapDvrEntry(d) ?: return@mapNotNull null
            // M475: the HTSP commands (cancelDvrEntry/deleteDvrEntry) take a NUMERIC id,
            // not a textual uuid — so we put the id into uuid, so that the recording can be cancelled.
            val numId = longOf(d, "id")
            if (numId != null) e.copy(uuid = numId.toString()) else e
        }

    /** Finished DVR recordings (state == "completed"). */
    fun dvrFinished(meta: HtspClient.Metadata): List<DvrEntry> =
        meta.dvr.mapNotNull { d ->
            val state = strOf(d, "state")
            if (state.isNotBlank() && state != "completed") return@mapNotNull null
            // M439: "Removed Recordings" — TVH keeps the entry as "completed" even after
            // the file has been deleted (according to retention), but it no longer sends dataSize / it is 0.
            // These do not belong in the archive: the file does not exist, playback would return 404.
            val ds = longOf(d, "dataSize")
            if (ds == null || ds <= 0) return@mapNotNull null
            mapDvrEntry(d)
        }

    /** In-progress recordings (state == "recording") — playable from the start
     *  up to the recorded edge via /dvrfile/<uuid>. */
    fun dvrRecording(meta: HtspClient.Metadata): List<DvrEntry> =
        meta.dvr.mapNotNull { d ->
            if (strOf(d, "state") != "recording") return@mapNotNull null
            mapDvrEntry(d)
        }

    private fun mapDvrEntry(d: Map<String, Any?>): DvrEntry? {
        val id = longOf(d, "id") ?: return null
        // /dvrfile needs the hex uuid; HTSP gives it in "uuid" (if present), otherwise the id
        val uuid = (d["uuid"] as? String)?.ifBlank { null } ?: id.toString()
        val start = longOf(d, "start") ?: 0
        val stop = longOf(d, "stop") ?: 0
        return DvrEntry(
            uuid = uuid,
            dispTitle = strOf(d, "title"),
            dispSubtitle = strOf(d, "subtitle"),
            dispDescription = strOf(d, "description").ifBlank { strOf(d, "summary") },
            channelName = strOf(d, "channelName"),
            // HTSP: "channel" = channelId; Channel.uuid is also channelId.toString()
            channelUuid = longOf(d, "channel")?.toString() ?: "",
            start = start,
            stop = stop,
            startExtra = longOf(d, "startExtra") ?: 0,
            stopExtra = longOf(d, "stopExtra") ?: 0,
            duration = if (stop > start) stop - start else 0,
            fileSize = longOf(d, "dataSize") ?: 0,
            status = strOf(d, "state"),
            contentType = longOf(d, "contentType")?.toInt() ?: 0,
            // M483: the numeric id for cancelDvrEntry/deleteDvrEntry — uuid stays
            // hex (for /dvrfile), otherwise a finished recording could not be deleted.
            dvrId = id.toString()
        )
    }

    /** All EPG events (only if the metadata was loaded withEpg). */

    private fun mapEvent(e: Map<String, Any?>): EpgEvent? {
        val cid = longOf(e, "channelId") ?: return null
        val ct = longOf(e, "contentType")?.toInt() ?: 0
        return EpgEvent(
            eventId = longOf(e, "eventId"),
            channelUuid = cid.toString(),
            channelName = "",
            start = longOf(e, "start") ?: 0,
            stop = longOf(e, "stop") ?: 0,
            title = strOf(e, "title"),
            subtitle = strOf(e, "subtitle"),
            summary = strOf(e, "summary"),
            description = strOf(e, "description"),
            genre = if (ct > 0) listOf(ct) else emptyList(),
            ageRating = longOf(e, "ageRating")?.toInt() ?: 0,
            episodeOnscreen = strOf(e, "episodeOnscreen"),
            nextEventId = longOf(e, "nextEventId")
        )
    }

    /** Programme for a channel via HTSP getEvents (fast, per-channel). */
    suspend fun epgForChannel(server: TvhServer, channelId: String, nowSec: Long): List<EpgEvent> {
        val cid = channelId.toLongOrNull() ?: return emptyList()
        val client = connectWithRetry(server)   // M621
        return try {
            client.getEvents(cid, numFollowing = 80, maxTime = nowSec + 3 * 86400)
                .mapNotNull { mapEvent(it) }
                .sortedBy { it.start }
        } finally {
            client.close()
        }
    }

    /** EPG for the grid PROGRESSIVELY on ONE connection: it goes through all channels in
     *  order and after each one calls onChannel (uuid, events), so the UI can
     *  display them as they come. One connection = we do not overload the server (unlike
     *  a connection per channel, which the HTSP server cannot handle). */
    suspend fun epgProgressive(
        server: TvhServer,
        nowSec: Long,
        onChannel: (String, List<EpgEvent>) -> Unit
    ) {
        val meta = metadata(server, withEpg = false, nowSec = nowSec)
        val channelIds = meta.channels.mapNotNull { longOf(it, "channelId") }
        if (channelIds.isEmpty()) return
        val client = connectWithRetry(server)   // M621
        try {
            for (cid in channelIds) {
                val evs = try {
                    client.getEvents(cid, numFollowing = 80, maxTime = nowSec + 3 * 86400)
                        .mapNotNull { mapEvent(it) }
                        // M398: some Tvheadend builds (e.g. 4.3~dev, HTSP v44)
                        // return the events of ALL channels at once for getEvents —
                        // the grid then had an identical merged list with overlapping
                        // blocks in every row. We therefore always
                        // filter the response by the requested channel and deduplicate it.
                        .filter { it.channelUuid == cid.toString() }
                        .distinctBy { it.eventId ?: "${'$'}{it.start}-${'$'}{it.title}" }
                        .sortedBy { it.start }
                } catch (e: Exception) { noteEpgError(e); emptyList() }
                onChannel(cid.toString(), evs)
            }
        } finally {
            client.close()
        }
    }
}
