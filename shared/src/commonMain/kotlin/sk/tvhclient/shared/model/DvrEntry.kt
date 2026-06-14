package sk.tvhclient.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DVR nahravka z api/dvr/entry/grid_finished / grid_upcoming.
 * Polia prebrate z Enigma2 pluginu (dvr.py / classifier.py):
 *  disp_title/disp_subtitle/disp_description, channelname, start/stop,
 *  duration, filesize, status, content_type (DVB genre pre klasifikator),
 *  uuid (pre dvrfile/<uuid> a delete/stop).
 */
@Serializable
data class DvrEntry(
    val uuid: String = "",
    @SerialName("disp_title") val dispTitle: String = "",
    @SerialName("disp_subtitle") val dispSubtitle: String = "",
    @SerialName("disp_description") val dispDescription: String = "",
    @SerialName("channelname") val channelName: String = "",
    val start: Long = 0,
    val stop: Long = 0,
    @SerialName("duration") val duration: Long = 0,
    @SerialName("filesize") val fileSize: Long = 0,
    @SerialName("status") val status: String = "",
    @SerialName("sched_status") val schedStatus: String = "",
    @SerialName("content_type") val contentType: Int = 0,
    @SerialName("errors") val errors: Int = 0
) {
    val title: String get() = dispTitle.ifBlank { "—" }

    val durationSec: Long
        get() = if (duration > 0) duration else (if (stop > start) stop - start else 0)

    /** DVB top nibble z content_type pre klasifikator. */
    val dvbGenreTop: Int get() = if (contentType > 0) contentType / 16 else 0
}
