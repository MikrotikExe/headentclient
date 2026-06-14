package sk.tvhclient.android

import androidx.activity.compose.BackHandler
import androidx.compose.material3.Button
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import sk.tvhclient.shared.formatDayLabel
import sk.tvhclient.shared.formatTimeHm
import sk.tvhclient.shared.model.DvbGenre
import sk.tvhclient.shared.model.EpgEvent

@Composable
fun genreLabel(topNibble: Int): String? {
    val key = DvbGenre.keyFor(topNibble) ?: return null
    val resId = when (key) {
        DvbGenre.FILM -> R.string.genre_film
        DvbGenre.NEWS -> R.string.genre_news
        DvbGenre.SHOW -> R.string.genre_show
        DvbGenre.SPORT -> R.string.genre_sport
        DvbGenre.CHILDREN -> R.string.genre_children
        DvbGenre.MUSIC -> R.string.genre_music
        DvbGenre.ARTS -> R.string.genre_arts
        DvbGenre.SOCIAL -> R.string.genre_social
        DvbGenre.EDUCATION -> R.string.genre_education
        DvbGenre.LEISURE -> R.string.genre_leisure
        else -> return null
    }
    return stringResource(resId)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EpgDetailScreen(event: EpgEvent, onBack: () -> Unit) {
    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(event.title.ifBlank { "—" }, maxLines = 1) },
                navigationIcon = {
                    Text(
                        "  \u2715  ",
                        modifier = Modifier.padding(8.dp).clickable { onBack() },
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            )
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Cas: den + od-do
            val timeLine = buildString {
                if (event.start > 0) {
                    append(formatDayLabel(event.start))
                    append("  ")
                    append(formatTimeHm(event.start))
                    if (event.stop > 0) {
                        append(" – ")
                        append(formatTimeHm(event.stop))
                    }
                }
            }
            if (timeLine.isNotBlank()) {
                Text(timeLine, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
            }

            // Riadok metadat: zaner, vek, epizoda
            val genre = genreLabel(event.dvbGenreTop)
            val meta = buildList {
                if (genre != null) add(genre)
                if (event.episodeOnscreen.isNotBlank()) add(event.episodeOnscreen)
                if (event.ageRating > 0) add(stringResource(R.string.epg_age, event.ageRating))
            }
            if (meta.isNotEmpty()) {
                Text(meta.joinToString("  ·  "),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(12.dp))
            }

            // Podtitul (epizoda nazov)
            if (event.subtitle.isNotBlank()) {
                Text(event.subtitle, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
            }

            // Plny popis
            val desc = event.bestDescription
            Text(
                desc.ifBlank { stringResource(R.string.epg_no_description) },
                style = MaterialTheme.typography.bodyLarge
            )

            // Nahrat (naplanuj nahravku z EPG eventu)
            val eid = event.eventId
            if (eid != null && eid > 0) {
                Spacer(Modifier.height(20.dp))
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val scheduling = remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        scheduling.value = true
                        scope.launch {
                            val server = sk.tvhclient.shared.Tvh.store.active()
                            val ok = if (server != null) {
                                withContext(Dispatchers.IO) {
                                    val api = sk.tvhclient.shared.Tvh.apiFor(server)
                                    try { api.dvrRecordEvent(eid) } finally { api.close() }
                                }
                            } else false
                            scheduling.value = false
                            android.widget.Toast.makeText(
                                context,
                                context.getString(
                                    if (ok) R.string.epg_record_done else R.string.epg_record_fail
                                ),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !scheduling.value
                ) {
                    Text("\u23FA  " + stringResource(R.string.epg_record))
                }
            }
        }
    }
}
