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
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.shadow
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ViewComfy
import androidx.compose.material.icons.filled.ViewCompact
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch

private const val PX_PER_MIN = 4          // the width of 1 minute in dp
private const val DAY_MIN = 24 * 60
private const val PICON_COL = 64           // the minimum width of the channel column (narrow screens)
// M377: wide screens (TV/tablet >=600dp) — the channel column carries picon + number + name
private const val CHAN_COL_WIDE = 188
// M387: adaptive — the channel column is a percentage of the window width (narrow 19 %, wide 22 %),
// the font and picon on narrow screens scaled by a factor k = width / 390 (0.9–1.3)
private fun chanColFor(screenWidthDp: Int, compact: Boolean = false): Int =
    if (screenWidthDp >= 600) {
        // M388-fix: compact on a phone applies in landscape too (a narrower column)
        if (compact) (screenWidthDp * 16 / 100).coerceIn(140, 180)
        else (screenWidthDp * 22 / 100).coerceIn(CHAN_COL_WIDE - 28, 200)
    }
    else if (compact) (screenWidthDp * 18 / 100).coerceIn(60, 100)
    else (screenWidthDp * 19 / 100).coerceIn(PICON_COL, 110)
private fun epgScaleK(screenWidthDp: Int): Float =
    (screenWidthDp / 390f).coerceIn(0.9f, 1.3f)
private const val ROW_H = 64
// Modern mode: a taller row — the card carries the time ABOVE the name + a progress bar + the channel number under the picon
private const val ROW_H_M = 84
private const val DAY_SWITCH_DP = 64       // the threshold for dragging past the edge to switch the day
private const val NOW_TICK_MS = 30_000L    // how often to redraw the live line/progress
private const val DVR_REFRESH_MS = 150_000L // a silent DVR refresh (new/finished recordings)

// Where to land after switching the day by a gesture: to the end (previous day) or the start (next day)
private enum class DayJump { END, START }

/**
 * Merges multiple recordings of the same programme into a single block for the grid.
 * The same programme tends to be recorded several times with slightly different times (padding),
 * so an exact time match is not enough. We group by title and within a title
 * merge entries that overlap in time; from each cluster we keep
 * the one with the largest file (the most complete copy to play).
 * Different broadcasts of the same title (which do not overlap) stay separate.
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

/** A single merged recording block for the grid (finished = green, in progress = red). */
private data class RecBlock(
    val start: Long,
    val stop: Long,
    val title: String,
    val inProgress: Boolean,
    val entry: sk.tvhclient.shared.model.DvrEntry
)

/** A navigation cell (D-pad): the time range + what opens on OK. Identical with the render. */
private class NavCell(val start: Long, val stop: Long, val detail: GridDetail)

/** Normalises a title for duplicate comparison: lower case, without "(ST)" and without "(number)". */
private fun normRecTitle(t: String): String {
    var s = t.lowercase()
    s = s.replace("(st)", " ")
    s = Regex("\\(\\d+\\)").replace(s, " ")
    s = Regex("\\s+").replace(s, " ").trim()
    return s
}

/**
 * Merges both finished (green) and currently in-progress (red) recordings of one channel
 * into a single set of blocks. The same programme tends to be recorded several times with a different title
 * ("(ST)" variants) and time (padding) — so it merges entries whose titles match after
 * normalisation, OR that overlap in time substantially (>=40 % of the shorter of the two,
 * so that merely adjacent programmes touching through padding are not merged). In a cluster with
 * an in-progress recording the block is red (in progress), otherwise green. For a click/playback
 * the entry with the largest file (the most complete copy) is chosen.
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

// A sentinel for the "Favourites" item in the EPG filter (distinguishes it from a tag uuid and from null=All).
private const val EPG_FILTER_FAV = "\u0000fav"

// M587: a sentinel for the "Radio" item — the grid switches to radio stations.
private const val EPG_FILTER_RADIO = "\u0000radio"

// Remembering the selected EPG group per server for the app's runtime (default null = All).
private object EpgGroupFilter {
    private val sel = HashMap<String, String?>()
    fun get(serverId: String?): String? = if (serverId == null) null else sel[serverId]
    fun set(serverId: String?, group: String?) { if (serverId != null) sel[serverId] = group }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpgGridScreen(
    allRows: List<ChannelRow>,
    categories: List<sk.tvhclient.shared.api.ChannelCategory>,
    seed: Map<String, List<EpgEvent>>,
    onBack: () -> Unit,
    // M587: radio stations in the grid. From the Radio tab it opens straight in radio
    // mode (radioOnly), from the TV guide it is the "Radio" item in the group filter.
    radioRows: List<ChannelRow> = emptyList(),
    radioCategories: List<sk.tvhclient.shared.api.ChannelCategory> = emptyList(),
    radioOnly: Boolean = false,
    // M591: open straight at the stations (the TV guide from the radio player), but
    // with the option of switching back to TV channels with the filter
    startInRadio: Boolean = false,
    // M592: the channel/station the grid should start at (what is currently playing in the player)
    focusUuid: String? = null,
    // M593-fix: a counter of grid openings. When the screen stays in the composition
    // (a return to the player and the TV guide again), without it the jump to the playing
    // channel would not be repeated and the grid would stay on the previous channel.
    openToken: Int = 0,
    // A phone in modern mode plays radio through the mini player — the Radio tab
    // hands over its own launch, so that the behaviour from the grid does not differ from the list.
    onPlayRadio: ((ChannelRow, EpgEvent?) -> Unit)? = null
) {
    BackHandler { onBack() }
    val context = LocalContext.current
    val modernUi = isModernUi()
    val server = remember { Tvh.store.active() }

    // The filter by group (tag). null = All, EPG_FILTER_FAV = Favourites, otherwise a tag uuid.
    // The choice is remembered for the app's runtime per server (EpgGroupFilter), default All.
    val sid = server?.id
    var selectedGroup by remember(sid) {
        mutableStateOf(
            when {
                radioOnly -> null
                startInRadio -> EPG_FILTER_RADIO   // M591
                else -> EpgGroupFilter.get(sid)
            }
        )
    }
    // M587: the source of the rows — either TV channels, or radio (the Radio tab / the "Radio" item)
    // a tag that has both TV channels and radio stations (e.g. "Slovak") is not taken as
    // a radio one — otherwise selecting it in the TV guide would switch the grid to stations
    val radioTagUuids = remember(radioCategories, categories) {
        // a tag that also has TV channels (e.g. the server has a tag "Radio" with a few TV channels)
        // stays a TV group — the grid does not switch to stations because of it
        val tv = categories.mapNotNull { it.tag?.uuid }.toSet()
        radioCategories.mapNotNull { it.tag?.uuid }.filterNot { it in tv }.toSet()
    }
    val radioMode = radioOnly || selectedGroup == EPG_FILTER_RADIO ||
        (selectedGroup != null && selectedGroup in radioTagUuids)
    // M587: in "radio only" mode (from the Radio tab) the choice is NOT stored in the shared
    // group memory — otherwise the TV guide would open in the radio group when at channels
    fun pickGroup(g: String?) {
        selectedGroup = g
        if (!radioOnly) EpgGroupFilter.set(sid, g)
    }
    val baseRows = if (radioMode) radioRows else allRows
    val baseCats = if (radioMode) radioCategories else categories
    val rows = remember(baseRows, baseCats, selectedGroup, radioMode) {
        when (val g = selectedGroup) {
            null, EPG_FILTER_RADIO -> baseRows
            EPG_FILTER_FAV -> {
                val favs = if (sid != null) Favorites.all(context, sid) else emptySet()
                baseRows.filter { it.channel.uuid in favs }
            }
            else -> baseCats.firstOrNull { it.tag?.uuid == g }?.rows ?: baseRows
        }
    }
    // The channel list for zapping and the list in the player (CH+/CH-, overlay)
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

    // The programme/recording detail (overlays the grid); a click on a block opens it
    var detail by remember { mutableStateOf<GridDetail?>(null) }
    // The last focused block (D-pad) -> the INFO key shows its detail
    var lastFocused by remember { mutableStateOf<GridDetail?>(null) }
    val infoSig by TabController.infoKey
    LaunchedEffect(infoSig) {
        if (infoSig > 0) detail = if (detail == null) lastFocused else null
    }

    var dayOffset by remember { mutableStateOf(0) }
    val dayStart = remember(dayOffset) { dayStartSec(dayOffset) }
    val dayEnd = dayStart + DAY_MIN * 60
    // The ticking time (the live line and progress) — redrawn every 30s
    var now by remember { mutableStateOf(currentTimeSeconds()) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(NOW_TICK_MS); now = currentTimeSeconds() }
    }

    // EPG in bulk (one-off, smooth scrolling) — a shared cache via the VM.
    // A seed from now/next for an immediate first picture, until the bulk load finishes.
    val epgVm: EpgGridViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val epgFull by epgVm.epg.collectAsState()
    val epgLoading by epgVm.loading.collectAsState()
    val epgGen by epgVm.gen.collectAsState()
    // HTSP: one connection, progressively all channels (a no-op for HTTP)
    LaunchedEffect(epgGen) { epgVm.loadHtsp() }
    val epg = remember(epgFull, seed) {
        // the seed (now/next) as a basis so that something shows at once; per-channel EPG
        // (progressive) overwrites the channels where we already have full data
        if (epgFull.isEmpty()) seed
        else {
            val m = HashMap<String, List<EpgEvent>>(seed)
            m.putAll(epgFull)
            m
        }
    }

    // DVR recordings (past programmes, backwards) — a shared cache via DvrViewModel
    // (survives a tab switch, is not loaded from the server again)
    val dvrVm: DvrViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val dvrState by dvrVm.state.collectAsState()
    LaunchedEffect(Unit) { dvrVm.loadIfNeeded() }
    // A periodic (every 2.5 min) silent DVR refresh — new/finished recordings
    // appear in the grid soon (without waiting ~15 min for expiry)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(DVR_REFRESH_MS)
            dvrVm.refresh()
        }
    }
    val dvrByChannel = remember(dvrState) {
        // In the grid we want one block per programme. The same programme may be
        // recorded several times with slightly different times (padding) -> we merge
        // recordings with the same title that overlap in time, and keep
        // the one with the largest file (the most complete copy to play).
        // The archive (DvrScreen) stays untouched — all recordings are visible there.
        // We match to a channel by UUID (not by name), the name is only a fallback — otherwise
        // the block would appear on all identically named channels (e.g. regional ITV1 HD).
        (dvrState as? DvrState.Loaded)?.entries
            ?.groupBy { it.channelUuid.ifBlank { it.channelName } }
            ?.mapValues { (_, list) -> collapseDvrOverlaps(list) }
            ?: emptyMap()
    }
    val recordingList = remember(dvrState) {
        (dvrState as? DvrState.Loaded)?.recording ?: emptyList()
    }
    val inProgressByChannel = remember(recordingList) {
        // Just as with finished recordings (green): the same programme may
        // right now be being recorded several times with slightly different times (padding) /
        // on several tuners -> we merge overlapping entries with the same title,
        // so that two red blocks do not overlap in the grid.
        // We match to a channel by UUID (not by name) — with duplicate names/LCNs
        // (e.g. regional "ITV1 HD" 103) the red block would otherwise pop up on all of them.
        // The name is only a fallback for old servers without a UUID in the DVR entry.
        recordingList.groupBy { it.channelUuid.ifBlank { it.channelName } }
            .mapValues { (_, list) -> collapseDvrOverlaps(list) }
    }

    val hScroll = rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    // M388: compact density (phone <600dp only), toggled by a button in the header
    val ctxDen = LocalContext.current
    // phone = smallest dimension <600dp (compact applies in landscape too); never tablet/TV
    val epgCompact = configuration.smallestScreenWidthDp < 600 &&
        EpgDensityPref.compactStateOf(ctxDen).value
    val pxMin = if (epgCompact) 3 else PX_PER_MIN

    // The visible time window (in minutes) — the rows render only the blocks nearby,
    // not all ~40. We bucket by 30 min, so that there is no redraw on every
    // pixel of horizontal scroll. That fixes the stutters when scrolling vertically.
    val pxPerMinPx = with(density) { pxMin.dp.toPx() }
    val screenWMin = remember(configuration.screenWidthDp, pxMin) {
        (with(density) { configuration.screenWidthDp.dp.toPx() } / pxPerMinPx).toInt()
    }
    val winBucket by remember(pxPerMinPx) {
        androidx.compose.runtime.derivedStateOf { (hScroll.value / pxPerMinPx / 30f).toInt() }
    }
    val visStartMin = winBucket * 30 - screenWMin
    val visEndMin = winBucket * 30 + screenWMin * 2 + 30

    // Smooth scrolling across the day boundary: at the edge of the timeline, switch the day.
    // Detection outside nested-scroll: we observe the finger movement directly (Initial pass,
    // without consuming), so horizontalScroll works normally. When hScroll
    // stands at the edge (0 or maxValue) and the finger keeps dragging in that direction, past a threshold
    // we switch the day. This is more reliable than reading the overscroll via onPostScroll
    // (which the fling phase mostly swallowed -> the edge did not switch).
    var pendingJump by remember { mutableStateOf<DayJump?>(null) }

    // After a switch/opening, set the position: continuity across midnight, otherwise the current time
    LaunchedEffect(dayOffset, pxMin) {
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
                // centre "now" in the middle of the visible part of the timeline (the screen width
                // without the channel logo column), so that a bit of the past is visible on the left too
                val halfVisMin = ((configuration.screenWidthDp - chanColFor(configuration.screenWidthDp, epgCompact)) / 2) / pxMin
                val startMin = (nowMin - halfVisMin).coerceIn(0, DAY_MIN)
                with(density) { (startMin * pxMin).dp.toPx() }.toInt()
                    .coerceAtMost(hScroll.maxValue)
            }
        }
        hScroll.scroll(androidx.compose.foundation.MutatePriority.PreventUserInput) {
            scrollBy((targetPx - hScroll.value).toFloat())
        }
        pendingJump = null
    }

    // --- D-pad navigation (TV): down/up holds the time column (the "now" line),
    // left/right moves through programmes in time. Our own selection model — automatic
    // Compose focus did not guarantee it (it flew off to the back arrow / outside the time column). ---
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val gridFocus = remember { FocusRequester() }
    // The cursor selection (the purple frame) + the D-pad only make sense on TV; on touch (phone/tablet) it is pointless
    val isTv = remember { isTvUiMode(context) }   // M679
    val daysBack = EpgRangePref.backStateOf(context).value
    val daysForward = EpgRangePref.fwdStateOf(context).value
    var pendingCursorEdge by remember { mutableStateOf<DayJump?>(null) }
    var selRow by remember { mutableStateOf(0) }
    var anchorTime by remember { mutableStateOf(now) }        // the time at which we hold the column
    var selStart by remember { mutableStateOf<Long?>(null) }  // the start of the selected cell

    // The cells of one channel — identical with the render: merged recordings + programmes without overlap, by time
    fun navCells(idx: Int): List<NavCell> {
        val r = rows.getOrNull(idx) ?: return emptyList()
        val uuid = r.channel.uuid
        val evs = (epg[uuid] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd }
        val dvr = (dvrByChannel[r.channel.uuid] ?: dvrByChannel[r.channel.name] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd }
        val inProg = (inProgressByChannel[r.channel.uuid] ?: inProgressByChannel[r.channel.name] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd }
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
        if (target < 0 || target > rows.lastIndex) return false  // edge -> let it escape (days up / down)
        selectRowAt(target, anchorTime)
        return true
    }
    fun moveHorizontal(dir: Int): Boolean {
        val cells = navCells(selRow)
        if (cells.isEmpty()) return true
        var cur = cells.indexOfFirst { it.start == selStart }
        if (cur < 0) cur = cells.indexOfFirst { it.start <= anchorTime && anchorTime < it.stop }
        val ni = (if (cur < 0) 0 else cur) + dir
        if (ni < 0) {                       // before the first cell -> the previous day, jump to the end of the day
            if (dayOffset > -daysBack) { pendingCursorEdge = DayJump.END; dayOffset-- }
            return true
        }
        if (ni > cells.lastIndex) {          // after the last cell -> the next day, jump to the start of the day
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
    // The initial selection + a reset on a day change: on the current day the "now" line, otherwise noon
    LaunchedEffect(dayOffset, rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        if (!isTv) { selStart = null; return@LaunchedEffect }  // touch: no cursor selection
        anchorTime = when (pendingCursorEdge) {
            DayJump.END -> dayStart + DAY_MIN.toLong() * 60 - 60   // the end of the day -> the last cell
            DayJump.START -> dayStart                              // the start of the day -> the first cell
            null -> if (dayOffset == 0) now else dayStart + 12L * 3600
        }
        pendingCursorEdge = null
        selectRowAt(selRow.coerceIn(0, rows.lastIndex), anchorTime)
    }
    // M592: a grid opened from the player starts on the channel that is currently playing —
    // until now it always jumped to the first channel in the list and the user had to hunt for it.
    // It applies once after opening; further navigation is driven by the cursor.
    var didFocusPlaying by remember(openToken) { mutableStateOf(false) }   // M593-fix
    var didLeavePlaying by remember(openToken) { mutableStateOf(false) }   // M593
    LaunchedEffect(rows, focusUuid, openToken) {
        if (didFocusPlaying || focusUuid == null || rows.isEmpty()) return@LaunchedEffect
        val idx = rows.indexOfFirst { it.channel.uuid == focusUuid }
        if (idx < 0) return@LaunchedEffect
        didFocusPlaying = true
        if (isTv) {
            selectRowAt(idx, anchorTime)
        } else {
            // M593: touch has no cursor — we at least mark the channel's currently running programme,
            // so that it is obvious where in the grid the user is moving from
            selRow = idx
            selStart = cellAt(navCells(idx), now)?.start
        }
        runCatching { listState.scrollToItem(idx) }
    }
    // M593-fix: on every opening from the player the correct group is restored too
    // (radio / TV channels), not only the first time
    LaunchedEffect(openToken) {
        if (openToken > 0 && !radioOnly) {
            selectedGroup = if (startInRadio) EPG_FILTER_RADIO else EpgGroupFilter.get(sid)
        }
    }
    // Focus on the grid after opening (TV)
    LaunchedEffect(Unit) {
        if (!isTv) return@LaunchedEffect
        kotlinx.coroutines.delay(150)
        runCatching { gridFocus.requestFocus() }
    }
    // Auto-scroll to the selected row / cell
    LaunchedEffect(selRow) { runCatching { listState.animateScrollToItem(selRow) } }
    // Centring the horizontal scroll (TV): put the MIDDLE of the selected programme in the middle
    // of the screen - on opening that is the currently running programme, so "what is on live" is in the middle
    // (half to the left, half to the right). During cursor navigation it centres the selected cell.
    LaunchedEffect(selStart) {
        val s = selStart ?: return@LaunchedEffect
        val cell = navCells(selRow).firstOrNull { it.start == s }
        val midSec = if (cell != null) (cell.start + cell.stop) / 2 else s
        val midMin = (((midSec - dayStart) / 60).toInt()).coerceIn(0, DAY_MIN)
        // to the middle of the visible timeline (the screen width without the logo column)
        val halfVisPx = with(density) { ((configuration.screenWidthDp - chanColFor(configuration.screenWidthDp, epgCompact)) / 2).dp.toPx() }
        val target = with(density) { (midMin * pxMin).dp.toPx() } - halfVisPx
        runCatching { hScroll.animateScrollTo(target.toInt().coerceAtLeast(0)) }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        // M386: the outer Scaffold (MainActivity) already insets the content by the system bars;
        // the inner Scaffold + TopAppBar must not add their own insets, otherwise the EPG is
        // shifted lower than the other tabs (both in portrait and landscape).
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                title = { Text(stringResourceTvGuide()) },
                navigationIcon = {
                    Text(
                        "  \u2190  ",
                        modifier = Modifier.padding(8.dp).clickable { onBack() },
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = {
                    // M388: the density toggle (compact/comfortable) — phone <600dp only
                    if (configuration.smallestScreenWidthDp < 600) {
                        androidx.compose.material3.IconButton(onClick = {
                            EpgDensityPref.set(ctxDen, !EpgDensityPref.compactStateOf(ctxDen).value)
                        }) {
                            androidx.compose.material3.Icon(
                                if (epgCompact) Icons.Default.ViewComfy else Icons.Default.ViewCompact,
                                contentDescription = stringResource(R.string.epg_density)
                            )
                        }
                    }
                    if (epgLoading) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(22.dp).padding(end = 4.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    // The filter by group: a pill (a funnel + the current filter), opens a list.
                    // The same mechanism on TV (D-pad + OK) and on a phone (click). BACK closes it.
                    val filterLabel = when (val g = selectedGroup) {
                        null -> if (radioOnly) stringResource(R.string.tab_radio)
                                else stringResource(R.string.all_channels)
                        EPG_FILTER_FAV -> stringResource(R.string.favorites)
                        EPG_FILTER_RADIO -> "\uD83D\uDCFB " + stringResource(R.string.tab_radio)   // M587
                        else -> (categories + radioCategories).firstOrNull { it.tag?.uuid == g }?.tag?.name
                            ?: stringResource(R.string.all_channels)
                    }
                    var filterMenu by remember { mutableStateOf(false) }
                    Box {
                        Row(
                            modifier = Modifier
                                .padding(end = 4.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .clickable { filterMenu = true }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Icon(
                                Icons.Default.FilterList,
                                contentDescription = stringResource(R.string.epg_filter),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                filterLabel,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.widthIn(max = 150.dp),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            androidx.compose.material3.Icon(
                                Icons.Default.ArrowDropDown, contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        // Capping the menu height so that it fits on the screen (both in landscape and portrait,
                        // and on TV) and the bottom tags are reachable by scrolling. DropdownMenu scrolls internally.
                        val maxMenuHeight = (LocalConfiguration.current.screenHeightDp * 0.72f).dp
                        androidx.compose.material3.DropdownMenu(
                            expanded = filterMenu,
                            onDismissRequest = { filterMenu = false },
                            modifier = Modifier.heightIn(max = maxMenuHeight)
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text(stringResource(R.string.all_channels)) },
                                trailingIcon = { if (selectedGroup == null) androidx.compose.material3.Icon(Icons.Default.Check, null) },
                                onClick = { pickGroup(null); filterMenu = false }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("\u2605 " + stringResource(R.string.favorites)) },
                                trailingIcon = { if (selectedGroup == EPG_FILTER_FAV) androidx.compose.material3.Icon(Icons.Default.Check, null) },
                                onClick = { pickGroup(EPG_FILTER_FAV); filterMenu = false }
                            )
                            // M587-fix: radio comes right after Favourites and has an icon — on the
                            // server a TAG named "Radio" may also exist (with TV channels),
                            // two identical items below each other would be confusing
                            if (!radioOnly && radioRows.isNotEmpty()) {
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text("\uD83D\uDCFB " + stringResource(R.string.tab_radio)) },
                                    trailingIcon = { if (selectedGroup == EPG_FILTER_RADIO) androidx.compose.material3.Icon(Icons.Default.Check, null) },
                                    onClick = { pickGroup(EPG_FILTER_RADIO); filterMenu = false }
                                )
                            }
                            baseCats.mapNotNull { it.tag }.forEach { tag ->
                                androidx.compose.material3.DropdownMenuItem(
                                    text = { Text(tag.name) },
                                    trailingIcon = { if (selectedGroup == tag.uuid) androidx.compose.material3.Icon(Icons.Default.Check, null) },
                                    onClick = { pickGroup(tag.uuid); filterMenu = false }
                                )
                            }
                        }
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
        // M387-fix2: the displayCutout inset REMOVED — on phones with a cutout
        // on the left edge it made a wide white band in landscape and the EPG did not line up with the header;
        // the left margin is now the same as on the other tabs.
        Column(Modifier.fillMaxSize().padding(padding)) {
            // The day picker; the range backwards/forwards follows the setting (EpgRangePref) — daysBack/daysForward are defined above
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
                    val label = if (off == 0) stringResource(R.string.epg_today) else formatDayLabel(dayStartSec(off))
                    Box(Modifier.padding(end = 8.dp)) {
                        if (modernUi) {
                            // Modern mode: a two-line pill — the day in bold + the date in small print
                            val cs = MaterialTheme.colorScheme
                            val sel = off == dayOffset
                            val dayName = if (off == 0) stringResource(R.string.epg_today) else label.substringBeforeLast(" ")
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

            // The timeline (header) — scrolls horizontally together with the rows;
            // modern mode: additionally a teal bubble with the current time above the now-line
            Row {
                Spacer(Modifier.width(chanColFor(configuration.screenWidthDp, epgCompact).dp))
                Box(Modifier.horizontalScroll(hScroll)) {
                    Row {
                        // M423-fix2: the ruler respects the 12/24 h option. The tiles and
                        // the "now" bubble go through formatTimeHm, here it was "%02d:00"
                        // hard-coded — the only place where the 24-hour format remained.
                        val ruler12 = !ClockPref.is24(LocalContext.current)
                        for (h in 0 until 24) {
                            Text(
                                if (ruler12) {
                                    val h12 = if (h % 12 == 0) 12 else h % 12
                                    "%d:00 %s".format(h12, if (h < 12) "AM" else "PM")
                                } else "%02d:00".format(h),
                                modifier = Modifier.width((60 * pxMin).dp).padding(start = 4.dp, bottom = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    if (modernUi && dayOffset == 0) {
                        val nowMin = ((now - dayStart) / 60).toInt()
                        if (nowMin in 0..DAY_MIN) {
                            Box(
                                Modifier
                                    .offset(x = (nowMin * pxMin).dp)
                                    // M425: previously there was "- 26" here, i.e. half
                                    // of the bubble width estimated for the shape HH:mm.
                                    // With 12-hour time the bubble is wider and
                                    // the marker moved off the line. Now it shifts
                                    // by half of the real measured width.
                                    .layout { measurable, constraints ->
                                        val p = measurable.measure(constraints)
                                        layout(p.width, p.height) {
                                            p.place(-p.width / 2, 0)
                                        }
                                    }
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

            // The channel rows
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
                                    // the start of the day, dragging to the right (into the past) -> the previous day
                                    accum += dx
                                    if (accum >= edgePx) { pendingJump = DayJump.END; dayOffset--; switched = true }
                                } else if (!switched && atEnd && dx < 0f && dayOffset < daysForward) {
                                    // the end of the day, dragging to the left (into the future) -> the next day
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
                    // Progressive: load the EPG for this channel when the row is visible
                    LaunchedEffect(uuid, epgGen) { epgVm.ensureChannel(uuid) }
                    EpgGridRow(
                        row = row,
                        events = (epg[uuid] ?: emptyList()).filter { it.stop > dayStart && it.start < dayEnd },
                        dvr = (dvrByChannel[row.channel.uuid] ?: dvrByChannel[row.channel.name] ?: emptyList())
                            .filter { it.stop > dayStart && it.start < dayEnd },
                        inProgress = (inProgressByChannel[row.channel.uuid] ?: inProgressByChannel[row.channel.name] ?: emptyList())
                            .filter { it.stop > dayStart && it.start < dayEnd },
                        dayStart = dayStart,
                        now = now,
                        showNow = dayOffset == 0,
                        visStartMin = visStartMin,
                        visEndMin = visEndMin,
                        hScroll = hScroll,
                        loader = loader,
                        selectedStart = if (idx == selRow) selStart else null,
                        playing = uuid == focusUuid && !didLeavePlaying,   // M593
                        onClick = { ev ->
                            if (!isTv) didLeavePlaying = true   // M593: after a programme is selected the marker disappears
                            detail = GridDetail.Epg(row, ev)
                        },
                        onDvr = { e -> detail = GridDetail.Dvr(row, e) },
                        onInProgress = { rec -> detail = GridDetail.InProgress(row, rec) },
                        onFocusDetail = { lastFocused = it }
                    )
                }
            }
        }
    }

        // The detail overlay (overlays the grid, keeps its position)
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
                            is GridDetail.Epg ->
                                if (radioMode && onPlayRadio != null) onPlayRadio(d.row, d.ev)
                                else playLive(context, d.row, d.ev, radioMode)   // M587
                            is GridDetail.Dvr -> playDvr(context, d.rec)
                            is GridDetail.InProgress ->
                                if (radioMode && onPlayRadio != null) onPlayRadio(d.row, null)
                                else playLiveChannel(context, d.row, radioMode)
                        }
                    },
                    onPlayFromStart = (d as? GridDetail.InProgress)?.let { ip ->
                        { playDvr(context, ip.rec) }
                    },
                    playLabelRes = if (d is GridDetail.InProgress) R.string.play_live else R.string.play,
                    // M483: after deleting/stopping, let the block disappear from the grid
                    onDvrChanged = { dvrVm.refresh() }
                )
            }
        }
    }
}

private sealed class GridDetail {
    data class Epg(val row: ChannelRow, val ev: EpgEvent) : GridDetail()
    data class Dvr(val row: ChannelRow, val rec: sk.tvhclient.shared.model.DvrEntry) : GridDetail()
    // A recording in progress — it can be played live as well as from the start
    data class InProgress(val row: ChannelRow, val rec: sk.tvhclient.shared.model.DvrEntry) : GridDetail()
}

@Composable
private fun GridDetailContent(
    detail: GridDetail,
    loader: coil.ImageLoader,
    onBack: () -> Unit,
    onPlay: () -> Unit,
    onPlayFromStart: (() -> Unit)? = null,
    playLabelRes: Int = R.string.play,
    onDvrChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val playFocus = remember { FocusRequester() }

    // ---- M473: recording a programme from the detail (both modern and classic mode) ----
    val dvrScope = rememberCoroutineScope()
    val recEventId = (detail as? GridDetail.Epg)?.ev?.eventId
    var canRecord by remember { mutableStateOf(false) }
    var recBusy by remember { mutableStateOf(false) }
    var recMsg by remember { mutableStateOf<String?>(null) }
    // M484: an error must be distinguished by colour — "Could not add dvrEntry" in green
    // looked like successful scheduling
    var recOk by remember { mutableStateOf(true) }
    // M483: after a successful deletion/stop we no longer offer the button (the entry is gone)
    var recDone by remember { mutableStateOf(false) }
    // M483: confirming a deletion/stop
    var confirmDvr by remember { mutableStateOf(false) }
    // M475: if the recording already exists, we hold on to it — we offer to cancel it
    var existingRec by remember {
        mutableStateOf<sk.tvhclient.shared.model.DvrEntry?>(null)
    }
    var recReload by remember { mutableStateOf(0) }
    // M483: the rights are needed for a DVR entry too (deleting), not just for an EPG programme
    LaunchedEffect(detail, recReload) {
        val ep = (detail as? GridDetail.Epg)
        val srv = sk.tvhclient.shared.Tvh.store.active()
        canRecord = if (srv == null) false else DvrController.access(srv).canRecord
        existingRec = if (ep == null || srv == null) null
        else DvrController.scheduledFor(srv, ep.row.channel.uuid, ep.ev.start, ep.ev.stop)
    }
    // M483: the entry that the delete/stop actions concern
    val dvrEntry: sk.tvhclient.shared.model.DvrEntry? = when (detail) {
        is GridDetail.Dvr -> detail.rec
        is GridDetail.InProgress -> detail.rec
        is GridDetail.Epg -> null
    }
    val stopping = detail is GridDetail.InProgress

    // The fields common to both types
    val title: String
    val subtitle: String
    val channelName: String
    val piconUrl: String?
    val start: Long
    val stop: Long
    val desc: String
    val ageRating: Int
    val episode: String
    // M483: do we have a file on the server that can be played? (previously `recorded` —
    // the same field was also used for the badge, which is why a recording in progress
    // reported "Recorded". The badge is now handled separately, by state.)
    val hasFile: Boolean
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
            hasFile = false
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
            hasFile = true
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
            hasFile = true
        }
    }
    val durationMin = ((stop - start) / 60).toInt()

    androidx.compose.foundation.layout.Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(androidx.compose.foundation.rememberScrollState())
    ) {
        // The header with the picon and the back button
        val detailModern = isModernUi()
        val dcs = MaterialTheme.colorScheme
        Box(
            Modifier
                .fillMaxWidth()
                .height(160.dp)
                .background(
                    if (detailModern) androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            dcs.primaryContainer.copy(alpha = if (isLightTheme()) 0.55f else 0.45f),
                            dcs.background
                        )
                    ) else androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(dcs.surfaceVariant, dcs.surfaceVariant)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            if (piconUrl != null) {
                if (detailModern) {
                    // the picon on a light plate (white picons on a dark background)
                    Box(
                        Modifier
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .background(if (isLightTheme()) dcs.surfaceContainerLowest else dcs.surfaceContainerHigh)
                            .border(1.dp, dcs.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                            .padding(horizontal = 20.dp, vertical = 12.dp)
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(piconUrl).build(),
                            contentDescription = channelName,
                            imageLoader = loader,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.height(72.dp)
                        )
                    }
                } else {
                    AsyncImage(
                        model = ImageRequest.Builder(context).data(piconUrl).build(),
                        contentDescription = channelName,
                        imageLoader = loader,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.height(90.dp).padding(8.dp)
                    )
                }
            } else {
                Text(channelName, style = MaterialTheme.typography.titleLarge)
            }
            if (detailModern) {
                Box(
                    Modifier.align(Alignment.TopStart).padding(10.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(dcs.surfaceContainerHigh.copy(alpha = 0.9f))
                        .dpadFocusable(androidx.compose.foundation.shape.CircleShape)
                        .clickable { onBack() }
                        .padding(10.dp)
                ) {
                    Text("\u2190", style = MaterialTheme.typography.titleMedium, color = dcs.onSurface)
                }
            } else {
                androidx.compose.material3.IconButton(
                    onClick = onBack,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp)
                ) {
                    Text("\u2190", style = MaterialTheme.typography.titleLarge)
                }
            }
        }

        androidx.compose.foundation.layout.Column(Modifier.padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = if (detailModern) androidx.compose.ui.text.font.FontWeight.Bold else null
            )
            // M483: three states instead of one — a recording in progress is red
            // (the same as its block in the grid), finished and scheduled ones are in the accent colour.
            // M485: a scheduled recording of a programme that is already running is "Recording" —
            // the state is read from the entry, not just from where the detail was opened from
            val exRec = existingRec
            val epgRecordingNow = exRec != null && exRec.isRecordingNow
            val badgeRes: Int? = when {
                detail is GridDetail.InProgress -> R.string.epg_recording_badge
                detail is GridDetail.Dvr -> R.string.epg_recorded_badge
                epgRecordingNow -> R.string.epg_recording_badge
                exRec != null -> R.string.epg_scheduled_badge
                else -> null
            }
            if (detailModern && badgeRes != null) {
                val live = detail is GridDetail.InProgress || epgRecordingNow
                val badgeColor = if (live) androidx.compose.ui.graphics.Color(0xFFE53935) else dcs.primary
                val glyph = when {
                    live -> "\u25CF "
                    detail is GridDetail.Dvr -> "\u2713 "
                    else -> "\u23F1 "
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
                        .background(
                            if (live) badgeColor.copy(alpha = 0.18f)
                            else dcs.primaryContainer.copy(alpha = 0.6f)
                        )
                        .padding(horizontal = 10.dp, vertical = 3.dp)
                ) {
                    Text(
                        glyph + stringResource(badgeRes),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = badgeColor
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            // The meta row: channel · date · time · length
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
            // Play only if there is something to play: a DVR recording, or an EPG programme
            // that is running right now (live). A future/unrecorded one cannot be played.
            val nowSec = currentTimeSeconds()
            val playable = hasFile || (start <= nowSec && nowSec < stop)
            if (playable) {
                // On TV/a remote, put the initial focus on Play, so that OK works straight away
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
                // A recording in progress — play from the start (you will catch up with the start)
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

            // M473: recording — only for an EPG programme that has not ended yet,
            // and only if the user has the right to record on the server
            // M475: record / cancel a scheduled recording
            val rec = existingRec
            if (canRecord && recEventId != null && stop > nowSec) {
                Spacer(Modifier.height(10.dp))
                // M606: an optional DVR profile selection before recording
                var askProfiles by remember { mutableStateOf<List<String>>(emptyList()) }
                fun doRecord(profile: String?) {
                        val srv = sk.tvhclient.shared.Tvh.store.active() ?: return
                        recBusy = true; recMsg = null
                        dvrScope.launch {
                            if (profile != null) DvrAskPref.setLastUsed(context, srv.id, profile)
                            val r = if (rec != null) DvrController.cancel(srv, rec)
                            // M484: the programme description -> the entry is reflected in the list immediately
                            else DvrController.recordEvent(
                                srv, recEventId,
                                (detail as? GridDetail.Epg)?.row?.channel?.uuid ?: "",
                                start, stop, title, profile
                            )
                            // M484: on a duplicate the server returns only a terse error —
                            // we look up where the recording already is
                            val dup = if (r.success || rec != null) null
                            else DvrController.duplicateOf(srv, title)
                            recBusy = false
                            recOk = r.success
                            recMsg = when {
                                r.success && rec != null -> context.getString(R.string.dvr_rec_cancelled)
                                r.success -> context.getString(R.string.dvr_rec_scheduled)
                                dup != null && dup.channelName.isNotBlank() ->
                                    context.getString(
                                        R.string.dvr_rec_duplicate, dup.channelName,
                                        formatDayLabel(dup.start) + " " + formatTimeHm(dup.start)
                                    )
                                else -> ConnLimitText.of(context, r.error) ?: context.getString(   // M692
                                    if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed
                                )
                            }
                            if (r.success) {
                                recReload++          // determine the state again
                                onDvrChanged()       // M485: the blocks in the grid too
                            }
                        }
                }
                if (askProfiles.isNotEmpty()) {
                    val srv = sk.tvhclient.shared.Tvh.store.active()
                    DvrProfilePickDialog(
                        options = askProfiles,
                        subtitle = title,
                        lastUsed = srv?.let { DvrAskPref.lastUsed(context, it.id) },
                        selected = -1,
                        onPick = { name -> askProfiles = emptyList(); doRecord(name) },
                        onDismiss = { askProfiles = emptyList() },
                        dpad = true
                    )
                }
                androidx.compose.material3.OutlinedButton(
                    onClick = {
                        val srv = sk.tvhclient.shared.Tvh.store.active() ?: return@OutlinedButton
                        if (rec != null) { doRecord(null); return@OutlinedButton }
                        dvrScope.launch {
                            val opts = DvrProfileAsk.options(context, srv)
                            if (opts.isEmpty()) doRecord(null) else askProfiles = opts
                        }
                    },
                    enabled = !recBusy,
                    // M483: the same width and internal layout as the playback
                    // buttons above — previously the button was narrow, sized to the text
                    modifier = Modifier.fillMaxWidth().dpadFocusable()
                ) {
                    androidx.compose.material3.Icon(
                        when {
                            // M485: a running recording is "stopped", not cancelled
                            epgRecordingNow -> Icons.Default.Stop
                            rec != null -> Icons.Default.Close
                            else -> Icons.Default.FiberManualRecord
                        },
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            recBusy -> stringResource(R.string.dvr_rec_working)
                            epgRecordingNow -> stringResource(R.string.dvr_stop_button)
                            rec != null -> stringResource(R.string.dvr_rec_cancel_button)
                            else -> stringResource(R.string.dvr_rec_button)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            // M483: a DVR entry — a finished recording can be deleted, one in progress
            // stopped. Until now the button was only offered for an EPG programme, so
            // a recording opened from the grid could not be removed at all.
            if (canRecord && dvrEntry != null && !recDone) {
                Spacer(Modifier.height(10.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { confirmDvr = true },
                    enabled = !recBusy,
                    modifier = Modifier.fillMaxWidth().dpadFocusable()
                ) {
                    androidx.compose.material3.Icon(
                        if (stopping) Icons.Default.Stop else Icons.Default.Delete,
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            recBusy -> stringResource(R.string.dvr_del_working)
                            stopping -> stringResource(R.string.dvr_stop_button)
                            else -> stringResource(R.string.dvr_delete_button)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }

            recMsg?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium,
                    color = if (recOk) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error)
            }

            if (desc.isNotBlank() && detailModern) {
                Spacer(Modifier.height(16.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(if (isLightTheme()) dcs.surfaceContainerLowest else dcs.surfaceContainer)
                        .border(1.dp, dcs.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text(desc, style = MaterialTheme.typography.bodyMedium)
                }
            } else if (desc.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                Text(desc, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }

    // M483: deleting/stopping always asks — it is irreversible and on a remote
    // control OK is easily pressed by accident.
    if (confirmDvr && dvrEntry != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDvr = false },
            title = {
                Text(stringResource(
                    if (stopping) R.string.dvr_stop_title else R.string.dvr_del_title
                ))
            },
            text = {
                Text(stringResource(
                    if (stopping) R.string.dvr_stop_msg else R.string.dvr_del_msg,
                    dvrEntry.title
                ))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDvr = false
                    val srv = sk.tvhclient.shared.Tvh.store.active()
                        ?: return@TextButton
                    recBusy = true; recMsg = null
                    dvrScope.launch {
                        val r = if (stopping) DvrController.cancel(srv, dvrEntry)
                        else DvrController.delete(srv, dvrEntry)
                        recBusy = false
                        recOk = r.success
                        recMsg = when {
                            r.success && stopping -> context.getString(R.string.dvr_stop_done)
                            r.success -> context.getString(R.string.dvr_del_done)
                            else -> r.error ?: context.getString(
                                if (r.timeout) R.string.err_timeout else R.string.dvr_del_failed
                            )
                        }
                        if (r.success) {
                            recDone = true
                            onDvrChanged()   // the grid loads the DVR list again
                        }
                    }
                }) {
                    Text(stringResource(
                        if (stopping) R.string.dvr_stop_button else R.string.delete
                    ))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDvr = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
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
    playing: Boolean = false,   // M593: the channel/station that is currently playing in the player
    onClick: (EpgEvent) -> Unit,
    onDvr: (sk.tvhclient.shared.model.DvrEntry) -> Unit,
    onInProgress: (sk.tvhclient.shared.model.DvrEntry) -> Unit,
    onFocusDetail: (GridDetail) -> Unit = {}
) {
    val context = LocalContext.current
    val modern = isModernUi()
    // M387: adaptive dimensions by window width; wide screens (>=600dp) stay
    // exactly as after M377 (k = 1, the original row heights)
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    // M388: compact density on a phone (the toggle in the EPG header)
    val ctxDen = LocalContext.current
    val epgCompact = conf.smallestScreenWidthDp < 600 && EpgDensityPref.compactStateOf(ctxDen).value
    val pxMin = if (epgCompact) 3 else PX_PER_MIN
    val chanW = chanColFor(conf.screenWidthDp, epgCompact)
    val wide = conf.screenWidthDp >= 600
    val k = if (wide) 1f else epgScaleK(conf.screenWidthDp)
    // narrow screens: the row is a bit taller, so that "number · name" fits under the picon;
    // compact = lower rows (more channels on screen)
    val rowH = if (wide) {
        // M388-fix: a phone in landscape can be compact too (lower rows)
        if (epgCompact) { if (modern) 58 else 50 } else { if (modern) ROW_H_M else ROW_H }
    }
        else if (epgCompact) ((if (modern) 62 else 54) * k).toInt()
        else (((if (modern) ROW_H_M + 6 else ROW_H + 16)) * k).toInt()
    Row(Modifier.height(rowH.dp)) {
        // The channel column. M377: on wide screens (TV/tablet)
        // picon → number → name in a row (as in the Tvheadend web UI) for both modes;
        // M387: on narrow phones the picon + "number · name" underneath it.
        if (wide) {
            val cs = MaterialTheme.colorScheme
            Row(
                Modifier.width(chanW.dp).height(rowH.dp)
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .then(
                        if (modern) Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    // M593: the playing channel is highlighted in a grid opened from the player
                                    playing -> cs.primaryContainer.copy(alpha = if (isLightTheme()) 0.5f else 0.4f)
                                    isLightTheme() -> cs.surfaceContainerLowest
                                    else -> cs.surfaceContainer
                                }
                            )
                            .border(
                                if (playing) 2.dp else 1.dp,
                                if (playing) cs.primary else cs.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                        else if (playing) Modifier   // M593: classic mode — an outline only
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, cs.primary, RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .padding(start = 4.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier.size((rowH - 18).dp.coerceAtMost(52.dp))
                        .clip(RoundedCornerShape(if (modern) 9.dp else 4.dp))
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
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = cs.primary,
                        maxLines = 1,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
                Text(
                    row.channel.name,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 6.dp).weight(1f)
                )
            }
        } else {
            // M387: narrow screens (both classic and modern) — the picon on top, under it
            // a single "number · name" line (ellipsised); without a picon, a large number.
            val cs = MaterialTheme.colorScheme
            val num = row.channel.number
            val label = if (num != null && num > 0) "$num · ${row.channel.name}" else row.channel.name
            Column(
                Modifier.width(chanW.dp).height(rowH.dp)
                    .padding(horizontal = 4.dp, vertical = 3.dp)
                    .then(
                        if (modern) Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                when {
                                    playing -> cs.primaryContainer.copy(alpha = if (isLightTheme()) 0.5f else 0.4f)   // M593
                                    isLightTheme() -> cs.surfaceContainerLowest
                                    else -> cs.surfaceContainer
                                }
                            )
                            .border(
                                if (playing) 2.dp else 1.dp,
                                if (playing) cs.primary else cs.outlineVariant,
                                RoundedCornerShape(12.dp)
                            )
                        else if (playing) Modifier   // M593
                            .clip(RoundedCornerShape(8.dp))
                            .border(2.dp, cs.primary, RoundedCornerShape(8.dp))
                        else Modifier
                    )
                    .padding(bottom = 3.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.fillMaxWidth().weight(1f)
                        .padding(
                            start = if (epgCompact) 2.dp else 4.dp,
                            end = if (epgCompact) 2.dp else 4.dp,
                            top = if (epgCompact) 2.dp else 4.dp,
                            bottom = if (epgCompact) 1.dp else 2.dp
                        )
                        .clip(RoundedCornerShape(if (modern) 9.dp else 4.dp))
                        .background(piconBackground()),
                    contentAlignment = Alignment.Center
                ) {
                    if (row.piconUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(row.piconUrl).build(),
                            contentDescription = row.channel.name,
                            imageLoader = loader,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(if (epgCompact) 1.dp else 3.dp)
                        )
                    } else if (num != null && num > 0) {
                        Text(
                            num.toString(),
                            fontSize = ((if (epgCompact) 16 else 20) * k).sp,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            color = cs.primary,
                            maxLines = 1
                        )
                    } else {
                        Text(
                            row.channel.name.take(3).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    label,
                    fontSize = ((if (epgCompact) 9 else 10) * k).sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (modern) cs.onSurfaceVariant else cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
        }
        // The programme area (scrolls horizontally)
        Box(
            Modifier
                .horizontalScroll(hScroll)
                .height(rowH.dp)
        ) {
            Box(Modifier.width((DAY_MIN * pxMin).dp).height(rowH.dp)) {
                // Recordings (finished green + in-progress red) merged into a single
                // set of blocks — without overlaps and duplicates (including "(ST)" title variants);
                // we render only the blocks in the visible window (culling)
                val recBlocks = remember(dvr, inProgress, now / 60) {
                    mergeRecordings(dvr.filter { it.stop <= now }, inProgress)
                }
                // Adjacent recordings of different programmes often overlap by the margin (padding).
                // We cut the overlap in its middle, so that the blocks abut and do not overlap.
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
                            rowH = rowH, pxMin = pxMin, compact = epgCompact,
                            title = rb.title,
                            timeLabel = formatTimeHm(rb.start) + " - " + formatTimeHm(rb.stop),
                            kind = MgKind.REC,
                            progressFrac = ((now - vStart).toFloat() / (vStop - vStart).coerceAtLeast(1)).coerceIn(0f, 1f),
                            selected = selectedStart == rb.start,
                            onClick = { onInProgress(rb.entry) }
                        ) else GridBlock(
                            rowH = rowH, pxMin = pxMin, compact = epgCompact,
                            startMin = startMin,
                            endMin = endMin,
                            title = rb.title,
                            timeLabel = formatTimeHm(rb.start) + " - " + formatTimeHm(rb.stop),
                            bg = Color(0x2EEF5350),       // lighter = not recorded yet (past the line)
                            recorded = false,
                            progressMin = ((now - vStart) / 60).toInt(),
                            progressColor = Color(0x80EF5350),  // darker = already recorded (before the line)
                            prefix = "\u25CF ",
                            selected = selectedStart == rb.start,
                            onClick = { onInProgress(rb.entry) },
                            onFocused = { onFocusDetail(GridDetail.InProgress(row, rb.entry)) }
                        )
                    } else {
                        if (modern) ModernGridBlock(
                            startMin = startMin,
                            endMin = endMin,
                            rowH = rowH, pxMin = pxMin, compact = epgCompact,
                            title = rb.title,
                            timeLabel = formatTimeHm(rb.start) + " - " + formatTimeHm(rb.stop),
                            kind = MgKind.RECORDED,
                            selected = selectedStart == rb.start,
                            onClick = { onDvr(rb.entry) }
                        ) else GridBlock(
                            rowH = rowH, pxMin = pxMin, compact = epgCompact,
                            startMin = startMin,
                            endMin = endMin,
                            title = rb.title,
                            timeLabel = formatTimeHm(rb.start) + " - " + formatTimeHm(rb.stop),
                            bg = if (isLightTheme()) Color(0xA643A047) else Color(0x5C43A047),  // green = recorded
                            recorded = true,
                            selected = selectedStart == rb.start,
                            onClick = { onDvr(rb.entry) },
                            onFocused = { onFocusDetail(GridDetail.Dvr(row, rb.entry)) }
                        )
                    }
                }
                // Programmes from the EPG including past ones (history); skip those that
                // are already shown by some merged recording block, so that there are not two blocks
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
                        rowH = rowH, pxMin = pxMin, compact = epgCompact,
                        title = ev.title.ifBlank { "—" },
                        timeLabel = formatTimeHm(ev.start) + " - " + formatTimeHm(ev.stop),
                        kind = when { isNow -> MgKind.NOW; isPast -> MgKind.PAST; else -> MgKind.FUTURE },
                        progressFrac = if (isNow)
                            ((now - ev.start).toFloat() / (ev.stop - ev.start).coerceAtLeast(1)).coerceIn(0f, 1f)
                        else 0f,
                        selected = selectedStart == ev.start,
                        onClick = { onClick(ev) }
                    ) else GridBlock(
                        rowH = rowH, pxMin = pxMin, compact = epgCompact,
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
                // The vertical live line of the current time (subtle, matching the colour);
                // modern mode: a more prominent teal line continuing from the bubble in the axis
                if (showNow) {
                    val nowMin = ((now - dayStart) / 60).toInt()
                    if (nowMin in 0..DAY_MIN) {
                        Box(
                            Modifier
                                .offset(x = (nowMin * pxMin).dp)
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
    rowH: Int = ROW_H,
    pxMin: Int = PX_PER_MIN,
    compact: Boolean = false,
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
    val cellW = wMin * pxMin
    val fullTitle = (prefix ?: if (recorded) "\u25B6 " else "") + title
    // M377: focus-expansion — when the selected cell is too narrow for the whole title,
    // we render a temporary wider bubble above it (an overlay with a zIndex; the grid
    // does not move, the bubble disappears when the cursor leaves). We measure the real text width.
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val titleStyle = MaterialTheme.typography.bodySmall
    val density = androidx.compose.ui.platform.LocalDensity.current
    val needDp = remember(fullTitle, titleStyle) {
        with(density) {
            measurer.measure(
                androidx.compose.ui.text.AnnotatedString(fullTitle),
                style = titleStyle, maxLines = 1
            ).size.width.toDp().value
        }
    }
    val expand = selected && needDp > (cellW - 16f)
    val bubbleW = if (expand) (needDp + 24f).coerceAtMost(320f).coerceAtLeast(cellW.toFloat()) else 0f
    val shiftDp = if (expand) {
        val over = (startMin * pxMin + bubbleW) - (DAY_MIN * pxMin)
        if (over > 0f) -over else 0f
    } else 0f
    Box(
        Modifier
            .offset(x = (startMin * pxMin).dp)
            .width(cellW.dp)
            .height(rowH.dp)
            .zIndex(if (expand) 2f else 0f)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(2.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(bg)
                .then(
                    if (selected && !expand) Modifier.border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                    else Modifier
                )
                .pointerInput(Unit) { detectTapGestures { onClick() } }
        ) {
            // Progress from the left: for a live programme the lighter primary colour, for a recording the darker red
            if (progressMin > 0) {
                Box(
                    Modifier
                        .width((progressMin.coerceAtMost(wMin) * pxMin).dp)
                        .height(rowH.dp)
                        .background(progressColor ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.9f))
                )
            }
            Column(Modifier.padding(horizontal = if (compact) 5.dp else 6.dp, vertical = if (compact) 2.dp else 4.dp)) {
                Text(
                    fullTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    timeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = timeColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        if (expand) {
            Box(
                Modifier
                    .offset(x = shiftDp.dp)
                    .requiredWidth(bubbleW.dp)
                    .height(rowH.dp)
                    .padding(2.dp)
                    .shadow(8.dp, RoundedCornerShape(6.dp))
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(2.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
                    .pointerInput(Unit) { detectTapGestures { onClick() } }
            ) {
                Column(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                    Text(
                        fullTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun playLive(context: android.content.Context, row: ChannelRow, ev: EpgEvent, radio: Boolean = false) {
    val intent = android.content.Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_UUID, row.channel.uuid)
        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
        putExtra(PlayerActivity.EXTRA_PROG_START, ev.start)
        putExtra(PlayerActivity.EXTRA_PROG_STOP, ev.stop)
        putExtra(PlayerActivity.EXTRA_PROG_TITLE, ev.title)
        if (radio) putExtra(PlayerActivity.EXTRA_KIND, "radio")   // M587
    }
    LivePlaylist.setIndexForUuid(row.channel.uuid)
    context.startActivity(intent)
}

/** Live playback of a channel without a specific programme (for a recording in progress). */
private fun playLiveChannel(context: android.content.Context, row: ChannelRow, radio: Boolean = false) {
    val intent = android.content.Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_UUID, row.channel.uuid)
        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
        if (radio) putExtra(PlayerActivity.EXTRA_KIND, "radio")   // M587
    }
    LivePlaylist.setIndexForUuid(row.channel.uuid)
    context.startActivity(intent)
}

/** Playback of a past programme from a DVR recording (including resume/position). */

/** The start of the day (local midnight) + a day offset, in seconds. */
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

/** The kind of card in the modern grid. */
private enum class MgKind { NOW, PAST, FUTURE, REC, RECORDED }

/**
 * A programme card in modern grid mode: the time in small print ABOVE a bold title,
 * gaps between the cards, recordings have a coloured edge on the left + a badge instead of
 * a full-area fill, the progress is a thin bar at the bottom of the card.
 */
@Composable
private fun ModernGridBlock(
    startMin: Int,
    endMin: Int,
    rowH: Int,
    pxMin: Int = PX_PER_MIN,
    compact: Boolean = false,
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
    val cellW = wMin * pxMin
    // M377: focus-expansion of short cards (the same principle as in classic mode)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    val mTitleStyle = MaterialTheme.typography.bodyMedium
    val mDensity = androidx.compose.ui.platform.LocalDensity.current
    val needDp = remember(title, mTitleStyle) {
        with(mDensity) {
            measurer.measure(
                androidx.compose.ui.text.AnnotatedString(title),
                style = mTitleStyle.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                maxLines = 1
            ).size.width.toDp().value
        }
    }
    val expand = selected && needDp > (cellW - 18f)
    val bubbleW = if (expand) (needDp + 28f).coerceAtMost(340f).coerceAtLeast(cellW.toFloat()) else 0f
    val shiftDp = if (expand) {
        val over = (startMin * pxMin + bubbleW) - (DAY_MIN * pxMin)
        if (over > 0f) -over else 0f
    } else 0f
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
            .offset(x = (startMin * pxMin).dp)
            .width(cellW.dp)
            .height(rowH.dp)
            .zIndex(if (expand) 2f else 0f)
            .padding(horizontal = 2.dp, vertical = 3.dp)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(12.dp))
                .background(bg)
                .then(
                    when {
                        selected && !expand -> Modifier.border(2.5.dp, cs.primary, RoundedCornerShape(12.dp))
                        selected -> Modifier
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
                        end = 6.dp, top = if (compact) 3.dp else 5.dp, bottom = if (compact) 3.dp else 5.dp
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
        if (expand) {
            Box(
                Modifier
                    .offset(x = shiftDp.dp)
                    .requiredWidth(bubbleW.dp)
                    .fillMaxHeight()
                    .shadow(10.dp, RoundedCornerShape(12.dp))
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (light) cs.surfaceContainerLowest else cs.surfaceContainer)
                    .border(2.5.dp, cs.primary, RoundedCornerShape(12.dp))
                    .pointerInput(Unit) { detectTapGestures { onClick() } }
            ) {
                Column(
                    Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 5.dp, bottom = 5.dp)
                ) {
                    Text(
                        timeLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                        color = cs.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
