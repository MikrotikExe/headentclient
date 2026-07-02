package sk.tvhclient.android

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest

/**
 * Moderny riadok zoznamu v hlavnych taboch (Kanaly, Radio): karta s velkym
 * piconom, nazvom kanala drobne NAD tucnym nazvom relacie, riadkom "Dalej:",
 * teal progresom a vpravo zostavajucimi minutami + decentnym cislom.
 * Zdielany komponent skratkovej vrstvy — klasicky riadok ostava nedotknuty,
 * obrazovky vetvia cez isModernUi(). Farby cez roly temy (svetly aj tmavy).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ModernChannelTabRow(
    name: String,
    number: Int?,
    piconUrl: String?,
    nowTitle: String?,
    nowStart: Long,
    nowStop: Long,
    nextTitle: String?,
    nowSec: Long,
    recording: Boolean,
    locked: Boolean,
    hidden: Boolean,
    loader: ImageLoader,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val baseBg = if (isLightTheme()) cs.surfaceContainerLowest else cs.surfaceContainer
    val cardBg = if (highlighted) cs.primaryContainer.copy(alpha = if (isLightTheme()) 0.5f else 0.4f) else baseBg
    Row(
        modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(cardBg)
            .dpadFocusable(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(64.dp, 52.dp).clip(RoundedCornerShape(14.dp)).background(piconBackground()),
            contentAlignment = Alignment.Center
        ) {
            if (piconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(ctx).data(piconUrl).size(160).build(),
                    contentDescription = name,
                    imageLoader = loader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
            } else {
                Text(name.take(3).uppercase(), color = cs.onSurface, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = cs.onSurfaceVariant, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false))
                if (recording) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE53935)))
                }
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Lock, contentDescription = null,
                        tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
                if (hidden) {
                    Spacer(Modifier.width(6.dp))
                    Text("\uD83D\uDEAB", color = cs.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            Text(
                nowTitle?.takeIf { it.isNotBlank() } ?: name,
                color = cs.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (!nextTitle.isNullOrBlank()) {
                Text(
                    stringResource(R.string.mh_next) + " " + nextTitle,
                    color = cs.onSurfaceVariant.copy(alpha = 0.75f), fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
            if (nowStart in 1 until nowStop) {
                val total = (nowStop - nowStart).coerceAtLeast(1)
                val frac = (nowSec - nowStart).coerceIn(0, total).toFloat() / total
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { frac },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    trackColor = cs.outlineVariant
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            if (number != null && number > 0) {
                Text(number.toString(), color = cs.primary,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            val left = if (nowStop > nowSec && nowStart > 0) ((nowStop - nowSec) / 60).toInt() else null
            if (left != null) {
                Text("$left min", color = cs.onSurfaceVariant, fontSize = 12.sp)
            }
        }
    }
}
