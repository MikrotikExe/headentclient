package sk.tvhclient.android

import android.content.Context

/** M403: kompenzacia postupneho posunu (driftu) zvuku. Niektore kanaly vysielaju
 *  mierne nepresne casove znacky (PCR/PTS) — Kodi to maskuje prevzorkovanim
 *  zvuku, libVLC sa znackam viac podriaduje a zvuk za minuty "utecie"; prepnutie
 *  kanala sync zresetuje a drift zacne znova. Prepinac zapne libVLC volby
 *  audio-time-stretch + vypnutie clock-jitter/clock-synchro heuristik.
 *  Predvolene VYPNUTE (experimentalne — meni spravanie hodin prehravaca). */
object AudioDriftFixPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "audio_drift_fix"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
    }
}
