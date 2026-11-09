package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf

/**
 * M636: reconnection timing and state (extracted from PlayerActivity). What exactly
 * an attempt does (HTSP resubscribe / feeder / direct HTTP / DVR reopen) stays in the
 * activity — it only arrives here as a lambda, because it touches mediaPlayer, the feeders and the stream URL.
 *
 * - Live stream: [scheduleReconnect] with a growing delay (1.5 s × attempt, max 8 s),
 *   at most [MAX_LIVE] attempts, then a Toast; a watchdog retries after 12 s if nothing is playing.
 * - In-progress recording: [reopenDvrLive] after 2.5 s, at most [MAX_DVR] attempts (backoff
 *   against a loop when nothing new arrives); [resetDvrReopen] on Playing / manual play.
 * [reconnecting] is read by PlayerUi (the "Reconnecting" spinner).
 */
class ReconnectController(
    private val ctx: Context,
    private val playerReady: () -> Boolean,
    private val isPlaying: () -> Boolean,
    /** M760: the player is in front (started) — the slow phase does not open streams in the background. */
    private val active: () -> Boolean = { true }
) {
    val reconnecting = mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private var liveAttempts = 0
    private var dvrAttempts = 0
    /** M760: the quick attempts are used up, the slow ones run (the message was shown once). */
    private var slowPhase = false

    /** Cancels the scheduled reconnect and hides the indicator. */
    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        liveAttempts = 0
        slowPhase = false
        reconnecting.value = false
    }

    /** Cancels pending tasks without changing the indicator (seek = a short stream restart, not an outage). */
    fun clearPending() {
        handler.removeCallbacksAndMessages(null)
        dvrAttempts = 0
    }

    fun resetDvrReopen() { dvrAttempts = 0 }

    /** Hides the indicator without resetting the counters (an error we are no longer dealing with). */
    fun hide() { reconnecting.value = false }

    /** Schedules a live stream reconnect; [retry] receives the attempt number (1..). */
    fun scheduleReconnect(retry: (attempt: Int) -> Unit) {
        if (!playerReady()) return
        if (liveAttempts >= MAX_LIVE) {
            // M760 (audit S2): the quick attempts are used up. Until now the player gave up here for
            // good — a box left running did not come back after a longer server or network outage
            // until somebody pressed a button. The message is shown once and the attempts go on
            // slowly for as long as the player is in front; Playing (cancel) ends them.
            if (!slowPhase) {
                slowPhase = true
                Toast.makeText(ctx, ctx.getString(R.string.reconnect_failed), Toast.LENGTH_LONG).show()
            }
            reconnecting.value = true
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({
                if (!playerReady()) return@postDelayed
                if (!active()) { scheduleReconnect(retry); return@postDelayed }   // in the background: only wait
                runCatching { retry(liveAttempts) }
                handler.postDelayed({
                    if (playerReady() && reconnecting.value && !isPlaying()) scheduleReconnect(retry)
                }, 12000)
            }, SLOW_DELAY_MS)
            return
        }
        liveAttempts++
        reconnecting.value = true
        val delay = (1500L * liveAttempts).coerceAtMost(8000L)
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!playerReady()) return@postDelayed
            runCatching { retry(liveAttempts) }
            // watchdog: if playback does not appear within 12 s (the spinner stayed), try again;
            // once the attempts are exhausted scheduleReconnect reports an error -> no permanent stall.
            // 12 s gives the HTSP subscription time to come up and buffer (shorter doubled it up)
            handler.postDelayed({
                if (playerReady() && reconnecting.value && !isPlaying()) scheduleReconnect(retry)
            }, 12000)
        }, delay)
    }

    /** Reopens an in-progress recording after 2.5 s; false = attempts exhausted. */
    fun reopenDvrLive(reopen: () -> Unit): Boolean {
        if (!playerReady()) return false
        if (dvrAttempts >= MAX_DVR) {
            reconnecting.value = false
            return false
        }
        dvrAttempts++
        reconnecting.value = true
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({
            if (!playerReady()) return@postDelayed
            runCatching { reopen() }
        }, 2500)
        return true
    }

    fun destroy() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val MAX_LIVE = 8
        /** M760: the pause before each attempt of the slow phase. */
        const val SLOW_DELAY_MS = 30_000L
        const val MAX_DVR = 5
    }
}
