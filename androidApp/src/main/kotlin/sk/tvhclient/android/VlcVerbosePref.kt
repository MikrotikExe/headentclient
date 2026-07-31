package sk.tvhclient.android

import android.content.Context

/**
 * M448: podrobny zaznam prehravaca (libVLC verbose).
 *
 * Predvolene VYPNUTE — appka posiela libVLC "--quiet", takze prehravac do
 * logcatu nepise nic. Po zapnuti sa posiela "-vv", cim sa objavia sprava o
 * hodinach (PCR), buffrovani a dekodovani. Sluzi na diagnostiku hlasenych
 * problemov (trhanie, zamrzanie, rozchadzajuci sa zvuk) — log potom staci
 * zachytit cez adb logcat alebo poslat cez Diagnostiku.
 *
 * Zapnute stoji vykon (vela zapisov), preto sa po diagnostike vypina.
 */
object VlcVerbosePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "vlc_verbose"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
