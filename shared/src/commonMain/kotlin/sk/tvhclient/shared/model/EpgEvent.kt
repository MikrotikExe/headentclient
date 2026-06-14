package sk.tvhclient.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * EPG event z api/epg/events/grid. Okrem now/next (channelUuid, cas, title)
 * nesie aj plny popis a metadata pre detail relacie: summary, description,
 * genre (DVB content-type kody), ageRating, cislo epizody.
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
    /** Najlepsi dostupny popis: description, fallback summary. */
    val bestDescription: String
        get() = description.ifBlank { summary }

    /** Top DVB kategoria (horny nibble prveho genre kodu), 0 = neznama. */
    val dvbGenreTop: Int
        get() = genre.firstOrNull()?.let { it / 16 } ?: 0
}
