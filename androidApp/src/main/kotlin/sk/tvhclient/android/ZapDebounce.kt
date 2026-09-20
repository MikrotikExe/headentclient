package sk.tvhclient.android

import android.os.Handler
import android.os.Looper

/**
 * M407 / M652: debounce of fast zapping via CH+/- (extracted from PlayerActivity).
 *
 * Fast presses only move the target index and immediately update the info on screen
 * ([preview]); the heavy stream load ([commit]) starts only once there has been no switch for ~350 ms
 * (like a set-top box). Without it every press waited for the previous load to finish.
 *
 * [pokeOnCommit]: M444 — when the zap bar is running (overlay off), switching MUST NOT poke
 * the classic controls, otherwise there are two bars on screen at once (Xiaomi Mi Box).
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

    /** M407: preview info of a channel without loading the stream (for fast zapping). */
    private fun preview(i: Int) {
        live.indexState.value = i
        live.titleState.value = live.names.getOrElse(i) { "" }
        live.uuidState.value = live.uuids.getOrNull(i) ?: live.uuidState.value
        live.showProgramme(LivePlaylist.channels.getOrNull(i))
    }

    /** Switches to the neighbouring live channel (delta +1 / -1) with a debounce. */
    fun switchLive(delta: Int) {
        haptic()
        if (live.uuids.size < 2 || live.index < 0) return
        val n = live.uuids.size
        // from the current target (if one is already pending) or from the current channel
        val from = if (pendingIndex >= 0) pendingIndex else live.index
        val target = ((from + delta) % n + n) % n
        pendingIndex = target
        preview(target)                 // immediate response on screen
        handler.removeCallbacks(commitRunnable)
        handler.postDelayed(commitRunnable, DEBOUNCE_MS)
    }

    fun cancel() { handler.removeCallbacks(commitRunnable) }

    private companion object { const val DEBOUNCE_MS = 350L }
}
