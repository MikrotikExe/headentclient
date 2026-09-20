package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Picon (channel/radio logo) background. Default = the existing behaviour (neutral
 * grey per the theme) so nothing changes for existing users. Optionally
 * transparent or one of the preset colours (swatch). Live state (MutableState)
 * so a change takes effect everywhere at once without a restart. Available on TV and phone.
 */
object PiconBgPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "picon_bg"

    const val DEFAULT = "default"
    const val TRANSPARENT = "transparent"

    // Preset colours (swatch) — the value is a hex that piconBackground() can parse.
    const val BLACK = "#000000"
    const val WHITE = "#FFFFFF"
    const val DARK = "#2B2F36"
    const val LIGHT = "#C3C8D0"
    const val NAVY = "#12294E"

    /** Display order in the swatch picker. */
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
