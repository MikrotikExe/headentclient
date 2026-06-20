package sk.tvhclient.android

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.currentTimeSeconds
import sk.tvhclient.shared.formatDayLabel
import sk.tvhclient.shared.formatTimeHm
import sk.tvhclient.shared.model.EpgEvent

private sealed class EpgState {
    data object Loading : EpgState()
    data class Loaded(val events: List<EpgEvent>) : EpgState()
    data class Error(val message: String) : EpgState()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpgScreen(channelUuid: String, channelName: String, onBack: () -> Unit) {
    var state by remember { mutableStateOf<EpgState>(EpgState.Loading) }
    var detail by remember { mutableStateOf<EpgEvent?>(null) }

    // Systemove tlacidlo Spat zavrie EPG (navrat na zoznam), nie celu appku
    BackHandler { onBack() }

    // Detail relacie ako samostatna obrazovka
    val sel = detail
    if (sel != null) {
        EpgDetailScreen(event = sel, onBack = { detail = null })
        return
    }

    val noActiveServerMsg = stringResource(R.string.no_active_server)
    LaunchedEffect(channelUuid) {
        state = EpgState.Loading
        val server = Tvh.store.active()
        if (server == null) {
            state = EpgState.Error(noActiveServerMsg)
            return@LaunchedEffect
        }
        try {
            val events = withContext(Dispatchers.IO) {
                val api = Tvh.apiFor(server)
                try { Tvh.fetchEpgForChannel(server, api, channelUuid) } finally { api.close() }
            }
            state = EpgState.Loaded(events)
        } catch (e: Exception) {
            state = EpgState.Error(e.message ?: "Chyba")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(channelName) },
                navigationIcon = {
                    Text(
                        "  \u2715  ",
                        modifier = Modifier
                            .padding(8.dp)
                            .clickable { onBack() },
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is EpgState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is EpgState.Error -> Text(
                    s.message,
                    Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.error
                )
                is EpgState.Loaded -> EpgList(s.events) { detail = it }
            }
        }
    }
}

@Composable
private fun EpgList(events: List<EpgEvent>, onClick: (EpgEvent) -> Unit) {
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.no_program))
        }
        return
    }
    val now = currentTimeSeconds()

    // Zoskupenie po dnoch (label podla zaciatku)
    val grouped = events.groupBy { formatDayLabel(it.start) }

    LazyColumn(Modifier.fillMaxSize()) {
        grouped.forEach { (day, dayEvents) ->
            item(key = "day_$day") {
                Text(
                    day,
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            items(dayEvents, key = { it.eventId ?: it.start }) { ev ->
                EpgRow(ev, isNow = ev.start <= now && now < ev.stop, onClick = { onClick(ev) })
            }
        }
    }
}

@Composable
private fun EpgRow(ev: EpgEvent, isNow: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .background(if (isNow) Color(0x22FFFFFF) else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            formatTimeHm(ev.start),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isNow) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ev.title.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isNow) FontWeight.Bold else FontWeight.Normal
            )
            val sub = ev.subtitle.ifBlank { ev.description }
            if (sub.isNotBlank()) {
                Text(
                    sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2
                )
            }
        }
    }
}

