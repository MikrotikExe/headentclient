package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Rezim rozhrania: klasicky vzhlad alebo moderny (navy/teal paleta; na TV navyse
 * hero domovska obrazovka + rady kariet). Default = klasicky, aby existujuci
 * pouzivatelia po update nevideli ziadnu zmenu. Drzime aj zivy stav (MutableState),
 * aby sa vzhlad prepol okamzite po zmene v nastaveniach, bez restartu.
 */
object UiModePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "ui_mode"

    const val CLASSIC = "classic"
    const val MODERN = "modern"

    val options = listOf(CLASSIC, MODERN)

    private var state: MutableState<String>? = null

    private fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, CLASSIC) ?: CLASSIC

    /** Zivy stav rezimu — citanim .value v @Composable sa vzhlad obnovi pri zmene. */
    fun stateOf(context: Context): MutableState<String> =
        state ?: mutableStateOf(load(context)).also { state = it }

    fun get(context: Context): String = stateOf(context).value

    fun set(context: Context, value: String) {
        stateOf(context).value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
