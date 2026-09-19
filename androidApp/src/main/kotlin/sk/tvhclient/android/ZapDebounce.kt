package sk.tvhclient.android

import android.os.Handler
import android.os.Looper

/**
 * M407 / M652: debounce rýchleho zappingu cez CH+/- (vyclenené z PlayerActivity).
 *
 * Rýchle stisky len posúvajú cieľový index a hneď aktualizujú info na obrazovke
 * ([preview]); ťažké načítanie streamu ([commit]) sa spustí až keď sa ~350 ms neprepína
 * (ako set-top box). Bez toho každý stisk čakal na dokončenie predošlého načítania.
 *
 * [pokeOnCommit]: M444 — keď beží zap pás (prekryv vypnutý), prepnutie NESMIE štuchnúť
 * klasické ovládanie, inak sú na obrazovke dva pásy naraz (Xiaomi Mi Box).
 */
internal class ZapDebounce(
    private val live: LiveSession,
    private val haptic: () -> Unit,
    private val pokeOnCommit: () -> Boolean,
    private val commit: (target: Int, poke: Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pendingIndex = -1
    private val commitRunnable = Runnable {
        val target = pendingIndex
        pendingIndex = -1
        if (target >= 0 && target != live.index) commit(target, pokeOnCommit())
    }

    /** M407: preview info kanála bez načítania streamu (pre rýchly zapping). */
    private fun preview(i: Int) {
        live.indexState.value = i
        live.titleState.value = live.names.getOrElse(i) { "" }
        live.uuidState.value = live.uuids.getOrNull(i) ?: live.uuidState.value
        live.showProgramme(LivePlaylist.channels.getOrNull(i))
    }

    /** Prepne na susedný live kanál (delta +1 / -1) s debounce. */
    fun switchLive(delta: Int) {
        haptic()
        if (live.uuids.size < 2 || live.index < 0) return
        val n = live.uuids.size
        // od aktualneho ciela (ak uz caka) alebo od aktualneho kanala
        val from = if (pendingIndex >= 0) pendingIndex else live.index
        val target = ((from + delta) % n + n) % n
        pendingIndex = target
        preview(target)                 // okamzita odozva na obrazovke
        handler.removeCallbacks(commitRunnable)
        handler.postDelayed(commitRunnable, DEBOUNCE_MS)
    }

    fun cancel() { handler.removeCallbacks(commitRunnable) }

    private companion object { const val DEBOUNCE_MS = 350L }
}
