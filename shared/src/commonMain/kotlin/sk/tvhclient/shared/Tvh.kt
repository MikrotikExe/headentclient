package sk.tvhclient.shared

import sk.tvhclient.shared.api.ChannelRepository
import sk.tvhclient.shared.api.ConnectionResult
import sk.tvhclient.shared.api.TvhApi
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.stream.StreamUrlBuilder
import sk.tvhclient.shared.storage.ServerStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * Vstupny bod pre obe platformy. Drzi store a tovarne na API/repository.
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
                val meta = client.fetchMetadata(
                    withEpg = false, channelsOnly = true, nowSec = currentTimeSeconds()
                )
                ConnectionResult.Success(
                    sk.tvhclient.shared.model.ServerInfo(
                        swVersion = "HTSP v${client.serverVersion ?: "?"} · ${meta.channels.size} kanálov",
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

    fun newServerId(): String {
        val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString { repeat(16) { append(chars.random()) } }
    }

    /** Vytvori API klienta pre dany server (volajuci je zodpovedny za close). */
    fun apiFor(server: TvhServer): TvhApi = TvhApi(server)

    /** Repository kanalov pre dany server. V HTSP rezime data cez 9982,
     *  inak cez HTTP /api. Picon URL bez creds (auth cez hlavicku). */
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
                // HTSP: now/next by vyzadoval tazky EPG dump -> preskoc pre
                // rychle nacitanie kanalov. Denny program (EpgScreen) si EPG
                // stiahne az na vyziadanie.
                if (htsp) emptyMap() else api.epgNow()
            },
            piconUrlFor = { ch -> StreamUrlBuilder.piconUrlNoCreds(server, ch.iconPublicUrl) },
            nowSec = { currentTimeSeconds() }
        )
    }

    /** Kanaly: HTSP alebo HTTP. */
    suspend fun fetchChannels(server: TvhServer, api: TvhApi): List<sk.tvhclient.shared.model.Channel> =
        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.channels(
                sk.tvhclient.shared.htsp.HtspData.metadata(server, false, currentTimeSeconds()))
        else api.channels()

    /** Dokoncene DVR: HTSP alebo HTTP. */
    suspend fun fetchDvrFinished(server: TvhServer, api: TvhApi): List<sk.tvhclient.shared.model.DvrEntry> =
        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.dvrFinished(
                sk.tvhclient.shared.htsp.HtspData.metadata(server, false, currentTimeSeconds()))
        else api.dvrFinished()

    /** Prebiehajuce nahravky (state recording) — prehratelne od zaciatku. */
    suspend fun fetchDvrInProgress(server: TvhServer, api: TvhApi): List<sk.tvhclient.shared.model.DvrEntry> =
        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.dvrRecording(
                sk.tvhclient.shared.htsp.HtspData.metadata(server, false, currentTimeSeconds()))
        else api.dvrUpcoming().filter {
            it.schedStatus == "recording" || it.status == "recording" || it.fileSize > 0
        }

    /** Zoznam nadchadzajucich relacii na kanal (HTSP, pre auto-prechod na zozname). */
    suspend fun fetchEpgUpcoming(server: TvhServer): Map<String, List<sk.tvhclient.shared.model.EpgEvent>> =
        if (server.connectionMode == "htsp")
            sk.tvhclient.shared.htsp.HtspData.epgUpcomingMap(server, currentTimeSeconds())
        else emptyMap()

    /** Hromadne EPG pre mriezku (vsetky kanaly naraz, plynule skrolovanie). */
    suspend fun fetchEpgGrid(server: TvhServer, api: TvhApi, force: Boolean = false): Map<String, List<sk.tvhclient.shared.model.EpgEvent>> =
        if (server.connectionMode == "htsp") {
            sk.tvhclient.shared.htsp.HtspData.epgGridMap(server, currentTimeSeconds(), force)
        } else {
            // HTTP: po kanaloch na jednom kliente (drzane v cache vo ViewModeli)
            val out = HashMap<String, List<sk.tvhclient.shared.model.EpgEvent>>()
            for (ch in fetchChannels(server, api)) {
                val evs = try { api.epgForChannel(ch.uuid) } catch (e: Exception) { emptyList() }
                if (evs.isNotEmpty()) out[ch.uuid] = evs
            }
            out
        }

    /** EPG pre kanal (denny program): HTSP getEvents (rychle) alebo HTTP. */
    suspend fun fetchEpgForChannel(server: TvhServer, api: TvhApi, channelUuid: String): List<sk.tvhclient.shared.model.EpgEvent> =
        if (server.connectionMode == "htsp") {
            sk.tvhclient.shared.htsp.HtspData.epgForChannel(server, channelUuid, currentTimeSeconds())
        } else api.epgForChannel(channelUuid)

    /** HTSP: progresivne EPG pre mriezku na jednom spojeni (callback po kanaloch). */
    suspend fun fetchEpgGridProgressive(
        server: TvhServer,
        onChannel: (String, List<sk.tvhclient.shared.model.EpgEvent>) -> Unit
    ) {
        if (server.connectionMode != "htsp") return
        sk.tvhclient.shared.htsp.HtspData.epgProgressive(server, currentTimeSeconds(), onChannel)
    }

    fun liveUrl(server: TvhServer, channelUuid: String, channelTitle: String?, profile: String): String =
        StreamUrlBuilder.liveUrl(server, channelUuid, profile, channelTitle, htsp = server.connectionMode == "htsp")

    /** Live URL bez creds (auth cez hlavicku) — pouziva profil zo servera. */
    fun liveUrlNoCreds(server: TvhServer, channelUuid: String, channelTitle: String?): String =
        StreamUrlBuilder.liveUrlNoCreds(server, channelUuid, server.profile.ifBlank { "pass" }, channelTitle,
            htsp = server.connectionMode == "htsp")

    fun dvrUrl(server: TvhServer, dvrFileId: String): String =
        StreamUrlBuilder.dvrUrl(server, dvrFileId)

    fun piconUrl(server: TvhServer, iconPublicUrl: String?): String? =
        StreamUrlBuilder.piconUrlNoCreds(server, iconPublicUrl)
}

/** Aktualny cas v sekundach — expect/actual per platforma. */
expect fun currentTimeSeconds(): Long

/** Formatuje unix sekundy na "HH:MM" v lokalnom case. expect/actual. */
expect fun formatTimeHm(epochSec: Long): String

/** Formatuje unix sekundy na nazov dna "Pondelok 14.6." v lokalnom case. */
expect fun formatDayLabel(epochSec: Long): String

/** Kluc datumu "YYYY-MM-DD" v lokalnom case pre zoskupenie. */
expect fun dateKey(epochSec: Long): String

/** Citatelny datum "14.6.2026" v lokalnom case. */
expect fun formatDateFull(epochSec: Long): String
