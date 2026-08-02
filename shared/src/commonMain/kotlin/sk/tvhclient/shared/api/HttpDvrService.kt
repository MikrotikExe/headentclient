package sk.tvhclient.shared.api

import sk.tvhclient.shared.model.TvhServer

/**
 * M472: nahravanie cez HTTP JSON API — pouzije sa, ked appka nejde cez HTSP.
 *
 * Endpointy vyzaduju pravo ACCESS_RECORDER, takze server prava vynuti sam;
 * `access()` sluzi len na to, aby sa pouzivatelovi nezobrazovalo nieco,
 * co mu server odmietne.
 */
class HttpDvrService(private val server: TvhServer) : DvrService {

    private val api = TvhApi(server)

    override suspend fun access(): DvrAccess = api.dvrAccess()

    /** Prvy DVR profil zo servera (prazdny = predvolba pouzivatela). */
    private suspend fun defaultConfigUuid(): String? = runCatching {
        api.dvrConfigs().firstOrNull()?.uuid
    }.getOrNull()

    override suspend fun recordEvent(eventId: Long, configId: String?): DvrResult = try {
        val cfg = configId ?: defaultConfigUuid()
        val params = HashMap<String, String>()
        params["event_id"] = eventId.toString()
        if (!cfg.isNullOrBlank()) params["config_uuid"] = cfg
        api.apiPost("api/dvr/entry/create_by_event", params)
        DvrResult.OK
    } catch (e: TvhHttpException) {
        DvrResult.fail(httpMessage(e.httpCode))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun recordTime(
        channelId: String, start: Long, stop: Long, title: String, configId: String?
    ): DvrResult = try {
        // conf je JSON objekt; nazov je jazykova mapa, ako to caka Tvheadend
        val safeTitle = title.replace("\\", "\\\\").replace("\"", "\\\"")
        val conf = buildString {
            append("{\"start\":").append(start)
            append(",\"stop\":").append(stop)
            append(",\"channel\":\"").append(channelId).append("\"")
            append(",\"title\":{\"eng\":\"").append(safeTitle).append("\"}")
            if (!configId.isNullOrBlank()) append(",\"config_name\":\"").append(configId).append("\"")
            append("}")
        }
        api.apiPost("api/dvr/entry/create", mapOf("conf" to conf))
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
        403 -> "Používateľ nemá právo nahrávať"
        401 -> "Neplatné prihlasovacie údaje"
        404 -> "Server túto funkciu nepodporuje"
        else -> "Server odpovedal chybou $code"
    }
}
