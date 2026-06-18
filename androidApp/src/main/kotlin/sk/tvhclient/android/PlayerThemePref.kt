package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Rezim temy overlay-u prehravaca — NEZAVISLE od temy aplikacie.
 *  - AUTO: podla systemu (predvolene)
 *  - LIGHT / DARK: vynutene
 * Drzime aj zivy stav, aby sa prejavila zmena bez restartu.
 */
object PlayerThemePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "player_theme"

    const val AUTO = "auto"
    const val LIGHT = "light"
    const val DARK = "dark"

    val options = listOf(AUTO, LIGHT, DARK)

    private var state: MutableState<String>? = null

    private fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    fun stateOf(context: Context): MutableState<String> =
        state ?: mutableStateOf(load(context)).also { state = it }

    fun get(context: Context): String = stateOf(context).value

    fun set(context: Context, value: String) {
        stateOf(context).value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
