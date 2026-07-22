package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.Dvr
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ChannelRow
import sk.tvhclient.shared.model.EpgEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Moderny UI rezim TV domovskej obrazovky (UiModePref.MODERN): hero karta
 * s aktualnym programom naposledy sledovaneho kanala (nazov, cas, progres,
 * "Dalej:"), rad oblubenych kanalov s prave beziacimi relaciami a navigacne
 * dlazdice. Data berie z tych istych zdrojov ako klasicky rezim (ChannelsState,
 * epgMap, Favorites, LastChannel) — nic nove sa nenacitava.
 */
@Composable
fun ModernTvHomeScreen(
    chState: ChannelsState,
    epgMap: Map<String, List<EpgEvent>>,
    onPlayChannel: (uuid: String, title: String) -> Unit,
    onChannels: () -> Unit,
    onRadio: () -> Unit,
    onTvProgram: () -> Unit,
    onArchive: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val bg = Brush.verticalGradient(listOf(cs.background, cs.surfaceContainerLow))
    val accent = cs.primary
    val accent2 = cs.secondary
    val cardBg = cs.surfaceContainer
    val cardBorder = cs.outlineVariant
    val fg = cs.onBackground
    val fgDim = cs.onSurfaceVariant

    // hodiny (pol minuty staci na progres bary aj cas v rohu)
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(30_000) }
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val timeStr = remember(now) { SimpleDateFormat("HH:mm", locale).format(Date(now)) }
    val dateStr = remember(now) { SimpleDateFormat("EEEE d. MMMM", locale).format(Date(now)) }
    val hhmm = remember(locale) { SimpleDateFormat("HH:mm", locale) }

    val rows: List<ChannelRow> = (chState as? ChannelsState.Loaded)?.allRows ?: emptyList()
    val sid = remember { Tvh.store.active()?.id ?: "default" }
    val server = remember { Tvh.store.active() }
    val piconLoader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
    val favUuids = remember(rows) { Favorites.all(ctx, sid) }
    val lastUuid = remember(rows) { LastChannel.get(ctx, sid) }

    // hero kanal: posledny sledovany -> prvy obluбeny -> prvy v zozname
    val hero: ChannelRow? = remember(rows, lastUuid) {
        rows.firstOrNull { it.channel.uuid == lastUuid }
            ?: rows.firstOrNull { it.channel.uuid in favUuids }
            ?: rows.firstOrNull()
    }
    // rad: oblubene (v poradi zoznamu), fallback prvych 8 kanalov
    val railRows: List<ChannelRow> = remember(rows, favUuids) {
        val favs = rows.filter { it.channel.uuid in favUuids }
        if (favs.isNotEmpty()) favs.take(12) else rows.take(8)
    }

    fun EpgEvent?.timeRange(): String {
        val e = this ?: return ""
        if (e.start <= 0 || e.stop <= 0) return ""
        return hhmm.format(Date(e.start * 1000)) + " – " + hhmm.format(Date(e.stop * 1000))
    }
    fun nextTitle(uuid: String, nowStopSec: Long): String? =
        epgMap[uuid]?.firstOrNull { it.start >= nowStopSec && it.title.isNotBlank() }?.title

    val nextLabel = stringResource(R.string.mh_next)

    val heroFocus = remember { FocusRequester() }
    val homeScroll = rememberScrollState()
    // Bug2-fix2: kym je fokus na hornych tlacidlach (Sledovat / TV program),
    // drzime scroll uplne hore (0), aby nad nimi bola vidno hlavicka datum/cas.
    // Jednorazovy animateScrollTo(0) Compose "bring into view" prebijal — preto
    // scroll drzime aktivne, kolko trva fokus hore.
    var watchFocused by remember { mutableStateOf(false) }
    var tvpFocused by remember { mutableStateOf(false) }
    val topFocused = watchFocused || tvpFocused
    LaunchedEffect(hero?.channel?.uuid) { runCatching { heroFocus.requestFocus() } }
    // Bug2-fix5: ked je fokus na hornych tlacidlach, nechame scroll pohltit
    // scrollovanie cez nestedScroll spojku — tym k bring-into-view skoku vobec
    // nedojde (ziadne blikanie) a vrch (datum/cas) ostane vidno.
    val topHold = remember {
        object : NestedScrollConnection {
            var active = false
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (active) Offset(0f, available.y) else Offset.Zero
        }
    }
    topHold.active = topFocused
    LaunchedEffect(topFocused) { if (topFocused) runCatching { homeScroll.scrollTo(0) } }

    Column(
        Modifier
            .fillMaxSize()
            .background(bg)
            .nestedScroll(topHold)
            .verticalScroll(homeScroll)
            .padding(horizontal = 40.dp, vertical = 24.dp)
    ) {
        // horna lista: datum | cas
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(dateStr.replaceFirstChar { it.uppercase() }, color = fgDim, fontSize = 15.sp)
            Text(timeStr, color = fg, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(14.dp))

        // ===== HERO (+ radio panel vpravo, ked hra mini radio — M344-fix2) =====
        Row(
            Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
        Box(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                // Zrkadlo radio panelu (M344-fix12): vlavo plna farba s oblymi
                // rohmi, doprava sa hero uplne rozpusti do pozadia
                .background(
                    Brush.horizontalGradient(
                        0f to cs.surfaceContainerHighest,
                        0.55f to cs.surfaceContainerLow.copy(alpha = 0.6f),
                        1f to androidx.compose.ui.graphics.Color.Transparent
                    )
                )
                .padding(22.dp)
        ) {
            Column {
                if (hero != null) {
                    val nowSec = now / 1000
                    val ev = epgMap[hero.channel.uuid]?.firstOrNull { nowSec in it.start until it.stop }
                    val title = ev?.title?.takeIf { it.isNotBlank() } ?: hero.nowTitle ?: hero.channel.name
                    val startSec = ev?.start ?: hero.nowStart
                    val stopSec = ev?.stop ?: hero.nowStop
                    val range = if (ev != null) ev.timeRange()
                        else if (startSec > 0 && stopSec > 0)
                            hhmm.format(Date(startSec * 1000)) + " – " + hhmm.format(Date(stopSec * 1000))
                        else ""
                    val next = if (stopSec > 0) nextTitle(hero.channel.uuid, stopSec) else null
                    val frac = if (startSec in 1 until stopSec)
                        ((nowSec - startSec).toFloat() / (stopSec - startSec)).coerceIn(0f, 1f) else 0f

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.clip(RoundedCornerShape(999.dp)).background(Color(0xFFE5484D))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(stringResource(R.string.mh_live), color = Color.White,
                                fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(10.dp))
                        if (hero.piconUrl != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(hero.piconUrl).build(),
                                contentDescription = null,
                                imageLoader = piconLoader,
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        val num = hero.channel.number?.takeIf { it > 0 }?.toString()
                        Text(listOfNotNull(num, hero.channel.name).joinToString(" · "),
                            color = fgDim, fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(title, color = fg, fontSize = 34.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            range.takeIf { it.isNotBlank() },
                            next?.let { "$nextLabel " + it }
                        ).joinToString(" · "),
                        color = fgDim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(12.dp))
                    if (frac > 0f) {
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.width(360.dp).height(5.dp).clip(RoundedCornerShape(3.dp)),
                            color = accent, trackColor = cs.outlineVariant
                        )
                        Spacer(Modifier.height(16.dp))
                    }
                    Row {
                        Box(
                            Modifier
                                .dpadFocusable(RoundedCornerShape(999.dp))
                                .focusRequester(heroFocus)
                                // Bug2-fix: pri fokuse Sledovat posun zoznam uplne
                                // na vrch, aby sa ukazala hlavicka (datum/cas) —
                                // inak fokus posunul scroll len po tlacidlo a
                                // hlavicka zostala skryta nad viditelnou oblastou.
                                .onFocusChanged { st ->
                                    watchFocused = st.isFocused
                                }
                                .clip(RoundedCornerShape(999.dp))
                                .background(accent)
                                .clickable { onPlayChannel(hero.channel.uuid, hero.channel.name) }
                                .padding(horizontal = 26.dp, vertical = 10.dp)
                        ) {
                            Text("▶  " + stringResource(R.string.mh_watch), color = cs.onPrimary,
                                fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier
                                .dpadFocusable(RoundedCornerShape(999.dp))
                                // Bug2-fix2: aj TV program drzi scroll hore, nech
                                // je nad nim vidno datum/cas.
                                .onFocusChanged { st -> tvpFocused = st.isFocused }
                                .clip(RoundedCornerShape(999.dp))
                                .background(cs.surfaceContainerHigh)
                                .clickable { onTvProgram() }
                                .padding(horizontal = 22.dp, vertical = 10.dp)
                        ) {
                            Text(stringResource(R.string.home_tv_program), color = cs.onSurface, fontSize = 16.sp)
                        }
                    }
                } else {
                    // este sa nacitava / bez servera: znacka + nazov, navigacia nizsie funguje
                    Text("Headent Client", color = fg, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    Text(dateStr, color = fgDim, fontSize = 15.sp)
                }
            }
        }
        TvRadioHomePanel(
            Modifier.width(330.dp).fillMaxHeight()
        )
        }

        Spacer(Modifier.height(18.dp))

        // ===== navigacne dlazdice =====
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ModernNavPill(stringResource(R.string.tab_channels), Icons.Default.LiveTv, onChannels)
            ModernNavPill(stringResource(R.string.tab_radio), Icons.Default.Radio, onRadio)
            ModernNavPill(stringResource(R.string.tab_dvr), Icons.AutoMirrored.Filled.Dvr, onArchive)
            ModernNavPill(stringResource(R.string.tab_settings), Icons.Default.Settings, onSettings)
        }

        Spacer(Modifier.height(20.dp))

        // ===== pokracovat v pozerani (M333) =====
        ContinueWatchingRail(headerFontSize = 18.sp, cardWidth = 230.dp)
        Spacer(Modifier.height(20.dp))

        // ===== rad: oblubene kanaly · teraz =====
        if (railRows.isNotEmpty()) {
            Text(stringResource(R.string.mh_fav_now), color = fg, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                itemsIndexed(railRows, key = { _, r -> r.channel.uuid }) { _, r ->
                    val nowSec = now / 1000
                    val ev = epgMap[r.channel.uuid]?.firstOrNull { nowSec in it.start until it.stop }
                    val t = ev?.title?.takeIf { it.isNotBlank() } ?: r.nowTitle ?: ""
                    val s = ev?.start ?: r.nowStart
                    val e = ev?.stop ?: r.nowStop
                    val frac = if (s in 1 until e) ((nowSec - s).toFloat() / (e - s)).coerceIn(0f, 1f) else 0f
                    Column(
                        Modifier
                            .width(210.dp)
                            .dpadFocusable(RoundedCornerShape(14.dp))
                            .clip(RoundedCornerShape(14.dp))
                            .background(cardBg)
                            .clickable { onPlayChannel(r.channel.uuid, r.channel.name) }
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (r.piconUrl != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(ctx).data(r.piconUrl).build(),
                                    contentDescription = null,
                                    imageLoader = piconLoader,
                                    modifier = Modifier.size(30.dp)
                                )
                            } else {
                                Box(
                                    Modifier.size(30.dp).clip(CircleShape).background(cardBorder),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(r.channel.name.take(2).uppercase(), color = fg, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.channel.name, color = fgDim, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(t.ifBlank { "–" }, color = fg, fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                            color = if (frac > 0f) accent2 else cs.outlineVariant,
                            trackColor = cs.outlineVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModernNavPill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .dpadFocusable(RoundedCornerShape(999.dp))
            .clip(RoundedCornerShape(999.dp))
            .background(cs.surfaceContainerHigh)
            .clickable { onClick() }
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            androidx.compose.material3.Icon(
                icon, contentDescription = null,
                tint = cs.primary, modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = cs.onSurface, fontSize = 14.sp)
    }
}
