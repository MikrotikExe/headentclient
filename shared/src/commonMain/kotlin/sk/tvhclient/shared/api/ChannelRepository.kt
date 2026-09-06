package sk.tvhclient.shared.api

import sk.tvhclient.shared.model.Channel
import sk.tvhclient.shared.model.ChannelTag
import sk.tvhclient.shared.model.EpgEvent

/**
 * Kanal s now/next EPG a piconom — to co potrebuje UI zoznam.
 */
data class ChannelRow(
    val channel: Channel,
    val piconUrl: String?,
    val nowTitle: String?,
    val nowStart: Long,
    val nowStop: Long
)

/**
 * Skupina kanalov podla tagu (kategoria) pre TV riadky aj mobilny zoznam.
 */
data class ChannelCategory(
    val tag: ChannelTag?,        // null = "Vsetky" / bez tagu
    val rows: List<ChannelRow>
)

/**
 * Nacita kanaly + tagy + now EPG a poskladá kategorie. Cache 60s ako v
 * pluginnom get_channels (ExpiringLRUCache 60s).
 */
class ChannelRepository(
    private val channelsProvider: suspend () -> List<Channel>,
    private val tagsProvider: suspend () -> List<ChannelTag>,
    private val epgNowProvider: suspend () -> Map<String, EpgEvent>,
    private val piconUrlFor: (Channel) -> String?,
    private val nowSec: () -> Long,
    private val cacheTtlSec: Long = 60
) {
    private var cachedChannels: List<Channel>? = null
    private var cachedTags: List<ChannelTag>? = null
    private var cacheTs: Long = 0
    // M586: now/next (HTTP) sa v ramci jedneho nacitania pyta raz — zalozka Radia
    // si pyta rows aj categories, TV zoznam allRows; bez tejto pamate by to boli
    // tri rovnake HTTP dotazy za sebou. Plati rovnaky TTL ako pre kanaly.
    private var cachedEpgNow: Map<String, EpgEvent>? = null
    private var epgNowTs: Long = 0

    private suspend fun epgNow(): Map<String, EpgEvent> {
        val now = nowSec()
        val c = cachedEpgNow
        if (c != null && (now - epgNowTs) < cacheTtlSec) return c
        val fresh = runCatching { epgNowProvider() }.getOrDefault(emptyMap())
        // prazdna odpoved (HTSP) sa nekesuje ako platna — nabuduce sa skusi znova
        if (fresh.isNotEmpty()) { cachedEpgNow = fresh; epgNowTs = now }
        return fresh
    }

    suspend fun load(force: Boolean = false): List<ChannelCategory> {
        val now = nowSec()
        if (force || cachedChannels == null || (now - cacheTs) >= cacheTtlSec) {
            cachedChannels = channelsProvider().filter { it.enabled }
            cachedTags = tagsProvider().filter { it.enabled }.sortedBy { it.index }
            cacheTs = now
            if (force) { cachedEpgNow = null; epgNowTs = 0 }   // M586: rucne obnovenie = cerstve now/next
        }
        val channels = cachedChannels ?: emptyList()
        val tags = cachedTags ?: emptyList()
        val epgNow = epgNow()

        val tagNameOf = tags.associate { it.uuid to it.name }
        fun isRadioCh(ch: Channel): Boolean = isRadioChannel(ch, tagNameOf)
        // TV zoznam = vsetky okrem radia
        val tvChannels = channels.filterNot { isRadioCh(it) }

        fun rowOf(ch: Channel): ChannelRow {
            val ev: EpgEvent? = epgNow[ch.uuid]
            return ChannelRow(
                channel = ch,
                piconUrl = piconUrlFor(ch),
                nowTitle = ev?.title?.ifBlank { null },
                nowStart = ev?.start ?: 0,
                nowStop = ev?.stop ?: 0
            )
        }

        val categories = mutableListOf<ChannelCategory>()
        // Kategorie podla tagov (poradie podla tag.index ako na serveri)
        for (tag in tags) {
            val rows = tvChannels
                .filter { tag.uuid in it.tags }
                .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                .map(::rowOf)
            if (rows.isNotEmpty()) categories.add(ChannelCategory(tag, rows))
        }
        // Kanaly bez tagu — do "Ostatne", aby sa nestratili
        val tagged = tvChannels.filter { it.tags.isNotEmpty() }.map { it.uuid }.toSet()
        val untagged = tvChannels.filterNot { it.uuid in tagged }
        if (untagged.isNotEmpty()) {
            categories.add(ChannelCategory(
                tag = null,
                rows = untagged
                    .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                    .map(::rowOf)
            ))
        }
        return categories
    }

    /** Plochy zoznam vsetkych TV kanalov (pre vyhladavanie), bez radia. */
    suspend fun allRows(force: Boolean = false): List<ChannelRow> {
        load(force)
        val channels = cachedChannels ?: emptyList()
        val tags = cachedTags ?: emptyList()
        val tagNameOf = tags.associate { it.uuid to it.name }
        val epgNow = epgNow()
        return channels
            .filterNot { isRadioChannel(it, tagNameOf) }
            .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            .map { ch ->
                val ev = epgNow[ch.uuid]
                ChannelRow(ch, piconUrlFor(ch), ev?.title?.ifBlank { null },
                    ev?.start ?: 0, ev?.stop ?: 0)
            }
    }

    /**
     * M504: je kanal radio?
     *
     * Prednost ma TYP SLUZBY z DVB tabuliek (rovnako to urcuje Kodi) — je to
     * udaj od servera, nezavisly od toho, ako si kto pomenoval tagy. Az ked
     * server typy neposkytne (starsi TVH, IPTV bez service info, obmedzene
     * prava), pouzije sa zaloha podla nazvov tagov.
     */
    private fun isRadioChannel(ch: Channel, tagNameOf: Map<String, String>): Boolean =
        ch.isRadioByService
            ?: sk.tvhclient.shared.model.RadioDetector.isRadio(
                ch.tags.mapNotNull { tagNameOf[it] }
            )

    /**
     * M505: radio kanaly rozdelene do kategorii podla tagov — Radio zalozka tak
     * moze filtrovat rovnako ako Kanaly. Kanaly bez tagu idu do kategorie s
     * tag = null, aby sa nestratili (rovnako ako pri TV).
     */
    suspend fun radioCategories(force: Boolean = false): List<ChannelCategory> {
        load(force)
        val channels = cachedChannels ?: emptyList()
        val tags = cachedTags ?: emptyList()
        val tagNameOf = tags.associate { it.uuid to it.name }
        val radio = channels.filter { isRadioChannel(it, tagNameOf) }
        if (radio.isEmpty()) return emptyList()

        val epgNow = epgNow()   // M586
        fun rowOf(ch: Channel): ChannelRow {
            val ev = epgNow[ch.uuid]
            return ChannelRow(ch, piconUrlFor(ch), ev?.title?.ifBlank { null },
                ev?.start ?: 0, ev?.stop ?: 0)
        }
        val byNumber = compareBy<Channel>({ it.number ?: Int.MAX_VALUE }, { it.name.lowercase() })

        val out = mutableListOf<ChannelCategory>()
        for (tag in tags) {
            val rows = radio.filter { tag.uuid in it.tags }.sortedWith(byNumber).map(::rowOf)
            if (rows.isNotEmpty()) out.add(ChannelCategory(tag, rows))
        }
        val untagged = radio.filter { ch -> tags.none { it.uuid in ch.tags } }
        if (untagged.isNotEmpty()) {
            out.add(ChannelCategory(null, untagged.sortedWith(byNumber).map(::rowOf)))
        }
        return out
    }

    /** Zoznam radio kanalov (pre Radio zalozku). */
    suspend fun radioRows(force: Boolean = false): List<ChannelRow> {
        load(force)
        val channels = cachedChannels ?: emptyList()
        val tags = cachedTags ?: emptyList()
        val tagNameOf = tags.associate { it.uuid to it.name }
        // M586: rovnako ako allRows doplni „prave hra" (HTTP: now/next pride v dumpe
        // kanalov). Doteraz radio dostavalo vzdy null, takze zalozka Radia aj zoznam
        // v prehravaci ostali bez programu.
        val epgNow = epgNow()
        return channels
            .filter { isRadioChannel(it, tagNameOf) }
            .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.name.lowercase() }))
            .map { ch ->
                val ev = epgNow[ch.uuid]
                ChannelRow(ch, piconUrlFor(ch), ev?.title?.ifBlank { null },
                    ev?.start ?: 0, ev?.stop ?: 0)
            }
    }
}
