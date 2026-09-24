package sk.tvhclient.shared.api

import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import io.ktor.client.statement.HttpResponse
import kotlin.concurrent.Volatile   // M678: kotlin.jvm.Volatile is deprecated in common code
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import sk.tvhclient.shared.model.Channel
import sk.tvhclient.shared.model.ChannelTag
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/** M691: Tvheadend's ST_RADIO (service.h: ST_UNSET=-1, ST_NONE, ST_OTHER, ST_SDTV, ST_HDTV, ST_FHDTV, ST_UHDTV, ST_RADIO). */
private const val ST_RADIO = 6
/** M691: DVB service types as mapped by Tvheadend's dvb_servicetype_lookup (dvb_psi_lib.c). */
private const val DVB_RADIO = 0x02
private val DVB_TV = setOf(
    0x01, 0x11, 0x16, 0x17, 0x18, 0x19, 0x1A, 0x1B, 0x1C, 0x1D, 0x1E, 0x1F,
    0x80, 0x91, 0x96, 0xA0, 0xA4, 0xA6, 0xA8, 0xD3
)

/**
 * HTTP client for the Tvheadend 4.3 JSON API.
 *
 * Patterns taken over from the debugged Enigma2 plugin (plugin_video_tvheadend,
 * tvheadend.py / _data_api.py):
 *  - retry-with-backoff (3 attempts, 0.5/1/2s) for transient errors (FIX 0.48)
 *  - paging via start/limit up to total (api_get_all)
 *  - short timeout for the connection test (fail-fast 5s)
 *  - endpoint constants identical to the plugin
 *
 * Auth: Ktor Basic + Digest with auto-detection via the 401 challenge. The plugin had
 * its own digest because of SHA-256/SHA-512-256 (stock requests only handled MD5);
 * if your server used SHA digest and Ktor failed, we would sort it out as in
 * the plugin's HTTPDigestAuthMulti. M1 connected with stock Ktor, so your
 * server is OK for now.
 */
class TvhApi(private val server: TvhServer) {

    // M399: coerceInputValues + isLenient — dev builds of Tvheadend sometimes change
    // the types of fields (number <-> string, null); without this tolerance the whole entry
    // silently dropped out (runCatching -> null) and DVR/Archive were empty without an error.
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; isLenient = true }

    private val client = sk.tvhclient.shared.net.tvhHttpClient(server, json)

    // ---- retry pattern from the plugin (FIX 0.48) ----
    private val retryAttempts = 3
    private val retryBackoffBaseMs = 500L
    private val retryStatusCodes = setOf(500, 502, 503, 504, 408, 429)

    private fun url(path: String): String =
        server.baseUrl.trimEnd('/') + "/" + path.trimStart('/')

    /**
     * GET on the TVH API with retry/backoff. Returns the raw JsonObject.
     * 401/403/404 → TvhHttpException without retry (a retry makes no sense).
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
                // exponential backoff 0.5/1/2s
                delay(retryBackoffBaseMs shl attempt)
            }
        }
        throw lastErr ?: TvhHttpException(0)
    }

    /**
     * Paging via start/limit up to total (api_get_all from the plugin).
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

    /** Removes duplicate DVR entries by uuid (the grid_* pages
     *  can overlap -> the same recording arrives several times). Empty uuids
     *  are left as they are (we have nothing to deduplicate by). */
    private fun List<sk.tvhclient.shared.model.DvrEntry>.dedupByUuid(): List<sk.tvhclient.shared.model.DvrEntry> {
        val seen = HashSet<String>()
        return filter { it.uuid.isBlank() || seen.add(it.uuid) }
    }

    private inline fun <reified T> decode(obj: JsonObject): T =
        try { json.decodeFromJsonElement<T>(obj) }
        catch (t: Throwable) {
            decodeFailCount++
            lastDecodeError = (t.message ?: t::class.simpleName ?: "?").take(300)
            throw t
        }

    /** M399: counter + last decoding error (can be shown in diagnostics). */
    companion object {
        @Volatile var decodeFailCount: Int = 0
        @Volatile var lastDecodeError: String? = null
    }

    // ---- public API ----

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

    /**
     * M504: channels + the types of their services.
     *
     * api/channel/grid returns only the UUIDs of the services in `services`, not their type,
     * so we fetch the types from api/mpegts/service/grid and pair them by UUID. HTSP
     * has it easier — it sends the type directly in channelAdd.
     *
     * If the service grid cannot be loaded (older server, limited rights),
     * the types stay empty and radio is recognized by the fallback based on tag names.
     */
    suspend fun channels(): List<Channel> {
        val list = apiGetAll("api/channel/grid", pageLimit = 1000).mapNotNull {
            runCatching { decode<Channel>(it) }.getOrNull()
        }
        if (list.none { it.services.isNotEmpty() }) return list
        val typeOf = runCatching { serviceTypes() }.getOrDefault(emptyMap())
        if (typeOf.isEmpty()) return list
        return list.map { ch ->
            val types = ch.services.mapNotNull { typeOf[it] }.filter { it.isNotBlank() }
            if (types.isEmpty()) ch else ch.copy(serviceTypes = types)
        }
    }

    /**
     * M504: service uuid -> its type ("Radio" / "TV").
     *
     * M691: the grid has no "dvb_servicetype_str" field — Tvheadend exposes the raw DVB service
     * type as the number `dvb_servicetype` and the user's override as `s_type_user`. The code
     * therefore always fell through to the service NAME, so over HTTP a radio station counted as
     * radio only if "radio" was in its name (stations without it landed among the TV channels,
     * HTSP was right). The decision now mirrors Tvheadend's own service_is_radio(): the override
     * wins, otherwise the DVB type through the same table as dvb_servicetype_lookup(). A service
     * whose type Tvheadend itself derives from the stream (IPTV, unknown DVB types) keeps the
     * previous name-based value, so nothing changes for those.
     */
    private suspend fun serviceTypes(): Map<String, String> =
        apiGetAll("api/mpegts/service/grid", pageLimit = 1000).mapNotNull { o ->
            val uuid = (o["uuid"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val user = (o["s_type_user"] as? JsonPrimitive)?.content?.toIntOrNull()
            val dvb = (o["dvb_servicetype"] as? JsonPrimitive)?.content?.toIntOrNull()
            val type = when {
                user != null && user > 0 -> if (user == ST_RADIO) "Radio" else "TV"
                dvb == DVB_RADIO -> "Radio"
                dvb != null && dvb in DVB_TV -> "TV"
                else -> (o["svcname"] as? JsonPrimitive)?.content
            } ?: return@mapNotNull null
            uuid to type
        }.toMap()

    /**
     * M380: the server's stream profiles (api/profile/list). Returns the profile names
     * in the order from the server — including custom transcode profiles, so the app
     * no longer guesses the list from hardcoded values. The response has the shape
     * {"entries":[{"key":"<uuid>","val":"<name>"}, ...]}; I take "val".
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
     * EPG of currently running programmes: dict channelUuid -> event.
     * The get_epg_now pattern from the plugin (mode=now).
     */
    /**
     * M511: EPG query parameters + language preference.
     *
     * `lang` determines which language variant of the title/description the server returns.
     * Without it, it uses the language set for the account, otherwise the system default — and
     * the client may then get a different version than, e.g., Kodi has.
     */
    private fun epgArgs(vararg pairs: Pair<String, String>): Map<String, String> {
        val m = LinkedHashMap<String, String>()
        pairs.forEach { m[it.first] = it.second }
        sk.tvhclient.shared.ClientIdent.lang3.takeIf { it.isNotBlank() }?.let { m["lang"] = it }
        return m
    }

    suspend fun epgNow(limit: Int = 5000): Map<String, EpgEvent> {
        val data = runCatching {
            apiGet("api/epg/events/grid", epgArgs("mode" to "now", "limit" to limit.toString()))
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
     * EPG programme for a specific channel (daily grid). TVH api/epg/events/grid
     * can filter by channel uuid directly on the server (more efficient than
     * filtering the whole grid on the client as the plugin did). Sorted by start.
     */
    suspend fun epgForChannel(channelUuid: String, limit: Int = 500): List<EpgEvent> {
        val data = runCatching {
            apiGet("api/epg/events/grid", epgArgs(
                "channel" to channelUuid,
                "limit" to limit.toString(),
                "sort" to "start",
                "dir" to "ASC"
            ))
        }.getOrNull() ?: return emptyList()
        return ((data["entries"] as? JsonArray)?.mapNotNull { el ->
            (el as? JsonObject)?.let { runCatching { decode<EpgEvent>(it) }.getOrNull() }
        } ?: emptyList())
            // M398-fix: some Tvheadend builds (e.g. 4.3~dev) ignore
            // the channel parameter and return the global grid — every channel would then
            // have an identical merged list with overlaps. We therefore always
            // filter the response by the event's channel (on correct servers a no-op;
            // events without a channelUuid are kept, we cannot judge them).
            .filter { it.channelUuid.isNullOrBlank() || it.channelUuid == channelUuid }
            .distinctBy { it.eventId ?: "${'$'}{it.start}-${'$'}{it.title}" }
            .sortedBy { it.start }
    }

    /** Finished DVR recordings (grid_finished). */
    suspend fun dvrFinished(): List<sk.tvhclient.shared.model.DvrEntry> =
        apiGetAll("api/dvr/entry/grid_finished", pageLimit = 500).mapNotNull {
            runCatching { decode<sk.tvhclient.shared.model.DvrEntry>(it) }.getOrNull()
        }.dedupByUuid()

    /** Scheduled/in-progress recordings (grid_upcoming). */
    suspend fun dvrUpcoming(): List<sk.tvhclient.shared.model.DvrEntry> =
        apiGetAll("api/dvr/entry/grid_upcoming", pageLimit = 500).mapNotNull {
            runCatching { decode<sk.tvhclient.shared.model.DvrEntry>(it) }.getOrNull()
        }.dedupByUuid()

    /**
     * M472: POST to the JSON API. Tvheadend takes the parameters as form fields
     * (application/x-www-form-urlencoded) and returns a JSON object.
     */
    internal suspend fun apiPost(path: String, params: Map<String, String>): JsonObject {
        val resp = client.post(url(path)) {
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(params.entries.joinToString("&") { (k, v) ->
                k.encodeURLParameter() + "=" + v.encodeURLParameter()
            })
        }
        val code = resp.status.value
        if (code != 200) throw TvhHttpException(code)
        val body = resp.body<String>()
        return if (body.isBlank()) JsonObject(emptyMap())
        else runCatching { json.parseToJsonElement(body).jsonObject }.getOrElse { JsonObject(emptyMap()) }
    }

    /**
     * M471: the logged-in user's rights over HTTP.
     *
     * `api/access/whoami` was added in Tvheadend API v20 (2026-07) and returns
     * the rights of the current session including the `dvr` list. On older servers the
     * endpoint does not exist (404) — then we return UNKNOWN and offer
     * recording; if the user does not have the rights, the server refuses the call (403)
     * and we display the error.
     */
    suspend fun dvrAccess(): DvrAccess = try {
        val o = apiGet("api/access/whoami")
        fun strList(key: String): List<String> =
            (o[key] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                ?: emptyList()
        /**
         * M517: `api/access/whoami` returns the rights as NUMBERS (`"dvr":1,"admin":0`),
         * not as a list of strings nor as "true"/"false". Until now they were read only
         * as a list, so with an HTTP connection `canRecord` always came out false
         * and the record button did not show up at all. I take both shapes — older
         * TVH versions send a list, newer ones a number.
         */
        fun flag(key: String): Boolean {
            val p = o[key] as? kotlinx.serialization.json.JsonPrimitive ?: return false
            val c = p.content
            return c == "true" || (c.toIntOrNull() ?: 0) != 0
        }
        val dvrRights = strList("dvr")
        DvrAccess(
            // list (older TVH) or a flag as a number (newer)
            canRecord = if (dvrRights.isNotEmpty())
                dvrRights.any { it in setOf("basic", "htsp", "all", "all_rw") }
            else flag("dvr"),
            canSeeFailed = dvrRights.contains("failed") || flag("dvr"),
            isAdmin = flag("admin"),
            recordingLimit = 0,
            known = true
        )
    } catch (e: TvhHttpException) {
        // 404 = old server without whoami, 403 = no rights for this endpoint
        if (e.httpCode == 403) DvrAccess.DENIED else DvrAccess.UNKNOWN
    } catch (_: Throwable) {
        DvrAccess.UNKNOWN
    }

    /** M472: DVR profiles (api/dvr/config/grid). */
    suspend fun dvrConfigs(): List<DvrConfig> = runCatching {
        apiGetAll("api/dvr/config/grid", pageLimit = 100).mapNotNull { o ->
            val uuid = (o["uuid"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
            val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            DvrConfig(uuid, name)
        }
    }.getOrElse { emptyList() }

    fun close() = client.close()
}

class TvhHttpException(val httpCode: Int) : Exception("HTTP $httpCode")
