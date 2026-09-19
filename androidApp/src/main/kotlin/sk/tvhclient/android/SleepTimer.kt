package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.mutableStateOf

/**
 * M629: časovač uspatia prehrávača (vyclenené z PlayerActivity). Po uplynutí zavolá
 * [onExpire] (aktivita spraví finish(); M535: stop() nie na hlavnom vlákne — zastaví
 * ho teardown pri finish()). [durations] je ponuka v minútach, 0 = vypnuté.
 */
class SleepTimer(private val ctx: Context, private val onExpire: () -> Unit) {
    val durations = listOf(0, 15, 30, 45, 60, 90)
    val minutesState = mutableStateOf(0)
    val deadlineState = mutableStateOf(0L)
    private val handler = Handler(Looper.getMainLooper())

    /** Nastaví časovač (0 = vypnúť). */
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

    /** Zruší čakajúci časovač bez oznámenia (onDestroy). */
    fun cancel() = handler.removeCallbacksAndMessages(null)
}
