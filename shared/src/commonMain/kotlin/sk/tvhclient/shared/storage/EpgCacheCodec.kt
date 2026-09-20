package sk.tvhclient.shared.storage

import kotlinx.serialization.json.Json
import sk.tvhclient.shared.model.EpgEvent

/**
 * Pure (platform-independent) EPG cache logic: serialization of the
 * channel -> list of programmes map, trimming of old days and merging of new data.
 * The actual file reading/writing is done by the platform layer (EpgCache on Android),
 * because the file system is accessed through the Context.
 */
object EpgCacheCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(data: Map<String, List<EpgEvent>>): String =
        json.encodeToString(data)

    fun decode(raw: String): Map<String, List<EpgEvent>> =
        try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyMap()
        }

    /**
     * Serialization of a SINGLE channel (its array of programmes) — for a streaming,
     * line-by-line write. The result is single-line JSON (strings have \n escaped), so it
     * can safely be stored as one line of the file. Peak memory = one channel, not the whole
     * map (this fixes the OOM on large servers, where the whole map was tens of MB in a single
     * String). encode()/decode() remain for backward compatibility.
     */
    fun encodeChannel(events: List<EpgEvent>): String =
        json.encodeToString(events)

    fun decodeChannel(raw: String): List<EpgEvent> =
        try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }

    /** Removes programmes that ended earlier than (nowSec - daysBack), and empty channels. */
    fun prune(
        data: Map<String, List<EpgEvent>>,
        nowSec: Long,
        daysBack: Int
    ): Map<String, List<EpgEvent>> {
        val cutoff = nowSec - daysBack.toLong() * 86400L
        val out = LinkedHashMap<String, List<EpgEvent>>()
        for ((uuid, evs) in data) {
            val kept = evs.filter { it.stop >= cutoff }
            if (kept.isNotEmpty()) out[uuid] = kept
        }
        return out
    }

    /**
     * Merges fresh programmes for a single channel into the map: deduplication by start time
     * (fresh ones overwrite old ones), the result sorted by start. Old programmes that the
     * server no longer sends are kept (memory of the past).
     */
    fun mergeChannel(
        base: Map<String, List<EpgEvent>>,
        uuid: String,
        fresh: List<EpgEvent>
    ): Map<String, List<EpgEvent>> {
        if (fresh.isEmpty()) return base
        val byStart = LinkedHashMap<Long, EpgEvent>()
        base[uuid]?.forEach { byStart[it.start] = it }
        fresh.forEach { byStart[it.start] = it }
        val merged = byStart.values.sortedBy { it.start }
        return base + (uuid to merged)
    }
}
