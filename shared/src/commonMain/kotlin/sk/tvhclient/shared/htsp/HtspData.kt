package sk.tvhclient.shared.htsp

import sk.tvhclient.shared.model.Channel
import sk.tvhclient.shared.model.ChannelTag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.currentTimeSeconds

/**
 * HTSP data source: maps the HTSP fields onto the app's models (the same ones as from HTTP /api;
 * the mapping is taken over from the plugin, _htsp_api.py). M693: all requests go over the app's
 * single shared connection (HtspSession / HtspSessions) — the stream included — like Kodi, so an
 * account with a connection limit of 1 works during playback too. Picons stay on HTTP.
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
    fun streamStopped() {
        if (streamCount > 0) streamCount--
        // M692: the stream that used up the limit has ended — do not keep the app waiting for the
        // rest of a backoff window (up to 5 min) that was only caused by it
        if (streamCount == 0) connLimit.clear()
    }

    // ---- M692: the account's connection limit (connlimit) ----
    // The server log showed thousands of "multiple connections are not allowed for user X" a day,
    // all from this app: during playback it kept opening a second HTSP connection (EPG/overlay
    // refresh every 60 s, daily programme, archive...), the server held it for 5 s and refused it,
    // and connectWithRetry made up to three attempts (twice as many with the remembered IP). Now a
    // refusal is never retried; if it happened while our own stream runs, no further data connection
    // is attempted until that stream stops (it keeps holding the limit), otherwise we back off for
    // 30 s -> 60 s -> 120 s -> max 5 min. The first successful connection clears it. Servers without a
    // limit never refuse, so nothing changes for them (a second connection during PiP still works).
    private class ConnLimitState(var level: Int, var until: Long, var duringStream: Boolean = false)
    private val connLimit = HashMap<String, ConnLimitState>()
    private var connLimitReportedAt = 0L

    /** M692: diagnostics hook (the Android app writes it into the diagnostic log); called at most once per 5 min. */
    var onConnLimit: ((String) -> Unit)? = null

    /** M692: true while a previous connlimit refusal for this server is within its backoff window. */
    fun connLimitActive(server: TvhServer): Boolean {
        val st = connLimit[server.id] ?: return false
        // refused while our own stream is running: the stream still holds the limit, so every
        // further attempt would be refused too — no more attempts until it stops (streamStopped clears it)
        if (st.duringStream && streaming) return true
        return currentTimeSeconds() < st.until
    }

    private fun noteConnLimit(server: TvhServer) {
        val now = currentTimeSeconds()
        val st = connLimit.getOrPut(server.id) { ConnLimitState(0, 0L) }
        st.level = (st.level + 1).coerceAtMost(5)
        val wait = (30L shl (st.level - 1)).coerceAtMost(300L)
        st.until = now + wait
        if (streaming) st.duringStream = true
        if (now - connLimitReportedAt >= 300) {
            connLimitReportedAt = now
            runCatching {
                onConnLimit?.invoke(
                    "connection limit reached for the account (streaming=$streaming) — next attempt in ${wait}s"
                )
            }
        }
    }

    private fun clearConnLimit(server: TvhServer) { connLimit.remove(server.id) }

    /** M692: called by the stream feeder when its own connection was refused with connlimit. */
    fun reportConnLimit(server: TvhServer) = noteConnLimit(server)

    private fun noteEpgError(e: Throwable) {
        lastEpgError = (e::class.simpleName ?: "Throwable") + ": " + (e.message ?: "")
    }

    // ---- M693: requests over the shared connection (HtspSession) ----
    @Suppress("UNCHECKED_CAST")
    private fun listOfMaps(v: Any?): List<Map<String, Any?>> =
        (v as? List<Any?>)?.mapNotNull { it as? Map<String, Any?> } ?: emptyList()

    /** getEvents (see HtspClient history: M511 language, numFollowing/maxTime). */
    private suspend fun getEvents(s: HtspSession, channelId: Long, numFollowing: Int, maxTime: Long): List<Map<String, Any?>> {
        val args = HashMap<String, Any?>()
        args["channelId"] = channelId
        if (numFollowing > 0) args["numFollowing"] = numFollowing.toLong()
        if (maxTime > 0) args["maxTime"] = maxTime
        sk.tvhclient.shared.ClientIdent.lang2.takeIf { it.isNotBlank() }?.let { args["language"] = it }
        return listOfMaps(s.request("getEvents", args)["events"])
    }

    /** M690: the whole schedule of one channel via epgQuery (empty query = every title, full=1). */
    private suspend fun epgQueryChannel(s: HtspSession, channelId: Long): List<Map<String, Any?>> {
        val args = HashMap<String, Any?>()
        args["query"] = ""
        args["channelId"] = channelId
        args["full"] = 1L
        sk.tvhclient.shared.ClientIdent.lang2.takeIf { it.isNotBlank() }?.let { args["language"] = it }
        return listOfMaps(s.request("epgQuery", args)["events"])
    }

    /** M471: rights from the last HTSP connection (accessUpdate), translated into
     *  the common shape for the UI. */

    /**
     * M476: the list of stream profiles over HTSP (`getProfiles`, HTSPv16+).
     * Until now the profiles were read only over the HTTP API — on a pure HTSP connection
     * (only port 9982 open) the list was thus not available at all.
     */
    suspend fun streamProfiles(server: TvhServer): List<String> = runCatching {
        // M693: over the shared connection
        listOfMaps(HtspSessions.get(server).request("getProfiles")["profiles"])
            .mapNotNull { (it["name"] as? String)?.takeIf { n -> n.isNotBlank() } }
    }.getOrDefault(emptyList())

    /**
     * M486: DVR profiles (recording configurations) over HTSP `getDvrConfigs`.
     * Returns uuid/name pairs; the server's default profile has an empty name.
     * An error = an empty list, and the caller then does not show the profile picker.
     */
    suspend fun dvrConfigs(server: TvhServer): List<sk.tvhclient.shared.api.DvrConfig> = runCatching {
        // M693: over the shared connection
        listOfMaps(HtspSessions.get(server).request("getDvrConfigs")["dvrconfigs"]).map { m ->
            sk.tvhclient.shared.api.DvrConfig((m["uuid"] as? String) ?: "", (m["name"] as? String) ?: "")
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

    /**
     * Channels, tags and recordings. M693: taken from the shared connection's async metadata model
     * (HtspSession) — the server keeps it up to date by itself, so there is no TTL and no new
     * connection per call. [cache] only keeps the last good snapshot for when the server cannot be
     * reached (and within a connlimit backoff, M692). [withEpg] and [epgMaxDays] are no longer used —
     * EPG goes through getEvents.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun metadata(server: TvhServer, withEpg: Boolean, nowSec: Long, epgMaxDays: Int = 1): HtspClient.Metadata {
        val key = server.id
        val c = cache[key]
        if (c != null && connLimitActive(server)) return c.meta   // M692
        val meta = try {
            HtspSessions.get(server).metadata()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            // unreachable server / connection lost: the last good data rather than an error
            c?.meta?.let { return it }
            throw e
        }
        if (meta.channels.isNotEmpty()) cache[key] = Cache(nowSec, meta, false)
        return meta
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
        // M692: a recent connlimit refusal -> do not connect at all until the backoff expires
        if (connLimitActive(server)) throw HtspConnLimitException(skipped = true)
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
                    clearConnLimit(server)   // M692
                    return c
                }
                catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (e: HtspConnLimitException) {
                    // M692: no further attempts (neither the remembered IP nor another round) —
                    // each one would only be held for 5 s and refused again
                    runCatching { c.close() }
                    noteConnLimit(server)
                    throw e
                }
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
        // M693: the M595 skip during playback is gone — now/next goes over the shared connection,
        // which the stream uses too, so it no longer costs a second connection (limit 1 is fine).
        // M572: the counters always apply only to the round currently running — when a round ended
        // with an exception (e.g. an unreachable server), the log otherwise repeated the number
        // from an older round ("0 ok, failed=552")
        lastEpgFailed = 0
        lastEpgEmpty = emptyList()
        // M692: a connlimit refusal is handled like the M595 skip — the cache, no error, no retry
        fun skippedByConnLimit(): Map<String, List<EpgEvent>> {
            lastEpgSkipped = true
            lastEpgFailed = 0
            lastEpgEmpty = emptyList()
            return nc?.map ?: emptyMap()
        }
        val meta = try {
            metadata(server, withEpg = false, nowSec = nowSec)
        } catch (e: HtspConnLimitException) { return skippedByConnLimit() }
        val channelIds = meta.channels.mapNotNull { longOf(it, "channelId") }
        if (channelIds.isEmpty()) return emptyMap()
        var session = try {
            HtspSessions.get(server)
        } catch (e: HtspConnLimitException) { return skippedByConnLimit() }
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
            return try { session = HtspSessions.get(server); true }   // M693: a dead session reconnects
            catch (e2: HtspConnLimitException) { false }   // M692: do not keep knocking
            catch (e2: Exception) {
                if (e2 is kotlinx.coroutines.CancellationException) throw e2
                noteEpgError(e2); false
            }
        }
        for (cid in channelIds) {
            var mapped = try {
                getEvents(session, cid, numFollowing = 5, maxTime = 0)
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
            // M690: channelEvents adds the epgQuery fallback for a cleared now/next pointer
            if (mapped.isEmpty()) {
                mapped = try {
                    channelEvents(session, cid, nowSec)
                        .filter { it.stop > nowSec }
                        .take(5)
                } catch (e: Exception) { if (onFailure(e)) continue else break }
            }
            streak = 0
            if (mapped.isNotEmpty()) out[cid.toString()] = mapped.sortedBy { it.start }
            else empty.add(cid)
        }
        // M693: the shared connection stays open
        lastEpgEmpty = empty
        // M551: an empty or incomplete result (getEvents failed) is NOT cached —
        // otherwise an empty map was returned for 10 minutes and the EPG in the player "got stuck"
        // (seen after switching the server from HTTP to HTSP mode while the app was running).
        if (out.isNotEmpty() && failed == 0) nowCache[server.id] = NowCache(nowSec, out)
        else nowCache.remove(server.id)
        lastEpgFailed = failed
        return out
    }

    fun clear(serverId: String) {
        cache.remove(serverId); nowCache.remove(serverId); capCache.remove(serverId)
        // M693: a suspected broken connection -> reconnect on the next use, but never under a running stream
        HtspSessions.peek(serverId)?.takeIf { !it.hasActiveSubscriptions() }?.let { HtspSessions.close(serverId) }
    }

    /**
     * M160 — the HTSP server's capabilities from `hello` (servercapability). Connects
     * to server.htspPort (whatever the user sets, default 9982), reads the
     * capabilities and closes immediately. The result is cached per server (TTL). On any
     * failure (port off/firewall/auth) -> reachable=false, empty caps.
     */
    suspend fun capabilities(server: TvhServer, nowSec: Long, ttl: Long = 600): Pair<Boolean, List<String>> {
        val c = capCache[server.id]
        if (c != null && nowSec - c.ts < ttl) return c.reachable to c.caps
        // M692: within a connlimit backoff keep the last known answer (or "unknown" without
        // caching it) instead of another refused connection
        if (connLimitActive(server)) return c?.let { it.reachable to it.caps } ?: (false to emptyList())
        // M693: from the hello of the shared connection — no extra connection
        val res = try {
            true to HtspSessions.get(server).serverCapabilities
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: HtspConnLimitException) {
            // M692: the limit is used up by someone else — keep the last known answer, do not cache
            return c?.let { it.reachable to it.caps } ?: (false to emptyList())
        } catch (e: Throwable) {
            false to emptyList<String>()
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

    /**
     * M690: the programme of one channel for the next 3 days — getEvents, and if that returns
     * nothing, the whole schedule via epgQuery ([HtspClient.epgQueryChannel]: getEvents starts at the
     * channel's now/next pointer, which Tvheadend temporarily clears when EIT and XMLTV events
     * replace each other). The fallback only runs for channels that came back empty, so channels
     * with a normal EPG are queried exactly as before.
     */
    private suspend fun channelEvents(session: HtspSession, cid: Long, nowSec: Long): List<EpgEvent> {
        val maxTime = nowSec + 3 * 86400
        fun clean(raw: List<Map<String, Any?>>): List<EpgEvent> = raw
            .mapNotNull { mapEvent(it) }
            // M398: some Tvheadend builds (e.g. 4.3~dev, HTSP v44)
            // return the events of ALL channels at once for getEvents —
            // the grid then had an identical merged list with overlapping
            // blocks in every row. We therefore always
            // filter the response by the requested channel and deduplicate it.
            .filter { it.channelUuid == cid.toString() }
            .distinctBy { it.eventId ?: "${it.start}-${it.title}" }
            .sortedBy { it.start }
        val direct = clean(getEvents(session, cid, numFollowing = 80, maxTime = maxTime))
        if (direct.isNotEmpty()) return direct
        val all = try {
            epgQueryChannel(session, cid)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            noteEpgError(e); emptyList()
        }
        // the same window as getEvents: from the running programme up to maxTime, at most 80
        return clean(all).filter { it.stop > nowSec && it.start <= maxTime }.take(80)
    }

    /** Programme for a channel via HTSP getEvents (fast, per-channel). */
    suspend fun epgForChannel(server: TvhServer, channelId: String, nowSec: Long): List<EpgEvent> {
        val cid = channelId.toLongOrNull() ?: return emptyList()
        return channelEvents(HtspSessions.get(server), cid, nowSec)   // M693: the shared connection
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
        val session = HtspSessions.get(server)   // M693: the shared connection (stays open)
        for (cid in channelIds) {
            if (!session.alive) break   // the connection died — the rest would fail immediately
            val evs = try {
                channelEvents(session, cid, nowSec)   // M690: incl. the epgQuery fallback
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) { noteEpgError(e); emptyList() }
            onChannel(cid.toString(), evs)
        }
    }
}
