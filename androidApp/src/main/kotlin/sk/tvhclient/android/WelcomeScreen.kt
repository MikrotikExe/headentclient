package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material3.MaterialTheme
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
    val hostFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Pociatocny D-pad fokus (TV) na prve pole Host/IP, nech sa zacina zhora
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(200)
        runCatching { hostFocus.requestFocus() }
    }

    val testState by vm.testState.collectAsState()
    var pending by remember { mutableStateOf<TvhServer?>(null) }

    // Po uspesnom teste uloz server a vojdi do appky
    LaunchedEffect(testState) {
        val st = testState
        if (st is TestState.Done && st.result is ConnectionResult.Success) {
            (vm.resolvedServer ?: pending)?.let { vm.save(it); vm.setActive(it.id); vm.resetTest() }
            pending = null
        }
    }

    // Pozadie aj text podla temy (svetla/tmava). Prepinac temy je hore,
    // lebo pred prihlasenim sa pouzivatel do nastaveni nedostane.
    val bgColors = if (isLightTheme())
        listOf(Color(0xFFEDEAF5), Color(0xFFF6F4FB), Color(0xFFFFFFFF))
    else
        listOf(Color(0xFF1B1430), Color(0xFF120F1A), Color(0xFF0C0B10))
    // Kompaktnejsi layout na sirku (Android TV / setobox / tablet na sirku), nech sa zmesti vsetko vratane loga
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val compact = configuration.screenWidthDp > configuration.screenHeightDp
    val logoSize = if (compact) 64.dp else 96.dp
    val logoIcon = if (compact) 36.dp else 54.dp
    val gapLogo = if (compact) 10.dp else 18.dp
    val gapForm = if (compact) 20.dp else 36.dp
    val vPad = if (compact) 8.dp else 16.dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(bgColors))
    ) {
    Column(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .fillMaxHeight()
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(top = if (compact) 56.dp else 0.dp)
            .padding(horizontal = 24.dp, vertical = vPad),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Na vysku (telefon) je prepinac temy v toku hore; na sirku (TV) je pripnuty mimo skrolu (nizsie)
        if (!compact) {
            ThemeSwitch(ctx)
            Spacer(Modifier.height(8.dp))
        }
        // Logo (na sirku/TV ho skryvame - prekryval by ho pripnuty prepinac a niet na neho miesto)
        if (!compact) {
            Box(
                modifier = Modifier
                    .size(logoSize)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    Icons.Default.LiveTv,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(logoIcon)
                )
            }
            Spacer(Modifier.height(gapLogo))
        }
        Text(
            "Headent Client",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(gapForm))

        TvTextField(
            label = stringResource(R.string.field_host),
            value = host,
            onValueChange = { host = it; localError = false },
            uri = true,
            focusRequester = hostFocus,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        TvTextField(
            label = stringResource(R.string.field_username),
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        TvTextField(
            label = stringResource(R.string.field_password),
            value = password,
            onValueChange = { password = it },
            password = true,
            modifier = Modifier.fillMaxWidth()
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
        // V prihlaseni nezobrazuj technicku verziu/API; pocas pripajania aj po uspesnom
        // teste (kym sa vojde do appky) ukaz "Prihlasenie prebieha". Chyby zobraz cez TestResultView.
        when (val st = testState) {
            is TestState.Running -> Text(
                stringResource(R.string.login_in_progress),
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold
            )
            is TestState.Done -> if (st.result is ConnectionResult.Success) {
                Text(
                    stringResource(R.string.login_in_progress),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold
                )
            } else {
                TestResultView(testState)
            }
            else -> {}
        }
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
                    vm.testAuto(srv)
                }
            },
            enabled = testState !is TestState.Running,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (testState is TestState.Running) {
                CircularProgressIndicator(
                    modifier = Modifier.width(22.dp).height(22.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text(stringResource(R.string.login_connect))
            }
        }

        Spacer(Modifier.height(16.dp))
        androidx.compose.material3.TextButton(onClick = { advanced = !advanced }) {
            Text(stringResource(R.string.login_more_options))
        }

        if (advanced) {
            Spacer(Modifier.height(4.dp))
            TvTextField(
                label = stringResource(R.string.field_name),
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            TvTextField(
                label = stringResource(R.string.field_port),
                value = port,
                onValueChange = { port = it },
                numeric = true,
                modifier = Modifier.fillMaxWidth()
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
                TvTextField(
                    label = stringResource(R.string.field_htsp_port),
                    value = htspPort,
                    onValueChange = { htspPort = it },
                    numeric = true,
                    modifier = Modifier.fillMaxWidth()
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
        if (compact) {
            // na sirku (TV/setobox): verzia v obsahu (pripnutie dole tu sposobovalo prekrytie)
            Spacer(Modifier.height(24.dp))
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) \u2022 ${BuildConfig.BUILD_DATE}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(Modifier.height(8.dp))
        } else {
            Spacer(Modifier.height(56.dp))
        }
    }

        // na vysku (telefon): verzia pripnuta dole
        if (!compact) {
            Text(
                "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) \u2022 ${BuildConfig.BUILD_DATE}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
            )
        }

        // Na sirku (TV): prepinac temy pripnuty hore, mimo skrolovania - nech sa neodreze (overscan) ani neodscrolluje
        if (compact) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(bgColors.first())
                    .statusBarsPadding()
                    .padding(top = 12.dp, bottom = 6.dp),
                contentAlignment = Alignment.Center
            ) {
                ThemeSwitch(ctx)
            }
        }
    }
}


/** Kompaktny prepinac temy (Auto / svetla / tmava) — hore na prihlasovacej obrazovke. */
@Composable
private fun ThemeSwitch(ctx: android.content.Context) {
    val mode = ThemePref.get(ctx)
    Row(
        modifier = Modifier
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val items = listOf(
            ThemePref.AUTO to "Auto",
            ThemePref.LIGHT to "\u2600",   // slnko
            ThemePref.DARK to "\u263D"     // mesiac
        )
        items.forEach { (m, glyph) ->
            val sel = mode == m
            Text(
                glyph,
                color = if (sel) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .clickable { ThemePref.set(ctx, m) }
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            )
        }
    }
}
