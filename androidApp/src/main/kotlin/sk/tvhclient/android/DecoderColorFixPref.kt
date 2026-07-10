package sk.tvhclient.android

import android.content.Context

/**
 * Kompatibilna oprava farieb dekodera. Na niektorych TV boxoch/cipsetoch ma
 * hardverovy dekoder (mediacodec) pri priamom vykreslovani (direct rendering)
 * prehodene farebne kanaly (U/V swap) — obraz je modro-oranzovy. Zapnutim sa
 * vypne direct rendering (:no-mediacodec-dr): dekodovanie OSTAVA hardverove
 * (box to utiahne), snimka sa len kopiruje cez medzibuffer, cim sa chybna
 * priama cesta obide. Default vypnute — zdrave zariadenia nechavame na
 * najrychlejsej ceste.
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
