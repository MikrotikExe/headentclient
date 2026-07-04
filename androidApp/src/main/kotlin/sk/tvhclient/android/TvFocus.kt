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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme

/**
 * Zvyraznenie pri fokuse (Android TV / D-pad). Prvok musi byt klikatelny
 * (clickable/combinedClickable mu da focus target) — tento modifier len
 * vykresli ramik a jemne pozadie ked je zafokusovany. Daj ho do chainu PRED
 * clickable, aby onFocusChanged videl jeho focus.
 *
 * Ramik ma vzdy 2.dp (len mení farbu), aby sa layout pri fokuse neposuval.
 */
fun Modifier.dpadFocusable(shape: Shape = RoundedCornerShape(8.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    // M339-fix2: fokus ako povodne (2dp ram + jemny podklad) — skalovanie z M331
    // je prec (zvacsena karta pretiekla zo slotu a Lazy kontajnery orezavali ramy)
    this
        .onFocusChanged { focused = it.isFocused }
        .border(BorderStroke(2.dp, if (focused) primary else Color.Transparent), shape)
        .then(
            if (focused) Modifier.background(primary.copy(alpha = 0.14f), shape)
            else Modifier
        )
}
