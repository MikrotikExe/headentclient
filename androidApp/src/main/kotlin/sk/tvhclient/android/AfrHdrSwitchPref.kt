package sk.tvhclient.android

import android.content.Context

/**
 * Prepnutie do HDR pri AFR (len TV/box). Tvrde prepnutie rezimu displeja
 * (preferredDisplayModeId) vyvola HDMI re-negociaciu a firmware niektorych
 * boxov pri nej prepne vystup do HDR (podla vlastnej HDR politiky, appka to
 * priamo zakazat nevie). Ked je tento prepinac VYPNUTY, AFR tvrde prepnutie
 * preskoci a poziada o zmenu frekvencie len plynulou cestou
 * (Surface.setFrameRate, bez re-syncu) — ziadna cierna obrazovka, ziadny
 * HDR flip; ci panel frekvenciu realne zmeni, rozhodne system.
 *
 * M500: default VYPNUTE. Tvrde prepnutie rezimu je na roznych boxoch
 * nepredvidatelne (cierna obrazovka pri kazdej zmene frekvencie, flip do HDR aj
 * pri SDR obsahu) a appka firmwaru do jeho HDR politiky nevidi. Kto to chce,
 * zapne si to sam v Nastaveniach pri AFR.
 */
object AfrHdrSwitchPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "afr_hdr_switch"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)   // M500: predvolene vypnute

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }
}
