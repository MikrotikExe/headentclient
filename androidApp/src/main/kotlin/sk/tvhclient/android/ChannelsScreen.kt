package sk.tvhclient.android

import androidx.compose.runtime.DisposableEffect
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.CalendarViewDay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.ViewList
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
fun ChannelsScreen(vm: ChannelsViewModel = viewModel(), resetSignal: Int = 0, onGoToNav: () -> Unit = {}) {
    val state by vm.state.collectAsState()
    val query by vm.query.collectAsState()
    val epgMap by vm.epgMap.collectAsState()
    val viewMode by vm.viewMode.collectAsState()
    var viewMenu by remember { mutableStateOf(false) }
    var selectedTag by remember { mutableStateOf<String?>(null) } // tag uuid alebo null = vsetky
    // M505: posledna volba skupiny sa obnovi (nastavuje sa nizsie, ked je znamy serverId)
    var tagRestored by remember { mutableStateOf(false) }
    var favOnly by remember { mutableStateOf(false) }
    var epgFor by remember { mutableStateOf<ChannelRow?>(null) }
    var contextRow by remember { mutableStateOf<ChannelRow?>(null) }
    var favTick by remember { mutableStateOf(0) }
    var lockTick by remember { mutableStateOf(0) }
    var hiddenTick by remember { mutableStateOf(0) }
    // Pri zamknutom kanali / nastaveniach: akcia, ktora sa vykona po spravnom PINe
    var pinAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    // EPG kláves dialkoveho -> otvor TV program (mriezku). Odvodene priamo zo
    // signalu (nie cez oneskoreny LaunchedEffect), nech sa neblikne zoznam.
    val epgSignal by TabController.epgGrid
    // baseline: pri cerstvom vstupe sa mriezka neotvori; pri cold-starte z
    // prehravaca (epgFromPlayer, signal uz zvyseny v onCreate) sa otvori
    var epgDismissedGen by remember {
        mutableStateOf(
            if (TabController.epgFromPlayer || TabController.epgColdOpen) TabController.epgGrid.value - 1
            else TabController.epgGrid.value
        )
    }
    androidx.compose.runtime.SideEffect { TabController.epgColdOpen = false }
    val showGrid = epgSignal > epgDismissedGen
    // pri navrate do prehravaca nechame mriezku zobrazenu (nech neblikne zoznam)
    // a skryjeme ju az ked sa MainActivity vrati na popredie (po zatvoreni prehravaca)
    var pendingGridDismiss by remember { mutableStateOf(false) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && pendingGridDismiss) {
                epgDismissedGen = epgSignal
                pendingGridDismiss = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // Zdielany DvrViewModel — vieme ktore kanaly sa prave nahravaju
    val dvrVm: DvrViewModel = viewModel()
    val dvrState by dvrVm.state.collectAsState()
    // Indikator nahravania parujeme na kanal podla UUID (nie nazvu) — inak by
    // pri duplicitnych nazvoch/LCN (napr. regionalne "ITV1 HD" 103) svietilo na
    // vsetkych. Nazov je len fallback pre stare servery bez UUID v DVR zazname.
    val recordingFor: (ChannelRow) -> sk.tvhclient.shared.model.DvrEntry? = remember(dvrState, state) {
        val recs = (dvrState as? DvrState.Loaded)?.recording ?: emptyList()
        val byUuid = recs.filter { it.channelUuid.isNotBlank() }.associateBy { it.channelUuid }
        // M556: fallback podla nazvu aj pre zaznamy, ktorych channelUuid nesedi na ZIADEN
        // kanal v zozname — po prepnuti HTSP <-> HTTP nesie stary DVR stav ine kluce
        // (HTSP channelId vs HTTP hex uuid) a bodky zmizli, kym sa DVR nenacitalo znova.
        val known = (state as? ChannelsState.Loaded)?.allRows?.map { it.channel.uuid }?.toSet() ?: emptySet()
        val byName = recs.filter { it.channelUuid.isBlank() || it.channelUuid !in known }.associateBy { it.channelName }
        val resolver: (ChannelRow) -> sk.tvhclient.shared.model.DvrEntry? =
            { row -> byUuid[row.channel.uuid] ?: byName[row.channel.name] }
        resolver
    }
    var recChoice by remember { mutableStateOf<Pair<ChannelRow, sk.tvhclient.shared.model.DvrEntry>?>(null) }
    val ctx = LocalContext.current
    val serverId = remember { Tvh.store.active()?.id }
    // M505: obnov naposledy zvolenu skupinu kanalov pre tento server
    LaunchedEffect(serverId) {
        if (!tagRestored) {
            tagRestored = true
            val t = LastTag.get(ctx, serverId, radio = false)
            if (t == LastTag.FAV) favOnly = true else selectedTag = t   // M541: aj Oblubene
        }
    }
    // Scroll pozicie prezivaju odskok do EPG a spat (remember v scope obrazovky)
    val listStateMain = androidx.compose.foundation.lazy.rememberLazyListState()
    val listStateSearch = androidx.compose.foundation.lazy.rememberLazyListState()
    val searchFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { vm.loadIfNeeded() }
    // M529: `loadIfNeeded` sa pri opatovnom otvoreni vrati hned, ked uz data ma —
    // prave zalozena nahravka sa preto v zozname neprejavila. `refresh` drzi stare
    // data a dotiahne nove bez blikania.
    LaunchedEffect(Unit) { dvrVm.loadIfNeeded(); dvrVm.refresh() }

    // Zoznam kanalov pre zapping a zoznam v prehravaci (CH+/CH-, overlay).
    // Skryte kanaly sem nepatria (v prehravaci sa nezobrazuju).
    // M566: aj pri zmene zvoleneho tagu / Oblubenych / poradia oblubenych — prehravac dostane
    // rovnaku skupinu, aku ma pouzivatel zvolenu v zozname (CH+/- a zoznam v prehravaci
    // prepinaju len v nej), nie vzdy „Vsetky"
    LaunchedEffect(state, epgMap, hiddenTick, favOnly, selectedTag, favTick) {
        val srv = Tvh.store.active()
        (state as? ChannelsState.Loaded)?.let { st ->
            val nowS = System.currentTimeMillis() / 1000
            val hidden = HiddenChannels.all(ctx, srv?.id)
            val full = st.allRows.filter { it.channel.uuid !in hidden }.map { r ->
                val (nt, ns, ne) = currentNow(r, epgMap[r.channel.uuid], nowS)
                val nx = epgMap[r.channel.uuid]?.firstOrNull { it.start >= (if (ne > 0) ne else nowS) }
                LivePlaylist.LiveChannel(
                    uuid = r.channel.uuid,
                    name = r.channel.name,
                    number = r.channel.number ?: 0,
                    piconUrl = r.piconUrl,
                    nowTitle = nt ?: "",
                    nowStart = ns,
                    nowStop = ne,
                    nextTitle = nx?.title ?: "",
                    nextStart = nx?.start ?: 0,
                    nextStop = nx?.stop ?: 0
                )
            }
            val grps = st.categories.mapNotNull { cat ->
                val t = cat.tag ?: return@mapNotNull null
                val u = cat.rows.map { it.channel.uuid }.filter { it !in hidden }.toSet()
                if (u.isEmpty()) null else LivePlaylist.Group(t.uuid, t.name, u)
            }
            // M541: skryte kanaly a poradie oblubenych aj pre prehravac na telefone
            val hiddenList = st.allRows.filter { it.channel.uuid in hidden }.map { r ->
                LivePlaylist.LiveChannel(
                    uuid = r.channel.uuid, name = r.channel.name,
                    number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                    nowTitle = "", nowStart = 0, nowStop = 0
                )
            }
            val favList = srv?.id?.let { Favorites.list(ctx, it) } ?: emptyList()
            // M566: skupina prehravaca = aktualny vyber v zozname (Oblubene / tag / Vsetky)
            val restore = when {
                favOnly && favList.isNotEmpty() -> LivePlaylist.GROUP_FAV
                selectedTag != null && grps.any { it.key == selectedTag } -> selectedTag
                else -> null
            }
            LivePlaylist.setChannels(full, grps, restore, favs = favList, hidden = hiddenList)
        }
    }

    // Klik na tab Kanaly (aj uz vybrany) vrati obrazovku na zaciatok.
    // Prve (inicialne) spustenie preskocime, nech sa nezhasne mriezka otvorena cez EPG.
    var resetInitDone by remember { mutableStateOf(false) }
    LaunchedEffect(resetSignal) {
        if (!resetInitDone) { resetInitDone = true; return@LaunchedEffect }
        epgFor = null
        epgDismissedGen = epgSignal
        TabController.epgFromPlayer = false
        selectedTag = null
        LastTag.set(ctx, serverId, false, null)   // M505: reset = aj zabudnut
        favOnly = false
        contextRow = null
        recChoice = null
        vm.setQuery("")
    }

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

    val st0 = state
    if (showGrid) {
        if (st0 is ChannelsState.Loaded) {
            EpgGridScreen(
                allRows = st0.allRows,
                categories = st0.categories,
                seed = epgMap,
                onBack = {
                    if (TabController.epgFromPlayer) {
                        val uuid = TabController.epgReturnUuid
                        TabController.epgFromPlayer = false
                        TabController.epgReturnUuid = null
                        if (uuid != null) {
                            LivePlaylist.setIndexForUuid(uuid)
                            val title = LivePlaylist.channels.firstOrNull { it.uuid == uuid }?.name ?: ""
                            val pi = android.content.Intent(ctx, PlayerActivity::class.java).apply {
                                putExtra(PlayerActivity.EXTRA_UUID, uuid)
                                putExtra(PlayerActivity.EXTRA_TITLE, title)
                            }
                            // mriezku necham zobrazenu, skryje sa az po navrate z prehravaca
                            pendingGridDismiss = true
                            runCatching { ctx.startActivity(pi) }
                        } else {
                            epgDismissedGen = epgSignal
                        }
                    } else {
                        epgDismissedGen = epgSignal
                    }
                }
            )
        } else {
            // kym sa kanaly nacitaju, nech sa neblikne zoznam
            androidx.compose.foundation.layout.Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) { androidx.compose.material3.CircularProgressIndicator() }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TvSearchBar(
                query = query,
                placeholder = stringResource(R.string.search_channels),
                onQueryChange = { vm.setQuery(it) },
                focusRequester = searchFocus,
                onUp = onGoToNav,
                modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.IconButton(onClick = { TabController.openEpgGrid() }) {
                androidx.compose.material3.Icon(
                    Icons.Default.CalendarViewDay,
                    contentDescription = stringResource(R.string.tv_guide)
                )
            }
            // M529: obnov aj zoznam nahravok, nech sa prekresli cervena bodka
            androidx.compose.material3.IconButton(onClick = { vm.load(true); dvrVm.refresh() }) {
                androidx.compose.material3.Icon(
                    Icons.Default.Refresh,
                    contentDescription = stringResource(R.string.retry)
                )
            }
            Box {
                androidx.compose.material3.IconButton(onClick = { viewMenu = true }) {
                    androidx.compose.material3.Icon(
                        when (viewMode) {
                            ChannelViewMode.LIST -> Icons.AutoMirrored.Filled.ViewList
                            ChannelViewMode.GRID -> Icons.Default.GridView
                            ChannelViewMode.TILES -> Icons.Default.ViewModule
                        },
                        contentDescription = stringResource(R.string.view_mode)
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = viewMenu,
                    onDismissRequest = { viewMenu = false }
                ) {
                    androidx.compose.material3.DropdownMenuItem(
                        text = { Text(stringResource(R.string.view_list)) },
                        leadingIcon = { androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ViewList, null) },
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
            is ChannelsState.Loading -> LoadingStatus()
            is ChannelsState.NoServer -> NoServerStatus()
            is ChannelsState.Error -> ErrorStatus(s.message, onRetry = { vm.load(true) })
            is ChannelsState.Loaded -> {
                if (query.isNotBlank()) {
                    // Vyhladavanie: plochy filtrovany zoznam
                    val q = query.trim().lowercase()
                    val results = s.allRows.filter { it.channel.name.lowercase().contains(q) }
                    ChannelView(viewMode, results, listStateSearch, nowTick, epgMap, recordingFor, onRecordingTap = { r, rec -> recChoice = r to rec }, onTopUp = { runCatching { searchFocus.requestFocus() } }, onShowEpg = { contextRow = it }, lockTick = lockTick, hiddenTick = hiddenTick)
                } else {
                    // Filtre podla tagov
                    val tags = s.categories.mapNotNull { it.tag }
                    // M505: ulozena skupina uz na serveri nemusi byt -> spadni na „vsetky"
                    if (selectedTag != null && tags.none { it.uuid == selectedTag }) selectedTag = null
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item("fav") {
                            FilterChip(
                                selected = favOnly,
                                onClick = {
                                    favOnly = true
                                    LastTag.set(ctx, serverId, false, LastTag.FAV)   // M541
                                },
                                label = { Text("\u2605 " + stringResource(R.string.favorites)) }
                            )
                        }
                        item("all") {
                            FilterChip(
                                selected = !favOnly && selectedTag == null,
                                onClick = {
                                    favOnly = false; selectedTag = null
                                    LastTag.set(ctx, serverId, false, null)   // M505
                                },
                                label = { Text(stringResource(R.string.all_channels)) }
                            )
                        }
                        items(tags, key = { it.uuid }) { tag ->
                            FilterChip(
                                selected = !favOnly && selectedTag == tag.uuid,
                                onClick = {
                                    favOnly = false; selectedTag = tag.uuid
                                    LastTag.set(ctx, serverId, false, tag.uuid)   // M505
                                },
                                label = { Text(tag.name) }
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    val favs = remember(favTick, serverId) {
                        if (serverId != null) Favorites.list(ctx, serverId) else emptyList()
                    }
                    val rows = when {
                        // M541: v poradi oblubenych a cislovane 1..n
                        favOnly -> favs.mapNotNull { u -> s.allRows.firstOrNull { it.channel.uuid == u } }
                            .mapIndexed { i, r -> r.copy(channel = r.channel.copy(number = i + 1)) }
                        // M533: „Vsetky" musi byt zotriedene podla CISLA kanala.
                        // Doteraz sa kategorie len spojili za sebou, takze poradie
                        // urcovalo poradie tagov — kanal s inym tagom skoncil mimo
                        // svojho miesta (napr. 155 a 156 az za 167).
                        selectedTag == null ->
                            s.categories.flatMap { it.rows }
                                .distinctBy { it.channel.uuid }
                                .sortedWith(
                                    compareBy(
                                        { it.channel.number ?: Int.MAX_VALUE },
                                        { it.channel.name.lowercase() }
                                    )
                                )
                        else ->
                            s.categories.firstOrNull { it.tag?.uuid == selectedTag }?.rows ?: emptyList()
                    }
                    // M571: skok na naposledy sledovany kanal je vec dialkoveho ovladaca —
                    // na TV na nom musi byt fokus. Na dotykovom zariadeni to pouzivatel vidi
                    // ako „zoznam sa otvara niekde v strede / na konci", preto tam zacina hore.
                    val focusUuid = remember(rows, serverId) {
                        if (isTvDeviceCtx(ctx))
                            LastChannel.get(ctx, serverId)?.takeIf { u -> rows.any { it.channel.uuid == u } }
                                ?: rows.firstOrNull()?.channel?.uuid
                        else null
                    }
                    // M560: v skupine Oblubene na dotykovom zariadeni sa da poradie menit tahanim
                    // za rukovat; uklada sa hned (Favorites.move, rovnake data ako TV rezim)
                    val moveFav: ((Int, Int) -> Unit)? =
                        if (favOnly && serverId != null && !isTvDeviceCtx(ctx)) { from, to ->
                            Favorites.move(ctx, serverId, from, to); favTick++
                        } else null
                    val favMarks = remember(favs, favOnly) { if (favOnly) emptySet() else favs.toSet() }   // M564
                    ChannelView(viewMode, rows, listStateMain, nowTick, epgMap, recordingFor, onRecordingTap = { r, rec -> recChoice = r to rec }, focusUuid = focusUuid, onTopUp = { runCatching { searchFocus.requestFocus() } }, onShowEpg = { contextRow = it }, lockTick = lockTick, hiddenTick = hiddenTick, onMove = moveFav, favUuids = favMarks)
                }
            }
        }
    }

    // Kanal sa nahrava — vyber: naživo alebo od zaciatku (prebiehajuca nahravka)
    val rc = recChoice
    if (rc != null) {
        val (rcRow, rcRec) = rc
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { recChoice = null },
            title = {
                Text(rcRow.channel.name, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            },
            text = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(rcRec.title, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.channel_archived),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Voicemail, contentDescription = null,
                            tint = Color(0xFFE53935), modifier = Modifier.size(18.dp).scale(scaleX = 1f, scaleY = -1f)
                        )
                    }
                }
            },
            // nazivo vlavo (fokus), od zaciatku vpravo — roztiahnute na celu sirku
            confirmButton = {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    val liveFocus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { runCatching { liveFocus.requestFocus() } }
                    androidx.compose.material3.TextButton(
                        onClick = { playChannel(ctx, rcRow, null, 0, 0); recChoice = null },
                        modifier = Modifier.focusRequester(liveFocus)
                    ) { AutoSizeText(stringResource(R.string.play_live), maxLines = 1) }
                    androidx.compose.material3.TextButton(onClick = {
                        val (_, ns, ne) = currentNow(rcRow, epgMap[rcRow.channel.uuid], System.currentTimeMillis() / 1000)
                        playDvrFile(ctx, rcRec, ns, ne); recChoice = null
                    }) { AutoSizeText(stringResource(R.string.play_from_start), maxLines = 1) }
                }
            }
        )
    }

    // Kontextove menu kanala (dlhy klik): Program / Oblubene / Profil / Zamok
    val cr = contextRow
    if (cr != null && serverId != null) {
        val dlgServer = remember { Tvh.store.active() }
        val loader = remember(dlgServer?.id) { PiconImageLoader.get(ctx, dlgServer) }
        val isFav = remember(favTick) { Favorites.isFav(ctx, serverId, cr.channel.uuid) }
        val isLocked = remember(lockTick) {
            ParentalLock.isChannelLocked(ctx, serverId, cr.channel.uuid)
        }
        val isHidden = remember(hiddenTick) {
            HiddenChannels.isHidden(ctx, serverId, cr.channel.uuid)
        }
        ChannelActionDialog(
            channelName = cr.channel.name,
            isFav = isFav,
            isLocked = isLocked,
            isHidden = isHidden,
            lockEnabled = ParentalLock.isEnabled(ctx),
            piconUrl = cr.piconUrl,
            piconLoader = loader,
            onProgram = { epgFor = cr; contextRow = null },
            onToggleFav = {
                Favorites.toggle(ctx, serverId, cr.channel.uuid); favTick++; contextRow = null
            },
            onToggleLock = {
                val uuid = cr.channel.uuid
                val doToggle: () -> Unit = {
                    ParentalLock.setChannelLocked(ctx, serverId, uuid, !isLocked); lockTick++
                }
                contextRow = null
                if (ParentalLock.needsPin(ctx)) pinAction = doToggle else doToggle()
            },
            onToggleHide = {
                HiddenChannels.setHidden(ctx, serverId, cr.channel.uuid, !isHidden)
                hiddenTick++; contextRow = null
            },
            onDismiss = { contextRow = null }
        )
    }

    // PIN dialog (zamykanie kanala / odomknutie pred chranenou akciou)
    val pa = pinAction
    if (pa != null) {
        PinDialog(
            title = stringResource(R.string.plock_enter),
            onDismiss = { pinAction = null },
            onComplete = { pin ->
                if (ParentalLock.checkPin(ctx, pin)) {
                    pinAction = null
                    pa()
                    true
                } else false
            }
        )
    }

}

@Composable
fun ChannelActionDialog(
    channelName: String,
    isFav: Boolean,
    isLocked: Boolean,
    isHidden: Boolean,
    lockEnabled: Boolean,
    onProgram: () -> Unit,
    onToggleFav: () -> Unit,
    onToggleLock: () -> Unit,
    onToggleHide: () -> Unit,
    onDismiss: () -> Unit,
    piconUrl: String? = null,
    piconLoader: coil.ImageLoader? = null,
) {
    if (isModernUi()) {
        // moderny variant: hlavicka s piconom + riadky s ikonami v kruzkoch
        // (rovnaky jazyk ako "Viac" panel v prehravaci); klasik nizsie nedotknuty
        val cs = MaterialTheme.colorScheme
        androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
                color = if (isLightTheme()) cs.surfaceContainerLowest else cs.surfaceContainer,
                tonalElevation = 6.dp
            ) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                    ) {
                        Box(
                            Modifier.size(52.dp, 42.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(piconBackground()),
                            contentAlignment = Alignment.Center
                        ) {
                            if (piconUrl != null && piconLoader != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(LocalContext.current).data(piconUrl).size(140).build(),
                                    contentDescription = null,
                                    imageLoader = piconLoader,
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(3.dp)
                                )
                            } else {
                                Text(channelName.take(3).uppercase(),
                                    style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(channelName, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(4.dp))
                    ModernActionRow(Icons.Default.CalendarViewDay,
                        stringResource(R.string.menu_program), onProgram)
                    ModernActionRow(Icons.Default.Star,
                        if (isFav) stringResource(R.string.fav_remove) else stringResource(R.string.fav_add),
                        onToggleFav)
                    if (lockEnabled) {
                        ModernActionRow(Icons.Filled.Lock,
                            if (isLocked) stringResource(R.string.plock_unlock) else stringResource(R.string.plock_lock),
                            onToggleLock)
                    }
                    ModernActionRow(Icons.Default.VisibilityOff,
                        if (isHidden) stringResource(R.string.ch_unhide) else stringResource(R.string.ch_hide),
                        onToggleHide)
                }
            }
        }
        return
    }
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
                if (lockEnabled) {
                    ActionRow(
                        if (isLocked) stringResource(R.string.plock_unlock) else stringResource(R.string.plock_lock),
                        onToggleLock
                    )
                }
                ActionRow(
                    if (isHidden) stringResource(R.string.ch_unhide) else stringResource(R.string.ch_hide),
                    onToggleHide
                )
            }
        }
    }
}

@Composable
private fun ModernActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .dpadFocusable(RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(36.dp).clip(androidx.compose.foundation.shape.CircleShape)
                .background(cs.primaryContainer.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(icon, contentDescription = null,
                tint = cs.onSurface, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("\u203A", color = cs.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
fun ActionRow(label: String, onClick: () -> Unit) {
    Text(
        label,
        modifier = Modifier
            .fillMaxWidth()
            .dpadFocusable()
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 14.dp),
        style = MaterialTheme.typography.bodyLarge
    )
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
        putExtra(
            PlayerActivity.EXTRA_REQUIRE_PIN,
            ParentalLock.channelNeedsPin(context, Tvh.store.active()?.id, row.channel.uuid)
        )
    }
    LivePlaylist.setIndexForUuid(row.channel.uuid)
    LastChannel.set(context, Tvh.store.active()?.id, row.channel.uuid)
    context.startActivity(intent)
}

/** Prehratie prebiehajucej/dokoncenej nahravky od zaciatku cez /dvrfile/<uuid>. */
private fun playDvrFile(
    context: android.content.Context,
    rec: sk.tvhclient.shared.model.DvrEntry,
    progStart: Long = 0,
    progStop: Long = 0
) {
    val server = Tvh.store.active() ?: return
    val url = Tvh.dvrUrl(server, rec.uuid)
    // Hranice relacie: primarne z EPG (spolahlive), fallback na hranice z nahravky
    // (tie byvaju pri prebiehajucom archive prazdne/neuplne). Dopocitavanie pocitame
    // relativne k ZACIATKU RELACIE, nie k realnemu zaciatku suboru.
    val pStart = if (progStart > 0) progStart else rec.start
    val pStop = if (progStop > progStart && progStop > 0) progStop else rec.stop
    val nowSec = System.currentTimeMillis() / 1000
    val inProgress = pStart > 0 && nowSec < pStop
    val intent = android.content.Intent(context, PlayerActivity::class.java).apply {
        putExtra(PlayerActivity.EXTRA_URL, url)
        putExtra(PlayerActivity.EXTRA_TITLE, rec.title)
        putExtra(PlayerActivity.EXTRA_DURATION_MS, rec.durationSec * 1000)
        putExtra(PlayerActivity.EXTRA_DVR_UUID, rec.uuid)
        putExtra(PlayerActivity.EXTRA_DVR_RECORDING, inProgress)
        putExtra(PlayerActivity.EXTRA_DVR_PROG_START_SEC, pStart)
        putExtra(PlayerActivity.EXTRA_DVR_PROG_STOP_SEC, pStop)
        putExtra(PlayerActivity.EXTRA_DVR_REAL_START_SEC, rec.realStartSec)
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
    recordingFor: (ChannelRow) -> sk.tvhclient.shared.model.DvrEntry?,
    onRecordingTap: (ChannelRow, sk.tvhclient.shared.model.DvrEntry) -> Unit,
    focusUuid: String? = null,
    onTopUp: () -> Unit = {},
    onShowEpg: (ChannelRow) -> Unit,
    lockTick: Int = 0,
    hiddenTick: Int = 0,
    onMove: ((Int, Int) -> Unit)? = null,   // M560: presun tahanim (len Oblubene, dotyk)
    favUuids: Set<String> = emptySet()      // M564: hviezdicka pri oblubenych mimo skupiny Oblubene
) {
    when (mode) {
        ChannelViewMode.LIST -> ChannelList(rows, listState, nowSec, epgMap, recordingFor, onRecordingTap, focusUuid, onTopUp, onShowEpg, lockTick, hiddenTick, onMove, favUuids)
        ChannelViewMode.GRID -> ChannelGrid(rows, nowSec, epgMap, columns = 2, recordingFor, onRecordingTap, onShowEpg, favUuids)
        ChannelViewMode.TILES -> ChannelGrid(rows, nowSec, epgMap, columns = 4, recordingFor, onRecordingTap, onShowEpg, favUuids)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelGrid(
    rows: List<ChannelRow>,
    nowSec: Long,
    epgMap: Map<String, List<sk.tvhclient.shared.model.EpgEvent>>,
    columns: Int,
    recordingFor: (ChannelRow) -> sk.tvhclient.shared.model.DvrEntry?,
    onRecordingTap: (ChannelRow, sk.tvhclient.shared.model.DvrEntry) -> Unit,
    onShowEpg: (ChannelRow) -> Unit,
    favUuids: Set<String> = emptySet()   // M564
) {
    val context = LocalContext.current
    val server = remember { Tvh.store.active() }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }
    if (rows.isEmpty()) {
        EmptyStatus(stringResource(R.string.no_channels))
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
            val rec = recordingFor(row)
            Column(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0x14FFFFFF))
                    .dpadFocusable()
                    .combinedClickable(
                        onClick = {
                            if (rec != null) onRecordingTap(row, rec)
                            else playChannel(context, row, nowTitle, nowStart, nowStop)
                        },
                        onLongClick = { onShowEpg(row) }
                    )
                    .padding(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    Modifier.size(if (tiles) 64.dp else 72.dp, if (tiles) 44.dp else 48.dp)
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
                            modifier = Modifier.fillMaxSize().padding(2.dp)
                        )
                    } else {
                        Text(row.channel.name.take(3).uppercase(),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    if (rec != null) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(10.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(Color(0xFFE53935))
                        )
                    }
                    if (row.channel.uuid in favUuids) {   // M564
                        androidx.compose.material3.Icon(
                            Icons.Filled.Star, contentDescription = null, tint = Color(0xFFFBBF24),
                            modifier = Modifier.align(Alignment.TopStart).padding(2.dp).size(12.dp)
                        )
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
    recordingFor: (ChannelRow) -> sk.tvhclient.shared.model.DvrEntry?,
    onRecordingTap: (ChannelRow, sk.tvhclient.shared.model.DvrEntry) -> Unit,
    focusUuid: String? = null,
    onTopUp: () -> Unit = {},
    onShowEpg: (ChannelRow) -> Unit,
    lockTick: Int = 0,
    hiddenTick: Int = 0,
    onMove: ((Int, Int) -> Unit)? = null,
    favUuids: Set<String> = emptySet()
) {
    val context = LocalContext.current
    val server = remember { Tvh.store.active() }
    val loader = remember(server?.id) { PiconImageLoader.get(context, server) }

    if (rows.isEmpty()) {
        EmptyStatus(stringResource(R.string.no_channels))
        return
    }
    // M560: stav tahania — uuid tahaneho riadka + posun prsta voci jeho aktualnej pozicii.
    // Riadky su klucovane uuid, takze pri vymene poradia sa composable (aj bezici gesture)
    // presunie s riadkom; posun sa po kazdej vymene zmensi o vysku riadka.
    var dragUuid by remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var dragOffset by remember { androidx.compose.runtime.mutableStateOf(0f) }
    val dragScope = androidx.compose.runtime.rememberCoroutineScope()
    // Pociatocny focus na posledny zvoleny (alebo prvy) kanal -> nech sa pri
    // starte neoznaci vyhladavacie pole a nevyskoci klavesnica.
    val firstFocus = remember { FocusRequester() }
    val jumpFocus = remember { FocusRequester() }
    var jumpTarget by remember { androidx.compose.runtime.mutableStateOf(-1) }
    val targetIndex = remember(rows, focusUuid) {
        (focusUuid?.let { u -> rows.indexOfFirst { it.channel.uuid == u } } ?: 0).coerceAtLeast(0)
    }
    var didFocus by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(rows) {
        if (!didFocus && rows.isNotEmpty()) {
            listState.scrollToItem(targetIndex)
            runCatching { firstFocus.requestFocus() }
            didFocus = true
        }
    }
    // Skok na index (wrap hore/dole, posun po 5): najprv doscrolluj, pockaj
    // snimku nech sa polozka vytvori, az potom ju zameraj.
    LaunchedEffect(jumpTarget) {
        val t = jumpTarget
        if (t >= 0) {
            listState.scrollToItem(t)
            androidx.compose.runtime.withFrameNanos { }
            runCatching { jumpFocus.requestFocus() }
            jumpTarget = -1
        }
    }
    val last = rows.lastIndex
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        itemsIndexed(rows, key = { _, it -> it.channel.uuid }) { idx, row ->
            val focusMod = when {
                jumpTarget >= 0 && idx == jumpTarget -> Modifier.focusRequester(jumpFocus)
                idx == targetIndex -> Modifier.focusRequester(firstFocus)
                else -> Modifier
            }
            val keyMod = focusMod.onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    // Na 1. kanali hore -> skoc na vyhladavacie pole (a odtial
                    // dalsim hore na spodne menu). Dole na poslednom -> spodne menu.
                    android.view.KeyEvent.KEYCODE_DPAD_UP ->
                        if (idx == 0) { onTopUp(); true } else false
                    android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                        // -5; ak uz si na zaciatku, skoc na koniec (wrap)
                        jumpTarget = if (idx == 0) last else (idx - 5).coerceAtLeast(0)
                        true
                    }
                    android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        // +5; ak uz si na konci, skoc na zaciatok (wrap)
                        jumpTarget = if (idx == last) 0 else (idx + 5).coerceAtMost(last)
                        true
                    }
                    else -> false
                }
            }
            if (onMove == null) {
                ChannelItem(row, loader, context, nowSec, epgMap[row.channel.uuid],
                    recordingFor(row), onRecordingTap, onShowEpg,
                    itemModifier = keyMod, lockTick = lockTick, hiddenTick = hiddenTick,
                    favorite = row.channel.uuid in favUuids)
            } else {
                // M560: riadok + rukovat na tahanie; tahany riadok nadvihnuty (posun, tien, okraj)
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
                        ChannelItem(row, loader, context, nowSec, epgMap[row.channel.uuid],
                            recordingFor(row), onRecordingTap, onShowEpg,
                            itemModifier = keyMod, lockTick = lockTick, hiddenTick = hiddenTick,
                            favorite = row.channel.uuid in favUuids)
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
                                        // vymena so susedom, ked je riadok prevlecený cez polovicu jeho vysky
                                        if (dragOffset > h / 2f && cur < rows.lastIndex) {
                                            onMove(cur, cur + 1); dragOffset -= h
                                        } else if (dragOffset < -h / 2f && cur > 0) {
                                            onMove(cur, cur - 1); dragOffset += h
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
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ChannelItem(
    row: ChannelRow,
    loader: coil.ImageLoader,
    context: android.content.Context,
    nowSec: Long,
    epgList: List<sk.tvhclient.shared.model.EpgEvent>?,
    recording: sk.tvhclient.shared.model.DvrEntry?,
    onRecordingTap: (ChannelRow, sk.tvhclient.shared.model.DvrEntry) -> Unit,
    onShowEpg: (ChannelRow) -> Unit,
    itemModifier: Modifier = Modifier,
    lockTick: Int = 0,
    hiddenTick: Int = 0,
    favorite: Boolean = false   // M564
) {
    val (curTitle, curStart, curStop) = currentNow(row, epgList, nowSec)
    val locked = remember(lockTick, row.channel.uuid) {
        ParentalLock.isChannelLocked(context, Tvh.store.active()?.id, row.channel.uuid)
    }
    val hidden = remember(hiddenTick, row.channel.uuid) {
        HiddenChannels.isHidden(context, Tvh.store.active()?.id, row.channel.uuid)
    }
    if (isModernUi()) {
        // moderny riadok: karta s "Dalej:" a minutami — zdielany komponent;
        // klasicky riadok nizsie ostava nedotknuty
        val next = remember(epgList, curStop, nowSec) {
            epgList?.firstOrNull {
                it.start >= (if (curStop > 0) curStop else nowSec) && it.title.isNotBlank()
            }?.title
        }
        ModernChannelTabRow(
            name = row.channel.name,
            number = row.channel.number,
            piconUrl = row.piconUrl,
            nowTitle = curTitle,
            nowStart = curStart,
            nowStop = curStop,
            nextTitle = next,
            nowSec = nowSec,
            recording = recording != null,
            locked = locked,
            hidden = hidden,
            loader = loader,
            onClick = {
                if (recording != null) onRecordingTap(row, recording)
                else playChannel(context, row, curTitle, curStart, curStop)
            },
            onLongClick = { onShowEpg(row) },
            modifier = itemModifier,
            favorite = favorite,   // M564
        )
        return
    }
    Row(
        modifier = itemModifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .dpadFocusable()
            .combinedClickable(
                onClick = {
                    if (recording != null) onRecordingTap(row, recording)
                    else playChannel(context, row, curTitle, curStart, curStop)
                },
                onLongClick = { onShowEpg(row) }
            )
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(56.dp, 40.dp)
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
                if (recording != null) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(Color(0xFFE53935))
                    )
                }
                if (favorite) {
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Star, contentDescription = null,
                        tint = Color(0xFFFBBF24), modifier = Modifier.size(16.dp)   // M564
                    )
                }
                if (locked) {
                    Spacer(Modifier.width(6.dp))
                    androidx.compose.material3.Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                }
                if (hidden) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "\uD83D\uDEAB",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

/** M560: TV (leanback) zariadenie — tahanie prstom nema zmysel, poradie oblubenych sa tam meni v prehravaci. */
internal fun isTvDeviceCtx(ctx: android.content.Context): Boolean {
    val um = ctx.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
    return um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
}
