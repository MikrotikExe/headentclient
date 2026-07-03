package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moderny TV overlay: spodny "channel surf" — rad kariet kanalov + ovladacia
 * lista s transportom v strede (Zvuk | -30 Pauza +30 | Titulky, po krajoch
 * Casovac/TV program a Info). Pri aktivnom timeshiftu sa nad listou zobrazi
 * seek bar a fokusovana karta upozorni, ze prepnutie posun v case ukonci.
 * Fokus riadi Activity cez indexy (row/card/strip) — D-pad routing v
 * dispatchKeyEvent; tu sa len kresli. Klasicky rezim nedotknuty.
 */
@Composable
internal fun ModernTvOverlay(
    channels: List<LivePlaylist.LiveChannel>,
    currentIndex: Int,
    cardIndex: Int,
    focusRow: Int,
    stripIndex: Int,
    stripIds: List<String>,
    isPlaying: Boolean,
    tsEngaged: Boolean,
    tsOffsetMs: Long,
    tsMaxMs: Long,
    imageLoader: ImageLoader,
) {
    val ctx = LocalContext.current
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    val nowSec = now / 1000
    val locale = Locale.getDefault()
    val hhmm = remember(locale) { SimpleDateFormat("HH:mm", locale) }
    val listState = rememberLazyListState()
    LaunchedEffect(cardIndex) {
        // Blizky posun animuj; vzdialeny skok (napr. wrap 1 -> 300 pri chprev)
        // skoc okamzite — inak sa list "prehrabava" cez vsetky kanaly
        val target = (cardIndex - 1).coerceAtLeast(0)
        val visible = listState.layoutInfo.visibleItemsInfo
        val near = visible.any { kotlin.math.abs(it.index - target) <= 6 }
        runCatching {
            if (near) listState.animateScrollToItem(target)
            else listState.scrollToItem(target)
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color(0x000A1124), Color(0xD90A1124), Color(0xF70A1124))
                    )
                )
                .padding(bottom = 14.dp)
        ) {
            // hinty
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.mtv_hint_all), color = Color(0xFF8FA6C8), fontSize = 12.sp)
                Text(stringResource(R.string.mtv_hint_nav), color = Color(0xFF8FA6C8), fontSize = 12.sp)
            }

            // ===== karty kanalov =====
            LazyRow(
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 28.dp)
            ) {
                itemsIndexed(channels, key = { _, c -> c.uuid }) { idx, c ->
                    val focused = focusRow == 0 && idx == cardIndex
                    ModernSurfCard(
                        ch = c, focused = focused, playingHere = idx == currentIndex,
                        tsEngaged = tsEngaged, nowSec = nowSec, hhmm = hhmm,
                        imageLoader = imageLoader, ctx = ctx
                    )
                }
            }

            // ===== timeshift seek bar =====
            if (tsEngaged) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val offSec = (tsOffsetMs / 1000).toInt()
                    Text(String.format("\u2212%02d:%02d", offSec / 60, offSec % 60),
                        color = Color(0xFF8FA6C8), fontSize = 12.sp)
                    Spacer(Modifier.width(10.dp))
                    val frac = if (tsMaxMs > 0)
                        (1f - tsOffsetMs.toFloat() / tsMaxMs).coerceIn(0f, 1f) else 1f
                    LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)),
                        color = playerAccent(), trackColor = Color(0xFF1B2C52)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(stringResource(R.string.mtv_live) + " \u203A",
                        color = Color(0xFF7FE3BF), fontSize = 12.sp)
                }
            } else {
                Spacer(Modifier.height(8.dp))
            }

            // ===== ovladacia lista: transport v strede =====
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                stripIds.forEachIndexed { i, id ->
                    val focused = focusRow == 1 && i == stripIndex
                    when (id) {
                        "play" -> ModernStripPlay(isPlaying, focused)
                        "tsrew" -> ModernStripCircle(Icons.Default.Replay30, focused)
                        "tsff" -> ModernStripCircle(Icons.Default.Forward30, focused)
                        "chprev" -> ModernStripCircle(Icons.Default.SkipPrevious, focused)
                        "chnext" -> ModernStripCircle(Icons.Default.SkipNext, focused)
                        else -> ModernStripPill(
                            icon = when (id) {
                                "more" -> Icons.Default.MoreHoriz
                                "list" -> Icons.AutoMirrored.Filled.List
                                "sleep" -> Icons.Default.Timer
                                "epg" -> Icons.Default.GridView
                                "audio" -> Icons.Default.MusicNote
                                "subs" -> Icons.Default.ClosedCaption
                                else -> Icons.Default.Info
                            },
                            label = when (id) {
                                "more" -> stringResource(R.string.pm_more)
                                "list" -> stringResource(R.string.tab_channels)
                                "sleep" -> stringResource(R.string.sleep_timer)
                                "epg" -> stringResource(R.string.home_tv_program)
                                "audio" -> stringResource(R.string.pm_audio)
                                "subs" -> stringResource(R.string.track_subtitles)
                                else -> stringResource(R.string.pm_info)
                            },
                            focused = focused
                        )
                    }
                    if (i < stripIds.size - 1) Spacer(Modifier.width(10.dp))
                }
            }
        }
    }
}

@Composable
private fun ModernSurfCard(
    ch: LivePlaylist.LiveChannel,
    focused: Boolean,
    playingHere: Boolean,
    tsEngaged: Boolean,
    nowSec: Long,
    hhmm: SimpleDateFormat,
    imageLoader: ImageLoader,
    ctx: android.content.Context,
) {
    val w = if (focused) 250.dp else 190.dp
    Column(
        Modifier
            .width(w)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Color(0xFF12294E) else Color(0xE60F1E3D))
            .then(
                if (focused) Modifier.border(3.dp, playerAccent(), RoundedCornerShape(14.dp))
                else Modifier.border(1.dp, Color(0xFF1E3A6E), RoundedCornerShape(14.dp))
            )
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp, 32.dp).clip(RoundedCornerShape(8.dp)).background(piconBackground()),
                contentAlignment = Alignment.Center
            ) {
                if (ch.piconUrl != null) {
                    AsyncImage(
                        model = ImageRequest.Builder(ctx).data(ch.piconUrl).size(120).build(),
                        contentDescription = null, imageLoader = imageLoader,
                        modifier = Modifier.fillMaxSize().padding(2.dp)
                    )
                } else Text(ch.name.take(2).uppercase(), color = playerFg(), fontSize = 10.sp)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                (if (ch.number > 0) "${ch.number} · " else "") + ch.name,
                color = playerFgDim(), fontSize = 12.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            if (playingHere) {
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(6.dp).clip(CircleShape).background(playerAccent()))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            ch.nowTitle.ifBlank { ch.name },
            color = playerFg(),
            fontSize = if (focused) 16.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        if (ch.nowStart > 0 && ch.nowStop > ch.nowStart) {
            val range = hhmm.format(Date(ch.nowStart * 1000)) + " – " + hhmm.format(Date(ch.nowStop * 1000))
            val left = if (ch.nowStop > nowSec) " · " + ((ch.nowStop - nowSec) / 60) + " min" else ""
            Text(
                range + (if (focused) left else ""),
                color = playerFgDim(), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (focused && ch.nextTitle.isNotBlank()) {
            Text(
                stringResource(R.string.mh_next) + " " + ch.nextTitle,
                color = playerFgFaint(), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        if (ch.nowStart > 0 && ch.nowStop > ch.nowStart) {
            val total = (ch.nowStop - ch.nowStart).coerceAtLeast(1)
            val frac = (nowSec - ch.nowStart).coerceIn(0, total).toFloat() / total
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { frac },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = playerAccent(), trackColor = Color(0xFF1B2C52)
            )
        }
        if (focused && tsEngaged) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.mtv_ts_warn), color = Color(0xFFFFB74D), fontSize = 11.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ModernStripPill(icon: ImageVector, label: String, focused: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (focused) playerSelTint() else Color(0xFF13234A))
            .then(
                if (focused) Modifier.border(2.dp, playerAccent(), RoundedCornerShape(999.dp))
                else Modifier.border(1.dp, Color(0xFF27407A), RoundedCornerShape(999.dp))
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = playerFg(), modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(label, color = playerFg(), fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
private fun ModernStripCircle(icon: ImageVector, focused: Boolean) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (focused) playerSelTint() else Color(0xFF13234A))
            .then(
                if (focused) Modifier.border(2.dp, playerAccent(), CircleShape)
                else Modifier.border(1.dp, Color(0xFF27407A), CircleShape)
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = playerFg(), modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun ModernStripPlay(isPlaying: Boolean, focused: Boolean) {
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(playerAccent())
            .then(if (focused) Modifier.border(3.dp, Color(0xFF7FE3BF), CircleShape) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = null, tint = Color(0xFF04120C), modifier = Modifier.size(30.dp)
        )
    }
}
