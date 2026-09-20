package sk.tvhclient.shared.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * DVR recording from api/dvr/entry/grid_finished / grid_upcoming.
 * Fields taken from the Enigma2 plugin (dvr.py / classifier.py):
 *  disp_title/disp_subtitle/disp_description, channelname, start/stop,
 *  duration, filesize, status, content_type (DVB genre for the classifier),
 *  uuid (for dvrfile/<uuid> and delete/stop).
 */
@Serializable
data class DvrEntry(
    val uuid: String = "",
    @SerialName("disp_title") val dispTitle: String = "",
    @SerialName("disp_subtitle") val dispSubtitle: String = "",
    @SerialName("disp_description") val dispDescription: String = "",
    @SerialName("channelname") val channelName: String = "",
    // Channel UUID for matching the recording indicator precisely to a specific channel
    // (HTTP grid: the "channel" field = hex uuid; HTSP: channelId as a string).
    // Necessary on servers with duplicate names/LCNs (e.g. regional ITV1 HD).
    @SerialName("channel") val channelUuid: String = "",
    val start: Long = 0,
    val stop: Long = 0,
    // The real file boundaries (including the padding before/after). The HTTP grid gives them
    // directly (start_real/stop_real), HTSP computes them from the padding (start_extra).
    @SerialName("start_real") val startReal: Long = 0,
    @SerialName("stop_real") val stopReal: Long = 0,
    // Recording padding in minutes (before/after) — a fallback when *_real is missing.
    @SerialName("start_extra") val startExtra: Long = 0,
    @SerialName("stop_extra") val stopExtra: Long = 0,
    @SerialName("duration") val duration: Long = 0,
    @SerialName("filesize") val fileSize: Long = 0,
    @SerialName("status") val status: String = "",
    @SerialName("sched_status") val schedStatus: String = "",
    @SerialName("content_type") val contentType: Int = 0,
    @SerialName("errors") val errors: Int = 0,
    // M483: HTSP commands (cancelDvrEntry/deleteDvrEntry) take a NUMERIC id, while
    // /dvrfile needs the hex uuid for playback. With HTSP we therefore keep both.
    // HTTP does not have this field — there both the commands and playback go through the uuid.
    val dvrId: String = ""
) {
    val title: String get() = dispTitle.ifBlank { "—" }

    /** M483: id for DVR commands (cancel/delete). HTSP = numeric id, HTTP = uuid. */
    val commandId: String get() = dvrId.ifBlank { uuid }

    /**
     * M485: is it recording right now?
     *
     * HTSP sends the state in `state`, HTTP in `status`/`sched_status`, and dev builds
     * of TVH also use "Running" — I accept all the variants so that the programme detail
     * can tell a running recording apart from a scheduled one.
     */
    val isRecordingNow: Boolean get() =
        status.equals("recording", ignoreCase = true) ||
            status.equals("running", ignoreCase = true) ||
            schedStatus.equals("recording", ignoreCase = true)

    val durationSec: Long
        get() = if (duration > 0) duration else (if (stop > start) stop - start else 0)

    /** The real start of the recorded file (including the padding before the programme). */
    val realStartSec: Long get() = when {
        startReal in 1 until start -> startReal
        startExtra > 0 && start > 0 -> start - startExtra * 60
        else -> start
    }

    /** The real end of the recorded file (including the padding after the programme). */
    val realStopSec: Long get() = when {
        stopReal > stop -> stopReal
        stopExtra > 0 && stop > 0 -> stop + stopExtra * 60
        else -> stop
    }

    /** Length of the real file in seconds (with the padding), falling back to the programme duration. */
    val realLengthSec: Long
        get() = (realStopSec - realStartSec).let { if (it > 0) it else durationSec }

    /** Position (0..1) of the programme start in the file — where the leading padding ends. 0 = no padding. */
    val programStartFraction: Float get() {
        val len = realStopSec - realStartSec
        val off = start - realStartSec
        return if (len > 0 && off > 0) (off.toFloat() / len).coerceIn(0f, 1f) else 0f
    }

    /** Position (0..1) of the programme end in the file — where the trailing padding begins. 1 = no padding. */
    val programStopFraction: Float get() {
        val len = realStopSec - realStartSec
        val off = stop - realStartSec
        return if (len > 0 && off in 1 until len) (off.toFloat() / len).coerceIn(0f, 1f) else 1f
    }

    /** DVB top nibble from content_type for the classifier.
     *  The HTTP API (grid_finished) already returns the upper nibble (0-11), HTSP returns
     *  the full DVB byte (major<<4 | minor). We normalize both: <=15 is already a
     *  nibble, anything larger is the full byte -> /16. */
    val dvbGenreTop: Int get() = when {
        contentType <= 0 -> 0
        contentType <= 15 -> contentType
        else -> contentType / 16
    }
}
