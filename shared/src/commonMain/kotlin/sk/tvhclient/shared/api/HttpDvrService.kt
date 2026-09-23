package sk.tvhclient.shared.api

import sk.tvhclient.shared.model.TvhServer

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

    private fun httpMessage(code: Int): String = when (code) {
        403 -> "User is not allowed to record"
        401 -> "Invalid credentials"
        404 -> "The server does not support this function"
        else -> "The server replied with error $code"
    }
}
