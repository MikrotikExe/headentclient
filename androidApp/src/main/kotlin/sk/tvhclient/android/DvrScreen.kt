package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.focusGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Movie
import androidx.compose.foundation.border
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Movie
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.dateKey
import sk.tvhclient.shared.formatDateFull
import sk.tvhclient.shared.formatTimeHm
import sk.tvhclient.shared.model.DvrClassifier
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.ImdbLookup

// Navigacia v archive (read-only zlozky)
private sealed class DvrNav {
    data object Root : DvrNav()
    data object Recent : DvrNav()
    data object Channels : DvrNav()
    data class Dates(val channel: String) : DvrNav()
    data class Day(val channel: String, val dateKey: String) : DvrNav()
    data class Category(val catKey: String) : DvrNav()
    data class Subgenre(val catKey: String, val subKey: String) : DvrNav()
    data class Series(val catKey: String, val subKey: String, val seriesTitle: String) : DvrNav()
}

@Composable
fun DvrScreen(vm: DvrViewModel = viewModel(), resetSignal: Int = 0) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var nav by remember { mutableStateOf<DvrNav>(DvrNav.Root) }
    var search by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(DvrViewPref.get(context)) }
    var viewMenu by remember { mutableStateOf(false) }
    // Klik na tab Archiv (aj uz vybrany) vrati na zaciatok (root + zrusene hladanie)
    LaunchedEffect(resetSignal) {
        nav = DvrNav.Root
        search = ""
    }
    // Korpus titulov pre podzanre (nacita sa raz z assetu)
    var corpusReady by remember { mutableStateOf(DvrClassifier.hasCorpus()) }
    LaunchedEffect(Unit) {
        if (!DvrClassifier.hasCorpus()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadCorpusFromAssets(context) }
            corpusReady = true
        }
    }

    // Po navrate z prehravaca obnov priznaky sledovania (hviezdicka/pozicia)
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var progressTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) progressTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) { vm.loadIfNeeded() }

    // IMDb online lookup: nacitaj cache z disku + dopln na pozadi necachnute
    // filmy/serialy (slovenske/ceske nazvy ktore korpus nepozna).
    var imdbTick by remember { mutableStateOf(0) }
    LaunchedEffect(state) {
        val s = state
        if (s !is DvrState.Loaded) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadImdbCache(context)
        }
        imdbTick++
        // tituly filmov/serialov co treba dohladat
        val pending = LinkedHashSet<String>()
        for (e in s.entries) {
            val cat = DvrClassifier.classify(e)
            if (cat != DvrClassifier.FILM && cat != DvrClassifier.SERIAL) continue
            val title = e.title
            if (ImdbLookup.isCached(title) || !ImdbLookup.worthSearching(title)) continue
            pending.add(title)
        }
        var done = 0
        for (title in pending) {
            if (ImdbLookup.fetch(title)) {
                done++
                if (done % 20 == 0) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveImdbCache(context) }
                    imdbTick++  // priebezne prekreslenie
                }
                kotlinx.coroutines.delay(1100)  // rate-limit IMDb
            }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveImdbCache(context) }
        imdbTick++
    }

    // Spat: ak hladame, zrus hladanie; inak ak nie sme v root, vrat sa vyssie
    BackHandler(enabled = nav != DvrNav.Root || search.isNotBlank()) {
        if (search.isNotBlank()) {
            search = ""
        } else {
            nav = when (val n = nav) {
                is DvrNav.Day -> DvrNav.Dates(n.channel)
                is DvrNav.Dates -> DvrNav.Channels
                is DvrNav.Channels -> DvrNav.Root
                is DvrNav.Recent -> DvrNav.Root
                is DvrNav.Series -> DvrNav.Subgenre(n.catKey, n.subKey)
                is DvrNav.Subgenre -> DvrNav.Category(n.catKey)
                is DvrNav.Category -> DvrNav.Root
                else -> DvrNav.Root
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Vyhladavanie nahravok (cez vsetky, podla nazvu) — klavesnica az po OK
        val searchFocus = remember { FocusRequester() }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            TvSearchBar(
                query = search,
                placeholder = stringResource(R.string.dvr_search),
                onQueryChange = { search = it },
                focusRequester = searchFocus,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.IconButton(onClick = { vm.refresh() }) {
                androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retry),
                    tint = MaterialTheme.colorScheme.onSurface)
            }
            Box {
                androidx.compose.material3.IconButton(onClick = { viewMenu = true }) {
                    androidx.compose.material3.Icon(
                        when (viewMode) {
                            ChannelViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                            ChannelViewMode.GRID -> Icons.Default.GridView
                            ChannelViewMode.TILES -> Icons.Default.ViewModule
                        },
                        contentDescription = stringResource(R.string.view_mode),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                }
                androidx.compose.material3.DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_list)) },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ViewList, null) },
                        onClick = { viewMode = ChannelViewMode.LIST; DvrViewPref.set(context, viewMode); viewMenu = false }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_grid)) },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Default.GridView, null) },
                        onClick = { viewMode = ChannelViewMode.GRID; DvrViewPref.set(context, viewMode); viewMenu = false }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_tiles)) },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Default.ViewModule, null) },
                        onClick = { viewMode = ChannelViewMode.TILES; DvrViewPref.set(context, viewMode); viewMenu = false }
                    )
                }
            }
        }
        val contentFocus = remember { FocusRequester() }
        // Po nacitani / zmene urovne daj focus na obsah (prvu zlozku), nech sa
        // pri vstupe do archivu neoznaci hladanie a nevyskoci klavesnica.
        LaunchedEffect(state is DvrState.Loaded, nav) {
            if (state is DvrState.Loaded && search.isBlank()) {
                runCatching { contentFocus.requestFocus() }
            }
        }
        Box(
            Modifier.weight(1f).fillMaxWidth()
                .focusRequester(contentFocus)
                .focusGroup()
        ) {
            when (val s = state) {
                is DvrState.Loading -> LoadingStatus()
                is DvrState.NoServer -> NoServerStatus()
                is DvrState.Error -> ErrorStatus(s.message, onRetry = { vm.load() })
                is DvrState.Loaded -> {
                    if (search.isNotBlank()) {
                        val q = normalizeSearch(search)
                        val results = remember(search, s.entries) {
                            s.entries.filter { normalizeSearch(it.title).contains(q) }
                                .sortedByDescending { it.start }
                        }
                        if (results.isEmpty()) {
                            EmptyStatus(stringResource(R.string.dvr_search_empty))
                        } else {
                            RecordingList(results, context, progressTick,
                                header = stringResource(R.string.dvr_search_results) + " (${results.size})",
                                viewMode = viewMode)
                        }
                    } else if (s.entries.isEmpty()) {
                        EmptyStatus(stringResource(R.string.dvr_empty))
                    } else {
                        androidx.compose.runtime.key(corpusReady, imdbTick) {
                            DvrContent(s.entries, s.channelOrder, s.channelPicons, nav, context, progressTick, viewMode = viewMode, onNav = { nav = it })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DvrContent(
    entries: List<DvrEntry>,
    channelOrder: Map<String, Int>,
    channelPicons: Map<String, String?>,
    nav: DvrNav,
    context: Context,
    progressTick: Int,
    viewMode: ChannelViewMode = ChannelViewMode.LIST,
    onNav: (DvrNav) -> Unit
) {
    val server = remember { Tvh.store.active() }
    val piconLoader = remember(server?.id) { PiconImageLoader.get(context, server) }
    // Klasifikacia je draha (strip diakritiky + regexy nad title/subtitle/popis/kanal).
    // Klasifikuj kazdu nahravku RAZ na nacitanie (memoizovane podla entries), nie pri
    // kazdej rekompozicii/navigacii - pri ~7000 nahravkach to inak sekalo pri kazdom kliku.
    val catOf = remember(entries) { entries.associateWith { DvrClassifier.classify(it) } }
    val byCatAll = remember(entries) { entries.groupBy { catOf.getValue(it) } }
    when (nav) {
        is DvrNav.Root -> {
            // Zlozky: Posledne sledovane + Podla kanalov + kategorie
            val byCat = byCatAll
            val recentCount = remember(progressTick, entries) {
                if (server == null) 0 else {
                    val uuids = entries.mapTo(HashSet()) { it.uuid }
                    WatchProgress.recent(context, server.id).count { it.first in uuids }
                }
            }
            val cats = DvrClassifier.order.filter { byCat.containsKey(it) }
            val chCount = entries.map { it.channelName }.distinct().size
            if (viewMode == ChannelViewMode.LIST) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item("hdr") { Header(stringResource(R.string.dvr_archive)) }
                    if (recentCount > 0) {
                        item("recent") {
                            FolderRow("\u25B6  " + stringResource(R.string.dvr_recent), sub = "$recentCount", iconKey = "recent") { onNav(DvrNav.Recent) }
                        }
                    }
                    item("by_channel") {
                        FolderRow(
                            "\uD83D\uDCFA  " + stringResource(R.string.dvr_by_channel),
                            sub = "$chCount " + stringResource(R.string.dvr_channels_count),
                            iconKey = "channels"
                        ) { onNav(DvrNav.Channels) }
                    }
                    item("cat_hdr") { Header(stringResource(R.string.dvr_by_genre)) }
                    items(cats, key = { it }) { cat ->
                        FolderRow("\uD83D\uDCC1  " + catLabel(cat), sub = "${byCat[cat]?.size ?: 0}", iconKey = cat) {
                            onNav(DvrNav.Category(cat))
                        }
                    }
                }
            } else {
                val cols = if (viewMode == ChannelViewMode.GRID) 2 else 3
                LazyVerticalGrid(columns = GridCells.Fixed(cols), modifier = Modifier.fillMaxSize()) {
                    item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) { Header(stringResource(R.string.dvr_archive)) }
                    if (recentCount > 0) {
                        item(key = "recent", span = { GridItemSpan(maxLineSpan) }) {
                            FolderRow("\u25B6  " + stringResource(R.string.dvr_recent), sub = "$recentCount", iconKey = "recent") { onNav(DvrNav.Recent) }
                        }
                    }
                    item(key = "by_channel", span = { GridItemSpan(maxLineSpan) }) {
                        FolderRow(
                            "\uD83D\uDCFA  " + stringResource(R.string.dvr_by_channel),
                            sub = "$chCount " + stringResource(R.string.dvr_channels_count),
                            iconKey = "channels"
                        ) { onNav(DvrNav.Channels) }
                    }
                    item(key = "cat_hdr", span = { GridItemSpan(maxLineSpan) }) { Header(stringResource(R.string.dvr_by_genre)) }
                    gridItems(cats, key = { it }) { cat ->
                        FolderCard(catLabel(cat), sub = "${byCat[cat]?.size ?: 0}", onClick = { onNav(DvrNav.Category(cat)) })
                    }
                }
            }
        }

        is DvrNav.Recent -> {
            val list = remember(progressTick, entries) {
                if (server == null) emptyList() else {
                    val byUuid = entries.associateBy { it.uuid }
                    WatchProgress.recent(context, server.id).mapNotNull { byUuid[it.first] }
                }
            }
            RecordingList(list, context, progressTick, header = stringResource(R.string.dvr_recent), viewMode = viewMode)
        }

        is DvrNav.Channels -> {
            // Kanaly ktore maju nahravky, zoradene podla cisla kanala (ako v zozname),
            // kanaly bez cisla na koniec podla abecedy.
            val byChannel = remember(entries) { entries.groupBy { it.channelName.ifBlank { "—" } } }
            val channels = remember(byChannel, channelOrder) {
                byChannel.keys.sortedWith(
                    compareBy({ channelOrder[it] ?: Int.MAX_VALUE }, { it.lowercase() })
                )
            }
            if (viewMode == ChannelViewMode.LIST) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item("hdr") { Header(stringResource(R.string.dvr_by_channel)) }
                    items(channels, key = { it }) { ch ->
                        val cnt = byChannel[ch]?.size ?: 0
                        ChannelFolderRow(ch, channelPicons[ch], piconLoader, context, sub = "$cnt") {
                            onNav(DvrNav.Dates(ch))
                        }
                    }
                }
            } else {
                val cols = if (viewMode == ChannelViewMode.GRID) 2 else 3
                LazyVerticalGrid(columns = GridCells.Fixed(cols), modifier = Modifier.fillMaxSize()) {
                    item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) { Header(stringResource(R.string.dvr_by_channel)) }
                    gridItems(channels, key = { it }) { ch ->
                        val cnt = byChannel[ch]?.size ?: 0
                        val picon = channelPicons[ch]
                        FolderCard(ch, sub = "$cnt", onClick = { onNav(DvrNav.Dates(ch)) }, leading = {
                            if (picon != null) {
                                Box(
                                    Modifier.fillMaxSize()
                                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                                        .background(piconBackground()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    coil.compose.AsyncImage(
                                        model = coil.request.ImageRequest.Builder(context).data(picon).build(),
                                        contentDescription = ch,
                                        imageLoader = piconLoader,
                                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                        modifier = Modifier.fillMaxSize().padding(8.dp)
                                    )
                                }
                            } else {
                                Text(ch.take(2).uppercase(), style = MaterialTheme.typography.titleMedium)
                            }
                        })
                    }
                }
            }
        }

        is DvrNav.Dates -> {
            val byDate = remember(entries, nav.channel) {
                entries.filter { it.channelName.ifBlank { "—" } == nav.channel }
                    .groupBy { dateKey(it.start) }
            }
            val dates = remember(byDate) { byDate.keys.sortedDescending() }
            if (viewMode == ChannelViewMode.LIST) {
                LazyColumn(Modifier.fillMaxSize()) {
                    item("hdr") { Header(nav.channel) }
                    items(dates, key = { it }) { dk ->
                        val list = byDate[dk] ?: emptyList()
                        val label = list.firstOrNull()?.let { formatDateFull(it.start) } ?: dk
                        FolderRow("\uD83D\uDCC5  $label", sub = "${list.size}", iconKey = "dates") {
                            onNav(DvrNav.Day(nav.channel, dk))
                        }
                    }
                }
            } else {
                val cols = if (viewMode == ChannelViewMode.GRID) 2 else 3
                LazyVerticalGrid(columns = GridCells.Fixed(cols), modifier = Modifier.fillMaxSize()) {
                    item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) { Header(nav.channel) }
                    gridItems(dates, key = { it }) { dk ->
                        val list = byDate[dk] ?: emptyList()
                        val label = list.firstOrNull()?.let { formatDateFull(it.start) } ?: dk
                        FolderCard(label, sub = "${list.size}", onClick = { onNav(DvrNav.Day(nav.channel, dk)) })
                    }
                }
            }
        }

        is DvrNav.Day -> {
            val list = remember(entries, nav.channel, nav.dateKey) {
                entries
                    .filter { it.channelName.ifBlank { "—" } == nav.channel && dateKey(it.start) == nav.dateKey }
                    .sortedByDescending { it.start }
            }
            RecordingList(list, context, progressTick, header = nav.channel, viewMode = viewMode)
        }

        is DvrNav.Category -> {
            val inCat = byCatAll[nav.catKey].orEmpty()
            if (DvrClassifier.hasSubgenres(nav.catKey)) {
                // Zlozky sub-zanrov ktore maju zaznamy (serialovy konsenzus) - memoizovane
                val bySub = remember(inCat, nav.catKey) {
                    val consensus = DvrClassifier.consensusSubgenres(inCat, nav.catKey)
                    inCat.groupBy { DvrClassifier.subgenreOf(it, nav.catKey, consensus) }
                }
                val order = DvrClassifier.subOrderFor(nav.catKey)
                if (viewMode == ChannelViewMode.LIST) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item("hdr") { Header(catLabel(nav.catKey)) }
                        items(order.filter { bySub.containsKey(it) }, key = { it }) { sub ->
                            FolderRow("\uD83D\uDCC1  " + subLabel(sub), sub = "${bySub[sub]?.size ?: 0}", iconKey = nav.catKey + "|" + sub) {
                                onNav(DvrNav.Subgenre(nav.catKey, sub))
                            }
                        }
                    }
                } else {
                    val cols = if (viewMode == ChannelViewMode.GRID) 2 else 3
                    LazyVerticalGrid(columns = GridCells.Fixed(cols), modifier = Modifier.fillMaxSize()) {
                        item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) { Header(catLabel(nav.catKey)) }
                        gridItems(order.filter { bySub.containsKey(it) }, key = { it }) { sub ->
                            FolderCard(subLabel(sub), sub = "${bySub[sub]?.size ?: 0}", onClick = { onNav(DvrNav.Subgenre(nav.catKey, sub)) })
                        }
                    }
                }
            } else {
                RecordingList(inCat.sortedByDescending { it.start }, context, progressTick, header = catLabel(nav.catKey), viewMode = viewMode)
            }
        }

        is DvrNav.Subgenre -> {
            val catEntries = byCatAll[nav.catKey].orEmpty()
            val inSub = remember(catEntries, nav.catKey, nav.subKey) {
                val consensus = DvrClassifier.consensusSubgenres(catEntries, nav.catKey)
                catEntries.filter {
                    DvrClassifier.subgenreOf(it, nav.catKey, consensus) == nav.subKey
                }
            }
            if (DvrClassifier.isSeriesLike(nav.catKey)) {
                // Zoskup epizody pod serial (canonical title). Vzdy zlozka,
                // aj ked ma serial len jednu epizodu (konzistentne).
                val bySeries = remember(inSub) { inSub.groupBy { DvrClassifier.seriesCanonicalTitle(it.title) } }
                val titles = remember(bySeries) { bySeries.keys.sortedBy { it.lowercase() } }
                if (viewMode == ChannelViewMode.LIST) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        item("hdr") { Header(subLabel(nav.subKey)) }
                        items(titles, key = { it }) { t ->
                            val grp = bySeries[t] ?: emptyList()
                            FolderRow("\uD83D\uDCFA  $t", sub = "${grp.size}", iconKey = "series") {
                                onNav(DvrNav.Series(nav.catKey, nav.subKey, t))
                            }
                        }
                    }
                } else {
                    val cols = if (viewMode == ChannelViewMode.GRID) 2 else 3
                    LazyVerticalGrid(columns = GridCells.Fixed(cols), modifier = Modifier.fillMaxSize()) {
                        item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) { Header(subLabel(nav.subKey)) }
                        gridItems(titles, key = { it }) { t ->
                            val grp = bySeries[t] ?: emptyList()
                            FolderCard(t, sub = "${grp.size}", onClick = { onNav(DvrNav.Series(nav.catKey, nav.subKey, t)) })
                        }
                    }
                }
            } else {
                RecordingList(inSub.sortedByDescending { it.start }, context, progressTick, header = subLabel(nav.subKey), viewMode = viewMode)
            }
        }

        is DvrNav.Series -> {
            val catEntries = byCatAll[nav.catKey].orEmpty()
            val eps = remember(catEntries, nav.catKey, nav.subKey, nav.seriesTitle) {
                val consensus = DvrClassifier.consensusSubgenres(catEntries, nav.catKey)
                catEntries.filter {
                    DvrClassifier.subgenreOf(it, nav.catKey, consensus) == nav.subKey &&
                    DvrClassifier.seriesCanonicalTitle(it.title) == nav.seriesTitle
                }.sortedByDescending { it.start }
            }
            RecordingList(eps, context, progressTick, header = nav.seriesTitle, viewMode = viewMode)
        }
    }
}

@Composable
private fun RecordingList(
    list: List<DvrEntry>,
    context: Context,
    progressTick: Int,
    header: String,
    viewMode: ChannelViewMode = ChannelViewMode.LIST
) {
    when (viewMode) {
        ChannelViewMode.LIST -> LazyColumn(Modifier.fillMaxSize()) {
            item("hdr") { Header(header) }
            items(list, key = { it.uuid }) { entry ->
                RecordingRow(entry, context, progressTick)
            }
        }
        ChannelViewMode.GRID, ChannelViewMode.TILES -> {
            val cols = if (viewMode == ChannelViewMode.GRID) 2 else 3
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols),
                modifier = Modifier.fillMaxSize()
            ) {
                item(key = "hdr", span = { GridItemSpan(maxLineSpan) }) { Header(header) }
                gridItems(list, key = { it.uuid }) { entry ->
                    RecordingCard(entry, context, progressTick)
                }
            }
        }
    }
}

/** Spusti prehravanie DVR nahravky. */
internal fun playDvr(context: Context, entry: DvrEntry) {
    val srv = Tvh.store.active() ?: return
    val url = Tvh.dvrUrl(srv, entry.uuid)
    val intent = Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_URL, url)
        putExtra(PlayerActivity.EXTRA_TITLE, entry.title)
        putExtra(PlayerActivity.EXTRA_DURATION_MS, entry.realLengthSec * 1000)
        putExtra(PlayerActivity.EXTRA_DVR_UUID, entry.uuid)
        putExtra(PlayerActivity.EXTRA_PROG_START_FRAC, entry.programStartFraction)
        putExtra(PlayerActivity.EXTRA_PROG_STOP_FRAC, entry.programStopFraction)
    }
    context.startActivity(intent)
}

@Composable
private fun RecordingCard(entry: DvrEntry, context: Context, progressTick: Int) {
    val server = remember { Tvh.store.active() }
    val info = remember(entry.uuid, progressTick) {
        server?.let { WatchProgress.get(context, it.id, entry.uuid) }
    }
    val modernRec = isModernUi()
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .padding(6.dp)
            .then(
                if (modernRec) Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isLightTheme()) cs.surfaceContainerLowest else cs.surfaceContainer)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
                    .dpadFocusable(RoundedCornerShape(14.dp))
                else Modifier.dpadFocusable()
            )
            .clickable { playDvr(context, entry) }
            .then(if (modernRec) Modifier.padding(6.dp) else Modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(96.dp)
                .clip(RoundedCornerShape(if (modernRec) 10.dp else 8.dp))
                .background(
                    if (modernRec) cs.primaryContainer.copy(alpha = if (isLightTheme()) 0.45f else 0.4f)
                    else androidx.compose.ui.graphics.Color(0x22FFFFFF)
                ),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                Icons.Default.Movie,
                contentDescription = null,
                tint = if (modernRec) cs.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(40.dp)
            )
            if (info?.completed == true) {
                Text(
                    "\u2605",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp)
                )
            }
            if (info != null && !info.completed && info.fraction > 0f) {
                LinearProgressIndicator(
                    progress = { info.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            entry.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        if (entry.channelName.isNotBlank()) {
            Text(
                entry.channelName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

object DvrViewPref {
    private const val KEY = "dvr_view_mode"
    fun get(c: Context): ChannelViewMode {
        val v = c.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return ChannelViewMode.LIST
        return runCatching { ChannelViewMode.valueOf(v) }.getOrDefault(ChannelViewMode.LIST)
    }
    fun set(c: Context, mode: ChannelViewMode) {
        c.getSharedPreferences("app_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}

@Composable
private fun RecordingRow(entry: DvrEntry, context: Context, progressTick: Int) {
    val server = remember { Tvh.store.active() }
    val info = remember(entry.uuid, progressTick) {
        server?.let { WatchProgress.get(context, it.id, entry.uuid) }
    }
    val modernRec = isModernUi()
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (modernRec) Modifier
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isLightTheme()) cs.surfaceContainerLowest else cs.surfaceContainer)
                    .border(1.dp, cs.outlineVariant, RoundedCornerShape(14.dp))
                    .animateContentSize(animationSpec = androidx.compose.animation.core.tween(180))
                    .dpadFocusable(RoundedCornerShape(14.dp))
                else Modifier.dpadFocusable()
            )
            .clickable { playDvr(context, entry) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dopozerane: moderny = teal fajka v cipe, klasik = hviezdicka
        if (info?.completed == true) {
            if (modernRec) {
                Box(
                    Modifier.size(22.dp).clip(androidx.compose.foundation.shape.CircleShape)
                        .background(cs.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Text("\u2713", color = cs.onPrimary,
                        style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
            } else {
                Text("\u2605  ", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                if (entry.channelName.isNotBlank()) append(entry.channelName)
                if (entry.start > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append(formatTimeHm(entry.start))
                }
                val mins = entry.durationSec / 60
                if (mins > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("$mins min")
                }
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Rozpozeranie (nie dopozerane): pozicia + ciara priebehu
            if (info != null && !info.completed && info.posMs > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.dvr_resume_at, fmtPos(info.posMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                if (info.fraction > 0f) {
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { info.fraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
        Text("\u25B6", Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.primary)
    }
}

private fun fmtPos(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:" + m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
    else "$m:" + s.toString().padStart(2, '0')
}

@Composable
private fun Header(text: String) {
    if (isModernUi()) {
        Text(
            text.uppercase(),
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = MaterialTheme.colorScheme.primary
        )
        return
    }
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// --- Moderny archiv (M316): karty priecinkov s farebnymi ikonovymi cipmi ---

/** Farby cipu ikony: pozadie/popredie pre svetly a tmavy rezim. */

@Composable
private fun ChannelFolderRow(
    name: String,
    piconUrl: String?,
    loader: coil.ImageLoader,
    context: Context,
    sub: String,
    onClick: () -> Unit
) {
    if (isModernUi()) {
        ModernFolderRow(name, sub, iconKey = "channels", leading = {
            Box(
                Modifier.size(width = 56.dp, height = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(piconBackground()),
                contentAlignment = Alignment.Center
            ) {
                if (piconUrl != null) {
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(context).data(piconUrl).build(),
                        contentDescription = name,
                        imageLoader = loader,
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize().padding(3.dp)
                    )
                } else {
                    Text(name.take(2).uppercase(), style = MaterialTheme.typography.labelSmall)
                }
            }
        }, onClick = onClick)
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .dpadFocusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 40.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .background(piconBackground()),
            contentAlignment = Alignment.Center
        ) {
            if (piconUrl != null) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(piconUrl).build(),
                    contentDescription = name,
                    imageLoader = loader,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
            } else {
                Text(name.take(2).uppercase(), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  \u203A", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderRow(label: String, sub: String, iconKey: String = "folder", onClick: () -> Unit) {
    if (isModernUi()) {
        // Emoji prefix (📁/📺/▶/📅) v modernom rezime nahradza ikonovy cip
        val clean = label.trimStart { !it.isLetterOrDigit() }
        ModernFolderRow(clean, sub, iconKey, onClick = onClick)
        return
    }
    Row(
        Modifier
            .fillMaxWidth()
            .dpadFocusable()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  \u203A", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Priecinok ako dlazdica (mriezka/dlazdice) — zachovava nazov aj pocet. */
@Composable
private fun FolderCard(
    label: String,
    sub: String,
    onClick: () -> Unit,
    leading: (@Composable () -> Unit)? = null
) {
    Column(
        Modifier.fillMaxWidth().padding(6.dp).dpadFocusable().clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(72.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .background(androidx.compose.ui.graphics.Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            if (leading != null) leading()
            else Text("\uD83D\uDCC1", style = MaterialTheme.typography.headlineMedium)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label, style = MaterialTheme.typography.bodySmall, maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Text(
            sub, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun subLabel(key: String): String {
    val resId = when (key) {
        DvrClassifier.MV_AKCNY -> R.string.sub_mv_akcny
        DvrClassifier.MV_KOMEDIA -> R.string.sub_mv_komedia
        DvrClassifier.MV_KRIMI -> R.string.sub_mv_krimi
        DvrClassifier.MV_DRAMA -> R.string.sub_mv_drama
        DvrClassifier.MV_SCIFI -> R.string.sub_mv_scifi
        DvrClassifier.MV_ROMANTIKA -> R.string.sub_mv_romantika
        DvrClassifier.MV_HOROR -> R.string.sub_mv_horor
        DvrClassifier.MV_DOBRODR -> R.string.sub_mv_dobrodruzny
        DvrClassifier.MV_ANIMAK -> R.string.sub_mv_animovany
        DvrClassifier.MV_HISTORICKY -> R.string.sub_mv_historicky
        DvrClassifier.MV_WESTERN -> R.string.sub_mv_western
        DvrClassifier.MV_INE -> R.string.sub_mv_ine
        DvrClassifier.SP_FUTBAL -> R.string.sub_sp_futbal
        DvrClassifier.SP_HOKEJ -> R.string.sub_sp_hokej
        DvrClassifier.SP_BASKETBAL -> R.string.sub_sp_basketbal
        DvrClassifier.SP_TENIS -> R.string.sub_sp_tenis
        DvrClassifier.SP_VOLEJBAL -> R.string.sub_sp_volejbal
        DvrClassifier.SP_HADZANA -> R.string.sub_sp_hadzana
        DvrClassifier.SP_BOJOVE -> R.string.sub_sp_bojove
        DvrClassifier.SP_ATLETIKA -> R.string.sub_sp_atletika
        DvrClassifier.SP_CYKLISTIKA -> R.string.sub_sp_cyklistika
        DvrClassifier.SP_MOTORSPORT -> R.string.sub_sp_motorsport
        DvrClassifier.SP_ZIMNE -> R.string.sub_sp_zimne
        DvrClassifier.SP_VODNE -> R.string.sub_sp_vodne
        DvrClassifier.SP_NEWS -> R.string.sub_sp_news
        DvrClassifier.NW_HLAVNE -> R.string.sub_nw_hlavne
        DvrClassifier.NW_POLITIKA -> R.string.sub_nw_politika
        DvrClassifier.NW_KRIMI -> R.string.sub_nw_krimi
        DvrClassifier.NW_MAGAZINY -> R.string.sub_nw_magaziny
        DvrClassifier.NW_POCASIE -> R.string.sub_nw_pocasie
        DvrClassifier.NW_INE -> R.string.sub_nw_ine
        DvrClassifier.SH_REALITY -> R.string.sub_sh_reality
        DvrClassifier.SH_TALK -> R.string.sub_sh_talk
        DvrClassifier.SH_SUTAZ -> R.string.sub_sh_sutaz
        DvrClassifier.SH_KUCHARSKE -> R.string.sub_sh_kucharske
        DvrClassifier.SH_ZABAVA -> R.string.sub_sh_zabava
        DvrClassifier.SH_MAGAZINY -> R.string.sub_sh_magaziny
        DvrClassifier.SH_INE -> R.string.sub_sh_ine
        DvrClassifier.CH_ANIMAK -> R.string.sub_ch_animak
        DvrClassifier.CH_ROZPRAVKY -> R.string.sub_ch_rozpravky
        DvrClassifier.CH_VZDELAVAC -> R.string.sub_ch_vzdelavac
        DvrClassifier.CH_FILMY -> R.string.sub_ch_filmy
        DvrClassifier.CH_INE -> R.string.sub_ch_ine
        DvrClassifier.MU_KLASIKA -> R.string.sub_mu_klasika
        DvrClassifier.MU_KONCERT -> R.string.sub_mu_koncert
        DvrClassifier.MU_HITY -> R.string.sub_mu_hity
        DvrClassifier.MU_FOLK -> R.string.sub_mu_folk
        DvrClassifier.MU_MAGAZINY -> R.string.sub_mu_magaziny
        DvrClassifier.MU_INE -> R.string.sub_mu_ine
        DvrClassifier.AR_DIVADLO -> R.string.sub_ar_divadlo
        DvrClassifier.AR_VYTVARNE -> R.string.sub_ar_vytvarne
        DvrClassifier.AR_LITERATURA -> R.string.sub_ar_literatura
        DvrClassifier.AR_FILM -> R.string.sub_ar_film
        DvrClassifier.AR_INE -> R.string.sub_ar_ine
        DvrClassifier.DC_PRIRODA -> R.string.sub_dc_priroda
        DvrClassifier.DC_HISTORIA -> R.string.sub_dc_historia
        DvrClassifier.DC_VEDA -> R.string.sub_dc_veda
        DvrClassifier.DC_CESTOPIS -> R.string.sub_dc_cestopis
        DvrClassifier.DC_OSOBNOSTI -> R.string.sub_dc_osobnosti
        DvrClassifier.DC_SPOLOCNOST -> R.string.sub_dc_spolocnost
        DvrClassifier.DC_INE -> R.string.sub_dc_ine
        DvrClassifier.HB_ZAHRADA -> R.string.sub_hb_zahrada
        DvrClassifier.HB_BYVANIE -> R.string.sub_hb_byvanie
        DvrClassifier.HB_VARENIE -> R.string.sub_hb_varenie
        DvrClassifier.HB_AUTO -> R.string.sub_hb_auto
        DvrClassifier.HB_CESTOVANIE -> R.string.sub_hb_cestovanie
        DvrClassifier.HB_ZDRAVIE -> R.string.sub_hb_zdravie
        DvrClassifier.HB_DIY -> R.string.sub_hb_diy
        DvrClassifier.HB_INE -> R.string.sub_hb_ine
        else -> R.string.sub_mv_ine
    }
    return stringResource(resId)
}

@Composable
/** TV/box Archiv: lavy panel (Hladat, Podla kanalu, Vsetko + zanre) + mriezka relacii + popis. */
fun TvArchiveScreen(vm: DvrViewModel = viewModel(), onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val server = remember { Tvh.store.active() }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }
    LaunchedEffect(Unit) { vm.loadIfNeeded() }
    LaunchedEffect(Unit) {
        if (!DvrClassifier.hasCorpus())
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadCorpusFromAssets(context) }
    }

    val loaded = state as? DvrState.Loaded
    if (loaded == null) {
        BackHandler { onBack() }
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }
    val entries = loaded.entries
    val byCat = remember(entries) { entries.groupBy { DvrClassifier.classify(it) } }
    val cats = remember(byCat) { DvrClassifier.order.filter { byCat.containsKey(it) } }

    var selKey by remember { mutableStateOf("all") }
    var selChannel by remember { mutableStateOf<String?>(null) }
    var selChannelDate by remember { mutableStateOf<String?>(null) }
    var selSub by remember { mutableStateOf<String?>(null) }
    var selSeries by remember { mutableStateOf<String?>(null) }
    fun openSection(key: String) { selKey = key; selChannel = null; selChannelDate = null; selSub = null; selSeries = null }
    var query by remember { mutableStateOf("") }
    val searchFocus = remember { FocusRequester() }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var progressTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) progressTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(selKey) { selChannel = null; selChannelDate = null; selSub = null; selSeries = null }
    BackHandler {
        when {
            selSeries != null -> selSeries = null
            selChannelDate != null -> selChannelDate = null
            selSub != null -> selSub = null
            selChannel != null -> selChannel = null
            else -> onBack()
        }
    }

    val channels = remember(entries) {
        entries.map { it.channelName }.filter { it.isNotBlank() }.distinct()
            .sortedBy { loaded.channelOrder[it] ?: Int.MAX_VALUE }
    }
    val recent = remember(progressTick, entries) {
        val sid = server?.id
        if (sid == null) emptyList()
        else {
            val byUuid = entries.associateBy { it.uuid }
            WatchProgress.recent(context, sid).mapNotNull { byUuid[it.first] }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Text(
            stringResource(R.string.dvr_archive).uppercase(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )
        Row(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxHeight().weight(0.26f)
                    .background(
                        if (isModernUi()) {
                            if (isLightTheme()) MaterialTheme.colorScheme.surfaceContainerLow
                            else MaterialTheme.colorScheme.surfaceContainerLowest
                        } else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                    )
                    .padding(vertical = 8.dp)
            ) {
                item("_recent") { ArcRailItem(stringResource(R.string.dvr_recent), recent.size, selKey == "_recent", iconKey = "recent") { openSection("_recent") } }
                item("_search") { ArcRailItem(stringResource(R.string.dvr_search), null, selKey == "_search", iconKey = "search") { openSection("_search") } }
                item("_channels") { ArcRailItem(stringResource(R.string.dvr_by_channel), null, selKey == "_channels", iconKey = "channels") { openSection("_channels") } }
                item("all") { ArcRailItem(stringResource(R.string.dvr_all), entries.size, selKey == "all", iconKey = "all") { openSection("all") } }
                items(cats, key = { it }) { c -> ArcRailItem(catLabel(c), byCat[c]?.size ?: 0, selKey == c, iconKey = c) { openSection(c) } }
            }
            Column(Modifier.weight(0.74f).fillMaxHeight()) {
                when {
                    selKey == "_recent" -> {
                        ArcFolderHeader(stringResource(R.string.dvr_recent))
                        ArcRecGrid(recent, loaded, loader, context, progressTick)
                    }
                    selKey == "_search" -> {
                        TvSearchBar(query, stringResource(R.string.dvr_search), { query = it }, searchFocus,
                            Modifier.fillMaxWidth().padding(8.dp))
                        val q = normalizeSearch(query.trim())
                        val res = if (q.isBlank()) emptyList()
                            else entries.filter { normalizeSearch(it.dispTitle).contains(q) || normalizeSearch(it.channelName).contains(q) }
                                .sortedByDescending { it.start }
                        ArcRecGrid(res, loaded, loader, context, progressTick)
                    }
                    selKey == "_channels" && selChannel == null -> {
                        ArcFolderHeader(stringResource(R.string.dvr_by_channel))
                        ArcChannelGrid(channels, entries, loaded, loader, context) { selChannel = it }
                    }
                    selKey == "_channels" && selChannelDate == null -> {
                        val chEnt = entries.filter { it.channelName == selChannel }
                        val byDate = chEnt.groupBy { dateKey(it.start) }
                        val dks = byDate.keys.sortedDescending()
                        ArcFolderHeader(selChannel ?: "")
                        ArcFolderGrid(dks.map { dk -> Triple(dk, byDate[dk]?.firstOrNull()?.let { formatDateFull(it.start) } ?: dk, byDate[dk]?.size ?: 0) }, iconKeyFor = { "dates" }) { selChannelDate = it }
                    }
                    selKey == "_channels" -> {
                        val list = entries.filter { it.channelName == selChannel && dateKey(it.start) == selChannelDate }
                            .sortedByDescending { it.start }
                        ArcRecGrid(list, loaded, loader, context, progressTick)
                    }
                    selKey == "all" -> {
                        ArcRecGrid(entries.sortedByDescending { it.start }, loaded, loader, context, progressTick)
                    }
                    else -> {
                        val cat = selKey
                        val inCat = byCat[cat] ?: emptyList()
                        val hasSub = DvrClassifier.hasSubgenres(cat)
                        val seriesLike = DvrClassifier.isSeriesLike(cat)
                        val consensus = if (hasSub) DvrClassifier.consensusSubgenres(inCat, cat) else emptyMap()
                        when {
                            hasSub && selSub == null -> {
                                val bySub = inCat.groupBy { DvrClassifier.subgenreOf(it, cat, consensus) }
                                val order = DvrClassifier.subOrderFor(cat).filter { bySub.containsKey(it) }
                                ArcFolderHeader(catLabel(cat))
                                ArcFolderGrid(order.map { sb -> Triple(sb, subLabel(sb), bySub[sb]?.size ?: 0) }, iconKeyFor = { sb -> cat + "|" + sb }) { selSub = it }
                            }
                            else -> {
                                val scope = if (hasSub) inCat.filter { DvrClassifier.subgenreOf(it, cat, consensus) == selSub } else inCat
                                if (seriesLike && selSeries == null) {
                                    val bySeries = scope.groupBy { DvrClassifier.seriesCanonicalTitle(it.title) }
                                    val titles = bySeries.keys.sortedBy { it.lowercase() }
                                    ArcFolderHeader(if (hasSub) subLabel(selSub ?: "") else catLabel(cat))
                                    ArcFolderGrid(titles.map { t -> Triple(t, t, bySeries[t]?.size ?: 0) }, glyph = "\uD83D\uDCFA", iconKeyFor = { "series" }) { selSeries = it }
                                } else if (seriesLike) {
                                    val eps = scope.filter { DvrClassifier.seriesCanonicalTitle(it.title) == selSeries }
                                        .sortedByDescending { it.start }
                                    ArcRecGrid(eps, loaded, loader, context, progressTick)
                                } else {
                                    ArcRecGrid(scope.sortedByDescending { it.start }, loaded, loader, context, progressTick)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ArcRecGrid(list: List<DvrEntry>, loaded: DvrState.Loaded, loader: coil.ImageLoader, context: Context, progressTick: Int) {
    var focused by remember { mutableStateOf<DvrEntry?>(null) }
    var infoEntry by remember { mutableStateOf<DvrEntry?>(null) }
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
        gridItems(list, key = { it.uuid }) { e ->
            ArcRecCard(e, loaded.channelPicons[e.channelName], loader, context, progressTick,
                onFocus = { focused = e }, onClick = { playDvr(context, e) }, onLong = { infoEntry = e })
        }
    }
    val f = if (focused != null && list.contains(focused)) focused else list.firstOrNull()
    f?.let { ArcDetail(it) }
    infoEntry?.let { ent -> ArcInfoDialog(ent) { infoEntry = null } }
}

@Composable
private fun ColumnScope.ArcChannelGrid(channels: List<String>, entries: List<DvrEntry>, loaded: DvrState.Loaded, loader: coil.ImageLoader, context: Context, onClick: (String) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
        gridItems(channels, key = { it }) { ch ->
            ArcChannelCard(ch, loaded.channelPicons[ch], entries.count { it.channelName == ch }, loader, context) { onClick(ch) }
        }
    }
}

@Composable
private fun ColumnScope.ArcFolderGrid(
    items: List<Triple<String, String, Int>>,
    glyph: String = "\uD83D\uDCC1",
    iconKeyFor: ((String) -> String)? = null,
    onClick: (String) -> Unit
) {
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
        gridItems(items, key = { it.first }) { tr ->
            ArcFolderCard(glyph, tr.second, tr.third, iconKey = iconKeyFor?.invoke(tr.first)) { onClick(tr.first) }
        }
    }
}

/** Karta priecinka moderneho archivu: farebny ikonovy cip + tucny nazov + badge poctu + sipka. */
@Composable
private fun ModernFolderRow(
    label: String,
    sub: String,
    iconKey: String,
    leading: (@Composable () -> Unit)? = null,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val light = isLightTheme()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (light) cs.surfaceContainerLowest else cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(18.dp))
            .dpadFocusable(RoundedCornerShape(18.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
        } else {
            val chip = mgChipFor(iconKey)
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(12.dp))
                    .background(androidx.compose.ui.graphics.Color(if (light) chip.bgL else chip.bgD)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    mgIconFor(iconKey), contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(if (light) chip.fgL else chip.fgD),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Text(
            label, Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier.clip(RoundedCornerShape(14.dp))
                .background(if (light) cs.surfaceContainer else cs.surfaceContainerHigh)
                .padding(horizontal = 10.dp, vertical = 4.dp)
        ) {
            Text(
                sub,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = cs.onSurfaceVariant,
                maxLines = 1
            )
        }
        Text(
            "  \u203A",
            style = MaterialTheme.typography.titleMedium,
            color = cs.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Composable
private fun ArcFolderHeader(text: String) {
    if (isModernUi()) {
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
        return
    }
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
}

@Composable
private fun ArcFolderCard(glyph: String, label: String, count: Int, iconKey: String? = null, onClick: () -> Unit) {
    if (isModernUi() && iconKey != null) {
        // Moderna dlazdica (M321): cip vlavo hore, tucny nazov, badge pocet
        val cs = MaterialTheme.colorScheme
        val light = isLightTheme()
        val chip = mgChipFor(iconKey)
        Column(
            Modifier.padding(6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (light) cs.surfaceContainerLowest else cs.surfaceContainer)
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(16.dp))
                .dpadFocusable(RoundedCornerShape(16.dp))
                .clickable { onClick() }
                .padding(12.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.Start
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(11.dp))
                    .background(androidx.compose.ui.graphics.Color(if (light) chip.bgL else chip.bgD)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    mgIconFor(iconKey), contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(if (light) chip.fgL else chip.fgD),
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(label, color = cs.onSurface, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(10.dp))
                    .background(if (light) cs.surfaceContainer else cs.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text("$count", color = cs.onSurfaceVariant,
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
            }
        }
        return
    }
    Column(
        Modifier.padding(6.dp).dpadFocusable(RoundedCornerShape(10.dp)).clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .padding(12.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(glyph, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ArcInfoDialog(e: DvrEntry, onDismiss: () -> Unit) {
    val desc = e.dispDescription.ifBlank { e.dispSubtitle }
    // Zavri az po novom celom stlaceni OK (cerstve DOWN repeatCount==0). Chvost dlheho OK (uvolnenie) sa ignoruje.
    var sawFreshDown by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth()
                .focusRequester(fr)
                .focusable()
                .onKeyEvent { ev ->
                    val k = ev.nativeKeyEvent
                    val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        k.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        k.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (!ok) return@onKeyEvent false
                    when (k.action) {
                        android.view.KeyEvent.ACTION_DOWN -> { if (k.repeatCount == 0) sawFreshDown = true; true }
                        android.view.KeyEvent.ACTION_UP -> { if (sawFreshDown) onDismiss(); true }
                        else -> false
                    }
                }
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(e.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(e.channelName + "  \u00B7  " + formatDateFull(e.start) + "  \u00B7  " + formatTimeHm(e.start),
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                    Text(if (desc.isNotBlank()) desc else "\u2014", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface)
                }
                Spacer(Modifier.height(14.dp))
                Text(stringResource(R.string.close) + "  (OK)", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
}

@Composable
private fun ArcRailItem(label: String, count: Int?, selected: Boolean, iconKey: String? = null, onClick: () -> Unit) {
    if (isModernUi() && iconKey != null) {
        // Moderny rail (M321): karta s farebnym ikonovym cipom a badge poctom
        val cs = MaterialTheme.colorScheme
        val light = isLightTheme()
        val chip = mgChipFor(iconKey)
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(
                    if (selected) cs.primaryContainer.copy(alpha = if (light) 0.45f else 0.5f)
                    else if (light) cs.surfaceContainerLowest else cs.surfaceContainer
                )
                .border(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) cs.primary else cs.outlineVariant,
                    RoundedCornerShape(13.dp)
                )
                .dpadFocusable(RoundedCornerShape(13.dp))
                .clickable { onClick() }
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(32.dp).clip(RoundedCornerShape(9.dp))
                    .background(androidx.compose.ui.graphics.Color(if (light) chip.bgL else chip.bgD)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    mgIconFor(iconKey), contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(if (light) chip.fgL else chip.fgD),
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(label, Modifier.weight(1f),
                color = if (selected) cs.primary else cs.onSurface,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(11.dp))
                        .background(if (light) cs.surfaceContainer else cs.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("$count", color = cs.onSurfaceVariant,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().dpadFocusable(RoundedCornerShape(8.dp)).clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f),
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ArcPicon(picon: String?, fallback: String, loader: coil.ImageLoader, context: Context) {
    Box(
        Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center
    ) {
        if (picon != null) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(context).data(picon).build(),
                contentDescription = null, imageLoader = loader,
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                modifier = Modifier.fillMaxSize().padding(8.dp)
            )
        } else {
            Text(fallback.take(3).uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun ArcRecCard(e: DvrEntry, picon: String?, loader: coil.ImageLoader, context: Context, progressTick: Int,
                       onFocus: () -> Unit, onClick: () -> Unit, onLong: () -> Unit) {
    val server = remember { Tvh.store.active() }
    val info = remember(e.uuid, progressTick) { server?.let { WatchProgress.get(context, it.id, e.uuid) } }
    val seen = info?.completed == true
    var longFired by remember { mutableStateOf(false) }
    Column(
        Modifier.padding(6.dp).onFocusChanged { if (it.isFocused) onFocus() }
            .dpadFocusable(RoundedCornerShape(10.dp))
            .onPreviewKeyEvent { ev ->
                val k = ev.nativeKeyEvent
                val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                    k.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                    k.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!ok) return@onPreviewKeyEvent false
                when (k.action) {
                    android.view.KeyEvent.ACTION_DOWN -> when (k.repeatCount) {
                        0 -> { longFired = false; false }          // nechaj clickable trackovat kratky klik
                        1 -> { longFired = true; onLong(); true }   // dlhe OK -> info, pohlt
                        else -> true
                    }
                    android.view.KeyEvent.ACTION_UP -> if (longFired) { longFired = false; true } else false
                    else -> false
                }
            }
            .clickable { onClick() }.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.fillMaxWidth()) {
            ArcPicon(picon, e.channelName, loader, context)
            if (seen) {
                Box(Modifier.matchParentSize().clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color(0x99000000)))
                androidx.compose.material3.Icon(
                    Icons.Default.CheckCircle, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center).size(34.dp)
                )
            }
            if (info != null && !seen && info.fraction > 0f) {
                LinearProgressIndicator(
                    progress = { info.fraction },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(e.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(formatDateFull(e.start) + "  \u00B7  " + formatTimeHm(e.start),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall, maxLines = 1,
            overflow = TextOverflow.Ellipsis)   // M425: dlhsi 12-hodinovy cas
    }
}

@Composable
private fun ArcChannelCard(name: String, picon: String?, count: Int, loader: coil.ImageLoader, context: Context, onClick: () -> Unit) {
    Column(
        Modifier.padding(6.dp).dpadFocusable(RoundedCornerShape(10.dp)).clickable { onClick() }.padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ArcPicon(picon, name, loader, context)
        Spacer(Modifier.height(6.dp))
        Text(name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("$count", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ArcDetail(f: DvrEntry) {
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Text(f.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(f.channelName + "  \u00B7  " + formatDateFull(f.start) + "  \u00B7  " + formatTimeHm(f.start),
            color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        val desc = f.dispDescription.ifBlank { f.dispSubtitle }
        if (desc.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(desc, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}


@Composable
private fun catLabel(key: String): String {
    val resId = when (key) {
        DvrClassifier.FILM -> R.string.cat_film
        DvrClassifier.SERIAL -> R.string.cat_serial
        DvrClassifier.SPORT -> R.string.cat_sport
        DvrClassifier.NEWS -> R.string.cat_news
        DvrClassifier.SHOW -> R.string.cat_show
        DvrClassifier.CHILDREN -> R.string.cat_children
        DvrClassifier.MUSIC -> R.string.cat_music
        DvrClassifier.ARTS -> R.string.cat_arts
        DvrClassifier.DOCUMENTARY -> R.string.cat_documentary
        DvrClassifier.HOBBY -> R.string.cat_hobby
        else -> R.string.cat_other
    }
    return stringResource(resId)
}

private fun normalizeSearch(s: String): String {
    val sb = StringBuilder(s.length)
    for (c in s.lowercase()) {
        sb.append(
            when (c) {
                'á','ä','à','â' -> 'a'; 'č','ç' -> 'c'; 'ď' -> 'd'
                'é','ě','è','ê' -> 'e'; 'í','ì','î' -> 'i'; 'ĺ','ľ' -> 'l'
                'ň' -> 'n'; 'ó','ô','ö','ò' -> 'o'; 'ŕ','ř' -> 'r'
                'š','ś' -> 's'; 'ť' -> 't'; 'ú','ů','ü','ù' -> 'u'
                'ý' -> 'y'; 'ž','ź','ż' -> 'z'
                else -> c
            }
        )
    }
    return sb.toString()
}

/** Nacita korpus titulov z assetu a vlozi do DvrClassifier. Volat raz, na IO. */
private fun loadCorpusFromAssets(context: Context) {
    if (DvrClassifier.hasCorpus()) return
    try {
        val json = context.assets.open("title_genre_corpus.json")
            .bufferedReader().use { it.readText() }
        val titles = org.json.JSONObject(json).getJSONObject("titles")
        val code2cat = mapOf(
            "ak" to "mv_akcny", "ko" to "mv_komedia", "kr" to "mv_krimi",
            "dr" to "mv_drama", "sf" to "mv_scifi", "ro" to "mv_romantika",
            "ho" to "mv_horor", "do" to "mv_dobrodruzny", "an" to "mv_animovany",
            "hi" to "mv_historicky", "we" to "mv_western"
        )
        val map = HashMap<String, String>(titles.length() * 2)
        val keys = titles.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            code2cat[titles.getString(k)]?.let { map[k] = it }
        }
        DvrClassifier.setCorpus(map)
    } catch (_: Exception) {
    }
}

/** Nacita IMDb cache z filesDir/imdb_cache.json do ImdbLookup. */
private fun loadImdbCache(context: Context) {
    try {
        val f = java.io.File(context.filesDir, "imdb_cache.json")
        if (f.exists()) ImdbLookup.importJson(f.readText())
    } catch (_: Exception) {
    }
}

/** Ulozi IMDb cache na disk. */
private fun saveImdbCache(context: Context) {
    try {
        java.io.File(context.filesDir, "imdb_cache.json").writeText(ImdbLookup.exportJson())
    } catch (_: Exception) {
    }
}
