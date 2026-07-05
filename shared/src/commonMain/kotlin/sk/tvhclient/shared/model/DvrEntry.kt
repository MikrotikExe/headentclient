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
    // UUID kanala pre presne parovanie indikatora nahravania na konkretny kanal
    // (HTTP grid: pole "channel" = hex uuid; HTSP: channelId ako string).
    // Nutne pri serveroch s duplicitnymi nazvami/LCN (napr. regionalne ITV1 HD).
    @SerialName("channel") val channelUuid: String = "",
    val start: Long = 0,
    val stop: Long = 0,
    // Skutocne hranice suboru (vratane okraja pred/po). HTTP grid ich dava
    // priamo (start_real/stop_real), HTSP ich dopocita z okraja (start_extra).
    @SerialName("start_real") val startReal: Long = 0,
    @SerialName("stop_real") val stopReal: Long = 0,
    // Okraj nahravania v minutach (pred/po) — fallback ked nie su *_real.
    @SerialName("start_extra") val startExtra: Long = 0,
    @SerialName("stop_extra") val stopExtra: Long = 0,
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

    /** Skutocny zaciatok nahravaneho suboru (vratane okraja pred relaciou). */
    val realStartSec: Long get() = when {
        startReal in 1 until start -> startReal
        startExtra > 0 && start > 0 -> start - startExtra * 60
        else -> start
    }

    /** Skutocny koniec nahravaneho suboru (vratane okraja po relacii). */
    val realStopSec: Long get() = when {
        stopReal > stop -> stopReal
        stopExtra > 0 && stop > 0 -> stop + stopExtra * 60
        else -> stop
    }

    /** Dlzka skutocneho suboru v sekundach (s okrajmi), fallback na trvanie relacie. */
    val realLengthSec: Long
        get() = (realStopSec - realStartSec).let { if (it > 0) it else durationSec }

    /** Pozicia (0..1) zaciatku relacie v subore — kde sa koreci okraj pred. 0 = bez okraja. */
    val programStartFraction: Float get() {
        val len = realStopSec - realStartSec
        val off = start - realStartSec
        return if (len > 0 && off > 0) (off.toFloat() / len).coerceIn(0f, 1f) else 0f
    }

    /** Pozicia (0..1) konca relacie v subore — kde zacina okraj po. 1 = bez okraja. */
    val programStopFraction: Float get() {
        val len = realStopSec - realStartSec
        val off = stop - realStartSec
        return if (len > 0 && off in 1 until len) (off.toFloat() / len).coerceIn(0f, 1f) else 1f
    }

    /** DVB top nibble z content_type pre klasifikator.
     *  HTTP API (grid_finished) vracia uz horny nibble (0-11), HTSP vracia
     *  plny DVB bajt (major<<4 | minor). Normalizujeme oboje: <=15 je uz
     *  nibble, vacsie je plny bajt -> /16. */
    val dvbGenreTop: Int get() = when {
        contentType <= 0 -> 0
        contentType <= 15 -> contentType
        else -> contentType / 16
    }
}
