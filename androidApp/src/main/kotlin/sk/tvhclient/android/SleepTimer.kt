package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf

/**
 * M629: player sleep timer (extracted from PlayerActivity). When it expires it calls
 * [onExpire] (the activity does finish(); M535: stop() not on the main thread — it is stopped
 * by the teardown on finish()). [durations] is the menu in minutes, 0 = off.
 */
class SleepTimer(private val ctx: Context, private val onExpire: () -> Unit) {
    val durations = listOf(0, 15, 30, 45, 60, 90)
    val minutesState = mutableStateOf(0)
    val deadlineState = mutableStateOf(0L)
    private val handler = Handler(Looper.getMainLooper())

    /** Sets the timer (0 = off). */
    fun set(minutes: Int) {
        handler.removeCallbacksAndMessages(null)
        minutesState.value = minutes
        if (minutes <= 0) {
            deadlineState.value = 0L
            Toast.makeText(ctx, ctx.getString(R.string.sleep_off), Toast.LENGTH_SHORT).show()
            return
        }
        deadlineState.value = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed({ onExpire() }, minutes * 60_000L)
        Toast.makeText(ctx, ctx.getString(R.string.sleep_set, minutes), Toast.LENGTH_SHORT).show()
    }

    /** Cancels a pending timer without notifying (onDestroy). */
    fun cancel() = handler.removeCallbacksAndMessages(null)
}
