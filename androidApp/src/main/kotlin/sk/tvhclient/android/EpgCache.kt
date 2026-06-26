package sk.tvhclient.android

import android.content.Context
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.storage.EpgCacheCodec
import java.io.File

/**
 * Diskovy cache EPG na Androide. Per server jeden JSON subor v internom ulozisku.
 * Tvheadend stare relacie z EPG postupne maze; tymto si appka pamata uplynule dni
 * (kolko presne urcuje EpgRangePref.daysBack).
 */
object EpgCache {

    private fun file(ctx: Context, serverId: String, kind: String = ""): File {
        val dir = File(ctx.filesDir, "epg_cache")
        if (!dir.exists()) dir.mkdirs()
        val suffix = if (kind.isEmpty()) "" else "_$kind"
        return File(dir, "epg_${serverId}$suffix.json")
    }

    fun load(ctx: Context, serverId: String, nowSec: Long, daysBack: Int): Map<String, List<EpgEvent>> {
        return try {
            val f = file(ctx, serverId)
            if (!f.exists()) emptyMap()
            else EpgCacheCodec.prune(EpgCacheCodec.decode(f.readText()), nowSec, daysBack)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun save(ctx: Context, serverId: String, data: Map<String, List<EpgEvent>>, nowSec: Long, daysBack: Int) {
        try {
            val pruned = EpgCacheCodec.prune(data, nowSec, daysBack)
            file(ctx, serverId).writeText(EpgCacheCodec.encode(pruned))
        } catch (e: Exception) {
            // cache je len optimalizacia — zlyhanie zapisu ignorujeme
        }
    }

    // M275: samostatny „live" cache pre prehravac (now/next + detail), aby neprepisoval
    // bohatsiu cache EPG mriezky (ta pouziva zakladny subor bez suffixu).
    fun loadLive(ctx: Context, serverId: String, nowSec: Long, daysBack: Int): Map<String, List<EpgEvent>> {
        return try {
            val f = file(ctx, serverId, "live")
            if (!f.exists()) emptyMap()
            else EpgCacheCodec.prune(EpgCacheCodec.decode(f.readText()), nowSec, daysBack)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun saveLive(ctx: Context, serverId: String, data: Map<String, List<EpgEvent>>, nowSec: Long, daysBack: Int) {
        try {
            val pruned = EpgCacheCodec.prune(data, nowSec, daysBack)
            file(ctx, serverId, "live").writeText(EpgCacheCodec.encode(pruned))
        } catch (e: Exception) {
        }
    }

    /** M275: cas posledneho ulozenia live cache (lastModified suboru), 0 ak neexistuje. */
    fun lastSavedLive(ctx: Context, serverId: String): Long =
        try {
            val f = file(ctx, serverId, "live")
            if (f.exists()) f.lastModified() else 0L
        } catch (e: Exception) { 0L }

    /** M275: zmazanie live EPG cache servera (pre rucne „Obnovit" v nastaveniach). */
    fun clearLive(ctx: Context, serverId: String) {
        try { file(ctx, serverId, "live").delete() } catch (e: Exception) {}
    }
}
