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
        LaunchedEffect(liveChannelUuid) {
            // right after a switch load the full EPG (description + next programme),
            // then refresh when the current programme runs out
            var firstDone = false
            while (true) {
                val now = System.currentTimeMillis() / 1000
                if (!firstDone || progStart() == 0L || progStop() == 0L || now >= progStop()) {
                    val list = try {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val api = Tvh.apiFor(server)
                            try { Tvh.fetchEpgForChannel(server, api, liveChannelUuid) }
                            finally { api.close() }
                        }
                    } catch (e: Exception) { emptyList() }
                    val cur = list.firstOrNull { it.start <= now && now < it.stop }
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
