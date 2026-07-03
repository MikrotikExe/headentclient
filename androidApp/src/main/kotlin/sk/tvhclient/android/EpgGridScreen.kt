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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
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
import androidx.compose.ui.graphics.lerp
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
// Moderny rezim: vyssi riadok — karta nesie cas NAD nazvom + progres pasik + cislo kanala pod piconom
private const val ROW_H_M = 84
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

/** Jeden zluceny blok nahravky pre mriezku (dokoncena = zelena, prebiehajuca = cervena). */
private data class RecBlock(
    val start: Long,
    val stop: Long,
    val title: String,
    val inProgress: Boolean,
    val entry: sk.tvhclient.shared.model.DvrEntry
)

/** Navigacna bunka (D-pad): casovy rozsah + co sa otvori pri OK. Zhodna s renderom. */
private class NavCell(val start: Long, val stop: Long, val detail: GridDetail)

/** Normalizuje nazov pre porovnanie duplicit: mala pismena, bez "(ST)" a bez "(cislo)". */
private fun normRecTitle(t: String): String {
    var s = t.lowercase()
    s = s.replace("(st)", " ")
    s = Regex("\\(\\d+\\)").replace(s, " ")
    s = Regex("\\s+").replace(s, " ").trim()
    return s
}

/**
 * Zluci dokoncene (zelene) aj prave prebiehajuce (cervene) nahravky jedneho kanala
 * do jednej sady blokov. Ta ista relacia byva nahrata viackrat s odlisnym nazvom
 * ("(ST)" varianty) aj casom (padding) — preto zluci zaznamy, ktorych nazov sa po
 * normalizacii zhoduje, ALEBO sa casovo vyrazne prekryvaju (>=40 % kratsieho z dvoch,
 * aby sa nespojili len susedne relacie dotykajuce sa cez padding). V zhluku s
 * prebiehajucou nahravkou je blok cerveny (prebieha), inak zeleny. Na klik/prehratie
 * sa vyberie zaznam s najvacsim suborom (najkompletnejsia kopia).
 */
private fun mergeRecordings(
    dvrPast: List<sk.tvhclient.shared.model.DvrEntry>,
    inProgress: List<sk.tvhclient.shared.model.DvrEntry>
): List<RecBlock> {
    data class Item(val e: sk.tvhclient.shared.model.DvrEntry, val live: Boolean)
    val all = ArrayList<Item>()
    dvrPast.forEach { all.add(Item(it, false)) }
    inProgress.forEach { all.add(Item(it, true)) }
    if (all.isEmpty()) return emptyList()
    all.sortBy { it.e.start }

    val out = ArrayList<RecBlock>()
    val cluster = ArrayList<Item>()
    val cTitles = HashSet<String>()
    var cEnd = Long.MIN_VALUE

    fun flush() {
        if (cluster.isEmpty()) return
        val live = cluster.any { it.live }
        val start = cluster.minOf { it.e.start }
        val stop = cluster.maxOf { it.e.stop }
        val pick = (if (live) cluster.filter { it.live } else cluster)
            .maxByOrNull { it.e.fileSize }?.e ?: cluster.first().e
        out.add(RecBlock(start, stop, pick.title, live, pick))
        cluster.clear(); cTitles.clear(); cEnd = Long.MIN_VALUE
    }

    for (item in all) {
        val e = item.e
        val len = (e.stop - e.start).coerceAtLeast(1)
        val overlap = (minOf(cEnd, e.stop) - e.start).coerceAtLeast(0)
        val joins = cluster.isNotEmpty() &&
            (normRecTitle(e.title) in cTitles || overlap >= len * 4 / 10)
        if (cluster.isEmpty() || joins) {
            cluster.add(item); cTitles.add(normRecTitle(e.title))
            if (e.stop > cEnd) cEnd = e.stop
        } else {
            flush()
            cluster.add(item); cTitles.add(normRecTitle(e.title)); cEnd = e.stop
        }
    }
    flush()
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
    val modernUi = isModernUi()
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
        // Rovnako ako pri dokoncenych nahravkach (zelene): ta ista relacia moze
        // prave teraz bezat nahravana viackrat s mierne odlisnym casom (padding) /
        // na viacerych tuneroch -> zlucime prekryvajuce sa zaznamy rovnakeho nazvu,
        // nech sa v mriezke neprekryvaju dva cervene bloky.
        recordingList.groupBy { it.channelName }
            .mapValues { (_, list) -> collapseDvrOverlaps(list) }
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
                // vycentruj "teraz" do stredu viditelnej casti casovej osi (sirka obrazovky
                // bez stlpca s logom kanala), nech vidno aj kus minulosti vlavo
                val halfVisMin = ((configuration.screenWidthDp - PICON_COL) / 2) / PX_PER_MIN
                val startMin = (nowMin - halfVisMin).coerceIn(0, DAY_MIN)
                with(density) { (startMin * PX_PER_MIN).dp.toPx() }.toInt()
                    .coerceAtMost(hScroll.maxValue)
            }
        }
        hScroll.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
            scrollBy((targetPx - hScroll.value).toFloat())
        }
        pendingJump = null
    }

    // --- D-pad navigacia (TV): dole/hore drzi casovy stlpec (linia "teraz"),
    // vlavo/vpravo prechadza relacie v case. Vlastny model vyberu — automaticky
    // fokus Compose to negarantoval (uletoval na sipku spat / mimo casovy stlpec). ---
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    // Kurzorovy vyber (fialovy ramik) + D-pad maju zmysel len na TV; na dotyku (telefon/tablet) je zbytocny
    val isTv = remember {
        val um = context.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    val daysBack = EpgRangePref.backStateOf(context).value
    val daysForward = EpgRangePref.fwdStateOf(context).value
    var pendingCursorEdge by remember { mutableStateOf<DayJump?>(null) }
    var selRow by remember { mutableStateOf(0) }
    var anchorTime by remember { mutableStateOf(now) }        // cas, na ktorom drzime stlpec
    var selStart by remember { mutableStateOf<Long?>(null) }  // zaciatok vybranej bunky

    // Bunky jedneho kanala — zhodne s renderom: zlucene nahravky + relacie bez prekryvu, podla casu
    fun navCells(idx: Int): List<NavCell> {
        val r = rows.getOrNull(idx) ?: return emptyList()
        val uuid = r.channel.uuid
        val evs = (epg[uuid] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd }
        val dvr = (dvrByChannel[r.channel.name] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd }
        val inProg = (inProgressByChannel[r.channel.name] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd }
        val recBlocks = mergeRecordings(dvr.filter { it.stop <= now }, inProg)
        val cells = ArrayList<NavCell>()
        recBlocks.forEach { rb ->
            cells.add(NavCell(rb.start, rb.stop,
                if (rb.inProgress) GridDetail.InProgress(r, rb.entry) else GridDetail.Dvr(r, rb.entry)))
        }
        evs.filter { ev -> recBlocks.none { it.start < ev.stop && it.stop > ev.start } }
            .forEach { ev -> cells.add(NavCell(ev.start, ev.stop, GridDetail.Epg(r, ev))) }
        cells.sortBy { it.start }
        return cells
    }
    fun cellAt(cells: List<NavCell>, t: Long): NavCell? =
        cells.firstOrNull { it.start <= t && t < it.stop }
            ?: cells.minByOrNull { kotlin.math.abs(((it.start + it.stop) / 2) - t) }
    fun selectRowAt(idx: Int, t: Long) {
        val c = cellAt(navCells(idx), t)
        selRow = idx
        selStart = c?.start
        lastFocused = c?.detail
    }
    fun moveVertical(delta: Int): Boolean {
        val target = selRow + delta
        if (target < 0 || target > rows.lastIndex) return false  // okraj -> nechaj unik (dni hore / dolu)
        selectRowAt(target, anchorTime)
        return true
    }
    fun moveHorizontal(dir: Int): Boolean {
        val cells = navCells(selRow)
        if (cells.isEmpty()) return true
        var cur = cells.indexOfFirst { it.start == selStart }
        if (cur < 0) cur = cells.indexOfFirst { it.start <= anchorTime && anchorTime < it.stop }
        val ni = (if (cur < 0) 0 else cur) + dir
        if (ni < 0) {                       // pred prvou bunkou -> predosly den, skoc na koniec dna
            if (dayOffset > -daysBack) { pendingCursorEdge = DayJump.END; dayOffset-- }
            return true
        }
        if (ni > cells.lastIndex) {          // za poslednou bunkou -> dalsi den, skoc na zaciatok dna
            if (dayOffset < daysForward) { pendingCursorEdge = DayJump.START; dayOffset++ }
            return true
        }
        val c = cells[ni]
        selStart = c.start
        anchorTime = c.start
        lastFocused = c.detail
        return true
    }
    val onGridKey: (androidx.compose.ui.input.key.KeyEvent) -> Boolean = handler@{ e ->
        if (e.type != KeyEventType.KeyDown) return@handler false
        when (e.key) {
            Key.DirectionDown -> moveVertical(1)
            Key.DirectionUp -> moveVertical(-1)
            Key.DirectionLeft -> moveHorizontal(-1)
            Key.DirectionRight -> moveHorizontal(1)
            Key.DirectionCenter, Key.Enter -> {
                navCells(selRow).firstOrNull { it.start == selStart }?.let { detail = it.detail }
                true
            }
            else -> false
        }
    }
    // Pociatocny vyber + reset pri zmene dna: na aktualnom dni linia "teraz", inak poludnie
    LaunchedEffect(dayOffset, rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (!isTv) { selStart = null; return@LaunchedEffect }  // dotyk: ziadny kurzorovy vyber
        anchorTime = when (pendingCursorEdge) {
            DayJump.END -> dayStart + DAY_MIN.toLong() * 60 - 60   // koniec dna -> posledna bunka
            DayJump.START -> dayStart                              // zaciatok dna -> prva bunka
            null -> if (dayOffset == 0) now else dayStart + 12L * 3600
        }
        pendingCursorEdge = null
        selectRowAt(selRow.coerceIn(0, rows.lastIndex), anchorTime)
    }
    // Fokus na mriezku po otvoreni (TV)
    LaunchedEffect(Unit) {
        if (!isTv) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        runCatching { gridFocus.requestFocus() }
    }
    // Auto-skrol na vybrany riadok / bunku
    LaunchedEffect(selRow) { runCatching { listState.animateScrollToItem(selRow) } }
    // Centrovanie horizontalneho skrolu (TV): do stredu obrazovky daj STRED vybranej
    // relacie - pri otvoreni je to prave beziaca relacia, takze "co ide live" je v strede
    // (polovica vlavo, polovica vpravo). Pocas navigacie kurzorom centruje vybranu bunku.
    LaunchedEffect(selStart) {
        val s = selStart ?: return@LaunchedEffect
        val cell = navCells(selRow).firstOrNull { it.start == s }
        val midSec = if (cell != null) (cell.start + cell.stop) / 2 else s
        val midMin = (((midSec - dayStart) / 60).toInt()).coerceIn(0, DAY_MIN)
        // do stredu viditelnej casovej osi (sirka obrazovky bez stlpca s logom)
        val halfVisPx = with(density) { ((configuration.screenWidthDp - PICON_COL) / 2).dp.toPx() }
        val target = with(density) { (midMin * PX_PER_MIN).dp.toPx() } - halfVisPx
        runCatching { hScroll.animateScrollTo(target.toInt().coerceAtLeast(0)) }
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
            // Vyber dna; rozsah dozadu/dopredu podla nastavenia (EpgRangePref) — daysBack/daysForward su definovane vyssie
            LaunchedEffect(daysBack, daysForward) {
                if (dayOffset < -daysBack) dayOffset = -daysBack
                if (dayOffset > daysForward) dayOffset = daysForward
            }
            val offsets = remember(daysBack, daysForward) { (-daysBack..daysForward).toList() }
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
                        if (modernUi) {
                            // Moderny rezim: dvojriadkova pilulka — den tucne + datum drobne
                            val cs = MaterialTheme.colorScheme
                            val sel = off == dayOffset
                            val dayName = if (off == 0) "Dnes" else label.substringBeforeLast(" ")
                            val dayDate = if (off == 0)
                                java.text.SimpleDateFormat("d.M.", java.util.Locale.getDefault())
                                    .format(java.util.Date(dayStartSec(0) * 1000))
                            else label.substringAfterLast(" ")
                            Column(
                                Modifier
                                    .clip(RoundedCornerShape(24.dp))
                                    .background(
                                        if (sel) cs.primary
                                        else if (isLightTheme()) cs.surfaceContainerLowest
                                        else cs.surfaceContainer
                                    )
                                    .then(
                                        if (sel) Modifier
                                        else Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(24.dp))
                                    )
                                    .clickable { pendingJump = null; dayOffset = off }
                                    .padding(horizontal = 18.dp, vertical = 7.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    dayName,
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                    color = if (sel) cs.onPrimary else cs.onSurface,
                                    maxLines = 1
                                )
                                Text(
                                    dayDate,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (sel) cs.onPrimary.copy(alpha = 0.8f) else cs.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        } else {
                            FilterChip(
                                selected = off == dayOffset,
                                onClick = { pendingJump = null; dayOffset = off },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // Casova os (hlavicka) — skroluje horizontalne spolu s riadkami;
            // moderny rezim: navyse teal bublina s aktualnym casom nad teraz-ciarou
            Row {
                Spacer(Modifier.width(PICON_COL.dp))
                Box(Modifier.horizontalScroll(hScroll)) {
                    Row {
                        for (h in 0 until 24) {
                            Text(
                                "%02d:00".format(h),
                                modifier = Modifier.width(HOUR_W.dp).padding(start = 4.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (modernUi && dayOffset == 0) {
                        val nowMin = ((now - dayStart) / 60).toInt()
                        if (nowMin in 0..DAY_MIN) {
                            Box(
                                Modifier
                                    .offset(x = (nowMin * PX_PER_MIN - 26).dp)
                                    .clip(RoundedCornerShape(11.dp))
                                    .background(MaterialTheme.colorScheme.primary)
                                    .padding(horizontal = 9.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    formatTimeHm(now),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Riadky kanalov
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(gridFocus)
                    .focusable()
                    .onPreviewKeyEvent(onGridKey)
                    .pointerInput(daysBack, daysForward) {
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
                                if (!switched && atStart && dx > 0f && dayOffset > -daysBack) {
                                    // zaciatok dna, tahám doprava (do minulosti) -> predosly den
                                    accum += dx
                                    if (accum >= edgePx) { pendingJump = DayJump.END; dayOffset--; switched = true }
                                } else if (!switched && atEnd && dx < 0f && dayOffset < daysForward) {
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
                itemsIndexed(rows, key = { _, it -> it.channel.uuid }) { idx, row ->
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
                        selectedStart = if (idx == selRow) selStart else null,
                        onClick = { ev -> detail = GridDetail.Epg(row, ev) },
                        onDvr = { e -> detail = GridDetail.Dvr(row, e) },
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
    data class Dvr(val row: ChannelRow, val rec: sk.tvhclient.shared.model.DvrEntry) : GridDetail()
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
    val playFocus = remember { FocusRequester() }
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
            piconUrl = detail.row.piconUrl
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
                // Na TV/diaľkovom daj počiatočný fokus na Prehrať, nech OK funguje hneď
                LaunchedEffect(detail) {
                    kotlinx.coroutines.delay(150)
                    runCatching { playFocus.requestFocus() }
                }
                androidx.compose.material3.Button(
                    onClick = onPlay,
                    modifier = Modifier.fillMaxWidth().focusRequester(playFocus)
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
    selectedStart: Long? = null,
    onClick: (EpgEvent) -> Unit,
    onDvr: (sk.tvhclient.shared.model.DvrEntry) -> Unit,
    onInProgress: (sk.tvhclient.shared.model.DvrEntry) -> Unit,
    onFocusDetail: (GridDetail) -> Unit = {}
) {
    val context = LocalContext.current
    val modern = isModernUi()
    val rowH = if (modern) ROW_H_M else ROW_H
    Row(Modifier.height(rowH.dp)) {
        // Picon stlpec (fixny); moderny rezim: zaoblena karta + cislo kanala pod piconom
        if (modern) {
            val cs = MaterialTheme.colorScheme
            Column(
                Modifier.width(PICON_COL.dp).height(rowH.dp).padding(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(piconBackground()),
                    contentAlignment = Alignment.Center
                ) {
                    if (row.piconUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(row.piconUrl).build(),
                            contentDescription = row.channel.name,
                            imageLoader = loader,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(3.dp)
                        )
                    } else {
                        Text(
                            row.channel.name.take(3).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
                val num = row.channel.number
                if (num != null && num > 0) {
                    Text(
                        num.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = cs.primary,
                        maxLines = 1
                    )
                }
            }
        } else {
        Box(
            Modifier.width(PICON_COL.dp).height(rowH.dp).padding(4.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(piconBackground()),
            contentAlignment = Alignment.Center
        ) {
            if (row.piconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(row.piconUrl).build(),
                    contentDescription = row.channel.name,
                    imageLoader = loader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(3.dp)
                )
            } else {
                Text(
                    row.channel.name.take(3).uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
        }
        // Programova plocha (skroluje horizontalne)
        Box(
            Modifier
                .horizontalScroll(hScroll)
                .height(rowH.dp)
        ) {
            Box(Modifier.width((DAY_MIN * PX_PER_MIN).dp).height(rowH.dp)) {
                // Nahravky (dokoncene zelene + prebiehajuce cervene) zlucene do jednej
                // sady blokov — bez prekryvov a duplicit (aj pri "(ST)" variantach nazvu);
                // vykreslime iba bloky vo viditeľnom okne (cullovanie)
                val recBlocks = remember(dvr, inProgress, now / 60) {
                    mergeRecordings(dvr.filter { it.stop <= now }, inProgress)
                }
                // Susedne nahravky roznych relacii sa casto prekryvaju o okraj (padding).
                // Orezeme prekryv v jeho strede, nech bloky na seba nadvazuju a neprekryvaju sa.
                val recBounds = remember(recBlocks) {
                    val n = recBlocks.size
                    val vs = LongArray(n) { recBlocks[it].start }
                    val ve = LongArray(n) { recBlocks[it].stop }
                    for (i in 1 until n) {
                        if (recBlocks[i].start < ve[i - 1]) {
                            val mid = (recBlocks[i].start + ve[i - 1]) / 2
                            ve[i - 1] = mid
                            if (vs[i] < mid) vs[i] = mid
                        }
                    }
                    vs to ve
                }
                recBlocks.forEachIndexed { i, rb ->
                    val vStart = recBounds.first[i]
                    val vStop = recBounds.second[i]
                    val startMin = (((vStart - dayStart) / 60).toInt()).coerceAtLeast(0)
                    val endMin = (((vStop - dayStart) / 60).toInt()).coerceAtMost(DAY_MIN)
                    if (endMin <= visStartMin || startMin >= visEndMin || startMin >= endMin) return@forEachIndexed
                    if (rb.inProgress) {
                        if (modern) ModernGridBlock(
                            startMin = startMin,
                            endMin = endMin,
                            rowH = rowH,
                            title = rb.title,
                            timeLabel = formatTimeHm(rb.start) + " - " + formatTimeHm(rb.stop),
                            kind = MgKind.REC,
                            progressFrac = ((now - vStart).toFloat() / (vStop - vStart).coerceAtLeast(1)).coerceIn(0f, 1f),
                            selected = selectedStart == rb.start,
                            onClick = { onInProgress(rb.entry) }
                        ) else GridBlock(
                            startMin = startMin,
                            endMin = endMin,
                            title = rb.title,
                            timeLabel = formatTimeHm(rb.start) + " - " + formatTimeHm(rb.stop),
                            bg = Color(0x2EEF5350),       // svetlejsia = este sa nenahralo (za ciarou)
                            recorded = false,
                            progressMin = ((now - vStart) / 60).toInt(),
                            progressColor = Color(0x80EF5350),  // tmavsia = uz nahrate (pred ciarou)
                            prefix = "\u25CF ",
                            selected = selectedStart == rb.start,
                            onClick = { onInProgress(rb.entry) },
                            onFocused = { onFocusDetail(GridDetail.InProgress(row, rb.entry)) }
                        )
                    } else {
                        if (modern) ModernGridBlock(
                            startMin = startMin,
                            endMin = endMin,
                            rowH = rowH,
                            title = rb.title,
                            timeLabel = formatTimeHm(rb.start) + " - " + formatTimeHm(rb.stop),
                            kind = MgKind.RECORDED,
                            selected = selectedStart == rb.start,
                            onClick = { onDvr(rb.entry) }
                        ) else GridBlock(
                            startMin = startMin,
                            endMin = endMin,
                            title = rb.title,
                            timeLabel = formatTimeHm(rb.start) + " - " + formatTimeHm(rb.stop),
                            bg = if (isLightTheme()) Color(0xA643A047) else Color(0x5C43A047),  // zelena = nahrate
                            recorded = true,
                            selected = selectedStart == rb.start,
                            onClick = { onDvr(rb.entry) },
                            onFocused = { onFocusDetail(GridDetail.Dvr(row, rb.entry)) }
                        )
                    }
                }
                // Relacie z EPG vratane minulych (historia); preskoc tie, ktore uz
                // ukazuje niektory zluceny blok nahravky, nech nie su dva bloky
                events.filter { ev ->
                    recBlocks.none { it.start < ev.stop && it.stop > ev.start }
                }.forEach { ev ->
                    val startMin = (((ev.start - dayStart) / 60).toInt()).coerceAtLeast(0)
                    val endMin = (((ev.stop - dayStart) / 60).toInt()).coerceAtMost(DAY_MIN)
                    if (endMin <= visStartMin || startMin >= visEndMin) return@forEach
                    val isNow = ev.start <= now && now < ev.stop
                    val isPast = ev.stop <= now
                    if (modern) ModernGridBlock(
                        startMin = startMin,
                        endMin = endMin,
                        rowH = rowH,
                        title = ev.title.ifBlank { "—" },
                        timeLabel = formatTimeHm(ev.start) + " - " + formatTimeHm(ev.stop),
                        kind = when { isNow -> MgKind.NOW; isPast -> MgKind.PAST; else -> MgKind.FUTURE },
                        progressFrac = if (isNow)
                            ((now - ev.start).toFloat() / (ev.stop - ev.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        else 0f,
                        selected = selectedStart == ev.start,
                        onClick = { onClick(ev) }
                    ) else GridBlock(
                        startMin = startMin,
                        endMin = endMin,
                        title = ev.title.ifBlank { "—" },
                        timeLabel = formatTimeHm(ev.start) + " - " + formatTimeHm(ev.stop),
                        bg = when {
                            isNow -> if (isLightTheme())
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                lerp(MaterialTheme.colorScheme.primaryContainer, Color.Black, 0.38f)
                            isPast -> if (isLightTheme()) Color(0x33000000) else Color(0x22FFFFFF)
                            else -> if (isLightTheme()) Color(0x0F000000) else Color(0x14FFFFFF)
                        },
                        recorded = false,
                        progressMin = if (isNow) ((now - ev.start) / 60).toInt() else 0,
                        progressColor = if (isNow) {
                            if (isLightTheme())
                                lerp(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.primary, 0.50f)
                            else
                                MaterialTheme.colorScheme.primaryContainer
                        } else null,
                        fg = if (isNow) MaterialTheme.colorScheme.onPrimaryContainer else null,
                        fgDim = if (isNow) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f) else null,
                        selected = selectedStart == ev.start,
                        onClick = { onClick(ev) },
                        onFocused = { onFocusDetail(GridDetail.Epg(row, ev)) }
                    )
                }
                // Zvisla live ciara aktualneho casu (jemna, ladi s farbou);
                // moderny rezim: vyraznejsia teal ciara nadvazujuca na bublinu v osi
                if (showNow) {
                    val nowMin = ((now - dayStart) / 60).toInt()
                    if (nowMin in 0..DAY_MIN) {
                        Box(
                            Modifier
                                .offset(x = (nowMin * PX_PER_MIN).dp)
                                .width(if (modern) 2.5.dp else 1.5.dp)
                                .height(rowH.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(
                                        alpha = if (modern) 0.85f else 0.7f
                                    )
                                )
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
    progressColor: Color? = null,
    prefix: String? = null,
    fg: Color? = null,
    fgDim: Color? = null,
    selected: Boolean = false,
    onClick: () -> Unit,
    onFocused: () -> Unit = {}
) {
    val titleColor = fg ?: MaterialTheme.colorScheme.onSurface
    val timeColor = fgDim ?: MaterialTheme.colorScheme.onSurfaceVariant
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
            .then(
                if (selected) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                else Modifier
            )
            .pointerInput(Unit) { detectTapGestures { onClick() } }
    ) {
        // Priebeh zlava: pri zivej relacii svetlejsia primarna, pri nahravke tmavsia cervena
        if (progressMin > 0) {
            Box(
                Modifier
                    .width((progressMin.coerceAtMost(wMin) * PX_PER_MIN).dp)
                    .height(ROW_H.dp)
                    .background(progressColor ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
            )
        }
        Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                (prefix ?: if (recorded) "\u25B6 " else "") + title,
                style = MaterialTheme.typography.bodySmall,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                timeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = timeColor,
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

/** Druh karty modernej mriezky. */
private enum class MgKind { NOW, PAST, FUTURE, REC, RECORDED }

/**
 * Karta relacie v modernom rezime mriezky: cas drobne NAD tucnym nazvom,
 * medzery medzi kartami, nahravky maju farebnu hranu vlavo + badge namiesto
 * celoplosnej vyplne, priebeh je tenky pasik na spodku karty.
 */
@Composable
private fun ModernGridBlock(
    startMin: Int,
    endMin: Int,
    rowH: Int,
    title: String,
    timeLabel: String,
    kind: MgKind,
    progressFrac: Float = 0f,
    selected: Boolean = false,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val light = isLightTheme()
    val wMin = endMin - startMin
    if (wMin <= 0) return
    val bg = when (kind) {
        MgKind.NOW -> lerp(
            if (light) cs.surfaceContainerLowest else cs.surfaceContainer,
            cs.primaryContainer, 0.6f
        )
        MgKind.PAST -> if (light) cs.surfaceContainer else cs.surfaceContainerLowest
        else -> if (light) cs.surfaceContainerLowest else cs.surfaceContainer
    }
    val stripColor = when (kind) {
        MgKind.REC -> Color(0xFFE53935)
        MgKind.RECORDED -> if (light) Color(0xFF43A047) else Color(0xFF66BB6A)
        else -> null
    }
    val titleColor = when {
        kind == MgKind.PAST -> cs.onSurfaceVariant
        kind == MgKind.NOW -> cs.onPrimaryContainer
        else -> cs.onSurface
    }
    val timeColor = when (kind) {
        MgKind.PAST -> cs.onSurfaceVariant.copy(alpha = 0.7f)
        MgKind.NOW -> cs.onPrimaryContainer.copy(alpha = 0.8f)
        else -> cs.onSurfaceVariant
    }
    Box(
        Modifier
            .offset(x = (startMin * PX_PER_MIN).dp)
            .width((wMin * PX_PER_MIN).dp)
            .height(rowH.dp)
            .padding(horizontal = 2.dp, vertical = 3.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .then(
                    when {
                        selected -> Modifier.border(2.5.dp, cs.primary, RoundedCornerShape(12.dp))
                        kind == MgKind.NOW -> Modifier.border(1.5.dp, cs.primary, RoundedCornerShape(12.dp))
                        else -> Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(12.dp))
                    }
                )
                .pointerInput(Unit) { detectTapGestures { onClick() } }
        ) {
            if (stripColor != null) {
                Box(
                    Modifier.width(4.dp).fillMaxHeight().background(stripColor)
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(
                        start = if (stripColor != null) 10.dp else 8.dp,
                        end = 6.dp, top = 5.dp, bottom = 5.dp
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = timeColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (kind == MgKind.REC || kind == MgKind.RECORDED) {
                        Spacer(Modifier.width(6.dp))
                        val badgeBg = if (kind == MgKind.REC) {
                            if (light) Color(0xFFFDECEA) else Color(0xFF3A1D20)
                        } else {
                            if (light) Color(0xFFE6F3E7) else Color(0xFF1C3122)
                        }
                        val badgeFg = if (kind == MgKind.REC) {
                            if (light) Color(0xFFC62828) else Color(0xFFEF8A88)
                        } else {
                            if (light) Color(0xFF2E7D32) else Color(0xFFA5D6A7)
                        }
                        Row(
                            Modifier.clip(RoundedCornerShape(9.dp)).background(badgeBg)
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (kind == MgKind.REC) {
                                Box(
                                    Modifier.size(6.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color(0xFFE53935))
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "REC",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = badgeFg, maxLines = 1
                                )
                            } else {
                                Text(
                                    "\u2713 " + stringResource(R.string.epg_recorded_badge),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                                    color = badgeFg, maxLines = 1
                                )
                            }
                        }
                    }
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                if ((kind == MgKind.NOW || kind == MgKind.REC) && progressFrac > 0f) {
                    val fill = if (kind == MgKind.REC) Color(0xFFE53935) else cs.primary
                    val track = if (kind == MgKind.REC) fill.copy(alpha = 0.25f) else cs.outlineVariant
                    Box(
                        Modifier.fillMaxWidth().height(4.dp)
                            .clip(RoundedCornerShape(2.dp)).background(track)
                    ) {
                        Box(
                            Modifier.fillMaxWidth(progressFrac).fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp)).background(fill)
                        )
                    }
                }
            }
        }
    }
}
