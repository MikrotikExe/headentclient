package sk.tvhclient.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.TvhServer

/**
 * Backup / restore of the application settings.
 * - servers (Tvh.store)
 * - SharedPreferences: app_prefs, channel_prefs, favorites, watch_progress
 * Everything is serialised into a single JSON (no extra libraries, just org.json).
 */
object Backup {

    private val PREF_FILES = listOf("app_prefs", "channel_prefs", "favorites", "watch_progress")

    fun export(ctx: Context): String {
        val root = JSONObject()
        root.put("app", "tvhclient")
        root.put("version", 1)

        // servers
        val arr = JSONArray()
        Tvh.store.list().forEach { s -> arr.put(serverToJson(s)) }
        root.put("servers", arr)
        root.put("activeServerId", Tvh.store.activeId ?: JSONObject.NULL)

        // SharedPreferences
        val prefs = JSONObject()
        for (name in PREF_FILES) prefs.put(name, prefsToJson(ctx, name))
        root.put("prefs", prefs)

        return root.toString(2)
    }

    /** M737: a backup larger than this is not a backup (a video picked by mistake) — refused before reading. */
    const val MAX_BYTES = 5 * 1024 * 1024

    /**
     * Returns true if the restore succeeded. Call OFF the main thread.
     *
     * M737 (audit K11): the whole document is parsed and checked FIRST, and only a valid one is
     * applied. Until now the servers were deleted before anything was checked: a file without a
     * "servers" array wiped every server and reported success, a malformed entry left the servers
     * gone and half of the settings overwritten, and a value of the wrong type (a hand-edited
     * backup, or one from a version that changed a key) crashed the app at every start.
     */
    fun import(ctx: Context, text: String): Boolean {
        val plan = runCatching { parse(ctx, text) }.getOrNull() ?: return false
        return try {
            val oldIds = Tvh.store.list().map { it.id }
            oldIds.forEach { Tvh.store.delete(it) }
            plan.servers.forEach { Tvh.store.upsert(it) }
            Tvh.store.activeId = plan.activeId
            for ((name, values) in plan.prefs) applyPrefs(ctx, name, values)
            // M738 (audit B23): the process-wide per-server state of every old and new id is dropped,
            // as save() does — a restored server with the same id but another host / connection mode
            // used to keep the cached HTSP connection, the live EPG window and the picon loader
            for (id in (oldIds + plan.servers.map { it.id }).toSet()) {
                runCatching { sk.tvhclient.shared.htsp.HtspData.reset(id) }
                runCatching { sk.tvhclient.shared.epg.HttpEpgFeed.stop(id) }
                runCatching { kotlinx.coroutines.runBlocking { sk.tvhclient.shared.epg.LiveEpg.forget(id) } }
                DvrProfileAsk.clear(id)
            }
            PiconImageLoader.invalidate()
            true
        } catch (e: Exception) {
            CrashLogger.report(ctx, "Backup.import", e)
            false
        }
    }

    private class Plan(val servers: List<TvhServer>, val activeId: String, val prefs: Map<String, Map<String, Any>>)

    /** The checked content of a backup, or null when it is not a usable one. */
    private fun parse(ctx: Context, text: String): Plan? {
        val root = JSONObject(text)
        if (root.optString("app") != "tvhclient") return null
        val arr = root.optJSONArray("servers") ?: return null
        val servers = ArrayList<TvhServer>()
        val ids = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: return null
            val s = jsonToServer(o)
            if (s.id.isBlank() || s.host.isBlank() || !ids.add(s.id)) return null
            servers.add(s)
        }
        if (servers.isEmpty()) return null   // restoring "no servers" over working ones is never what was meant
        val wanted = (root.opt("activeServerId") as? String)?.takeIf { it in ids }
        val prefs = LinkedHashMap<String, Map<String, Any>>()
        val po = root.optJSONObject("prefs")
        if (po != null) for (name in PREF_FILES) {
            val o = po.optJSONObject(name) ?: continue
            prefs[name] = parsePrefs(ctx, name, o)
        }
        return Plan(servers, wanted ?: servers.first().id, prefs)
    }

    /**
     * Typed values of one prefs file. A key whose value is malformed, or whose type differs from the
     * type the app holds for it right now, is skipped (the app reads each key with one fixed type —
     * a wrong one throws ClassCastException at the read).
     */
    private fun parsePrefs(ctx: Context, name: String, o: JSONObject): Map<String, Any> {
        val current = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).all
        val out = LinkedHashMap<String, Any>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val e = o.optJSONObject(key) ?: continue
            val v: Any = runCatching<Any?> {
                when (e.optString("t")) {
                    "b" -> e.getBoolean("v")
                    "i" -> e.getInt("v")
                    "l" -> e.getLong("v")
                    "f" -> e.getDouble("v").toFloat()
                    "s" -> e.getString("v")
                    "ss" -> {
                        val a = e.getJSONArray("v")
                        HashSet<String>().also { set -> for (i in 0 until a.length()) set.add(a.getString(i)) }
                    }
                    else -> null
                }
            }.getOrNull() ?: continue
            val cur = current[key]
            if (cur != null && !sameType(cur, v)) continue
            out[key] = v
        }
        return out
    }

    private fun sameType(a: Any, b: Any): Boolean = when (a) {
        is Boolean -> b is Boolean
        is Int -> b is Int
        is Long -> b is Long
        is Float -> b is Float
        is String -> b is String
        is Set<*> -> b is Set<*>
        else -> false
    }

    private fun serverToJson(s: TvhServer) = JSONObject().apply {
        put("id", s.id)
        put("name", s.name)
        put("host", s.host)
        put("port", s.port)
        put("useHttps", s.useHttps)
        put("username", s.username)
        put("password", s.password)
        put("profile", s.profile)
        put("dvrConfig", s.dvrConfig)   // M486
        put("authMode", s.authMode)
        put("connectionMode", s.connectionMode)
        put("htspPort", s.htspPort)
    }

    private fun jsonToServer(o: JSONObject) = TvhServer(
        id = o.optString("id"),
        name = o.optString("name"),
        host = o.optString("host"),
        port = o.optInt("port", 9981),
        useHttps = o.optBoolean("useHttps", false),
        username = o.optString("username", ""),
        password = o.optString("password", ""),
        profile = o.optString("profile", ""),   // M502
        dvrConfig = o.optString("dvrConfig", ""),   // M486
        authMode = o.optString("authMode", "auto"),
        connectionMode = o.optString("connectionMode", "http"),
        htspPort = o.optInt("htspPort", 9982)
    )

    private fun prefsToJson(ctx: Context, name: String): JSONObject {
        val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val out = JSONObject()
        for ((k, v) in sp.all) {
            val e = JSONObject()
            when (v) {
                is Boolean -> { e.put("t", "b"); e.put("v", v) }
                is Int -> { e.put("t", "i"); e.put("v", v) }
                is Long -> { e.put("t", "l"); e.put("v", v) }
                is Float -> { e.put("t", "f"); e.put("v", v.toDouble()) }
                is String -> { e.put("t", "s"); e.put("v", v) }
                is Set<*> -> {
                    e.put("t", "ss")
                    e.put("v", JSONArray(v.map { it.toString() }))
                }
                else -> continue
            }
            out.put(k, e)
        }
        return out
    }

    @Suppress("UNCHECKED_CAST")
    private fun applyPrefs(ctx: Context, name: String, values: Map<String, Any>) {
        val ed = ctx.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
        ed.clear()
        for ((key, v) in values) when (v) {
            is Boolean -> ed.putBoolean(key, v)
            is Int -> ed.putInt(key, v)
            is Long -> ed.putLong(key, v)
            is Float -> ed.putFloat(key, v)
            is String -> ed.putString(key, v)
            is Set<*> -> ed.putStringSet(key, v as Set<String>)
        }
        // commit (synchronous) – so the data is on disk even on an immediate restart
        ed.commit()
    }
}
