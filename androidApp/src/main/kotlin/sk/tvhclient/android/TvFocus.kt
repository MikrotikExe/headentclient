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
    // Moderny rezim (M331-fix): namiesto skalovania (zvacsena karta pretiekla
    // von zo slotu a LazyRow/LazyColumn ju na hranach orezavali — ramy "uchadzali")
    // sa fokus zvyrazni animovanym hrubsim ramom a jasnejsim podkladom. Nic
    // nepretecie, ziadne orezanie, citatelnost z gauca ostava.
    val modern = isModernUi()
    val borderW by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (modern && focused) 3.dp else 2.dp,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "dpadBorder"
    )
    val bgAlpha by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (focused) { if (modern) 0.22f else 0.14f } else 0f,
        animationSpec = androidx.compose.animation.core.tween(120),
        label = "dpadBg"
    )
    this
        .onFocusChanged { focused = it.isFocused }
        .border(BorderStroke(if (focused) borderW else 2.dp, if (focused) primary else Color.Transparent), shape)
        .then(
            if (bgAlpha > 0f) Modifier.background(primary.copy(alpha = bgAlpha), shape)
            else Modifier
        )
}
