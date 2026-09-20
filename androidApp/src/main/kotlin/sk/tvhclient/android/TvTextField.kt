package sk.tvhclient.android

import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
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
 * Text field for TV. On being focused (with the arrows) the keyboard is NOT shown — it is only
 * a highlighted field with a label and a value. The keyboard comes up only after pressing OK;
 * at that point a real OutlinedTextField with an IME is shown. Done/BACK closes it and focus
 * returns to the field. This way the keyboard never pops up on its own while moving through the form.
 *
 * Autocorrect and initial capitals are disabled (host/username/password are technical data).
 * With password=true there is an eye button in the editor to show/hide the password.
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
    focusRequester: FocusRequester? = null,
    // Modern mode (M319): an optional coloured icon chip on the left inside the field
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    chipBg: androidx.compose.ui.graphics.Color? = null,
    chipFg: androidx.compose.ui.graphics.Color? = null
) {
    val modern = isModernUi()
    val boxShape = RoundedCornerShape(if (modern) 18.dp else 4.dp)
    val boxBorder = if (modern) MaterialTheme.colorScheme.outlineVariant
                    else MaterialTheme.colorScheme.outline
    val boxBg = if (modern) {
        if (isLightTheme()) MaterialTheme.colorScheme.surfaceContainerLowest
        else MaterialTheme.colorScheme.surfaceContainer
    } else androidx.compose.ui.graphics.Color.Transparent
    // Chip with an icon (modern mode only and only when an icon is given)
    val chip: (@Composable () -> Unit)? =
        if (modern && leadingIcon != null && chipBg != null && chipFg != null) {
            {
                Box(
                    Modifier.size(38.dp).clip(RoundedCornerShape(11.dp)).background(chipBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(leadingIcon, contentDescription = null, tint = chipFg,
                        modifier = Modifier.size(20.dp))
                }
            }
        } else null
    var editing by remember { mutableStateOf(false) }
    var everEdited by remember { mutableStateOf(false) }
    var revealed by remember { mutableStateOf(false) }
    // Buffer of the characters typed while switching from the box -> the IME field (a USB keyboard types faster
    // than the redraw keeps up). The local state is always read fresh, so the order is preserved.
    var starting by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf("") }
    val internalFocus = remember { FocusRequester() }
    val boxFocus = focusRequester ?: internalFocus

    if (editing) {
        val imeFocus = remember { FocusRequester() }
        // M282: the field is driven by the LOCAL text, which starts with the value + the characters typed
        // in box mode (pending) in the right order. This removes the asynchronous "catching up" via
        // onValueChange, which with a fast USB keyboard swapped the first characters around (admintest
        // -> damintest). Every keypress changes the local text synchronously and is propagated upwards at the same time.
        var text by remember { mutableStateOf(value + pending) }
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; onValueChange(it) },
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
            shape = boxShape,
            modifier = modifier
                .focusRequester(imeFocus)
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown &&
                        e.nativeKeyEvent.keyCode == android.view.KeyEvent.KEYCODE_BACK
                    ) { editing = false; true } else false
                }
        )
        LaunchedEffect(Unit) {
            // pending is already included in 'text' -> propagate upwards once and clear the buffer
            if (pending.isNotEmpty()) onValueChange(text)
            pending = ""
            starting = false
            runCatching { imeFocus.requestFocus() }
        }
    } else {
        // After the editing ends return focus to the field (so it does not hang) + clear the buffer
        LaunchedEffect(editing) {
            if (!editing) { starting = false; pending = "" }
            if (everEdited) runCatching { boxFocus.requestFocus() }
        }
        // field content (label + value/dots) — shared by both branches
        val labelValue: @Composable () -> Unit = {
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
        // capture of the characters from an attached keyboard before the IME opens (in the right order)
        val captureKeys = Modifier.onPreviewKeyEvent { e ->
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
        if (password) {
            // M282-fix: the eye is INSIDE the field's border (on the right), so that the password field has the same width
            // as the other fields (symmetry). The text part is focusable (OK = edit),
            // the eye is separately focusable (OK = show/hide the password) — both reachable with the D-pad.
            Row(
                modifier = modifier
                    .heightIn(min = 56.dp)
                    .clip(boxShape)
                    .background(boxBg)
                    .border(1.dp, boxBorder, boxShape),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (chip != null) {
                    Spacer(Modifier.width(12.dp))
                    chip()
                }
                Column(
                    Modifier
                        .weight(1f)
                        .focusRequester(boxFocus)
                        .dpadFocusable(boxShape)
                        .then(captureKeys)
                        .clickable { editing = true; everEdited = true }
                        .padding(horizontal = if (chip != null) 12.dp else 16.dp, vertical = 8.dp)
                ) { labelValue() }
                Box(
                    Modifier
                        .padding(end = 6.dp)
                        .dpadFocusable()
                        .clickable { revealed = !revealed }
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (revealed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (revealed) R.string.hide_password else R.string.show_password
                        ),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Row(
                modifier = modifier
                    .heightIn(min = 56.dp)
                    .clip(boxShape)
                    .background(boxBg)
                    .border(1.dp, boxBorder, boxShape)
                    .focusRequester(boxFocus)
                    .dpadFocusable(boxShape)
                    .then(captureKeys)
                    .clickable { editing = true; everEdited = true }
                    .padding(horizontal = if (chip != null) 12.dp else 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (chip != null) {
                    chip()
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) { labelValue() }
            }
        }
    }
}
