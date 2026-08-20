package sk.tvhclient.shared.htsp

import sk.tvhclient.shared.model.Channel
import sk.tvhclient.shared.model.ChannelTag
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
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

    /** M471: prava z posledneho HTSP spojenia (accessUpdate), prelozene do
     *  spolocneho tvaru pre UI. */

    /**
     * M476: zoznam stream profilov cez HTSP (`getProfiles`, HTSPv16+).
     * Doteraz sa profily citali len cez HTTP API — na cistom HTSP pripojeni
     * (otvoreny len port 9982) tak zoznam nebol dostupny vobec.
     */
    suspend fun streamProfiles(server: TvhServer): List<String> = runCatching {
        val c = HtspClient(server.host, server.htspPort, server.username, server.password)
        c.connect()
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
     * M486: DVR profily (konfiguracie nahravania) cez HTSP `getDvrConfigs`.
     * Vracia dvojice uuid/nazov; prazdny nazov ma predvoleny profil servera.
     * Chyba = prazdny zoznam, volajuci potom ponuku profilu nezobrazi.
     */
    suspend fun dvrConfigs(server: TvhServer): List<sk.tvhclient.shared.api.DvrConfig> = runCatching {
        val c = HtspClient(server.host, server.htspPort, server.username, server.password)
        c.connect()
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
        val client = HtspClient(server.host, server.htspPort, server.username, server.password)
        client.connect()
        val meta = try {
            client.fetchMetadata(withEpg = withEpg, epgMaxDays = epgMaxDays, nowSec = nowSec)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // Appka sa ukoncuje pocas nacitavania — socket ZATVOR CISTO (NonCancellable,
            // inak visi a FinalizerWatchdog zhodi appku pri dalsom spusteni), a
            // znovu vyhod zrusenie (coroutine sa ma korektne ukoncit).
            withContext(NonCancellable) { client.close() }
            throw e
        } catch (e: Throwable) {
            // Ina chyba (napr. poskodene data z rozsynchronizovaneho spojenia pri
            // rychlom restarte). NEZHADZUJEME appku — ak mame stare cache data,
            // vratime ich; inak vyhodime chybu, aby volajuci skusil znovu.
            // NEUKLADAME prazdne do cache (inak by dalsie spustenie ukazalo prazdno).
            withContext(NonCancellable) { client.close() }
            c?.meta?.let { return it }
            throw e
        } finally {
            // Poistka: socket zatvor cisto aj pri normalnom dobehu / zruseni.
            withContext(NonCancellable) { client.close() }
        }
        // Kompletne data (dokonceny sync) -> uloz do cache a vrat.
        if (meta.syncDone && meta.channels.isNotEmpty()) {
            cache[key] = Cache(nowSec, meta, withEpg)
            return meta
        }
        // Nekompletne (napr. prerusene ukoncenim appky pocas nacitavania):
        // ak mame stare kompletne cache data, radsej vratime tie; inak vratime
        // co je (aspon ciastocne) a NEcachujeme, nech dalsie spustenie stiahne znovu.
        return c?.meta ?: meta
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

    fun clear(serverId: String) { cache.remove(serverId); nowCache.remove(serverId); capCache.remove(serverId) }

    /**
     * M160 — schopnosti HTSP servera z `hello` (servercapability). Pripoji sa
     * na server.htspPort (cokolvek si uzivatel nastavi, default 9982), precita
     * capability a hned zavrie. Vysledok cachuje per server (TTL). Pri akomkolvek
     * zlyhani (port vypnuty/firewall/auth) -> reachable=false, prazdne caps.
     */
    suspend fun capabilities(server: TvhServer, nowSec: Long, ttl: Long = 600): Pair<Boolean, List<String>> {
        val c = capCache[server.id]
        if (c != null && nowSec - c.ts < ttl) return c.reachable to c.caps
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
     * M160 — je timeshift na serveri dostupny? True len ak je HTSP port dostupny,
     * auth presla a server hlasi capability "timeshift". Inak false (timeshift
     * sa v prehravaci nezapne, appka bezi dalej cez hlavny rezim/9981).
     */
    suspend fun timeshiftAvailable(server: TvhServer, nowSec: Long): Boolean {
        val (reachable, caps) = capabilities(server, nowSec)
        return reachable && caps.contains("timeshift")
    }

    // ---- mapovanie ----

    fun channels(meta: HtspClient.Metadata): List<Channel> =
        meta.channels.mapNotNull { ch ->
            val cid = longOf(ch, "channelId") ?: return@mapNotNull null
            @Suppress("UNCHECKED_CAST")
            val tagIds = (ch["tags"] as? List<Any?>)?.mapNotNull { (it as? Long)?.toString() } ?: emptyList()
            // M504: `services` (HTSPv5+) nesie typ sluzby — podla neho sa rozlisi
            // radio od TV rovnako ako v Kodi, nezavisle od pomenovania tagov.
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
     * M474: naplanovane a prave beziace nahravky (state "scheduled"/"recording").
     * Sluzi na to, aby appka neponukala nahravanie relacie, ktora uz nahravanie ma.
     */
    fun dvrScheduled(meta: HtspClient.Metadata): List<DvrEntry> =
        meta.dvr.mapNotNull { d ->
            val state = strOf(d, "state")
            if (state != "scheduled" && state != "recording") return@mapNotNull null
            val e = mapDvrEntry(d) ?: return@mapNotNull null
            // M475: HTSP prikazy (cancelDvrEntry/deleteDvrEntry) beru CISELNE id,
            // nie textove uuid — do uuid preto dame id, aby sa dala nahravka zrusit.
            val numId = longOf(d, "id")
            if (numId != null) e.copy(uuid = numId.toString()) else e
        }

    /** Dokončené DVR nahrávky (state == "completed"). */
    fun dvrFinished(meta: HtspClient.Metadata): List<DvrEntry> =
        meta.dvr.mapNotNull { d ->
            val state = strOf(d, "state")
            if (state.isNotBlank() && state != "completed") return@mapNotNull null
            // M439: "Removed Recordings" — TVH drzi zaznam ako "completed" aj po
            // zmazani suboru (podla retencie), ale dataSize uz neposiela / je 0.
            // Do archivu nepatria: subor neexistuje, prehratie by vratilo 404.
            val ds = longOf(d, "dataSize")
            if (ds == null || ds <= 0) return@mapNotNull null
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
            // HTSP: "channel" = channelId; Channel.uuid je tiez channelId.toString()
            channelUuid = longOf(d, "channel")?.toString() ?: "",
            start = start,
            stop = stop,
            startExtra = longOf(d, "startExtra") ?: 0,
            stopExtra = longOf(d, "stopExtra") ?: 0,
            duration = if (stop > start) stop - start else 0,
            fileSize = longOf(d, "dataSize") ?: 0,
            status = strOf(d, "state"),
            contentType = longOf(d, "contentType")?.toInt() ?: 0,
            // M483: ciselne id pre cancelDvrEntry/deleteDvrEntry — uuid ostava
            // hex (na /dvrfile), inak by sa dokoncena nahravka nedala zmazat.
            dvrId = id.toString()
        )
    }

    /** Všetky EPG eventy (len ak meta načítané withEpg). */

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
                        // M398: niektore buildy Tvheadendu (napr. 4.3~dev, HTSP v44)
                        // vratia na getEvents udalosti VSETKYCH kanalov naraz —
                        // mriezka mala potom v kazdom riadku identicky zjednoteny
                        // zoznam s prekryvajucimi sa blokmi. Odpoved preto vzdy
                        // filtrujeme podla pozadovaneho kanala a deduplikujeme.
                        .filter { it.channelUuid == cid.toString() }
                        .distinctBy { it.eventId ?: "${'$'}{it.start}-${'$'}{it.title}" }
                        .sortedBy { it.start }
                } catch (e: Exception) { emptyList() }
                onChannel(cid.toString(), evs)
            }
        } finally {
            client.close()
        }
    }
}
