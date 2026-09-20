package sk.tvhclient.android

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M262 / M492 / M508 / M647: HTSP timeshift (pause and jumps behind live), extracted from PlayerActivity.
 *
 * Holds the offset behind live ([offsetMs], growing during a pause at 1 s/s via a ticker), whether timeshift
 * is "engaged" ([engaged], the first pause) and the accumulated jump, which is sent to the server only after
 * the tapping settles (350 ms) as a single relative subscriptionSkip — we return to live
 * by jumping forward, not with subscriptionLive or a restart (the same buffer is kept).
 * Buffer depth: the real one from the server (timeshiftStatus end−start, M508-fix2), otherwise wall-clock.
 */
internal class TimeshiftController(
    private val scope: CoroutineScope,
    private val feeder: () -> HtspTsFeeder?,
    private val onResumePlayback: () -> Unit,
    private val onSeekSpinner: () -> Unit
) {
    val offsetMs = mutableStateOf(0L)
    val engaged = mutableStateOf(false)

    private var accumMs = 0L
    private var pauseStartedAt = 0L
    private var startedAt = 0L
    private var pendingSkipMs = 0L
    private var skipFlushJob: Job? = null
    private var tickerJob: Job? = null

    /** The first pause "engages" timeshift; returns true if it was the first (the caller re-anchors the focus). */
    fun onPaused(): Boolean {
        // with "On-demand" timeshift the first pause is the moment the server really starts building the buffer
        if (startedAt <= 0L) startedAt = System.currentTimeMillis()
        val first = !engaged.value
        engaged.value = true
        pauseStartedAt = System.currentTimeMillis()
        startTicker()
        return first
    }

    fun onResumed() {
        if (pauseStartedAt > 0L) {
            accumMs += System.currentTimeMillis() - pauseStartedAt
            pauseStartedAt = 0L
        }
        stopTicker()
        offsetMs.value = accumMs
    }

    /** During a pause the offset behind live grows (1 s/s); updates the indicator every second. */
    private fun startTicker() {
        tickerJob?.cancel()
        tickerJob = scope.launch {
            while (true) {
                val extra = if (pauseStartedAt > 0L) System.currentTimeMillis() - pauseStartedAt else 0L
                offsetMs.value = accumMs + extra
                delay(1000)
            }
        }
    }

    fun stopTicker() { tickerJob?.cancel(); tickerJob = null }

    /** Deliver the accumulated jump immediately (before pause/play, so the server stays consistent). */
    fun flushNow() { skipFlushJob?.cancel(); flushSkip() }

    /** A new live start (a fresh subscription = live) -> reset timeshift. */
    fun reset() {
        stopTicker()
        skipFlushJob?.cancel(); skipFlushJob = null
        pendingSkipMs = 0L
        accumMs = 0L
        pauseStartedAt = 0L
        startedAt = 0L   // a new channel = a new buffer from zero
        engaged.value = false
        offsetMs.value = 0L
    }

    /**
     * How far back it is possible to seek. The REAL buffer length reported by the server takes
     * precedence (M508-fix2); wall-clock is the backup for older TVH / radio (it does not send timeshift info).
     */
    fun maxRewindMs(): Long {
        val fromServer = (feeder()?.bufferTicks ?: 0L) / 90L   // 90 kHz -> ms
        if (fromServer > 0L) return fromServer.coerceAtMost(3600_000L)
        if (startedAt <= 0L) return 0L
        return (System.currentTimeMillis() - startedAt).coerceAtMost(3600_000L)
    }

    /** A relative jump in timeshift (seconds; negative = backwards). Updates the indicator too. */
    fun skip(seconds: Int) {
        // if paused, start playback after the jump (so the result of the jump is visible)
        if (pauseStartedAt > 0L) {
            accumMs += System.currentTimeMillis() - pauseStartedAt
            pauseStartedAt = 0L
            stopTicker()
            onResumePlayback()
        }
        // target position behind live, clamped to <0 .. buffer depth>
        val target = (accumMs - seconds.toLong() * 1000L).coerceIn(0L, maxRewindMs())
        val deltaMs = target - accumMs
        if (deltaMs == 0L) return                   // nowhere to go (start of the buffer or live)
        accumMs = target
        offsetMs.value = accumMs
        // the indicator reacts immediately, but send the real jump only once the tapping stops —
        // several jumps in a row otherwise force libVLC to keep resynchronising (it stutters)
        pendingSkipMs += deltaMs
        skipFlushJob?.cancel()
        skipFlushJob = scope.launch {
            delay(350)
            flushSkip()
        }
    }

    private fun flushSkip() {
        val net = pendingSkipMs
        pendingSkipMs = 0L
        if (net != 0L) {
            feeder()?.skip((-net / 1000L).toInt())   // backwards => negative, forwards => positive
            onSeekSpinner()
        }
    }

    fun destroy() { stopTicker(); skipFlushJob?.cancel() }
}
