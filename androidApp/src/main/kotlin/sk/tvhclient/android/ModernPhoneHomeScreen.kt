package sk.tvhclient.android

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ChannelRow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moderna domovska obrazovka pre TELEFON (UiModePref.MODERN) — telefonne
 * rozlozenie, nie TV koncept: kompaktny hero + VERTIKALNY zoznam oblubenych
 * kanalov (plnosiroke riadky s piconom, relaciou, progresom a zostavajucim
 * casom) + riadok "Vsetky kanaly". Skratky sekcii tu nie su — dupliovali by
 * spodnu listu. Zdiela ChannelsViewModel (activity scope) s obrazovkou kanalov.
 */
@Composable
fun ModernPhoneHomeScreen(
    onOpenChannels: () -> Unit,
    onOpenEpg: () -> Unit,
) {
    val ctx = LocalContext.current
    val chVm: ChannelsViewModel = viewModel()
    val chState by chVm.state.collectAsState()
    val epgMap by chVm.epgMap.collectAsState()
    LaunchedEffect(Unit) { chVm.loadIfNeeded() }

    val cs = MaterialTheme.colorScheme
    val accent = cs.primary
    val accent2 = cs.secondary
    val cardBg = cs.surfaceContainer
    val cardBorder = cs.outlineVariant
    val fg = cs.onBackground
    val fgDim = cs.onSurfaceVariant

    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val clockFmt = ClockPref.hm(LocalContext.current)
    val hhmm = remember(locale, clockFmt) { SimpleDateFormat(clockFmt, locale) }
    val dateStr = remember(now) { SimpleDateFormat("EEEE d. MMMM", locale).format(Date(now)) }

    val rows: List<ChannelRow> = (chState as? ChannelsState.Loaded)?.allRows ?: emptyList()
    val sid = remember { Tvh.store.active()?.id ?: "default" }
    val server = remember { Tvh.store.active() }
    val piconLoader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
    val favUuids = remember(rows) { Favorites.all(ctx, sid) }
    val lastUuid = remember(rows) { LastChannel.get(ctx, sid) }

    val hero: ChannelRow? = remember(rows, lastUuid) {
        rows.firstOrNull { it.channel.uuid == lastUuid }
            ?: rows.firstOrNull { it.channel.uuid in favUuids }
            ?: rows.firstOrNull()
    }
    val listRows: List<ChannelRow> = remember(rows, favUuids) {
        val favs = rows.filter { it.channel.uuid in favUuids }
        if (favs.isNotEmpty()) favs else rows.take(10)
    }

    fun play(uuid: String, title: String) {
        (chState as? ChannelsState.Loaded)?.let { st ->
            val hidden = HiddenChannels.all(ctx, Tvh.store.active()?.id)
            LivePlaylist.channels = st.allRows.filter { it.channel.uuid !in hidden }.map { r ->
                LivePlaylist.LiveChannel(
                    uuid = r.channel.uuid, name = r.channel.name,
                    number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                    nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                )
            }
        }
        LivePlaylist.setIndexForUuid(uuid)
        runCatching {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_UUID, uuid)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_KIND, "tv")
            })
        }
    }

    val nextLabel = stringResource(R.string.mh_next)
    val nowSec = now / 1000

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(cs.background, cs.surfaceContainerLow))),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        item(key = "date") {
            Text(dateStr.replaceFirstChar { it.uppercase() }, color = fgDim, fontSize = 13.sp)
            Spacer(Modifier.height(10.dp))
        }

        // ===== kompaktny HERO =====
        item(key = "hero") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(cs.surfaceContainerHighest, cs.surfaceContainerLow, cs.background)))
                    .padding(14.dp)
            ) {
                Column {
                    if (hero != null) {
                        val ev = epgMap[hero.channel.uuid]?.firstOrNull { nowSec in it.start until it.stop }
                        val title = ev?.title?.takeIf { it.isNotBlank() } ?: hero.nowTitle ?: hero.channel.name
                        val startSec = ev?.start ?: hero.nowStart
                        val stopSec = ev?.stop ?: hero.nowStop
                        val range = if (startSec > 0 && stopSec > 0)
                            hhmm.format(Date(startSec * 1000)) + " – " + hhmm.format(Date(stopSec * 1000)) else ""
                        val next = if (stopSec > 0)
                            epgMap[hero.channel.uuid]?.firstOrNull { it.start >= stopSec && it.title.isNotBlank() }?.title
                            else null
                        val frac = if (startSec in 1 until stopSec)
                            ((nowSec - startSec).toFloat() / (stopSec - startSec)).coerceIn(0f, 1f) else 0f

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFFE5484D))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Text(stringResource(R.string.mh_live), color = Color.White,
                                    fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.width(8.dp))
                            if (hero.piconUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(ctx).data(hero.piconUrl).build(),
                                    contentDescription = null,
                                    imageLoader = piconLoader,
                                    modifier = Modifier.size(22.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                            }
                            val num = hero.channel.number?.takeIf { it > 0 }?.toString()
                            Text(listOfNotNull(num, hero.channel.name).joinToString(" · "),
                                color = fgDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(7.dp))
                        Text(title, color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            listOfNotNull(
                                range.takeIf { it.isNotBlank() },
                                next?.let { "$nextLabel $it" }
                            ).joinToString(" · "),
                            color = fgDim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(9.dp))
                        if (frac > 0f) {
                            LinearProgressIndicator(
                                progress = { frac },
                                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                color = accent, trackColor = cs.outlineVariant
                            )
                            Spacer(Modifier.height(11.dp))
                        }
                        Row {
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(accent)
                                    .clickable { play(hero.channel.uuid, hero.channel.name) }
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                                Text("▶  " + stringResource(R.string.mh_watch), color = cs.onPrimary,
                                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            }
                            Spacer(Modifier.width(10.dp))
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(cs.surfaceContainerHigh)
                                    .clickable { onOpenEpg() }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(stringResource(R.string.home_tv_program), color = cs.onSurface, fontSize = 14.sp)
                            }
                        }
                    } else {
                        Text("Headent Client", color = fg, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text(dateStr, color = fgDim, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }

        // ===== pokracovat v pozerani (M333) =====
        item(key = "cwrail") {
            ContinueWatchingRail(headerFontSize = 16.sp, cardWidth = 210.dp)
            Spacer(Modifier.height(18.dp))
        }

        // ===== oblubene · teraz — vertikalne riadky =====
        if (listRows.isNotEmpty()) {
            item(key = "favhdr") {
                Text(stringResource(R.string.mh_fav_now), color = fg, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            items(listRows, key = { it.channel.uuid }) { r ->
                val ev = epgMap[r.channel.uuid]?.firstOrNull { nowSec in it.start until it.stop }
                val t = ev?.title?.takeIf { it.isNotBlank() } ?: r.nowTitle ?: ""
                val s = ev?.start ?: r.nowStart
                val e = ev?.stop ?: r.nowStop
                val frac = if (s in 1 until e) ((nowSec - s).toFloat() / (e - s)).coerceIn(0f, 1f) else 0f
                val leftMin = if (e > nowSec && s > 0) ((e - nowSec) / 60).toInt() else null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardBg)
                        .clickable { play(r.channel.uuid, r.channel.name) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (r.piconUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(ctx).data(r.piconUrl).build(),
                            contentDescription = null,
                            imageLoader = piconLoader,
                            modifier = Modifier.size(40.dp)
                        )
                    } else {
                        Box(
                            Modifier.size(40.dp).clip(CircleShape).background(cardBorder),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(r.channel.name.take(2).uppercase(), color = fg, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(r.channel.name, color = fgDim, fontSize = 11.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.ifBlank { "–" }, color = fg, fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (frac > 0f) accent2 else cs.outlineVariant,
                            trackColor = cs.outlineVariant
                        )
                    }
                    if (leftMin != null) {
                        Spacer(Modifier.width(10.dp))
                        Text("$leftMin min", color = fgDim, fontSize = 11.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ===== vsetky kanaly =====
        item(key = "allch") {
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.surfaceContainerHigh)
                    .clickable { onOpenChannels() }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(stringResource(R.string.mh_all_channels), color = cs.onSurface,
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("›", color = fgDim, fontSize = 18.sp)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
