package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.formatDayLabel
import sk.tvhclient.shared.formatTimeHm
import sk.tvhclient.shared.model.DvrClassifier
import sk.tvhclient.shared.model.DvrEntry

@Composable
fun DvrScreen(vm: DvrViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    var toDelete by remember { mutableStateOf<DvrEntry?>(null) }
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.load() }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is DvrState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is DvrState.NoServer -> Text(
                stringResource(R.string.no_active_server),
                Modifier.align(Alignment.Center)
            )
            is DvrState.Error -> Text(
                s.message, Modifier.align(Alignment.Center),
                color = MaterialTheme.colorScheme.error
            )
            is DvrState.Loaded -> {
                if (s.groups.isEmpty()) {
                    Text(stringResource(R.string.dvr_empty), Modifier.align(Alignment.Center))
                } else {
                    DvrList(s.groups, context, onDelete = { toDelete = it })
                }
            }
        }
    }

    val del = toDelete
    if (del != null) {
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text(del.title) },
            text = { Text(stringResource(R.string.dvr_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(del.uuid)
                    toDelete = null
                }) { Text(stringResource(R.string.dvr_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { toDelete = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

@Composable
private fun DvrList(
    groups: List<DvrGroup>,
    context: Context,
    onDelete: (DvrEntry) -> Unit
) {
    LazyColumn(Modifier.fillMaxSize()) {
        groups.forEach { group ->
            item(key = "cat_${group.categoryKey}") {
                Text(
                    catLabel(group.categoryKey),
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            items(group.entries, key = { it.uuid }) { entry ->
                DvrRow(entry, context, onDelete)
            }
        }
    }
}

@Composable
private fun DvrRow(entry: DvrEntry, context: Context, onDelete: (DvrEntry) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val server = Tvh.store.active() ?: return@clickable
                val url = Tvh.dvrUrl(server, entry.uuid)
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, url)
                    putExtra(PlayerActivity.EXTRA_TITLE, entry.title)
                }
                context.startActivity(intent)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                entry.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            val meta = buildString {
                if (entry.channelName.isNotBlank()) append(entry.channelName)
                if (entry.start > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append(formatDayLabel(entry.start))
                    append(" ")
                    append(formatTimeHm(entry.start))
                }
                val mins = entry.durationSec / 60
                if (mins > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("$mins min")
                }
            }
            if (meta.isNotBlank()) {
                Text(
                    meta, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            "\uD83D\uDDD1",
            Modifier
                .clickable { onDelete(entry) }
                .padding(8.dp)
        )
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
