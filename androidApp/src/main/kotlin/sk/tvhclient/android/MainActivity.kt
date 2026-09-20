package sk.tvhclient.android

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.focusGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.filled.Palette
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
import androidx.compose.runtime.DisposableEffect
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

/** Bridge between the coloured remote-control buttons (dispatchKeyEvent) and the Compose tabs. */
object TabController {
    val requested = mutableStateOf(-1)
    fun request(tab: Int) { requested.value = tab }
    // EPG (TV guide) key of the remote -> open the grid in Channels
    val epgGrid = mutableStateOf(0)
    var epgFromPlayer = false
    var epgReturnUuid: String? = null
    // M591: the grid should open right at the radio stations (from the radio player)
    var epgRadio = false
    // Open the grid also on a fresh mount of Channels (e.g. from the modern Home
    // screen) — without this the baseline would swallow the signal and only the list would open.
    var epgColdOpen = false
    // M397: one-shot flag "open the grid now" — the hosts consume it already
    // during composition, so the intro screen has no time to flash
    var epgPending = false
    fun openEpgGrid(fromPlayer: Boolean = false, returnUuid: String? = null, radio: Boolean = false) {
        epgFromPlayer = fromPlayer
        epgReturnUuid = returnUuid
        epgRadio = radio   // M591
        epgPending = true
        epgGrid.value = epgGrid.value + 1
    }
    // INFO key -> detail of the selected programme (in the grid)
    val infoKey = mutableStateOf(0)
    fun pressInfo() { infoKey.value = infoKey.value + 1 }
    // whether changes were made in the current settings subsection (for the confirmation when leaving)
    val settingsDirty = mutableStateOf(false)
    // M389-fix: true = changes with an explicit Save (server form) -> "without saving" dialog
    val settingsDirtyUnsaved = mutableStateOf(false)
    // incrementing it forces a reload of channels/radios/archive/EPG (after saving/changing a server)
    val dataReload = mutableStateOf(0)
}

class MainActivity : ComponentActivity() {

    /** M429 (TV only): when the user leaves the app and the player hangs in a PiP
     *  thumbnail, close it along with the pinned window. PiP on TV only lives inside
     *  the app (a thumbnail over the TV guide); it has no business over the launcher/YouTube.
     *  It does not concern phones — there PiP over other apps is desirable. */
    override fun onStop() {
        super.onStop()
        val isTv = isTvUiMode(this)   // M679
        if (isTv && !isChangingConfigurations) {
            PlayerActivity.closeIfInPip()
        }
    }

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    /**
     * M494: after the app starts, continue where the user left off (TV only).
     *
     * Only on a COLD start — `savedInstanceState != null` means the activity is
     * merely being recreated (rotation, return from the background) and opening the player
     * again would throw the user out of the list. The return from the player
     * (open_epg) is skipped likewise, otherwise it would immediately open again and
     * could not be left.
     */
    private fun maybeResumeLastPlayback(savedInstanceState: Bundle?) {
        if (savedInstanceState != null) return
        if (intent?.getBooleanExtra("open_epg", false) == true) return
        if (!isTvUiMode(this)) return   // M679
        if (!ResumeLastPref.get(this)) return
        // M496: only prepare the request — it is carried out by the UI, which can wait for the channels
        LastPlayback.prepareRestore(this, sk.tvhclient.shared.Tvh.store.active()?.id)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Draw under the system bars (edge-to-edge), so that the app background fills the whole screen
        // including the navigation bar area / around the keyboard (otherwise a black strip appeared there).
        // The bars are transparent -> the window background (surface) shows through them, so they look the colour of the surface.
        //
        // M538: originally enableEdgeToEdge() from androidx.activity. Its internal class
        // EdgeToEdgeApi28 sets LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES, which the Play
        // Console reports as a deprecated API (SDK 35) — it was never in our code, but
        // the library is packed into the APK whole. We achieve the same result without it: transparent
        // bars, the contrast scrim off and the cutout mode are theme attributes (Theme.Headent,
        // values / values-v28 / values-v29 / values-v35), only turning off fitSystemWindows
        // stays here. R8 then strips the unused EdgeToEdge class out of the APK.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // M573: headentclient://channel/<uuid> — only on a cold start; on a recreation of
        // the activity (language change, density…) the player would otherwise open again
        if (savedInstanceState == null) DeepLink.handle(intent)
        if (intent?.getBooleanExtra("open_epg", false) == true) {
            TabController.openEpgGrid(
                fromPlayer = true, returnUuid = intent.getStringExtra("epg_return_uuid"),
                radio = intent.getBooleanExtra("epg_radio", false)   // M591
            )
        }
        maybeResumeLastPlayback(savedInstanceState)   // M494
        setContent {
            val themeMode = ThemePref.stateOf(this).value
            val dark = when (themeMode) {
                ThemePref.DARK -> true
                ThemePref.LIGHT -> false
                else -> isSystemInDarkTheme()
            }
            // The modern mode has its own teal palette in both the dark (navy) and light variant;
            // it respects the theme choice (light/dark/auto) just like the classic one
            val modernUi = UiModePref.stateOf(this).value == UiModePref.MODERN
            MaterialTheme(colorScheme = when {
                modernUi && dark -> modernColorScheme()
                modernUi -> modernLightColorScheme()
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }) {
                val view = LocalView.current
                val barColor = MaterialTheme.colorScheme.surface
                if (!view.isInEditMode) {
                    SideEffect {
                        val window = (view.context as android.app.Activity).window
                        // The window background set to the surface colour, so that an uncovered area (e.g. when the
                        // keyboard pops up and the window shrinks) does not show a black strip. Transparent
                        // system bars then show through in this colour (a replacement for the deprecated
                        // window.statusBarColor / navigationBarColor, which SDK 35 ignores).
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
        DeepLink.handle(intent)   // M573
        if (intent.getBooleanExtra("open_epg", false)) {
            TabController.openEpgGrid(
                fromPlayer = true, returnUuid = intent.getStringExtra("epg_return_uuid"),
                radio = intent.getBooleanExtra("epg_radio", false)   // M591
            )
        }
    }

    // Coloured buttons on the remote -> tab switch
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.action == android.view.KeyEvent.ACTION_DOWN) {
            val t = when (event.keyCode) {
                android.view.KeyEvent.KEYCODE_PROG_RED -> 0     // Channels
                android.view.KeyEvent.KEYCODE_PROG_GREEN -> 1   // Radio
                android.view.KeyEvent.KEYCODE_PROG_YELLOW -> 2  // Archive
                android.view.KeyEvent.KEYCODE_PROG_BLUE -> 3    // Settings
                else -> -1
            }
            if (t >= 0) { TabController.request(t); return true }
            when (event.keyCode) {
                // EPG / TV guide key (icon to the left of 0)
                android.view.KeyEvent.KEYCODE_GUIDE,
                android.view.KeyEvent.KEYCODE_CAPTIONS,
                android.view.KeyEvent.KEYCODE_TV_DATA_SERVICE,
                android.view.KeyEvent.KEYCODE_TV_CONTENTS_MENU,
                android.view.KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU -> {
                    TabController.openEpgGrid(); return true
                }
                // INFO key (icon to the right of 0)
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
    // No server -> welcome screen
    if (servers.isEmpty()) { WelcomeScreen(serversVm); return }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val isTv = remember { isTvUiMode(ctx) }   // M679
    if (isTv) TvHomeHost() else AppMain()
}

/** TV/box: intro launcher + separate sections (without the bottom bar). Back = launcher.
 *  Channels/Radios go straight into the player (the list is preloaded and populates LivePlaylist,
 *  so that the switching list in the player works). */
@Composable
private fun TvHomeHost() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val chVm: ChannelsViewModel = viewModel()
    val raVm: RadioViewModel = viewModel()
    val chState by chVm.state.collectAsState()
    val epgMap by chVm.epgMap.collectAsState()
    val raState by raVm.state.collectAsState()
    LaunchedEffect(Unit) { chVm.loadIfNeeded(); raVm.load() }   // preload both channels and radios
    // M557: a change of the server configuration (e.g. HTTP <-> HTSP) increments dataReload — the channels
    // must be loaded again NOW, not only when the list is opened. The home screen (Watch,
    // the favourites row) otherwise held channels with stale identifiers (HTSP channelId vs
    // HTTP uuid) and the player could not start a stream with them.
    val reloadTok = TabController.dataReload.value
    LaunchedEffect(reloadTok) { if (reloadTok > 0) { chVm.loadIfNeeded(); raVm.load() } }

    // section: "", "epg", "archive", "settings"; play: "", "tv", "radio"
    // M601: when the player asks for the TV guide (even from radio), we start right in the grid.
    // Until now the signal was handled only in the body of the composition, so when the host started
    // (e.g. after playing radio, when the screen was being composed anew) the intro flashed up.
    var section by remember { mutableStateOf(if (TabController.epgPending) "epg" else "") }
    // The player asks for the TV guide (open_epg intent) -> open the grid in the TV launcher too (M323)
    val epgSigTv by TabController.epgGrid
    // M397: we consume the signal ALREADY DURING composition — LaunchedEffect only ran after
    // the first frame, so when opening the TV guide from the player the intro flashed up.
    // Reading epgSigTv ensures a recomposition on a warm onNewIntent too.
    if (epgSigTv > 0 && TabController.epgPending) {
        TabController.epgPending = false
        if (section != "epg") section = "epg"
    }
    var lastTile by remember { mutableStateOf("channels") }
    var play by remember { mutableStateOf("") }
    // M605: the option "tiles open the list" — the player opens with the list open
    // over the last channel playing (the TV channels / Radios tiles, the restore after startup)
    var tileListFirst by remember { mutableStateOf(false) }
    // M613: coloured buttons on the remote on TV as well — red Channels, green Radio,
    // yellow Archive, blue Settings. Until now they were handled only by the phone host
    // (AppMain); on TV the signal from MainActivity.dispatchKeyEvent was never consumed,
    // so the coloured keys on a box did nothing. Applies in both the modern and the classic mode.
    val reqTabTv by TabController.requested
    LaunchedEffect(reqTabTv) {
        if (reqTabTv !in 0..3) return@LaunchedEffect
        val t = reqTabTv
        TabController.requested.value = -1
        when (t) {
            0 -> {   // Channels: the player with the last channel (the same as the tile)
                lastTile = "channels"; section = ""
                tileListFirst = TileListPref.get(ctx); chVm.loadIfNeeded(); play = "tv"
            }
            1 -> {   // Radio
                lastTile = "radio"; section = ""
                tileListFirst = TileListPref.get(ctx); raVm.load(); chVm.loadIfNeeded(); play = "radio"
            }
            2 -> { lastTile = "archive"; play = ""; section = "archive" }
            3 -> { lastTile = "settings"; play = ""; section = "settings" }
        }
    }
    // M599: a pending start (restoring the last channel after startup, autostart) is CANCELLED
    // when the user goes elsewhere in the meantime — to the archive, the TV guide, the settings. Otherwise
    // once the channels were fetched the player opened over a half-built screen: the app stuttered,
    // and on weaker boxes it even crashed.
    LaunchedEffect(section) {
        if (section.isNotEmpty() && play.isNotEmpty()) {
            play = ""
            LastPlayback.pendingUuid = null
            LastPlayback.pendingKind = null
        }
    }
    // M531: a safeguard against the intro screen getting stuck.
    //
    // While `play` waits for the load, the tiles are dead. When the load got stuck in the
    // "loading" state (e.g. the server does not answer and the state never changes again), the
    // screen stayed blocked until the app was restarted. After 20 s we cancel the wait so
    // that the app can be controlled again.
    LaunchedEffect(play) {
        if (play.isNotEmpty()) {
            kotlinx.coroutines.delay(20_000)
            play = ""
        }
    }
    // M496: restoring the last playback after the app starts (TV).
    // A live channel MUST NOT be started directly — the player gets the channel list through
    // LivePlaylist, which on a cold start is not yet populated, so it would play
    // a single channel and CH+/- would not work. That is why we start it the same way as
    // autostart (play = "tv"): it waits for the channels to load, LivePlaylist is populated
    // and only then does the player open.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val kind = LastPlayback.pendingKind
        if (kind != null && play.isEmpty() && section.isEmpty()) {   // M599
            LastPlayback.pendingKind = null
            // M605-fix2: restoring the last channel after the app starts respects the option
            // "tiles open the list" — otherwise the list did not open after switching the box on
            tileListFirst = TileListPref.get(ctx)
            if (kind == "radio") { raVm.load(); chVm.loadIfNeeded(); play = "radio" }
            else { chVm.loadIfNeeded(); play = "tv" }
        }
    }
    // M573: a deep link to a channel (a favourite's shortcut) goes the same way — it waits
    // for the channels, populates LivePlaylist and plays exactly that channel
    val deepUuid = DeepLink.pending.value
    androidx.compose.runtime.LaunchedEffect(deepUuid) {
        if (deepUuid != null) {
            DeepLink.pending.value = null
            LastPlayback.pendingUuid = deepUuid
            section = ""
            chVm.loadIfNeeded()
            play = "tv"
        }
    }
    // M573: favourites shortcuts from the current channel list
    LaunchedEffect(chState, epgMap) {
        (chState as? ChannelsState.Loaded)?.let {
            FavoriteShortcuts.rowsLoaded(ctx, sk.tvhclient.shared.Tvh.store.active()?.id, it.allRows, epgMap)   // M573 / M580
        }
    }
    var showExit by remember { mutableStateOf(false) }

    fun playUuid(uuid: String, title: String, kind: String = "tv", listFirst: Boolean = false) {
        runCatching {
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).apply {
                putExtra(PlayerActivity.EXTRA_UUID, uuid)
                putExtra(PlayerActivity.EXTRA_TITLE, title)
                putExtra(PlayerActivity.EXTRA_KIND, kind)
                if (listFirst) putExtra(PlayerActivity.EXTRA_LIST_FIRST, true)
            })
        }
    }

    // Channels: once loaded it populates LivePlaylist and plays the last/first channel
    LaunchedEffect(play, chState) {
        if (play == "tv") {
            val st = chState
            if (st is ChannelsState.Loaded) {
                val sid = sk.tvhclient.shared.Tvh.store.active()?.id
                val hidden = HiddenChannels.all(ctx, sid)
                val full = st.allRows.filter { it.channel.uuid !in hidden }.map { r ->
                    LivePlaylist.LiveChannel(
                        uuid = r.channel.uuid, name = r.channel.name,
                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                        nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                    )
                }
                val grps = st.categories.mapNotNull { cat ->
                    val t = cat.tag ?: return@mapNotNull null
                    val u = cat.rows.map { it.channel.uuid }.filter { it !in hidden }.toSet()
                    if (u.isEmpty()) null else LivePlaylist.Group(t.uuid, t.name, u)
                }
                // M541: hidden channels separately (a pseudo-group in the player for unhiding)
                val hiddenList = st.allRows.filter { it.channel.uuid in hidden }.map { r ->
                    LivePlaylist.LiveChannel(
                        uuid = r.channel.uuid, name = r.channel.name,
                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                        nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                    )
                }
                // M506: restore the last selected group (the same as in the Channels tab)
                LivePlaylist.setChannels(
                    full, grps, LastTag.toGroupKey(LastTag.get(ctx, sid, radio = false)),
                    favs = if (sid != null) Favorites.list(ctx, sid) else emptyList(), hidden = hiddenList
                )
                // M573: the channel from the deep link (a favourite's shortcut) — if it is not in the restored
                // group, switch to All; if the server does not have it (hidden / deleted), play
                // nothing instead of a random first channel
                val want = LastPlayback.pendingUuid
                if (want != null && LivePlaylist.channels.none { it.uuid == want }) {
                    if (full.any { it.uuid == want }) {
                        LivePlaylist.setChannels(
                            full, grps, null,
                            favs = if (sid != null) Favorites.list(ctx, sid) else emptyList(), hidden = hiddenList
                        )
                    } else {
                        LastPlayback.pendingUuid = null
                        play = ""
                        return@LaunchedEffect
                    }
                }
                // M496: if we are restoring the last broadcast, that channel takes precedence
                val target = (LastPlayback.pendingUuid ?: LastChannel.get(ctx, sid))
                    ?.takeIf { u -> LivePlaylist.channels.any { it.uuid == u } }
                    ?: LivePlaylist.channels.firstOrNull()?.uuid
                LastPlayback.pendingUuid = null
                play = ""
                val listFirst = tileListFirst   // M605
                tileListFirst = false
                if (target != null) {
                    LivePlaylist.setIndexForUuid(target)
                    playUuid(target, LivePlaylist.channels.firstOrNull { it.uuid == target }?.name ?: "", listFirst = listFirst)
                }
            } else if (st is ChannelsState.Error || st is ChannelsState.NoServer) {
                play = ""
            }
        }
    }
    // Radios: once loaded it populates LivePlaylist and plays the last/first station
    LaunchedEffect(play, raState) {
        if (play == "radio") {
            val st = raState
            if (st is RadioState.Loaded) {
                val sid = sk.tvhclient.shared.Tvh.store.active()?.id
                // M506: radio has groups by tags too — the player can thus
                // switch between them (long-press OK) just as with TV.
                val hiddenR = HiddenChannels.all(ctx, sid)
                // M586: "now playing" for radios from the shared now/next map (HTSP) —
                // the player thus has the programme right away, not only after fetching the EPG itself
                val nowSecR = System.currentTimeMillis() / 1000
                fun radioCh(r: sk.tvhclient.shared.api.ChannelRow): LivePlaylist.LiveChannel {
                    val ev = epgMap[r.channel.uuid]?.firstOrNull { nowSecR in it.start until it.stop }
                    return LivePlaylist.LiveChannel(
                        uuid = r.channel.uuid, name = r.channel.name,
                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                        nowTitle = ev?.title ?: r.nowTitle ?: "",
                        nowStart = ev?.start ?: r.nowStart, nowStop = ev?.stop ?: r.nowStop
                    )
                }
                // M602: numbering of radios 1…n by their order (excluding hidden ones), if the option is on
                val fullR = RadioNumberingPref.apply(ctx, st.rows.filter { it.channel.uuid !in hiddenR }).map { radioCh(it) }
                val grpsR = st.categories.mapNotNull { cat ->
                    val t = cat.tag ?: return@mapNotNull null
                    val u = cat.rows.map { it.channel.uuid }.filter { it !in hiddenR }.toSet()
                    if (u.isEmpty()) null else LivePlaylist.Group(t.uuid, t.name, u)
                }
                // M582: hidden radios separately (a pseudo-group in the player for unhiding) — parity with TV
                // M602-fix: hidden ones are numbered 1…n too (until now they kept the server numbers)
                val hiddenListR = RadioNumberingPref.apply(ctx, st.rows.filter { it.channel.uuid in hiddenR }).map { radioCh(it) }
                LivePlaylist.setChannels(
                    fullR, grpsR, LastTag.toGroupKey(LastTag.get(ctx, sid, radio = true)),
                    favs = if (sid != null) Favorites.list(ctx, sid) else emptyList(), hidden = hiddenListR
                )
                // M497: the station being restored takes precedence over the last one
                val target = (LastPlayback.pendingUuid ?: LastRadio.get(ctx, sid))
                    ?.takeIf { u -> LivePlaylist.channels.any { it.uuid == u } }
                    ?: LivePlaylist.channels.firstOrNull()?.uuid
                LastPlayback.pendingUuid = null
                play = ""
                val listFirstR = tileListFirst   // M605-fix: the Radios tile as well
                tileListFirst = false
                if (target != null) {
                    LivePlaylist.setIndexForUuid(target)
                    playUuid(target, LivePlaylist.channels.firstOrNull { it.uuid == target }?.name ?: "", "radio", listFirst = listFirstR)
                }
            } else if (st is RadioState.Error || st is RadioState.NoServer) {
                play = ""
            }
        }
    }

    when {
        section == "epg" -> {
            // M396: the TV guide opened FROM THE PLAYER -> BACK returns to the player
            // on the original channel (as in the Channels tab), not to the launcher intro.
            // M397-fix: we do NOT blank the grid on the return straight away (the intro would flash up
            // while the player starts) — it stays shown until the player covers
            // it, and is blanked only after the return from it (the ChannelsScreen pattern).
            var pendingEpgDismiss by remember { mutableStateOf(false) }
            val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && pendingEpgDismiss) {
                        pendingEpgDismiss = false
                        section = ""
                    }
                }
                lifecycleOwner.lifecycle.addObserver(obs)
                onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
            }
            val epgBack: () -> Unit = {
                if (TabController.epgFromPlayer) {
                    val uuid = TabController.epgReturnUuid
                    TabController.epgFromPlayer = false
                    TabController.epgReturnUuid = null
                    if (uuid != null) {
                        LivePlaylist.setIndexForUuid(uuid)
                        val title = LivePlaylist.channels.firstOrNull { it.uuid == uuid }?.name ?: ""
                        pendingEpgDismiss = true
                        // M604: the return from the guide to the RADIO player must go as
                        // radio — otherwise the station was played as a TV channel (without the radio
                        // interface and the station list)
                        playUuid(uuid, title, if (TabController.epgRadio) "radio" else "tv")
                    } else {
                        section = ""
                    }
                } else {
                    section = ""
                }
            }
            androidx.activity.compose.BackHandler { epgBack() }
            val st = chState
            if (st is ChannelsState.Loaded) {
                EpgGridScreen(
                    allRows = st.allRows, categories = st.categories, seed = epgMap,
                    onBack = { epgBack() },
                    startInRadio = TabController.epgRadio,   // M591
                    focusUuid = TabController.epgReturnUuid,   // M592
                    openToken = TabController.epgGrid.value,   // M593-fix

                    // M587: radio stations as a further group in the grid filter
                    radioRows = RadioNumberingPref.apply(ctx, (raState as? RadioState.Loaded)?.rows ?: emptyList()),   // M602
                    radioCategories = ((raState as? RadioState.Loaded)?.categories ?: emptyList())
                        .map { c -> c.copy(rows = RadioNumberingPref.apply(ctx, c.rows)) }
                )
            } else {
                CenterLoading()
            }
        }
        section == "archive" -> {
            TvArchiveScreen(onBack = { section = "" })
        }
        section == "settings" -> {
            androidx.activity.compose.BackHandler { section = "" }
            // M620 (issue #15): the parental lock for the settings applied only on a phone
            // (AppMain). On TV the Settings opened directly, so the option
            // "require PIN for -> Settings" did nothing here. The same gate as
            // on a phone: without the correct PIN ServersTab is not composed at all, cancelling
            // the dialog (BACK) returns to the home screen.
            var setUnlocked by remember { mutableStateOf(!ParentalLock.settingsNeedsPin(ctx)) }
            androidx.compose.material3.Surface(
                modifier = Modifier.fillMaxSize(),
                color = androidx.compose.material3.MaterialTheme.colorScheme.background
            ) {
                if (setUnlocked) ServersTab()
                else PinDialog(
                    title = stringResource(R.string.plock_unlock_settings),
                    onDismiss = { section = "" },
                    onComplete = { pin ->
                        if (ParentalLock.checkPin(ctx, pin)) {
                            ParentalLock.markUnlocked(ctx); setUnlocked = true; true
                        } else false
                    }
                )
            }
        }
        else -> {
            androidx.activity.compose.BackHandler(enabled = !showExit) { showExit = true }
            Box(Modifier.fillMaxSize()) {
                // Interface mode: the classic launcher (default) or the modern one (UiModePref);
                // it is read on every return to home, so switching it in the settings
                // takes effect immediately without a restart.
                if (UiModePref.get(ctx) == UiModePref.MODERN) {
                    ModernTvHomeScreen(
                        chState = chState,
                        epgMap = epgMap,
                        onPlayChannel = { uuid, title ->
                            lastTile = "channels"
                            // populate the playlist as in the classic flow (play="tv"), otherwise
                            // channel switching would not work in the player
                            (chState as? ChannelsState.Loaded)?.let { st ->
                                val sid2 = sk.tvhclient.shared.Tvh.store.active()?.id
                                val hidden = HiddenChannels.all(ctx, sid2)
                                val full = st.allRows.filter { it.channel.uuid !in hidden }.map { r ->
                                    LivePlaylist.LiveChannel(
                                        uuid = r.channel.uuid, name = r.channel.name,
                                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                                        nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                                    )
                                }
                                val grps = st.categories.mapNotNull { cat ->
                                    val t = cat.tag ?: return@mapNotNull null
                                    val u = cat.rows.map { it.channel.uuid }.filter { it !in hidden }.toSet()
                                    if (u.isEmpty()) null else LivePlaylist.Group(t.uuid, t.name, u)
                                }
                                // M506: restore the group, but only if the selected channel belongs
                                // to it — otherwise the user would click a channel and would not have
                                // it in the CH+/- list
                                val favs2 = if (sid2 != null) Favorites.list(ctx, sid2) else emptyList()
                                val hiddenList2 = st.allRows.filter { it.channel.uuid in hidden }.map { r ->
                                    LivePlaylist.LiveChannel(
                                        uuid = r.channel.uuid, name = r.channel.name,
                                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                                        nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                                    )
                                }
                                val saved = LastTag.toGroupKey(LastTag.get(ctx, sid2, radio = false))
                                    ?.takeIf { k ->
                                        (k == LivePlaylist.GROUP_FAV && uuid in favs2) ||   // M541
                                            grps.firstOrNull { it.key == k }?.uuids?.contains(uuid) == true
                                    }
                                LivePlaylist.setChannels(full, grps, saved, favs = favs2, hidden = hiddenList2)
                            }
                            LivePlaylist.setIndexForUuid(uuid)
                            playUuid(uuid, title)
                        },
                        onChannels = { lastTile = "channels"; tileListFirst = TileListPref.get(ctx); chVm.loadIfNeeded(); play = "tv" },   // M531, M605
                        onRadio = { lastTile = "radio"; tileListFirst = TileListPref.get(ctx); raVm.load(); chVm.loadIfNeeded(); play = "radio" },   // M531, M605-fix
                        onTvProgram = { lastTile = "epg"; section = "epg" },
                        onArchive = { lastTile = "archive"; section = "archive" },
                        onSettings = { lastTile = "settings"; section = "settings" },
                    )
                } else {
                TvHomeScreen(   // during a pending (play) the launcher stays visible until the player comes up
                    focusKey = lastTile,
                    onChannels = { lastTile = "channels"; tileListFirst = TileListPref.get(ctx); chVm.loadIfNeeded(); play = "tv" },   // M531, M605
                    onRadio = { lastTile = "radio"; tileListFirst = TileListPref.get(ctx); raVm.load(); chVm.loadIfNeeded(); play = "radio" },   // M531, M605-fix
                    onTvProgram = { lastTile = "epg"; section = "epg" },
                    onArchive = { lastTile = "archive"; section = "archive" },
                    onSettings = { lastTile = "settings"; section = "settings" },
                )
                }
                if (showExit) {
                    androidx.activity.compose.BackHandler { showExit = false }
                    TvExitDialog(
                        onConfirm = {
                            // Quitting the app also stops the mini radio (M344-fix5);
                            // the HOME key does not quit the app, the radio plays on there
                            RadioPlayerService.stop(ctx)
                            (ctx as? android.app.Activity)?.finish()
                        },
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

/** Confirmation of quitting the application on the intro launcher (TV/box, D-pad).
 *  We drive the selection ourselves (left/right arrows + OK), because Compose focus on cheap
 *  boxes is not reliable (the first press was "swallowed" to establish focus). */
@Composable
private fun TvExitDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    var sel by remember { mutableStateOf(0) }   // 0 = Cancel (default), 1 = Quit
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
                            // M268: we do the activation (OK/Enter) only on KeyUp and consume that one too.
                            // Otherwise, after the dialog is closed (Cancel), the KeyUp is passed to the home tile,
                            // whose clickable fires and opens the player by mistake. Just consume the KeyDown.
                            else -> activate
                        }
                        KeyEventType.KeyUp ->
                            if (activate) { if (sel == 1) onConfirm() else onCancel(); true }
                            else false
                        else -> false   // BACK is left to close it via BackHandler
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
                // redraw the app (loads the refreshed servers and language), without killing the process
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
    val homeCtx = androidx.compose.ui.platform.LocalContext.current
    val modernPhone = UiModePref.stateOf(homeCtx).value == UiModePref.MODERN
    // Fixed logical tab IDs (independent of the presence of the "Home" tab). Thanks to that
    // switching the mode (classic<->modern) neither shifts the indices nor causes a flash
    // of another tab. The "Home" tab exists only in the modern mode (otherwise it is not shown).
    val chIdx = 1; val radioIdx = 2; val dvrIdx = 3; val setIdx = 4
    // Default tab: modern -> Home(0), classic -> Channels(1).
    val homeTab = if (modernPhone) 0 else chIdx
    var tab by remember { mutableStateOf(if (initialTab != 0) initialTab else homeTab) }
    // Reset signals: a click on a tab (even an already selected one) returns that screen to the start
    var resetCh by remember { mutableStateOf(0) }
    var resetDvr by remember { mutableStateOf(0) }
    var resetRadio by remember { mutableStateOf(0) }
    var resetSet by remember { mutableStateOf(0) }
    val navFocus = remember { FocusRequester() }
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    var showExit by remember { mutableStateOf(false) }
    // leaving the settings with unsaved/made changes -> confirmation
    var leaveConfirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    fun guardLeave(action: () -> Unit) {
        if (tab == setIdx && TabController.settingsDirty.value) leaveConfirm = action else action()
    }

    // Coloured buttons on the remote (via TabController) switch the tab
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

    // EPG key -> switch to Channels (without a reset, so that the grid stays open)
    val epgSig by TabController.epgGrid
    LaunchedEffect(epgSig) { if (epgSig > 0) tab = chIdx }

    // M573: a deep link to a channel (a favourite's shortcut on a phone). The player needs
    // a populated LivePlaylist, which is why it waits for the channels to load (the same ViewModel
    // as the Channels tab) and only then starts.
    val deepUuid = DeepLink.pending.value
    val deepVm: ChannelsViewModel = viewModel()
    val deepState by deepVm.state.collectAsState()
    LaunchedEffect(deepUuid, deepState) {
        if (deepUuid == null) return@LaunchedEffect
        when (val st = deepState) {
            is ChannelsState.Loaded -> {
                DeepLink.pending.value = null
                val sid = sk.tvhclient.shared.Tvh.store.active()?.id
                val hidden = HiddenChannels.all(homeCtx, sid)
                val row = st.allRows.firstOrNull { it.channel.uuid == deepUuid && it.channel.uuid !in hidden }
                    ?: return@LaunchedEffect   // unknown or hidden channel -> nothing
                val full = st.allRows.filter { it.channel.uuid !in hidden }.map { r ->
                    LivePlaylist.LiveChannel(
                        uuid = r.channel.uuid, name = r.channel.name,
                        number = r.channel.number ?: 0, piconUrl = r.piconUrl,
                        nowTitle = r.nowTitle ?: "", nowStart = r.nowStart, nowStop = r.nowStop
                    )
                }
                val grps = st.categories.mapNotNull { cat ->
                    val t = cat.tag ?: return@mapNotNull null
                    val u = cat.rows.map { it.channel.uuid }.filter { it !in hidden }.toSet()
                    if (u.isEmpty()) null else LivePlaylist.Group(t.uuid, t.name, u)
                }
                LivePlaylist.setChannels(
                    full, grps, LastTag.toGroupKey(LastTag.get(homeCtx, sid, radio = false)),
                    favs = if (sid != null) Favorites.list(homeCtx, sid) else emptyList()
                )
                LivePlaylist.setIndexForUuid(deepUuid)
                LastChannel.set(homeCtx, sid, deepUuid)
                runCatching {
                    homeCtx.startActivity(Intent(homeCtx, PlayerActivity::class.java).apply {
                        putExtra(PlayerActivity.EXTRA_UUID, deepUuid)
                        putExtra(PlayerActivity.EXTRA_TITLE, row.channel.name)
                        putExtra(PlayerActivity.EXTRA_KIND, "tv")
                    })
                }
            }
            is ChannelsState.Error, is ChannelsState.NoServer -> DeepLink.pending.value = null
            else -> deepVm.loadIfNeeded()
        }
    }

    // Back: from another tab back to Channels; on Channels -> the quit confirmation.
    // (Inner screens have their own BackHandler, which takes precedence.)
    androidx.activity.compose.BackHandler(enabled = !showExit) {
        if (tab != homeTab) { resetCh++; tab = homeTab }
        else if (onExitToHome != null) onExitToHome()   // TV: back to the launcher
        else showExit = true
    }

    val red = Color(0xFFE53935)
    val green = Color(0xFF43A047)
    val yellow = Color(0xFFFDD835)
    val blue = Color(0xFF1E88E5)

    Scaffold(
        bottomBar = {
            androidx.compose.foundation.layout.Column {
            MiniRadioBar()
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
                        if (tab == setIdx) guardLeave { resetSet++ }   // re-tap: back to the root of the settings
                        else tab = setIdx
                    },
                    icon = { androidx.compose.material3.Icon(
                        Icons.Default.Dns, contentDescription = null) },
                    label = { TabLabel(blue, stringResource(R.string.tab_settings)) }
                )
            }
            }
        }
    ) { padding ->
        Box(Modifier.padding(padding)) {
            val tabContent: @Composable (Int) -> Unit = { t ->
                when (t) {
                    0 -> if (modernPhone) ModernPhoneHomeScreen(
                        onOpenChannels = { resetCh++; tab = chIdx },
                        onOpenEpg = { TabController.epgColdOpen = true; TabController.openEpgGrid() },
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
                                onDismiss = { tab = homeTab },
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
            // Always the same structure (AnimatedContent), so that switching the classic<->modern mode
            // does not unmount the tab content (otherwise e.g. a subpage in the Settings would be lost).
            // The transition fade only in the modern mode; classic = an instant change (no animation).
            val modernNow = isModernUi()
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    if (modernNow) {
                        androidx.compose.animation.fadeIn(
                            androidx.compose.animation.core.tween(180)
                        ) togetherWith androidx.compose.animation.fadeOut(
                            androidx.compose.animation.core.tween(120)
                        )
                    } else {
                        androidx.compose.animation.EnterTransition.None togetherWith
                            androidx.compose.animation.ExitTransition.None
                    }
                },
                label = "tabFade"
            ) { t ->
                // M482: during the transition both cards are on screen at once and without
                // their own background one was visible through the other (the texts
                // overlapped). An opaque background removes that.
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) { tabContent(t) }
            }
        }
    }

    leaveConfirm?.let { action ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { leaveConfirm = null },
            title = { Text(stringResource(
                if (TabController.settingsDirtyUnsaved.value) R.string.srv_leave_title else R.string.set_leave_title)) },
            text = { Text(stringResource(
                if (TabController.settingsDirtyUnsaved.value) R.string.srv_leave_msg else R.string.set_leave_msg)) },
            confirmButton = {
                TextButton(onClick = {
                    TabController.settingsDirty.value = false
                    TabController.settingsDirtyUnsaved.value = false
                    leaveConfirm = null
                    action()
                }) { Text(stringResource(
                    if (TabController.settingsDirtyUnsaved.value) R.string.srv_leave_yes else R.string.set_leave_yes)) }
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
                TextButton(onClick = {
                    showExit = false
                    activity?.let { RadioPlayerService.stop(it) }
                    activity?.finish()
                }) {
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
            "vzhlad" to androidx.compose.ui.focus.FocusRequester(),
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
    // on every section change start "clean" (changes are marked only by a user action)
    LaunchedEffect(section) { TabController.settingsDirty.value = false }
    // after the return to the list put the focus back on the category it was left from;
    // on entering a section give the focus to the first control
    LaunchedEffect(section) {
        if (section == null) {
            val target = (lastSection?.let { catFocus[it] }) ?: catFocus["general"]
            runCatching { target?.requestFocus() }
        } else runCatching { sectionFocus.requestFocus() }
    }

    // Back: form -> server list (the section stays); legal -> section; section -> root.
    BackHandler(enabled = showForm || legalDoc != null || section != null) {
        when {
            showForm -> { showForm = false; editing = null; vm.resetTest(); restoreFocusSignal++ }
            legalDoc != null -> legalDoc = null
            else -> { section = null; TabController.settingsDirty.value = false }
        }
    }

    // After the form is closed put the focus back where it was entered from (the server being edited / Add)
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
    // M393: the Remote control section only makes sense on TV (it enables the receiver on the box);
    // on a phone we hide it — a phone is the remote, not the controlled device.
    val ctxTvChk = androidx.compose.ui.platform.LocalContext.current
    val isTvSettings = remember { isTvUiMode(ctxTvChk) }   // M679
    val effective = section ?: "general"

    // shared section content (used in both the sidebar and the drill-down mode)
    val renderContent: @Composable (String) -> Unit = { sec ->
        when (sec) {
            "general" -> GeneralSettings(ctx)
            "vzhlad" -> AppearanceSettings(ctx)
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
            "vzhlad" -> stringResource(R.string.set_cat_appearance)
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
                    // back arrow: always with a legal document; on a narrow screen also within a section
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
            // TV / wider screen: side panel with categories (icons) + content on the right
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
                    SettingsNavItem(Icons.Filled.Tune, stringResource(R.string.set_cat_general), effective == "general", catFocus["general"],
                        subtitle = stringResource(R.string.set_sub_general),
                        chipBgL = 0xFFE0F2EF, chipFgL = 0xFF0F8A63, chipBgD = 0xFF0F2E22, chipFgD = 0xFF7FE3BF
                    ) { lastSection = "general"; section = "general" }
                    SettingsNavItem(Icons.Filled.PlayArrow, stringResource(R.string.set_cat_playback), effective == "playback", catFocus["playback"],
                        subtitle = stringResource(R.string.set_sub_playback),
                        chipBgL = 0xFFD8F0FB, chipFgL = 0xFF1877A8, chipBgD = 0xFF12283A, chipFgD = 0xFF7CC4E8
                    ) { lastSection = "playback"; section = "playback" }
                    SettingsNavItem(Icons.Filled.Palette, stringResource(R.string.set_cat_appearance), effective == "vzhlad", catFocus["vzhlad"],
                        subtitle = stringResource(R.string.set_sub_appearance),
                        chipBgL = 0xFFEDE7FE, chipFgL = 0xFF534AB7, chipBgD = 0xFF241F45, chipFgD = 0xFFAFA9EC
                    ) { lastSection = "vzhlad"; section = "vzhlad" }
                    SettingsNavItem(Icons.Filled.Lock, stringResource(R.string.plock_title), effective == "plock", catFocus["plock"],
                        subtitle = stringResource(R.string.set_sub_plock),
                        chipBgL = 0xFFFFE1E1, chipFgL = 0xFFD64545, chipBgD = 0xFF3A1D20, chipFgD = 0xFFEF8A88
                    ) { lastSection = "plock"; section = "plock" }
                    SettingsNavItem(Icons.Filled.Dns, stringResource(R.string.set_cat_servers), effective == "servers", catFocus["servers"],
                        subtitle = stringResource(R.string.set_sub_servers),
                        badge = servers.size.takeIf { it > 0 }?.toString(),
                        chipBgL = 0xFFE3E0FB, chipFgL = 0xFF6A5AD8, chipBgD = 0xFF241F45, chipFgD = 0xFFA99BF5
                    ) { lastSection = "servers"; section = "servers" }
                    if (isTvSettings) SettingsNavItem(Icons.Filled.SettingsRemote, stringResource(R.string.set_cat_remote), effective == "remote", catFocus["remote"],
                        subtitle = stringResource(R.string.set_sub_remote),
                        chipBgL = 0xFFFFECCC, chipFgL = 0xFFC07A17, chipBgD = 0xFF3A2B12, chipFgD = 0xFFE8B96A
                    ) { lastSection = "remote"; section = "remote" }
                    SettingsNavItem(Icons.Filled.Info, stringResource(R.string.set_cat_info), effective == "info", catFocus["info"],
                        subtitle = stringResource(R.string.set_sub_info),
                        badge = BuildConfig.VERSION_NAME,
                        chipBgL = 0xFFE0F2EF, chipFgL = 0xFF0F8A63, chipBgD = 0xFF0F2E22, chipFgD = 0xFF7FE3BF
                    ) { lastSection = "info"; section = "info" }
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
            // Phone: the original drill-down (list of categories -> detail)
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
                    SettingsCategory(stringResource(R.string.set_cat_general), catFocus["general"],
                        icon = Icons.Filled.Tune, subtitle = stringResource(R.string.set_sub_general),
                        chipBgL = 0xFFE0F2EF, chipFgL = 0xFF0F8A63, chipBgD = 0xFF0F2E22, chipFgD = 0xFF7FE3BF
                    ) { lastSection = "general"; section = "general" }
                    SettingsCategory(stringResource(R.string.set_cat_playback), catFocus["playback"],
                        icon = Icons.Filled.PlayArrow, subtitle = stringResource(R.string.set_sub_playback),
                        chipBgL = 0xFFD8F0FB, chipFgL = 0xFF1877A8, chipBgD = 0xFF12283A, chipFgD = 0xFF7CC4E8
                    ) { lastSection = "playback"; section = "playback" }
                    SettingsCategory(stringResource(R.string.set_cat_appearance), catFocus["vzhlad"],
                        icon = Icons.Filled.Palette, subtitle = stringResource(R.string.set_sub_appearance),
                        chipBgL = 0xFFEDE7FE, chipFgL = 0xFF534AB7, chipBgD = 0xFF241F45, chipFgD = 0xFFAFA9EC
                    ) { lastSection = "vzhlad"; section = "vzhlad" }
                    SettingsCategory(stringResource(R.string.plock_title), catFocus["plock"],
                        icon = Icons.Filled.Lock, subtitle = stringResource(R.string.set_sub_plock),
                        chipBgL = 0xFFFFE1E1, chipFgL = 0xFFD64545, chipBgD = 0xFF3A1D20, chipFgD = 0xFFEF8A88
                    ) { lastSection = "plock"; section = "plock" }
                    SettingsCategory(stringResource(R.string.set_cat_servers), catFocus["servers"],
                        icon = Icons.Filled.Dns, subtitle = stringResource(R.string.set_sub_servers),
                        badge = servers.size.takeIf { it > 0 }?.toString(),
                        chipBgL = 0xFFE3E0FB, chipFgL = 0xFF6A5AD8, chipBgD = 0xFF241F45, chipFgD = 0xFFA99BF5
                    ) { lastSection = "servers"; section = "servers" }
                    if (isTvSettings) SettingsCategory(stringResource(R.string.set_cat_remote), catFocus["remote"],
                        icon = Icons.Filled.SettingsRemote, subtitle = stringResource(R.string.set_sub_remote),
                        chipBgL = 0xFFFFECCC, chipFgL = 0xFFC07A17, chipBgD = 0xFF3A2B12, chipFgD = 0xFFE8B96A
                    ) { lastSection = "remote"; section = "remote" }
                    SettingsCategory(stringResource(R.string.set_cat_info), catFocus["info"],
                        icon = Icons.Filled.Info, subtitle = stringResource(R.string.set_sub_info),
                        badge = BuildConfig.VERSION_NAME,
                        chipBgL = 0xFFE0F2EF, chipFgL = 0xFF0F8A63, chipBgD = 0xFF0F2E22, chipFgD = 0xFF7FE3BF
                    ) { lastSection = "info"; section = "info" }
                } else {
                    renderContent(section!!)
                }
            }
        }
    }
}



// Settings side panel item (icon + name) for TV/wider screens.
@Composable
private fun SettingsNavItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    focusRequester: androidx.compose.ui.focus.FocusRequester?,
    subtitle: String? = null,
    badge: String? = null,
    chipBgL: Long = 0xFFE0F2EF, chipFgL: Long = 0xFF0F8A63,
    chipBgD: Long = 0xFF0F2E22, chipFgD: Long = 0xFF7FE3BF,
    onClick: () -> Unit
) {
    if (isModernUi()) {
        // Modern sidebar (M320): a card with a coloured icon chip, a subtitle and a badge
        val cs = MaterialTheme.colorScheme
        val light = isLightTheme()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (selected) cs.primaryContainer.copy(alpha = if (light) 0.45f else 0.5f)
                    else if (light) cs.surfaceContainerLowest else cs.surfaceContainer
                )
                .border(
                    if (selected) 1.5.dp else 1.dp,
                    if (selected) cs.primary else cs.outlineVariant,
                    RoundedCornerShape(14.dp)
                )
                .dpadFocusable(RoundedCornerShape(14.dp))
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .clickable { onClick() }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(10.dp))
                    .background(androidx.compose.ui.graphics.Color(if (light) chipBgL else chipBgD)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    icon, contentDescription = null,
                    tint = androidx.compose.ui.graphics.Color(if (light) chipFgL else chipFgD),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (selected) cs.primary else cs.onSurface,
                    maxLines = 1
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            if (badge != null) {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(11.dp))
                        .background(if (light) cs.surfaceContainer else cs.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = cs.onSurfaceVariant, maxLines = 1
                    )
                }
            }
        }
        return
    }
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
