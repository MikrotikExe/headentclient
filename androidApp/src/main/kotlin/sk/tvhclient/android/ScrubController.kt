package sk.tvhclient.android

import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * M597 / M598 / M646: pretáčanie nahrávky kurzorom na lište (scrub), vyclenené z PlayerActivity.
 *
 * [fraction] = poloha kurzora v rámci DOSIAHNUTEĽNÉHO rozsahu baru ([barMs]; pri in-progress
 * nahrávke o 45 s kratší). Krok šípkou = 30 s; pri držaní plynulý posun každých 50 ms so
 * zrýchľovaním (1 min/s → 2,5 → 5 → 10 min/s, alebo % dĺžky nahrávky, strop celá nahrávka
 * za ~6 s). Obraz sa počas posúvania NEprestavuje — pretočí sa raz po ustálení (2 s,
 * [scheduleAuto]) alebo po OK ([commit]). M598-fix4: rýchla séria stlačenie/uvoľnenie
 * z IR/CEC ovládačov (< 250 ms) je pokračovanie toho istého pretáčania.
 */
internal class ScrubController(
    private val scope: CoroutineScope,
    private val barMs: () -> Long,
    private val durMs: () -> Long,
    private val playheadMs: () -> Long,
    private val seekable: () -> Boolean,
    private val seekAbsolute: (Long) -> Unit,
    private val poke: () -> Unit
) {
    val fraction = mutableStateOf(0f)
    private var autoJob: Job? = null
    private var holdJob: Job? = null
    private var lastUpMs = 0L
    private var lastDir = 0
    private var holdSince = 0L

    val holding: Boolean get() = holdJob != null

    fun cancelAuto() { autoJob?.cancel(); autoJob = null }

    /** Zlomok kroku 30 s v rámci dĺžky (fallback 2 %, ak dĺžku nepoznáme). */
    fun stepFrac(): Float { val dur = durMs(); return if (dur > 0) 30_000f / dur else 0.02f }

    /** Posuň kurzor o jeden krok (±1). */
    fun step(dir: Int) { fraction.value = (fraction.value + dir * stepFrac()).coerceIn(0f, 1f) }

    /** Kurzor na aktuálny playhead prehrávacích hodín (nie player.position — na rastúcom TS nespoľahlivá). */
    fun init() {
        val bar = barMs()
        fraction.value = if (bar > 0) (playheadMs().toFloat() / bar).coerceIn(0f, 1f) else 0f
    }

    /** Vykona pretocenie na poziciu kurzora. M597-fix: ak kurzor skoncil prakticky tam,
     *  kde sa uz hra (posun tam a spat), sa NEpretaca — prestavba by obraz zbytocne sekla. */
    fun commit(minDeltaMs: Long = 0L) {
        cancelAuto()
        holdJob?.cancel(); holdJob = null   // M598-fix2
        if (!seekable()) return
        val bar = barMs()
        if (bar <= 0) return
        val progMs = (fraction.value.coerceIn(0f, 1f) * bar).toLong()
        if (minDeltaMs > 0L && kotlin.math.abs(progMs - playheadMs()) < minDeltaMs) return
        seekAbsolute(progMs)
    }

    /** Naplanuje automaticke potvrdenie posunu po 2 s necinnosti (M597). */
    fun scheduleAuto() {
        cancelAuto()
        autoJob = scope.launch {
            delay(2000)
            commit(minDeltaMs = 5_000L)   // M597-fix: bez skutocneho posunu ziadna prestavba
            poke()
        }
    }

    /** true = predchadzajuce pretacanie pokracuje (rovnaky smer, kratka medzera). */
    fun continues(dir: Int): Boolean = dir == lastDir && SystemClock.uptimeMillis() - lastUpMs < 250L

    fun startHold(dir: Int) {
        holdJob?.cancel()
        // M598-fix4: kym sipku DRZIM, sa nesmie spustit automaticke potvrdenie z klepnutia.
        cancelAuto()
        val continuing = continues(dir)
        lastDir = dir
        if (!continuing) holdSince = SystemClock.uptimeMillis()
        holdJob = scope.launch {
            val startedAt = holdSince
            // do 0,4 s je to este klik, nie drzanie; pri pokracovani sa neceka
            if (!continuing) delay(400)
            while (isActive) {
                val dur = durMs()
                if (dur > 0) {
                    val held = SystemClock.uptimeMillis() - startedAt
                    // M598-fix5: rychlost sa odvija aj od DLZKY nahravky (1 % / 2,5 % / 5 % / 10 % za s)
                    val durSec = dur / 1000.0
                    val rate = when {
                        held < 1_500L -> maxOf(60.0, durSec * 0.01)
                        held < 3_000L -> maxOf(150.0, durSec * 0.025)
                        held < 5_000L -> maxOf(300.0, durSec * 0.05)
                        else -> maxOf(600.0, durSec * 0.10)
                    }
                    val r = minOf(rate, durSec / 6.0)
                    val deltaMs = (r * 50.0).toFloat()      // posun za jeden 50 ms krok
                    fraction.value = (fraction.value + dir * deltaMs / dur).coerceIn(0f, 1f)
                    poke()
                }
                delay(50)
            }
        }
    }

    /** Pustenie sipky: zastav plynuly posun a naplanuj pretocenie (M597). */
    fun stopHold() {
        if (holdJob == null) return
        holdJob?.cancel()
        holdJob = null
        lastUpMs = SystemClock.uptimeMillis()   // M598-fix4
        scheduleAuto()
    }

    /** Klik šípkou pri kurzore na lište (M598-fix2/fix4): krok, ak nejde o pokračovanie, a začni držanie. */
    fun tapOrHold(dir: Int) {
        if (!continues(dir)) step(dir)
        startHold(dir)
    }
}
