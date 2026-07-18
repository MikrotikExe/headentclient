package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Spolocne UI prvky a pomocnici prehravaca (vyclenene z PlayerActivity.kt kvoli prehladnosti).

@Composable
internal fun TrackMenu(
    header: String,
    items: List<TrackItem>,
    currentId: Int,
    allowOff: Boolean,
    navIndex: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val listState = rememberLazyListState()
    // TV D-pad: drz zvyrazneny riadok vo vyhlade pri posuvani
    LaunchedEffect(navIndex) {
        runCatching { listState.animateScrollToItem(navIndex.coerceAtLeast(0)) }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x99000000))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                // uzky ohraniceny dialog (nie cez celu sirku TV); riadky vnutri su posuvatelne
                .widthIn(min = 280.dp, max = 460.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xEE202020))
                .padding(8.dp)
        ) {
            Text(
                header,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp)
            )
            // poradie riadkov musi sediet s trackMenuIds() v Activity: [Vypnute] + items (pre titulky)
            val offset = if (allowOff) 1 else 0
            if (items.isEmpty() && !allowOff) {
                Text(
                    stringResource(R.string.track_none),
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    if (allowOff) {
                        item(key = "off") {
                            TrackRow(stringResource(R.string.track_off), selected = currentId == -1, highlighted = navIndex == 0) { onPick(-1) }
                        }
                    }
                    itemsIndexed(items, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t.name, selected = t.id == currentId, highlighted = navIndex == i + offset) { onPick(t.id) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrackRow(label: String, selected: Boolean, highlighted: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (highlighted) Color(0x553B82F6) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "\u2713  " else "    ",
            color = MaterialTheme.colorScheme.primary
        )
        Text(label, color = Color.White)
    }
}

@Composable
internal fun TextChip(label: String, selected: Boolean = false, scale: Float = 1f, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) playerAccent().copy(alpha = 0.8f) else Color(0x88000000))
            .then(if (selected) Modifier.border(3.dp, Color.White, shape) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = (16.dp * scale), vertical = (10.dp * scale))
    ) {
        Text(label, color = Color.White, fontSize = 14.sp * scale)
    }
}

// Velke stredove tlacidlo play/pauza. Ikonu kreslime cez Canvas, aby
// pauza nemala farebny "emoji" (VLC) vzhlad a sedela so stylom play trojuholnika.
@Composable
internal fun PlayPauseButton(isPlaying: Boolean, selected: Boolean, scale: Float = 1f, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp * scale)
            .clip(CircleShape)
            // play/pauza = primarna akcia -> vzdy modra (odlisena); biely ramik len pri D-pad fokuse (TV)
            .background(playerAccent().copy(alpha = 0.8f))
            .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(30.dp * scale)) {
            val w = size.width
            val h = size.height
            if (isPlaying) {
                // dve zvisle ciary = pauza
                val barW = w * 0.26f
                val gap = w * 0.18f
                drawRect(
                    Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2f - gap / 2f - barW, 0f),
                    size = androidx.compose.ui.geometry.Size(barW, h)
                )
                drawRect(
                    Color.White,
                    topLeft = androidx.compose.ui.geometry.Offset(w / 2f + gap / 2f, 0f),
                    size = androidx.compose.ui.geometry.Size(barW, h)
                )
            } else {
                // trojuholnik = play
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(w * 0.14f, 0f)
                    lineTo(w * 0.14f, h)
                    lineTo(w * 0.92f, h / 2f)
                    close()
                }
                drawPath(p, Color.White)
            }
        }
    }
}

@Composable
internal fun CircleButton(
    icon: ImageVector,
    onClick: () -> Unit,
    big: Boolean = false,
    selected: Boolean = false,
    active: Boolean = false,
    scale: Float = 1f,
    labelScale: Float = 1f,
    modifier: Modifier = Modifier
) {
    val s = (if (big) 76 else 44).dp * scale
    val iconSize = (if (big) 38 else 24).dp * scale * labelScale
    Box(
        modifier
            .size(s)
            .clip(CircleShape)
            .background(
                when {
                    selected -> playerAccent().copy(alpha = 0.8f)
                    active -> Color(0x9943A047)
                    else -> if (isLightTheme()) Color(0x88000000) else Color(0xCC4D4D4D)
                }
            )
            .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

// Poradie ovladacich prvkov v paneli prehravaca pre D-pad navigaciu (Activity ich navriguje).
// Musi sediet s vykreslenim v PlayerUi (rovnaka podmienka canZap).
internal fun playerControlOrder(canZap: Boolean, seekable: Boolean = false, pip: Boolean = true, timeshift: Boolean = false, profile: Boolean = false): List<String> = buildList {
    // vlavo
    add("close")
    if (pip) add("pip")
    if (canZap) { add("list"); add("epg") }
    // stred (transport)
    if (timeshift) add("tsrew")
    if (canZap) add("prev")
    add("play")
    if (canZap) add("next")
    if (timeshift) add("tsff")
    if (seekable) add("seek")
    // vpravo — M383: prepinac stream profilu hned za titulkami (len HTTP live)
    add("audio"); add("subs")
    if (profile) add("profile")
    add("sleep"); add("info")
}

internal fun fmtMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) {
        "$h:" + m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
    } else {
        "$m:" + s.toString().padStart(2, '0')
    }
}
