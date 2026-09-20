package sk.tvhclient.android

import android.content.Context
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.storage.EpgCacheCodec
import java.io.File

/**
 * The on-disk EPG cache on Android. One JSON file per server in internal storage.
 * Tvheadend gradually deletes old programmes from the EPG; with this the app remembers past days
 * (exactly how many is determined by EpgRangePref.daysBack).
 */
object EpgCache {

    private fun file(ctx: Context, serverId: String, kind: String = ""): File {
        val dir = File(ctx.filesDir, "epg_cache")
        if (!dir.exists()) dir.mkdirs()
        val suffix = if (kind.isEmpty()) "" else "_$kind"
        return File(dir, "epg_${serverId}$suffix.json")
    }

    /**
     * Streaming write of the cache line by line: each channel = one line "uuid\t<json array of programmes>".
     * Peak memory = the largest single channel, not the whole map (previously encode() assembled one
     * huge String over the whole map -> OutOfMemoryError on large servers, ~96 MB).
     * Written via .tmp + rename, so that no corrupted file is left behind on failure.
     */
    private fun writeStreamed(f: File, data: Map<String, List<EpgEvent>>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((uuid, evs) in data) {
                // the uuid is a hex/numeric channel identifier — no tab/newline, safe within a line
                w.write(uuid)
                w.write("\t")
                w.write(EpgCacheCodec.encodeChannel(evs))   // single-line JSON
                w.write("\n")
            }
        }
        f.delete()
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    /** Streaming read of the cache line by line — peak memory = one channel. */
    private fun readStreamed(f: File): Map<String, List<EpgEvent>> {
        val out = LinkedHashMap<String, List<EpgEvent>>()
        f.bufferedReader().useLines { lines ->
            for (line in lines) {
                val t = line.indexOf('\t')
                if (t <= 0) continue
                val evs = EpgCacheCodec.decodeChannel(line.substring(t + 1))
                if (evs.isNotEmpty()) out[line.substring(0, t)] = evs
            }
        }
        return out
    }

    fun load(ctx: Context, serverId: String, nowSec: Long, daysBack: Int): Map<String, List<EpgEvent>> {
        return try {
            val f = file(ctx, serverId)
            if (!f.exists()) emptyMap()
            else EpgCacheCodec.prune(readStreamed(f), nowSec, daysBack)
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    fun save(ctx: Context, serverId: String, data: Map<String, List<EpgEvent>>, nowSec: Long, daysBack: Int) {
        try {
            val pruned = EpgCacheCodec.prune(data, nowSec, daysBack)
            writeStreamed(file(ctx, serverId), pruned)
        } catch (e: Throwable) {
            // the cache is only an optimisation — a write failure (including OOM) is ignored, it must not bring the app down
        }
    }

    // M275: a separate "live" cache for the player (now/next + detail), so that it does not overwrite
    // the richer EPG grid cache (that one uses the base file without a suffix).
    fun loadLive(ctx: Context, serverId: String, nowSec: Long, daysBack: Int): Map<String, List<EpgEvent>> {
        return try {
            val f = file(ctx, serverId, "live")
            if (!f.exists()) emptyMap()
            else EpgCacheCodec.prune(readStreamed(f), nowSec, daysBack)
        } catch (e: Throwable) {
            emptyMap()
        }
    }

    fun saveLive(ctx: Context, serverId: String, data: Map<String, List<EpgEvent>>, nowSec: Long, daysBack: Int) {
        try {
            val pruned = EpgCacheCodec.prune(data, nowSec, daysBack)
            writeStreamed(file(ctx, serverId, "live"), pruned)
        } catch (e: Throwable) {
        }
    }

    /** M275: the time of the last save of the live cache (the file's lastModified), 0 if it does not exist. */
    fun lastSavedLive(ctx: Context, serverId: String): Long =
        try {
            val f = file(ctx, serverId, "live")
            if (f.exists()) f.lastModified() else 0L
        } catch (e: Exception) { 0L }

    /** M275: deleting the server's live EPG cache (for the manual "Refresh" in settings). */
    fun clearLive(ctx: Context, serverId: String) {
        try { file(ctx, serverId, "live").delete() } catch (e: Exception) {}
    }

    /** M391: complete deletion of the server's EPG cache (both grid and live) — after a change
     *  of the connection method the channel identifiers are invalid. */
    fun clearAll(ctx: Context, serverId: String) {
        try { file(ctx, serverId).delete() } catch (e: Exception) {}
        clearLive(ctx, serverId)
    }
}
