package sk.tvhclient.shared.htsp

import sk.tvhclient.shared.api.DvrAccess
import sk.tvhclient.shared.api.DvrResult
import sk.tvhclient.shared.api.DvrService
import sk.tvhclient.shared.model.DvrAutorec
import sk.tvhclient.shared.model.DvrTimerec
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.model.autorecAround
import sk.tvhclient.shared.model.autorecWindow

/**
 * M472: recording over HTSP (addDvrEntry, cancelDvrEntry, deleteDvrEntry).
 *
 * M693: the commands go over the app's shared HTSP connection (HtspSession), the same one the stream
 * uses — like Kodi. A new recording then arrives as dvrEntryAdd in the session's metadata by itself.
 */
class HtspDvrService(private val server: TvhServer) : DvrService {

    /** M693: DVR commands go over the shared connection (HtspSession) — no connection of their own. */
    private suspend fun session(): HtspSession = HtspSessions.get(server)

    /** The server's response: success=1, or an error with readable text. */
    private fun reply(r: Map<String, Any?>): DvrResult {
        val ok = ((r["success"] as? Long) ?: 0L) == 1L
        if (ok) {
            val id = (r["id"] as? Long)?.toString() ?: (r["id"] as? String)
            return DvrResult(true, id = id)
        }
        return DvrResult.fail((r["error"] as? String) ?: "The server rejected the request")
    }

    override suspend fun access(): DvrAccess = try {
        val s = session()
        // M471/M480: the rights arrive asynchronously right after login (accessUpdate) — the session's
        // reader keeps them; on a brand-new connection wait for them briefly
        var acc = s.access
        var guard = 0
        while (acc == null && guard++ < 10) {
            kotlinx.coroutines.delay(200)
            acc = s.access
        }
        val a = acc
        if (a == null) DvrAccess.UNKNOWN
        else DvrAccess(
            canRecord = a.dvr, canSeeFailed = a.failedDvr, isAdmin = a.admin,
            recordingLimit = a.connLimitDvr, known = true
        )
    } catch (_: Throwable) {
        DvrAccess.UNKNOWN
    }

    override suspend fun recordEvent(eventId: Long, configId: String?): DvrResult = try {
        val args = HashMap<String, Any?>()
        args["eventId"] = eventId
        if (!configId.isNullOrBlank()) args["configName"] = configId
        reply(session().request("addDvrEntry", args))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun cancel(id: String): DvrResult = try {
        val n = id.toLongOrNull() ?: return DvrResult.fail("Invalid recording ID")
        reply(session().request("cancelDvrEntry", mapOf("id" to n)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun delete(id: String): DvrResult = try {
        val n = id.toLongOrNull() ?: return DvrResult.fail("Invalid recording ID")
        reply(session().request("deleteDvrEntry", mapOf("id" to n)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    // ---- M696: recording rules over HTSP (add/update/delete{Autorec,Timerec}Entry) ----
    //
    // The list is not requested: with enableAsyncMetadata the server pushes every rule as
    // autorecEntryAdd / timerecEntryAdd (and later updates/deletes), the session keeps them.
    // Field names follow htsp_serierec_convert() / htsp_build_autorecentry() in htsp_server.c.

    private fun l(m: Map<String, Any?>, k: String): Long? = m[k] as? Long
    private fun s(m: Map<String, Any?>, k: String): String = (m[k] as? String) ?: ""

    /** HTSP reports the DVR profile as configId (uuid); the app works with the profile name. */
    private suspend fun configNames(): Map<String, String> = runCatching {
        HtspData.dvrConfigs(server).associate { it.uuid to it.name }
    }.getOrDefault(emptyMap())

    override suspend fun autorecs(): List<DvrAutorec> {
        val r = session().rules()
        val cfg = configNames()
        return r.autorecs.map { m ->
            DvrAutorec(
                id = s(m, "id"),
                enabled = (l(m, "enabled") ?: 1L) != 0L,
                name = s(m, "name"),
                title = s(m, "title"),
                channelUuid = l(m, "channel")?.toString() ?: "",
                daysOfWeek = (l(m, "daysOfWeek") ?: DvrAutorec.ALL_DAYS.toLong()).toInt(),
                startMin = autorecAround((l(m, "start") ?: -1L).toInt(), (l(m, "startWindow") ?: -1L).toInt()),   // M700
                startWindowMin = (l(m, "startWindow") ?: -1L).toInt(),
                dupDetect = (l(m, "dupDetect") ?: 0L).toInt(),
                configName = cfg[s(m, "configId")] ?: "",
                comment = s(m, "comment"),
                serieslink = s(m, "serieslinkUri"),   // M704
                directory = s(m, "directory")          // M705
            )
        }
    }

    override suspend fun timerecs(): List<DvrTimerec> {
        val r = session().rules()
        val cfg = configNames()
        return r.timerecs.map { m ->
            DvrTimerec(
                id = s(m, "id"),
                enabled = (l(m, "enabled") ?: 1L) != 0L,
                name = s(m, "name"),
                title = s(m, "title"),
                channelUuid = l(m, "channel")?.toString() ?: "",
                daysOfWeek = (l(m, "daysOfWeek") ?: DvrAutorec.ALL_DAYS.toLong()).toInt(),
                startMin = (l(m, "start") ?: 0L).toInt(),
                stopMin = (l(m, "stop") ?: 0L).toInt(),
                configName = cfg[s(m, "configId")] ?: "",
                comment = s(m, "comment"),
                directory = s(m, "directory")   // M705
            )
        }
    }

    private fun autorecArgs(a: DvrAutorec, update: Boolean): HashMap<String, Any?> {
        val args = HashMap<String, Any?>()
        if (update) args["id"] = a.id
        args["title"] = a.title
        args["name"] = a.name
        args["enabled"] = if (a.enabled) 1L else 0L
        // -1 = any channel (on add the server also accepts a missing field; on update a missing
        // field keeps the old channel, so -1 is sent explicitly)
        args["channelId"] = a.channelUuid.toLongOrNull() ?: -1L
        args["daysOfWeek"] = a.daysOfWeek.toLong()
        // "Start around" (minutes after midnight; -1 = any time). M700: on add the server builds
        // start/startWindow from approxTime itself, but updateAutorecEntry ignores approxTime and only
        // reads start/startWindow — so an edit of the time did nothing. Both are sent explicitly now.
        if (update) {
            val (after, before) = autorecWindow(a.startMin)
            args["start"] = after.toLong()
            args["startWindow"] = before.toLong()
        } else {
            args["approxTime"] = a.startMin.toLong()
        }
        args["dupDetect"] = a.dupDetect.toLong()
        args["comment"] = a.comment
        if (a.configName.isNotBlank()) args["configName"] = a.configName
        // M704: sent only when set — on an update an absent field keeps the server's value
        if (a.serieslink.isNotBlank()) args["serieslinkUri"] = a.serieslink
        args["directory"] = a.directory.trim()   // M705
        return args
    }

    private fun timerecArgs(a: DvrTimerec, update: Boolean): HashMap<String, Any?> {
        val args = HashMap<String, Any?>()
        if (update) args["id"] = a.id
        args["title"] = a.title
        args["name"] = a.name
        args["enabled"] = if (a.enabled) 1L else 0L
        args["channelId"] = a.channelUuid.toLongOrNull() ?: -1L
        args["daysOfWeek"] = a.daysOfWeek.toLong()
        args["start"] = a.startMin.toLong()
        args["stop"] = a.stopMin.toLong()
        args["comment"] = a.comment
        if (a.configName.isNotBlank()) args["configName"] = a.configName
        args["directory"] = a.directory.trim()   // M705
        return args
    }

    override suspend fun addAutorec(rule: DvrAutorec): DvrResult = try {
        reply(session().request("addAutorecEntry", autorecArgs(rule, update = false)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun updateAutorec(rule: DvrAutorec): DvrResult = try {
        reply(session().request("updateAutorecEntry", autorecArgs(rule, update = true)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun deleteAutorec(id: String): DvrResult = try {
        reply(session().request("deleteAutorecEntry", mapOf("id" to id)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun addTimerec(rule: DvrTimerec): DvrResult = try {
        reply(session().request("addTimerecEntry", timerecArgs(rule, update = false)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun updateTimerec(rule: DvrTimerec): DvrResult = try {
        reply(session().request("updateTimerecEntry", timerecArgs(rule, update = true)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }

    override suspend fun deleteTimerec(id: String): DvrResult = try {
        reply(session().request("deleteTimerecEntry", mapOf("id" to id)))
    } catch (e: Throwable) { DvrResult.fail(e.message) }
}
