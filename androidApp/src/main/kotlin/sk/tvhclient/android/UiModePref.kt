package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * Interface mode: the classic look or the modern one (navy/teal palette; on TV additionally
 * a hero home screen + rows of cards). Default = classic, so that existing
 * users see no change after an update. We also hold live state (MutableState),
 * so the look switches immediately after a change in the settings, without a restart.
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

    /** Live state of the mode — reading .value in a @Composable makes the look refresh on a change. */
    fun stateOf(context: Context): MutableState<String> =
        state ?: mutableStateOf(load(context)).also { state = it }

    fun get(context: Context): String = stateOf(context).value

    fun set(context: Context, value: String) {
        stateOf(context).value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
    }
}
