package sk.tvhclient.android

import android.content.Context

/**
 * Tracking of the playback position of DVR/archive programmes. For each item (by
 * serverId + uuid) it holds the position, the duration, the watched-to-the-end flag and the time of the last
 * viewing. Stored in SharedPreferences as "posMs|durMs|completed|ts".
 */
object WatchProgress {
    private const val PREFS = "watch_progress"
    private const val COMPLETE_FRACTION = 0.95

    data class Info(
        val posMs: Long,
        val durMs: Long,
        val completed: Boolean,
        val ts: Long
    ) {
        val fraction: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    }

    private fun key(serverId: String, uuid: String) = "wp:$serverId:$uuid"

    private fun parse(v: String): Info? {
        val p = v.split("|")
        if (p.size < 4) return null
        return Info(
            posMs = p[0].toLongOrNull() ?: 0,
            durMs = p[1].toLongOrNull() ?: 0,
            completed = p[2] == "1",
            ts = p[3].toLongOrNull() ?: 0
        )
    }

    /** [legacyId]: M715 — the older HTSP key (numeric id), read when the uuid has nothing yet. */
    fun get(context: Context, serverId: String, uuid: String, legacyId: String? = null): Info? {
        if (uuid.isBlank()) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(key(serverId, uuid), null)
            ?: legacyId?.takeIf { it.isNotBlank() && it != uuid }?.let { prefs.getString(key(serverId, it), null) }
            ?: return null
        return parse(raw)
    }

    fun save(context: Context, serverId: String, uuid: String, posMs: Long, durMs: Long) {
        if (uuid.isBlank()) return
        val completed = durMs > 0 && posMs >= (durMs * COMPLETE_FRACTION).toLong()
        val v = "$posMs|$durMs|${if (completed) "1" else "0"}|${System.currentTimeMillis()}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(serverId, uuid), v).apply()
    }

    /** Mark as fully watched (e.g. on EndReached). */
    fun markCompleted(context: Context, serverId: String, uuid: String, durMs: Long) {
        if (uuid.isBlank()) return
        val d = if (durMs > 0) durMs else 1
        save(context, serverId, uuid, d, d)
    }

    /**
     * M715: over HTSP a recording was keyed by its numeric id until now (the hex uuid was read
     * from a field the server does not send). Moves such positions to the hex uuid, so nothing
     * saved is lost after the update and HTSP and HTTP share one key. Cheap — only the entries
     * whose numeric key exists are touched.
     */
    fun migrateLegacy(context: Context, serverId: String, entries: List<sk.tvhclient.shared.model.DvrEntry>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        var ed: android.content.SharedPreferences.Editor? = null
        for (e in entries) {
            val old = e.dvrId
            if (old.isBlank() || old == e.uuid || e.uuid.isBlank()) continue
            val v = prefs.getString(key(serverId, old), null) ?: continue
            val editor = ed ?: prefs.edit().also { ed = it }
            val cur = prefs.getString(key(serverId, e.uuid), null)
            // keep the more recent one (both can exist after switching between HTSP and HTTP)
            if (cur == null || (parse(v)?.ts ?: 0L) > (parse(cur)?.ts ?: 0L)) editor.putString(key(serverId, e.uuid), v)
            editor.remove(key(serverId, old))
        }
        ed?.apply()
    }

    /** uuid -> Info for the given server, ordered from the most recently watched. */
    fun recent(context: Context, serverId: String, limit: Int = 100): List<Pair<String, Info>> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prefix = "wp:$serverId:"
        val out = ArrayList<Pair<String, Info>>()
        for ((k, v) in prefs.all) {
            if (!k.startsWith(prefix) || v !is String) continue
            val info = parse(v) ?: continue
            out.add(k.removePrefix(prefix) to info)
        }
        return out.sortedByDescending { it.second.ts }.take(limit)
    }
}
