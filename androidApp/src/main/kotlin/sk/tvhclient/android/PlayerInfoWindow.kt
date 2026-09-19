package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * M661: info okno prehrávača vyclenené z PlayerUi (PlayerActivity.kt) — limit 64 kB na metódu.
 * Čistý composable; stav (showInfo) drží volajúci a zatvára ho cez [setShowInfo].
 */

/** Info okno: detail práve bežiacej relácie (INFO kláves / tlačidlo). Volať len keď je showInfo == true. */
@Composable
internal fun PlayerInfoWindow(
    setShowInfo: (Boolean) -> Unit,
    title: String,
    seekable: Boolean,
    progStart: Long,
    progStop: Long,
    progTitle: String,
    progDesc: String,
    nextTitle: String,
    nextStart: Long,
    nextStop: Long,
    liveNowSec: Long,
    liveChannels: List<LivePlaylist.LiveChannel>,
    liveCurrentIndex: Int,
    dvrActivity: PlayerActivity?
) {
    // Info okno: detail prave beziacej relacie (INFO kláves / tlacidlo)
    androidx.activity.compose.BackHandler { setShowInfo(false) }
    val clk: (Long) -> String = { sec ->
        if (sec <= 0) "" else java.text.SimpleDateFormat(sk.tvhclient.shared.TimeFormatConfig.hm, java.util.Locale.getDefault())
            .format(java.util.Date(sec * 1000))
    }
    val tRange = if (progStart > 0 && progStop > progStart)
        clk(progStart) + " \u2013 " + clk(progStop) else ""
    Box(
        Modifier
            .fillMaxSize()
            .background(playerScrim())
            .clickable { setShowInfo(false) },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Surface(
            color = playerScrim(),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth(0.72f).widthIn(max = 560.dp)
        ) {
            Column(
                Modifier
                    .padding(28.dp)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState())
            ) {
                // hlavicka kanala (len pri zivom vysielani)
                val infoCh = liveChannels.getOrNull(liveCurrentIndex)
                if (infoCh != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (infoCh.number > 0) {
                            Text(
                                "${infoCh.number}",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            infoCh.name,
                            color = playerFgDim(),
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    progTitle.ifBlank { title },
                    color = playerFg(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
                if (tRange.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(tRange, color = playerFgDim(), fontSize = 15.sp)
                }
                // priebeh + zostavajuci cas (len zive vysielanie)
                if (!seekable && progStart > 0 && progStop > progStart) {
                    val totalI = (progStop - progStart).coerceAtLeast(1)
                    val fracI = ((liveNowSec - progStart).toFloat() / totalI.toFloat())
                        .coerceIn(0f, 1f)
                    val remainI = ((progStop - liveNowSec) / 60).coerceAtLeast(0)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { fracI },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        trackColor = playerTrack()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.time_remaining, remainI), color = playerFgFaint(), fontSize = 13.sp)
                }
                if (progDesc.isNotBlank()) {
                    Spacer(Modifier.height(14.dp))
                    Text(progDesc, color = playerFgDim(), fontSize = 16.sp, lineHeight = 22.sp)
                }
                // M490: nahravanie aj z info okna (telefon — dotyk, bez fokusu)
                if (dvrActivity?.dvrRecordVisible() == true) {
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { setShowInfo(false); dvrActivity?.toggleRecordCurrent() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.Icon(
                            if (dvrActivity?.dvrExistingState?.value != null) Icons.Default.Stop
                            else Icons.Default.FiberManualRecord,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(
                            if (dvrActivity?.dvrExistingState?.value != null)
                                R.string.dvr_rec_cancel_button else R.string.dvr_rec_button
                        ))
                    }
                }
                if (nextTitle.isNotBlank()) {
                    Spacer(Modifier.height(16.dp))
                    val nr = when {
                        nextStart > 0 && nextStop > nextStart ->
                            clk(nextStart) + " \u2013 " + clk(nextStop) + "  "
                        nextStart > 0 -> clk(nextStart) + "  "
                        else -> ""
                    }
                    Text(
                        // M491: bolo natvrdo po slovensky
                        stringResource(R.string.mh_next) + " " + nr + nextTitle,
                        color = playerFgFaint(),
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}
