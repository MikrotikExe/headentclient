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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width

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
    // M484: chybu odlisime farbou od potvrdenia
    var recOk by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(true) }
    // M484: ta ista logika ako v mriezke — ak nahravka uz existuje, ponukneme
    // jej zrusenie namiesto toho, aby sme dali naplanovat druhu
    var existingRec by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<sk.tvhclient.shared.model.DvrEntry?>(null)
    }
    var recReload by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(0) }
    var server by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<sk.tvhclient.shared.model.TvhServer?>(null)
    }

    // Prava zistime raz pri otvoreni; ak ich server neoznami, tlacidlo ukazeme
    // a pripadnu chybu zobrazime az z odpovede.
    androidx.compose.runtime.LaunchedEffect(event.eventId, recReload) {
        val s = sk.tvhclient.shared.Tvh.store.active()
        server = s
        canRecord = if (s == null || event.eventId == null) false
        else DvrController.access(s).canRecord
        val chUuid = event.channelUuid
        existingRec = if (s == null || chUuid.isNullOrBlank()) null
        else DvrController.scheduledFor(s, chUuid, event.start, event.stop)
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
            // M484: tlacidlo sa prepina Nahrat <-> Zrusit, ako v mriezke
            val nowSec = System.currentTimeMillis() / 1000
            val rec = existingRec
            if (canRecord && event.eventId != null && event.stop > nowSec) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = {
                        val s = server ?: return@OutlinedButton
                        val eid = event.eventId ?: return@OutlinedButton
                        recording = true
                        recMessage = null
                        scope.launch {
                            val r = if (rec != null) DvrController.cancel(s, rec)
                            else DvrController.recordEvent(
                                s, eid, event.channelUuid ?: "",
                                event.start, event.stop, event.title
                            )
                            val dup = if (r.success || rec != null) null
                            else DvrController.duplicateOf(s, event.title)
                            recording = false
                            recOk = r.success
                            recMessage = when {
                                r.success && rec != null -> ctx.getString(R.string.dvr_rec_cancelled)
                                r.success -> ctx.getString(R.string.dvr_rec_scheduled)
                                dup != null && dup.channelName.isNotBlank() ->
                                    ctx.getString(
                                        R.string.dvr_rec_duplicate, dup.channelName,
                                        formatDayLabel(dup.start) + " " + formatTimeHm(dup.start)
                                    )
                                else -> r.error ?: ctx.getString(
                                    if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed
                                )
                            }
                            if (r.success) recReload++
                        }
                    },
                    enabled = !recording,
                    modifier = Modifier.fillMaxWidth().dpadFocusable()
                ) {
                    val recNow = rec != null && rec.isRecordingNow   // M485
                    Icon(
                        when {
                            recNow -> Icons.Default.Stop
                            rec != null -> Icons.Default.Close
                            else -> Icons.Default.FiberManualRecord
                        },
                        contentDescription = null
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        when {
                            recording -> stringResource(R.string.dvr_rec_working)
                            recNow -> stringResource(R.string.dvr_stop_button)
                            rec != null -> stringResource(R.string.dvr_rec_cancel_button)
                            else -> stringResource(R.string.dvr_rec_button)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                recMessage?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium,
                        color = if (recOk) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error)
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
