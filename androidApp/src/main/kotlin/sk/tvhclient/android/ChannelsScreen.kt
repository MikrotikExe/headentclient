package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
fun ChannelsScreen(vm: ChannelsViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()
    val epgMap by vm.epgMap.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    var viewMenu by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf<String?>(null) } // tag uuid alebo null = vsetky
    var favOnly by remember { mutableStateOf(false) }
    var epgFor by remember { mutableStateOf<ChannelRow?>(null) }
    var contextRow by remember { mutableStateOf<ChannelRow?>(null) }
    var profileFor by remember { mutableStateOf<ChannelRow?>(null) }
    var favTick by remember { mutableStateOf(0) }
    val ctx = LocalContext.current
    val serverId = remember { Tvh.store.active()?.id }
    // Scroll pozicie prezivaju odskok do EPG a spat (remember v scope obrazovky)
    val listStateMain = androidx.compose.foundation.lazy.rememberLazyListState()
    val listStateSearch = androidx.compose.foundation.lazy.rememberLazyListState()

    LaunchedEffect(Unit) { vm.loadIfNeeded() }

    // tikajuci cas pre live ciaru priebehu (prekreslenie kazdych 30s)
    var nowTick by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowTick = System.currentTimeMillis() / 1000
        }
    }

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
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.setQuery(it) },
                label = { Text(stringResource(R.string.search_channels)) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.IconButton(onClick = { vm.load(true) }) {
                androidx.compose.material3.Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry)
                )
            }
            Box {
                androidx.compose.material3.IconButton(onClick = { viewMenu = true }) {
                    androidx.compose.material3.Icon(
                        when (viewMode) {
                            ChannelViewMode.LIST -> Icons.Default.ViewList
                            ChannelViewMode.GRID -> Icons.Default.GridView
                            ChannelViewMode.TILES -> Icons.Default.ViewModule
                        },
                        contentDescription = null
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = viewMenu,
                    onDismissRequest = { viewMenu = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_list)) },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Default.ViewList, null) },
                        onClick = { vm.setViewMode(ChannelViewMode.LIST); viewMenu = false }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_grid)) },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Default.GridView, null) },
                        onClick = { vm.setViewMode(ChannelViewMode.GRID); viewMenu = false }
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_tiles)) },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.Default.ViewModule, null) },
                        onClick = { vm.setViewMode(ChannelViewMode.TILES); viewMenu = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        when (val s = state) {
            is ChannelsState.Loading -> CenterBox { CircularProgressIndicator() }
            is ChannelsState.NoServer -> CenterBox {
                Text(stringResource(R.string.no_active_server))
            }
            is ChannelsState.Error -> CenterBox {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(onClick = { vm.load(true) }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
            is ChannelsState.Loaded -> {
                if (query.isNotBlank()) {
                    // Vyhladavanie: plochy filtrovany zoznam
                    val q = query.trim().lowercase()
                    val results = s.allRows.filter { it.channel.name.lowercase().contains(q) }
                    ChannelView(viewMode, results, listStateSearch, nowTick, epgMap) { contextRow = it }
                } else {
                    // Filtre podla tagov
                    val tags = s.categories.mapNotNull { it.tag }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item("fav") {
                            FilterChip(
                                selected = favOnly,
                                onClick = { favOnly = true },
                                label = { Text("\u2605 " + stringResource(R.string.favorites)) }
                            )
                        }
                        item("all") {
                            FilterChip(
                                selected = !favOnly && selectedTag == null,
                                onClick = { favOnly = false; selectedTag = null },
                                label = { Text(stringResource(R.string.all_channels)) }
                            )
                        }
                        items(tags, key = { it.uuid }) { tag ->
                            FilterChip(
                                selected = !favOnly && selectedTag == tag.uuid,
                                onClick = { favOnly = false; selectedTag = tag.uuid },
                                label = { Text(tag.name) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val favs = remember(favTick, serverId) {
                        if (serverId != null) Favorites.all(ctx, serverId) else emptySet()
                    }
                    val rows = when {
                        favOnly -> s.allRows.filter { it.channel.uuid in favs }
                        selectedTag == null ->
                            s.categories.flatMap { it.rows }.distinctBy { it.channel.uuid }
                        else ->
                            s.categories.firstOrNull { it.tag?.uuid == selectedTag }?.rows ?: emptyList()
                    }
                    ChannelView(viewMode, rows, listStateMain, nowTick, epgMap) { contextRow = it }
                }
            }
        }
    }

    // Kontextove menu kanala (dlhy klik): Program / Oblubene / Profil
    val cr = contextRow
    if (cr != null && serverId != null) {
        val isFav = remember(favTick) { Favorites.isFav(ctx, serverId, cr.channel.uuid) }
        ChannelActionDialog(
            channelName = cr.channel.name,
            isFav = isFav,
            onProgram = { epgFor = cr; contextRow = null },
            onToggleFav = {
                Favorites.toggle(ctx, serverId, cr.channel.uuid); favTick++; contextRow = null
            },
            onProfile = { profileFor = cr; contextRow = null },
            onDismiss = { contextRow = null }
        )
    }

    // Vyber profilu prehravania pre kanal
    val pf = profileFor
    if (pf != null && serverId != null) {
        val current = remember { ChannelPrefs.getProfile(ctx, serverId, pf.channel.uuid) }
        ProfilePickerDialog(
            channelName = pf.channel.name,
            current = current,
            onPick = {
                ChannelPrefs.setProfile(ctx, serverId, pf.channel.uuid, it); profileFor = null
            },
            onDismiss = { profileFor = null }
        )
    }
}

@Composable
private fun ChannelActionDialog(
    channelName: String,
    isFav: Boolean,
    onProgram: () -> Unit,
    onToggleFav: () -> Unit,
    onProfile: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    channelName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                ActionRow(stringResource(R.string.menu_program), onProgram)
                ActionRow(
                    if (isFav) stringResource(R.string.fav_remove) else stringResource(R.string.fav_add),
                    onToggleFav
                )
                ActionRow(stringResource(R.string.ch_profile), onProfile)
            }
        }
    }
}

@Composable
private fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge
    )
}

@Composable
private fun ProfilePickerDialog(
    channelName: String,
    current: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    stringResource(R.string.ch_profile) + " · " + channelName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
                ChannelPrefs.profileOptions.forEach { (code, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(code) }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (code == current) "\u2713  " else "     ",
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
}

private fun playChannel(
    context: android.content.Context,
    row: ChannelRow,
    title: String?,
    start: Long,
    stop: Long
) {
    val intent = android.content.Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_UUID, row.channel.uuid)
        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
        putExtra(PlayerActivity.EXTRA_PROG_START, start)
        putExtra(PlayerActivity.EXTRA_PROG_STOP, stop)
        putExtra(PlayerActivity.EXTRA_PROG_TITLE, title ?: "")
    }
    context.startActivity(intent)
}

/** Aktualna relacia z HTSP zoznamu (auto-prechod) alebo z row (HTTP). */
private fun currentNow(
    row: ChannelRow,
    epgList: List<sk.tvhclient.shared.model.EpgEvent>?,
    nowSec: Long
): Triple<String?, Long, Long> {
    return if (epgList != null) {
        val cur = epgList.firstOrNull { it.start <= nowSec && nowSec < it.stop }
        Triple(cur?.title?.ifBlank { null }, cur?.start ?: 0, cur?.stop ?: 0)
    } else {
        Triple(row.nowTitle, row.nowStart, row.nowStop)
    }
}

@Composable
private fun ChannelView(
    mode: ChannelViewMode,
    rows: List<ChannelRow>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    nowSec: Long,
    epgMap: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>,
    onShowEpg: (ChannelRow) -> Unit
) {
    when (mode) {
        ChannelViewMode.LIST -> ChannelList(rows, listState, nowSec, epgMap, onShowEpg)
        ChannelViewMode.GRID -> ChannelGrid(rows, nowSec, epgMap, columns = 2, onShowEpg)
        ChannelViewMode.TILES -> ChannelGrid(rows, nowSec, epgMap, columns = 4, onShowEpg)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelGrid(
    rows: List<ChannelRow>,
    nowSec: Long,
    epgMap: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>,
    columns: Int,
    onShowEpg: (ChannelRow) -> Unit
) {
    val context = LocalContext.current
    val server = remember { Tvh.store.active() }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }
    if (rows.isEmpty()) {
        CenterBox { Text(stringResource(R.string.no_channels)) }
        return
    }
    val tiles = columns >= 4
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        gridItems(rows, key = { it.channel.uuid }) { row ->
            val (nowTitle, nowStart, nowStop) = currentNow(row, epgMap[row.channel.uuid], nowSec)
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x14FFFFFF))
                    .combinedClickable(
                        onClick = { playChannel(context, row, nowTitle, nowStart, nowStop) },
                        onLongClick = { onShowEpg(row) }
                    )
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(if (tiles) 64.dp else 72.dp, if (tiles) 44.dp else 48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (row.piconUrl != null) {
                        AsyncImage(
                            model = ImageRequest.Builder(context).data(row.piconUrl).build(),
                            contentDescription = row.channel.name,
                            imageLoader = loader,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().padding(2.dp)
                        )
                    } else {
                        Text(row.channel.name.take(3).uppercase(),
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    row.channel.name,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                if (!tiles && nowTitle != null) {
                    Text(
                        nowTitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (nowStart > 0 && nowStop > nowStart) {
                        val frac = ((nowSec - nowStart).toFloat() /
                            (nowStop - nowStart).toFloat()).coerceIn(0f, 1f)
                        Spacer(Modifier.height(3.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { frac },
                            modifier = Modifier.fillMaxWidth().height(3.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelList(
    rows: List<ChannelRow>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    nowSec: Long,
    epgMap: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>,
    onShowEpg: (ChannelRow) -> Unit
) {
    val context = LocalContext.current
    val server = remember { Tvh.store.active() }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }

    if (rows.isEmpty()) {
        CenterBox { Text(stringResource(R.string.no_channels)) }
        return
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(rows, key = { it.channel.uuid }) { row ->
            ChannelItem(row, loader, context, nowSec, epgMap[row.channel.uuid], onShowEpg)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelItem(
    row: ChannelRow,
    loader: coil.ImageLoader,
    context: android.content.Context,
    nowSec: Long,
    epgList: List<sk.tvhclient.shared.model.EpgEvent>?,
    onShowEpg: (ChannelRow) -> Unit
) {
    val (curTitle, curStart, curStop) = currentNow(row, epgList, nowSec)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { playChannel(context, row, curTitle, curStart, curStop) },
                onLongClick = { onShowEpg(row) }
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp, 40.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0x22FFFFFF)),
            contentAlignment = Alignment.Center
        ) {
            if (row.piconUrl != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(row.piconUrl).build(),
                    contentDescription = row.channel.name,
                    imageLoader = loader,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
            } else {
                Text(
                    row.channel.name.take(2).uppercase(),
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.channel.number != null) {
                    Text(
                        "${row.channel.number}  ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    row.channel.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Aktualna relacia uz vypocitana hore (curTitle/curStart/curStop)
            if (curTitle != null) {
                Text(
                    curTitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (curStart > 0 && curStop > curStart) {
                    val frac = ((nowSec - curStart).toFloat() /
                        (curStop - curStart).toFloat()).coerceIn(0f, 1f)
                    Spacer(Modifier.height(3.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { frac },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
        // Ikona sipky -> otvori EPG kanala
        Text(
            "\u203A",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { onShowEpg(row) }
                .padding(horizontal = 14.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun CenterBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}
