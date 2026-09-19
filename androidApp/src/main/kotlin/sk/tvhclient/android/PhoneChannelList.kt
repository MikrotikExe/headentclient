package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sk.tvhclient.shared.model.TvhServer

/**
 * M631: zoznam kanálov priamo v prehrávači na TELEFÓNE — vysúva sa zhora podľa [listFrac]
 * (0..1, animuje volajúci), hlavička je úchyt (ťah hore / klik zatvorí), spodných ~10 %
 * je zóna na zatvorenie ťahom. Riadky: moderný (ModernPlayerChannelRow) alebo klasický.
 * Vyclenené z PlayerUi (PlayerActivity.kt), správanie nezmenené.
 */
@Composable
internal fun PhoneChannelListOverlay(
    listFrac: Float,
    liveChannels: List<LivePlaylist.LiveChannel>,
    server: TvhServer?,
    serverId: String?,
    liveCurrentIndex: Int,
    channelNavIndex: Int,
    epgLoading: Boolean,
    lockTick: Int,
    liveNowSec: Long,
    onSelectChannel: (Int) -> Unit,
    onChannelLongPress: (Int) -> Unit,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val loader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = liveCurrentIndex.coerceAtLeast(0)
    )
    // efektivny vyber: pri D-pad navigacii navIndex, inak aktualny kanal
    val sel = if (channelNavIndex >= 0) channelNavIndex else liveCurrentIndex
    LaunchedEffect(channelNavIndex) {
        val i = channelNavIndex
        if (i in liveChannels.indices) {
            val vis = listState.layoutInfo.visibleItemsInfo
            val first = vis.firstOrNull()?.index ?: 0
            val last = vis.lastOrNull()?.index ?: 0
            // skoc len ked je ciel mimo obrazovky — okamzite, bez pretacania cez vsetky polozky
            if (vis.isEmpty() || i < first || i > last) listState.scrollToItem(i)
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
      val fullH = maxHeight
      Column(
        Modifier
            .fillMaxWidth()
            .height(fullH * listFrac.coerceIn(0f, 1f))   // rastie zhora nadol
            .align(Alignment.TopStart)
            .clipToBounds()
            .background(playerScrim())
      ) {
        Column(Modifier.fillMaxWidth().height(fullH)) {   // obsah v plnej vyske, klipovany zhora
        // hlavicka = uchyt: tah hore zatvori (nebrani rolovaniu zoznamu), klik tiez zatvori
        Column(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var dyh = 0f
                    detectVerticalDragGestures(
                        onDragStart = { dyh = 0f },
                        onDragEnd = { if (dyh < -60f) onClose() }
                    ) { _, amount -> dyh += amount }
                }
                .clickable { onClose() }
        ) {
            Box(
                Modifier
                    .padding(top = 8.dp)
                    .align(Alignment.CenterHorizontally)
                    .size(width = 40.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(playerFgDim())
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("\u2039", color = playerFg(), fontSize = 24.sp)
                Spacer(Modifier.width(10.dp))
                Text(
                    androidx.compose.ui.res.stringResource(R.string.player_channel_list),
                    color = playerFg(),
                    style = MaterialTheme.typography.titleMedium
                )
                if (epgLoading) {
                    Spacer(Modifier.weight(1f))
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = playerAccent(),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // Spodnych ~10% je vyhradenych na zatvaranie: tah zdola hore tam
                        // zoznam zatvori (namiesto rolovania). Klik na kanal aj rolovanie
                        // inde ostavaju zachovane - gesto citame na Initial passe a berieme
                        // ho LEN ked tah zacne v spodnej zone a ide nahor.
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitPointerEvent(
                                    androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                ).changes.firstOrNull() ?: continue
                                if (!(down.pressed && !down.previousPressed)) continue
                                if (down.position.y < size.height * 0.90f) continue
                                val pid = down.id
                                var totalDy = 0f
                                var decided = false
                                var closing = false
                                while (true) {
                                    val ev = awaitPointerEvent(
                                        androidx.compose.ui.input.pointer.PointerEventPass.Initial
                                    )
                                    val ch = ev.changes.firstOrNull { it.id == pid } ?: break
                                    if (!ch.pressed) break
                                    totalDy += ch.position.y - ch.previousPosition.y
                                    if (!decided && kotlin.math.abs(totalDy) > 12f) {
                                        decided = true
                                        closing = totalDy < 0f   // tah nahor -> zatvarame
                                    }
                                    if (closing) ch.consume()     // zober gesto LazyColumnu
                                }
                                if (closing && totalDy < -60f) onClose()
                            }
                        }
                    }
            ) {
            LazyColumn(
                state = listState,
                userScrollEnabled = listFrac >= 0.999f,   // rolovat az ked je zoznam uplne otvoreny
                modifier = Modifier.fillMaxSize()
            ) {
                itemsIndexed(liveChannels) { idx, ch ->
                    val selected = idx == sel
                    val locked = remember(lockTick, ch.uuid, serverId) {
                        ParentalLock.isChannelLocked(ctx, serverId, ch.uuid)
                    }
                    if (isModernUi()) {
                        // moderny riadok: ina stavba (picon velky, "Dalej:", minuty) — zdielany komponent
                        ModernPlayerChannelRow(
                            ch = ch,
                            selected = selected,
                            locked = locked,
                            nowSec = liveNowSec,
                            imageLoader = loader,
                            onClick = { onSelectChannel(idx); onClose() },
                            onLongClick = { onChannelLongPress(idx) },
                        )
                    } else {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .background(if (selected) Color(0x553B82F6) else Color.Transparent)
                            .combinedClickable(
                                onClick = { onSelectChannel(idx); onClose() },
                                onLongClick = { onChannelLongPress(idx) }
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (ch.number > 0) ch.number.toString() else "",
                            color = if (selected) playerFg() else Color(0xFF6699FF),
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(34.dp)
                        )
                        Box(
                            Modifier
                                .size(48.dp, 40.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(piconBackground()),
                            contentAlignment = Alignment.Center
                        ) {
                            if (ch.piconUrl != null) {
                                val req = remember(ch.piconUrl) {
                                    ImageRequest.Builder(ctx).data(ch.piconUrl).size(120).build()
                                }
                                AsyncImage(
                                    model = req,
                                    contentDescription = null,
                                    imageLoader = loader,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(2.dp)
                                )
                            } else {
                                Text(
                                    ch.name.take(3).uppercase(),
                                    color = playerFg(),
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    ch.name,
                                    color = playerFg(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (ch.recording) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(
                                        Modifier.size(8.dp)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(Color(0xFFE53935))
                                    )
                                }
                                if (locked) {
                                    Spacer(Modifier.width(6.dp))
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Filled.Lock,
                                        contentDescription = null,
                                        tint = playerFgDim(),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                            if (ch.nowTitle.isNotBlank()) {
                                Text(
                                    ch.nowTitle,
                                    color = playerFgDim(),
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (ch.nowStop > ch.nowStart) {
                                    val total = (ch.nowStop - ch.nowStart).coerceAtLeast(1)
                                    val frac = (liveNowSec - ch.nowStart)
                                        .coerceIn(0, total).toFloat() / total
                                    androidx.compose.material3.LinearProgressIndicator(
                                        progress = { frac },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp),
                                        trackColor = playerTrack()
                                    )
                                }
                            }
                        }
                    }
                    }
                }
            }
            }
        }
      }
    }
}
