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

// Server UI: list row, add/edit form, test result, dropdown field
// (extracted from MainActivity.kt for readability).

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
    // M502: a new server without a profile = "as per server setting". "pass" is a profile
    // for HTTP transport (MPEG-TS passthrough) and made no sense as a default — with
    // HTSP even less so, that carries elementary streams. WelcomeScreen has had it right
    // since M479, this form forgot about it.
    var profile by remember { mutableStateOf(existing?.profile ?: "") }
    // M486: DVR profile (the one recordings go into); empty = as per server setting
    var dvrConfig by remember { mutableStateOf(existing?.dvrConfig ?: "") }
    var authMode by remember { mutableStateOf(existing?.authMode ?: "auto") }
    var connMode by remember { mutableStateOf(existing?.connectionMode ?: "htsp") }
    var htspPort by remember { mutableStateOf((existing?.htspPort ?: 9982).toString()) }

    // Initial D-pad focus (TV) on the first field, so you can navigate top to bottom right away
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { firstFocus.requestFocus() }
    }

    val testState by vm.testState.collectAsState()
    val ctxReset = androidx.compose.ui.platform.LocalContext.current

    // M389: unsaved changes — comparison of the current fields with the values at open time.
    // On leaving (BACK / Cancel / tab switch via guardLeave) a confirmation is requested.
    fun formSig() = listOf(name, host, port, useHttps, username, password, profile, dvrConfig, authMode, connMode, htspPort)
    val initSig = remember { formSig() }
    val dirty = formSig() != initSig
    var leaveAsk by remember { mutableStateOf(false) }
    fun requestClose() { if (dirty) leaveAsk = true else onClose() }
    LaunchedEffect(dirty) {
        TabController.settingsDirty.value = dirty
        TabController.settingsDirtyUnsaved.value = dirty
    }
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            TabController.settingsDirty.value = false
            TabController.settingsDirtyUnsaved.value = false
        }
    }
    androidx.activity.compose.BackHandler { requestClose() }
    if (leaveAsk) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { leaveAsk = false },
            title = { Text(stringResource(R.string.srv_leave_title)) },
            text = { Text(stringResource(R.string.srv_leave_msg)) },
            confirmButton = {
                TextButton(onClick = { leaveAsk = false; onClose() }) {
                    Text(stringResource(R.string.srv_leave_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { leaveAsk = false }) {
                    Text(stringResource(R.string.set_leave_no))
                }
            }
        )
    }

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
            // M502: empty lets the server decide; do NOT rewrite it to "pass" —
            // that is exactly what returned the HTTP profile even for servers set to HTSP
            profile = profile.trim(),
            dvrConfig = dvrConfig.trim(),
            authMode = authMode,
            connectionMode = connMode,
            htspPort = htspPort.toIntOrNull() ?: 9982
        )
    }

    // M380: profiles are fetched from the server automatically — no button.
    // An existing server (with stored credentials) is loaded right after opening;
    // a new one as soon as the address and credentials are filled in. Debounce 800 ms, so
    // a request is not fired on every letter. Failure = silence, the fallback stays.
    val serverProfiles by vm.profiles.collectAsState()
    // M486: DVR profiles are fetched the same way as stream profiles
    val serverDvrConfigs by vm.dvrConfigs.collectAsState()
    LaunchedEffect(Unit) { vm.clearProfiles(); vm.clearDvrConfigs() }
    LaunchedEffect(host, port, username, password, useHttps, authMode, connMode) {
        // M476: profiles are loaded for HTSP too — the protocol has its own
        // getProfiles (v16+), so no HTTP port is needed
        kotlinx.coroutines.delay(800)
        buildServer()?.let { vm.loadProfiles(it); vm.loadDvrConfigs(it) }
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
            // M478: the profile choice is only shown once the list has actually arrived from the server.
            // Previously it was shown immediately with a substitute list (pass, matroska...),
            // so when creating a server the user picked from profiles their server
            // may not have at all. When editing an already saved server we
            // always show it — the profile is part of the settings and must be changeable even
            // when the server happens to be unreachable.
            if (serverProfiles.isNotEmpty() || existing != null) {
                DropdownField(
                    label = stringResource(R.string.field_profile),
                    value = profile,
                    // M380: the list from the server (including custom transcode
                    // profiles) exactly as it returned it; fallback only when editing
                    // an existing server and the list could not be loaded.
                    // M479: the first option is empty = "as per server setting".
                    // For HTSP that is the correct default (the profile is determined by the account on the server),
                    // for HTTP it is legitimate too — the server uses its own default.
                    // M501: show the stored value even when the server does not have it
                    // in the list (e.g. the profile was renamed/removed). Otherwise the menu
                    // highlighted the first item and it looked as if "as per
                    // server" was set, although a name the server does not understand was being sent and it
                    // silently fell back to its own default profile.
                    options = (listOf("") + serverProfiles.ifEmpty {
                        ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() }
                    } + profile.trim()).distinct(),
                    optionLabel = { if (it.isBlank()) stringResource(R.string.profile_server_default) else it },
                    onSelect = { profile = it }
                )
                // M501: a profile the server does not offer is almost certainly a typo or
                // a leftover after a rename — the server ignores it and uses its own default
                if (serverProfiles.isNotEmpty() && profile.isNotBlank() &&
                    profile.trim() !in serverProfiles
                ) {
                    Text(
                        stringResource(R.string.profile_unknown_warn, profile.trim()),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                    )
                }
                // M382: not every profile can be played (container/codecs) —
                // e.g. Vorbis in MP4 is non-standard and the audio does not work in other
                // players either. The recommended one is pass (no transcoding).
                // M503: with HTSP the note takes a different form — "pass" is an HTTP profile
                // (MPEG-TS passthrough) and the server does not even offer it in the menu; HTSP
                // carries elementary streams and the profile is determined by the account on the server.
                Text(
                    stringResource(
                        if (connMode == "htsp") R.string.profile_note_htsp
                        else R.string.profile_note
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
                )
            }
            // M486: recording profile. We only show it when the server returned some
            // (or when editing an already saved server), same as the stream profile.
            // An empty choice = the server decides according to the account's rights.
            if (serverDvrConfigs.isNotEmpty() || (existing != null && dvrConfig.isNotBlank())) {
                DropdownField(
                    label = stringResource(R.string.field_dvr_config),
                    value = dvrConfig,
                    options = listOf("") + serverDvrConfigs.map { it.name }
                        .filter { it.isNotBlank() }.distinct(),
                    // M487: for the DVR profile it is not "as per server setting" —
                    // TVH always translates an empty name to the default profile, so let us
                    // name it that way too
                    optionLabel = {
                        if (it.isBlank()) stringResource(R.string.dvr_config_default) else it
                    },
                    onSelect = { dvrConfig = it }
                )
                Text(
                    stringResource(R.string.dvr_config_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 2.dp)
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
                Button(onClick = {
                    buildServer()?.let {
                        // M391: a change of connection method = a new namespace of identifiers
                        // -> discard the EPG cache, last channel/station and the in-memory playlist
                        val modeChanged = existing != null && existing.connectionMode != it.connectionMode
                        vm.save(it)
                        if (modeChanged) ServerDataReset.onConnectionModeChanged(ctxReset, it.id)
                        onClose()
                    }
                }) {
                    Text(stringResource(R.string.save))
                }
                TextButton(onClick = { requestClose() }) {
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
                // M692: the account's connection limit is not a network error
                if (ConnLimitText.isConnLimit(r.message)) stringResource(R.string.err_conn_limit)
                else stringResource(R.string.test_network_error),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

// TV-friendly dropdown field: the anchor is a plain Box (no text field =>
// no keyboard pops up on boxes), selection happens in a dialog with manual D-pad
// navigation (arrows + OK), which works reliably on cheap boxes.
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
        // Modern mode: a row — name on the left, value as a pill with an arrow on the right
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
    // M380-fix: recompute the selection index when the list of options changes — profiles
    // are fetched from the server asynchronously, so they can be swapped under an open
    // dialog (fallback -> list from the server). Without key(options) the cursor
    // would stay on the old index and point at a different profile.
    var sel by remember(options) { mutableStateOf(options.indexOf(current).coerceAtLeast(0)) }
    val fr = remember { FocusRequester() }
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        runCatching { listState.scrollToItem(sel) }
        runCatching { fr.requestFocus() }
    }
    // while moving with the arrows keep the selected item in the visible part of the list
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
