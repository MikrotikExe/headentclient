package sk.tvhclient.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * M658: extrapolating the length of a RECORDING IN PROGRESS (extracted from PlayerActivity.onCreate).
 *
 * Programme in progress: the length grows towards the live edge; the bar must ALWAYS be visible.
 * If we have the programme boundaries, we extrapolate relative to its start (capped by the programme length).
 * If the boundaries are missing (the recording has no start/stop filled in), we keep pace with the length from VLC.
 *
 * [playerLength]: the real length from libVLC (0 if the engine is not ready).
 * [isRecording]: the recording is still running (the activity's dvrRecording — may change during the cycle).
 * [current]/[set]: the activity's current length; [set] MUST set both dvrDurationMs and the compose state.
 */
internal class DvrDurationTicker(
    private val scope: CoroutineScope,
    private val playerLength: () -> Long,
    private val isRecording: () -> Boolean,
    private val current: () -> Long,
    private val set: (Long) -> Unit
) {
    /** Sets the initial length and starts the one-second cycle (the same calculations as originally in onCreate). */
    fun start(durationMs: Long, progStartSec: Long, progStopSec: Long) {
        val haveBounds = progStartSec > 0 && progStopSec > progStartSec
        val progDurMs = if (haveBounds) (progStopSec - progStartSec) * 1000 else 0L
        set(
            if (haveBounds)
                ((System.currentTimeMillis() / 1000 - progStartSec) * 1000).coerceIn(1000L, progDurMs)
            else
                maxOf(durationMs, 1000L)   // at least 1s, so that the bar is shown
        )
        scope.launch {
            while (true) {
                val nowSec = System.currentTimeMillis() / 1000
                val live = if (haveBounds)
                    ((minOf(nowSec, progStopSec) - progStartSec) * 1000).coerceIn(1000L, progDurMs)
                else
                    maxOf(current(), playerLength())
                // M528: for a FINISHED recording the real length of the file takes precedence,
                // as determined by libVLC. Until now the cycle only ever increased the length, so when
                // a recording had been stopped early, the scheduled programme length remained —
                // a 15-minute recording pretended to be an hour long.
                val realLen = playerLength()
                if (!isRecording() && realLen > 1000L && realLen != current()) {
                    set(realLen)
                } else if (live > current()) {
                    set(live)
                }
                if (haveBounds && nowSec >= progStopSec) break  // the programme has ended
                kotlinx.coroutines.delay(1000)
            }
        }
    }
}
