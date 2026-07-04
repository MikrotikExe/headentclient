package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.runtime.remember
import sk.tvhclient.shared.Tvh

/**
 * Mini prehravac radia (M340) — lista nad spodnou navigaciou, kym radio hra
 * na pozadi cez RadioPlayerService. Klik na listu otvori plny prehravac,
 * tlacidla pauzuju/zastavia. Zobrazuje sa len v modernom rezime a len ked
 * je service aktivny.
 */
@Composable
fun MiniRadioBar() {
    if (!isModernUi()) return
    val active by RadioCenter.active
    if (!active) return
    val playing by RadioCenter.playing
    val name by RadioCenter.stationName
    val picon by RadioCenter.piconUrl
    val epgTitle by RadioCenter.nowTitle
    val epgStop by RadioCenter.nowStop
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val server = remember { Tvh.store.active() }
    val loader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
    // EPG riadok len kym relacia realne bezi (po konci by bol zavadzajuci)
    val epgLine = if (epgTitle.isNotBlank() &&
        (epgStop <= 0L || System.currentTimeMillis() / 1000 < epgStop)
    ) epgTitle else ""

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (isLightTheme()) cs.surfaceContainer else cs.surfaceContainerHigh)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
            .clickable { RadioCenter.openFull(ctx) }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(10.dp))
                .background(cs.primaryContainer.copy(alpha = 0.6f)),
            contentAlignment = Alignment.Center
        ) {
            if (picon != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(picon).build(),
                    contentDescription = null,
                    imageLoader = loader,
                    modifier = Modifier.size(28.dp)
                )
            } else {
                Icon(Icons.Filled.Radio, contentDescription = null,
                    tint = cs.primary, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, color = cs.onSurface, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                epgLine.ifBlank { stringResource(R.string.tab_radio) },
                color = cs.onSurfaceVariant,
                fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(36.dp).clip(CircleShape).background(cs.primary)
                .clickable { RadioCenter.toggle(ctx) },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                tint = cs.onPrimary, modifier = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.size(32.dp).clip(CircleShape)
                .background(cs.surfaceContainerHighest)
                .clickable { RadioCenter.stop(ctx) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pm_close),
                tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}
