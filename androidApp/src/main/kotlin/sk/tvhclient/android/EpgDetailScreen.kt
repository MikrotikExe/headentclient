package sk.tvhclient.android

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.material3.Button

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

    // ---- M472: nahravanie programu ----
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var canRecord by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var recording by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var recMessage by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var server by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<sk.tvhclient.shared.model.TvhServer?>(null)
    }

    // Prava zistime raz pri otvoreni; ak ich server neoznami, tlacidlo ukazeme
    // a pripadnu chybu zobrazime az z odpovede.
    androidx.compose.runtime.LaunchedEffect(event.eventId) {
        val s = sk.tvhclient.shared.Tvh.store.active()
        server = s
        canRecord = if (s == null || event.eventId == null) false
        else DvrController.access(s).canRecord
    }

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

            // M472: nahravanie — len ak ma pouzivatel pravo a ide o buduci program
            val nowSec = System.currentTimeMillis() / 1000
            if (canRecord && event.eventId != null && event.stop > nowSec) {
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = {
                        val s = server ?: return@Button
                        val eid = event.eventId ?: return@Button
                        recording = true
                        recMessage = null
                        scope.launch {
                            val r = DvrController.recordEvent(s, eid)
                            recording = false
                            recMessage = if (r.success)
                                ctx.getString(R.string.dvr_rec_scheduled)
                            else r.error ?: ctx.getString(R.string.dvr_rec_failed)
                        }
                    },
                    enabled = !recording,
                    modifier = Modifier.dpadFocusable()
                ) {
                    Text(
                        if (recording) stringResource(R.string.dvr_rec_working)
                        else stringResource(R.string.dvr_rec_button)
                    )
                }
                recMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(12.dp))
            }

            // Plny popis
            val desc = event.bestDescription
            Text(
                desc.ifBlank { stringResource(R.string.epg_no_description) },
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
