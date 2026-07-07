package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Pozadie piconu (loga kanala/radia). Default = doterajsie spravanie (neutralna
 * seda podla temy), aby sa existujucim pouzivatelom nic nezmenilo. Volitelne
 * priehladne alebo jedna z prednastavenych farieb (swatch). Zivy stav (MutableState),
 * aby sa zmena prejavila okamzite vsade bez restartu. Dostupne na TV aj telefone.
 */
object PiconBgPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "picon_bg"

    const val DEFAULT = "default"
    const val TRANSPARENT = "transparent"

    // Prednastavene farby (swatch) — hodnota je hex, ktory vie piconBackground() rozparsovat.
    const val BLACK = "#000000"
    const val WHITE = "#FFFFFF"
    const val DARK = "#2B2F36"
    const val LIGHT = "#C3C8D0"
    const val NAVY = "#12294E"

    /** Poradie zobrazenia vo swatch-picker-i. */
    val options = listOf(DEFAULT, TRANSPARENT, BLACK, WHITE, DARK, LIGHT, NAVY)

    private var state: MutableState<String>? = null

    private fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, DEFAULT) ?: DEFAULT

    fun stateOf(context: Context): MutableState<String> =
        state ?: mutableStateOf(load(context)).also { state = it }

    fun get(context: Context): String = stateOf(context).value

    fun set(context: Context, value: String) {
        stateOf(context).value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
