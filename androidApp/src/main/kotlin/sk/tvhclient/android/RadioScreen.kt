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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
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
fun RadioScreen(vm: RadioViewModel = viewModel()) {
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

    LaunchedEffect(Unit) { vm.load() }

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
                            ChannelViewMode.LIST -> LazyColumn(Modifier.fillMaxSize()) {
                                items(rows, key = { it.channel.uuid }) { row ->
                                    RadioRow(row, rows, loader, context, onContext = { contextRow = it })
                                }
                            }
                            ChannelViewMode.GRID, ChannelViewMode.TILES -> {
                                val cols = if (viewMode == ChannelViewMode.GRID) 2 else 4
                                LazyVerticalGrid(
                                    columns = GridCells.Fixed(cols),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    gridItems(rows, key = { it.channel.uuid }) { row ->
                                        RadioTile(row, rows, loader, context, onContext = { contextRow = it })
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
    onContext: (ChannelRow) -> Unit
) {
    Row(
        Modifier
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
    onContext: (ChannelRow) -> Unit
) {
    Column(
        Modifier
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
    LastChannel.set(context, server.id, row.channel.uuid)
    val intent = Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_UUID, row.channel.uuid)
        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
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
