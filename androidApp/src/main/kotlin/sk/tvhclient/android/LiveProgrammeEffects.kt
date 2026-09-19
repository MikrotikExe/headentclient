package sk.tvhclient.android

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * M663: efekty živej relácie v PlayerUi (vyclenené kvôli 64 KB limitu metódy):
 * sekundový tik [onTick] (liveNowSec) a načítanie plného EPG kanála hneď po prepnutí
 * a potom vždy, keď aktuálna relácia dobehne (popis + ďalšia relácia). Stav relácie
 * drží PlayerUi (progStart/progStop… `by remember(liveChannelUuid)`), sem chodia gettery
 * a jeden callback [onProgramme]. Podmienky a kľúče LaunchedEffect sú zhodné s pôvodným kódom.
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
            // tik kazdu sekundu
            while (true) {
                onTick(System.currentTimeMillis() / 1000)
                kotlinx.coroutines.delay(1000)
            }
        }
        LaunchedEffect(liveChannelUuid) {
            // hned po prepnuti nacitaj plne EPG (popis + dalsia relacia),
            // potom obnovuj ked aktualna relacia dobehne
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
