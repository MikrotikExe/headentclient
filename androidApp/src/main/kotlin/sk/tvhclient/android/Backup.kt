package sk.tvhclient.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.TvhServer

/**
 * Zaloha / obnova nastaveni aplikacie.
 * - servery (Tvh.store)
 * - SharedPreferences: app_prefs, channel_prefs, favorites, watch_progress
 * Vsetko sa serializuje do jedneho JSON-u (bez dalsich kniznic, len org.json).
 */
object Backup {

    private val PREF_FILES = listOf("app_prefs", "channel_prefs", "favorites", "watch_progress")

    fun export(ctx: Context): String {
        val root = JSONObject()
        root.put("app", "tvhclient")
        root.put("version", 1)

        // servery
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

    /** Vrati true ak sa obnova podarila. */
    fun import(ctx: Context, text: String): Boolean {
        return try {
            val root = JSONObject(text)
            if (root.optString("app") != "tvhclient") return false

            // servery: zmaz existujuce, pridaj zo zalohy
            Tvh.store.list().map { it.id }.forEach { Tvh.store.delete(it) }
            val arr = root.optJSONArray("servers")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    Tvh.store.upsert(jsonToServer(arr.getJSONObject(i)))
                }
            }
            val active = root.opt("activeServerId")
            if (active is String && active.isNotBlank()) Tvh.store.activeId = active

            // SharedPreferences
            val prefs = root.optJSONObject("prefs")
            if (prefs != null) {
                for (name in PREF_FILES) {
                    val o = prefs.optJSONObject(name) ?: continue
                    jsonToPrefs(ctx, name, o)
                }
            }
            true
        } catch (e: Exception) {
            false
        }
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

    private fun jsonToPrefs(ctx: Context, name: String, o: JSONObject) {
        val sp = ctx.getSharedPreferences(name, Context.MODE_PRIVATE)
        val ed = sp.edit()
        ed.clear()
        val keys = o.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val e = o.getJSONObject(key)
            when (e.optString("t")) {
                "b" -> ed.putBoolean(key, e.getBoolean("v"))
                "i" -> ed.putInt(key, e.getInt("v"))
                "l" -> ed.putLong(key, e.getLong("v"))
                "f" -> ed.putFloat(key, e.getDouble("v").toFloat())
                "s" -> ed.putString(key, e.getString("v"))
                "ss" -> {
                    val arr = e.getJSONArray("v")
                    val set = HashSet<String>()
                    for (i in 0 until arr.length()) set.add(arr.getString(i))
                    ed.putStringSet(key, set)
                }
            }
        }
        // commit (synchronne) – aby boli data na disku aj pri okamzitom restarte
        ed.commit()
    }
}
