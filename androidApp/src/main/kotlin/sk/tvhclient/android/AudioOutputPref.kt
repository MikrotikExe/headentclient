package sk.tvhclient.android

import android.content.Context

/**
 * Rezim zvukoveho vystupu libVLC (riesi rozchadzajuci sa / oneskoreny zvuk na
 * niektorych boxoch a TV).
 *  - AUTO: detekcia (passthrough sa pouzije ak ho zariadenie podporuje)
 *    POZOR (M436): na Amlogic boxoch (Strong) passthrough cesta pridava
 *    latenciu, ktoru appka nevidi — zvuk sa posuva o stovky ms. Preto uz
 *    NIE JE predvolena.
 *  - STEREO: max 2 kanaly (kompat rezim)
 *  - PCM: zvuk dekoduje appka (max 8 kanalov) — PREDVOLENE od 1.0.2 (M436):
 *    spravna synchronizacia vsade, viackanalovy LPCM cez HDMI stale funguje;
 *    kto chce bitstream do AVR, prepne si Passthrough/Auto.
 *  - PASSTHROUGH: "priamy prenos" — zvuk ide v povodnom formate (AC3/EAC3/DTS)
 *    priamo do TV/AVR, ktory ho dekoduje sam (zarovna sync, ak box pridaval latenciu)
 * Mapuje sa na MediaPlayer.setAudioOutputDevice(null/"stereo"/"pcm"/"encoded").
 * Ulozene globalne v SharedPreferences.
 */
object AudioOutputPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "audio_output_mode"

    const val AUTO = "auto"
    const val STEREO = "stereo"
    const val PCM = "pcm"
    const val PASSTHROUGH = "passthrough"

    val options = listOf(AUTO, PASSTHROUGH, PCM, STEREO)

    /** Hodnota pre MediaPlayer.setAudioOutputDevice; null = auto detekcia. */
    fun deviceId(context: Context): String? = when (get(context)) {
        STEREO -> "stereo"
        PCM -> "pcm"
        PASSTHROUGH -> "encoded"
        else -> null
    }

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, PCM) ?: PCM

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
