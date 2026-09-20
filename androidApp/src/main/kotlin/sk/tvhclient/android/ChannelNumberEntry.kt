package sk.tvhclient.android

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M635: entering a channel number from the remote (split out of PlayerActivity) — the digits are
 * collected (max 4), after 1.5 s without another one or after OK they are committed via [onCommit]
 * (the activity finds the channel by number and switches). [entryState] is read by NumberEntryOverlay.
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

    /** Commits the number being typed immediately (OK) — without waiting for the timer. */
    fun commitNow() { job?.cancel(); commit() }

    private fun commit() {
        val typed = entry.toIntOrNull()
        entry = ""
        entryState.value = ""
        if (typed == null) return
        onCommit(typed)
    }
}
