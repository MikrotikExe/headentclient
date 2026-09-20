package sk.tvhclient.android

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * M597 / M598 / M646: seeking a recording with the cursor on the bar (scrub), extracted from PlayerActivity.
 *
 * [fraction] = cursor position within the REACHABLE range of the bar ([barMs]; 45 s shorter for an
 * in-progress recording). Arrow step = 30 s; while held, continuous movement every 50 ms with
 * acceleration (1 min/s → 2.5 → 5 → 10 min/s, or % of the recording length, capped at the whole recording
 * in ~6 s). The picture is NOT rebuilt while moving — it seeks once after it settles (2 s,
 * [scheduleAuto]) or on OK ([commit]). M598-fix4: a rapid press/release series
 * from IR/CEC remotes (< 250 ms) is a continuation of the same seek.
 */
internal class ScrubController(
    private val scope: CoroutineScope,
    private val barMs: () -> Long,
    private val durMs: () -> Long,
    private val playheadMs: () -> Long,
    private val seekable: () -> Boolean,
    private val seekAbsolute: (Long) -> Unit,
    private val poke: () -> Unit
) {
    val fraction = mutableStateOf(0f)
    private var autoJob: Job? = null
    private var holdJob: Job? = null
    private var lastUpMs = 0L
    private var lastDir = 0
    private var holdSince = 0L

    val holding: Boolean get() = holdJob != null

    fun cancelAuto() { autoJob?.cancel(); autoJob = null }

    /** Fraction of the 30 s step within the length (fallback 2 % if the length is unknown). */
    fun stepFrac(): Float { val dur = durMs(); return if (dur > 0) 30_000f / dur else 0.02f }

    /** Move the cursor by one step (±1). */
    fun step(dir: Int) { fraction.value = (fraction.value + dir * stepFrac()).coerceIn(0f, 1f) }

    /** Cursor to the current playhead of the playback clock (not player.position — unreliable on a growing TS). */
    fun init() {
        val bar = barMs()
        fraction.value = if (bar > 0) (playheadMs().toFloat() / bar).coerceIn(0f, 1f) else 0f
    }

    /** Performs the seek to the cursor position. M597-fix: if the cursor ended up practically
     *  where playback already is (moved there and back), it does NOT seek — a rebuild would stutter the picture for nothing. */
    fun commit(minDeltaMs: Long = 0L) {
        cancelAuto()
        holdJob?.cancel(); holdJob = null   // M598-fix2
        if (!seekable()) return
        val bar = barMs()
        if (bar <= 0) return
        val progMs = (fraction.value.coerceIn(0f, 1f) * bar).toLong()
        if (minDeltaMs > 0L && kotlin.math.abs(progMs - playheadMs()) < minDeltaMs) return
        seekAbsolute(progMs)
    }

    /** Schedules automatic confirmation of the move after 2 s of inactivity (M597). */
    fun scheduleAuto() {
        cancelAuto()
        autoJob = scope.launch {
            delay(2000)
            commit(minDeltaMs = 5_000L)   // M597-fix: no real move, no rebuild
            poke()
        }
    }

    /** true = the previous seek continues (same direction, short gap). */
    fun continues(dir: Int): Boolean = dir == lastDir && SystemClock.uptimeMillis() - lastUpMs < 250L

    fun startHold(dir: Int) {
        holdJob?.cancel()
        // M598-fix4: while I HOLD the arrow, automatic confirmation from a tap must not fire.
        cancelAuto()
        val continuing = continues(dir)
        lastDir = dir
        if (!continuing) holdSince = SystemClock.uptimeMillis()
        holdJob = scope.launch {
            val startedAt = holdSince
            // up to 0.4 s it is still a click, not a hold; on a continuation there is no wait
            if (!continuing) delay(400)
            while (isActive) {
                val dur = durMs()
                if (dur > 0) {
                    val held = SystemClock.uptimeMillis() - startedAt
                    // M598-fix5: the speed also depends on the LENGTH of the recording (1 % / 2.5 % / 5 % / 10 % per s)
                    val durSec = dur / 1000.0
                    val rate = when {
                        held < 1_500L -> maxOf(60.0, durSec * 0.01)
                        held < 3_000L -> maxOf(150.0, durSec * 0.025)
                        held < 5_000L -> maxOf(300.0, durSec * 0.05)
                        else -> maxOf(600.0, durSec * 0.10)
                    }
                    val r = minOf(rate, durSec / 6.0)
                    val deltaMs = (r * 50.0).toFloat()      // movement per one 50 ms step
                    fraction.value = (fraction.value + dir * deltaMs / dur).coerceIn(0f, 1f)
                    poke()
                }
                delay(50)
            }
        }
    }

    /** Arrow released: stop continuous movement and schedule the seek (M597). */
    fun stopHold() {
        if (holdJob == null) return
        holdJob?.cancel()
        holdJob = null
        lastUpMs = SystemClock.uptimeMillis()   // M598-fix4
        scheduleAuto()
    }

    /** Arrow click with the cursor on the bar (M598-fix2/fix4): step if this is not a continuation, and start the hold. */
    fun tapOrHold(dir: Int) {
        if (!continues(dir)) step(dir)
        startHold(dir)
    }
}
