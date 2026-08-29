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

    /**
     * M486: HTTP API chce uuid profilu, appka si vsak uklada nazov (HTSP berie
     * nazov). Nazov preto prelozime na uuid; ak sa profil na serveri nenajde,
     * radsej nepošleme nic a necháme rozhodnut server, nez aby sme nahravali
     * do cudzieho profilu.
     */
    private suspend fun configUuidByName(name: String): String? = runCatching {
        api.dvrConfigs().firstOrNull { it.name.equals(name, ignoreCase = true) }?.uuid
    }.getOrNull()

    override suspend fun recordEvent(eventId: Long, configId: String?): DvrResult = try {
        // M487: bez zvoleneho profilu neposielame nic a necháme rozhodnut server
        // — rovnako ako HTSP cesta. Do M486 sa tu bral PRVY profil zo zoznamu,
        // co je poradie z api/dvr/config/grid, nie predvolba servera.
        val cfg = if (configId.isNullOrBlank()) null else configUuidByName(configId)
        val params = HashMap<String, String>()
        params["event_id"] = eventId.toString()
        if (!cfg.isNullOrBlank()) params["config_uuid"] = cfg
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
        403 -> "Používateľ nemá právo nahrávať"
        401 -> "Neplatné prihlasovacie údaje"
        404 -> "Server túto funkciu nepodporuje"
        else -> "Server odpovedal chybou $code"
    }
}
