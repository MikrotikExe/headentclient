package sk.tvhclient.android

import android.content.Context

/**
 * Rezim deinterlacingu prehravaca. Prekladane DVB vysielanie (576i/1080i) tvori
 * na rychlych zaberoch vodorovne "hrebenove" pasy (combing). Deinterlacing ich
 * odstrani.
 *  - AUTO: libVLC deinterlacuje len ked je zdroj prekladany (odporucane)
 *  - OFF:  vypnute
 *  - BOB / YADIF / YADIF2X / X: konkretny algoritmus (od najlacnejsieho po najkvalitnejsi)
 * Ulozene globalne v SharedPreferences.
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
