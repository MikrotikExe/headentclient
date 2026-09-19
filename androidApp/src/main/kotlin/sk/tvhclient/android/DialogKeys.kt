package sk.tvhclient.android

import android.view.KeyEvent
import androidx.compose.runtime.MutableState

/**
 * M644: spoločné klávesové vzory dialógov prehrávača (vyclenené z dispatchKeyEvent).
 * Na boxe dialógy v Compose nemajú fokus, preto šípky/OK/BACK rieši aktivita sama.
 */
internal object DialogKeys {
    fun isOk(kc: Int): Boolean =
        kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER

    fun isVolume(kc: Int): Boolean =
        kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN || kc == KeyEvent.KEYCODE_VOLUME_MUTE

    /**
     * Dialóg s dvoma voľbami vedľa seba (0/1): VĽAVO/VPRAVO prepína [sel], OK (len prvé
     * stlačenie, nie opakovanie) potvrdí cez [onOk] s aktuálnou voľbou, BACK zavolá [onBack].
     * Vždy spotrebuje (aj UP udalosti), dialóg blokuje vstup do pozadia.
     */
    fun twoChoice(kc: Int, down: Boolean, event: KeyEvent, sel: MutableState<Int>,
                  onOk: (Int) -> Unit, onBack: () -> Unit): Boolean {
        if (down) when (kc) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> sel.value = 1 - sel.value
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (event.repeatCount == 0) onOk(sel.value)
            KeyEvent.KEYCODE_BACK -> onBack()
        }
        return true
    }

    /**
     * Vertikálny zoznam: HORE/DOLE posúva [sel] (s pretočením), OK potvrdí, BACK (a pri
     * [leftCloses] aj VĽAVO) zavrie. [okFirstPressOnly] = OK len pri repeatCount 0.
     * Vráti false pre klávesy hlasitosti, ak [passVolume] — volajúci ich pošle systému;
     * inak vždy true.
     */
    fun verticalList(kc: Int, down: Boolean, event: KeyEvent, count: Int, sel: MutableState<Int>,
                     okFirstPressOnly: Boolean = false, leftCloses: Boolean = false, passVolume: Boolean = false,
                     onOk: () -> Unit, onBack: () -> Unit): Boolean {
        if (down && count > 0) when (kc) {
            KeyEvent.KEYCODE_DPAD_UP -> { sel.value = (sel.value - 1 + count) % count; return true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { sel.value = (sel.value + 1) % count; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!okFirstPressOnly || event.repeatCount == 0) onOk()
                return true
            }
            KeyEvent.KEYCODE_BACK -> { onBack(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT -> if (leftCloses) { onBack(); return true }
        }
        if (passVolume && isVolume(kc)) return false
        return true
    }
}
