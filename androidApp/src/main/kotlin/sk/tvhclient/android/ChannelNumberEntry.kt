package sk.tvhclient.android

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M635: zadávanie čísla kanála z diaľkového (vyclenené z PlayerActivity) — číslice sa
 * zbierajú (max 4), po 1,5 s bez ďalšej alebo po OK sa potvrdia cez [onCommit]
 * (aktivita nájde kanál podľa čísla a prepne). [entryState] číta NumberEntryOverlay.
 */
class ChannelNumberEntry(private val scope: CoroutineScope, private val onCommit: (Int) -> Unit) {
    val entryState = mutableStateOf("")
    private var entry = ""
    private var job: Job? = null

    val isPending: Boolean get() = entry.isNotEmpty()

    fun digit(d: Int) {
        entry = (entry + d).takeLast(4)
        entryState.value = entry
        job?.cancel()
        job = scope.launch {
            delay(1500)
            commit()
        }
    }

    /** Potvrdí rozpísané číslo hneď (OK) — bez čakania na časovač. */
    fun commitNow() { job?.cancel(); commit() }

    private fun commit() {
        val typed = entry.toIntOrNull()
        entry = ""
        entryState.value = ""
        if (typed == null) return
        onCommit(typed)
    }
}
