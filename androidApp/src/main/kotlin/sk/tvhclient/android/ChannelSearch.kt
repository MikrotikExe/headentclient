package sk.tvhclient.android

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * M370 / M635: searching for a channel by name in the player's channel list (TV: the system
 * keyboard), split out of PlayerActivity. State for TvChannelListOverlay + keys.
 *
 * Two phases: focus on the text field ([fieldFocused] = true; the text is handled by the IME, we handle only
 * BACK and DOWN) and focus on the results (UP/DOWN/OK/BACK). Channel selection is handled by
 * [onSelect] (the activity switches the group too, if needed). [onDeactivate] is called when
 * search is opened (it closes the group pill).
 */
class ChannelSearch(
    private val activity: Activity,
    private val onSelect: (uuid: String) -> Unit,
    private val onDeactivate: () -> Unit
) {
    val activeState = mutableStateOf(false)
    val queryState = mutableStateOf("")
    val fieldFocusedState = mutableStateOf(true)
    val navIndexState = mutableStateOf(0)
    val focusSignalState = mutableStateOf(0)

    val isActive: Boolean get() = activeState.value

    fun results(): List<LivePlaylist.LiveChannel> {
        val q = queryState.value.trim()
        if (q.isEmpty()) return emptyList()
        return LivePlaylist.allChannels.filter { it.name.contains(q, ignoreCase = true) }
    }

    fun setQuery(q: String) { queryState.value = q; navIndexState.value = 0 }

    fun open() {
        onDeactivate()
        queryState.value = ""
        navIndexState.value = 0
        fieldFocusedState.value = true
        activeState.value = true
        focusSignalState.value = focusSignalState.value + 1
        // The player's immersive window otherwise does not let the system keyboard in — we force it.
        runCatching {
            activity.window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .show(WindowInsetsCompat.Type.ime())
        }
    }

    fun close() {
        activeState.value = false
        fieldFocusedState.value = true
        queryState.value = ""
        runCatching {
            WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                .hide(WindowInsetsCompat.Type.ime())
        }
    }

    /** Silent deactivation without the keyboard (when the channel list is opened/closed). */
    fun deactivateSilently() { activeState.value = false }

    private fun focusField() {
        fieldFocusedState.value = true
        focusSignalState.value = focusSignalState.value + 1
    }

    /**
     * Keys while the text field is focused. Returns true if we consumed the key;
     * false = let the system/IME have it (the caller calls super.dispatchKeyEvent).
     * Call only when [isActive] && fieldFocusedState.
     */
    fun handleFieldKey(kc: Int, down: Boolean): Boolean {
        if (!down) return false
        when (kc) {
            KeyEvent.KEYCODE_BACK -> { close(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (results().isNotEmpty()) {
                    fieldFocusedState.value = false
                    navIndexState.value = 0
                }
                return true
            }
        }
        return false
    }

    /**
     * Keys while the results are focused (the channel list is open, search is active).
     * Returns true if consumed; false = the key is not ours (e.g. volume).
     */
    fun handleResultsKey(kc: Int, down: Boolean): Boolean {
        if (!down) return false
        val res = results()
        val n2 = res.size
        when (kc) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (navIndexState.value <= 0) focusField()
                else navIndexState.value = navIndexState.value - 1
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (n2 > 0) navIndexState.value = (navIndexState.value + 1).coerceAtMost(n2 - 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                res.getOrNull(navIndexState.value)?.let { onSelect(it.uuid) }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { focusField(); return true }
        }
        return false
    }
}
