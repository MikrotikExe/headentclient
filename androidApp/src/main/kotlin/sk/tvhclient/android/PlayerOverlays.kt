package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import sk.tvhclient.shared.model.TvhServer
import kotlin.math.roundToInt

/*
 * M630: the small player overlays extracted from PlayerUi (PlayerActivity.kt), which was
 * at the edge of the 64 KB JVM method limit. Pure composables with few parameters,
 * with no ties to the activity. They are called from the root Box of PlayerUi in the original order.
 */

/** Audio-only (radio): instead of black, the centred station logo; when the channel list is
 *  open on TV, the logo moves into the preview rectangle [previewRect]. */
@Composable
internal fun RadioCenterLogo(centerLogoUrl: String?, server: TvhServer?, previewRect: Rect?) {
    val ctxLogo = LocalContext.current
    val cfgLogo = LocalConfiguration.current
    val density = LocalDensity.current
    val logoLoader = remember(server?.id) { PiconImageLoader.get(ctxLogo, server) }
    val side = if (previewRect != null) {
        with(density) { (minOf(previewRect.width, previewRect.height) * 0.55f).toDp() }
    } else (minOf(cfgLogo.screenWidthDp, cfgLogo.screenHeightDp) * 0.42f).dp
    val logoBoxMod = if (previewRect != null) {
        Modifier
            .absoluteOffset { IntOffset(previewRect.left.roundToInt(), previewRect.top.roundToInt()) }
            .size(with(density) { previewRect.width.toDp() }, with(density) { previewRect.height.toDp() })
    } else Modifier.fillMaxSize()
    Box(logoBoxMod, contentAlignment = Alignment.Center) {
        var logoOk by remember(centerLogoUrl) { mutableStateOf(centerLogoUrl != null) }
        if (centerLogoUrl != null && logoOk) {
            AsyncImage(
                model = ImageRequest.Builder(ctxLogo).data(centerLogoUrl).build(),
                contentDescription = null,
                imageLoader = logoLoader,
                contentScale = ContentScale.Fit,
                onState = { st -> if (st is AsyncImagePainter.State.Error) logoOk = false },
                modifier = Modifier.size(side)
            )
        } else {
            // The default radio graphic (when the station has no picon or it does not load)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(side * 0.7f)
                        .clip(RoundedCornerShape(side.value.dp * 0.12f))
                        .background(Brush.verticalGradient(listOf(playerTrack(), playerTrack()))),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Radio, contentDescription = null,
                        tint = playerFg(), modifier = Modifier.size(side * 0.42f))
                }
            }
        }
    }
}

/** Reconnection indicator (network dropout during a live broadcast). */
@Composable
internal fun ReconnectingOverlay() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color(0xCC000000), RoundedCornerShape(14.dp))
                .padding(horizontal = 28.dp, vertical = 22.dp)
        ) {
            CircularProgressIndicator(color = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.reconnecting), color = Color.White,
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** The spinner in the middle during timeshift seeking (resync). */
@Composable
internal fun SeekingSpinner() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = playerFg())
    }
}

/** A YouTube-style hint on a double tap (a 10 s jump), on the side of the tap, accumulating. */
@Composable
internal fun SeekHintOverlay(seekHint: Int) {
    val fwd = seekHint > 0
    val label = if (fwd) "+$seekHint  ›" else "‹  $seekHint"
    Box(
        Modifier.fillMaxSize().padding(horizontal = 44.dp),
        contentAlignment = if (fwd) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Text(label, color = Color.White,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow(color = Color(0xB3000000), blurRadius = 14f)))
    }
}

/** MX Player overlay: volume or brightness (centred). The caller guarantees that at least one is >= 0. */
@Composable
internal fun GestureLevelOverlay(volPct: Int, brightPct: Int) {
    val isVol = volPct >= 0
    val pct = if (isVol) volPct else brightPct
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xAA000000))
                .padding(horizontal = 22.dp, vertical = 16.dp)
        ) {
            val gLabel = stringResource(if (isVol) R.string.player_volume else R.string.player_brightness)
            Text("$gLabel  $pct%", color = Color.White, style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(
                progress = { pct / 100f },
                modifier = Modifier.width(170.dp).padding(top = 10.dp),
                color = playerAccent(),
                trackColor = Color(0x55FFFFFF)
            )
        }
    }
}

/** Seek-scrub overlay (top centre): ±m:ss or ±Ns depending on [scrubSec]. */
@Composable
internal fun ScrubSecondsOverlay(scrubSec: Int) {
    val a = kotlin.math.abs(scrubSec)
    val mm = a / 60
    val ss = a % 60
    val core = if (mm > 0) "$mm:" + ss.toString().padStart(2, '0') else "${ss}s"
    val label = (if (scrubSec >= 0) "+" else "−") + core
    Box(Modifier.fillMaxSize().padding(top = 56.dp), contentAlignment = Alignment.TopCenter) {
        Text(label, color = Color.White,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.SemiBold,
                shadow = Shadow(color = Color(0xB3000000), blurRadius = 14f)))
    }
}

/** Overlay with the channel number currently being entered (top centre of the root Box). */
@Composable
internal fun BoxScope.NumberEntryOverlay(numberEntry: String) {
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .padding(top = 40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(playerScrim())
            .padding(horizontal = 28.dp, vertical = 14.dp)
    ) {
        Text(numberEntry, color = playerFg(), fontSize = 48.sp)
    }
}

/** Programme info (detail) — an overlay in the player's style (M280). [recordLabel] null = no
 *  recording item; M490: on TV the item is selected with the down arrow ([recordSelected]),
 *  because the dialog swallows the keys and OK closes it. */
@Composable
internal fun ChannelInfoOverlay(
    channel: String,
    title: String,
    time: String,
    desc: String,
    recordLabel: String?,
    recordActive: Boolean,
    recordSelected: Boolean,
    onClose: () -> Unit,
    onRecord: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color(0xCC0B1220))
            .clickable { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.78f)
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B2433))
                .padding(horizontal = 24.dp, vertical = 24.dp)
        ) {
            if (channel.isNotBlank()) {
                Text(channel, color = Color(0xFF6699FF),
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
            }
            Text(title.ifBlank { channel }, color = Color.White,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            if (time.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(time, color = Color(0xFFB9C2D0), style = MaterialTheme.typography.titleMedium)
            }
            if (desc.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(desc, color = Color(0xFFD7DEE8), style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState()))
            }
            if (recordLabel != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (recordSelected) Color(0x553B82F6) else Color.Transparent)
                        .border(
                            1.dp, if (recordSelected) Color(0xFF3B82F6) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .clickable { onClose(); onRecord() }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (recordActive) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        contentDescription = null,
                        tint = if (recordSelected) Color(0xFF6699FF) else Color(0xFFB9C2D0),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(recordLabel, color = if (recordSelected) Color.White else Color(0xFFD7DEE8),
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}
