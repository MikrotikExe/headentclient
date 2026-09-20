package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * App theme mode.
 *  - AUTO: as per the system (light/dark according to the device setting) — the default
 *  - LIGHT: always light
 *  - DARK: always dark
 * We also hold live state (MutableState) so the theme switches immediately after a change in settings,
 * without needing a restart. Stored globally in SharedPreferences.
 */
object ThemePref {
    private const val PREFS = "app_prefs"
    private const val KEY = "app_theme"

    const val AUTO = "auto"
    const val LIGHT = "light"
    const val DARK = "dark"

    val options = listOf(AUTO, LIGHT, DARK)

    private var state: MutableState<String>? = null

    private fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    /** Live state of the mode — reading .value in a @Composable refreshes the theme on change. */
    fun stateOf(context: Context): MutableState<String> =
        state ?: mutableStateOf(load(context)).also { state = it }

    fun get(context: Context): String = stateOf(context).value

    fun set(context: Context, value: String) {
        stateOf(context).value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
