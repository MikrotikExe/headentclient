package sk.tvhclient.android

import android.app.Activity
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.runtime.mutableStateOf
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * M370 / M635: hľadanie kanála podľa názvu v zozname kanálov prehrávača (TV: systémová
 * klávesnica), vyclenené z PlayerActivity. Stav pre TvChannelListOverlay + klávesy.
 *
 * Dve fázy: fokus na textovom poli ([fieldFocused] = true; text spracúva IME, my len
 * BACK a DOLE) a fokus na výsledkoch (HORE/DOLE/OK/BACK). Výber kanála rieši
 * [onSelect] (aktivita prepne aj skupinu, ak treba). [onDeactivate] sa volá pri
 * otvorení hľadania (zavrie pilulku skupiny).
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
        // Immersive okno prehravaca inak systemovu klavesnicu nepusti — vynutime ju.
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

    /** Tiché deaktivovanie bez klávesnice (pri otvorení/zatvorení zoznamu kanálov). */
    fun deactivateSilently() { activeState.value = false }

    private fun focusField() {
        fieldFocusedState.value = true
        focusSignalState.value = focusSignalState.value + 1
    }

    /**
     * Klávesy pri fokuse na textovom poli. Vráti true, ak sme kláves spotrebovali;
     * false = nech ho dostane systém/IME (volajúci zavolá super.dispatchKeyEvent).
     * Volať len keď [isActive] && fieldFocusedState.
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
     * Klávesy pri fokuse na výsledkoch (zoznam kanálov otvorený, hľadanie aktívne).
     * Vráti true, ak spotrebované; false = kláves nie je náš (napr. hlasitosť).
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
