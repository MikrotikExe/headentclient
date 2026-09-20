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
 * M643: info o relácii (detail) v prehrávači — stav, načítanie a klávesy, vyclenené
 * z PlayerActivity. Vykreslenie robí ChannelInfoOverlay (PlayerOverlays.kt).
 * Okamžite ukáže now-polia z kanála, popis doplní z EPG cache alebo asynchrónne zo servera
 * (a uloží do cache cez [cacheChannelEpg], M274). M490: šípka dole vyberie položku
 * nahrávania, OK ju vykoná cez [toggleRecord].
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
    // M490: v info prekryti je vybrata polozka nahravania (sipka dole)
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

    /** Zobrazi detail aktualnej relacie kanala (z EPG); okamzite ukaze now-polia, popis doplni async. */
    fun show(idx: Int) {
        val ch = live.channelsState.value.getOrNull(idx) ?: return
        channel.value = ch.name
        title.value = ch.nowTitle
        time.value = fmtRange(ch.nowStart, ch.nowStop)
        desc.value = ""
        onShown()  // M446: skry zap pas
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
                cacheChannelEpg(ch.uuid, list)   // M274: memoizuj pre dalsie zobrazenia/reopen
                if (visible.value) pick(list)?.let { applyInfo(it) }
            }
        }
    }

    fun close() { visible.value = false }

    /** Klávesy pri otvorenom info: dole/hore = položka nahrávania, OK vykoná/zavrie, BACK/VĽAVO zavrie. */
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

    // M679: fmtClock/fmtRange su v UiTime.kt (spolocne pre prehravac aj zoznamy)
}
