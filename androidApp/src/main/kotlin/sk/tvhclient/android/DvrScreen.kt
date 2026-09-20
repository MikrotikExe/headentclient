package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.dateKey
import sk.tvhclient.shared.formatDateFull
import sk.tvhclient.shared.formatTimeHm
import sk.tvhclient.shared.model.DvrClassifier
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.ImdbLookup
import androidx.compose.foundation.lazy.grid.items as gridItems

// Navigation in the archive (read-only folders)
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

/**
 * M483: a request to delete a recording from the archive.
 *
 * Both the row and the card are rendered by functions called from dozens of places, so pushing
 * a callback all the way down would mean changing every call. The request therefore goes through
 * this small shared state and the dialog is rendered by the screen that has the
 * ViewModel at hand for refreshing the list.
 */
internal object DvrDeleteRequest {
    /** The entry awaiting confirmation; null = the dialog is not shown. */
    var pending by mutableStateOf<DvrEntry?>(null)
    /** Does the user have the right to record/delete? Determined once when the archive is opened. */
    var allowed by mutableStateOf(false)

    fun ask(entry: DvrEntry) { if (allowed) pending = entry }
}

/**
 * M483: confirming and performing the deletion (phone and TV archive alike).
 *
 * Deleting is irreversible — the server deletes the file too — so it always asks. After success
 * the list is loaded again, so that the recording disappears from the TV guide grid as well.
 */
@Composable
internal fun DvrDeleteDialog(vm: DvrViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val srv = Tvh.store.active()
        DvrDeleteRequest.allowed = srv != null && DvrController.access(srv).canRecord
    }
    val entry = DvrDeleteRequest.pending ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { if (!busy) DvrDeleteRequest.pending = null },
        title = { Text(stringResource(R.string.dvr_del_title)) },
        text = { Text(stringResource(R.string.dvr_del_msg, entry.title)) },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = !busy,
                onClick = {
                    val srv = Tvh.store.active() ?: return@TextButton
                    busy = true
                    scope.launch {
                        val r = DvrController.delete(srv, entry)
                        busy = false
                        DvrDeleteRequest.pending = null
                        android.widget.Toast.makeText(
                            context,
                            if (r.success) context.getString(R.string.dvr_del_done)
                            else r.error ?: context.getString(
                                if (r.timeout) R.string.err_timeout else R.string.dvr_del_failed
                            ),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                        if (r.success) vm.refresh()
                    }
                }
            ) {
                Text(stringResource(
                    if (busy) R.string.dvr_del_working else R.string.delete
                ))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                enabled = !busy,
                onClick = { DvrDeleteRequest.pending = null }
            ) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
fun DvrScreen(vm: DvrViewModel = viewModel(), resetSignal: Int = 0) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    // M589: a refresh keeps the old data, so with an unchanged archive nothing was visible —
    // the button turns into a spinner during the load and failure is reported by a message
    val refreshing by vm.refreshing.collectAsState()
    val refreshFailed by vm.refreshFailed.collectAsState()
    var seenRefreshFail by remember { mutableStateOf(refreshFailed) }
    LaunchedEffect(refreshFailed) {
        if (refreshFailed != seenRefreshFail) {
            seenRefreshFail = refreshFailed
            android.widget.Toast.makeText(
                context, context.getString(R.string.load_error), android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }
    var nav by remember { mutableStateOf<DvrNav>(DvrNav.Root) }
    var search by remember { mutableStateOf("") }
    var viewMode by remember { mutableStateOf(DvrViewPref.get(context)) }
    var viewMenu by remember { mutableStateOf(false) }
    // A click on the Archive tab (even an already selected one) returns to the start (root + search cancelled)
    LaunchedEffect(resetSignal) {
        nav = DvrNav.Root
        search = ""
    }
    // The corpus of titles for sub-genres (loaded once from an asset)
    var corpusReady by remember { mutableStateOf(DvrClassifier.hasCorpus()) }
    LaunchedEffect(Unit) {
        if (!DvrClassifier.hasCorpus()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { loadCorpusFromAssets(context) }
            corpusReady = true
        }
    }

    // After returning from the player, refresh the watch flags (star/position)
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

    // IMDb online lookup: load the cache from disk + fill in uncached
    // films/series in the background (Slovak/Czech titles the corpus does not know).
    var imdbTick by remember { mutableStateOf(0) }
    LaunchedEffect(state) {
        val s = state
        if (s !is DvrState.Loaded) return@LaunchedEffect
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            loadImdbCache(context)
        }
        imdbTick++
        // the titles of films/series that need looking up
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
                    imdbTick++  // incremental redraw
                }
                kotlinx.coroutines.delay(1100)  // IMDb rate-limit
            }
        }
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { saveImdbCache(context) }
        imdbTick++
    }

    // Back: if we are searching, cancel the search; otherwise, if we are not at the root, go up
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

    DvrDeleteDialog(vm)   // M483: deleting a recording (long press on an item)

    Column(Modifier.fillMaxSize()) {
        // Searching recordings (across all of them, by title) — the keyboard only after OK
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
            androidx.compose.material3.IconButton(
                onClick = { vm.refresh() },
                enabled = !refreshing   // M589
            ) {
                if (refreshing) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(22.dp), strokeWidth = 2.dp
                    )
                } else {
                    androidx.compose.material3.Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retry),
                        tint = MaterialTheme.colorScheme.onSurface)
                }
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
        // After a load / level change, put focus on the content (the first folder), so that
        // entering the archive does not select the search and pop up the keyboard.
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
                is DvrState.Error -> ErrorStatus(   // M491: empty message -> translation
                    s.message.ifBlank { stringResource(R.string.load_error) },
                    onRetry = { vm.load() })
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
    // Classification is expensive (stripping diacritics + regexes over title/subtitle/description/channel).
    // Classify each recording ONCE per load (memoised by entries), not on
    // every recomposition/navigation - with ~7000 recordings it otherwise stuttered on every click.
    val catOf = remember(entries) { entries.associateWith { DvrClassifier.classify(it) } }
    val byCatAll = remember(entries) { entries.groupBy { catOf.getValue(it) } }
    when (nav) {
        is DvrNav.Root -> {
            // Folders: Recently watched + By channel + categories
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
            // Channels that have recordings, sorted by channel number (as in the list),
            // channels without a number at the end, alphabetically.
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
                // Sub-genre folders that have entries (series consensus) - memoised
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
                // Group episodes under a series (canonical title). Always a folder,
                // even if the series has only one episode (for consistency).
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

private fun isMediaPlayKey(code: Int): Boolean = when (code) {
    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> true
    else -> false
}

private fun handleMediaPlayKey(k: android.view.KeyEvent, onPlay: () -> Unit): Boolean = when (k.action) {
    android.view.KeyEvent.ACTION_DOWN -> {
        if (k.repeatCount == 0) {
            onPlay()
            true
        } else true
    }
    android.view.KeyEvent.ACTION_UP -> true
    else -> false
}

private fun Modifier.playOnMediaKey(onPlay: () -> Unit): Modifier = this.onPreviewKeyEvent { ev ->
    val k = ev.nativeKeyEvent
    if (!isMediaPlayKey(k.keyCode)) return@onPreviewKeyEvent false
    handleMediaPlayKey(k, onPlay)
}

/** Starts playback of a DVR recording. */
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
            .playOnMediaKey { playDvr(context, entry) }
            // M483: long press = offer to delete the recording
            .combinedClickable(
                onClick = { playDvr(context, entry) },
                onLongClick = { DvrDeleteRequest.ask(entry) }
            )
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
            .playOnMediaKey { playDvr(context, entry) }
            // M483: long press = offer to delete the recording
            .combinedClickable(
                onClick = { playDvr(context, entry) },
                onLongClick = { DvrDeleteRequest.ask(entry) }
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Watched to the end: modern = a teal tick in the chip, classic = a star
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
            // Partly watched (not watched to the end): position + progress line
            if (info != null && !info.completed && info.posMs > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.dvr_resume_at, fmtMs(info.posMs)),   // M679: UiTime.kt
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

// --- Modern archive (M316): folder cards with coloured icon chips ---

/** Icon chip colours: background/foreground for light and dark mode. */

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
        // The emoji prefix (📁/📺/▶/📅) is replaced by the icon chip in modern mode
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

/** A folder as a tile (grid/tiles) — keeps both the name and the count. */
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
        DvrClassifier.MV_ACTION -> R.string.sub_mv_action
        DvrClassifier.MV_COMEDY -> R.string.sub_mv_comedy
        DvrClassifier.MV_CRIME -> R.string.sub_mv_crime
        DvrClassifier.MV_DRAMA -> R.string.sub_mv_drama
        DvrClassifier.MV_SCIFI -> R.string.sub_mv_scifi
        DvrClassifier.MV_ROMANCE -> R.string.sub_mv_romance
        DvrClassifier.MV_HORROR -> R.string.sub_mv_horror
        DvrClassifier.MV_ADVENTURE -> R.string.sub_mv_adventure
        DvrClassifier.MV_ANIMATED -> R.string.sub_mv_animated
        DvrClassifier.MV_HISTORICAL -> R.string.sub_mv_historical
        DvrClassifier.MV_WESTERN -> R.string.sub_mv_western
        DvrClassifier.MV_OTHER -> R.string.sub_mv_other
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
        DvrClassifier.NW_CRIME -> R.string.sub_nw_crime
        DvrClassifier.NW_MAGAZINY -> R.string.sub_nw_magaziny
        DvrClassifier.NW_POCASIE -> R.string.sub_nw_pocasie
        DvrClassifier.NW_OTHER -> R.string.sub_nw_other
        DvrClassifier.SH_REALITY -> R.string.sub_sh_reality
        DvrClassifier.SH_TALK -> R.string.sub_sh_talk
        DvrClassifier.SH_SUTAZ -> R.string.sub_sh_sutaz
        DvrClassifier.SH_KUCHARSKE -> R.string.sub_sh_kucharske
        DvrClassifier.SH_ZABAVA -> R.string.sub_sh_zabava
        DvrClassifier.SH_MAGAZINY -> R.string.sub_sh_magaziny
        DvrClassifier.SH_OTHER -> R.string.sub_sh_other
        DvrClassifier.CH_ANIMATED -> R.string.sub_ch_animated
        DvrClassifier.CH_ROZPRAVKY -> R.string.sub_ch_rozpravky
        DvrClassifier.CH_VZDELAVAC -> R.string.sub_ch_vzdelavac
        DvrClassifier.CH_MOVIES -> R.string.sub_ch_movies
        DvrClassifier.CH_OTHER -> R.string.sub_ch_other
        DvrClassifier.MU_KLASIKA -> R.string.sub_mu_klasika
        DvrClassifier.MU_KONCERT -> R.string.sub_mu_koncert
        DvrClassifier.MU_HITY -> R.string.sub_mu_hity
        DvrClassifier.MU_FOLK -> R.string.sub_mu_folk
        DvrClassifier.MU_MAGAZINY -> R.string.sub_mu_magaziny
        DvrClassifier.MU_OTHER -> R.string.sub_mu_other
        DvrClassifier.AR_DIVADLO -> R.string.sub_ar_divadlo
        DvrClassifier.AR_VYTVARNE -> R.string.sub_ar_vytvarne
        DvrClassifier.AR_LITERATURA -> R.string.sub_ar_literatura
        DvrClassifier.AR_MOVIE -> R.string.sub_ar_movie
        DvrClassifier.AR_OTHER -> R.string.sub_ar_other
        DvrClassifier.DC_PRIRODA -> R.string.sub_dc_priroda
        DvrClassifier.DC_HISTORIA -> R.string.sub_dc_historia
        DvrClassifier.DC_VEDA -> R.string.sub_dc_veda
        DvrClassifier.DC_CESTOPIS -> R.string.sub_dc_cestopis
        DvrClassifier.DC_OSOBNOSTI -> R.string.sub_dc_osobnosti
        DvrClassifier.DC_SPOLOCNOST -> R.string.sub_dc_spolocnost
        DvrClassifier.DC_OTHER -> R.string.sub_dc_other
        DvrClassifier.HB_ZAHRADA -> R.string.sub_hb_zahrada
        DvrClassifier.HB_BYVANIE -> R.string.sub_hb_byvanie
        DvrClassifier.HB_VARENIE -> R.string.sub_hb_varenie
        DvrClassifier.HB_AUTO -> R.string.sub_hb_auto
        DvrClassifier.HB_CESTOVANIE -> R.string.sub_hb_cestovanie
        DvrClassifier.HB_ZDRAVIE -> R.string.sub_hb_zdravie
        DvrClassifier.HB_DIY -> R.string.sub_hb_diy
        DvrClassifier.HB_OTHER -> R.string.sub_hb_other
        else -> R.string.sub_mv_other
    }
    return stringResource(resId)
}

@Composable
/** TV/box Archive: left panel (Search, By channel, All + genres) + programme grid + description. */
fun TvArchiveScreen(vm: DvrViewModel = viewModel(), onBack: () -> Unit) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val refreshingTv by vm.refreshing.collectAsState()   // M589
    val server = remember { Tvh.store.active() }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }
    // M528: request a fresh list on every opening of the archive.
    // `loadIfNeeded` returns immediately when it already has data, so a just-finished
    // recording only appeared after an app restart. `refresh` keeps the old data
    // and pulls the new one in without flicker. The TV archive moreover has no refresh button
    // (that is only in the phone version), so there is no other way to refresh it.
    LaunchedEffect(Unit) { vm.loadIfNeeded(); vm.refresh() }
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

    DvrDeleteDialog(vm)   // M483: deleting a recording (long OK -> info -> Delete)

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // M530: the heading on the left, the manual refresh top right. The archive also refreshes itself
        // on opening (M528), but when the app stays open, a just-finished
        // recording would otherwise not appear.
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.dvr_archive).uppercase(),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(horizontal = 20.dp, vertical = 12.dp)
            )
            ArcReloadButton(loading = refreshingTv) { vm.refresh() }
        }
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
            // M514-fix: the recordings grid as well — after picking a date, focus should land
            // on the first programme, not hop back to "Recently watched"
            Box(Modifier.arcAutoFocus(e.uuid == list.firstOrNull()?.uuid)) {
                ArcRecCard(e, loaded.channelPicons[e.channelName], loader, context, progressTick,
                    onFocus = { focused = e }, onClick = { playDvr(context, e) }, onLong = { infoEntry = e })
            }
        }
    }
    val f = if (focused != null && list.contains(focused)) focused else list.firstOrNull()
    f?.let { ArcDetail(it) }
    infoEntry?.let { ent -> ArcInfoDialog(ent) { infoEntry = null } }
}


/**
 * M514: the first tile of a new grid requests focus right when it is created.
 *
 * When the user picks a folder, the old grid disappears together with the element that
 * held focus. Compose then searches for it from the start of the tree and finds "Recently
 * watched" in the left rail — which was visible as a brief flicker. Requesting focus
 * only from an outer LaunchedEffect is too late (the flicker has already happened); the tile
 * itself must request it in the very composition in which it appears.
 */
@Composable
private fun Modifier.arcAutoFocus(active: Boolean): Modifier {
    if (!active) return this
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    return this.focusRequester(fr)
}

@Composable
private fun ColumnScope.ArcChannelGrid(channels: List<String>, entries: List<DvrEntry>, loaded: DvrState.Loaded, loader: coil.ImageLoader, context: Context, onClick: (String) -> Unit) {
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxWidth().weight(1f).padding(8.dp)) {
        gridItems(channels, key = { it }) { ch ->
            Box(Modifier.arcAutoFocus(ch == channels.firstOrNull())) {   // M514
                ArcChannelCard(ch, loaded.channelPicons[ch], entries.count { it.channelName == ch }, loader, context) { onClick(ch) }
            }
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
            Box(Modifier.arcAutoFocus(tr.first == items.firstOrNull()?.first)) {   // M514
                ArcFolderCard(glyph, tr.second, tr.third, iconKey = iconKeyFor?.invoke(tr.first)) { onClick(tr.first) }
            }
        }
    }
}

/** A folder card of the modern archive: a coloured icon chip + a bold name + a count badge + an arrow. */
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
        // A modern tile (M321): the chip top left, a bold name, a count badge
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
    // Close only after a new full press of OK (a fresh DOWN with repeatCount==0). The tail of a long OK (the release) is ignored.
    var sawFreshDown by remember { mutableStateOf(false) }
    // M498: the Surface swallows all keys (OK closes the dialog), so a focusable
    // button never gets to them. The delete item is therefore selected with the DOWN ARROW
    // and confirmed with OK — the same as the info overlay in the player.
    var delSel by remember { mutableStateOf(false) }
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
                    // M498: the arrows toggle the selection of the delete item
                    if (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN) {
                        if (k.action == android.view.KeyEvent.ACTION_DOWN &&
                            DvrDeleteRequest.allowed) delSel = true
                        return@onKeyEvent true
                    }
                    if (k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP) {
                        if (k.action == android.view.KeyEvent.ACTION_DOWN) delSel = false
                        return@onKeyEvent true
                    }
                    val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        k.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        k.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                    if (!ok) return@onKeyEvent false
                    when (k.action) {
                        android.view.KeyEvent.ACTION_DOWN -> { if (k.repeatCount == 0) sawFreshDown = true; true }
                        android.view.KeyEvent.ACTION_UP -> {
                            if (sawFreshDown) {
                                // M498: OK on the selected item = delete, otherwise close
                                val del = delSel
                                onDismiss()
                                if (del) DvrDeleteRequest.ask(e)
                            }
                            true
                        }
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
                // M483: deleting from the TV archive — down arrow onto the button, OK confirms
                if (DvrDeleteRequest.allowed) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        stringResource(R.string.dvr_delete_button),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            // M498: the selection draws itself — focus does not work here
                            .background(
                                if (delSel) MaterialTheme.colorScheme.error.copy(alpha = 0.18f)
                                else androidx.compose.ui.graphics.Color.Transparent
                            )
                            .clickable { onDismiss(); DvrDeleteRequest.ask(e) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    // M498: the hint depends on what OK will actually do
                    if (delSel) stringResource(R.string.dvr_delete_button) + "  (OK)"
                    else stringResource(R.string.close) + "  (OK)",
                    color = if (delSel) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.End))
            }
        }
    }
    LaunchedEffect(Unit) { fr.requestFocus() }
}

/**
 * M530: the refresh button in the top right corner of the archive.
 *
 * Its look follows the chosen interface, so that it does not stand out from the whole:
 *  - MODERN: a rounded card with a coloured icon chip, like the items in the left rail
 *  - CLASSIC: a flat outline, like the other classic controls
 */
@Composable
private fun ArcReloadButton(loading: Boolean = false, onClick: () -> Unit) {   // M589
    var focused by remember { mutableStateOf(false) }
    val cs = MaterialTheme.colorScheme
    val modern = isModernUi()
    val light = isLightTheme()
    val shape = RoundedCornerShape(if (modern) 13.dp else 8.dp)
    Row(
        Modifier
            .padding(end = 20.dp)
            .clip(shape)
            .background(
                when {
                    focused && modern -> cs.primaryContainer.copy(alpha = if (light) 0.45f else 0.5f)
                    focused -> cs.primary.copy(alpha = 0.22f)
                    modern -> cs.surfaceVariant.copy(alpha = if (light) 0.35f else 0.25f)
                    else -> androidx.compose.ui.graphics.Color.Transparent
                }
            )
            .then(
                // classic mode: an outline instead of a fill
                if (!modern) Modifier.border(
                    1.dp,
                    if (focused) cs.primary else cs.outline.copy(alpha = 0.5f),
                    shape
                ) else Modifier
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (loading) {   // M589: visible progress on TV as well
            androidx.compose.material3.CircularProgressIndicator(
                modifier = Modifier.size(if (modern) 26.dp else 18.dp), strokeWidth = 2.dp
            )
        } else if (modern) {
            // an icon chip as in the left rail
            val chip = mgChipFor("dates")
            Box(
                Modifier.size(26.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color(if (light) chip.bgL else chip.bgD)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    Icons.Default.Refresh, contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(if (light) chip.fgL else chip.fgD),
                    modifier = Modifier.size(16.dp)
                )
            }
        } else {
            androidx.compose.material3.Icon(
                Icons.Default.Refresh, contentDescription = null,
                tint = if (focused) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            stringResource(R.string.dvr_reload),
            color = if (focused) cs.primary else cs.onSurface,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.titleSmall
        )
    }
}

@Composable
private fun ArcRailItem(label: String, count: Int?, selected: Boolean, iconKey: String? = null, onClick: () -> Unit) {
    if (isModernUi() && iconKey != null) {
        // Modern rail (M321): a card with a coloured icon chip and a count badge
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
                if (isMediaPlayKey(k.keyCode)) return@onPreviewKeyEvent handleMediaPlayKey(k) { onClick() }
                val ok = k.keyCode == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        k.keyCode == android.view.KeyEvent.KEYCODE_ENTER ||
                        k.keyCode == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                if (!ok) return@onPreviewKeyEvent false
                when (k.action) {
                    android.view.KeyEvent.ACTION_DOWN -> when (k.repeatCount) {
                        0 -> { longFired = false; false }          // let clickable keep tracking the short click
                        1 -> { longFired = true; onLong(); true }   // long OK -> info, consume
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
            overflow = TextOverflow.Ellipsis)   // M425: the longer 12-hour time
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
        DvrClassifier.SERIAL -> R.string.cat_series
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

/** Loads the corpus of titles from an asset and feeds it into DvrClassifier. Call once, on IO. */
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

/** Loads the IMDb cache from filesDir/imdb_cache.json into ImdbLookup. */
private fun loadImdbCache(context: Context) {
    try {
        val f = java.io.File(context.filesDir, "imdb_cache.json")
        if (f.exists()) ImdbLookup.importJson(f.readText())
    } catch (_: Exception) {
    }
}

/** Saves the IMDb cache to disk. */
private fun saveImdbCache(context: Context) {
    try {
        java.io.File(context.filesDir, "imdb_cache.json").writeText(ImdbLookup.exportJson())
    } catch (_: Exception) {
    }
}