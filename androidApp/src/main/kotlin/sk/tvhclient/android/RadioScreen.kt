package sk.tvhclient.android

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
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
    // Moderny rezim: "co prave hra" — EPG zdielame s ChannelsViewModel
    // (radia su v TVH tiez kanaly); ak pre stanicu EPG nie je, riadok
    // zobrazi len nazov. Klasik tieto data nepouziva.
    val chVm: ChannelsViewModel = viewModel()
    val radioEpg by chVm.epgMap.collectAsState()
    var nowTick by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(60_000); nowTick = System.currentTimeMillis() / 1000 }
    }
    val context = LocalContext.current
    val server = remember { Tvh.store.active() }
    val serverId = server?.id ?: ""
    // M505: filter podla tagov (ako v Kanaloch); null = vsetky stanice.
    // Obnovi sa posledna volba pre TENTO server.
    // M582: „Oblubene" ako skupina (LastTag.FAV), rovnako ako v Kanaloch
    val savedTag = remember(serverId) { LastTag.get(context, serverId, radio = true) }
    var selectedTag by remember(serverId) { mutableStateOf(savedTag?.takeIf { it != LastTag.FAV }) }
    var favOnly by remember(serverId) { mutableStateOf(savedTag == LastTag.FAV) }
    val lastRadioUuid = remember(state) { LastRadio.get(context, server?.id) }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }

    var contextRow by remember { mutableStateOf<ChannelRow?>(null) }
    var epgFor by remember { mutableStateOf<ChannelRow?>(null) }
    var showGrid by remember { mutableStateOf(false) }   // M587: mriezka TV programu pre radia
    var favTick by remember { mutableStateOf(0) }
    var lockTick by remember { mutableStateOf(0) }
    var hiddenTick by remember { mutableStateOf(0) }
    var viewMode by remember { mutableStateOf(RadioViewPref.get(context)) }
    var viewMenu by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadIfNeeded() }
    // M586: „prave hra" pri staniciach berieme z ChannelsViewModel (radia su v TVH
    // tiez kanaly a HTSP now/next sa tiahne pre vsetky naraz). Doteraz to nikto
    // z tejto zalozky nespustil — kto otvoril Radia bez Kanalov, videl len nazvy.
    LaunchedEffect(Unit) { chVm.loadIfNeeded() }

    // D-pad fokus: pociatocny fokus na prve radio + presmerovanie pri reselect (znovu kliknutie na Radia)
    val firstFocus = remember { FocusRequester() }
    val jumpFocus = remember { FocusRequester() }
    var jumpTarget by remember { mutableStateOf(-1) }
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // M583: presun oblubenych tahanim za rukovat (len chip Oblubene, dotyk) — ako v Kanaloch (M560)
    var dragUuid by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val dragScope = rememberCoroutineScope()
    val touchDevice = remember { !context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) }
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

    // M587: mriezka TV programu pre rozhlasove stanice (rovnaka ako pri kanaloch,
    // len so stanicami a ich skupinami). Spustenie ide cez playRadio, nech je
    // spravanie rovnake ako zo zoznamu (mini prehravac, PIN, LastRadio).
    val stGrid = state
    if (showGrid) {
        if (stGrid is RadioState.Loaded) {
            EpgGridScreen(
                allRows = emptyList(),
                categories = emptyList(),
                seed = radioEpg,
                onBack = { showGrid = false },
                radioRows = stGrid.rows,
                radioCategories = stGrid.categories,
                radioOnly = true,
                onPlayRadio = { row, ev ->
                    playRadio(
                        context, stGrid.rows, row,
                        epgTitle = ev?.title ?: row.nowTitle ?: "",
                        epgStart = ev?.start ?: row.nowStart,
                        epgStop = ev?.stop ?: row.nowStop
                    )
                }
            )
            return
        }
        showGrid = false
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
            IconButton(onClick = { showGrid = true }) {   // M587
                Icon(Icons.Default.CalendarViewDay, contentDescription = stringResource(R.string.tv_guide))
            }
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
                is RadioState.Error -> ErrorStatus(   // M491
                    s.message.ifBlank { stringResource(R.string.load_error) },
                    onRetry = { vm.load() })
                is RadioState.Loaded -> Column(Modifier.fillMaxSize()) {
                    // M505-fix: pas filtrov a zoznam musia byt POD SEBOU. Rodic je
                    // Box (deti sa prekryvaju), takze bez tohto Column sa pas vykreslil
                    // pod zoznamom stanic a nebolo ho vidiet.
                    val q = query.trim().lowercase()
                    // pas sa ukaze len ked ma radio aspon jednu skupinu
                    val radioTags = s.categories.mapNotNull { it.tag }
                    // M582: zoznam oblubenych v poradi (ako v Kanaloch); pas filtrov sa ukaze
                    // aj bez tagov, ked su nejake oblubene radia
                    val favs = remember(favTick, serverId) { Favorites.list(context, serverId) }
                    val favUuids = remember(favs) { favs.toSet() }
                    val radioFavs = remember(favs, s) { favs.filter { u -> s.rows.any { it.channel.uuid == u } } }
                    if (q.isBlank() && (radioTags.isNotEmpty() || radioFavs.isNotEmpty())) {
                        // ulozeny tag uz na serveri nemusi existovat -> spadni na „vsetky"
                        val validTag = selectedTag?.takeIf { u -> radioTags.any { it.uuid == u } }
                        if (validTag != selectedTag) selectedTag = validTag
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(horizontal = 12.dp)
                        ) {
                            if (radioFavs.isNotEmpty()) item("fav") {
                                FilterChip(
                                    selected = favOnly,
                                    onClick = {
                                        favOnly = true
                                        LastTag.set(context, serverId, true, LastTag.FAV)
                                    },
                                    label = { Text("\u2605 " + stringResource(R.string.favorites)) }
                                )
                            }
                            item("all") {
                                FilterChip(
                                    selected = !favOnly && selectedTag == null,
                                    onClick = {
                                        favOnly = false; selectedTag = null
                                        LastTag.set(context, serverId, true, null)
                                    },
                                    label = { Text(stringResource(R.string.all_channels)) }
                                )
                            }
                            items(radioTags, key = { it.uuid }) { tag ->
                                FilterChip(
                                    selected = !favOnly && selectedTag == tag.uuid,
                                    onClick = {
                                        favOnly = false; selectedTag = tag.uuid
                                        LastTag.set(context, serverId, true, tag.uuid)
                                    },
                                    label = { Text(tag.name) }
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    // Oblubene bez radii (vsetky odobrane) -> spat na Vsetky
                    if (favOnly && radioFavs.isEmpty() && q.isBlank()) { favOnly = false }
                    val base = when {
                        q.isNotBlank() -> s.rows
                        favOnly -> radioFavs.mapNotNull { u -> s.rows.firstOrNull { it.channel.uuid == u } }
                            .mapIndexed { i, r -> r.copy(channel = r.channel.copy(number = i + 1)) }
                        selectedTag == null -> s.rows
                        else -> s.categories.firstOrNull { it.tag?.uuid == selectedTag }?.rows
                            ?: emptyList()
                    }
                    val favMarks = if (favOnly) emptySet() else favUuids
                    val rows = if (q.isBlank()) base
                               else base.filter { it.channel.name.lowercase().contains(q) }
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
                                    val canDrag = favOnly && touchDevice && q.isBlank() && rows.size > 1
                                    if (!canDrag) {
                                        RadioRow(
                                            row, rows, loader, context,
                                            modifier = keyMod,
                                            onContext = { contextRow = it },
                                            epgList = radioEpg[row.channel.uuid],
                                            nowSec = nowTick,
                                            highlighted = row.channel.uuid == lastRadioUuid,
                                            favorite = row.channel.uuid in favMarks,   // M582
                                            hiddenTick = hiddenTick,
                                        )
                                    } else {
                                        // M583: riadok + rukovat na tahanie; tahany riadok nadvihnuty (posun, tien, okraj)
                                        val dragging = dragUuid == row.channel.uuid
                                        val accent = MaterialTheme.colorScheme.primary
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .then(if (dragging) Modifier else Modifier.animateItem())
                                                .zIndex(if (dragging) 1f else 0f)
                                                .graphicsLayer {
                                                    translationY = if (dragging) dragOffset else 0f
                                                    shadowElevation = if (dragging) 24f else 0f
                                                    shape = RoundedCornerShape(8.dp); clip = dragging
                                                }
                                                .then(if (dragging) Modifier.border(2.dp, accent, RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surface) else Modifier),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(Modifier.weight(1f)) {
                                                RadioRow(
                                                    row, rows, loader, context,
                                                    modifier = keyMod,
                                                    onContext = { contextRow = it },
                                                    epgList = radioEpg[row.channel.uuid],
                                                    nowSec = nowTick,
                                                    highlighted = row.channel.uuid == lastRadioUuid,
                                                    hiddenTick = hiddenTick,
                                                )
                                            }
                                            Icon(
                                                Icons.Default.DragHandle, contentDescription = null,
                                                tint = if (dragging) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier
                                                    .padding(horizontal = 10.dp)
                                                    .size(28.dp)
                                                    .pointerInput(row.channel.uuid) {
                                                        detectDragGestures(
                                                            onDragStart = { dragUuid = row.channel.uuid; dragOffset = 0f },
                                                            onDragEnd = { dragUuid = null; dragOffset = 0f },
                                                            onDragCancel = { dragUuid = null; dragOffset = 0f },
                                                            onDrag = { change, delta ->
                                                                change.consume()
                                                                dragOffset += delta.y
                                                                val info = listState.layoutInfo
                                                                val me = info.visibleItemsInfo.firstOrNull { it.key == row.channel.uuid }
                                                                    ?: return@detectDragGestures
                                                                val cur = me.index
                                                                val h = me.size.toFloat().coerceAtLeast(1f)
                                                                // vymena so susedom, ked je riadok prevleceny cez polovicu jeho vysky
                                                                val swapWith = when {
                                                                    dragOffset > h / 2f && cur < info.totalItemsCount - 1 -> cur + 1
                                                                    dragOffset < -h / 2f && cur > 0 -> cur - 1
                                                                    else -> -1
                                                                }
                                                                // sused z aktualneho layoutu (kluc = uuid), nie z `rows`
                                                                // zachytenych pri prvej kompozicii — po prvom presune by boli stare
                                                                val other = info.visibleItemsInfo.firstOrNull { it.index == swapWith }?.key as? String
                                                                if (swapWith >= 0 && other != null) {
                                                                    // podla uuid — zoznam oblubenych je spolocny s TV kanalmi
                                                                    Favorites.moveUuid(context, serverId, row.channel.uuid, other)
                                                                    favTick++
                                                                    dragOffset += if (swapWith > cur) -h else h
                                                                }
                                                                // autoscroll pri okrajoch zoznamu
                                                                val y = me.offset + dragOffset + h / 2f
                                                                val vpStart = info.viewportStartOffset.toFloat()
                                                                val vpEnd = info.viewportEndOffset.toFloat()
                                                                val edge = h
                                                                val scrollBy = when {
                                                                    y < vpStart + edge -> -(vpStart + edge - y) * 0.3f
                                                                    y > vpEnd - edge -> (y - (vpEnd - edge)) * 0.3f
                                                                    else -> 0f
                                                                }
                                                                if (scrollBy != 0f) dragScope.launch { listState.scrollBy(scrollBy) }
                                                            }
                                                        )
                                                    }
                                            )
                                        }
                                    }
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
                                            onContext = { contextRow = it },
                                            favorite = row.channel.uuid in favMarks,   // M582
                                            hiddenTick = hiddenTick,
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
            piconUrl = cr.piconUrl,
            piconLoader = loader,
            onProgram = { epgFor = cr; contextRow = null },
            onToggleFav = {
                Favorites.toggle(context, serverId, cr.channel.uuid); favTick++; contextRow = null
            },
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RadioRow(
    row: ChannelRow,
    allRows: List<ChannelRow>,
    loader: coil.ImageLoader,
    context: android.content.Context,
    modifier: Modifier = Modifier,
    onContext: (ChannelRow) -> Unit,
    epgList: List<sk.tvhclient.shared.model.EpgEvent>? = null,
    nowSec: Long = 0L,
    highlighted: Boolean = false,
    favorite: Boolean = false,   // M582
    hiddenTick: Int = 0,
) {
    val hidden = remember(hiddenTick, row.channel.uuid) {
        HiddenChannels.isHidden(context, Tvh.store.active()?.id, row.channel.uuid)
    }
    if (isModernUi()) {
        // moderny riadok: karta s "co prave hra", progresom a minutami;
        // posledne pocuvana stanica ma teal zvyraznenie. Klasik nizsie nedotknuty.
        val ev = epgList?.firstOrNull { nowSec in it.start until it.stop }
        val nt = ev?.title?.takeIf { it.isNotBlank() } ?: row.nowTitle
        val ns = ev?.start ?: row.nowStart
        val ne = ev?.stop ?: row.nowStop
        val next = epgList?.firstOrNull {
            it.start >= (if (ne > 0) ne else nowSec) && it.title.isNotBlank()
        }?.title
        ModernChannelTabRow(
            name = row.channel.name,
            number = row.channel.number,
            piconUrl = row.piconUrl,
            nowTitle = nt,
            nowStart = ns,
            nowStop = ne,
            nextTitle = next,
            nowSec = nowSec,
            recording = false,
            locked = false,
            hidden = hidden,
            loader = loader,
            onClick = { playRadio(context, allRows, row, nt ?: "", ns, ne) },
            onLongClick = { onContext(row) },
            modifier = modifier,
            highlighted = highlighted,
            favorite = favorite,   // M582
        )
        return
    }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(row.channel.name, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (favorite) {   // M582
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Filled.Star, contentDescription = null,
                        tint = androidx.compose.ui.graphics.Color(0xFFFBBF24), modifier = Modifier.size(16.dp))
                }
                if (hidden) {
                    Spacer(Modifier.width(6.dp))
                    Text("\uD83D\uDEAB", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
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
    onContext: (ChannelRow) -> Unit,
    favorite: Boolean = false,   // M582
    hiddenTick: Int = 0,
) {
    val hidden = remember(hiddenTick, row.channel.uuid) {
        HiddenChannels.isHidden(context, Tvh.store.active()?.id, row.channel.uuid)
    }
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
            if (favorite) {   // M582
                Icon(Icons.Filled.Star, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFFBBF24),
                    modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(12.dp))
            }
            if (hidden) {
                Text("\uD83D\uDEAB", style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
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

/** Spusti rozhlasovu stanicu cez EXTRA_UUID a naplni LivePlaylist (zapping + zoznam v prehravaci).
 *  M587: internal — pouziva to aj mriezka TV programu (polozka „Rádiá"). */
internal fun playRadio(
    context: android.content.Context,
    allRows: List<ChannelRow>,
    row: ChannelRow,
    // EPG obohatene pri kliku (surovy row.nowTitle byva prazdny — zoznam
    // ho plni az pri renderi z epgMap, preto sa v mini liste neukazovalo)
    epgTitle: String = row.nowTitle ?: "",
    epgStart: Long = row.nowStart,
    epgStop: Long = row.nowStop
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
    // M340: na telefone v modernom rezime hra radio cez mini prehravac
    // (foreground service + lista nad tabmi) — appka ostava pouzitelna.
    // TV, klasik a zamknute stanice idu povodnou cestou (plny prehravac,
    // ktory riesi PIN aj D-pad ovladanie).
    val needsPin = ParentalLock.channelNeedsPin(context, server.id, row.channel.uuid)
    val tvDevice = context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    if (!tvDevice && !needsPin && UiModePref.get(context) == UiModePref.MODERN) {
        RadioCenter.stations = allRows.map {
            RadioCenter.RadioStation(
                it.channel.uuid, it.channel.name, it.piconUrl,
                it.nowTitle ?: "", it.nowStart, it.nowStop
            )
        }
        RadioCenter.play(
            context, server, row.channel.uuid, row.channel.name,
            picon = row.piconUrl,
            epgTitle = epgTitle,
            epgStart = epgStart, epgStop = epgStop
        )
        return
    }
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
