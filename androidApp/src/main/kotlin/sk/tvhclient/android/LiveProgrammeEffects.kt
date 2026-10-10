package sk.tvhclient.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * M663: live programme effects in PlayerUi (split out because of the 64 KB method limit):
 * the one-second tick [onTick] (liveNowSec) and loading the channel's full EPG right after a switch
 * and then whenever the current programme runs out (description + next programme). The programme state is
 * held by PlayerUi (progStart/progStop… `by remember(liveChannelUuid)`), getters
 * and a single [onProgramme] callback come in here. The conditions and LaunchedEffect keys are identical to the original code.
 */
@Composable
internal fun LiveProgrammeEffects(
    seekable: Boolean,
    liveChannelUuid: String?,
    server: TvhServer?,
    hasLiveProg: Boolean,
    progStart: () -> Long,
    progStop: () -> Long,
    onTick: (nowSec: Long) -> Unit,
    onProgramme: (cur: EpgEvent, next: EpgEvent?) -> Unit
) {
    if (!seekable && liveChannelUuid != null && server != null) {
        LaunchedEffect(Unit) {
            // tick every second
            while (true) {
                onTick(System.currentTimeMillis() / 1000)
                kotlinx.coroutines.delay(1000)
            }
        }
        // M720: the live window (LiveEpg) — the programme on air and the next one straight from the
        // server's push, every change applied at once (a title fixed in the EPG, an overrun, the
        // rollover at the end); the getEvents/grid fetch below is only the fallback until the
        // window is there
        LaunchedEffect(liveChannelUuid) {
            val live = Tvh.liveEpg(server)
            var lastApply = 0L
            live.revision.collect {
                val since = System.currentTimeMillis() - lastApply   // at most once a second (conflated)
                if (since < 1_000) kotlinx.coroutines.delay(1_000 - since)
                lastApply = System.currentTimeMillis()
                val now = System.currentTimeMillis() / 1000
                val cur = live.currentFor(liveChannelUuid, now) ?: return@collect
                val nx = live.snapshot[liveChannelUuid]?.firstOrNull { it.start >= cur.stop && it.eventId != cur.eventId }
                onProgramme(cur, nx)
            }
        }
        LaunchedEffect(liveChannelUuid) {
            // right after a switch load the full EPG (description + next programme),
            // then refresh when the current programme runs out
            var firstDone = false
            var overrun = false   // M715: the current programme runs past its end (server: running)
            var lastFetch = 0L
            val live = sk.tvhclient.shared.epg.LiveEpg.store(server.id)
            while (true) {
                val now = System.currentTimeMillis() / 1000
                // M715: while it overruns, its end is unknown -> ask again every 30 s, not every 5 s
                val due = now >= progStop() && (!overrun || now - lastFetch >= 30)
                if (!firstDone || progStart() == 0L || progStop() == 0L || due) {
                    lastFetch = now
                    // M720: from the live window when it is fresh — no request at all
                    val liveCur = if (live.isFresh(now)) live.currentFor(liveChannelUuid, now) else null
                    if (liveCur != null) {
                        overrun = liveCur.running
                        val nx = live.snapshot[liveChannelUuid]?.firstOrNull { it.start >= liveCur.stop && it.eventId != liveCur.eventId }
                        onProgramme(liveCur, nx)
                        firstDone = true
                        kotlinx.coroutines.delay(5000)
                        continue
                    }
                    val list = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val api = Tvh.apiFor(server)
                            try { Tvh.fetchEpgForChannel(server, api, liveChannelUuid) }
                            finally { api.close() }
                        }
                    } catch (e: Exception) { emptyList() }
                    val cur = list.firstOrNull { it.isCurrentAt(now) }
                    overrun = cur?.running == true
                    if (cur != null) {
                        val nx = list.firstOrNull { it.start >= cur.stop }
                        onProgramme(cur, nx)
                    }
                    firstDone = true
                }
                kotlinx.coroutines.delay(5000)
            }
        }
    } else if (hasLiveProg) {
        LaunchedEffect(Unit) {
            while (true) {
                onTick(System.currentTimeMillis() / 1000)
                kotlinx.coroutines.delay(1000)
            }
        }
    }
}
