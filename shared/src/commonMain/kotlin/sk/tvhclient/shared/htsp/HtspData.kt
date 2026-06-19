package sk.tvhclient.shared.htsp

import sk.tvhclient.shared.model.Channel
import sk.tvhclient.shared.model.ChannelTag
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * HTSP dátový zdroj: pripojí sa cez 9982, stiahne metadáta (enableAsyncMetadata)
 * a namapuje HTSP polia na modely appky (rovnaké ako z HTTP /api). Mapovanie
 * prebraté z pluginu (_htsp_api.py). Jednoduchá TTL cache podľa servera, aby
 * sa nepripájalo pri každej karte.
 *
 * Streaming a picony ostávajú HTTP (HTSP tu rieši len dáta).
 */
object HtspData {
    private data class Cache(val ts: Long, val meta: HtspClient.Metadata, val withEpg: Boolean)
    private val cache = HashMap<String, Cache>()
    private data class NowCache(val ts: Long, val map: Map<String, List<EpgEvent>>)
    private val nowCache = HashMap<String, NowCache>()
    private val gridCache = HashMap<String, NowCache>()

    private fun longOf(m: Map<String, Any?>, key: String): Long? = (m[key] as? Long)
    private fun strOf(m: Map<String, Any?>, key: String): String = (m[key] as? String) ?: ""

    suspend fun metadata(server: TvhServer, withEpg: Boolean, nowSec: Long, epgMaxDays: Int = 1): HtspClient.Metadata {
        val key = server.id
        val ttl = if (withEpg) 600 else 120
        val c = cache[key]
        if (c != null && nowSec - c.ts < ttl && (!withEpg || c.withEpg)) {
            return c.meta
        }
        val client = HtspClient(server.host, server.htspPort, server.username, server.password)
        client.connect()
        val meta = try {
            client.fetchMetadata(withEpg = withEpg, epgMaxDays = epgMaxDays, nowSec = nowSec)
        } finally {
            client.close()
        }
        cache[key] = Cache(nowSec, meta, withEpg)
        return meta
    }

    /** now/next mapa: pre kazdy kanal aktualne beziaci program. Cez getEvents
     *  na jednom otvorenom spojeni — async dump je tu nepouzitelny, lebo
     *  posiela najprv tisice DVR zaznamov a eventy sa nestihnu. */
    /** Mapa kanal -> zoznam nadchadzajucich relacii (aktualna + dalsie).
     *  Zoznam umozni klientovi prepnut na dalsiu relaciu bez noveho stahovania. */
    suspend fun epgUpcomingMap(server: TvhServer, nowSec: Long): Map<String, List<EpgEvent>> {
        val nc = nowCache[server.id]
        if (nc != null && nowSec - nc.ts < 600) return nc.map
        val meta = metadata(server, withEpg = false, nowSec = nowSec)
        val channelIds = meta.channels.mapNotNull { longOf(it, "channelId") }
        if (channelIds.isEmpty()) return emptyMap()
        val client = HtspClient(server.host, server.htspPort, server.username, server.password)
        client.connect()
        val out = HashMap<String, List<EpgEvent>>()
        try {
            for (cid in channelIds) {
                val evs = try {
                    client.getEvents(cid, numFollowing = 5, maxTime = 0)
                } catch (e: Exception) { continue }
                val mapped = evs.mapNotNull { mapEvent(it) }
                    .filter { it.stop > nowSec }
                    .sortedBy { it.start }
                if (mapped.isNotEmpty()) out[cid.toString()] = mapped
            }
        } finally {
            client.close()
        }
        nowCache[server.id] = NowCache(nowSec, out)
        return out
    }

    /** Hromadne EPG pre mriezku cez JEDEN async dump (metadata s EPG) — nie
     *  591x getEvents. Vyrazne rychlejsie. Cache 10 min. */
    suspend fun epgGridMap(server: TvhServer, nowSec: Long, force: Boolean = false): Map<String, List<EpgEvent>> {
        val gc = gridCache[server.id]
        if (!force && gc != null && nowSec - gc.ts < 600) return gc.map
        val meta = metadata(server, withEpg = true, nowSec = nowSec, epgMaxDays = 3)
        val out = HashMap<String, MutableList<EpgEvent>>()
        for (e in meta.events) {
            val ev = mapEvent(e) ?: continue
            val cid = ev.channelUuid ?: continue
            out.getOrPut(cid) { ArrayList() }.add(ev)
        }
        val result = out.mapValues { entry -> entry.value.sortedBy { it.start } }
        gridCache[server.id] = NowCache(nowSec, result)
        return result
    }

    fun clear(serverId: String) { cache.remove(serverId); nowCache.remove(serverId); gridCache.remove(serverId) }

    /** M159 — diagnostika timeshiftu na prvom kanali servera. */
    data class TimeshiftProbeResult(val channelName: String, val probe: HtspClient.TimeshiftProbe)

    suspend fun probeTimeshift(
        server: TvhServer,
        nowSec: Long,
        timeshiftPeriodSec: Int = 3600,
        durationMs: Long = 8_000
    ): TimeshiftProbeResult {
        val meta = metadata(server, withEpg = false, nowSec = nowSec)
        val first = meta.channels.firstOrNull { longOf(it, "channelId") != null }
            ?: throw IllegalStateException("žiadny kanál na serveri")
        val cid = longOf(first, "channelId")!!
        val name = strOf(first, "channelName").ifBlank { cid.toString() }
        val client = HtspClient(server.host, server.htspPort, server.username, server.password)
        client.connect()
        val probe = try {
            client.probeTimeshift(cid, timeshiftPeriodSec, durationMs)
        } finally {
            client.close()
        }
        return TimeshiftProbeResult(name, probe)
    }

    // ---- mapovanie ----

    fun channels(meta: HtspClient.Metadata): List<Channel> =
        meta.channels.mapNotNull { ch ->
            val cid = longOf(ch, "channelId") ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val tagIds = (ch["tags"] as? List<Any?>)?.mapNotNull { (it as? Long)?.toString() } ?: emptyList()
            Channel(
                uuid = cid.toString(),
                name = strOf(ch, "channelName").ifBlank { cid.toString() },
                number = longOf(ch, "channelNumber")?.toInt(),
                iconPublicUrl = strOf(ch, "channelIcon").ifBlank { null },
                tags = tagIds,
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

    /** Dokončené DVR nahrávky (state == "completed"). */
    fun dvrFinished(meta: HtspClient.Metadata): List<DvrEntry> =
        meta.dvr.mapNotNull { d ->
            val state = strOf(d, "state")
            if (state.isNotBlank() && state != "completed") return@mapNotNull null
            mapDvrEntry(d)
        }

    /** Prebiehajuce nahravky (state == "recording") — prehratelne od zaciatku
     *  po nahratu hranu cez /dvrfile/<uuid>. */
    fun dvrRecording(meta: HtspClient.Metadata): List<DvrEntry> =
        meta.dvr.mapNotNull { d ->
            if (strOf(d, "state") != "recording") return@mapNotNull null
            mapDvrEntry(d)
        }

    private fun mapDvrEntry(d: Map<String, Any?>): DvrEntry? {
        val id = longOf(d, "id") ?: return null
        // /dvrfile potrebuje hex uuid; HTSP ho dava v "uuid" (ak je), inak id
        val uuid = (d["uuid"] as? String)?.ifBlank { null } ?: id.toString()
        val start = longOf(d, "start") ?: 0
        val stop = longOf(d, "stop") ?: 0
        return DvrEntry(
            uuid = uuid,
            dispTitle = strOf(d, "title"),
            dispSubtitle = strOf(d, "subtitle"),
            dispDescription = strOf(d, "description").ifBlank { strOf(d, "summary") },
            channelName = strOf(d, "channelName"),
            start = start,
            stop = stop,
            startExtra = longOf(d, "startExtra") ?: 0,
            stopExtra = longOf(d, "stopExtra") ?: 0,
            duration = if (stop > start) stop - start else 0,
            fileSize = longOf(d, "dataSize") ?: 0,
            status = strOf(d, "state"),
            contentType = longOf(d, "contentType")?.toInt() ?: 0
        )
    }

    /** Všetky EPG eventy (len ak meta načítané withEpg). */
    fun events(meta: HtspClient.Metadata): List<EpgEvent> =
        meta.events.mapNotNull { mapEvent(it) }

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

    /** Program pre kanal cez HTSP getEvents (rychle, per-kanal). */
    suspend fun epgForChannel(server: TvhServer, channelId: String, nowSec: Long): List<EpgEvent> {
        val cid = channelId.toLongOrNull() ?: return emptyList()
        val client = HtspClient(server.host, server.htspPort, server.username, server.password)
        client.connect()
        return try {
            client.getEvents(cid, numFollowing = 80, maxTime = nowSec + 3 * 86400)
                .mapNotNull { mapEvent(it) }
                .sortedBy { it.start }
        } finally {
            client.close()
        }
    }

    /** EPG pre mriezku PROGRESIVNE na JEDNOM spojeni: prejde vsetky kanaly v
     *  poradi a po kazdom zavola onChannel (uuid, eventy), takze UI ich vie
     *  zobrazovat priebezne. Jedno spojenie = nezahltime server (na rozdiel od
     *  spojenia per kanal, ktore HTSP server nezvlada). */
    suspend fun epgProgressive(
        server: TvhServer,
        nowSec: Long,
        onChannel: (String, List<EpgEvent>) -> Unit
    ) {
        val meta = metadata(server, withEpg = false, nowSec = nowSec)
        val channelIds = meta.channels.mapNotNull { longOf(it, "channelId") }
        if (channelIds.isEmpty()) return
        val client = HtspClient(server.host, server.htspPort, server.username, server.password)
        client.connect()
        try {
            for (cid in channelIds) {
                val evs = try {
                    client.getEvents(cid, numFollowing = 80, maxTime = nowSec + 3 * 86400)
                        .mapNotNull { mapEvent(it) }
                        .sortedBy { it.start }
                } catch (e: Exception) { emptyList() }
                onChannel(cid.toString(), evs)
            }
        } finally {
            client.close()
        }
    }
}
