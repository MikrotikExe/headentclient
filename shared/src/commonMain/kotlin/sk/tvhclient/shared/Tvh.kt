package sk.tvhclient.shared

import sk.tvhclient.shared.api.ChannelRepository
import sk.tvhclient.shared.api.ConnectionResult
import sk.tvhclient.shared.api.TvhApi
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.stream.StreamUrlBuilder
import sk.tvhclient.shared.storage.ServerStore
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.launch

/**
 * The entry point for both platforms. Holds the store and the factories for the API/repositories.
 */
object Tvh {

    private val storeLazy = lazy { ServerStore() }
    val store: ServerStore get() = storeLazy.value
    /** M688: true once the encrypted store has been opened — checking does not open it. */
    val isStoreReady: Boolean get() = storeLazy.isInitialized()

    @Throws(CancellationException::class)
    suspend fun testConnection(server: TvhServer): ConnectionResult {
        if (server.connectionMode == "htsp") {
            val client = sk.tvhclient.shared.htsp.HtspClient(
                host = server.host, port = server.htspPort,
                user = server.username, pwd = server.password
            )
            return try {
                client.connect()
                client.fetchMetadata(
                    withEpg = false, channelsOnly = true, nowSec = currentTimeSeconds()
                )
                ConnectionResult.Success(
                    sk.tvhclient.shared.model.ServerInfo(
                        swVersion = client.serverSwVersion ?: "?",
                        apiVersion = (client.serverVersion ?: 0L).toInt(),
                        name = client.serverName ?: "Tvheadend"
                    )
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e   // M740: a cancelled test is not a network error (testAuto would try the other mode)
            } catch (e: Exception) {
                val detail = e.message ?: e::class.simpleName ?: "unknown error"
                ConnectionResult.NetworkError("HTSP: $detail")
            } finally {
                client.close()
            }
        }
        val api = TvhApi(server)
        try { return api.testConnection() } finally { api.close() }
    }

    /**
     * Test with automatic detection. It tries the selected mode; if that fails, it tries the opposite one
     * (HTSP 9982 <-> HTTP 9981). Returns the result and the server with the mode that works
     * (the caller saves that server). The default is HTSP, HTTP serves as a safety net.
     */
    @Throws(CancellationException::class)
    suspend fun testConnectionAuto(server: TvhServer): Pair<ConnectionResult, TvhServer> {
        val first = testConnection(server)
        if (first is ConnectionResult.Success) return first to server
        // Fallback to the opposite mode (a disabled 9981 web interface may also return 401/403,
        // not just a network error; likewise 9982 need not be available).
        val altMode = if (server.connectionMode == "htsp") "http" else "htsp"
        val altServer = server.copy(connectionMode = altMode)
        val second = testConnection(altServer)
        if (second is ConnectionResult.Success) return second to altServer
        return first to server
    }

    fun newServerId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(16) { append(chars.random()) } }
    }

    /** Creates an API client for the given server (the caller is responsible for close). */
    fun apiFor(server: TvhServer): TvhApi = TvhApi(server)

    /** Channel repository for the given server. In HTSP mode the data goes via 9982,
     *  otherwise via HTTP /api. Picon URL without creds (auth via the header). */
    fun channelRepository(server: TvhServer, api: TvhApi): ChannelRepository {
        val htsp = server.connectionMode == "htsp"
        return ChannelRepository(
            channelsProvider = { fetchChannels(server, api) },
            tagsProvider = {
                if (htsp) sk.tvhclient.shared.htsp.HtspData.tags(
                    sk.tvhclient.shared.htsp.HtspData.metadata(server, false, currentTimeSeconds())
                ) else api.tags()
            },
            epgNowProvider = {
                // M720: the live window (LiveEpg) when it is fresh — HTSP gets now/next for free
                // from the server's push, HTTP from the comet feed (started here on first use);
                // until the first window arrives HTTP asks the grid (one request) as before and
                // HTSP stays without it (the daily guide loads the EPG on demand).
                val live = sk.tvhclient.shared.epg.LiveEpg.store(server.id)
                if (!htsp) sk.tvhclient.shared.epg.HttpEpgFeed.ensure(server)
                when {
                    live.isFresh() -> live.nowMap()
                    htsp -> emptyMap()
                    else -> api.epgNow()
                }
            },
            piconUrlFor = { ch -> StreamUrlBuilder.piconUrlNoCreds(server, ch.iconPublicUrl) },
            nowSec = { currentTimeSeconds() }
        )
    }

    /** Channels: HTSP or HTTP. */
    suspend fun fetchChannels(server: TvhServer, api: TvhApi): List<sk.tvhclient.shared.model.Channel> =        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.channels(
                sk.tvhclient.shared.htsp.HtspData.metadata(server, false, currentTimeSeconds()))
        else api.channels()

    /**
     * M380: the server's stream profiles for the menu in the settings. HTTP API only —
     * in HTSP mode the profile is not dealt with, hence an empty list. Errors (server
     * unreachable, old API) are not thrown upwards: the caller uses a fallback.
     */
    /** M715: server id -> profile names usable over HTSP (from the HTTP API; empty = not readable). */
    @kotlin.concurrent.Volatile private var htspUsableProfiles: Map<String, List<String>> = emptyMap()

    suspend fun streamProfiles(server: TvhServer): List<String> {
        // M476: HTSP has its own getProfiles (v16+), the HTTP port is not needed
        if (server.connectionMode == "htsp") {
            val htsp = sk.tvhclient.shared.htsp.HtspData.streamProfiles(server)
            // M715: getProfiles also lists disabled profiles (profile.c profile_get_htsp_list has no
            // enabled check) and the server then silently plays another one. api/profile/list?htsp=1
            // lists only the usable ones — when the account can read the HTTP API, keep only those.
            // one attempt (the HTTP port may be closed for an HTSP-only setup), remembered per server
            val usable = htspUsableProfiles[server.id] ?: try {
                val api = TvhApi(server, retryAttempts = 1)
                val list = try { api.streamProfiles(htsp = true) } finally { api.close() }
                if (list.isNotEmpty()) htspUsableProfiles = htspUsableProfiles + (server.id to list)
                list
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { emptyList() }
            return if (usable.isEmpty()) htsp else htsp.filter { it in usable }.ifEmpty { htsp }
        }
        // M738 (audit B19): the client is closed (it was left open with its own pool and threads)
        return runCatching {
            val api = TvhApi(server)
            try { api.streamProfiles() } finally { api.close() }
        }.getOrDefault(emptyList())
    }

    /**
     * M486: the server's DVR profiles for the menu in the settings. HTSP has its own
     * getDvrConfigs, HTTP uses api/dvr/config/grid.
     */
    suspend fun dvrConfigs(server: TvhServer): List<sk.tvhclient.shared.api.DvrConfig> =
        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.dvrConfigs(server)
        else runCatching {
            val api = TvhApi(server)
            try { api.dvrConfigs() } finally { api.close() }
        }.getOrDefault(emptyList())

    /** Finished DVR: HTSP or HTTP. */
    suspend fun fetchDvrFinished(server: TvhServer, api: TvhApi): List<sk.tvhclient.shared.model.DvrEntry> =
        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.dvrFinished(
                sk.tvhclient.shared.htsp.HtspData.metadata(server, false, currentTimeSeconds()))
        else api.dvrFinished()

    /** In-progress recordings (state recording) — playable from the start. */
    suspend fun fetchDvrInProgress(server: TvhServer, api: TvhApi): List<sk.tvhclient.shared.model.DvrEntry> =
        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.dvrRecording(
                sk.tvhclient.shared.htsp.HtspData.metadata(server, false, currentTimeSeconds()))
        else api.dvrUpcoming().filter {
            // M399: TVH dev builds also use "Running" / other capitalisation
            it.schedStatus.equals("recording", ignoreCase = true) ||
                it.schedStatus.equals("recordingError", ignoreCase = true) ||   // M710
                it.status.equals("recording", ignoreCase = true) ||
                it.status.equals("running", ignoreCase = true) ||
                it.fileSize > 0
        }

    /** List of the upcoming programmes per channel over HTSP (the auto-advance in the list). HTTP reads
     *  the live window directly (ChannelsViewModel.startLive / PlayerEpgStore.startLive), M736. */
    suspend fun fetchEpgUpcoming(server: TvhServer): Map<String, List<sk.tvhclient.shared.model.EpgEvent>> =
        sk.tvhclient.shared.htsp.HtspData.epgUpcomingMap(server, currentTimeSeconds())

    /**
     * M720: the live EPG window of the server (see LiveEpg). HTTP: starts the comet feed when it
     * is not running. HTSP: the window lives with the shared connection — when it was closed for
     * idleness, reconnects in the background (at most once a minute, inside the connlimit rules),
     * so a screen that keeps showing now/next gets a fresh window again.
     */
    fun liveEpg(server: TvhServer): sk.tvhclient.shared.epg.LiveEpgStore {
        val live = sk.tvhclient.shared.epg.LiveEpg.store(server.id)
        if (server.connectionMode != "htsp") {
            sk.tvhclient.shared.epg.HttpEpgFeed.ensure(server)
        } else if (live.feedAlive) {
            sk.tvhclient.shared.htsp.HtspSessions.keepAlive(server.id)   // a reader is here: no idle close
        } else {
            val now = currentTimeSeconds()
            if (now - htspWake >= 60) {
                htspWake = now
                bgScope.launch {
                    runCatching { sk.tvhclient.shared.htsp.HtspSessions.get(server) }
                }
            }
        }
        return live
    }
    private val bgScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    @kotlin.concurrent.Volatile private var htspWake = 0L

    /** EPG for a channel (daily guide): HTSP getEvents (fast) or HTTP. */
    suspend fun fetchEpgForChannel(server: TvhServer, api: TvhApi, channelUuid: String): List<sk.tvhclient.shared.model.EpgEvent> {
        val now = currentTimeSeconds()
        val list = if (server.connectionMode == "htsp") {
            sk.tvhclient.shared.htsp.HtspData.epgForChannel(server, channelUuid, now)
        } else api.epgForChannel(channelUuid)
        // M720: the next hours come from the live window the server keeps current (titles, times,
        // the running pointer); the rest of the day stays as fetched
        val live = sk.tvhclient.shared.epg.LiveEpg.store(server.id)
        if (!live.isFresh(now)) return list
        val window = live.snapshot[channelUuid] ?: return list
        return sk.tvhclient.shared.epg.LiveEpg.mergeWindow(list, window, live.windowEnd(now))
    }

    /** HTSP: progressive EPG for the grid over a single connection (callback per channel). */
    suspend fun fetchEpgGridProgressive(
        server: TvhServer,
        onChannel: (String, List<sk.tvhclient.shared.model.EpgEvent>) -> Unit
    ) {
        if (server.connectionMode != "htsp") return
        val now = currentTimeSeconds()
        // M720: the next hours from the live window (fresh titles, the running pointer), the rest as fetched
        val live = sk.tvhclient.shared.epg.LiveEpg.store(server.id)
        val fresh = live.isFresh(now)
        val end = live.windowEnd(now)
        sk.tvhclient.shared.htsp.HtspData.epgProgressive(server, now) { uuid, evs ->
            val win = if (fresh) live.snapshot[uuid] else null
            onChannel(uuid, if (win.isNullOrEmpty()) evs else sk.tvhclient.shared.epg.LiveEpg.mergeWindow(evs, win, end))
        }
    }

    fun liveUrl(server: TvhServer, channelUuid: String, channelTitle: String?, profile: String): String =
        StreamUrlBuilder.liveUrl(server, channelUuid, profile, channelTitle, htsp = server.connectionMode == "htsp")

    /** Live URL without creds (auth via the header) — uses the profile from the server. */
    fun liveUrlNoCreds(server: TvhServer, channelUuid: String, channelTitle: String?): String =
        StreamUrlBuilder.liveUrlNoCreds(server, channelUuid, server.profile.ifBlank { "pass" }, channelTitle,
            htsp = server.connectionMode == "htsp")

    fun dvrUrl(server: TvhServer, dvrFileId: String): String =
        StreamUrlBuilder.dvrUrl(server, dvrFileId)

    fun piconUrl(server: TvhServer, iconPublicUrl: String?): String? =
        StreamUrlBuilder.piconUrlNoCreds(server, iconPublicUrl)
}

/** Current time in seconds — expect/actual per platform. */
expect fun currentTimeSeconds(): Long

/**
 * M743 (audit R6): seconds on a monotonic clock, for measuring intervals (keepalive, idle close,
 * waits). The wall clock jumps on a box that boots with a wrong time and fixes it via NTP later —
 * a "no reply for 15 s" check then killed a healthy connection, or a wait never ended. Only
 * differences are meaningful; the offset keeps the value well away from 0 (used as "not set").
 */
private val monoStart = kotlin.time.TimeSource.Monotonic.markNow()
fun monoSeconds(): Long = 1_000_000L + monoStart.elapsedNow().inWholeSeconds

/** Formats unix seconds as "HH:MM" in local time. expect/actual. */
expect fun formatTimeHm(epochSec: Long): String

/** Formats unix seconds as a day name "Pondelok 14.6." in local time. */
expect fun formatDayLabel(epochSec: Long): String

/** Date key "YYYY-MM-DD" in local time for grouping. */
expect fun dateKey(epochSec: Long): String

/** Human-readable date "14.6.2026" in local time. */
expect fun formatDateFull(epochSec: Long): String
