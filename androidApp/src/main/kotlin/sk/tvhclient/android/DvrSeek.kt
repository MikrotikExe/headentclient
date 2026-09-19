package sk.tvhclient.android

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * M594 / M648: výpočet cieľa pretáčania v nahrávke a pomocná logika okolo neho, vyclenené
 * z PlayerActivity. Samotné PREBUDOVANIE streamu (nová Media s :start-time alebo reštart
 * feedera na byte-offsete) ostáva v aktivite ([performSeek]) — siaha na URL, feeder, player.
 *
 * - Cieľ: playhead prehrávacích hodín (player.position je pre rastúci TS aj pipe nestabilná),
 *   rezerva od konca: in-progress 45 s (zapísané dáta zaostávajú), dokončená 5 s (súbor býva
 *   kratší než trvanie z EPG, M594). Skoky pod 1 s sa ignorujú.
 * - M594 zotavenie: chyba/koniec do 15 s po pretočení = trafený EOF → ustúp o 30 s (max 2×).
 * - Dvojklik (YouTube-style): hint ±10 s sa akumuluje, pretočí sa raz ~0,45 s po poslednom kliku.
 */
internal class DvrSeek(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val durMs: () -> Long,
    private val recording: () -> Boolean,
    private val playheadMs: () -> Long,
    private val seekable: () -> Boolean,
    private val performSeek: (targetMs: Long, fromMs: Long, dur: Long) -> Unit
) {
    /** Hint ±s pri dvojkliku (0 = skrytý); číta SeekHintOverlay. */
    val hint = mutableStateOf(0)
    private var hintJob: Job? = null
    private var accumBaseMs = -1L
    private var commitJob: Job? = null

    private var lastSeekAtMs = 0L
    private var lastSeekTargetMs = 0L
    private var recoverTries = 0

    private fun maxMs(dur: Long): Long =
        if (recording()) (dur - 45_000L).coerceAtLeast(0L) else (dur - 5_000L).coerceAtLeast(0L)

    /** Volá aktivita po každom reálnom pretočení (seekDvrTo) — pre M594 zotavenie. */
    fun markSeek(targetMs: Long) {
        lastSeekAtMs = SystemClock.elapsedRealtime()
        lastSeekTargetMs = targetMs
    }

    /** Playing dorazil → pretočenie sa podarilo, vynuluj pokusy o zotavenie. */
    fun onPlaying() { recoverTries = 0 }

    private fun justSeeked(): Boolean =
        lastSeekAtMs > 0L && SystemClock.elapsedRealtime() - lastSeekAtMs < 15_000L

    /** Vrati true, ak sa po pretoceni podarilo ustupit spat a skusit znova. */
    fun recoverAfterSeek(): Boolean {
        if (!seekable() || recording() || !justSeeked() || recoverTries >= 2) return false
        recoverTries++
        val back = (lastSeekTargetMs - 30_000L * recoverTries).coerceAtLeast(0L)
        CrashLogger.report(ctx, "PlayerActivity.dvrSeek", "seek past end of file, retry #$recoverTries at ${back / 1000}s")
        seekAbsolute(back)
        return true
    }

    /** Absolutny seek na program-relativny cas (spodna lista / D-pad / kurzor). */
    fun seekAbsolute(targetMs: Long) {
        if (!seekable()) return
        val dur = durMs()
        if (dur <= 0) return
        val curMs = playheadMs().coerceIn(0L, dur)
        val tgt = targetMs.coerceIn(0L, maxMs(dur))
        if (kotlin.math.abs(tgt - curMs) < 1000L) return
        performSeek(tgt, curMs, dur)
    }

    fun seekRelative(deltaMs: Long) {
        if (!seekable()) return
        val dur = durMs()
        if (dur <= 0) return
        val curMs = playheadMs().coerceIn(0L, dur)
        val targetMs = (curMs + deltaMs).coerceIn(0L, maxMs(dur))
        if (kotlin.math.abs(targetMs - curMs) < 1000L) return
        performSeek(targetMs, curMs, dur)
    }

    /** Dvojklik: akumuluj hint; pri nahrávke pretoč raz po ~0,45 s od posledného kliku.
     *  [applyImmediately] = live timeshift (skok hned, len hint). */
    fun doubleTap(forward: Boolean, applyImmediately: Boolean) {
        val step = if (forward) 10 else -10
        if (!applyImmediately) {
            // zafixuj vychodzi playhead na zaciatku serie klikov (dalsie kliky len pridavaju)
            if (accumBaseMs < 0L) accumBaseMs = playheadMs()
        }
        val cur = hint.value
        val acc = if (cur != 0 && (cur > 0) == forward) cur + step else step
        hint.value = acc
        hintJob?.cancel()
        hintJob = scope.launch {
            delay(800)
            hint.value = 0
        }
        if (!applyImmediately) {
            // DVR: pretoc az ~0,5 s po poslednom kliku na akumulovany sucet (1 restart namiesto N)
            commitJob?.cancel()
            commitJob = scope.launch {
                delay(450)
                val target = accumBaseMs + acc * 1000L
                accumBaseMs = -1L
                hintJob?.cancel()
                hint.value = 0
                seekAbsolute(target)
            }
        }
    }

    /** Nové médium / nová subscription: zahoď akumulátor dvojkliku aj hint (M492). */
    fun resetForNewMedia() {
        hintJob?.cancel(); hint.value = 0
        commitJob?.cancel(); commitJob = null
        accumBaseMs = -1L
    }
}
