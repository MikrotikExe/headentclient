package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sk.tvhclient.shared.Tvh

/**
 * M430 / M628: kompaktný zap pás pri prepínaní kanálov (keď je prekryv vypnutý) —
 * číslo · kanál / program · čas / priebeh, na ~4 s. Dáta má z LiveChannel
 * (EPG now/next už v pamäti), nič nesťahuje. Vyclenené z PlayerActivity.
 *
 * [suppressed] hovorí, či je na obrazovke iný prekryv (klasické ovládanie,
 * moderný prehľad, info okno) — vtedy sa pás nezobrazí (M442: tie ukazujú ten
 * istý údaj a pri prepínaní sa samy aktualizujú; inak by na niektorých
 * zariadeniach svietili dva pásy naraz).
 */
class ZapBar(
    private val scope: CoroutineScope,
    private val suppressed: () -> Boolean
) {
    val visible = mutableStateOf(false)
    val number = mutableStateOf("")
    val picon = mutableStateOf<String?>(null)
    val channel = mutableStateOf("")
    val title = mutableStateOf("")
    val time = mutableStateOf("")
    val progress = mutableStateOf(0f)
    private var job: Job? = null

    /** M446: zruší zap pás — volá sa vždy, keď sa otvára iný prekryv (moderný
     *  prehľad, klasické ovládanie, info). Bez toho by pás ostal visieť navrchu
     *  až do vypršania 4 s a bary by sa prekrývali. */
    fun hide() {
        job?.cancel()
        visible.value = false
    }

    fun show(ch: LivePlaylist.LiveChannel?) {
        if (suppressed()) return
        ch ?: return
        number.value = if (ch.number > 0) ch.number.toString() else ""
        picon.value = ch.piconUrl
        channel.value = ch.name
        title.value = ch.nowTitle
        time.value = fmtRange(ch.nowStart, ch.nowStop)
        val now = System.currentTimeMillis() / 1000
        progress.value = if (ch.nowStop > ch.nowStart)
            ((now - ch.nowStart).toFloat() / (ch.nowStop - ch.nowStart)).coerceIn(0f, 1f) else 0f
        visible.value = true
        job?.cancel()
        job = scope.launch {
            delay(4000)
            visible.value = false
        }
    }
}

/** Vykreslenie zap pásu vľavo dole (volajúci rozhodne, či ho práve zobraziť). */
@Composable
internal fun ZapBarOverlay(bar: ZapBar) {
    Box(
        Modifier.fillMaxSize().padding(start = 28.dp, bottom = 32.dp),
        contentAlignment = Alignment.BottomStart
    ) {
        Row(
            Modifier.widthIn(min = 300.dp, max = 560.dp)
                .shadow(8.dp, RoundedCornerShape(16.dp))
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f))
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val zpCtx = LocalContext.current
            val zpSrv = remember { Tvh.store.active() }
            val zpLoader = remember(zpSrv?.id) { PiconImageLoader.get(zpCtx, zpSrv) }
            val zpUrl = bar.picon.value
            if (!zpUrl.isNullOrBlank()) {
                var zpOk by remember(zpUrl) { mutableStateOf(true) }
                if (zpOk) {
                    AsyncImage(
                        model = ImageRequest.Builder(zpCtx).data(zpUrl).build(),
                        contentDescription = null,
                        imageLoader = zpLoader,
                        contentScale = ContentScale.Fit,
                        onState = { st -> if (st is AsyncImagePainter.State.Error) zpOk = false },
                        modifier = Modifier.size(46.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (bar.number.value.isNotBlank()) {
                        Text(bar.number.value, color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold)
                        Text("  ·  ", color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.titleLarge)
                    }
                    Text(bar.channel.value, color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (bar.title.value.isNotBlank() || bar.time.value.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(bar.title.value, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        if (bar.time.value.isNotBlank()) {
                            Spacer(Modifier.width(16.dp))
                            Text(bar.time.value, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (bar.progress.value > 0f) {
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f))) {
                        Box(Modifier.fillMaxWidth(bar.progress.value).fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.primary))
                    }
                }
            }
        }
    }
}
