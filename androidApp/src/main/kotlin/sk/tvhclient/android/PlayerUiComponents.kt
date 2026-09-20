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
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shared UI elements and helpers of the player (extracted from PlayerActivity.kt for clarity).

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
    // M385: the row highlight is D-pad focus — it only makes sense on TV. On a phone
    // (touch) the top row of the menu (Audio / Subtitles / Stream profile) was otherwise permanently lit.
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isTvDevice = remember { isTvUiMode(ctx) }   // M679
    val nav = if (isTvDevice) navIndex else -1
    // TV D-pad: keep the highlighted row in view while scrolling
    LaunchedEffect(nav) {
        if (nav >= 0) runCatching { listState.animateScrollToItem(nav) }
    }
    // M558-fix: colours by mode (modern = dark blue panel / teal accent, classic unchanged)
    val modern = isModernUi()
    Box(
        Modifier
            .fillMaxSize()
            .background(if (modern) playerScrimSoft() else Color(0x99000000))
            .consumeAllPointer()   // M563
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                // a narrow bounded dialog (not across the whole TV width); the rows inside are scrollable
                .widthIn(min = 280.dp, max = 460.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (modern) playerScrim() else Color(0xEE202020))
                .padding(8.dp)
        ) {
            Text(
                header,
                color = if (modern) playerFg() else Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(12.dp)
            )
            // the order of the rows must match trackMenuIds() in the Activity: [Off] + items (for subtitles)
            val offset = if (allowOff) 1 else 0
            if (items.isEmpty() && !allowOff) {
                Text(
                    stringResource(R.string.track_none),
                    color = if (modern) playerFgDim() else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(12.dp)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 320.dp)
                ) {
                    if (allowOff) {
                        item(key = "off") {
                            TrackRow(stringResource(R.string.track_off), selected = currentId == -1, highlighted = nav == 0) { onPick(-1) }
                        }
                    }
                    itemsIndexed(items, key = { _, t -> t.id }) { i, t ->
                        TrackRow(t.name, selected = t.id == currentId, highlighted = nav == i + offset) { onPick(t.id) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun TrackRow(label: String, selected: Boolean, highlighted: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val modern = isModernUi()   // M558-fix
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (!highlighted) Color.Transparent
                else if (modern) playerAccent().copy(alpha = 0.35f) else Color(0x553B82F6)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (selected) "\u2713  " else "    ",
            color = if (modern) playerAccent() else MaterialTheme.colorScheme.primary
        )
        Text(label, color = if (modern) playerFg() else Color.White)
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

// The big central play/pause button. We draw the icon with a Canvas so that
// the pause does not have a coloured "emoji" (VLC) look and matches the style of the play triangle.
@Composable
internal fun PlayPauseButton(isPlaying: Boolean, selected: Boolean, scale: Float = 1f, onClick: () -> Unit) {
    Box(
        Modifier
            .size(76.dp * scale)
            .clip(CircleShape)
            // play/pause = the primary action -> always blue (set apart); a white frame only on D-pad focus (TV)
            .background(playerAccent().copy(alpha = 0.8f))
            .then(if (selected) Modifier.border(3.dp, Color.White, CircleShape) else Modifier)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Canvas(Modifier.size(30.dp * scale)) {
            val w = size.width
            val h = size.height
            if (isPlaying) {
                // two vertical bars = pause
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
                // triangle = play
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

// The order of the controls in the player panel for D-pad navigation (the Activity navigates them).
// Must match the rendering in PlayerUi (the same canZap condition).
internal fun playerControlOrder(canZap: Boolean, seekable: Boolean = false, pip: Boolean = true, timeshift: Boolean = false, profile: Boolean = false, record: Boolean = false, teletext: Boolean = false): List<String> = buildList {
    // left
    add("close")
    if (pip) add("pip")
    if (canZap) { add("list"); add("epg") }
    // M554: teletext and info on the left (the right group was overcrowded)
    if (teletext) add("txt")
    add("info")
    // middle (transport)
    if (timeshift) add("tsrew")
    if (canZap) add("prev")
    add("play")
    if (canZap) add("next")
    if (timeshift) add("tsff")
    if (seekable) add("seek")
    // right — M383: the stream profile switch right after subtitles (HTTP live only)
    add("audio"); add("subs")
    if (profile) add("profile")
    if (record) add("rec")   // M490: record / cancel the recording of the running programme
    add("sleep")
}

// M679: fmtMs moved into UiTime.kt (together with fmtClock/fmtRange)

/**
 * M563: consumes all touch events (including dragging), so that the player gestures under the
 * overlay (volume/brightness by swipe, opening the list, switching the channel) do not react
 * while a menu is open. The click itself is handled by the clickable behind this modifier.
 */
internal fun Modifier.consumeAllPointer(): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        do {
            val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Main)
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}
