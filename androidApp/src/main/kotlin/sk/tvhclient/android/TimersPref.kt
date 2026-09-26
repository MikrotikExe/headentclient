package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * M696: "Enable timers" (Settings -> General -> Recordings).
 *
 * Recording rules (record by EPG / by time, "Record series" in the programme detail and the
 * Scheduled/Timers sections in Recordings) are an advanced feature most viewers never touch, and
 * on a shared server they change what the server records for everybody on that account. Off by
 * default; whoever wants them turns them on here. The switch only hides the UI — the server
 * enforces the rights itself.
 */
object TimersPref {
    private const val PREFS = "app_prefs"
    private const val KEY = "timers_enabled"

    private var state: MutableState<Boolean>? = null

    fun get(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    /** Compose state shared by every screen, so a change in the Settings shows up at once. */
    fun stateOf(context: Context): MutableState<Boolean> =
        state ?: mutableStateOf(get(context)).also { state = it }

    fun set(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY, enabled).apply()
        stateOf(context).value = enabled
    }
}
