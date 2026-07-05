package sk.tvhclient.shared.storage

import kotlinx.serialization.json.Json
import sk.tvhclient.shared.model.EpgEvent

/**
 * Cista (platformovo-nezavisla) logika cache EPG: serializacia mapy
 * kanal -> zoznam relacii, orezanie starych dni a zlucenie novych dat.
 * Samotne citanie/zapis suboru robi platformova vrstva (na Androide EpgCache),
 * lebo k suborovemu systemu sa pristupuje cez Context.
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
     * Serializacia JEDNEHO kanala (pole relacii) — pre prudovy zapis po riadkoch.
     * Vysledok je jednoriadkovy JSON (retazce maju \n escapnute), takze sa da
     * bezpecne ulozit ako jeden riadok suboru. Peak pamat = jeden kanal, nie cela
     * mapa (riesi OOM na velkych serveroch, kde cela mapa bola desiatky MB v jednom
     * Stringu). encode()/decode() ostavaju pre spatnu kompatibilitu.
     */
    fun encodeChannel(events: List<EpgEvent>): String =
        json.encodeToString(events)

    fun decodeChannel(raw: String): List<EpgEvent> =
        try {
            json.decodeFromString(raw)
        } catch (e: Exception) {
            emptyList()
        }

    /** Odstrani relacie, ktore skoncili skor ako (nowSec - daysBack), a prazdne kanaly. */
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
     * Zluci cerstve relacie jedneho kanala do mapy: deduplikacia podla casu zaciatku
     * (cerstve prepisu stare), vysledok zoradeny podla startu. Stare relacie, ktore
     * uz server neposiela, ostavaju zachovane (pamat dozadu).
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
