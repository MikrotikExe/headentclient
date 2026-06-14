package sk.tvhclient.android

import android.content.Context

/**
 * Sledovanie pozicie prehravania DVR/archiv relacii. Pre kazdu polozku (podla
 * serverId + uuid) drzi poziciu, dlzku, priznak dopozerane a cas posledneho
 * sledovania. Ulozene v SharedPreferences ako "posMs|durMs|completed|ts".
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

    fun get(context: Context, serverId: String, uuid: String): Info? {
        if (uuid.isBlank()) return null
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key(serverId, uuid), null) ?: return null
        return parse(raw)
    }

    fun save(context: Context, serverId: String, uuid: String, posMs: Long, durMs: Long) {
        if (uuid.isBlank()) return
        val completed = durMs > 0 && posMs >= (durMs * COMPLETE_FRACTION).toLong()
        val v = "$posMs|$durMs|${if (completed) "1" else "0"}|${System.currentTimeMillis()}"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(key(serverId, uuid), v).apply()
    }

    /** Oznac ako cele dopozerane (napr. pri EndReached). */
    fun markCompleted(context: Context, serverId: String, uuid: String, durMs: Long) {
        if (uuid.isBlank()) return
        val d = if (durMs > 0) durMs else 1
        save(context, serverId, uuid, d, d)
    }

    /** uuid -> Info pre dany server, zoradene od najnovsie sledovaneho. */
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
