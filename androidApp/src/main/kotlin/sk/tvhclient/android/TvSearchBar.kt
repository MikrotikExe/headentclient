package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * Vyhladavacie pole pre TV. Po zamerani (sipkami) sa NEZOBRAZI klavesnica —
 * je to len zvyraznene pole. Klavesnica sa otvori az po stlaceni OK; vtedy sa
 * objavi skutocne textove pole s IME. BACK / potvrdenie klavesnice ho zatvori
 * a fokus sa vrati na pole. Tym sa klavesnica nikdy nevyskoci sama.
 */
@Composable
fun TvSearchBar(
    query: String,
    placeholder: String,
    onQueryChange: (String) -> Unit,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onUp: () -> Unit = {}
) {
    var editing by remember { mutableStateOf(false) }

    if (editing) {
        val imeFocus = remember { FocusRequester() }
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            shape = if (isModernUi()) RoundedCornerShape(999.dp) else androidx.compose.material3.OutlinedTextFieldDefaults.shape,
            label = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { editing = false }),
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
        // Vratenie fokusu na pole po zatvoreni klavesnice (nech nezostane visiet)
        // moderny rezim: pilulka s jemnym pozadim; klasik: povodny ramik
        val modern = isModernUi()
        val shape = if (modern) RoundedCornerShape(999.dp) else RoundedCornerShape(4.dp)
        Box(
            modifier = modifier
                .heightIn(min = 56.dp)
                .focusRequester(focusRequester)
                .dpadFocusable(shape)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown &&
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP
                    ) { onUp(); true } else false
                }
                .clip(shape)
                .background(
                    if (modern) {
                        if (isLightTheme()) MaterialTheme.colorScheme.surfaceContainerLowest
                        else MaterialTheme.colorScheme.surfaceContainer
                    } else androidx.compose.ui.graphics.Color.Transparent
                )
                .clickable { editing = true }
                .border(
                    1.dp,
                    if (modern) MaterialTheme.colorScheme.outlineVariant
                    else MaterialTheme.colorScheme.outline,
                    shape
                )
                .padding(horizontal = 16.dp, vertical = 16.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                if (query.isBlank()) placeholder else query,
                color = if (query.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
