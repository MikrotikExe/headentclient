package sk.tvhclient.android

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ChannelRow

@Composable
fun RadioScreen(vm: RadioViewModel = viewModel(), resetSignal: Int = 0, onGoToNav: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()
    val context = LocalContext.current
    val server = remember { Tvh.store.active() }
    val serverId = server?.id ?: ""
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }

    var contextRow by remember { mutableStateOf<ChannelRow?>(null) }
    var epgFor by remember { mutableStateOf<ChannelRow?>(null) }
    var profileFor by remember { mutableStateOf<ChannelRow?>(null) }
    var favTick by remember { mutableStateOf(0) }
    var lockTick by remember { mutableStateOf(0) }
    var hiddenTick by remember { mutableStateOf(0) }
    var viewMode by remember { mutableStateOf(RadioViewPref.get(context)) }
    var viewMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadIfNeeded() }

    // D-pad fokus: pociatocny fokus na prve radio + presmerovanie pri reselect (znovu kliknutie na Radia)
    val firstFocus = remember { FocusRequester() }
    val jumpFocus = remember { FocusRequester() }
    var jumpTarget by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // Po nacitani daj fokus na prvu polozku (nech sa da hned ist sipkou dole)
    LaunchedEffect(state) {
        if (state is RadioState.Loaded) {
            kotlinx.coroutines.delay(150)
            runCatching { firstFocus.requestFocus() }
        }
    }
    // Skok o 5 (LEFT/RIGHT): doscrolluj, pockaj snimku, az potom zameraj
    LaunchedEffect(jumpTarget) {
        val t = jumpTarget
        if (t >= 0) {
            runCatching { listState.scrollToItem(t) }
            androidx.compose.runtime.withFrameNanos { }
            runCatching { jumpFocus.requestFocus() }
            jumpTarget = -1
        }
    }
    // Znovu kliknutie na Radia v navigacii: skroluj na vrch a daj fokus na prve radio
    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) {
            runCatching { listState.scrollToItem(0) }
            runCatching { gridState.scrollToItem(0) }
            runCatching { firstFocus.requestFocus() }
        }
    }

    // EPG jedneho radia
    val epgRow = epgFor
    if (epgRow != null) {
        EpgScreen(
            channelUuid = epgRow.channel.uuid,
            channelName = epgRow.channel.name,
            onBack = { epgFor = null }
        )
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvSearchBar(
                query = query,
                placeholder = stringResource(R.string.search_channels),
                onQueryChange = { vm.setQuery(it) },
                focusRequester = searchFocus,
                onUp = onGoToNav,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { vm.load() }) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.retry))
            }
            Box {
                IconButton(onClick = { viewMenu = true }) {
                    Icon(
                        when (viewMode) {
                            ChannelViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                            ChannelViewMode.GRID -> Icons.Default.GridView
                            ChannelViewMode.TILES -> Icons.Default.ViewModule
                        },
                        contentDescription = stringResource(R.string.view_mode)
                    )
                }
                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_list)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.ViewList, null) },
                        onClick = { viewMode = ChannelViewMode.LIST; RadioViewPref.set(context, viewMode); viewMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_grid)) },
                        leadingIcon = { Icon(Icons.Default.GridView, null) },
                        onClick = { viewMode = ChannelViewMode.GRID; RadioViewPref.set(context, viewMode); viewMenu = false }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_tiles)) },
                        leadingIcon = { Icon(Icons.Default.ViewModule, null) },
                        onClick = { viewMode = ChannelViewMode.TILES; RadioViewPref.set(context, viewMode); viewMenu = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxSize()) {
            when (val s = state) {
                is RadioState.Loading -> LoadingStatus()
                is RadioState.NoServer -> NoServerStatus()
                is RadioState.Error -> ErrorStatus(s.message, onRetry = { vm.load() })
                is RadioState.Loaded -> {
                    val q = query.trim().lowercase()
                    val rows = if (q.isBlank()) s.rows
                               else s.rows.filter { it.channel.name.lowercase().contains(q) }
                    if (rows.isEmpty()) {
                        EmptyStatus(stringResource(R.string.radio_empty))
                    } else {
                        when (viewMode) {
                            ChannelViewMode.LIST -> LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                                itemsIndexed(rows, key = { _, it -> it.channel.uuid }) { idx, row ->
                                    val last = rows.lastIndex
                                    val focusMod = when {
                                        jumpTarget >= 0 && idx == jumpTarget -> Modifier.focusRequester(jumpFocus)
                                        idx == 0 -> Modifier.focusRequester(firstFocus)
                                        else -> Modifier
                                    }
                                    val keyMod = focusMod.onPreviewKeyEvent { e ->
                                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                                        when (e.nativeKeyEvent.keyCode) {
                                            // Hore na 1. radiu -> vyhladavacie pole (a odtial hore na spodne menu)
                                            android.view.KeyEvent.KEYCODE_DPAD_UP ->
                                                if (idx == 0) { runCatching { searchFocus.requestFocus() }; true } else false
                                            // Vlavo: -5 (wrap na koniec)
                                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                                                jumpTarget = if (idx == 0) last else (idx - 5).coerceAtLeast(0)
                                                true
                                            }
                                            // Vpravo: +5 (wrap na zaciatok)
                                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                                jumpTarget = if (idx == last) 0 else (idx + 5).coerceAtMost(last)
                                                true
                                            }
                                            else -> false
                                        }
                                    }
                                    RadioRow(
                                        row, rows, loader, context,
                                        modifier = keyMod,
                                        onContext = { contextRow = it }
                                    )
                                }
                            }
                            ChannelViewMode.GRID, ChannelViewMode.TILES -> {
                                val cols = if (viewMode == ChannelViewMode.GRID) 2 else 4
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(cols),
                                    state = gridState,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    gridItemsIndexed(rows, key = { _, it -> it.channel.uuid }) { idx, row ->
                                        RadioTile(
                                            row, rows, loader, context,
                                            modifier = if (idx == 0) Modifier.focusRequester(firstFocus) else Modifier,
                                            onContext = { contextRow = it }
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

    // Kontextove menu (dlhe podrzanie)
    val cr = contextRow
    if (cr != null) {
        val isFav = remember(favTick) { Favorites.isFav(context, serverId, cr.channel.uuid) }
        val isLocked = remember(lockTick) {
            ParentalLock.isChannelLocked(context, serverId, cr.channel.uuid)
        }
        val isHidden = remember(hiddenTick) {
            HiddenChannels.isHidden(context, serverId, cr.channel.uuid)
        }
        ChannelActionDialog(
            channelName = cr.channel.name,
            isFav = isFav,
            isLocked = isLocked,
            isHidden = isHidden,
            lockEnabled = ParentalLock.isEnabled(context),
            onProgram = { epgFor = cr; contextRow = null },
            onToggleFav = {
                Favorites.toggle(context, serverId, cr.channel.uuid); favTick++; contextRow = null
            },
            onProfile = { profileFor = cr; contextRow = null },
            onToggleLock = {
                val uuid = cr.channel.uuid
                ParentalLock.setChannelLocked(context, serverId, uuid, !isLocked); lockTick++
                contextRow = null
            },
            onToggleHide = {
                HiddenChannels.setHidden(context, serverId, cr.channel.uuid, !isHidden)
                hiddenTick++; contextRow = null
            },
            onDismiss = { contextRow = null }
        )
    }

    val pf = profileFor
    if (pf != null) {
        val current = remember { ChannelPrefs.getProfile(context, serverId, pf.channel.uuid) }
        ProfilePickerDialog(
            channelName = pf.channel.name,
            current = current,
            onPick = {
                ChannelPrefs.setProfile(context, serverId, pf.channel.uuid, it); profileFor = null
            },
            onDismiss = { profileFor = null }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RadioRow(
    row: ChannelRow,
    allRows: List<ChannelRow>,
    loader: coil.ImageLoader,
    context: android.content.Context,
    modifier: Modifier = Modifier,
    onContext: (ChannelRow) -> Unit
) {
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { playRadio(context, allRows, row) },
                onLongClick = { onContext(row) }
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 56.dp, height = 40.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
            .background(piconBackground()), contentAlignment = Alignment.Center) {
            if (row.piconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(row.piconUrl).build(),
                    contentDescription = row.channel.name,
                    imageLoader = loader,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
            } else {
                Text("\uD83D\uDCFB")  // radio emoji
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            row.channel.number?.let {
                Text("$it", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary)
            }
            Text(row.channel.name, style = MaterialTheme.typography.bodyLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Sipka -> otvori menu
        Text(
            "\u203A",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable { onContext(row) }
                .padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RadioTile(
    row: ChannelRow,
    allRows: List<ChannelRow>,
    loader: coil.ImageLoader,
    context: android.content.Context,
    modifier: Modifier = Modifier,
    onContext: (ChannelRow) -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { playRadio(context, allRows, row) },
                onLongClick = { onContext(row) }
            )
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.fillMaxWidth().height(56.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .background(piconBackground()),
            contentAlignment = Alignment.Center
        ) {
            if (row.piconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(row.piconUrl).build(),
                    contentDescription = row.channel.name,
                    imageLoader = loader,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(4.dp)
                )
            } else {
                Text("\uD83D\uDCFB")
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            row.channel.name,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Spusti rozhlasovu stanicu cez EXTRA_UUID a naplni LivePlaylist (zapping + zoznam v prehravaci). */
private fun playRadio(
    context: android.content.Context,
    allRows: List<ChannelRow>,
    row: ChannelRow
) {
    val server = Tvh.store.active() ?: return
    LivePlaylist.channels = allRows.map {
        LivePlaylist.LiveChannel(
            uuid = it.channel.uuid,
            name = it.channel.name,
            number = it.channel.number ?: 0,
            piconUrl = it.piconUrl,
            nowTitle = it.nowTitle ?: "",
            nowStart = it.nowStart,
            nowStop = it.nowStop
        )
    }
    LivePlaylist.setIndexForUuid(row.channel.uuid)
    LastRadio.set(context, server.id, row.channel.uuid)
    val intent = Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_UUID, row.channel.uuid)
        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
        putExtra(PlayerActivity.EXTRA_KIND, "radio")
        putExtra(PlayerActivity.EXTRA_PROG_START, row.nowStart)
        putExtra(PlayerActivity.EXTRA_PROG_STOP, row.nowStop)
        putExtra(PlayerActivity.EXTRA_PROG_TITLE, row.nowTitle ?: "")
        putExtra(
            PlayerActivity.EXTRA_REQUIRE_PIN,
            ParentalLock.channelNeedsPin(context, server.id, row.channel.uuid)
        )
    }
    context.startActivity(intent)
}

object RadioViewPref {
    private const val KEY = "radio_view_mode"
    fun get(c: android.content.Context): ChannelViewMode {
        val v = c.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return ChannelViewMode.LIST
        return runCatching { ChannelViewMode.valueOf(v) }.getOrDefault(ChannelViewMode.LIST)
    }
    fun set(c: android.content.Context, mode: ChannelViewMode) {
        c.getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putString(KEY, mode.name).apply()
    }
}
