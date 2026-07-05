package sk.tvhclient.android

import android.content.Context

/** Kompenzacia oneskorenia zvuku (M349, ako Kodi "Audio offset"):
 *  pri passthrough dekoduje AC3/EAC3 az TV/AVR a pridava latenciu, ktoru
 *  prehravac nevidi — zvuk je pozadu za obrazom. Zaporna hodnota posunie
 *  zvuk dopredu (libVLC to riesi oneskorenim video hodin).
 *  AUTO (default): pri passthrough vystupe -200 ms, inak 0. */
object AudioDelayPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "audio_delay_ms"
    const val AUTO = "auto"
    val options = listOf(AUTO, "0", "-100", "-200", "-300", "-400", "-500", "100", "200")

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AUTO) ?: AUTO

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }

    /** Efektivne oneskorenie v mikrosekundach pre MediaPlayer.setAudioDelay. */
    fun effectiveUs(context: Context): Long = when (val v = get(context)) {
        AUTO -> if (AudioOutputPref.get(context) == AudioOutputPref.PASSTHROUGH) -200_000L else 0L
        else -> (v.toLongOrNull() ?: 0L) * 1000L
    }
}
