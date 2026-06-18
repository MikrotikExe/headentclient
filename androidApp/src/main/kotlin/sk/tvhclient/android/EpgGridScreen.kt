package sk.tvhclient.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.focus.onFocusEvent
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
private const val DAY_SWITCH_DP = 64       // prah pretiahnutia za okraj na prepnutie dna
private const val NOW_TICK_MS = 30_000L    // ako casto prekreslit live ciaru/priebeh
private const val DVR_REFRESH_MS = 150_000L // tichy refresh DVR (nove/dokoncene nahravky)

// Kam doskocit po prepnuti dna gestom: na koniec (predosly den) alebo zaciatok (dalsi den)
private enum class DayJump { END, START }

/**
 * Zluci viacnasobne nahravky tej istej relacie do jedneho bloku pre mriezku.
 * Ta ista relacia byva nahrata viackrat s mierne odlisnym casom (padding),
 * takze nestaci presna zhoda casu. Zoskupime podla nazvu a v ramci nazvu
 * spojime zaznamy, ktore sa casovo prekryvaju; z kazdeho zhluku ponechame
 * ten s najvacsim suborom (najkompletnejsia kopia na prehratie).
 * Rozne vysielania toho isteho nazvu (neprekryvaju sa) zostavaju oddelene.
 */
private fun collapseDvrOverlaps(
    list: List<sk.tvhclient.shared.model.DvrEntry>
): List<sk.tvhclient.shared.model.DvrEntry> {
    val out = ArrayList<sk.tvhclient.shared.model.DvrEntry>()
    for ((_, group) in list.groupBy { it.title }) {
        val sorted = group.sortedBy { it.start }
        val cluster = ArrayList<sk.tvhclient.shared.model.DvrEntry>()
        var clusterEnd = Long.MIN_VALUE
        for (e in sorted) {
            if (cluster.isEmpty() || e.start < clusterEnd) {
                cluster.add(e)
                if (e.stop > clusterEnd) clusterEnd = e.stop
            } else {
                out.add(cluster.maxByOrNull { it.fileSize } ?: cluster.first())
                cluster.clear()
                cluster.add(e)
                clusterEnd = e.stop
            }
        }
        if (cluster.isNotEmpty()) out.add(cluster.maxByOrNull { it.fileSize } ?: cluster.first())
    }
    return out
}

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
    // Zoznam kanalov pre zapping a zoznam v prehravaci (CH+/CH-, overlay)
    LaunchedEffect(rows) {
        val srv = Tvh.store.active()
        val nowS = System.currentTimeMillis() / 1000
        LivePlaylist.channels = rows.map { r ->
            val cur = seed[r.channel.uuid]?.firstOrNull { it.start <= nowS && nowS < it.stop }
            val nt = (cur?.title?.ifBlank { null }) ?: r.nowTitle ?: ""
            val ns = if (cur != null) cur.start else r.nowStart
            val ne = if (cur != null) cur.stop else r.nowStop
            LivePlaylist.LiveChannel(
                uuid = r.channel.uuid,
                name = r.channel.name,
                number = r.channel.number ?: 0,
                piconUrl = r.piconUrl,
                nowTitle = nt,
                nowStart = ns,
                nowStop = ne
            )
        }
    }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }

    // Detail relacie/nahravky (prekryva mriezku); klik na blok ho otvori
    var detail by remember { mutableStateOf<GridDetail?>(null) }
    // Posledny zafokusovany blok (D-pad) -> INFO kláves zobrazi jeho detail
    var lastFocused by remember { mutableStateOf<GridDetail?>(null) }
    val infoSig by TabController.infoKey
    LaunchedEffect(infoSig) {
        if (infoSig > 0) detail = if (detail == null) lastFocused else null
    }

    var dayOffset by remember { mutableStateOf(0) }
    val dayStart = remember(dayOffset) { dayStartSec(dayOffset) }
    val dayEnd = dayStart + DAY_MIN * 60
    // Tikajuci cas (live ciara a priebeh) — prekreslenie kazdych 30s
    var now by remember { mutableStateOf(currentTimeSeconds()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(NOW_TICK_MS); now = currentTimeSeconds() }
    }

    // EPG hromadne (jednorazovo, plynule skrolovanie) — zdielana cache cez VM.
    // Seed z now/next pre okamzity prvy obraz, kym dobehne hromadne nacitanie.
    val epgVm: EpgGridViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val epgFull by epgVm.epg.collectAsState()
    val epgLoading by epgVm.loading.collectAsState()
    val epgGen by epgVm.gen.collectAsState()
    // HTSP: jedno spojenie, progresivne vsetky kanaly (no-op pre HTTP)
    LaunchedEffect(epgGen) { epgVm.loadHtsp() }
    val epg = remember(epgFull, seed) {
        // seed (now/next) ako zaklad nech sa hned nieco ukaze; per-kanal EPG
        // (progresivne) prepise kanaly kde uz mame plne data
        if (epgFull.isEmpty()) seed
        else {
            val m = HashMap<String, List<EpgEvent>>(seed)
            m.putAll(epgFull)
            m
        }
    }

    // DVR nahravky (minule relacie dozadu) — zdielana cache cez DvrViewModel
    // (prezije prepnutie kariet, nenacitava sa znova zo servera)
    val dvrVm: DvrViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val dvrState by dvrVm.state.collectAsState()
    LaunchedEffect(Unit) { dvrVm.loadIfNeeded() }
    // Periodicky (kazde 2,5 min) tichy refresh DVR — nove/dokoncene nahravky
    // sa objavia v mriezke skoro (bez cakania ~15 min na expiraciu)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(DVR_REFRESH_MS)
            dvrVm.refresh()
        }
    }
    val dvrByChannel = remember(dvrState) {
        // V mriezke chceme jeden blok na relaciu. Ta ista relacia moze byt
        // nahrata viackrat s mierne odlisnym casom (padding) -> zlucime
        // nahravky s rovnakym nazvom, ktore sa casovo prekryvaju, a necháme
        // tu s najvacsim suborom (najkompletnejsia kopia na prehratie).
        // Archiv (DvrScreen) zostava nedotknuty — tam vidno vsetky nahravky.
        (dvrState as? DvrState.Loaded)?.entries
            ?.groupBy { it.channelName }
            ?.mapValues { (_, list) -> collapseDvrOverlaps(list) }
            ?: emptyMap()
    }
    val recordingList = remember(dvrState) {
        (dvrState as? DvrState.Loaded)?.recording ?: emptyList()
    }
    val inProgressByChannel = remember(recordingList) {
        recordingList.groupBy { it.channelName }
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

    // Plynule skrolovanie cez hranicu dna: na okraji casovej osi prepni den.
    // Detekcia mimo nested-scroll: pozorujeme pohyb prsta priamo (Initial pass,
    // bez konzumovania), takze horizontalScroll funguje normalne. Ked hScroll
    // stoji na okraji (0 alebo maxValue) a prst tahá dalej tym smerom, po prahu
    // prepneme den. Toto je spolahlivejsie ako citanie pretecenia cez onPostScroll
    // (to fling faza vacsinou prehltla -> okraj sa neprepol).
    var pendingJump by remember { mutableStateOf<DayJump?>(null) }

    // Po prepnuti/otvoreni nastav poziciu: kontinuita cez polnoc, inak aktualny cas
    LaunchedEffect(dayOffset) {
        var tries = 0
        while (hScroll.maxValue == 0 && tries < 25) {
            kotlinx.coroutines.delay(20); tries++
        }
        val targetPx = when (pendingJump) {
            DayJump.END -> hScroll.maxValue
            DayJump.START -> 0
            null -> {
                val nowMin = if (dayOffset == 0)
                    (((currentTimeSeconds() - dayStart) / 60).toInt()) else 0
                val startMin = (nowMin - 30).coerceIn(0, DAY_MIN)
                with(density) { (startMin * PX_PER_MIN).dp.toPx() }.toInt()
                    .coerceAtMost(hScroll.maxValue)
            }
        }
        hScroll.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
            scrollBy((targetPx - hScroll.value).toFloat())
        }
        pendingJump = null
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
                            contentDescription = stringResource(R.string.retry)
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
            LaunchedEffect(dayOffset) {
                val idx = offsets.indexOf(dayOffset).coerceAtLeast(0)
                dayListState.animateScrollToItem(idx)
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
                            onClick = { pendingJump = null; dayOffset = off },
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
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        val edgePx = DAY_SWITCH_DP.dp.toPx()
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            var accum = 0f
                            var switched = false
                            while (true) {
                                val ev = awaitPointerEvent(PointerEventPass.Initial)
                                val ch = ev.changes.firstOrNull() ?: break
                                if (!ch.pressed) break
                                val dx = ch.position.x - ch.previousPosition.x
                                val atStart = hScroll.value <= 0
                                val atEnd = hScroll.maxValue > 0 && hScroll.value >= hScroll.maxValue
                                if (!switched && atStart && dx > 0f && dayOffset > -7) {
                                    // zaciatok dna, tahám doprava (do minulosti) -> predosly den
                                    accum += dx
                                    if (accum >= edgePx) { pendingJump = DayJump.END; dayOffset--; switched = true }
                                } else if (!switched && atEnd && dx < 0f && dayOffset < 6) {
                                    // koniec dna, tahám dolava (do buducnosti) -> dalsi den
                                    accum += -dx
                                    if (accum >= edgePx) { pendingJump = DayJump.START; dayOffset++; switched = true }
                                } else if (!atStart && !atEnd) {
                                    accum = 0f
                                }
                            }
                        }
                    }
            ) {
                items(rows, key = { it.channel.uuid }) { row ->
                    val uuid = row.channel.uuid
                    // Progresivne: nacitaj EPG pre tento kanal ked je riadok viditelny
                    LaunchedEffect(uuid, epgGen) { epgVm.ensureChannel(uuid) }
                    EpgGridRow(
                        row = row,
                        events = (epg[uuid] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd },
                        dvr = (dvrByChannel[row.channel.name] ?: emptyList())
                            .filter { it.stop > dayStart && it.start < dayEnd },
                        inProgress = (inProgressByChannel[row.channel.name] ?: emptyList())
                            .filter { it.stop > dayStart && it.start < dayEnd },
                        dayStart = dayStart,
                        now = now,
                        showNow = dayOffset == 0,
                        visStartMin = visStartMin,
                        visEndMin = visEndMin,
                        hScroll = hScroll,
                        loader = loader,
                        onClick = { ev -> detail = GridDetail.Epg(row, ev) },
                        onDvr = { e -> detail = GridDetail.Dvr(e) },
                        onInProgress = { rec -> detail = GridDetail.InProgress(row, rec) },
                        onFocusDetail = { lastFocused = it }
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
                            is GridDetail.InProgress -> playLiveChannel(context, d.row)
                        }
                    },
                    onPlayFromStart = (d as? GridDetail.InProgress)?.let { ip ->
                        { playDvr(context, ip.rec) }
                    },
                    playLabelRes = if (d is GridDetail.InProgress) R.string.play_live else R.string.play
                )
            }
        }
    }
}

private sealed class GridDetail {
    data class Epg(val row: ChannelRow, val ev: EpgEvent) : GridDetail()
    data class Dvr(val rec: sk.tvhclient.shared.model.DvrEntry) : GridDetail()
    // Prave prebiehajuca nahravka — da sa pustit naživo aj od zaciatku
    data class InProgress(val row: ChannelRow, val rec: sk.tvhclient.shared.model.DvrEntry) : GridDetail()
}

@Composable
private fun GridDetailContent(
    detail: GridDetail,
    loader: coil.ImageLoader,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayFromStart: (() -> Unit)? = null,
    playLabelRes: Int = R.string.play
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
        is GridDetail.InProgress -> {
            title = detail.rec.title
            subtitle = detail.rec.dispSubtitle
            channelName = detail.row.channel.name
            piconUrl = detail.row.piconUrl
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
            // Prehrat len ak je co prehrat: DVR nahravka, alebo EPG relacia
            // ktora prave bezi (naziva). Buduca/nenahravana sa prehrat neda.
            val nowSec = currentTimeSeconds()
            val playable = recorded || (start <= nowSec && nowSec < stop)
            if (playable) {
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
                        stringResource(playLabelRes),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                // Prebiehajuca nahravka — prehrat od zaciatku (dobehnes zaciatok)
                if (onPlayFromStart != null) {
                    Spacer(Modifier.height(8.dp))
                    androidx.compose.material3.OutlinedButton(
                        onClick = onPlayFromStart,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.PlayArrow,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stringResource(R.string.play_from_start),
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                }
            } else {
                Text(
                    stringResource(
                        if (start > nowSec) R.string.not_started else R.string.not_available
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
    inProgress: List<sk.tvhclient.shared.model.DvrEntry>,
    dayStart: Long,
    now: Long,
    showNow: Boolean,
    visStartMin: Int,
    visEndMin: Int,
    hScroll: androidx.compose.foundation.ScrollState,
    loader: coil.ImageLoader,
    onClick: (EpgEvent) -> Unit,
    onDvr: (sk.tvhclient.shared.model.DvrEntry) -> Unit,
    onInProgress: (sk.tvhclient.shared.model.DvrEntry) -> Unit,
    onFocusDetail: (GridDetail) -> Unit = {}
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
                        onClick = { onDvr(rec) },
                        onFocused = { onFocusDetail(GridDetail.Dvr(rec)) }
                    )
                }
                // Prave prebiehajuce nahravky (● REC) — vyplnia medzeru hned,
                // klik pusti spravnu nahravku (presne uuid, ziadne fuzzy parovanie)
                inProgress.forEach { rec ->
                    val startMin = (((rec.start - dayStart) / 60).toInt()).coerceAtLeast(0)
                    val endMin = (((rec.stop - dayStart) / 60).toInt()).coerceAtMost(DAY_MIN)
                    if (endMin <= visStartMin || startMin >= visEndMin) return@forEach
                    GridBlock(
                        startMin = startMin,
                        endMin = endMin,
                        title = rec.title,
                        timeLabel = formatTimeHm(rec.start) + " - " + formatTimeHm(rec.stop),
                        bg = Color(0x33EF5350),       // cervenkavy = prave sa nahrava
                        recorded = false,
                        prefix = "\u25CF ",
                        onClick = { onInProgress(rec) },
                        onFocused = { onFocusDetail(GridDetail.InProgress(row, rec)) }
                    )
                }
                // Relacie z EPG vratane minulych (historia); preskoc tie, ktore uz
                // ukazuje blok nahravky (DVR alebo prave prebiehajuca), nech nie su dva bloky
                events.filter { ev ->
                    inProgress.none { it.start < ev.stop && it.stop > ev.start } &&
                        dvr.none { it.stop <= now && it.start < ev.stop && it.stop > ev.start }
                }.forEach { ev ->
                    val startMin = (((ev.start - dayStart) / 60).toInt()).coerceAtLeast(0)
                    val endMin = (((ev.stop - dayStart) / 60).toInt()).coerceAtMost(DAY_MIN)
                    if (endMin <= visStartMin || startMin >= visEndMin) return@forEach
                    val isNow = ev.start <= now && now < ev.stop
                    val isPast = ev.stop <= now
                    GridBlock(
                        startMin = startMin,
                        endMin = endMin,
                        title = ev.title.ifBlank { "—" },
                        timeLabel = formatTimeHm(ev.start) + " - " + formatTimeHm(ev.stop),
                        bg = when {
                            isNow -> MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                            isPast -> if (isLightTheme()) Color(0x0F000000) else Color(0x14FFFFFF)
                            else -> if (isLightTheme()) Color(0x1F000000) else Color(0x22FFFFFF)
                        },
                        recorded = false,
                        progressMin = if (isNow) ((now - ev.start) / 60).toInt() else 0,
                        onClick = { onClick(ev) },
                        onFocused = { onFocusDetail(GridDetail.Epg(row, ev)) }
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
    prefix: String? = null,
    onClick: () -> Unit,
    onFocused: () -> Unit = {}
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
            .onFocusEvent { if (it.isFocused) onFocused() }
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
                (prefix ?: if (recorded) "\u25B6 " else "") + title,
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
    LivePlaylist.setIndexForUuid(row.channel.uuid)
    context.startActivity(intent)
}

/** Live prehratie kanala bez konkretnej relacie (pre prebiehajucu nahravku). */
private fun playLiveChannel(context: android.content.Context, row: ChannelRow) {
    val intent = android.content.Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_UUID, row.channel.uuid)
        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
    }
    LivePlaylist.setIndexForUuid(row.channel.uuid)
    context.startActivity(intent)
}

/** Spatne prehratie minulej relacie z DVR nahravky (vratane resume/pozicie). */

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
