package sk.tvhclient.shared

import sk.tvhclient.shared.api.ChannelRepository
import sk.tvhclient.shared.api.ConnectionResult
import sk.tvhclient.shared.api.TvhApi
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.stream.StreamUrlBuilder
import sk.tvhclient.shared.storage.ServerStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * The entry point for both platforms. Holds the store and the factories for the API/repositories.
 */
object Tvh {

    val store: ServerStore by lazy { ServerStore() }

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
            } catch (e: Exception) {
                val detail = e.message ?: e::class.simpleName ?: "neznáma chyba"
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
                // HTSP: now/next would require a heavy EPG dump -> skip it for
                // a fast channel load. The daily guide (EpgScreen) downloads the EPG
                // only on demand.
                if (htsp) emptyMap() else api.epgNow()
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
    suspend fun streamProfiles(server: TvhServer): List<String> {
        // M476: HTSP has its own getProfiles (v16+), the HTTP port is not needed
        if (server.connectionMode == "htsp")
            return sk.tvhclient.shared.htsp.HtspData.streamProfiles(server)
        return runCatching { TvhApi(server).streamProfiles() }.getOrDefault(emptyList())
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
                it.status.equals("recording", ignoreCase = true) ||
                it.status.equals("running", ignoreCase = true) ||
                it.fileSize > 0
        }

    /** List of the upcoming programmes for a channel (HTSP, for the auto-advance in the list). */
    suspend fun fetchEpgUpcoming(server: TvhServer): Map<String, List<sk.tvhclient.shared.model.EpgEvent>> =
        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.epgUpcomingMap(server, currentTimeSeconds())
        else emptyMap()

    /** EPG for a channel (daily guide): HTSP getEvents (fast) or HTTP. */
    suspend fun fetchEpgForChannel(server: TvhServer, api: TvhApi, channelUuid: String): List<sk.tvhclient.shared.model.EpgEvent> =
        if (server.connectionMode == "htsp") {
            sk.tvhclient.shared.htsp.HtspData.epgForChannel(server, channelUuid, currentTimeSeconds())
        } else api.epgForChannel(channelUuid)

    /** HTSP: progressive EPG for the grid over a single connection (callback per channel). */
    suspend fun fetchEpgGridProgressive(
        server: TvhServer,
        onChannel: (String, List<sk.tvhclient.shared.model.EpgEvent>) -> Unit
    ) {
        if (server.connectionMode != "htsp") return
        sk.tvhclient.shared.htsp.HtspData.epgProgressive(server, currentTimeSeconds(), onChannel)
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

/** Formats unix seconds as "HH:MM" in local time. expect/actual. */
expect fun formatTimeHm(epochSec: Long): String

/** Formats unix seconds as a day name "Pondelok 14.6." in local time. */
expect fun formatDayLabel(epochSec: Long): String

/** Date key "YYYY-MM-DD" in local time for grouping. */
expect fun dateKey(epochSec: Long): String

/** Human-readable date "14.6.2026" in local time. */
expect fun formatDateFull(epochSec: Long): String
