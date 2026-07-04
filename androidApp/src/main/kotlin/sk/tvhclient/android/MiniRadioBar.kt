package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
fun MiniRadioBar() = MiniRadioCore(tv = false)

/** TV variant (M342): sirsia karta v launcheri, D-pad fokusovatelne prvky. */
@Composable
fun TvMiniRadioBar(modifier: Modifier = Modifier) = MiniRadioCore(tv = true, modifier = modifier)

@Composable
private fun MiniRadioCore(tv: Boolean, modifier: Modifier = Modifier) {
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
        modifier
            .then(
                if (tv) Modifier.widthIn(min = 360.dp, max = 460.dp)
                else Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
            )
            .clip(RoundedCornerShape(16.dp))
            .background(if (isLightTheme()) cs.surfaceContainer else cs.surfaceContainerHigh)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
            .then(if (tv) Modifier.dpadFocusable(RoundedCornerShape(16.dp)) else Modifier)
            .clickable { RadioCenter.openFull(ctx) }
            .padding(horizontal = if (tv) 16.dp else 12.dp, vertical = if (tv) 12.dp else 8.dp),
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
            Modifier.size(if (tv) 42.dp else 36.dp).clip(CircleShape).background(cs.primary)
                .then(if (tv) Modifier.dpadFocusable(CircleShape) else Modifier)
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
            Modifier.size(if (tv) 38.dp else 32.dp).clip(CircleShape)
                .background(cs.surfaceContainerHighest)
                .then(if (tv) Modifier.dpadFocusable(CircleShape) else Modifier)
                .clickable { RadioCenter.stop(ctx) },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pm_close),
                tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
    }
}


/**
 * Radio panel na TV domovskej obrazovke (M344-fix2) — sucast layoutu vpravo
 * vedla hero bloku (nie plavajuci overlay). Moderny jazyk: karta 18dp s
 * obrysom, teal kapitalkovy nadpis, picon plat, EPG riadok a D-pad
 * fokusovatelne ovladanie (karta = plny prehravac, ⏯, ✕).
 */
@Composable
fun TvRadioHomePanel(modifier: Modifier = Modifier) {
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
    val epgLine = if (epgTitle.isNotBlank() &&
        (epgStop <= 0L || System.currentTimeMillis() / 1000 < epgStop)
    ) epgTitle else ""

    Column(
        modifier
            // Radialna ZIARA namiesto orezaneho gradientu (M344-fix8): svetlo
            // vychadza z pravej strany a vsetkymi smermi mekko dozneje do
            // uplneho stratena — ziadny stvorec, ziadne hrany, ziadne pasy.
            // (Kazdy pravouhly gradient v clipnutom tvare niekde ukaze hranu,
            // preto drawBehind s radialom kotvenym na pravy okraj panelu.)
            .drawBehind {
                drawRect(
                    brush = androidx.compose.ui.graphics.Brush.radialGradient(
                        0f to cs.surfaceContainerHighest,
                        0.55f to cs.surfaceContainerLow.copy(alpha = 0.55f),
                        1f to androidx.compose.ui.graphics.Color.Transparent,
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.86f, size.height * 0.45f),
                        radius = size.width * 0.95f
                    )
                )
            }
            .padding(18.dp)
    ) {
        Text(
            stringResource(R.string.tab_radio).uppercase(),
            color = cs.primary, fontSize = 11.sp,
            fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(12.dp))
                .dpadFocusable(RoundedCornerShape(12.dp))
                .clickable { RadioCenter.openFull(ctx) }
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(12.dp))
                    .background(cs.primaryContainer.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                if (picon != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(picon).build(),
                        contentDescription = null,
                        imageLoader = loader,
                        modifier = Modifier.size(38.dp)
                    )
                } else {
                    Icon(Icons.Filled.Radio, contentDescription = null,
                        tint = cs.primary, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(name, color = cs.onSurface, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (epgLine.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(epgLine, color = cs.onSurfaceVariant, fontSize = 12.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.weight(1f, fill = true))
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // ✕ ukoncit (vlavo) · ⏯ stop/play · ⏭ dalsia stanica (vpravo)
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(cs.surfaceContainerHighest)
                    .dpadFocusable(CircleShape)
                    .clickable { RadioCenter.stop(ctx) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.pm_close),
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(cs.surfaceContainerHighest)
                    .dpadFocusable(CircleShape)
                    .clickable { RadioCenter.switchStation(ctx, -1) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = null,
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(cs.primary)
                    .dpadFocusable(CircleShape)
                    .clickable { RadioCenter.toggle(ctx) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                    tint = cs.onPrimary, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(cs.surfaceContainerHighest)
                    .dpadFocusable(CircleShape)
                    .clickable { RadioCenter.switchStation(ctx, +1) },
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.SkipNext, contentDescription = null,
                    tint = cs.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
    }
}
