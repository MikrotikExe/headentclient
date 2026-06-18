package sk.tvhclient.android

import android.content.Context

/**
 * Predvolene otacanie obrazovky v prehravaci.
 *  - AUTO: podla senzora / nastavenia zariadenia (sprava sa ako doteraz, da sa zamknut zamkom)
 *  - PORTRAIT: vynutene na vysku
 *  - LANDSCAPE: vynutene na sirku
 * Pri PORTRAIT/LANDSCAPE je orientacia uz pevna, takze tlacidlo zamku v prehravaci nema zmysel
 * a skryva sa. Ulozene globalne v SharedPreferences.
 */
object OrientationPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "player_orientation"

    const val AUTO = "auto"
    const val PORTRAIT = "portrait"
    const val LANDSCAPE = "landscape"

    val options = listOf(AUTO, LANDSCAPE, PORTRAIT)

    fun get(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, AUTO) ?: AUTO

    fun set(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
