package sk.tvhclient.android

import android.content.Context

/** Pauza po zmene obnovovacej frekvencie (M347, ako Kodi "Delay after change
 *  of refresh rate"): TV pri HDMI resyncu na 1-2 s strati obraz aj zvuk —
 *  kratka pauza prehravania zabrani stratenemu zaciatku a rozsynchronizovaniu. */
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
