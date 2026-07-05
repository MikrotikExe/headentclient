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

    /**
     * Prudovy zapis cache po riadkoch: kazdy kanal = jeden riadok "uuid\t<json pola relacii>".
     * Peak pamat = najvacsi jeden kanal, nie cela mapa (predtym encode() skladal jeden
     * obrovsky String cez celu mapu -> OutOfMemoryError na velkych serveroch, ~96 MB).
     * Zapis cez .tmp + rename, aby pri zlyhani neostal poskodeny subor.
     */
    private fun writeStreamed(f: File, data: Map<String, List<EpgEvent>>) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((uuid, evs) in data) {
                // uuid je hex/ciselny identifikator kanala — bez tab/newline, bezpecne v riadku
                w.write(uuid)
                w.write("\t")
                w.write(EpgCacheCodec.encodeChannel(evs))   // jednoriadkovy JSON
                w.write("\n")
            }
        }
        f.delete()
        if (!tmp.renameTo(f)) { tmp.copyTo(f, overwrite = true); tmp.delete() }
    }

    /** Prudove citanie cache po riadkoch — peak pamat = jeden kanal. */
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
            // cache je len optimalizacia — zlyhanie zapisu (vratane OOM) ignorujeme, nesmie zhodit appku
        }
    }

    // M275: samostatny „live" cache pre prehravac (now/next + detail), aby neprepisoval
    // bohatsiu cache EPG mriezky (ta pouziva zakladny subor bez suffixu).
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
