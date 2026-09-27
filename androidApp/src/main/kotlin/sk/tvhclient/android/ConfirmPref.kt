package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

/**
 * M702 (issue #18): the confirmation questions can be switched off in Settings -> General.
 *  - [exitApp]: "Really exit Headent Client?" on Back at the home screen (phone and TV launcher).
 *  - [stopPlayback]: "Stop playback?" on Back / Exit during live playback in the player.
 * Both on by default — nothing changes for users who do not touch them.
 */
object ConfirmPref {
    private const val PREFS = "app_prefs"
    private const val KEY_EXIT = "confirm_exit_app"
    private const val KEY_STOP = "confirm_stop_playback"

    private var exitState: MutableState<Boolean>? = null
    private var stopState: MutableState<Boolean>? = null

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun exitAppState(c: Context): MutableState<Boolean> =
        exitState ?: mutableStateOf(prefs(c).getBoolean(KEY_EXIT, true)).also { exitState = it }

    fun stopPlaybackState(c: Context): MutableState<Boolean> =
        stopState ?: mutableStateOf(prefs(c).getBoolean(KEY_STOP, true)).also { stopState = it }

    fun exitApp(c: Context): Boolean = exitAppState(c).value
    fun stopPlayback(c: Context): Boolean = stopPlaybackState(c).value

    fun setExitApp(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_EXIT, v).apply(); exitAppState(c).value = v
    }

    fun setStopPlayback(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(KEY_STOP, v).apply(); stopPlaybackState(c).value = v
    }
}
