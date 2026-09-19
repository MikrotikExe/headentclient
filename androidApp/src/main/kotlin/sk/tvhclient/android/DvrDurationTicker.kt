package sk.tvhclient.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * M658: dopočítavanie dĺžky PREBIEHAJÚCEJ nahrávky (vyclenené z PlayerActivity.onCreate).
 *
 * Prebiehajuca relacia: dlzka rastie k zivej hrane; bar musi byt VZDY viditelny.
 * Ak mame hranice relacie, dopocitavame relativne k jej zaciatku (cap dlzkou relacie).
 * Ak hranice chybaju (nahravka nema vyplnene start/stop), drzime krok s dlzkou z VLC.
 *
 * [playerLength]: skutočná dĺžka z libVLC (0 ak engine nie je pripravený).
 * [isRecording]: nahrávka stále beží (dvrRecording aktivity — môže sa počas cyklu zmeniť).
 * [current]/[set]: aktuálna dĺžka aktivity; [set] MUSÍ nastaviť aj dvrDurationMs aj compose stav.
 */
internal class DvrDurationTicker(
    private val scope: CoroutineScope,
    private val playerLength: () -> Long,
    private val isRecording: () -> Boolean,
    private val current: () -> Long,
    private val set: (Long) -> Unit
) {
    /** Nastaví počiatočnú dĺžku a spustí sekundový cyklus (rovnaké výpočty ako pôvodne v onCreate). */
    fun start(durationMs: Long, progStartSec: Long, progStopSec: Long) {
        val haveBounds = progStartSec > 0 && progStopSec > progStartSec
        val progDurMs = if (haveBounds) (progStopSec - progStartSec) * 1000 else 0L
        set(
            if (haveBounds)
                ((System.currentTimeMillis() / 1000 - progStartSec) * 1000).coerceIn(1000L, progDurMs)
            else
                maxOf(durationMs, 1000L)   // aspon 1s, nech sa bar zobrazi
        )
        scope.launch {
            while (true) {
                val nowSec = System.currentTimeMillis() / 1000
                val live = if (haveBounds)
                    ((minOf(nowSec, progStopSec) - progStartSec) * 1000).coerceIn(1000L, progDurMs)
                else
                    maxOf(current(), playerLength())
                // M528: pri DOKONCENEJ nahravke ma prednost skutocna dlzka suboru,
                // ktoru zisti libVLC. Cyklus dlzku doteraz len zvacsoval, takze ked
                // bola nahravka zastavena skor, ostala planovana dlzka relacie —
                // 15-minutova nahravka sa tvarila ako hodinova.
                val realLen = playerLength()
                if (!isRecording() && realLen > 1000L && realLen != current()) {
                    set(realLen)
                } else if (live > current()) {
                    set(live)
                }
                if (haveBounds && nowSec >= progStopSec) break  // relacia skoncila
                kotlinx.coroutines.delay(1000)
            }
        }
    }
}
