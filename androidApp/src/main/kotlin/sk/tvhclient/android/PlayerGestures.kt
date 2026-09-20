package sk.tvhclient.android

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * M664: player touch gestures (MX Player style) extracted from PlayerUi: horizontal drag =
 * seek (only when there is something to seek), vertical drag on the right = volume, on the left = brightness, in the middle from the top =
 * sliding out the channel list. The detector body is 1:1 with the original pointerInput in PlayerUi;
 * PlayerUi states are read through getters (the detector is a suspend loop, it reads the current values)
 * and written through setters. The pointerInput keys stay (isTvGest, seekable, timeshiftEngaged,
 * controlsVisible).
 */
internal fun Modifier.playerGestures(
    ctx: Context,
    isTvGest: Boolean,
    seekable: Boolean,
    timeshiftEngaged: Boolean,
    controlsVisible: Boolean,
    overlayOpen: () -> Boolean,
    listScope: CoroutineScope,
    listFrac: () -> Float,
    setListFrac: (Float) -> Unit,
    scrubSec: () -> Int,
    setScrubSec: (Int) -> Unit,
    setVolPct: (Int) -> Unit,
    setBrightPct: (Int) -> Unit,
    setShowChannelList: (Boolean) -> Unit,
    onScrubSeek: (Int) -> Unit
): Modifier = pointerInput(isTvGest, seekable, timeshiftEngaged, controlsVisible) {
    val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val act = ctx as? Activity
    val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        var mode = 0          // 0=undecided, 1=seek(H), 2=volume(V right), 3=brightness(V left), 4=open list (V from the top)
        var startVol = 0
        var startBright = 0.5f
        val guardTop = 48.dp.toPx()              // inset from the top edge (system bar/shade)
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            val dx = ch.position.x - down.position.x
            val dy = ch.position.y - down.position.y
            // Keep the gestures over the whole area; disable them only in the area of the bottom bar
            // (controls/slider) when it is visible - there you seek via the slider.
            val inBar = controlsVisible && down.position.y > size.height * 0.6f
            // M565: the player gestures (volume, brightness, seek, sliding out the list) are disabled
            // while any menu is open — this detector does not respect consume() of
            // its children (awaitFirstDown(requireUnconsumed = false)), so it has to be disabled here
            if (mode == 0 && !overlayOpen() && !inBar && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                mode = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                    if (seekable || timeshiftEngaged) 1 else 0   // seek only when there is something to seek
                } else if (isTvGest) {
                    0                                            // no gestures on TV
                } else if (down.position.y <= guardTop) {
                    0                                            // top edge (system bar/wifi) -> no vertical gesture
                } else if (down.position.x < size.width * 0.25f) {
                    val cur = act?.window?.attributes?.screenBrightness ?: -1f
                    startBright = if (cur in 0f..1f) cur else 0.5f; 3                              // left 25% = brightness
                } else if (down.position.x >= size.width * 0.75f) {
                    startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC); 2   // right 25% = volume
                } else if (dy > 0) {
                    4                                            // middle 50% (0.25-0.75), drag down -> open the list
                } else {
                    0                                            // otherwise -> nothing
                }
            }
            if (mode != 0) ch.consume()
            when (mode) {
                1 -> setScrubSec((dx / size.width * 90f).toInt())
                4 -> setListFrac((dy / (size.height * 0.5f) * 0.7f).coerceIn(0f, 1f))   // sliding out from the top following the finger (30% slower)
                2 -> {
                    val nv = (startVol - dy / size.height * maxVol).toInt().coerceIn(0, maxVol)
                    audio.setStreamVolume(AudioManager.STREAM_MUSIC, nv, 0)
                    setVolPct(nv * 100 / maxVol)
                }
                3 -> {
                    val nb = (startBright - dy / size.height).coerceIn(0.01f, 1f)
                    act?.window?.let { w -> val lp = w.attributes; lp.screenBrightness = nb; w.attributes = lp }
                    setBrightPct((nb * 100).toInt())
                }
            }
        }
        if (mode == 1) {
            val secs = scrubSec()
            if (secs != Int.MIN_VALUE && secs != 0) onScrubSeek(secs)
        }
        if (mode == 4) {
            val open = listFrac() > 0.33f
            setShowChannelList(open)        // open -> LaunchedEffect pulls it to 1
            if (!open) listScope.launch {
                androidx.compose.animation.core.animate(listFrac(), 0f) { v, _ -> setListFrac(v) }
            }
        }
    }
}
