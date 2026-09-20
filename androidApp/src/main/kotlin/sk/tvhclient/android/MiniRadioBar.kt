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
 * Mini radio player (M340) — a bar above the bottom navigation while the radio plays
 * in the background via RadioPlayerService. Tapping the bar opens the full player,
 * the buttons pause/stop it. Shown in both modern and classic mode
 * (M624), only while the service is active.
 */
@Composable
fun MiniRadioBar() {
    // M624: the bar in classic mode too (like Spotify) — colours come from MaterialTheme,
    // so in classic it picks up its palette. The TV panel (TvRadioHomePanel) stays modern.
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
    // EPG line only while the programme is actually running (after it ends it would be misleading)
    val epgLine = if (epgTitle.isNotBlank() &&
        (epgStop <= 0L || System.currentTimeMillis() / 1000 < epgStop)
    ) epgTitle else ""

    Row(
        Modifier
            .fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp)
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


/**
 * Radio panel on the TV home screen (M344-fix2) — part of the layout on the right
 * next to the hero block (not a floating overlay). Modern language: 18dp card with
 * an outline, teal small-caps heading, picon plate, EPG line and D-pad
 * focusable controls (card = full player, ⏯, ✕).
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
            // Variant B (M344-fix12): solid colour with rounded corners on the right,
            // towards the left the panel dissolves completely — a mirror of the hero card, which
            // dissolves towards the right in the same way; the two meet softly in the middle.
            .clip(RoundedCornerShape(18.dp))
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to androidx.compose.ui.graphics.Color.Transparent,
                    0.45f to cs.surfaceContainerLow.copy(alpha = 0.6f),
                    1f to cs.surfaceContainerHighest
                )
            )
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
            // ✕ close (left) · ⏯ stop/play · ⏭ next station (right)
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
