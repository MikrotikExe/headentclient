package sk.tvhclient.android

import android.view.KeyEvent
import androidx.compose.runtime.MutableState

/**
 * M644: shared key patterns for the player dialogs (extracted from dispatchKeyEvent).
 * On a box, Compose dialogs do not have focus, so arrows/OK/BACK are handled by the activity itself.
 */
internal object DialogKeys {
    fun isOk(kc: Int): Boolean =
        kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER || kc == KeyEvent.KEYCODE_NUMPAD_ENTER

    fun isVolume(kc: Int): Boolean =
        kc == KeyEvent.KEYCODE_VOLUME_UP || kc == KeyEvent.KEYCODE_VOLUME_DOWN || kc == KeyEvent.KEYCODE_VOLUME_MUTE

    /**
     * A dialog with two options side by side (0/1): LEFT/RIGHT toggles [sel], OK (first
     * press only, not repeats) confirms via [onOk] with the current choice, BACK calls [onBack].
     * Always consumes (including UP events), the dialog blocks input to the background.
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
     * Vertical list: UP/DOWN moves [sel] (with wrap-around), OK confirms, BACK (and, with
     * [leftCloses], LEFT as well) closes. [okFirstPressOnly] = OK only at repeatCount 0.
     * Returns false for the volume keys if [passVolume] — the caller passes them to the system;
     * otherwise always true.
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
