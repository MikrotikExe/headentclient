package sk.tvhclient.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * EPG event from api/epg/events/grid. Besides now/next (channelUuid, time, title)
 * it also carries the full description and metadata for the programme detail: summary,
 * description, genre (DVB content-type codes), ageRating, episode number.
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
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
    @SerialName("nextEventId") val nextEventId: Long? = null,
    /**
     * M704: the EPG series link (Tvheadend `serieslinkUri`, both HTSP and the HTTP grid). A "Record
     * series" rule with it matches every episode of the series exactly, whatever the titles look
     * like. Not written to the disk cache when empty (most events have none).
     */
    @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.NEVER)
    @SerialName("serieslinkUri") val serieslinkUri: String = "",
    /**
     * M715: the server still holds this programme as the running one after its scheduled end
     * (EIT running status, epg.c: ch_epg_now is not moved on while the event is running — e.g.
     * a match with extra time). Only HTSP tells it (the channel's eventId); not stored on disk.
     */
    @kotlinx.serialization.Transient val running: Boolean = false,
    /** M715: when [running] was learnt (epoch s) — an old copy in a cache must not keep it "now". */
    @kotlinx.serialization.Transient val runningAt: Long = 0
) {
    /**
     * M715: is this the programme on air at [now]? By time, or an overrunning programme the
     * server still reports as running (at most [MAX_OVERRUN_SEC] past its end, against a
     * running flag that never gets cleared).
     */
    fun isCurrentAt(now: Long): Boolean =
        start <= now && (now < stop ||
            (running && now < stop + MAX_OVERRUN_SEC && now - runningAt < RUNNING_VALID_SEC))

    /** The best available description: description, falling back to summary. */
    val bestDescription: String
        get() = description.ifBlank { summary }

    /** Top-level DVB category (upper nibble of the first genre code), 0 = unknown. */
    val dvbGenreTop: Int
        get() = genre.firstOrNull()?.let { it / 16 } ?: 0

    companion object {
        /** M715: how long an overrunning programme may stay "now" after its scheduled end. */
        const val MAX_OVERRUN_SEC = 3 * 3600L
        /** M715: how long a learnt "still running" holds without a fresh answer from the server. */
        const val RUNNING_VALID_SEC = 15 * 60L
        /** M715: the server moves its "now" at the stop time by ITS clock — a small clock skew is not an overrun. */
        const val OVERRUN_MIN_SEC = 60L
    }
}
