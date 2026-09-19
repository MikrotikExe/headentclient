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
 * M664: dotykové gestá prehrávača (MX Player štýl) vyclenené z PlayerUi: vodorovný ťah =
 * seek (len keď je čo pretáčať), zvislý ťah vpravo = hlasitosť, vľavo = jas, v strede zhora =
 * vysunutie zoznamu kanálov. Telo detektora je 1:1 s pôvodným pointerInput v PlayerUi;
 * stavy PlayerUi sa čítajú cez gettery (detektor je suspend slučka, číta aktuálne hodnoty)
 * a zapisujú cez settery. Kľúče pointerInput ostávajú (isTvGest, seekable, timeshiftEngaged,
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
        var mode = 0          // 0=nerozhodnute, 1=seek(H), 2=hlasitost(V vpravo), 3=jas(V vlavo), 4=otvor zoznam (V zhora)
        var startVol = 0
        var startBright = 0.5f
        val guardTop = 48.dp.toPx()              // odsadenie od hornej hrany (systemova lista/shade)
        while (true) {
            val ev = awaitPointerEvent()
            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
            if (!ch.pressed) break
            val dx = ch.position.x - down.position.x
            val dy = ch.position.y - down.position.y
            // Gesta zachovaj po ploche; zakaz ich len v oblasti spodneho baru
            // (ovladanie/slider), ked je viditelny - tam pretacas cez slider.
            val inBar = controlsVisible && down.position.y > size.height * 0.6f
            // M565: gesta prehravaca (hlasitost, jas, seek, vysunutie zoznamu) su vypnute,
            // kym je otvorene akekolvek menu — tento detektor nerespektuje consume()
            // deti (awaitFirstDown(requireUnconsumed = false)), takze ho treba vypnut tu
            if (mode == 0 && !overlayOpen() && !inBar && (kotlin.math.abs(dx) > slop || kotlin.math.abs(dy) > slop)) {
                mode = if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                    if (seekable || timeshiftEngaged) 1 else 0   // seek len ked je co pretacat
                } else if (isTvGest) {
                    0                                            // na TV ziadne gesta
                } else if (down.position.y <= guardTop) {
                    0                                            // horny okraj (systemova lista/wifi) -> ziadne vertikalne gesto
                } else if (down.position.x < size.width * 0.25f) {
                    val cur = act?.window?.attributes?.screenBrightness ?: -1f
                    startBright = if (cur in 0f..1f) cur else 0.5f; 3                              // lavych 25% = jas
                } else if (down.position.x >= size.width * 0.75f) {
                    startVol = audio.getStreamVolume(AudioManager.STREAM_MUSIC); 2   // pravych 25% = hlasitost
                } else if (dy > 0) {
                    4                                            // stred 50% (0.25-0.75), tah dole -> otvor zoznam
                } else {
                    0                                            // ine -> nic
                }
            }
            if (mode != 0) ch.consume()
            when (mode) {
                1 -> setScrubSec((dx / size.width * 90f).toInt())
                4 -> setListFrac((dy / (size.height * 0.5f) * 0.7f).coerceIn(0f, 1f))   // vysuvanie zhora za prstom (o 30% pomalsie)
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
            setShowChannelList(open)        // open -> LaunchedEffect dotiahne na 1
            if (!open) listScope.launch {
                androidx.compose.animation.core.animate(listFrac(), 0f) { v, _ -> setListFrac(v) }
            }
        }
    }
}
