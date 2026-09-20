package sk.tvhclient.android

import android.view.KeyEvent
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.EpgEvent

/**
 * M643: programme info (detail) in the player — state, loading and keys, split out
 * of PlayerActivity. The drawing is done by ChannelInfoOverlay (PlayerOverlays.kt).
 * It immediately shows the now fields from the channel, the description is filled in from the EPG cache or asynchronously from the server
 * (and stored in the cache via [cacheChannelEpg], M274). M490: the down arrow selects the recording
 * item, OK performs it via [toggleRecord].
 */
internal class ChannelInfo(
    private val scope: CoroutineScope,
    private val live: LiveSession,
    private val epgUpcoming: () -> Map<String, List<EpgEvent>>,
    private val cacheChannelEpg: (String, List<EpgEvent>) -> Unit,
    private val dvrRecordVisible: () -> Boolean,
    private val toggleRecord: () -> Unit,
    private val onShown: () -> Unit
) {
    val visible = mutableStateOf(false)
    // M490: the recording item is selected in the info overlay (down arrow)
    val recSel = mutableStateOf(false)
    val channel = mutableStateOf("")
    val title = mutableStateOf("")
    val time = mutableStateOf("")
    val desc = mutableStateOf("")

    val isOpen: Boolean get() = visible.value

    private fun applyInfo(ev: EpgEvent) {
        if (ev.title.isNotBlank()) title.value = ev.title
        if (ev.start > 0) time.value = fmtRange(ev.start, ev.stop)
        desc.value = ev.bestDescription
    }

    /** Shows the detail of the channel's current programme (from the EPG); shows the now fields immediately, fills in the description async. */
    fun show(idx: Int) {
        val ch = live.channelsState.value.getOrNull(idx) ?: return
        channel.value = ch.name
        title.value = ch.nowTitle
        time.value = fmtRange(ch.nowStart, ch.nowStop)
        desc.value = ""
        onShown()  // M446: hide the zapping bar
        recSel.value = false   // M490
        visible.value = true
        val srv = Tvh.store.active() ?: return
        val nowSec = System.currentTimeMillis() / 1000
        fun pick(list: List<EpgEvent>) =
            list.firstOrNull { it.start <= nowSec && nowSec < it.stop } ?: list.minByOrNull { it.start }
        val cached = epgUpcoming()[ch.uuid]
        if (!cached.isNullOrEmpty()) {
            pick(cached)?.let { applyInfo(it) }
        } else {
            scope.launch {
                val list = runCatching {
                    withContext(Dispatchers.IO) { Tvh.fetchEpgForChannel(srv, Tvh.apiFor(srv), ch.uuid) }
                }.getOrDefault(emptyList())
                cacheChannelEpg(ch.uuid, list)   // M274: memoize for further displays/reopens
                if (visible.value) pick(list)?.let { applyInfo(it) }
            }
        }
    }

    fun close() { visible.value = false }

    /** Keys while info is open: down/up = the recording item, OK performs/closes, BACK/LEFT closes. */
    fun handleKey(kc: Int, down: Boolean): Boolean {
        if (down) when (kc) {
            KeyEvent.KEYCODE_DPAD_DOWN -> { if (dvrRecordVisible()) recSel.value = true; return true }
            KeyEvent.KEYCODE_DPAD_UP -> { recSel.value = false; return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                val rec = recSel.value
                close()
                if (rec) toggleRecord()
                return true
            }
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DPAD_LEFT -> { close(); return true }
        }
        return true
    }

    // M679: fmtClock/fmtRange are in UiTime.kt (shared by the player and the lists)
}
