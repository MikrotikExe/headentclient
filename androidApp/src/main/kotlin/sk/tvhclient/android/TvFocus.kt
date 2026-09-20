package sk.tvhclient.android

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.focusable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * Focus highlight (Android TV / D-pad). The element must be clickable
 * (clickable/combinedClickable gives it a focus target) — this modifier only
 * draws the border and a subtle background when it is focused. Put it in the chain BEFORE
 * clickable, so that onFocusChanged sees its focus.
 *
 * The border is always 2.dp (only the colour changes), so the layout does not shift on focus.
 */
/**
 * M395-fix2: INVISIBLE focus for purely readable (non-clickable) blocks on TV.
 * No highlight and no border — the element is only a focus target, so the D-pad can
 * land on it and the scroll container automatically scrolls it into view (focusable
 * in Compose does bringIntoView by itself). Use it on informational texts above/below
 * clickable elements so that they can be read to the end: Column(Modifier.dpadReadable()).
 */
fun Modifier.dpadReadable(): Modifier =
    this.then(androidx.compose.ui.Modifier.focusable())

fun Modifier.dpadFocusable(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    // M339-fix2: focus as it was originally (2dp border + subtle background) — the scaling from M331
    // is gone (the enlarged card overflowed its slot and Lazy containers clipped the borders)
    this
        .onFocusChanged { focused = it.isFocused }
        .border(BorderStroke(2.dp, if (focused) primary else Color.Transparent), shape)
        .then(
            if (focused) Modifier.background(primary.copy(alpha = 0.14f), shape)
            else Modifier
        )
}
