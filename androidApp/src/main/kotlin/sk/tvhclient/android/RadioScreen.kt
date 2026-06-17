package sk.tvhclient.android

import android.content.Intent
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
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
        OutlinedTextField(
            value = query,
            onValueChange = { vm.setQuery(it) },
            label = { Text(stringResource(R.string.search_channels)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))

        Box(Modifier.fillMaxSize()) {
            when (val s = state) {
                is RadioState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is RadioState.NoServer -> Text(
                    stringResource(R.string.no_active_server), Modifier.align(Alignment.Center)
                )
                is RadioState.Error -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(s.message, color = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.height(12.dp))
                    androidx.compose.material3.Button(onClick = { vm.load() }) {
                        Text(stringResource(R.string.retry))
                    }
                }
                is RadioState.Loaded -> {
                    val q = query.trim().lowercase()
                    val rows = if (q.isBlank()) s.rows
                               else s.rows.filter { it.channel.name.lowercase().contains(q) }
                    if (rows.isEmpty()) {
                        Text(stringResource(R.string.radio_empty), Modifier.align(Alignment.Center))
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                            items(rows, key = { it.channel.uuid }) { row ->
                                RadioRow(row, loader, context, onContext = { contextRow = it })
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
    loader: coil.ImageLoader,
    context: android.content.Context,
    onContext: (ChannelRow) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    val server = Tvh.store.active() ?: return@combinedClickable
                    val prof = ChannelPrefs.getProfile(context, server.id, row.channel.uuid)
                        .ifBlank { server.profile.ifBlank { "pass" } }
                    val url = Tvh.liveUrl(server, row.channel.uuid, row.channel.name, prof)
                    val intent = Intent(context, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_URL, url)
                        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
                    }
                    context.startActivity(intent)
                },
                onLongClick = { onContext(row) }
            )
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 56.dp, height = 40.dp), contentAlignment = Alignment.Center) {
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
    }
}
