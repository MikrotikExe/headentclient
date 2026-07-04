package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import sk.tvhclient.shared.Tvh

/**
 * "Pokracovat v pozerani" (M333) — vodorovny rad rozpozeranych nahravok na
 * domovskej obrazovke (telefon aj TV, len moderny rezim). Data: prienik
 * WatchProgress (lokalne pozicie) a nacitanych DVR poloziek. Dopozerane a
 * polozky pod 1 min sa nezobrazuju; klik pokracuje cez playDvr (resume dialog
 * / logika prehravaca ostava nedotknuta).
 */
@Composable
internal fun ContinueWatchingRail(
    headerFontSize: TextUnit = 16.sp,
    cardWidth: Dp = 210.dp,
    modifier: Modifier = Modifier
) {
    if (!isModernUi()) return
    val ctx = LocalContext.current
    val server = remember { Tvh.store.active() } ?: return
    val dvrVm: DvrViewModel = viewModel()
    androidx.compose.runtime.LaunchedEffect(Unit) { dvrVm.loadIfNeeded() }
    val st by dvrVm.state.collectAsState()
    val entries = (st as? DvrState.Loaded)?.entries ?: return

    // obnova po navrate z prehravaca (nova pozicia/dopozerane)
    var tick by remember { mutableStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) tick++
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    val items = remember(entries, tick) {
        WatchProgress.recent(ctx, server.id, 60)
            .asSequence()
            .filter { (_, info) -> !info.completed && info.posMs >= 60_000 && info.durMs > 0 }
            .mapNotNull { (uuid, info) ->
                entries.firstOrNull { it.uuid == uuid }?.let { it to info }
            }
            .take(10)
            .toList()
    }
    if (items.isEmpty()) return

    val cs = MaterialTheme.colorScheme
    Column(modifier) {
        Text(
            stringResource(R.string.mh_continue),
            color = cs.onSurface, fontSize = headerFontSize,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(10.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(items, key = { it.first.uuid }) { (entry, info) ->
                Column(
                    Modifier
                        .width(cardWidth)
                        .dpadFocusable(RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                        .background(cs.surfaceContainer)
                        .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
                        .clickable { playDvr(ctx, entry) }
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape)
                                .background(cs.primaryContainer.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("\u25B6", color = cs.primary, fontSize = 11.sp)
                        }
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            if (entry.channelName.isNotBlank()) {
                                Text(entry.channelName, color = cs.onSurfaceVariant,
                                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(entry.title, color = cs.onSurface, fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { info.fraction },
                        modifier = Modifier.fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)),
                        trackColor = cs.surfaceContainerHigh
                    )
                    val leftMin = ((info.durMs - info.posMs) / 60000L).toInt().coerceAtLeast(1)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.mh_left, leftMin),
                        color = cs.onSurfaceVariant, fontSize = 11.sp, maxLines = 1
                    )
                }
            }
        }
    }
}
