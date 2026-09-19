package sk.tvhclient.android

import android.content.Context
import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf

/**
 * M629: PIN výzva rodičovského zámku v prehrávači (vyclenené z PlayerActivity).
 * Vykreslenie robí PinDialog v PlayerUi; tu je stav, zadávanie číslic, D-pad
 * mriežka (M265) a klávesy počas výzvy.
 *
 * Z aktivity potrebuje: či ide o TV (mriežka), počet kanálov v zozname
 * (M267: „zoznam" má zmysel len pri 2+), otvorenie zoznamu kanálov, prepnutie na
 * susedný kanál počas výzvy (M262, cez switchToIndex, ktorý zámok znova vyhodnotí)
 * a [onRequested] — aktivita si tam vynuluje OK gesto (okLongFired), lebo výzva
 * preberá vstup.
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
    // M265: výber v PIN mriežke (D-pad). Mriežka 1-9 / del 0 zoznam.
    val gridRowState = mutableStateOf(0)
    val gridColState = mutableStateOf(0)

    private var onSuccess: (() -> Unit)? = null
    private var onCancel: (() -> Unit)? = null
    private var markUnlock = true
    // M262: index kanála, ktorého prehrávanie výzva blokuje (null = iné použitie,
    // napr. zamykanie z menu). Umožňuje počas výzvy prepnúť na susedný kanál.
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

    /** M267: z PIN výzvy zamknutého kanála otvor zoznam kanálov, nech si používateľ vyberie
     *  iný (nezamknutý). Výzvu zatvoríme bez onCancel (teda bez finish), aby prehrávač
     *  nezhasol. Ak je len jeden kanál, niet kam prepnúť -> cancel (finish). */
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

    /** M262: prepnutie počas výzvy — zruší výzvu (bez ukončenia prehrávača) a prepne
     *  relatívne k blokovanému kanálu; switchToIndex zámok znova vyhodnotí. */
    private fun switchFrom(fromIndex: Int, delta: Int) {
        val n = channelCount()
        if (n < 2) return
        close()
        switchToIndex(((fromIndex + delta) % n + n) % n)
    }

    /** Klávesy počas výzvy — číslice zadávame my; na TV aj D-pad mriežka. Vždy spotrebuje. */
    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        if (!down) return true
        val digit = when (kc) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> kc - KeyEvent.KEYCODE_0
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> kc - KeyEvent.KEYCODE_NUMPAD_0
            else -> -1
        }
        // priame číslice z diaľkového (ak ich ovládač má)
        if (digit >= 0) { digit(digit); return true }
        when (kc) {
            KeyEvent.KEYCODE_DEL -> { del(); return true }
            // M267-fix: šípka Späť počas PIN výzvy zamknutého kanála vráti používateľa
            // k zoznamu kanálov (nech si vyberie nezamknutý), neukončuje prehrávač.
            KeyEvent.KEYCODE_BACK -> { openList(); return true }
        }
        // M262: počas výzvy sa dá prepnúť na iný kanál — len hardvérové CHANNEL +/-
        // (D-pad ovláda PIN mriežku). Voľný kanál sa začne hrať, ďalší zamknutý
        // si opäť vypýta PIN.
        val pci = channelIndex
        if (pci != null && channelCount() > 1 && event.repeatCount == 0) {
            when (kc) {
                KeyEvent.KEYCODE_CHANNEL_UP -> { switchFrom(pci, +1); return true }
                KeyEvent.KEYCODE_CHANNEL_DOWN -> { switchFrom(pci, -1); return true }
            }
        }
        // M265: D-pad mriežka na zadanie PIN — pre ovládače bez číselných kláves.
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
