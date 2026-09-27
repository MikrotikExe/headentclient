package sk.tvhclient.shared.api

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import sk.tvhclient.shared.model.DupDetect
import sk.tvhclient.shared.model.DvrAutorec
import sk.tvhclient.shared.model.DvrTimerec
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.model.autorecAround
import sk.tvhclient.shared.model.autorecWindow
import sk.tvhclient.shared.model.formatHm
import sk.tvhclient.shared.model.maskToWeekdays
import sk.tvhclient.shared.model.parseHm
import sk.tvhclient.shared.model.weekdaysToMask

/**
 * M472: recording over the HTTP JSON API — used when the app does not go through HTSP.
 *
 * The endpoints require the ACCESS_RECORDER right, so the server enforces the rights itself;
 * `access()` only serves to keep the user from being shown something
 * the server will refuse.
 */
class HttpDvrService(private val server: TvhServer) : DvrService {

    private val api = TvhApi(server)

    override suspend fun access(): DvrAccess = api.dvrAccess()

    /**
     * M486: the HTTP API wants the profile's uuid, but the app stores the name (HTSP takes
     * the name). We therefore translate the name to a uuid; if the profile is not found on
     * the server, we would rather send nothing and let the server decide than record
     * into someone else's profile.
     */
    private suspend fun configUuidByName(name: String): String? = runCatching {
        api.dvrConfigs().firstOrNull { it.name.equals(name, ignoreCase = true) }?.uuid
    }.getOrNull()

    override suspend fun recordEvent(eventId: Long, configId: String?): DvrResult = try {
        // M487: without a chosen profile we send nothing and let the server decide
        // — the same as the HTSP path. Until M486 the FIRST profile from the list was taken here,
        // which is the order from api/dvr/config/grid, not the server's default.
        val cfg = if (configId.isNullOrBlank()) null else configUuidByName(configId)
        val params = HashMap<String, String>()
        params["event_id"] = eventId.toString()
        // M689: config_uuid is MANDATORY in this form of the call — Tvheadend
        // (api_dvr_entry_create_from_single) returns EINVAL = HTTP 400 without it, so since M487
        // recording over HTTP failed whenever no profile was chosen. An empty value is what "let the
        // server decide" means: dvr_config_find_by_list("") finds no profile by uuid or name and
        // falls back to the first profile the user is allowed, or to the server default.
        params["config_uuid"] = cfg ?: ""
        api.apiPost("api/dvr/entry/create_by_event", params)
        DvrResult.OK
    } catch (e: TvhHttpException) {
        DvrResult.fail(httpMessage(e.httpCode))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun cancel(id: String): DvrResult = try {
        api.apiPost("api/dvr/entry/cancel", mapOf("uuid" to id))
        DvrResult.OK
    } catch (e: TvhHttpException) {
        DvrResult.fail(httpMessage(e.httpCode))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun delete(id: String): DvrResult = try {
        api.apiPost("api/dvr/entry/remove", mapOf("uuid" to id))
        DvrResult.OK
    } catch (e: TvhHttpException) {
        DvrResult.fail(httpMessage(e.httpCode))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    // ---- M696: recording rules over the JSON API ----
    //
    // Field ids are the idnode class properties (dvr_autorec.c / dvr_timerec.c): the grid returns
    // them, api/dvr/<kind>/create takes them in `conf`, api/idnode/save in `node` (with `uuid`),
    // api/idnode/delete takes `uuid`. Times are "HH:MM" strings ("Any" = -1), weekdays a list 1..7,
    // config_name the profile uuid.

    private fun str(o: JsonObject, k: String): String =
        (o[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content ?: ""
    private fun int(o: JsonObject, k: String, def: Int): Int =
        (o[k] as? JsonPrimitive)?.content?.toIntOrNull() ?: def
    private fun bool(o: JsonObject, k: String, def: Boolean): Boolean =
        (o[k] as? JsonPrimitive)?.content?.let { it == "true" || it == "1" } ?: def
    private fun days(o: JsonObject): Int {
        val arr = o["weekdays"] as? JsonArray ?: return DvrAutorec.ALL_DAYS
        val list = arr.mapNotNull { (it as? JsonPrimitive)?.content?.toIntOrNull() }
        return if (list.isEmpty()) DvrAutorec.ALL_DAYS else weekdaysToMask(list)
    }

    private suspend fun configs(): List<DvrConfig> = runCatching { api.dvrConfigs() }.getOrDefault(emptyList())
    private fun List<DvrConfig>.nameOf(uuid: String) = firstOrNull { it.uuid == uuid }?.name ?: ""
    private fun List<DvrConfig>.uuidOf(name: String) =
        firstOrNull { it.name.equals(name, ignoreCase = true) }?.uuid

    override suspend fun autorecs(): List<DvrAutorec> {
        val cfg = configs()
        return api.ruleGrid("autorec").map { o ->
            DvrAutorec(
                id = str(o, "uuid"),
                enabled = bool(o, "enabled", true),
                name = str(o, "name"),
                title = str(o, "title"),
                channelUuid = str(o, "channel"),
                daysOfWeek = days(o),
                startMin = autorecAround(parseHm(str(o, "start")), parseHm(str(o, "start_window"))),   // M700
                startWindowMin = parseHm(str(o, "start_window")),
                dupDetect = int(o, "record", DupDetect.ALL),
                configName = cfg.nameOf(str(o, "config_name")),
                comment = str(o, "comment")
            )
        }
    }

    override suspend fun timerecs(): List<DvrTimerec> {
        val cfg = configs()
        return api.ruleGrid("timerec").map { o ->
            DvrTimerec(
                id = str(o, "uuid"),
                enabled = bool(o, "enabled", true),
                name = str(o, "name"),
                title = str(o, "title"),
                channelUuid = str(o, "channel"),
                daysOfWeek = days(o),
                startMin = parseHm(str(o, "start")).coerceAtLeast(0),
                stopMin = parseHm(str(o, "stop")).coerceAtLeast(0),
                configName = cfg.nameOf(str(o, "config_name")),
                comment = str(o, "comment")
            )
        }
    }

    private fun weekdaysJson(mask: Int) = JsonArray(maskToWeekdays(mask).map { JsonPrimitive(it) })

    private suspend fun autorecNode(a: DvrAutorec, withUuid: Boolean): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (withUuid) m["uuid"] = JsonPrimitive(a.id)
        m["enabled"] = JsonPrimitive(a.enabled)
        m["name"] = JsonPrimitive(a.name)
        m["title"] = JsonPrimitive(a.title)
        m["channel"] = JsonPrimitive(a.channelUuid)
        m["weekdays"] = weekdaysJson(a.daysOfWeek)
        // M700: "start after" / "start before" = ±15 min around the chosen time, "" = any
        // (the setter treats a non-digit value as -1)
        val (after, before) = autorecWindow(a.startMin)
        m["start"] = JsonPrimitive(formatHm(after))
        m["start_window"] = JsonPrimitive(formatHm(before))
        m["record"] = JsonPrimitive(a.dupDetect)
        m["comment"] = JsonPrimitive(a.comment)
        if (a.configName.isNotBlank()) configs().uuidOf(a.configName)?.let { m["config_name"] = JsonPrimitive(it) }
        return JsonObject(m)
    }

    private suspend fun timerecNode(a: DvrTimerec, withUuid: Boolean): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        if (withUuid) m["uuid"] = JsonPrimitive(a.id)
        m["enabled"] = JsonPrimitive(a.enabled)
        m["name"] = JsonPrimitive(a.name)
        m["title"] = JsonPrimitive(a.title)
        m["channel"] = JsonPrimitive(a.channelUuid)
        m["weekdays"] = weekdaysJson(a.daysOfWeek)
        m["start"] = JsonPrimitive(formatHm(a.startMin))
        m["stop"] = JsonPrimitive(formatHm(a.stopMin))
        m["comment"] = JsonPrimitive(a.comment)
        if (a.configName.isNotBlank()) configs().uuidOf(a.configName)?.let { m["config_name"] = JsonPrimitive(it) }
        return JsonObject(m)
    }

    private suspend fun run(block: suspend () -> JsonObject): DvrResult = try {
        val r = block()
        DvrResult(true, id = str(r, "uuid").ifBlank { null })
    } catch (e: TvhHttpException) {
        DvrResult.fail(httpMessage(e.httpCode))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun addAutorec(rule: DvrAutorec) =
        run { api.apiPostJson("api/dvr/autorec/create", "conf", autorecNode(rule, withUuid = false)) }

    override suspend fun updateAutorec(rule: DvrAutorec) =
        run { api.apiPostJson("api/idnode/save", "node", autorecNode(rule, withUuid = true)) }

    override suspend fun deleteAutorec(id: String) =
        run { api.apiPost("api/idnode/delete", mapOf("uuid" to id)) }

    override suspend fun addTimerec(rule: DvrTimerec) =
        run { api.apiPostJson("api/dvr/timerec/create", "conf", timerecNode(rule, withUuid = false)) }

    override suspend fun updateTimerec(rule: DvrTimerec) =
        run { api.apiPostJson("api/idnode/save", "node", timerecNode(rule, withUuid = true)) }

    override suspend fun deleteTimerec(id: String) =
        run { api.apiPost("api/idnode/delete", mapOf("uuid" to id)) }

    private fun httpMessage(code: Int): String = when (code) {
        403 -> "User is not allowed to record"
        401 -> "Invalid credentials"
        404 -> "The server does not support this function"
        else -> "The server replied with error $code"
    }
}
