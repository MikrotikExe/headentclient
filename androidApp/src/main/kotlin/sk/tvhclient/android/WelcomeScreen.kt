package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import sk.tvhclient.shared.api.ConnectionResult
import sk.tvhclient.shared.model.TvhServer

// Uvodna obrazovka / onboarding (pridanie prveho servera). Vyclenene z MainActivity.kt.

@Composable
fun WelcomeScreen(vm: ServersViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var host by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPw by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    // pokrocile
    var name by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9981") }
    var useHttps by remember { mutableStateOf(false) }
    var authMode by remember { mutableStateOf("auto") }
    var connMode by remember { mutableStateOf("http") }
    var htspPort by remember { mutableStateOf("9982") }
    var profile by remember { mutableStateOf("pass") }
    var localError by remember { mutableStateOf(false) }

    val testState by vm.testState.collectAsState()
    var pending by remember { mutableStateOf<TvhServer?>(null) }

    // Po uspesnom teste uloz server a vojdi do appky
    LaunchedEffect(testState) {
        val st = testState
        if (st is TestState.Done && st.result is ConnectionResult.Success) {
            pending?.let { vm.save(it); vm.setActive(it.id); vm.resetTest() }
            pending = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1B1430), Color(0xFF120F1A), Color(0xFF0C0B10))
                )
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(40.dp))
        // Logo v jemnom zaoblenom rámiku
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            androidx.compose.material3.Icon(
                Icons.Default.LiveTv,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(54.dp)
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(
            "Headent Client",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(36.dp))

        OutlinedTextField(
            value = host, onValueChange = { host = it; localError = false },
            label = { Text(stringResource(R.string.field_host)) },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Dns, null) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = username, onValueChange = { username = it },
            label = { Text(stringResource(R.string.field_username)) },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Person, null) },
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text(stringResource(R.string.field_password)) },
            leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Lock, null) },
            trailingIcon = {
                androidx.compose.material3.IconButton(onClick = { showPw = !showPw }) {
                    androidx.compose.material3.Icon(
                        if (showPw) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = stringResource(
                            if (showPw) R.string.hide_password else R.string.show_password
                        )
                    )
                }
            },
            visualTransformation = if (showPw) androidx.compose.ui.text.input.VisualTransformation.None
            else PasswordVisualTransformation(),
            singleLine = true, modifier = Modifier.fillMaxWidth()
        )

        if (localError) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.field_host) + " ?",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(12.dp))
        TestResultView(testState)
        Spacer(Modifier.height(12.dp))

        Button(
            onClick = {
                val p = port.toIntOrNull()
                if (host.isBlank() || p == null) {
                    localError = true
                } else {
                    val srv = TvhServer(
                        id = vm.newId(),
                        name = name.ifBlank { host.trim() },
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
                    pending = srv
                    vm.test(srv)
                }
            },
            enabled = testState !is TestState.Running,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.login_connect))
        }

        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) {
            Text(stringResource(R.string.login_more_options))
        }

        if (advanced) {
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = name, onValueChange = { name = it },
                label = { Text(stringResource(R.string.field_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = port, onValueChange = { port = it },
                label = { Text(stringResource(R.string.field_port)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = stringResource(R.string.field_conn_mode),
                value = connMode,
                options = listOf("http", "htsp"),
                optionLabel = {
                    if (it == "htsp") stringResource(R.string.conn_htsp)
                    else stringResource(R.string.conn_http)
                }
            ) { connMode = it }
            if (connMode == "htsp") {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = htspPort, onValueChange = { htspPort = it },
                    label = { Text(stringResource(R.string.field_htsp_port)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth()
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useHttps, onCheckedChange = { useHttps = it })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.field_https))
            }
            Spacer(Modifier.height(10.dp))
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
                }
            ) { authMode = it }
            Spacer(Modifier.height(10.dp))
            DropdownField(
                label = stringResource(R.string.field_profile),
                value = profile,
                options = ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() },
                optionLabel = { it }
            ) { profile = it }
            Spacer(Modifier.height(8.dp))
            // skryta moznost: obnova nastaveni zo zalohy
            BackupControls(compact = true, onImported = { vm.refresh() })
        }
    }
}

