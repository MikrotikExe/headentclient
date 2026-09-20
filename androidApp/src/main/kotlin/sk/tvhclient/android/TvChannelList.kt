package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Voicemail
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sk.tvhclient.shared.model.EpgEvent
import sk.tvhclient.shared.model.TvhServer

/**
 * M632: channel list in the player on TV — a full-screen scrim with a hole for the preview
 * of the playing channel ([previewRect], BlendMode.Clear, M266), on the left a list paged by 7
 * or the search results (M370), at the top the date / group pill (M369b) / clock, on the right
 * the detail of the playing channel (EPG via [onLoadChannelEpg]) and further programmes.
 * [onPreviewRect] reports the position of the preview rectangle to the caller (which draws the radio logo into it).
 * Extracted from PlayerUi (PlayerActivity.kt), behaviour unchanged.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TvChannelListOverlay(
    liveChannels: List<LivePlaylist.LiveChannel>,
    server: TvhServer?,
    serverId: String?,
    liveCurrentIndex: Int,
    channelNavIndex: Int,
    epgLoading: Boolean,
    lockTick: Int,
    liveNowSec: Long,
    onLoadChannelEpg: (String, (List<EpgEvent>) -> Unit) -> Unit,
    channelGroupLabel: String,
    channelGroupPicker: Boolean,
    searchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchFieldFocused: Boolean,
    searchHits: List<LivePlaylist.LiveChannel>,
    searchNavIndex: Int,
    searchFocusSignal: Int,
    inPreview: Boolean,
    previewRect: Rect?,
    onPreviewRect: (Rect) -> Unit
) {
    val ctx = LocalContext.current
    val loaderT = remember(server?.id) { PiconImageLoader.get(ctx, server) }
    val selT = channelNavIndex.coerceIn(0, liveChannels.size - 1)
    // paging by 7: show exactly the current group of seven, once past it the next one flips in
    val pageSizeT = 7
    val pageStartT = (selT / pageSizeT) * pageSizeT
    val pageItemsT = liveChannels.drop(pageStartT).take(pageSizeT)
    // the right panel (EPG, preview, programmes) follows the PLAYING channel — it changes only after switching (OK)
    val detT = liveCurrentIndex.coerceIn(0, liveChannels.size - 1)
    val detUuid = liveChannels.getOrNull(detT)?.uuid
    var epgT by remember { mutableStateOf<List<sk.tvhclient.shared.model.EpgEvent>>(emptyList()) }
    // M370-fix: key on the UUID (not the index) — on a tag change the channel stays the same,
    // so the EPG is not needlessly reloaded.
    LaunchedEffect(detUuid) {
        val uuid = detUuid ?: return@LaunchedEffect
        epgT = emptyList()
        onLoadChannelEpg(uuid) { list -> epgT = list }
    }
    val nowT = liveNowSec
    val curT = epgT.firstOrNull { it.start <= nowT && nowT < it.stop }
    val nextT = epgT.filter { it.start >= nowT }.sortedBy { it.start }.take(4)
    val dateStr = java.text.SimpleDateFormat("EEEE d. MMMM", java.util.Locale.getDefault())
        .format(java.util.Date(nowT * 1000)).replaceFirstChar { it.uppercase() }
    val accentC = playerAccent()
    val borderC = playerBorder()
    val cardC = playerCard()
    val selTintC = playerSelTint()

    val scrimC = playerScrim()
    // M370: focus for the search text field (requested on opening/returning to the field)
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val keyboardCtrl = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    LaunchedEffect(searchFocusSignal, searchFieldFocused, searchActive) {
        if (searchActive && searchFieldFocused) {
            runCatching { searchFocus.requestFocus() }
            kotlinx.coroutines.delay(60)
            runCatching { keyboardCtrl?.show() }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            // M266: the offscreen buffer is expensive and is needed ONLY for BlendMode.Clear
            // (the preview cut-out). Without a preview we do not allocate it -> snappy first open.
            .then(
                if (inPreview)
                    Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                else Modifier
            )
            .drawBehind {
                drawRect(scrimC)
                val r = previewRect
                if (inPreview && r != null)
                    drawRect(
                        androidx.compose.ui.graphics.Color.Transparent,
                        topLeft = androidx.compose.ui.geometry.Offset(r.left, r.top),
                        size = Size(r.width, r.height),
                        blendMode = BlendMode.Clear
                    )
            }
    ) {
        // top bar: date on the left, clock on the right
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(dateStr, color = playerFgDim(), style = MaterialTheme.typography.titleMedium)
            if (searchActive) {
                // M370: search field (system keyboard on TV)
                Spacer(Modifier.width(14.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    singleLine = true,
                    placeholder = {
                        Text(androidx.compose.ui.res.stringResource(R.string.search_channels),
                            color = playerFgDim())
                    },
                    leadingIcon = {
                        androidx.compose.material3.Icon(
                            androidx.compose.material.icons.Icons.Default.Search, null,
                            tint = playerFgDim())
                    },
                    modifier = Modifier.weight(1f).focusRequester(searchFocus)
                )
            } else {
                // M369b: group filter pill in the top bar (does not eat into the list height)
                if (channelGroupLabel.isNotEmpty()) {
                    Spacer(Modifier.width(14.dp))
                    Row(
                        Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (channelGroupPicker) selTintC else cardC)
                            .then(
                                if (channelGroupPicker)
                                    Modifier.border(2.dp, accentC, RoundedCornerShape(16.dp))
                                else Modifier
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (channelGroupPicker) {
                            Text("\u2039", color = accentC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(
                            channelGroupLabel,
                            color = playerFg(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (channelGroupPicker) {
                            Spacer(Modifier.width(8.dp))
                            Text("\u203A", color = accentC, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    // M370-fix2: visible magnifier next to the pill (search opens with the UP arrow)
                    Spacer(Modifier.width(10.dp))
                    androidx.compose.material3.Icon(
                        androidx.compose.material.icons.Icons.Default.Search,
                        contentDescription = null,
                        tint = if (channelGroupPicker) accentC else playerFgDim(),
                        modifier = Modifier.size(22.dp)
                    )
                    if (channelGroupPicker) {
                        Spacer(Modifier.width(6.dp))
                        Text("\u25B2 " + androidx.compose.ui.res.stringResource(R.string.search_channels),
                            color = accentC, style = MaterialTheme.typography.labelMedium)
                    }
                }
                Spacer(Modifier.weight(1f))
            }
            if (epgLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = accentC,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(12.dp))
            }
            Text(fmtClock(nowT), color = playerFg(),
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.fillMaxWidth().weight(1f)) {
            // LEFT: channel list (cards with a border)
            Column(
                modifier = Modifier.fillMaxHeight().fillMaxWidth(0.46f)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
              if (searchActive) {
                // M370: search results (across all channels)
                val hits = searchHits
                if (hits.isEmpty()) {
                    Text(
                        if (searchQuery.isBlank())
                            androidx.compose.ui.res.stringResource(R.string.search_channels)
                        else androidx.compose.ui.res.stringResource(R.string.no_channels),
                        color = playerFgDim(),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp)
                    )
                } else {
                    val lsSearch = rememberLazyListState()
                    LaunchedEffect(searchNavIndex) {
                        runCatching { lsSearch.scrollToItem(searchNavIndex.coerceAtLeast(0)) }
                    }
                    LazyColumn(state = lsSearch, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(hits, key = { _, c -> c.uuid }) { i, ch ->
                            val selRow = i == searchNavIndex && !searchFieldFocused
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (selRow) selTintC else cardC)
                                    .border(1.dp, if (selRow) accentC else borderC, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 12.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(piconBackground()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (ch.piconUrl != null) {
                                        AsyncImage(
                                            model = remember(ch.piconUrl) { ImageRequest.Builder(ctx).data(ch.piconUrl).size(120).build() },
                                            contentDescription = null, imageLoader = loaderT,
                                            contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                            modifier = Modifier.fillMaxSize().padding(3.dp)
                                        )
                                    } else Text(ch.name.take(3).uppercase(), color = playerFg(), style = MaterialTheme.typography.labelMedium)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ch.name, color = playerFg(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                    if (ch.nowTitle.isNotBlank())
                                        Text(ch.nowTitle, color = playerFgDim(), style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Spacer(Modifier.width(8.dp))
                                if (serverId != null && Favorites.isFav(ctx, serverId, ch.uuid)) {
                                    androidx.compose.material3.Icon(
                                        imageVector = androidx.compose.material.icons.Icons.Filled.Star,
                                        contentDescription = null,
                                        tint = Color(0xFFF2C14E),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                }
                                Text(if (ch.number > 0) ch.number.toString() else "", color = accentC,
                                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                }
              } else {
                pageItemsT.forEachIndexed { localIdx, ch ->
                    val idx = pageStartT + localIdx
                    val selRow = idx == selT
                    val lockedRow = remember(lockTick, ch.uuid, serverId) {
                        ParentalLock.isChannelLocked(ctx, serverId, ch.uuid)
                    }
                    val favRow = serverId != null && Favorites.isFav(ctx, serverId, ch.uuid)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (selRow) selTintC else cardC)
                            .border(1.dp, if (selRow) accentC else borderC, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(54.dp, 40.dp).clip(RoundedCornerShape(6.dp)).background(piconBackground()),
                            contentAlignment = Alignment.Center
                        ) {
                            if (ch.piconUrl != null) {
                                AsyncImage(
                                    model = remember(ch.piconUrl) { ImageRequest.Builder(ctx).data(ch.piconUrl).size(120).build() },
                                    contentDescription = null, imageLoader = loaderT,
                                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize().padding(3.dp)
                                )
                            } else Text(ch.name.take(3).uppercase(), color = playerFg(), style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(ch.name, color = playerFg(), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f, fill = false))
                                if (ch.recording) {
                                    Spacer(Modifier.width(6.dp))
                                    Box(Modifier.size(8.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(Color(0xFFE53935)))
                                }
                            }
                            if (ch.nowTitle.isNotBlank())
                                Text(ch.nowTitle, color = playerFgDim(), style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.width(8.dp))
                        if (favRow) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Star,
                                contentDescription = null,
                                tint = Color(0xFFF2C14E),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        if (lockedRow) {
                            androidx.compose.material3.Icon(
                                imageVector = androidx.compose.material.icons.Icons.Filled.Lock,
                                contentDescription = null,
                                tint = playerFgDim(),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        Text(if (ch.number > 0) ch.number.toString() else "", color = accentC,
                            fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    }
                }
              }
            }
            // RIGHT: detail of the selected + preview of the playing + further programmes
            Column(Modifier.fillMaxHeight().weight(1f).padding(horizontal = 22.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        (curT?.title?.takeIf { it.isNotBlank() }) ?: liveChannels.getOrNull(detT)?.nowTitle ?: "",
                        color = playerFg(), style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold, maxLines = 1,
                        modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE)
                    )
                    if (liveChannels.getOrNull(detT)?.recording == true) {
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.Icon(
                            Icons.Default.Voicemail, contentDescription = null,
                            tint = Color(0xFFE53935),
                            modifier = Modifier.size(22.dp).scale(scaleX = 1f, scaleY = -1f)
                        )
                    }
                }
                if (curT != null)
                    Text(fmtClock(curT.start) + " – " + fmtClock(curT.stop), color = accentC,
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 4.dp))
                // preview: live video of the playing channel — the VLC surface shows through the hole in the scrim
                Box(
                    Modifier.padding(top = 12.dp).height(156.dp).aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(10.dp))
                        .onGloballyPositioned { c ->
                            val p = c.positionInRoot()
                            onPreviewRect(androidx.compose.ui.geometry.Rect(
                                p.x, p.y,
                                p.x + c.size.width.toFloat(), p.y + c.size.height.toFloat()
                            ))
                        }
                        .border(1.dp, borderC, RoundedCornerShape(10.dp))
                )
                // description: max 3 lines, clipped
                val desc = curT?.bestDescription ?: ""
                if (desc.isNotBlank())
                    Text(desc, color = playerFgDim(), style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 12.dp))
                // programmes right below the description (natural flow from the top)
                if (nextT.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    nextT.forEach { ev ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(fmtClock(ev.start), color = accentC,
                                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.width(58.dp))
                            Text(ev.title, color = playerFg(), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, modifier = Modifier.weight(1f).basicMarquee(iterations = Int.MAX_VALUE))
                        }
                    }
                }
            }
        }
    }
}
