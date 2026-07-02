package sk.tvhclient.android

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.focusGroup
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.Dvr
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
    var epgFromPlayer = false
    var epgReturnUuid: String? = null
    fun openEpgGrid(fromPlayer: Boolean = false, returnUuid: String? = null) {
        epgFromPlayer = fromPlayer
        epgReturnUuid = returnUuid
        epgGrid.value = epgGrid.value + 1
    }
    // INFO kláves -> detail vybranej relacie (v mriezke)
    val infoKey = mutableStateOf(0)
    fun pressInfo() { infoKey.value = infoKey.value + 1 }
    // ci boli v aktualnej podsekcii nastaveni vykonane zmeny (kvoli potvrdeniu pri odchode)
    val settingsDirty = mutableStateOf(false)
    // zvysenim sa vynuti znovunacitanie kanalov/radii/archivu/EPG (po ulozeni/zmene servera)
    val dataReload = mutableStateOf(0)
}

class MainActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kresli pod systemove pruhy (edge-to-edge), aby pozadie appky vyplnilo celu obrazovku
        // vratane oblasti navigacneho pruhu / okolo klavesnice (inak tam vznikal cierny pruh).
        // Pruhy su priehladne -> presviti cez ne pozadie okna (surface), takze vyzeraju vo farbe povrchu.
        // enableEdgeToEdge je moderna nahrada za setDecorFitsSystemWindows + window.statusBarColor
        // (tie su od Androidu 15 / SDK 35 zastarale a ignorovane).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT
            )
        )
        if (intent?.getBooleanExtra("open_epg", false) == true) {
            TabController.openEpgGrid(fromPlayer = true, returnUuid = intent.getStringExtra("epg_return_uuid"))
        }
        setContent {
            val themeMode = ThemePref.stateOf(this).value
            val dark = when (themeMode) {
                ThemePref.DARK -> true
                ThemePref.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // Moderny rezim ma vlastnu navy/teal paletu (vzdy tmavu); inak svetla/tmava tema
            val modernUi = UiModePref.stateOf(this).value == UiModePref.MODERN
            MaterialTheme(colorScheme = if (modernUi) modernColorScheme() else if (dark) darkColorScheme() else lightColorScheme()) {
                val view = LocalView.current
                val barColor = MaterialTheme.colorScheme.surface
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as android.app.Activity).window
                        // Pozadie okna na farbu povrchu, aby nepokryta plocha (napr. pri vyskoceni
                        // klavesnice, ked sa okno zmensi) neukazovala cierny pruh. Priehladne
                        // systemove pruhy potom presvitaju touto farbou (nahrada za zastarale
                        // window.statusBarColor / navigationBarColor, ktore SDK 35 ignoruje).
                        window.setBackgroundDrawable(
                            android.graphics.drawable.ColorDrawable(barColor.toArgb())
                        )
                        WindowCompat.getInsetsController(window, view).apply {
                            isAppearanceLightStatusBars = !dark
                            isAppearanceLightNavigationBars = !dark
                        }
                    }
                }
                App()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_epg", false)) {
            TabController.openEpgGrid(fromPlayer = true, returnUuid = intent.getStringExtra("epg_return_uuid"))
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
                android.view.KeyEvent.KEYCODE_CAPTIONS,
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
    // Ziadny server -> uvitacia obrazovka
    if (servers.isEmpty()) { WelcomeScreen(serversVm); return }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isTv = remember {
        val um = ctx.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    if (isTv) TvHomeHost() else AppMain()
}

/** TV/box: uvodny launcher + samostatne sekcie (bez spodneho baru). Spat = launcher.
 *  Kanaly/Radia idu rovno do prehravaca (zoznam sa prednacita a naplni LivePlaylist,
 *  aby fungoval prepinaci zoznam v prehravaci). */
@Composable
private fun TvHomeHost() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val chVm: ChannelsViewModel = viewModel()
    val raVm: RadioViewModel = viewModel()
    val chState by chVm.state.collectAsState()
    val epgMap by chVm.epgMap.collectAsState()
    val raState by raVm.state.collectAsState()
    LaunchedEffect(Unit) { chVm.loadIfNeeded(); raVm.load() }   // prednacitaj kanaly aj radia

    // sekcia: "", "epg", "archive", "settings"; play: "", "tv", "radio"
    var section by remember { mutableStateOf("") }
    var lastTile by remember { mutableStateOf("channels") }
    var play by remember { mutableStateOf("") }
    var showExit by remember { mutableStateOf(false) }

    fun playUuid(uuid: String, title: String, kind: String = "tv") {
        runCatching {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_UUID, uuid)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_KIND, kind)
            })
        }
    }

    // Kanaly: po nacitani naplni LivePlaylist a pusti posledny/prvy kanal
    LaunchedEffect(play, chState) {
        if (play == "tv") {
            val st = chState
            if (st is ChannelsState.Loaded) {
                val sid = sk.tvhclient.shared.Tvh.store.active()?.id
                val hidden = HiddenChannels.all(ctx, sid)
                LivePlaylist.channels = st.allRows.filter { it.channel.uuid !in hidden }.map { r ->
                    LivePlaylist.LiveChannel(
                        uuid = r.channel.uuid, name = r.channel.name,
                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                        nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                    )
                }
                val target = LastChannel.get(ctx, sid)
                    ?.takeIf { u -> LivePlaylist.channels.any { it.uuid == u } }
                    ?: LivePlaylist.channels.firstOrNull()?.uuid
                play = ""
                if (target != null) {
                    LivePlaylist.setIndexForUuid(target)
                    playUuid(target, LivePlaylist.channels.firstOrNull { it.uuid == target }?.name ?: "")
                }
            } else if (st is ChannelsState.Error || st is ChannelsState.NoServer) {
                play = ""
            }
        }
    }
    // Radia: po nacitani naplni LivePlaylist a pusti poslednu/prvu stanicu
    LaunchedEffect(play, raState) {
        if (play == "radio") {
            val st = raState
            if (st is RadioState.Loaded) {
                val sid = sk.tvhclient.shared.Tvh.store.active()?.id
                LivePlaylist.channels = st.rows.map { r ->
                    LivePlaylist.LiveChannel(
                        uuid = r.channel.uuid, name = r.channel.name,
                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                        nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                    )
                }
                val target = LastRadio.get(ctx, sid)
                    ?.takeIf { u -> LivePlaylist.channels.any { it.uuid == u } }
                    ?: LivePlaylist.channels.firstOrNull()?.uuid
                play = ""
                if (target != null) {
                    LivePlaylist.setIndexForUuid(target)
                    playUuid(target, LivePlaylist.channels.firstOrNull { it.uuid == target }?.name ?: "", "radio")
                }
            } else if (st is RadioState.Error || st is RadioState.NoServer) {
                play = ""
            }
        }
    }

    when {
        section == "epg" -> {
            androidx.activity.compose.BackHandler { section = "" }
            val st = chState
            if (st is ChannelsState.Loaded) {
                EpgGridScreen(rows = st.allRows, seed = epgMap, onBack = { section = "" })
            } else {
                CenterLoading()
            }
        }
        section == "archive" -> {
            TvArchiveScreen(onBack = { section = "" })
        }
        section == "settings" -> {
            androidx.activity.compose.BackHandler { section = "" }
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = androidx.compose.material3.MaterialTheme.colorScheme.background
            ) { ServersTab() }
        }
        else -> {
            androidx.activity.compose.BackHandler(enabled = !showExit) { showExit = true }
            Box(Modifier.fillMaxSize()) {
                // Rezim rozhrania: klasicky launcher (default) alebo moderny (UiModePref);
                // cita sa pri kazdom navrate na home, takze prepnutie v nastaveniach
                // sa prejavi hned bez restartu.
                if (UiModePref.get(ctx) == UiModePref.MODERN) {
                    ModernTvHomeScreen(
                        chState = chState,
                        epgMap = epgMap,
                        onPlayChannel = { uuid, title ->
                            lastTile = "channels"
                            // napln playlist ako klasicky tok (play="tv"), inak by
                            // v prehravaci neslo prepinanie kanalov
                            (chState as? ChannelsState.Loaded)?.let { st ->
                                val sid2 = sk.tvhclient.shared.Tvh.store.active()?.id
                                val hidden = HiddenChannels.all(ctx, sid2)
                                LivePlaylist.channels = st.allRows.filter { it.channel.uuid !in hidden }.map { r ->
                                    LivePlaylist.LiveChannel(
                                        uuid = r.channel.uuid, name = r.channel.name,
                                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                                        nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                                    )
                                }
                            }
                            LivePlaylist.setIndexForUuid(uuid)
                            playUuid(uuid, title)
                        },
                        onChannels = { lastTile = "channels"; if (play.isEmpty()) play = "tv" },
                        onRadio = { lastTile = "radio"; if (play.isEmpty()) play = "radio" },
                        onTvProgram = { lastTile = "epg"; section = "epg" },
                        onArchive = { lastTile = "archive"; section = "archive" },
                        onSettings = { lastTile = "settings"; section = "settings" },
                    )
                } else {
                TvHomeScreen(   // pocas pending (play) zostava viditelny launcher, kym naskoci prehravac
                    focusKey = lastTile,
                    onChannels = { lastTile = "channels"; if (play.isEmpty()) play = "tv" },
                    onRadio = { lastTile = "radio"; if (play.isEmpty()) play = "radio" },
                    onTvProgram = { lastTile = "epg"; section = "epg" },
                    onArchive = { lastTile = "archive"; section = "archive" },
                    onSettings = { lastTile = "settings"; section = "settings" },
                )
                }
                if (showExit) {
                    androidx.activity.compose.BackHandler { showExit = false }
                    TvExitDialog(
                        onConfirm = { (ctx as? android.app.Activity)?.finish() },
                        onCancel = { showExit = false }
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

/** Potvrdenie ukoncenia aplikacie na uvodnom launcheri (TV/box, D-pad).
 *  Vyber riadime sami (sipky vlavo/vpravo + OK), lebo Compose focus na lacnych
 *  boxoch nie je spolahlivy (prvy stlac sa "prehltol" na nadviazanie fokusu). */
@Composable
private fun TvExitDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    var sel by remember { mutableStateOf(0) }   // 0 = Zrusit (predvolba), 1 = Ukoncit
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Box(
        Modifier.fillMaxSize().background(Color(0xCC0B1220)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.fillMaxWidth(0.6f)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B2433))
                .padding(horizontal = 28.dp, vertical = 28.dp)
                .focusRequester(fr)
                .focusable()
                .onPreviewKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    val activate = code == android.view.KeyEvent.KEYCODE_DPAD_CENTER ||
                        code == android.view.KeyEvent.KEYCODE_ENTER ||
                        code == android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
                    when (e.type) {
                        KeyEventType.KeyDown -> when (code) {
                            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> { sel = 0; true }
                            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> { sel = 1; true }
                            // M268: aktivaciu (OK/Enter) spravime az na KeyUp a spotrebujeme aj ten.
                            // Inak sa po zavreti dialogu (Zrusit) KeyUp prenesie na domovsku dlazdicu,
                            // ktorej clickable sa spusti a omylom otvori prehravac. KeyDown len spotrebuj.
                            else -> activate
                        }
                        KeyEventType.KeyUp ->
                            if (activate) { if (sel == 1) onConfirm() else onCancel(); true }
                            else false
                        else -> false   // BACK necha zavriet cez BackHandler
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                stringResource(R.string.exit_title), color = Color.White,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.exit_msg), color = Color(0xFFB9C2D0),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (sel == 0) Color(0x553B82F6) else Color.Transparent)
                        .border(1.dp, if (sel == 0) Color(0xFF3B82F6) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .clickable { onCancel() }
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.exit_no),
                        color = if (sel == 0) Color.White else Color(0xFFB9C2D0),
                        fontWeight = FontWeight.SemiBold)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (sel == 1) Color(0x55FF6B6B) else Color.Transparent)
                        .border(1.dp, if (sel == 1) Color(0xFFFF6B6B) else Color(0x33FFFFFF), RoundedCornerShape(12.dp))
                        .clickable { onConfirm() }
                        .padding(horizontal = 22.dp, vertical = 12.dp)
                ) {
                    Text(stringResource(R.string.exit_yes), color = Color(0xFFFF6B6B),
                        fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
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
    AutoSizeText(
        text,
        maxLines = 1,
        style = androidx.compose.material3.MaterialTheme.typography.labelMedium
    )
}

@Composable
fun AppMain(initialTab: Int = 0, onExitToHome: (() -> Unit)? = null) {
    var tab by remember { mutableStateOf(initialTab) }
    // Reset signaly: klik na tab (aj uz vybrany) vrati danu obrazovku na zaciatok
    var resetCh by remember { mutableStateOf(0) }
    var resetDvr by remember { mutableStateOf(0) }
    var resetRadio by remember { mutableStateOf(0) }
    var resetSet by remember { mutableStateOf(0) }
    val navFocus = remember { FocusRequester() }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var showExit by remember { mutableStateOf(false) }
    // Moderny rezim na telefone: extra tab "Domov" na zaciatku (klasik bez zmeny).
    // Indexy tabov sa posunu o off; klasik ma off=0, cize povodne spravanie.
    val homeCtx = androidx.compose.ui.platform.LocalContext.current
    val modernPhone = UiModePref.stateOf(homeCtx).value == UiModePref.MODERN
    val off = if (modernPhone) 1 else 0
    val chIdx = off; val radioIdx = 1 + off; val dvrIdx = 2 + off; val setIdx = 3 + off
    // odchod z nastaveni s neulozenymi/vykonanymi zmenami -> potvrdenie
    var leaveConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guardLeave(action: () -> Unit) {
        if (tab == setIdx && TabController.settingsDirty.value) leaveConfirm = action else action()
    }

    // Farebne tlacidla na dialkovom (cez TabController) prepnu tab
    val reqTab by TabController.requested
    LaunchedEffect(reqTab) {
        if (reqTab in 0..3) {
            when (reqTab) {
                0 -> { resetCh++; tab = chIdx }
                1 -> { resetRadio++; tab = radioIdx }
                2 -> { resetDvr++; tab = dvrIdx }
                3 -> { resetSet++; tab = setIdx }
                else -> tab = reqTab
            }
            TabController.requested.value = -1
        }
    }

    // EPG kláves -> prepni na Kanaly (bez resetu, nech zostane mriezka otvorena)
    val epgSig by TabController.epgGrid
    LaunchedEffect(epgSig) { if (epgSig > 0) tab = chIdx }

    // Spat: z ineho tabu spat na Kanaly; na Kanaloch -> potvrdenie ukoncenia.
    // (Vnutorne obrazovky maju vlastny BackHandler, ten ma prednost.)
    androidx.activity.compose.BackHandler(enabled = !showExit) {
        if (tab != 0) { resetCh++; tab = 0 }
        else if (onExitToHome != null) onExitToHome()   // TV: spat na launcher
        else showExit = true
    }

    val red = Color(0xFFE53935)
    val green = Color(0xFF43A047)
    val yellow = Color(0xFFFDD835)
    val blue = Color(0xFF1E88E5)

    Scaffold(
        bottomBar = {
            NavigationBar {
                if (modernPhone) {
                    NavigationBarItem(
                        selected = tab == 0,
                        onClick = { guardLeave { tab = 0 } },
                        icon = { androidx.compose.material3.Icon(
                            Icons.Default.Home, contentDescription = null) },
                        label = { TabLabel(Color(0xFF1D9E75), stringResource(R.string.mh_home)) }
                    )
                }
                NavigationBarItem(
                    selected = tab == chIdx,
                    onClick = { guardLeave { resetCh++; tab = chIdx } },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.LiveTv, contentDescription = null) },
                    label = { TabLabel(red, stringResource(R.string.tab_channels)) },
                    modifier = Modifier.focusRequester(navFocus)
                )
                NavigationBarItem(
                    selected = tab == radioIdx,
                    onClick = { guardLeave { resetRadio++; tab = radioIdx } },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.Radio, contentDescription = null) },
                    label = { TabLabel(green, stringResource(R.string.tab_radio)) }
                )
                NavigationBarItem(
                    selected = tab == dvrIdx,
                    onClick = { guardLeave { resetDvr++; tab = dvrIdx } },
                    icon = { androidx.compose.material3.Icon(
                        Icons.AutoMirrored.Filled.Dvr, contentDescription = null) },
                    label = { TabLabel(yellow, stringResource(R.string.tab_dvr)) }
                )
                NavigationBarItem(
                    selected = tab == setIdx,
                    onClick = {
                        if (tab == setIdx) guardLeave { resetSet++ }   // re-tap: spat na koren nastaveni
                        else tab = setIdx
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
                0 -> if (modernPhone) ModernPhoneHomeScreen(
                    onOpenChannels = { resetCh++; tab = chIdx },
                    onOpenRadio = { resetRadio++; tab = radioIdx },
                    onOpenEpg = { TabController.openEpgGrid() },
                    onOpenArchive = { resetDvr++; tab = dvrIdx },
                    onOpenSettings = { tab = setIdx },
                ) else ChannelsScreen(
                    resetSignal = resetCh,
                    onGoToNav = { runCatching { navFocus.requestFocus() } }
                )
                chIdx -> ChannelsScreen(
                    resetSignal = resetCh,
                    onGoToNav = { runCatching { navFocus.requestFocus() } }
                )
                radioIdx -> RadioScreen(
                    resetSignal = resetRadio,
                    onGoToNav = { runCatching { navFocus.requestFocus() } }
                )
                dvrIdx -> DvrScreen(resetSignal = resetDvr)
                else -> {
                    val ctx = androidx.compose.ui.platform.LocalContext.current
                    var unlocked by remember { mutableStateOf(!ParentalLock.settingsNeedsPin(ctx)) }
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
    ServerList(vm = vm, resetSignal = resetSignal)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerList(vm: ServersViewModel, resetSignal: Int = 0) {
    val servers by vm.servers.collectAsState()
    val activeId by vm.activeId.collectAsState()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var section by remember { mutableStateOf<String?>(null) }
    var lastSection by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<TvhServer?>(null) }
    var showForm by remember { mutableStateOf(false) }
    var lastEditedId by remember { mutableStateOf<String?>(null) }
    var restoreFocusSignal by remember { mutableStateOf(0) }
    val editRestoreFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val addRestoreFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    val catFocus = remember {
        mapOf(
            "general" to androidx.compose.ui.focus.FocusRequester(),
            "playback" to androidx.compose.ui.focus.FocusRequester(),
            "plock" to androidx.compose.ui.focus.FocusRequester(),
            "servers" to androidx.compose.ui.focus.FocusRequester(),
            "remote" to androidx.compose.ui.focus.FocusRequester(),
            "info" to androidx.compose.ui.focus.FocusRequester()
        )
    }
    val sectionFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    var legalDoc by remember { mutableStateOf<LegalDoc?>(null) }

    LaunchedEffect(resetSignal) {
        if (resetSignal > 0) { legalDoc = null; section = null; lastSection = null; showForm = false; editing = null; TabController.settingsDirty.value = false }
    }
    // pri kazdej zmene sekcie zacni s "ciste" (zmeny oznaci az uzivatelska akcia)
    LaunchedEffect(section) { TabController.settingsDirty.value = false }
    // po navrate do zoznamu vrat fokus na kategoriu, z ktorej sa odislo;
    // pri vstupe do sekcie daj fokus na prvy ovladaci prvok
    LaunchedEffect(section) {
        if (section == null) {
            val target = (lastSection?.let { catFocus[it] }) ?: catFocus["general"]
            runCatching { target?.requestFocus() }
        } else runCatching { sectionFocus.requestFocus() }
    }

    // Spat: formular -> zoznam serverov (sekcia ostava); legal -> sekcia; sekcia -> koren.
    BackHandler(enabled = showForm || legalDoc != null || section != null) {
        when {
            showForm -> { showForm = false; editing = null; vm.resetTest(); restoreFocusSignal++ }
            legalDoc != null -> legalDoc = null
            else -> { section = null; TabController.settingsDirty.value = false }
        }
    }

    // Po zatvoreni formulara vrat fokus tam, odkial sa vchadzalo (upravovany server / Pridat)
    LaunchedEffect(restoreFocusSignal) {
        if (restoreFocusSignal > 0) {
            kotlinx.coroutines.delay(120)
            val target = if (lastEditedId != null) editRestoreFocus else addRestoreFocus
            runCatching { target.requestFocus() }
        }
    }

    if (showForm) {
        ServerForm(
            vm = vm,
            existing = editing,
            onClose = { showForm = false; editing = null; vm.resetTest(); restoreFocusSignal++ }
        )
        return
    }

    val wide = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp >= 600
    val effective = section ?: "general"

    // spolocny obsah sekcie (pouzity v sidebar aj drill-down rezime)
    val renderContent: @Composable (String) -> Unit = { sec ->
        when (sec) {
            "general" -> GeneralSettings(ctx)
            "playback" -> PlaybackSettings(ctx)
            "plock" -> ParentalSettings(ctx)
            "servers" -> ServersSettings(vm, servers, activeId,
                onAdd = { editing = null; lastEditedId = null; showForm = true },
                onEdit = { editing = it; lastEditedId = it.id; showForm = true },
                restoreEditId = lastEditedId,
                restoreEditFocus = editRestoreFocus,
                addFocus = addRestoreFocus)
            "remote" -> RemoteSettings(ctx, servers, activeId)
            "info" -> InfoSettings(ctx, servers, activeId) { legalDoc = it }
            else -> {}
        }
    }

    val title = if (legalDoc != null) legalDoc!!.title
        else if (wide) stringResource(R.string.tab_settings)
        else when (section) {
            "general" -> stringResource(R.string.set_cat_general)
            "playback" -> stringResource(R.string.set_cat_playback)
            "plock" -> stringResource(R.string.plock_title)
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
                    // sipka spat: pri pravnom dokumente vzdy; na uzkej obrazovke aj v sekcii
                    if (legalDoc != null || (!wide && section != null)) {
                        androidx.compose.material3.IconButton(onClick = {
                            if (legalDoc != null) legalDoc = null
                            else { section = null; TabController.settingsDirty.value = false }
                        }) {
                            Text("\u2039", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                }
            )
        }
    ) { padding ->
        val legal = legalDoc
        if (legal != null) {
            LegalScreen(legal, Modifier.padding(padding)) { legalDoc = null }
        } else if (wide) {
            // TV / sirsia obrazovka: bocny panel s kategoriami (ikony) + obsah vpravo
            Row(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                Column(
                    Modifier
                        .width(300.dp)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .focusGroup()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    SettingsNavItem(Icons.Filled.Tune, stringResource(R.string.set_cat_general), effective == "general", catFocus["general"]) { lastSection = "general"; section = "general" }
                    SettingsNavItem(Icons.Filled.PlayArrow, stringResource(R.string.set_cat_playback), effective == "playback", catFocus["playback"]) { lastSection = "playback"; section = "playback" }
                    SettingsNavItem(Icons.Filled.Lock, stringResource(R.string.plock_title), effective == "plock", catFocus["plock"]) { lastSection = "plock"; section = "plock" }
                    SettingsNavItem(Icons.Filled.Dns, stringResource(R.string.set_cat_servers), effective == "servers", catFocus["servers"]) { lastSection = "servers"; section = "servers" }
                    SettingsNavItem(Icons.Filled.SettingsRemote, stringResource(R.string.set_cat_remote), effective == "remote", catFocus["remote"]) { lastSection = "remote"; section = "remote" }
                    SettingsNavItem(Icons.Filled.Info, stringResource(R.string.set_cat_info), effective == "info", catFocus["info"]) { lastSection = "info"; section = "info" }
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outlineVariant)
                )
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .focusRequester(sectionFocus)
                        .focusGroup()
                        .padding(horizontal = 24.dp, vertical = 16.dp)
                ) {
                    renderContent(effective)
                }
            }
        } else {
            // Telefon: povodny drill-down (zoznam kategorii -> detail)
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
                    .focusRequester(sectionFocus)
                    .focusGroup()
            ) {
                if (section == null) {
                    SettingsCategory(stringResource(R.string.set_cat_general), catFocus["general"]) { lastSection = "general"; section = "general" }
                    SettingsCategory(stringResource(R.string.set_cat_playback), catFocus["playback"]) { lastSection = "playback"; section = "playback" }
                    SettingsCategory(stringResource(R.string.plock_title), catFocus["plock"]) { lastSection = "plock"; section = "plock" }
                    SettingsCategory(stringResource(R.string.set_cat_servers), catFocus["servers"]) { lastSection = "servers"; section = "servers" }
                    SettingsCategory(stringResource(R.string.set_cat_remote), catFocus["remote"]) { lastSection = "remote"; section = "remote" }
                    SettingsCategory(stringResource(R.string.set_cat_info), catFocus["info"]) { lastSection = "info"; section = "info" }
                } else {
                    renderContent(section!!)
                }
            }
        }
    }
}



// Polozka bocneho panela nastaveni (ikona + nazov) pre TV/sirsie obrazovky.
@Composable
private fun SettingsNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    focusRequester: androidx.compose.ui.focus.FocusRequester?,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else androidx.compose.ui.graphics.Color.Transparent
            )
            .dpadFocusable()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.material3.Icon(
            icon,
            contentDescription = null,
            tint = if (selected) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.titleSmall,
            color = if (selected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}
