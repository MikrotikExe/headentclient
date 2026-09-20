package sk.tvhclient.android

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Text that shrinks itself to fit into the available width/height.
 * Intended for cramped UI (tabs, buttons, chips, labels in narrow rows),
 * where the text wraps or gets clipped differently at various resolutions/font scalings.
 *
 * LEAVE ordinary prose text to wrap normally — this is not a replacement for Text everywhere,
 * only for places with a hard width limit.
 *
 * Works on older Compose too (without native autoSize): it gradually lowers fontSize
 * until the content fits, or until it reaches minTextSize. It is drawn only once the
 * size is resolved (no flicker during measuring).
 */
@Composable
fun AutoSizeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    maxLines: Int = 1,
    minTextSize: TextUnit = 9.sp,
    style: TextStyle = LocalTextStyle.current
) {
    val base = if (style.fontSize == TextUnit.Unspecified) 14.sp else style.fontSize
    var scaled by remember(text, style, maxLines) { mutableStateOf(style.copy(fontSize = base)) }
    var ready by remember(text, style, maxLines) { mutableStateOf(false) }
    Text(
        text = text,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        color = color,
        maxLines = maxLines,
        softWrap = maxLines > 1,
        overflow = TextOverflow.Clip,
        style = scaled,
        onTextLayout = { res ->
            if (!ready) {
                if (res.didOverflowWidth || res.didOverflowHeight) {
                    val next = scaled.fontSize * 0.92f
                    if (next.value >= minTextSize.value) scaled = scaled.copy(fontSize = next)
                    else ready = true
                } else {
                    ready = true
                }
            }
        }
    )
}
