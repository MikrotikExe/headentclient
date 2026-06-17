package sk.tvhclient.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Dvr
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
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
    // ci boli v aktualnej podsekcii nastaveni vykonane zmeny (kvoli potvrdeniu pri odchode)
    val settingsDirty = mutableStateOf(false)
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent?.getBooleanExtra("open_epg", false) == true) {
            TabController.openEpgGrid()
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
            TabController.openEpgGrid()
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
                android.view.KeyEvent.KEYCODE_TV_DATA_SERVICE,
                android.view.KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                android.view.KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU -> {
                    TabController.openEpgGrid(); return true
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
fun BackupControls(compact: Boolean = false, onImported: () -> Unit = {}) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            val ok = runCatching {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(Backup.export(ctx).toByteArray()) }
            }.isSuccess
            Toast.makeText(
                ctx, ctx.getString(if (ok) R.string.backup_exported else R.string.backup_failed),
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            val ok = text != null && Backup.import(ctx, text)
            Toast.makeText(
                ctx, ctx.getString(if (ok) R.string.backup_imported else R.string.backup_failed),
                Toast.LENGTH_LONG
            ).show()
            if (ok) {
                onImported()
                // znovu vykresli appku (nacita obnovene servery aj jazyk), bez zabitia procesu
                (ctx as? android.app.Activity)?.recreate()
            }
        }
    }
    if (compact) {
        androidx.compose.material3.TextButton(
            onClick = { runCatching { importLauncher.launch(arrayOf("*/*")) } }
        ) { Text(stringResource(R.string.backup_restore)) }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { runCatching { exportLauncher.launch("tvhclient-zaloha.json") } }) {
                Text(stringResource(R.string.backup_export))
            }
            OutlinedButton(onClick = { runCatching { importLauncher.launch(arrayOf("*/*")) } }) {
                Text(stringResource(R.string.backup_import))
            }
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
    var resetSet by remember { mutableStateOf(0) }
    val navFocus = remember { FocusRequester() }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var showExit by remember { mutableStateOf(false) }
    // odchod z nastaveni s neulozenymi/vykonanymi zmenami -> potvrdenie
    var leaveConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guardLeave(action: () -> Unit) {
        if (tab == 3 && TabController.settingsDirty.value) leaveConfirm = action else action()
    }

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

    // EPG kláves -> prepni na Kanaly (bez resetu, nech zostane mriezka otvorena)
    val epgSig by TabController.epgGrid
    LaunchedEffect(epgSig) { if (epgSig > 0) tab = 0 }

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
                    onClick = { guardLeave { resetCh++; tab = 0 } },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.LiveTv, contentDescription = null) },
                    label = { TabLabel(red, stringResource(R.string.tab_channels)) },
                    modifier = Modifier.focusRequester(navFocus)
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { guardLeave { tab = 1 } },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.Radio, contentDescription = null) },
                    label = { TabLabel(green, stringResource(R.string.tab_radio)) }
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { guardLeave { resetDvr++; tab = 2 } },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.Dvr, contentDescription = null) },
                    label = { TabLabel(yellow, stringResource(R.string.tab_dvr)) }
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = {
                        if (tab == 3) guardLeave { resetSet++ }   // re-tap: spat na koren nastaveni
                        else tab = 3
                    },
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
                        ServersTab(resetSignal = resetSet)
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

    leaveConfirm?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { leaveConfirm = null },
            title = { Text(stringResource(R.string.set_leave_title)) },
            text = { Text(stringResource(R.string.set_leave_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    TabController.settingsDirty.value = false
                    leaveConfirm = null
                    action()
                }) { Text(stringResource(R.string.set_leave_yes)) }
            },
            dismissButton = {
                TextButton(onClick = { leaveConfirm = null }) {
                    Text(stringResource(R.string.set_leave_no))
                }
            }
        )
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
fun ServersTab(vm: ServersViewModel = viewModel(), resetSignal: Int = 0) {
    var editing by remember { mutableStateOf<TvhServer?>(null) }
    var showForm by remember { mutableStateOf(false) }

    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) { showForm = false; editing = null }
    }

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
            resetSignal = resetSignal,
            onAdd = { editing = null; showForm = true },
            onEdit = { editing = it; showForm = true }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerList(vm: ServersViewModel, resetSignal: Int = 0, onAdd: () -> Unit, onEdit: (TvhServer) -> Unit) {
    val servers by vm.servers.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var section by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) { section = null; TabController.settingsDirty.value = false }
    }
    // pri kazdej zmene sekcie zacni s "ciste" (zmeny oznaci az uzivatelska akcia)
    LaunchedEffect(section) { TabController.settingsDirty.value = false }

    BackHandler(enabled = section != null) {
        section = null; TabController.settingsDirty.value = false
    }

    val title = when (section) {
        "general" -> stringResource(R.string.set_cat_general)
        "playback" -> stringResource(R.string.set_cat_playback)
        "playlist" -> stringResource(R.string.set_cat_playlist)
        "servers" -> stringResource(R.string.set_cat_servers)
        "remote" -> stringResource(R.string.set_cat_remote)
        "info" -> stringResource(R.string.set_cat_info)
        else -> stringResource(R.string.tab_settings)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    if (section != null) {
                        androidx.compose.material3.IconButton(onClick = {
                            section = null; TabController.settingsDirty.value = false
                        }) {
                            Text("\u2039", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            when (section) {
                null -> {
                    SettingsCategory(stringResource(R.string.set_cat_general)) { section = "general" }
                    SettingsCategory(stringResource(R.string.set_cat_playback)) { section = "playback" }
                    SettingsCategory(stringResource(R.string.set_cat_playlist)) { section = "playlist" }
                    SettingsCategory(stringResource(R.string.set_cat_servers)) { section = "servers" }
                    SettingsCategory(stringResource(R.string.set_cat_remote)) { section = "remote" }
                    SettingsCategory(stringResource(R.string.set_cat_info)) { section = "info" }
                }
                "general" -> GeneralSettings(ctx)
                "playback" -> PlaybackSettings(ctx)
                "playlist" -> PlaylistSettings(ctx)
                "servers" -> ServersSettings(vm, servers, activeId, onAdd, onEdit)
                "remote" -> RemoteSettings(ctx)
                "info" -> InfoSettings(ctx, servers, activeId)
            }
        }
    }
}


