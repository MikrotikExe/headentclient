package sk.tvhclient.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sk.tvhclient.shared.api.ConnectionResult
import sk.tvhclient.shared.model.TvhServer

// UI servera: zoznam-riadok, formular pridania/upravy, vysledok testu, rozbalovacie pole
// (vyclenene z MainActivity.kt kvoli prehladnosti).

@Composable
fun ServerRow(
    server: TvhServer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    editFocusRequester: FocusRequester? = null
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(server.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${server.host}:${server.port}" +
                            if (server.useHttps) " (HTTPS)" else "",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (isActive) {
                    Text(
                        stringResource(R.string.active),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isActive) {
                    TextButton(onClick = onSelect) { Text(stringResource(R.string.use_server)) }
                }
                TextButton(
                    onClick = onEdit,
                    modifier = if (editFocusRequester != null)
                        Modifier.focusRequester(editFocusRequester) else Modifier
                ) { Text(stringResource(R.string.edit)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.delete)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerForm(vm: ServersViewModel, existing: TvhServer?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var host by remember { mutableStateOf(existing?.host ?: "") }
    var port by remember { mutableStateOf((existing?.port ?: 9981).toString()) }
    var useHttps by remember { mutableStateOf(existing?.useHttps ?: false) }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var profile by remember { mutableStateOf(existing?.profile ?: "pass") }
    var authMode by remember { mutableStateOf(existing?.authMode ?: "auto") }
    var connMode by remember { mutableStateOf(existing?.connectionMode ?: "htsp") }
    var htspPort by remember { mutableStateOf((existing?.htspPort ?: 9982).toString()) }

    // Pociatocny D-pad fokus (TV) na prve pole, nech sa da hned navigovat zhora dole
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { firstFocus.requestFocus() }
    }

    val testState by vm.testState.collectAsState()

    fun buildServer(): TvhServer? {
        val p = port.toIntOrNull() ?: return null
        if (host.isBlank()) return null
        return TvhServer(
            id = existing?.id ?: vm.newId(),
            name = name.ifBlank { host },
            host = host.trim(),
            port = p,
            useHttps = useHttps,
            username = username.trim(),
            password = password,
            profile = profile.trim().ifBlank { "pass" },
            authMode = authMode,
            connectionMode = connMode,
            htspPort = htspPort.toIntOrNull() ?: 9982
        )
    }

    // M380: profily sa tahaju zo servera automaticky — ziadne tlacidlo.
    // Existujuci server (ma ulozene prihlasenie) sa nacita hned po otvoreni;
    // novy hned ako su vyplnene adresa a prihlasenie. Debounce 800 ms, nech
    // sa nestriela dotaz pri kazdom pismene. Zlyhanie = ticho, ostane fallback.
    val serverProfiles by vm.profiles.collectAsState()
    LaunchedEffect(Unit) { vm.clearProfiles() }
    LaunchedEffect(host, port, username, password, useHttps, authMode, connMode) {
        if (connMode == "htsp") return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        buildServer()?.let { vm.loadProfiles(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = {
                Text(
                    stringResource(
                        if (existing == null) R.string.add_server else R.string.edit_server
                    )
                )
            })
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            TvTextField(
                label = stringResource(R.string.field_name),
                value = name, onValueChange = { name = it },
                focusRequester = firstFocus,
                modifier = Modifier.fillMaxWidth()
            )
            TvTextField(
                label = stringResource(R.string.field_host),
                value = host, onValueChange = { host = it },
                uri = true,
                modifier = Modifier.fillMaxWidth()
            )
            TvTextField(
                label = stringResource(R.string.field_port),
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(), numeric = true
            )
            DropdownField(
                label = stringResource(R.string.field_conn_mode),
                value = connMode,
                options = listOf("http", "htsp"),
                optionLabel = {
                    if (it == "htsp") stringResource(R.string.conn_htsp)
                    else stringResource(R.string.conn_http)
                },
                onSelect = { connMode = it }
            )
            if (connMode == "htsp") {
                TvTextField(
                    label = stringResource(R.string.field_htsp_port),
                    value = htspPort, onValueChange = { htspPort = it.filter(Char::isDigit) },
                    modifier = Modifier.fillMaxWidth(), numeric = true
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadFocusable()
                    .clickable { useHttps = !useHttps }
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = useHttps, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.field_https))
            }
            TvTextField(
                label = stringResource(R.string.field_username),
                value = username, onValueChange = { username = it },
                modifier = Modifier.fillMaxWidth()
            )
            TvTextField(
                label = stringResource(R.string.field_password),
                value = password, onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(), password = true
            )
            if (connMode != "htsp") {
                DropdownField(
                    label = stringResource(R.string.field_profile),
                    value = profile,
                    // M380: zoznam zo servera (vratane vlastnych transcode
                    // profilov) tak, ako ho vratil; ak sa nenacital, fallback
                    // na predvolene profily Tvheadendu (ChannelPrefs, M379).
                    options = serverProfiles.ifEmpty {
                        ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() }
                    },
                    optionLabel = { it },
                    onSelect = { profile = it }
                )
            }
            DropdownField(
                label = stringResource(R.string.field_auth),
                value = authMode,
                options = listOf("auto", "basic", "digest", "none"),
                optionLabel = {
                    when (it) {
                        "auto" -> stringResource(R.string.auth_auto)
                        "basic" -> stringResource(R.string.auth_basic)
                        "digest" -> stringResource(R.string.auth_digest)
                        else -> stringResource(R.string.auth_none)
                    }
                },
                onSelect = { authMode = it }
            )

            TestResultView(testState)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { buildServer()?.let(vm::test) },
                    enabled = testState !is TestState.Running
                ) {
                    Text(stringResource(R.string.test_connection))
                }
                Button(onClick = { buildServer()?.let { vm.save(it); onClose() } }) {
                    Text(stringResource(R.string.save))
                }
                TextButton(onClick = onClose) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    }
}

@Composable
fun TestResultView(state: TestState) {
    when (state) {
        is TestState.Idle -> {}
        is TestState.Running -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.testing))
        }
        is TestState.Done -> when (val r = state.result) {
            is ConnectionResult.Success -> Text(
                stringResource(
                    R.string.test_ok,
                    r.info.swVersion ?: "?",
                    r.info.apiVersion ?: 0
                ),
                color = MaterialTheme.colorScheme.primary
            )
            is ConnectionResult.AuthFailed -> Text(
                stringResource(R.string.test_auth_failed),
                color = MaterialTheme.colorScheme.error
            )
            is ConnectionResult.HttpError -> Text(
                stringResource(R.string.test_http_error, r.httpCode),
                color = MaterialTheme.colorScheme.error
            )
            is ConnectionResult.NetworkError -> Text(
                stringResource(R.string.test_network_error),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// TV-friendly rozbalovacie pole: kotva je obycajny Box (ziadne textove pole =>
// na boxoch nevyskoci klavesnica), vyber prebieha v dialogu s manualnou D-pad
// navigaciou (sipky + OK), co na lacnych boxoch funguje spolahlivo.
@Composable
fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    optionLabel: @Composable (String) -> String,
    onSelect: (String) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    if (isModernUi()) {
        // Moderny rezim: riadok — nazov vlavo, hodnota ako pilulka s sipkou vpravo
        val cs = MaterialTheme.colorScheme
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .dpadFocusable(RoundedCornerShape(10.dp))
                .clickable { open = true }
                .padding(vertical = 12.dp, horizontal = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 2
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (isLightTheme()) cs.surfaceContainer else cs.surfaceContainerHigh)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    optionLabel(value) + "  \u25BE",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    color = cs.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    } else {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .dpadFocusable()
                .clickable { open = true }
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(4.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                optionLabel(value),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
    }
    if (open) {
        TvSelectDialog(label, options, value, optionLabel, { open = false }) {
            onSelect(it); open = false
        }
    }
}

@Composable
private fun TvSelectDialog(
    title: String,
    options: List<String>,
    current: String,
    optionLabel: @Composable (String) -> String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var sel by remember { mutableStateOf(options.indexOf(current).coerceAtLeast(0)) }
    val fr = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        runCatching { listState.scrollToItem(sel) }
        runCatching { fr.requestFocus() }
    }
    // pri pohybe sipkami drz vybranu polozku vo viditelnej casti zoznamu
    LaunchedEffect(sel) { runCatching { listState.animateScrollToItem(sel) } }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.width(340.dp)
        ) {
            Column(
                Modifier
                    .padding(16.dp)
                    .focusRequester(fr)
                    .focusable()
                    .onPreviewKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (e.nativeKeyEvent.keyCode) {
                            android.view.KeyEvent.KEYCODE_DPAD_UP -> { sel = (sel - 1 + options.size) % options.size; true }
                            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> { sel = (sel + 1) % options.size; true }
                            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
                            android.view.KeyEvent.KEYCODE_ENTER,
                            android.view.KeyEvent.KEYCODE_NUMPAD_ENTER -> { onSelect(options[sel]); true }
                            else -> false
                        }
                    }
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.heightIn(max = 360.dp)
                ) {
                    itemsIndexed(options) { i, opt ->
                        val selected = i == sel
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else androidx.compose.ui.graphics.Color.Transparent
                                )
                                .clickable { onSelect(opt) }
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        ) {
                            Text(
                                optionLabel(opt),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary
                                else MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }
}
