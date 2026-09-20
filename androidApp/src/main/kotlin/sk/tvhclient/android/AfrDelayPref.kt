package sk.tvhclient.android

import android.content.Context

/** Pause after a refresh-rate change (M347, like Kodi "Delay after change
 *  of refresh rate"): during the HDMI resync the TV loses picture and sound for 1-2 s —
 *  a short playback pause prevents a lost start and desynchronisation. */
object AfrDelayPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "afr_delay_sec"
    val options = listOf(0, 1, 2, 3, 5)

    fun get(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY, 2)

    fun set(context: Context, sec: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, sec).apply()
    }
}
