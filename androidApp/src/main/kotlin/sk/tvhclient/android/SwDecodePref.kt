package sk.tvhclient.android

import android.content.Context

/**
 * M447: vynutene softverove dekodovanie videa.
 *
 * Predvolene VYPNUTE — appka pouziva hardverovy dekoder zariadenia (spravne
 * spravanie na drvivej vacsine boxov). Zapina sa tam, kde je HW dekoder
 * pokazeny: napr. Xiaomi Mi Box S (Android 9, stary Amlogic OMX) trha
 * 10-bit HEVC v sekundovych intervaloch, kym H.264 s vyssim tokom hra
 * plynulo. Softverove dekodovanie zaberie viac CPU (1080p H.264/HEVC 8-bit
 * bezne boxy zvladnu), preto sa nezapina samo.
 */
object SwDecodePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "sw_decode"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
