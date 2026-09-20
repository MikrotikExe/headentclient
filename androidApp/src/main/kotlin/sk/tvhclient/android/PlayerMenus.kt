package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/*
 * M633: the player menus extracted from PlayerUi (PlayerActivity.kt) — the modern "More" menu
 * (M327), the sleep timer selection and the PIN prompt panel. Pure composables; the D-pad highlight
 * is driven by the caller via a highlight index (-1 = no highlight, M385-fix: TV only).
 */

/** The modern "More" menu: Channels / Timer / Profile / Record / Teletext / Info according to [ids] (M383). */
@Composable
internal fun ModernMoreMenu(
    ids: List<String>,
    highlightIndex: Int,
    recActive: Boolean,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val labels = ids.map { id ->
        when (id) {
            "list" -> stringResource(R.string.tab_channels)
            "sleep" -> stringResource(R.string.sleep_timer)
            "profile" -> stringResource(R.string.field_profile)
            "rec" -> stringResource(if (recActive) R.string.dvr_rec_cancel_button else R.string.dvr_rec_button)   // M490
            "teletext" -> stringResource(R.string.teletext)   // M553
            else -> stringResource(R.string.pm_info)
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(playerScrimSoft())
            .consumeAllPointer()   // M563
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                // M558: without a max width the rows (fillMaxWidth) stretched across the whole screen;
                // the same width as TrackMenu (Audio/Subtitles/Profile)
                .widthIn(min = 280.dp, max = 460.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(playerScrim())
                .padding(8.dp)
        ) {
            Text(stringResource(R.string.pm_more), color = playerFg(),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            labels.forEachIndexed { idx, label ->
                val sel = idx == highlightIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) playerAccent().copy(alpha = 0.35f) else Color.Transparent)   // M562
                        .clickable { onPick(idx) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // M516: an icon for every item — without it the list was bare text, while the classic bar has icons
                    Icon(
                        when (ids.getOrNull(idx)) {
                            "list" -> Icons.Default.List
                            "sleep" -> Icons.Default.Timer
                            "profile" -> Icons.Default.Tune
                            "rec" -> if (recActive) Icons.Default.Stop else Icons.Default.FiberManualRecord
                            "teletext" -> Icons.AutoMirrored.Filled.Article   // M553-fix3
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = if (sel) playerFg() else playerFgDim(),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(label, color = playerFg())
                }
            }
        }
    }
}

/** Selection of the sleep timer length — vertical; the order must match SleepTimer.durations. */
@Composable
internal fun SleepOptionsMenu(highlightIndex: Int, onSelect: (Int) -> Unit, onDismiss: () -> Unit) {
    val opts = listOf(stringResource(R.string.sleep_off), "15 min", "30 min", "45 min", "60 min", "90 min")
    Box(
        Modifier
            .fillMaxSize()
            .background(playerScrimSoft())
            .consumeAllPointer()   // M563
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                // M562: the same width as the More menu / TrackMenu (without a max the rows stretched)
                .widthIn(min = 280.dp, max = 460.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(playerScrim())
                .padding(8.dp)
        ) {
            Text(stringResource(R.string.sleep_timer), color = playerFg(),
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
            opts.forEachIndexed { idx, label ->
                val sel = idx == highlightIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (sel) (if (isModernUi()) playerAccent().copy(alpha = 0.35f) else Color(0x553B82F6)) else Color.Transparent)   // M562
                        .clickable { onSelect(idx) }
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = playerFg())
                }
            }
        }
    }
}

/** Parental lock: the panel for entering the PIN in the player (M273 compact, like PinDialogGrid).
 *  Digits from the remote are handled by the activity (PinPrompt); [gridRow]/[gridCol] = the D-pad highlight
 *  on TV, -1 = no highlight. */
@Composable
internal fun PlayerPinPanel(
    pinLen: Int,
    pinError: Boolean,
    gridRow: Int,
    gridCol: Int,
    onDigit: (Int) -> Unit,
    onBack: () -> Unit,
    onOpenList: () -> Unit
) {
    Box(
        Modifier.fillMaxSize().background(Color(0x990B1220))
            .pointerInput(Unit) { detectTapGestures { } },   // block input to the background
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1B2433),
            contentColor = Color.White,
            tonalElevation = 6.dp
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.plock_enter), color = Color.White,
                    style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(4) { i ->
                        Box(Modifier.size(18.dp).clip(CircleShape).background(
                            if (i < pinLen) MaterialTheme.colorScheme.primary else Color(0x44FFFFFF)))
                    }
                }
                if (pinError) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.plock_wrong), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                // Numeric grid: touch on a phone, D-pad driven on TV
                // (highlighting the selected key) — for remotes without number keys.
                val padKeys = listOf(
                    listOf("1", "2", "3"),
                    listOf("4", "5", "6"),
                    listOf("7", "8", "9"),
                    listOf("del", "0", "list")
                )
                padKeys.forEachIndexed { r, rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowKeys.forEachIndexed { c, label ->
                            val selected = r == gridRow && c == gridCol
                            Box(
                                Modifier.size(width = 64.dp, height = 44.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x22FFFFFF))
                                    .border(2.dp, if (selected) Color.White else Color(0x55FFFFFF), RoundedCornerShape(22.dp))
                                    .clickable {
                                        when (label) {
                                            "del" -> onBack()
                                            "list" -> onOpenList()
                                            else -> onDigit(label.toInt())
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when (label) { "del" -> "⌫"; "list" -> "☰"; else -> label },
                                    color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                    style = MaterialTheme.typography.titleMedium
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}
