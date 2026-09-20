package sk.tvhclient.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * EPG event from api/epg/events/grid. Besides now/next (channelUuid, time, title)
 * it also carries the full description and metadata for the programme detail: summary,
 * description, genre (DVB content-type codes), ageRating, episode number.
 */
@Serializable
data class EpgEvent(
    @SerialName("eventId") val eventId: Long? = null,
    @SerialName("channelUuid") val channelUuid: String? = null,
    @SerialName("channelName") val channelName: String = "",
    val start: Long = 0,
    val stop: Long = 0,
    val title: String = "",
    val subtitle: String = "",
    @SerialName("summary") val summary: String = "",
    @SerialName("description") val description: String = "",
    @SerialName("genre") val genre: List<Int> = emptyList(),
    @SerialName("ageRating") val ageRating: Int = 0,
    @SerialName("episodeOnscreen") val episodeOnscreen: String = "",
    @SerialName("nextEventId") val nextEventId: Long? = null
) {
    /** The best available description: description, falling back to summary. */
    val bestDescription: String
        get() = description.ifBlank { summary }

    /** Top-level DVB category (upper nibble of the first genre code), 0 = unknown. */
    val dvbGenreTop: Int
        get() = genre.firstOrNull()?.let { it / 16 } ?: 0
}
