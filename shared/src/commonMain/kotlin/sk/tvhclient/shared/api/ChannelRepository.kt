package sk.tvhclient.shared.api

import sk.tvhclient.shared.model.Channel
import sk.tvhclient.shared.model.ChannelTag
import sk.tvhclient.shared.model.EpgEvent

/**
 * Channel with now/next EPG and picon — what the UI list needs.
 */
data class ChannelRow(
    val channel: Channel,
    val piconUrl: String?,
    val nowTitle: String?,
    val nowStart: Long,
    val nowStop: Long
)

/**
 * Group of channels by tag (category) for the TV rows and the mobile list.
 */
data class ChannelCategory(
    val tag: ChannelTag?,        // null = "All" / no tag
    val rows: List<ChannelRow>
)

/**
 * Loads channels + tags + now EPG and assembles the categories. Cache 60s as in
 * the plugin's get_channels (ExpiringLRUCache 60s).
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
    // M586: now/next (HTTP) is asked for only once within a single load — the Radio tab
    // asks for both rows and categories, the TV list for allRows; without this memory that
    // would be three identical HTTP queries in a row. The same TTL applies as for channels.
    private var cachedEpgNow: Map<String, EpgEvent>? = null
    private var epgNowTs: Long = 0

    private suspend fun epgNow(): Map<String, EpgEvent> {
        val now = nowSec()
        val c = cachedEpgNow
        if (c != null && (now - epgNowTs) < cacheTtlSec) return c
        val fresh = runCatching { epgNowProvider() }.getOrDefault(emptyMap())
        // an empty response (HTSP) is not cached as valid — next time it will be tried again
        if (fresh.isNotEmpty()) { cachedEpgNow = fresh; epgNowTs = now }
        return fresh
    }

    suspend fun load(force: Boolean = false): List<ChannelCategory> {
        val now = nowSec()
        if (force || cachedChannels == null || (now - cacheTs) >= cacheTtlSec) {
            cachedChannels = channelsProvider().filter { it.enabled }
            cachedTags = tagsProvider().filter { it.enabled }.sortedBy { it.index }
            cacheTs = now
            if (force) { cachedEpgNow = null; epgNowTs = 0 }   // M586: manual refresh = fresh now/next
        }
        val channels = cachedChannels ?: emptyList()
        val tags = cachedTags ?: emptyList()
        val epgNow = epgNow()

        val tagNameOf = tags.associate { it.uuid to it.name }
        fun isRadioCh(ch: Channel): Boolean = isRadioChannel(ch, tagNameOf)
        // TV list = everything except radio
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
        // Categories by tags (order by tag.index, as on the server)
        for (tag in tags) {
            val rows = tvChannels
                .filter { tag.uuid in it.tags }
                .sortedWith(compareBy({ it.number ?: Int.MAX_VALUE }, { it.name.lowercase() }))
                .map(::rowOf)
            if (rows.isNotEmpty()) categories.add(ChannelCategory(tag, rows))
        }
        // Channels without a tag — into "Other", so they don't get lost
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

    /** Flat list of all TV channels (for searching), without radio. */
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
     * M504: is the channel a radio?
     *
     * The SERVICE TYPE from the DVB tables takes precedence (Kodi determines it the
     * same way) — it is data from the server, independent of how anyone named their
     * tags. Only when the server does not provide the types (older TVH, IPTV without
     * service info, limited rights) is the fallback by tag names used.
     */
    private fun isRadioChannel(ch: Channel, tagNameOf: Map<String, String>): Boolean =
        ch.isRadioByService
            ?: sk.tvhclient.shared.model.RadioDetector.isRadio(
                ch.tags.mapNotNull { tagNameOf[it] }
            )

    /**
     * M505: radio channels split into categories by tags — the Radio tab can thus
     * filter the same way as Channels. Channels without a tag go into the category with
     * tag = null, so they don't get lost (the same as for TV).
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

    /** List of radio channels (for the Radio tab). */
    suspend fun radioRows(force: Boolean = false): List<ChannelRow> {
        load(force)
        val channels = cachedChannels ?: emptyList()
        val tags = cachedTags ?: emptyList()
        val tagNameOf = tags.associate { it.uuid to it.name }
        // M586: just like allRows it fills in "now playing" (HTTP: now/next comes in the dump
        // of channels). Until now radio always got null, so the Radio tab and the list
        // in the player were left without a programme.
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
