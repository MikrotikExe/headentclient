package sk.tvhclient.android

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Textove pole pre TV. Po zamerani (sipkami) sa NEZOBRAZI klavesnica — je to len
 * zvyraznene pole s popisom a hodnotou. Klavesnica nabehne az po stlaceni OK;
 * vtedy sa zobrazi skutocne OutlinedTextField s IME. Done/BACK ho zatvori a fokus
 * sa vrati na pole. Tym sa klavesnica pri prechadzani formulara nikdy nevyskoci sama.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    password: Boolean = false
) {
    var editing by remember { mutableStateOf(false) }
    var everEdited by remember { mutableStateOf(false) }
    val boxFocus = remember { FocusRequester() }

    if (editing) {
        val imeFocus = remember { FocusRequester() }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation =
                if (password) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { editing = false }),
            modifier = modifier
                .focusRequester(imeFocus)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown &&
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK
                    ) { editing = false; true } else false
                }
        )
        LaunchedEffect(Unit) { runCatching { imeFocus.requestFocus() } }
    } else {
        // Po skonceni uprav vrat fokus na pole (nech nezostane visiet)
        LaunchedEffect(editing) {
            if (everEdited) runCatching { boxFocus.requestFocus() }
        }
        Column(
            modifier = modifier
                .heightIn(min = 56.dp)
                .focusRequester(boxFocus)
                .dpadFocusable()
                .clickable { editing = true; everEdited = true }
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val shown = when {
                value.isBlank() -> "\u2014"
                password -> "\u2022".repeat(value.length)
                else -> value
            }
            Text(
                shown,
                style = MaterialTheme.typography.bodyLarge,
                color = if (value.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
