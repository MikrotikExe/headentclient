package sk.tvhclient.android

import android.content.Context

/**
 * Kompatibilna oprava farieb dekodera. Na niektorych TV boxoch/cipsetoch ma
 * hardverovy dekoder (mediacodec) cez NDK cestu prehodene farebne kanaly
 * (U/V swap) — obraz je modro-oranzovy. Zapnutim sa preferuje JNI cesta
 * mediacodecu (:codec=mediacodec_jni,all): dekodovanie OSTAVA hardverove
 * a direct rendering zapnuty (ziadne kopirovanie, ziadne trhanie), len sa
 * pouzije ina, na tychto cipsetoch korektna cesta. Default vypnute.
 */
object DecoderColorFixPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "decoder_color_fix_mode"

    const val OFF = "off"
    /** Preferovat JNI cestu mediacodecu (DR ostava; plynule, ak cipset JNI podporuje). */
    const val JNI = "jni"
    /** Vypnut direct rendering (kopirovacia cesta; spolahlive farby, narocnejsie). */
    const val NODR = "nodr"

    val options = listOf(OFF, JNI, NODR)

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, OFF) ?: OFF

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
