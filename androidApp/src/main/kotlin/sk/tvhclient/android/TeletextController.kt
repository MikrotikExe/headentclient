package sk.tvhclient.android

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import sk.tvhclient.shared.model.TvhServer

/**
 * M553 / M627: teletext state and control in the player (extracted from PlayerActivity).
 * Rendering is done by TeletextOverlay, decoding by [session] (TeletextSession, M552).
 *
 * From the activity it needs only three things as lambdas: the current server (HTSP vs HTTP path),
 * the uuid of the live channel (memory of the last page, the HTTP side branch) and whether this is seekable
 * playback (archive — teletext is not offered). [onOpened] closes the modern overlay.
 */
class TeletextController(
    private val activity: ComponentActivity,
    private val liveServer: () -> TvhServer?,
    private val liveUuid: () -> String?,
    private val seekable: () -> Boolean,
    private val onOpened: () -> Unit
) {
    /** M552: teletext of the current channel (HTSP: data from the feeder, HTTP: our own side branch). */
    val session: TeletextSession by lazy { TeletextSession(activity) }

    val openState = mutableStateOf(false)
    val pageState = mutableStateOf(0x100)
    val subState = mutableStateOf(-1)
    val entryState = mutableStateOf("")
    val transparentState = mutableStateOf(false)
    val revealState = mutableStateOf(false)
    private val lastPage = HashMap<String, Int>()   // channel -> last page
    private val entryHandler = Handler(Looper.getMainLooper())
    private val entryTimeout = Runnable { entryState.value = "" }

    fun isHtspLiveServer(): Boolean = liveServer()?.connectionMode == "htsp"

    /** The Teletext item is offered only for a live channel: HTSP when the channel has the track, HTTP always
     *  (whether it is broadcast is only learnt from the PMT after opening). */
    fun visible(): Boolean {
        if (seekable() || liveUuid() == null) return false
        return if (isHtspLiveServer()) session.availableState.value else true
    }

    fun open() {
        val uuid = liveUuid() ?: return
        pageState.value = lastPage[uuid] ?: 0x100
        subState.value = -1
        entryState.value = ""
        revealState.value = false
        openState.value = true
        onOpened()
        if (!isHtspLiveServer()) liveServer()?.let { session.startHttp(it, uuid, activity.lifecycleScope) }
    }

    fun close() {
        if (!openState.value) return
        openState.value = false
        entryHandler.removeCallbacks(entryTimeout)
        liveUuid()?.let { lastPage[it] = pageState.value }
        session.stopHttp()
    }

    /** Close and discard the decoded data — on a channel / source switch (M552/M553). */
    fun reset() { close(); session.reset() }

    fun toggleTransparent() { transparentState.value = !transparentState.value }

    private fun goto(page: Int) {
        if (page < 0x100 || page > 0x8FF) return
        pageState.value = page
        subState.value = -1
        entryState.value = ""
        revealState.value = false
    }

    /** Next/previous page: the nearest one already received, otherwise ±1 (hex numbering 100..8FF, decimal only). */
    fun step(dir: Int) {
        val cur = pageState.value
        val known = session.decoder.knownPages().filter { isDecimalPage(it) }
        val next = if (dir > 0) known.firstOrNull { it > cur } else known.lastOrNull { it < cur }
        if (next != null) { goto(next); return }
        var p = cur
        repeat(0x800) {
            p += dir
            if (p < 0x100) p = 0x8FF
            if (p > 0x8FF) p = 0x100
            if (isDecimalPage(p)) { goto(p); return }
        }
    }

    private fun isDecimalPage(p: Int): Boolean = ((p shr 4) and 0xF) <= 9 && (p and 0xF) <= 9

    fun subStep(dir: Int) {
        val subs = session.decoder.subpages(pageState.value)
        if (subs.size < 2) return
        val curPage = session.decoder.page(pageState.value, subState.value)
        val curSub = curPage?.subpage ?: subs.last()
        val i = subs.indexOf(curSub)
        val ni = ((if (i < 0) 0 else i) + dir + subs.size) % subs.size
        subState.value = subs[ni]
    }

    fun digit(d: Int) {
        entryHandler.removeCallbacks(entryTimeout)
        var e = entryState.value
        if (e.isEmpty() && (d < 1 || d > 8)) return   // page 100..899
        e += d
        if (e.length >= 3) { goto(e.toInt(16)); return }
        entryState.value = e
        entryHandler.postDelayed(entryTimeout, 4000)
    }

    private fun fastext(link: Int) {
        val pg = session.decoder.page(pageState.value, subState.value) ?: return
        val target = pg.links.getOrNull(link) ?: return
        if (target > 0) goto(target)
    }

    /** Keys while teletext is open. Volume is passed through to the system, everything else is consumed. */
    fun handleKey(kc: Int, down: Boolean, event: KeyEvent): Boolean {
        when (kc) {
            KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_MUTE, KeyEvent.KEYCODE_MUTE -> return false
        }
        if (!down) return true
        when (kc) {
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> digit(kc - KeyEvent.KEYCODE_0)
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> digit(kc - KeyEvent.KEYCODE_NUMPAD_0)
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> step(+1)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> step(-1)
            KeyEvent.KEYCODE_DPAD_LEFT -> subStep(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> subStep(+1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER ->
                if (event.repeatCount == 0) toggleTransparent()
            KeyEvent.KEYCODE_PROG_RED -> fastext(0)
            KeyEvent.KEYCODE_PROG_GREEN -> fastext(1)
            KeyEvent.KEYCODE_PROG_YELLOW -> fastext(2)
            KeyEvent.KEYCODE_PROG_BLUE -> fastext(3)
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_MENU ->
                revealState.value = !revealState.value   // reveal concealed (conceal) characters
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_TV_TELETEXT -> close()
        }
        return true
    }
}
