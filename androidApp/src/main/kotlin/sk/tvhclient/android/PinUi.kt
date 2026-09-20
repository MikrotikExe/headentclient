package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * PIN dialog (4 digits). We drive keyboard navigation ourselves (arrows + OK),
 * because Compose focus is not reliable on cheap Android TV boxes.
 * Digits straight from the remote (0-9) work too. [onComplete] receives the 4 digits
 * and returns true = accepted (the caller closes it), false = wrong (it clears it).
 */
@Composable
fun PinDialog(
    title: String,
    onDismiss: () -> Unit,
    onComplete: (String) -> Boolean
) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    if (ParentalLock.pinInput(ctx) == "keyboard") {
        PinDialogKeyboard(title, onDismiss, onComplete)
    } else {
        PinDialogGrid(title, onDismiss, onComplete)
    }
}

@Composable
private fun PinDialogGrid(
    title: String,
    onDismiss: () -> Unit,
    onComplete: (String) -> Boolean
) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var selRow by remember { mutableStateOf(0) }
    var selCol by remember { mutableStateOf(0) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    // key grid: 1-9, then [delete] 0 [cancel]
    val grid = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("del", "0", "x")
    )

    fun push(d: Int) {
        if (entered.length >= 4) return
        entered += d
        error = false
        if (entered.length == 4) {
            val pin = entered
            if (!onComplete(pin)) { error = true; entered = "" }
        }
    }
    fun del() { if (entered.isNotEmpty()) entered = entered.dropLast(1) }
    fun activate(label: String) {
        when (label) {
            "del" -> del()
            "x" -> onDismiss()
            else -> push(label.toInt())
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF1B2433),
            contentColor = Color.White,
            tonalElevation = 6.dp
        ) {
            Column(
                Modifier
                    .padding(24.dp)
                    .focusRequester(fr)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val kc = e.nativeKeyEvent.keyCode
                        val digit = when (kc) {
                            in android.view.KeyEvent.KEYCODE_0..android.view.KeyEvent.KEYCODE_9 ->
                                kc - android.view.KeyEvent.KEYCODE_0
                            in android.view.KeyEvent.KEYCODE_NUMPAD_0..android.view.KeyEvent.KEYCODE_NUMPAD_9 ->
                                kc - android.view.KeyEvent.KEYCODE_NUMPAD_0
                            else -> -1
                        }
                        when {
                            digit >= 0 -> { push(digit); true }
                            kc == android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { selCol = (selCol - 1 + 3) % 3; true }
                            kc == android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { selCol = (selCol + 1) % 3; true }
                            kc == android.view.KeyEvent.KEYCODE_DPAD_UP -> { selRow = (selRow - 1 + 4) % 4; true }
                            kc == android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { selRow = (selRow + 1) % 4; true }
                            kc == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                                kc == android.view.KeyEvent.KEYCODE_ENTER ||
                                kc == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                activate(grid[selRow][selCol]); true
                            }
                            kc == android.view.KeyEvent.KEYCODE_DEL -> { del(); true }
                            else -> false  // BACK is left to close the dialog
                        }
                    },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    repeat(4) { i ->
                        Box(
                            Modifier.size(18.dp).clip(CircleShape).background(
                                if (i < entered.length) MaterialTheme.colorScheme.primary
                                else Color(0x44FFFFFF)
                            )
                        )
                    }
                }
                if (error) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.plock_wrong), color = MaterialTheme.colorScheme.error)
                }
                Spacer(Modifier.height(16.dp))
                grid.forEachIndexed { r, rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        rowKeys.forEachIndexed { c, label ->
                            val selected = r == selRow && c == selCol
                            Box(
                                Modifier
                                    .size(width = 64.dp, height = 44.dp)
                                    .clip(RoundedCornerShape(22.dp))
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primary
                                        else Color(0x22FFFFFF)
                                    )
                                    .border(
                                        2.dp,
                                        if (selected) Color.White else Color(0x55FFFFFF),
                                        RoundedCornerShape(22.dp)
                                    )
                                    .clickable { selRow = r; selCol = c; activate(label) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    when (label) { "del" -> "\u232B"; "x" -> "\u2715"; else -> label },
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

@Composable
private fun PinDialogKeyboard(
    title: String,
    onDismiss: () -> Unit,
    onComplete: (String) -> Boolean
) {
    var entered by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }

    fun submit() {
        if (entered.length == 4 && !onComplete(entered)) { error = true; entered = "" }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = entered,
                    onValueChange = { v ->
                        entered = v.filter { it.isDigit() }.take(4)
                        error = false
                        if (entered.length == 4) submit()
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.focusRequester(fr)
                )
                if (error) {
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.plock_wrong), color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
