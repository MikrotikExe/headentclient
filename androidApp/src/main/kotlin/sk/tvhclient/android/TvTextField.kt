package sk.tvhclient.android

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Textove pole pre TV. Po zamerani (sipkami) sa NEZOBRAZI klavesnica — je to len
 * zvyraznene pole s popisom a hodnotou. Klavesnica nabehne az po stlaceni OK;
 * vtedy sa zobrazi skutocne OutlinedTextField s IME. Done/BACK ho zatvori a fokus
 * sa vrati na pole. Tym sa klavesnica pri prechadzani formulara nikdy nevyskoci sama.
 *
 * Autokorekcia a velke zaciatocne pismena su vypnute (host/meno/heslo su technicke udaje).
 * Pri password=true je v editacii tlacidlo oka na zobrazenie/skrytie hesla.
 */
@Composable
fun TvTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    uri: Boolean = false,
    password: Boolean = false,
    focusRequester: FocusRequester? = null
) {
    var editing by remember { mutableStateOf(false) }
    var everEdited by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    // Buffer znakov napisanych pocas prepinania box -> IME pole (USB klavesnica pise rychlejsie
    // ako stiha prekreslenie). Lokalny stav sa cita vzdy aktualne, takze poradie sa zachova.
    var starting by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf("") }
    val internalFocus = remember { FocusRequester() }
    val boxFocus = focusRequester ?: internalFocus

    if (editing) {
        val imeFocus = remember { FocusRequester() }
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            visualTransformation =
                if (password && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
            trailingIcon = if (password) {
                {
                    IconButton(onClick = { revealed = !revealed }) {
                        Icon(
                            if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = stringResource(
                                if (revealed) R.string.hide_password else R.string.show_password
                            )
                        )
                    }
                }
            } else null,
            keyboardOptions = KeyboardOptions(
                keyboardType = when {
                    numeric -> KeyboardType.Number
                    password -> KeyboardType.Password
                    uri -> KeyboardType.Uri
                    else -> KeyboardType.Text
                },
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
                capitalization = KeyboardCapitalization.None
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
        LaunchedEffect(Unit) {
            if (pending.isNotEmpty()) { onValueChange(value + pending); pending = "" }
            starting = false
            runCatching { imeFocus.requestFocus() }
        }
    } else {
        // Po skonceni uprav vrat fokus na pole (nech nezostane visiet) + vycisti buffer
        LaunchedEffect(editing) {
            if (!editing) { starting = false; pending = "" }
            if (everEdited) runCatching { boxFocus.requestFocus() }
        }
        Column(
            modifier = modifier
                .heightIn(min = 56.dp)
                .focusRequester(boxFocus)
                .dpadFocusable()
                .onPreviewKeyEvent { e ->
                    // Pripojena klavesnica: znaky napisane skor, nez sa stihne otvorit IME pole,
                    // sa zhromazdia do bufra (v spravnom poradi) a aplikuju sa naraz pri aktivacii.
                    if (e.type == KeyEventType.KeyDown) {
                        val ch = e.nativeKeyEvent.unicodeChar
                        if (ch != 0 && !Character.isISOControl(ch)) {
                            pending += ch.toChar()
                            everEdited = true
                            if (!starting) { starting = true; editing = true }
                            true
                        } else false
                    } else false
                }
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
                password && !revealed -> "\u2022".repeat(value.length)
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
