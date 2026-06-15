package sk.tvhclient.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ChannelRow
import sk.tvhclient.shared.currentTimeSeconds
import sk.tvhclient.shared.formatDayLabel
import sk.tvhclient.shared.formatTimeHm
import sk.tvhclient.shared.model.EpgEvent

private const val PX_PER_MIN = 4          // sirka 1 minuty v dp
private const val HOUR_W = 60 * PX_PER_MIN // 240 dp
private const val DAY_MIN = 24 * 60
private const val PICON_COL = 64           // sirka stlpca s piconom
private const val ROW_H = 64

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpgGridScreen(
    rows: List<ChannelRow>,
    seed: Map<String, List<EpgEvent>>,
    onBack: () -> Unit
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val server = remember { Tvh.store.active() }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }

    // Detail relacie/nahravky (prekryva mriezku); klik na blok ho otvori
    var detail by remember { mutableStateOf<GridDetail?>(null) }

    var dayOffset by remember { mutableStateOf(0) }
    val dayStart = remember(dayOffset) { dayStartSec(dayOffset) }
    val dayEnd = dayStart + DAY_MIN * 60
    // Tikajuci cas (live ciara a priebeh) — prekreslenie kazdych 30s
    var now by remember { mutableStateOf(currentTimeSeconds()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(30_000); now = currentTimeSeconds() }
    }

    // EPG hromadne (jednorazovo, plynule skrolovanie) — zdielana cache cez VM.
    // Seed z now/next pre okamzity prvy obraz, kym dobehne hromadne nacitanie.
    val epgVm: EpgGridViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val epgFull by epgVm.epg.collectAsState()
    val epgLoading by epgVm.loading.collectAsState()
    LaunchedEffect(Unit) { epgVm.loadIfNeeded() }
    val epg = remember(epgFull, seed) {
        if (epgFull.isNotEmpty()) epgFull else seed
    }

    // DVR nahravky (minule relacie dozadu) — zdielana cache cez DvrViewModel
    // (prezije prepnutie kariet, nenacitava sa znova zo servera)
    val dvrVm: DvrViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val dvrState by dvrVm.state.collectAsState()
    LaunchedEffect(Unit) { dvrVm.loadIfNeeded() }
    val dvrByChannel = remember(dvrState) {
        (dvrState as? DvrState.Loaded)?.entries?.groupBy { it.channelName } ?: emptyMap()
    }

    val hScroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current

    // Viditeľné časové okno (v minútach) — riadky vykreslia len bloky v okolí,
    // nie všetkých ~40. Bucketujeme po 30 min, aby sa neprekresľovalo pri každom
    // pixeli horizontálneho skrolu. To rieši zaseky pri zvislom posúvaní.
    val pxPerMinPx = with(density) { PX_PER_MIN.dp.toPx() }
    val screenWMin = remember(configuration.screenWidthDp) {
        (with(density) { configuration.screenWidthDp.dp.toPx() } / pxPerMinPx).toInt()
    }
    val winBucket by remember {
        androidx.compose.runtime.derivedStateOf { (hScroll.value / pxPerMinPx / 30f).toInt() }
    }
    val visStartMin = winBucket * 30 - screenWMin
    val visEndMin = winBucket * 30 + screenWMin * 2 + 30

    // Po otvoreni posun na aktualny cas (pre Dnes), s malym predstihom
    LaunchedEffect(dayOffset) {
        val nowMin = if (dayOffset == 0)
            (((currentTimeSeconds() - dayStart) / 60).toInt()) else 0
        val startMin = (nowMin - 30).coerceIn(0, DAY_MIN)
        val targetPx = with(density) { (startMin * PX_PER_MIN).dp.toPx() }.toInt()
        var tries = 0
        while (hScroll.maxValue == 0 && tries < 25) {
            kotlinx.coroutines.delay(20); tries++
        }
        hScroll.scrollTo(targetPx.coerceAtMost(hScroll.maxValue))
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResourceTvGuide()) },
                navigationIcon = {
                    Text(
                        "  \u2190  ",
                        modifier = Modifier.padding(8.dp).clickable { onBack() },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    if (epgLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(22.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    androidx.compose.material3.IconButton(onClick = {
                        epgVm.refresh(); dvrVm.refresh()
                    }) {
                        androidx.compose.material3.Icon(
                            Icons.Default.Refresh,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Vyber dna (minule -7 az +6); minule dni = nahravky z DVR
            val offsets = remember { (-7..6).toList() }
            val dayListState = androidx.compose.foundation.lazy.rememberLazyListState()
            LaunchedEffect(Unit) {
                val idx = offsets.indexOf(0).coerceAtLeast(0)
                dayListState.scrollToItem(idx)
            }
            LazyRow(
                state = dayListState,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                items(offsets) { off ->
                    val label = if (off == 0) "Dnes" else formatDayLabel(dayStartSec(off))
                    Box(Modifier.padding(end = 8.dp)) {
                        FilterChip(
                            selected = off == dayOffset,
                            onClick = { dayOffset = off },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // Casova os (hlavicka) — skroluje horizontalne spolu s riadkami
            Row {
                Spacer(Modifier.width(PICON_COL.dp))
                Row(Modifier.horizontalScroll(hScroll)) {
                    for (h in 0 until 24) {
                        Text(
                            "%02d:00".format(h),
                            modifier = Modifier.width(HOUR_W.dp).padding(start = 4.dp, bottom = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Riadky kanalov
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { it.channel.uuid }) { row ->
                    val uuid = row.channel.uuid
                    EpgGridRow(
                        row = row,
                        events = (epg[uuid] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd },
                        dvr = (dvrByChannel[row.channel.name] ?: emptyList())
                            .filter { it.stop > dayStart && it.start < dayEnd },
                        dayStart = dayStart,
                        now = now,
                        showNow = dayOffset == 0,
                        visStartMin = visStartMin,
                        visEndMin = visEndMin,
                        hScroll = hScroll,
                        loader = loader,
                        onClick = { ev -> detail = GridDetail.Epg(row, ev) },
                        onDvr = { e -> detail = GridDetail.Dvr(e) }
                    )
                }
            }
        }
    }

        // Overlay s detailom (prekryva mriezku, zachova jej poziciu)
        detail?.let { d ->
            BackHandler { detail = null }
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background
            ) {
                GridDetailContent(
                    detail = d,
                    loader = loader,
                    onBack = { detail = null },
                    onPlay = {
                        when (d) {
                            is GridDetail.Epg -> playLive(context, d.row, d.ev)
                            is GridDetail.Dvr -> playDvr(context, d.rec)
                        }
                    }
                )
            }
        }
    }
}

private sealed class GridDetail {
    data class Epg(val row: ChannelRow, val ev: EpgEvent) : GridDetail()
    data class Dvr(val rec: sk.tvhclient.shared.model.DvrEntry) : GridDetail()
}

@Composable
private fun GridDetailContent(
    detail: GridDetail,
    loader: coil.ImageLoader,
    onBack: () -> Unit,
    onPlay: () -> Unit
) {
    val context = LocalContext.current
    // Spolocne polia z oboch typov
    val title: String
    val subtitle: String
    val channelName: String
    val piconUrl: String?
    val start: Long
    val stop: Long
    val desc: String
    val ageRating: Int
    val episode: String
    val recorded: Boolean
    when (detail) {
        is GridDetail.Epg -> {
            title = detail.ev.title.ifBlank { "—" }
            subtitle = detail.ev.subtitle
            channelName = detail.row.channel.name
            piconUrl = detail.row.piconUrl
            start = detail.ev.start; stop = detail.ev.stop
            desc = detail.ev.bestDescription
            ageRating = detail.ev.ageRating
            episode = detail.ev.episodeOnscreen
            recorded = false
        }
        is GridDetail.Dvr -> {
            title = detail.rec.title
            subtitle = detail.rec.dispSubtitle
            channelName = detail.rec.channelName
            piconUrl = null
            start = detail.rec.start; stop = detail.rec.stop
            desc = detail.rec.dispDescription
            ageRating = 0
            episode = ""
            recorded = true
        }
    }
    val durationMin = ((stop - start) / 60).toInt()

    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
    ) {
        // Hlavicka s piconom a tlacidlom spat
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (piconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(piconUrl).build(),
                    contentDescription = channelName,
                    imageLoader = loader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(90.dp).padding(8.dp)
                )
            } else {
                Text(channelName, style = MaterialTheme.typography.titleLarge)
            }
            androidx.compose.material3.IconButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
            ) {
                Text("\u2190", style = MaterialTheme.typography.titleLarge)
            }
        }

        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            // Meta riadok: kanal · datum · cas · dlzka
            val meta = buildString {
                append(channelName)
                append("  ·  ")
                append(formatDayLabel(start))
                append("  ·  ")
                append(formatTimeHm(start)); append(" - "); append(formatTimeHm(stop))
                if (durationMin > 0) { append("  ·  "); append(durationMin); append(" min") }
                if (episode.isNotBlank()) { append("  ·  "); append(episode) }
                if (ageRating > 0) { append("  ·  "); append(ageRating); append("+") }
            }
            Text(meta, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(Modifier.height(16.dp))
            androidx.compose.material3.Button(
                onClick = onPlay,
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Default.PlayArrow,
                    contentDescription = null
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.play),
                    style = MaterialTheme.typography.titleMedium
                )
            }

            if (desc.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun EpgGridRow(
    row: ChannelRow,
    events: List<EpgEvent>,
    dvr: List<sk.tvhclient.shared.model.DvrEntry>,
    dayStart: Long,
    now: Long,
    showNow: Boolean,
    visStartMin: Int,
    visEndMin: Int,
    hScroll: androidx.compose.foundation.ScrollState,
    loader: coil.ImageLoader,
    onClick: (EpgEvent) -> Unit,
    onDvr: (sk.tvhclient.shared.model.DvrEntry) -> Unit
) {
    val context = LocalContext.current
    Row(Modifier.height(ROW_H.dp)) {
        // Picon stlpec (fixny)
        Box(
            Modifier.width(PICON_COL.dp).height(ROW_H.dp).padding(4.dp),
            contentAlignment = Alignment.Center
        ) {
            if (row.piconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(row.piconUrl).build(),
                    contentDescription = row.channel.name,
                    imageLoader = loader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text(
                    row.channel.name.take(3).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
        // Programova plocha (skroluje horizontalne)
        Box(
            Modifier
                .horizontalScroll(hScroll)
                .height(ROW_H.dp)
        ) {
            Box(Modifier.width((DAY_MIN * PX_PER_MIN).dp).height(ROW_H.dp)) {
                // Minule relacie z DVR (prehratelne dozadu) — len co skoncilo;
                // vykreslime iba bloky v okolnom viditeľnom okne (cullovanie)
                dvr.filter { it.stop <= now }.forEach { rec ->
                    val startMin = (((rec.start - dayStart) / 60).toInt()).coerceAtLeast(0)
                    val endMin = (((rec.stop - dayStart) / 60).toInt()).coerceAtMost(DAY_MIN)
                    if (endMin <= visStartMin || startMin >= visEndMin) return@forEach
                    GridBlock(
                        startMin = startMin,
                        endMin = endMin,
                        title = rec.title,
                        timeLabel = formatTimeHm(rec.start) + " - " + formatTimeHm(rec.stop),
                        bg = Color(0x3366BB6A),       // zelenkavy = nahrate, da sa prehrat
                        recorded = true,
                        onClick = { onDvr(rec) }
                    )
                }
                // Aktualne a buduce relacie z EPG (tiez cullovane)
                events.filter { it.stop > now }.forEach { ev ->
                    val startMin = (((ev.start - dayStart) / 60).toInt()).coerceAtLeast(0)
                    val endMin = (((ev.stop - dayStart) / 60).toInt()).coerceAtMost(DAY_MIN)
                    if (endMin <= visStartMin || startMin >= visEndMin) return@forEach
                    val isNow = ev.start <= now && now < ev.stop
                    GridBlock(
                        startMin = startMin,
                        endMin = endMin,
                        title = ev.title.ifBlank { "—" },
                        timeLabel = formatTimeHm(ev.start) + " - " + formatTimeHm(ev.stop),
                        bg = if (isNow) MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                             else Color(0x22FFFFFF),
                        recorded = false,
                        progressMin = if (isNow) ((now - ev.start) / 60).toInt() else 0,
                        onClick = { onClick(ev) }
                    )
                }
                // Zvisla live ciara aktualneho casu (jemna, ladi s farbou)
                if (showNow) {
                    val nowMin = ((now - dayStart) / 60).toInt()
                    if (nowMin in 0..DAY_MIN) {
                        Box(
                            Modifier
                                .offset(x = (nowMin * PX_PER_MIN).dp)
                                .width(1.5.dp)
                                .height(ROW_H.dp)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.7f))
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GridBlock(
    startMin: Int,
    endMin: Int,
    title: String,
    timeLabel: String,
    bg: Color,
    recorded: Boolean,
    progressMin: Int = 0,
    onClick: () -> Unit
) {
    val wMin = endMin - startMin
    if (wMin <= 0) return
    Box(
        Modifier
            .offset(x = (startMin * PX_PER_MIN).dp)
            .width((wMin * PX_PER_MIN).dp)
            .height(ROW_H.dp)
            .padding(2.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .clickable { onClick() }
    ) {
        // Live priebeh (svetlejsia vypln zlava) pre bezzhiacu relaciu
        if (progressMin > 0) {
            Box(
                Modifier
                    .width((progressMin.coerceAtMost(wMin) * PX_PER_MIN).dp)
                    .height(ROW_H.dp)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
            )
        }
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                (if (recorded) "\u25B6 " else "") + title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

private fun playLive(context: android.content.Context, row: ChannelRow, ev: EpgEvent) {
    val intent = android.content.Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_UUID, row.channel.uuid)
        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
        putExtra(PlayerActivity.EXTRA_PROG_START, ev.start)
        putExtra(PlayerActivity.EXTRA_PROG_STOP, ev.stop)
        putExtra(PlayerActivity.EXTRA_PROG_TITLE, ev.title)
    }
    context.startActivity(intent)
}

/** Spatne prehratie minulej relacie z DVR nahravky (vratane resume/pozicie). */
private fun playDvr(context: android.content.Context, rec: sk.tvhclient.shared.model.DvrEntry) {
    val server = Tvh.store.active() ?: return
    val url = Tvh.dvrUrl(server, rec.uuid)
    val intent = android.content.Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_URL, url)
        putExtra(PlayerActivity.EXTRA_TITLE, rec.title)
        putExtra(PlayerActivity.EXTRA_DURATION_MS, rec.durationSec * 1000)
        putExtra(PlayerActivity.EXTRA_DVR_UUID, rec.uuid)
    }
    context.startActivity(intent)
}

/** Zaciatok dna (lokalna polnoc) + offset dni, v sekundach. */
private fun dayStartSec(offset: Int): Long {
    val c = java.util.Calendar.getInstance()
    c.add(java.util.Calendar.DAY_OF_YEAR, offset)
    c.set(java.util.Calendar.HOUR_OF_DAY, 0)
    c.set(java.util.Calendar.MINUTE, 0)
    c.set(java.util.Calendar.SECOND, 0)
    c.set(java.util.Calendar.MILLISECOND, 0)
    return c.timeInMillis / 1000
}

@Composable
private fun stringResourceTvGuide(): String =
    androidx.compose.ui.res.stringResource(R.string.tv_guide)
