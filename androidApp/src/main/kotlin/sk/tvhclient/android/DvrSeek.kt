package sk.tvhclient.android

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M594 / M648: computing the seek target within a recording and the helper logic around it, extracted
 * from PlayerActivity. The actual REBUILDING of the stream (a new Media with :start-time or a restart
 * of the feeder at a byte offset) stays in the activity ([performSeek]) — it touches the URL, the feeder, the player.
 *
 * - Target: the playhead of the playback clock (player.position is unstable both for a growing TS and for a pipe),
 *   margin from the end: in-progress 45 s (the written data lags behind), finished 5 s (the file tends to be
 *   shorter than the duration from the EPG, M594). Jumps under 1 s are ignored.
 * - M594 recovery: an error/end within 15 s after a seek = we hit EOF → back off by 30 s (max 2×).
 * - Double-click (YouTube-style): the ±10 s hint accumulates, the seek happens once ~0.45 s after the last click.
 */
internal class DvrSeek(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val durMs: () -> Long,
    private val recording: () -> Boolean,
    private val playheadMs: () -> Long,
    private val seekable: () -> Boolean,
    private val performSeek: (targetMs: Long, fromMs: Long, dur: Long) -> Unit
) {
    /** The ±s hint on a double-click (0 = hidden); read by SeekHintOverlay. */
    val hint = mutableStateOf(0)
    private var hintJob: Job? = null
    private var accumBaseMs = -1L
    private var commitJob: Job? = null

    private var lastSeekAtMs = 0L
    private var lastSeekTargetMs = 0L
    private var recoverTries = 0

    private fun maxMs(dur: Long): Long =
        if (recording()) (dur - 45_000L).coerceAtLeast(0L) else (dur - 5_000L).coerceAtLeast(0L)

    /** Called by the activity after every real seek (seekDvrTo) — for the M594 recovery. */
    fun markSeek(targetMs: Long) {
        lastSeekAtMs = SystemClock.elapsedRealtime()
        lastSeekTargetMs = targetMs
    }

    /** Playing has arrived → the seek succeeded, reset the recovery attempts. */
    fun onPlaying() { recoverTries = 0 }

    private fun justSeeked(): Boolean =
        lastSeekAtMs > 0L && SystemClock.elapsedRealtime() - lastSeekAtMs < 15_000L

    /** Returns true if, after a seek, we managed to back off and try again. */
    fun recoverAfterSeek(): Boolean {
        if (!seekable() || recording() || !justSeeked() || recoverTries >= 2) return false
        recoverTries++
        val back = (lastSeekTargetMs - 30_000L * recoverTries).coerceAtLeast(0L)
        CrashLogger.report(ctx, "PlayerActivity.dvrSeek", "seek past end of file, retry #$recoverTries at ${back / 1000}s")
        seekAbsolute(back)
        return true
    }

    /** An absolute seek to a programme-relative time (bottom bar / D-pad / cursor). */
    fun seekAbsolute(targetMs: Long) {
        if (!seekable()) return
        val dur = durMs()
        if (dur <= 0) return
        val curMs = playheadMs().coerceIn(0L, dur)
        val tgt = targetMs.coerceIn(0L, maxMs(dur))
        if (kotlin.math.abs(tgt - curMs) < 1000L) return
        performSeek(tgt, curMs, dur)
    }

    fun seekRelative(deltaMs: Long) {
        if (!seekable()) return
        val dur = durMs()
        if (dur <= 0) return
        val curMs = playheadMs().coerceIn(0L, dur)
        val targetMs = (curMs + deltaMs).coerceIn(0L, maxMs(dur))
        if (kotlin.math.abs(targetMs - curMs) < 1000L) return
        performSeek(targetMs, curMs, dur)
    }

    /** Double-click: accumulate the hint; for a recording, seek once ~0.45 s after the last click.
     *  [applyImmediately] = live timeshift (jump at once, hint only). */
    fun doubleTap(forward: Boolean, applyImmediately: Boolean) {
        val step = if (forward) 10 else -10
        if (!applyImmediately) {
            // pin the starting playhead at the beginning of the series of clicks (further clicks only add to it)
            if (accumBaseMs < 0L) accumBaseMs = playheadMs()
        }
        val cur = hint.value
        val acc = if (cur != 0 && (cur > 0) == forward) cur + step else step
        hint.value = acc
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(800)
            hint.value = 0
        }
        if (!applyImmediately) {
            // DVR: seek only ~0.5 s after the last click, to the accumulated total (1 restart instead of N)
            commitJob?.cancel()
            commitJob = scope.launch {
                delay(450)
                val target = accumBaseMs + acc * 1000L
                accumBaseMs = -1L
                hintJob?.cancel()
                hint.value = 0
                seekAbsolute(target)
            }
        }
    }

    /** New media / new subscription: discard both the double-click accumulator and the hint (M492). */
    fun resetForNewMedia() {
        hintJob?.cancel(); hint.value = 0
        commitJob?.cancel(); commitJob = null
        accumBaseMs = -1L
    }
}
