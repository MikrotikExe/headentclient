package sk.tvhclient.android

import android.content.Intent
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Dvr
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sk.tvhclient.shared.api.ConnectionResult
import sk.tvhclient.shared.model.TvhServer

/** Most medzi farebnymi tlacidlami dialkoveho (dispatchKeyEvent) a Compose tabmi. */
object TabController {
    val requested = mutableStateOf(-1)
    fun request(tab: Int) { requested.value = tab }
    // EPG (TV program) kláves dialkoveho -> otvor mriezku v Kanaloch
    val epgGrid = mutableStateOf(0)
    fun openEpgGrid() { epgGrid.value = epgGrid.value + 1 }
    // INFO kláves -> detail vybranej relacie (v mriezke)
    val infoKey = mutableStateOf(0)
    fun pressInfo() { infoKey.value = infoKey.value + 1 }
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("open_epg", false) == true) {
            TabController.request(0); TabController.openEpgGrid()
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_epg", false)) {
            TabController.request(0); TabController.openEpgGrid()
        }
    }

    // Farebne tlacidla na dialkovom -> prepnutie tabu
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val t = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_PROG_RED -> 0     // Kanaly
                android.view.KeyEvent.KEYCODE_PROG_GREEN -> 1   // Radio
                android.view.KeyEvent.KEYCODE_PROG_YELLOW -> 2  // Archiv
                android.view.KeyEvent.KEYCODE_PROG_BLUE -> 3    // Nastavenie
                else -> -1
            }
            if (t >= 0) { TabController.request(t); return true }
            when (event.keyCode) {
                // EPG / TV program kláves (ikona vlavo od 0)
                android.view.KeyEvent.KEYCODE_GUIDE,
                android.view.KeyEvent.KEYCODE_TV_DATA_SERVICE -> {
                    TabController.request(0); TabController.openEpgGrid(); return true
                }
                // INFO kláves (ikona vpravo od 0)
                android.view.KeyEvent.KEYCODE_INFO -> { TabController.pressInfo(); return true }
            }
        }
        return super.dispatchKeyEvent(event)
    }
}

@Composable
fun App() {
    val serversVm: ServersViewModel = viewModel()
    val servers by serversVm.servers.collectAsState()
    // Ziadny server -> uvitacia obrazovka; inak hlavne taby
    if (servers.isEmpty()) WelcomeScreen(serversVm) else AppMain()
}

@OptIn(ExperimentalMaterial3Api::class)
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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(24.dp))
        // Logo (zatial TV ikona — nahradit vlastnym logom)
        androidx.compose.material3.Icon(
            Icons.Default.LiveTv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(72.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("TVHeadend Client", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(32.dp))

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
                        contentDescription = null
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
        }
    }
}

@Composable
private fun TabLabel(dot: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(6.dp))
        Text(text)
    }
}

@Composable
fun AppMain() {
    var tab by remember { mutableStateOf(0) }
    // Reset signaly: klik na tab (aj uz vybrany) vrati danu obrazovku na zaciatok
    var resetCh by remember { mutableStateOf(0) }
    var resetDvr by remember { mutableStateOf(0) }
    val navFocus = remember { FocusRequester() }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var showExit by remember { mutableStateOf(false) }

    // Farebne tlacidla na dialkovom (cez TabController) prepnu tab
    val reqTab by TabController.requested
    LaunchedEffect(reqTab) {
        if (reqTab in 0..3) {
            when (reqTab) {
                0 -> { resetCh++; tab = 0 }
                2 -> { resetDvr++; tab = 2 }
                else -> tab = reqTab
            }
            TabController.requested.value = -1
        }
    }

    // Spat: z ineho tabu spat na Kanaly; na Kanaloch -> potvrdenie ukoncenia.
    // (Vnutorne obrazovky maju vlastny BackHandler, ten ma prednost.)
    androidx.activity.compose.BackHandler(enabled = !showExit) {
        if (tab != 0) { resetCh++; tab = 0 } else showExit = true
    }

    val red = Color(0xFFE53935)
    val green = Color(0xFF43A047)
    val yellow = Color(0xFFFDD835)
    val blue = Color(0xFF1E88E5)

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { resetCh++; tab = 0 },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.LiveTv, contentDescription = null) },
                    label = { TabLabel(red, stringResource(R.string.tab_channels)) },
                    modifier = Modifier.focusRequester(navFocus)
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.Radio, contentDescription = null) },
                    label = { TabLabel(green, stringResource(R.string.tab_radio)) }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { resetDvr++; tab = 2 },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.Dvr, contentDescription = null) },
                    label = { TabLabel(yellow, stringResource(R.string.tab_dvr)) }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.Dns, contentDescription = null) },
                    label = { TabLabel(blue, stringResource(R.string.tab_settings)) }
                )
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                0 -> ChannelsScreen(
                    resetSignal = resetCh,
                    onGoToNav = { runCatching { navFocus.requestFocus() } }
                )
                1 -> RadioScreen()
                2 -> DvrScreen(resetSignal = resetDvr)
                else -> {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    var unlocked by remember { mutableStateOf(!ParentalLock.needsPin(ctx)) }
                    if (unlocked) {
                        ServersTab()
                    } else {
                        PinDialog(
                            title = stringResource(R.string.plock_unlock_settings),
                            onDismiss = { tab = 0 },
                            onComplete = { pin ->
                                if (ParentalLock.checkPin(ctx, pin)) {
                                    ParentalLock.markUnlocked(ctx); unlocked = true; true
                                } else false
                            }
                        )
                    }
                }
            }
        }
    }

    if (showExit) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text(stringResource(R.string.exit_title)) },
            text = { Text(stringResource(R.string.exit_msg)) },
            confirmButton = {
                TextButton(onClick = { showExit = false; activity?.finish() }) {
                    Text(stringResource(R.string.exit_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExit = false }) {
                    Text(stringResource(R.string.exit_no))
                }
            }
        )
    }
}

@Composable
fun ServersTab(vm: ServersViewModel = viewModel()) {
    var editing by remember { mutableStateOf<TvhServer?>(null) }
    var showForm by remember { mutableStateOf(false) }

    if (showForm) {
        ServerForm(
            vm = vm,
            existing = editing,
            onClose = {
                showForm = false
                editing = null
                vm.resetTest()
            }
        )
    } else {
        ServerList(
            vm = vm,
            onAdd = { editing = null; showForm = true },
            onEdit = { editing = it; showForm = true }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerList(vm: ServersViewModel, onAdd: () -> Unit, onEdit: (TvhServer) -> Unit) {
    val servers by vm.servers.collectAsState()
    val activeId by vm.activeId.collectAsState()

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Vyber jazyka appky (Systém/SK/CZ/EN) — zmena restartuje aktivitu
            val ctx = androidx.compose.ui.platform.LocalContext.current
            var lang by remember { mutableStateOf(LocaleHelper.getLang(ctx)) }
            DropdownField(
                label = stringResource(R.string.language),
                value = lang,
                options = listOf("", "sk", "cs", "en"),
                optionLabel = {
                    when (it) {
                        "sk" -> "Slovenčina"
                        "cs" -> "Čeština"
                        "en" -> "English"
                        else -> stringResource(R.string.lang_system)
                    }
                },
                onSelect = {
                    if (it != lang) {
                        lang = it
                        LocaleHelper.setLang(ctx, it)
                        (ctx as? android.app.Activity)?.recreate()
                    }
                }
            )
            Spacer(Modifier.height(16.dp))

            // Predvolene audio stopy (priorita 1-3)
            Text(stringResource(R.string.audio_pref_title),
                style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            var audio by remember {
                mutableStateOf(AudioPref.get(ctx).let { l -> List(3) { l.getOrNull(it) ?: "" } })
            }
            fun setSlot(i: Int, code: String) {
                val list = audio.toMutableList()
                list[i] = code
                audio = list
                AudioPref.set(ctx, list)
            }
            val audioLabels: @Composable (String) -> String = { code ->
                AudioPref.options.firstOrNull { it.first == code }?.second ?: "—"
            }
            val audioOptions = AudioPref.options.map { it.first }
            DropdownField(stringResource(R.string.audio_pref_1), audio[0], audioOptions, audioLabels) { setSlot(0, it) }
            DropdownField(stringResource(R.string.audio_pref_2), audio[1], audioOptions, audioLabels) { setSlot(1, it) }
            DropdownField(stringResource(R.string.audio_pref_3), audio[2], audioOptions, audioLabels) { setSlot(2, it) }
            Spacer(Modifier.height(16.dp))

            // Rodicovsky zamok (PIN)
            Text(stringResource(R.string.plock_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            var lockEnabled by remember { mutableStateOf(ParentalLock.isEnabled(ctx)) }
            var pinStage by remember { mutableStateOf(0) }   // 0=ziadny, 1=novy, 2=potvrdit
            var firstPin by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = lockEnabled,
                    onCheckedChange = { on ->
                        if (on) {
                            if (ParentalLock.hasPin(ctx)) {
                                ParentalLock.setEnabled(ctx, true); lockEnabled = true
                            } else pinStage = 1  // najprv nastav PIN
                        } else {
                            ParentalLock.setEnabled(ctx, false); lockEnabled = false
                        }
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.plock_enable))
            }
            OutlinedButton(
                onClick = { firstPin = ""; pinStage = 1 },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text(
                    if (ParentalLock.hasPin(ctx)) stringResource(R.string.plock_change_pin)
                    else stringResource(R.string.plock_set_pin)
                )
            }
            if (pinStage == 1) {
                PinDialog(
                    title = stringResource(R.string.plock_enter_new),
                    onDismiss = { pinStage = 0 },
                    onComplete = { pin -> firstPin = pin; pinStage = 2; true }
                )
            } else if (pinStage == 2) {
                PinDialog(
                    title = stringResource(R.string.plock_confirm),
                    onDismiss = { pinStage = 0; firstPin = "" },
                    onComplete = { pin ->
                        if (pin == firstPin) {
                            ParentalLock.setPin(ctx, pin)
                            ParentalLock.setEnabled(ctx, true); lockEnabled = true
                            pinStage = 0; firstPin = ""; true
                        } else { firstPin = ""; pinStage = 1; true }  // nezhoda -> zadaj odznova
                    }
                )
            }
            Spacer(Modifier.height(16.dp))

            // Automaticke spustenie po zapnuti zariadenia
            Text(stringResource(R.string.autostart_title), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            // Povolenie "zobrazenie nad ostatnymi aplikaciami" — vdaka nemu sa
            // appka smie spustit z pozadia (boot / prebudenie). Bez neho to Android blokuje.
            fun requestOverlay() {
                if (android.os.Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(ctx)) {
                    val i = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + ctx.packageName)
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    if (runCatching { ctx.startActivity(i) }.isFailure) {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }
                    }
                }
            }
            var autostart by remember { mutableStateOf(AutostartPref.isEnabled(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autostart,
                    onCheckedChange = { on ->
                        autostart = on; AutostartPref.setEnabled(ctx, on)
                        if (on) requestOverlay()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.autostart_enable))
            }
            var autostartWake by remember { mutableStateOf(AutostartPref.isWakeEnabled(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = autostartWake,
                    onCheckedChange = { on ->
                        autostartWake = on; AutostartPref.setWakeEnabled(ctx, on)
                        if (on) requestOverlay()
                    }
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.autostart_wake))
            }
            Text(
                stringResource(R.string.autostart_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(16.dp))
            if (servers.isEmpty()) {
                Text(stringResource(R.string.no_servers))
                Spacer(Modifier.height(16.dp))
            } else {
                servers.forEach { server ->
                    ServerRow(
                        server = server,
                        isActive = server.id == activeId,
                        onSelect = { vm.setActive(server.id) },
                        onEdit = { onEdit(server) },
                        onDelete = { vm.delete(server.id) }
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.add_server))
            }
        }
    }
}

@Composable
fun ServerRow(
    server: TvhServer,
    isActive: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
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
                TextButton(onClick = onEdit) { Text(stringResource(R.string.edit)) }
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
    var connMode by remember { mutableStateOf(existing?.connectionMode ?: "http") }
    var htspPort by remember { mutableStateOf((existing?.htspPort ?: 9982).toString()) }

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
                modifier = Modifier.fillMaxWidth()
            )
            TvTextField(
                label = stringResource(R.string.field_host),
                value = host, onValueChange = { host = it },
                modifier = Modifier.fillMaxWidth()
            )
            TvTextField(
                label = stringResource(R.string.field_port),
                value = port, onValueChange = { port = it.filter(Char::isDigit) },
                modifier = Modifier.width(160.dp), numeric = true
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
                    modifier = Modifier.width(160.dp), numeric = true
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = useHttps, onCheckedChange = { useHttps = it })
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
            DropdownField(
                label = stringResource(R.string.field_profile),
                value = profile,
                options = listOf(
                    "pass", "mpegts", "matroska",
                    "webtv-h264-aac-matroska", "webtv-h264-aac-mpegts"
                ),
                optionLabel = { it },
                onSelect = { profile = it }
            )
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
                stringResource(R.string.test_network_error, r.message),
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DropdownField(
    label: String,
    value: String,
    options: List<String>,
    optionLabel: @Composable (String) -> String,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.material3.ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = optionLabel(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor()
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(optionLabel(opt)) },
                    onClick = { onSelect(opt); expanded = false }
                )
            }
        }
    }
}
