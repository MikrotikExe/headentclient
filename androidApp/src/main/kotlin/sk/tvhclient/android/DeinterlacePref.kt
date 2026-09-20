package sk.tvhclient.android

import android.content.Context

/**
 * Player deinterlacing mode. Interlaced DVB broadcasts (576i/1080i) produce
 * horizontal "comb" stripes on fast shots (combing). Deinterlacing removes
 * them.
 *  - AUTO: libVLC deinterlaces only when the source is interlaced (recommended)
 *  - OFF:  disabled
 *  - BOB / YADIF / YADIF2X / X: a specific algorithm (from cheapest to highest quality)
 * Stored globally in SharedPreferences.
 */
object DeinterlacePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "deinterlace_mode"

    const val AUTO = "auto"
    const val OFF = "off"
    const val BOB = "bob"
    const val YADIF = "yadif"
    const val YADIF2X = "yadif2x"
    const val X = "x"

    val options = listOf(AUTO, OFF, BOB, YADIF, YADIF2X, X)

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AUTO) ?: AUTO

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
