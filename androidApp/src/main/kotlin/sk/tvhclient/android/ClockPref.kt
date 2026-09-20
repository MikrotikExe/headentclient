package sk.tvhclient.android

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import sk.tvhclient.shared.TimeFormatConfig

/**
 * Clock format across the whole app (M423) — requested in GitHub issue #2.
 *  - AUTO: according to the device's system setting — the default
 *  - H24:  always 24-hour (13:45)
 *  - H12:  always 12-hour (1:45 PM)
 *
 * The same pattern as ThemePref: live state (MutableState), so that the time is redrawn
 * right after a change in settings, without a restart. The AM/PM labels are localised by
 * SimpleDateFormat itself according to the Locale, we do not write them by hand.
 */
object ClockPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "clock_format"

    const val AUTO = "auto"
    const val H24 = "h24"
    const val H12 = "h12"

    val options = listOf(AUTO, H24, H12)

    private var state: MutableState<String>? = null

    /**
     * Revision — incremented when the user changes the system 12/24 setting
     * while the app is running. Compose reads it in hm(), so the time is redrawn
     * without a restart. The choice itself in SharedPreferences does not change.
     */
    private val revision: MutableIntState = mutableIntStateOf(0)

    private fun load(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, AUTO) ?: AUTO

    /** Live state of the choice — reading .value in a @Composable redraws the time on a change. */
    fun stateOf(context: Context): MutableState<String> =
        state ?: mutableStateOf(load(context)).also { state = it }

    fun get(context: Context): String = stateOf(context).value

    fun set(context: Context, value: String) {
        stateOf(context).value = value
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, value).apply()
        apply(context)
    }

    /** true = 24-hour format. With AUTO we ask the system. */
    fun is24(context: Context): Boolean = when (get(context)) {
        H24 -> true
        H12 -> false
        else -> DateFormat.is24HourFormat(context)
    }

    /** Pattern for SimpleDateFormat. */
    fun hm(context: Context): String {
        revision.intValue          // subscription: redrawn on a system change too
        return if (is24(context)) "HH:mm" else "h:mm a"
    }

    /** Called on ACTION_TIME_CHANGED — the system changed 12/24. */
    fun onSystemFormatChanged(context: Context) {
        apply(context)
        revision.intValue++
    }

    /** Passes the current choice to the shared module, which has no Context. */
    fun apply(context: Context) {
        TimeFormatConfig.hm = hm(context)
    }
}
