package sk.tvhclient.android

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf

/**
 * M629: the parental lock PIN prompt in the player (split out of PlayerActivity).
 * Drawing is done by PinDialog in PlayerUi; here are the state, digit entry, the D-pad
 * grid (M265) and the keys during the prompt.
 *
 * From the activity it needs: whether this is a TV (grid), the number of channels in the list
 * (M267: "list" only makes sense with 2+), opening the channel list, switching to
 * a neighbouring channel during the prompt (M262, via switchToIndex, which re-evaluates the lock)
 * and [onRequested] — there the activity clears the OK gesture (okLongFired), because the prompt
 * takes over input.
 */
class PinPrompt(
    private val ctx: Context,
    private val isTv: () -> Boolean,
    private val channelCount: () -> Int,
    private val openChannelList: () -> Unit,
    private val switchToIndex: (Int) -> Unit,
    private val onRequested: () -> Unit
) {
    val promptState = mutableStateOf(false)
    val entryState = mutableStateOf("")
    val errorState = mutableStateOf(false)
    // M265: selection in the PIN grid (D-pad). Grid 1-9 / del 0 list.
    val gridRowState = mutableStateOf(0)
    val gridColState = mutableStateOf(0)

    private var onSuccess: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var markUnlock = true
    // M262: index of the channel whose playback the prompt is blocking (null = another use,
    // e.g. locking from the menu). Allows switching to a neighbouring channel during the prompt.
    private var channelIndex: Int? = null

    val isOpen: Boolean get() = promptState.value

    fun request(onOk: () -> Unit, onCancel: () -> Unit, markUnlock: Boolean = true, channelIndex: Int? = null) {
        onRequested()
        this.markUnlock = markUnlock
        this.channelIndex = channelIndex
        onSuccess = onOk; this.onCancel = onCancel
        entryState.value = ""; errorState.value = false
        gridRowState.value = 0; gridColState.value = 0
        promptState.value = true
    }

    fun close() {
        promptState.value = false; entryState.value = ""; errorState.value = false
        onSuccess = null; onCancel = null
        markUnlock = true
        channelIndex = null
    }

    fun cancel() {
        val c = onCancel
        close(); c?.invoke()
    }

    /** M267: from a locked channel's PIN prompt open the channel list so the user can pick
     *  another (unlocked) one. We close the prompt without onCancel (that is, without finish) so the player
     *  does not go dark. If there is only one channel there is nowhere to switch -> cancel (finish). */
    fun openList() {
        if (channelCount() < 2) { cancel(); return }
        close()
        openChannelList()
    }

    fun digit(d: Int) {
        if (entryState.value.length >= 4) return
        entryState.value += d
        errorState.value = false
        if (entryState.value.length == 4) {
            if (ParentalLock.checkPin(ctx, entryState.value)) {
                if (markUnlock) ParentalLock.markUnlocked(ctx)
                val ok = onSuccess
                close(); ok?.invoke()
            } else { errorState.value = true; entryState.value = "" }
        }
    }

    fun del() {
        if (entryState.value.isNotEmpty()) entryState.value = entryState.value.dropLast(1)
        errorState.value = false
    }

    private fun activateGridKey() {
        val grid = listOf(
            listOf("1", "2", "3"),
            listOf("4", "5", "6"),
            listOf("7", "8", "9"),
            listOf("del", "0", "list")
        )
        val r = gridRowState.value.coerceIn(0, 3)
        val c = gridColState.value.coerceIn(0, 2)
        when (val label = grid[r][c]) {
            "del" -> del()
            "list" -> openList()
            else -> digit(label.toInt())
        }
    }

    /** M262: switching during the prompt — cancels the prompt (without ending the player) and switches
     *  relative to the blocked channel; switchToIndex re-evaluates the lock. */
    private fun switchFrom(fromIndex: Int, delta: Int) {
        val n = channelCount()
        if (n < 2) return
        close()
        switchToIndex(((fromIndex + delta) % n + n) % n)
    }

    /** Keys during the prompt — we take the digits ourselves; on TV the D-pad grid too. Always consumes. */
    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        if (!down) return true
        val digit = when (kc) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> kc - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> kc - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        // direct digits from the remote (if the remote has them)
        if (digit >= 0) { digit(digit); return true }
        when (kc) {
            KeyEvent.KEYCODE_DEL -> { del(); return true }
            // M267-fix: the Back key during a locked channel's PIN prompt returns the user
            // to the channel list (to pick an unlocked one), it does not end the player.
            KeyEvent.KEYCODE_BACK -> { openList(); return true }
        }
        // M262: during the prompt you can switch to another channel — hardware CHANNEL +/- only
        // (the D-pad drives the PIN grid). A free channel starts playing, the next locked one
        // asks for the PIN again.
        val pci = channelIndex
        if (pci != null && channelCount() > 1 && event.repeatCount == 0) {
            when (kc) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { switchFrom(pci, +1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchFrom(pci, -1); return true }
            }
        }
        // M265: a D-pad grid for entering the PIN — for remotes without number keys.
        if (isTv()) {
            when (kc) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { gridColState.value = (gridColState.value - 1 + 3) % 3; return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { gridColState.value = (gridColState.value + 1) % 3; return true }
                KeyEvent.KEYCODE_DPAD_UP -> { gridRowState.value = (gridRowState.value - 1 + 4) % 4; return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { gridRowState.value = (gridRowState.value + 1) % 4; return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                    { activateGridKey(); return true }
            }
        }
        return true
    }
}
