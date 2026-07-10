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
    private const val KEY = "decoder_color_fix"

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, on).apply()
    }
}
