package sk.tvhclient.shared.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import sk.tvhclient.shared.model.Channel
import sk.tvhclient.shared.model.ChannelTag
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * HTTP klient pre Tvheadend 4.3 JSON API.
 *
 * Vzory prebrane z odladeneho Enigma2 pluginu (plugin_video_tvheadend,
 * tvheadend.py / _data_api.py):
 *  - retry-with-backoff (3 pokusy, 0.5/1/2s) pre transient chyby (FIX 0.48)
 *  - stranky cez start/limit az do total (api_get_all)
 *  - kratky timeout pre test pripojenia (fail-fast 5s)
 *  - endpoint konstanty zhodne s pluginom
 *
 * Auth: Ktor Basic + Digest s auto-detekciou cez 401 challenge. Plugin mal
 * vlastny digest kvoli SHA-256/SHA-512-256 (stock requests zvladal len MD5);
 * ak by tvoj server pouzival SHA digest a Ktor zlyhal, doriesime to ako v
 * pluginnom HTTPDigestAuthMulti. M1 sa pripojil so stock Ktor, takze tvoj
 * server je zatial OK.
 */
class TvhApi(private val server: TvhServer) {

    private val json = Json { ignoreUnknownKeys = true }

    private val client = sk.tvhclient.shared.net.tvhHttpClient(server, json)

    // ---- retry vzor z pluginu (FIX 0.48) ----
    private val retryAttempts = 3
    private val retryBackoffBaseMs = 500L
    private val retryStatusCodes = setOf(500, 502, 503, 504, 408, 429)

    private fun url(path: String): String =
        server.baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * GET na TVH API s retry/backoff. Vracia surovy JsonObject.
     * 401/403/404 → TvhHttpException bez retry (retry nema zmysel).
     */
    private suspend fun apiGet(
        path: String,
        params: Map<String, String> = emptyMap()
    ): JsonObject {
        var lastErr: Throwable? = null
        for (attempt in 0 until retryAttempts) {
            var resp: HttpResponse? = null
            try {
                resp = client.get(url(path)) {
                    params.forEach { (k, v) -> parameter(k, v) }
                }
            } catch (e: Throwable) {
                lastErr = e
            }
            if (resp != null) {
                val status = resp.status.value
                if (status == 200) {
                    return json.parseToJsonElement(resp.body<String>()).jsonObject
                }
                if (status !in retryStatusCodes) {
                    throw TvhHttpException(status)
                }
                lastErr = TvhHttpException(status)
            }
            if (attempt < retryAttempts - 1) {
                // exponencialny backoff 0.5/1/2s
                delay(retryBackoffBaseMs shl attempt)
            }
        }
        throw lastErr ?: TvhHttpException(0)
    }

    /**
     * Stránkovanie cez start/limit az do total (api_get_all z pluginu).
     */
    private suspend fun apiGetAll(
        path: String,
        pageLimit: Int = 500,
        extraParams: Map<String, String> = emptyMap()
    ): List<JsonObject> {
        val entries = mutableListOf<JsonObject>()
        var start = 0
        var total: Int? = null
        repeat(200) {
            val data = apiGet(path, extraParams + mapOf(
                "start" to start.toString(),
                "limit" to pageLimit.toString()
            ))
            val page = (data["entries"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?: emptyList()
            entries.addAll(page)
            if (total == null) {
                total = (data["total"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.let { runCatching { it.int }.getOrNull() }
            }
            val t = total
            if (t != null && entries.size >= t) return entries
            if (page.isEmpty() || page.size < pageLimit) return entries
            start += pageLimit
        }
        return entries
    }

    /** Odstrani duplicitne DVR zaznamy podla uuid (stranky grid_* sa
     *  vedia prekryvat -> ta ista nahravka pride viackrat). Prazdne uuid
     *  nechame tak (nemame podla coho deduplikovat). */
    private fun List<sk.tvhclient.shared.model.DvrEntry>.dedupByUuid(): List<sk.tvhclient.shared.model.DvrEntry> {
        val seen = HashSet<String>()
        return filter { it.uuid.isBlank() || seen.add(it.uuid) }
    }

    private inline fun <reified T> decode(obj: JsonObject): T =
        json.decodeFromJsonElement<T>(obj)

    // ---- verejne API ----

    suspend fun testConnection(): ConnectionResult = try {
        val resp = client.get(url("api/serverinfo"))
        when (resp.status.value) {
            200 -> ConnectionResult.Success(json.decodeFromString(resp.body<String>()))
            401, 403 -> ConnectionResult.AuthFailed(resp.status.value)
            else -> ConnectionResult.HttpError(resp.status.value)
        }
    } catch (e: Exception) {
        ConnectionResult.NetworkError(e.message ?: e::class.simpleName ?: "unknown")
    }

    suspend fun channels(): List<Channel> =
        apiGetAll("api/channel/grid", pageLimit = 1000).mapNotNull {
            runCatching { decode<Channel>(it) }.getOrNull()
        }

    /**
     * M380: stream profily servera (api/profile/list). Vracia nazvy profilov
     * v poradi zo servera — vratane vlastnych transcode profilov, takze appka
     * uz nehada zoznam natvrdo. Odpoved ma tvar
     * {"entries":[{"key":"<uuid>","val":"<nazov>"}, ...]}; beriem "val".
     */
    suspend fun streamProfiles(): List<String> =
        (apiGet("api/profile/list")["entries"] as? JsonArray)
            ?.mapNotNull { e ->
                (e as? JsonObject)?.get("val")
                    ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
                    ?.content
            }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            ?: emptyList()

    suspend fun tags(): List<ChannelTag> =
        apiGetAll("api/channeltag/grid", pageLimit = 200).mapNotNull {
            runCatching { decode<ChannelTag>(it) }.getOrNull()
        }

    /**
     * EPG práve bežiacich programov: dict channelUuid -> event.
     * Vzor get_epg_now z pluginu (mode=now).
     */
    suspend fun epgNow(limit: Int = 5000): Map<String, EpgEvent> {
        val data = runCatching {
            apiGet("api/epg/events/grid", mapOf("mode" to "now", "limit" to limit.toString()))
        }.getOrNull() ?: return emptyMap()
        val out = mutableMapOf<String, EpgEvent>()
        (data["entries"] as? JsonArray)?.forEach { el ->
            val obj = el as? JsonObject ?: return@forEach
            val ev = runCatching { decode<EpgEvent>(obj) }.getOrNull() ?: return@forEach
            ev.channelUuid?.let { out[it] = ev }
        }
        return out
    }

    /**
     * EPG program pre konkretny kanal (denny grid). TVH api/epg/events/grid
     * vie filtrovat podla channel uuid priamo na serveri (efektivnejsie ako
     * klientsky filter cely grid ako robil plugin). Zoradene podla start.
     */
    suspend fun epgForChannel(channelUuid: String, limit: Int = 500): List<EpgEvent> {
        val data = runCatching {
            apiGet("api/epg/events/grid", mapOf(
                "channel" to channelUuid,
                "limit" to limit.toString(),
                "sort" to "start",
                "dir" to "ASC"
            ))
        }.getOrNull() ?: return emptyList()
        return (data["entries"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { runCatching { decode<EpgEvent>(it) }.getOrNull() }
        } ?: emptyList()
    }

    /** Dokoncene DVR nahravky (grid_finished). */
    suspend fun dvrFinished(): List<sk.tvhclient.shared.model.DvrEntry> =
        apiGetAll("api/dvr/entry/grid_finished", pageLimit = 500).mapNotNull {
            runCatching { decode<sk.tvhclient.shared.model.DvrEntry>(it) }.getOrNull()
        }.dedupByUuid()

    /** Naplanovane/prebiehajuce nahravky (grid_upcoming). */
    suspend fun dvrUpcoming(): List<sk.tvhclient.shared.model.DvrEntry> =
        apiGetAll("api/dvr/entry/grid_upcoming", pageLimit = 500).mapNotNull {
            runCatching { decode<sk.tvhclient.shared.model.DvrEntry>(it) }.getOrNull()
        }.dedupByUuid()

    fun close() = client.close()
}

class TvhHttpException(val httpCode: Int) : Exception("HTTP $httpCode")
