package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import org.videolan.libvlc.MediaPlayer

/**
 * M665: the state of the DVR recording being played (extracted from PlayerActivity) — identity (uuid, server),
 * length, programme boundaries for a recording in progress, playhead clock, seed after a seek,
 * the end-reached flag — and saving watch progress ([saveProgress], WatchProgress).
 * A data holder; the activity accesses the fields via delegates with the original names.
 */
internal class DvrPlayback(private val ctx: Context) {
    var uuid: String? = null
    var serverId: String? = null
    var durationMs: Long = 0
    // Programme in progress: we extrapolate the length relative to the start of the PROGRAMME (not the file),
    // capped by the programme length. The seekbar thus shows the elapsed part of the programme, not the whole archive.
    var recording = false
    var progStartSec: Long = 0
    var progStopSec: Long = 0
    var realStartSec: Long = 0
    val durationState = mutableStateOf(0L)
    var reachedEnd = false
    // The playhead in programme time (ms) mirrored from the wall-clock playback clock in PlayerUi -
    // a reliable source for reopening the stream (player.time is unstable for a growing TS).
    val playheadMsState = mutableStateOf(0L)
    // After a DVR seek: the target (programme-relative) time that the playhead clock should adopt.
    // -1 = no pending seek. With feeder/pipe, player.position is invalid after a restart,
    // so the clock cannot resync from the position - the seed gives it the right starting point.
    val seekSeedState = mutableStateOf(-1L)

    /** Saves the position (or "watched to the end"); [player] = a live player or null (M535 already released). */
    fun saveProgress(player: MediaPlayer?) {
        val uuid = uuid ?: return
        val sid = serverId ?: return
        val mp = player ?: return   // M535: the player may already be released
        val dur = if (durationMs > 0) durationMs else mp.length
        if (dur <= 0) return
        if (reachedEnd && !recording) {
            WatchProgress.markCompleted(ctx, sid, uuid, dur)
            return
        }
        // The programme-relative time from the playhead clock is the only reliable source:
        // after a seek the stream restarts (:start-time) and mediaPlayer.position
        // is relative to the NEW stream — nonsensically small positions were being stored,
        // watch progress was lost and the 95% "watched" threshold was never reached.
        val playheadMs = playheadMsState.value
        val posMs = if (playheadMs > 0) {
            playheadMs.coerceAtMost(dur)
        } else {
            val pos = mp.position
            // do not overwrite a good position with zero (e.g. when the media has not loaded yet)
            if (pos > 0.001f && pos <= 1f) (pos * dur).toLong() else return
        }
        if (posMs > 0) WatchProgress.save(ctx, sid, uuid, posMs, dur)
    }
}
