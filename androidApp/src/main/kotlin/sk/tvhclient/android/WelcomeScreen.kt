package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Lock
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.sp
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
    var connMode by remember { mutableStateOf("htsp") }
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
    val versionLabel = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) \u2022 ${BuildConfig.BUILD_DATE}"

    // Branding (logo + nazov + popis) - zdielane pre oba layouty
    val modernUi = isModernUi()
    val branding: @Composable () -> Unit = {
        Box(
            modifier = Modifier
                .size(logoSize)
                .clip(RoundedCornerShape(if (modernUi) 28.dp else 24.dp))
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
        Text(
            "Headent Client",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }

    // Formular (polia + tlacidlo + viac moznosti + pokrocile) - zdielane; prepinac temy sa prida zvlast
    val formFields: @Composable () -> Unit = {
        val chipLight = isLightTheme()
        TvTextField(
            label = stringResource(R.string.field_host),
            value = host,
            onValueChange = { host = it; localError = false },
            uri = true,
            focusRequester = hostFocus,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.Filled.Dns,
            chipBg = Color(if (chipLight) 0xFFE0F2EF else 0xFF0F2E22),
            chipFg = Color(if (chipLight) 0xFF0F8A63 else 0xFF7FE3BF)
        )
        Spacer(Modifier.height(10.dp))
        TvTextField(
            label = stringResource(R.string.field_username),
            value = username,
            onValueChange = { username = it },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.Filled.Person,
            chipBg = Color(if (chipLight) 0xFFD8F0FB else 0xFF12283A),
            chipFg = Color(if (chipLight) 0xFF1877A8 else 0xFF7CC4E8)
        )
        Spacer(Modifier.height(10.dp))
        TvTextField(
            label = stringResource(R.string.field_password),
            value = password,
            onValueChange = { password = it },
            password = true,
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = Icons.Filled.Lock,
            chipBg = Color(if (chipLight) 0xFFFFE9D6 else 0xFF3A2B12),
            chipFg = Color(if (chipLight) 0xFFC07A17 else 0xFFE8B96A)
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .dpadFocusable()
                    .clickable { useHttps = !useHttps },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Switch len zobrazovaci — fokus/klik ma cely riadok (M367)
                Switch(checked = useHttps, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.field_https),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
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
            if (connMode != "htsp") {
                Spacer(Modifier.height(10.dp))
                DropdownField(
                    label = stringResource(R.string.field_profile),
                    value = profile,
                    options = ChannelPrefs.profileOptions.map { it.first }.filter { it.isNotBlank() },
                    optionLabel = { it }
                ) { profile = it }
                // M382: rovnaka poznamka ako v Upravit server
                Text(
                    stringResource(R.string.profile_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            Spacer(Modifier.height(8.dp))
            BackupControls(compact = true, onImported = { vm.refresh() })
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(bgColors))
    ) {
        if (compact) {
            // Dva stlpce: vlavo logo+nazov, vpravo prepinac+formular. Vsetko sa zmesti na sirku (TV / box / tablet).
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 40.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .then(
                            if (modernUi) Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .background(
                                    if (isLightTheme())
                                        MaterialTheme.colorScheme.surfaceContainerLowest
                                    else MaterialTheme.colorScheme.surfaceContainerLow
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
                                .padding(vertical = 40.dp, horizontal = 16.dp)
                            else Modifier
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    branding()
                    Spacer(Modifier.height(18.dp))
                    Text(
                        versionLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Spacer(Modifier.height(24.dp))
                    UiModePicker(ctx)
                }
                Spacer(Modifier.width(32.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ThemeSwitch(ctx)
                    Spacer(Modifier.height(12.dp))
                    formFields()
                }
            }
        } else {
            // Jeden stlpec (telefon na vysku)
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxHeight()
                    .widthIn(max = 520.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .imePadding()
                    .padding(horizontal = 24.dp, vertical = vPad),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ThemeSwitch(ctx)
                Spacer(Modifier.height(32.dp))
                branding()
                Spacer(Modifier.height(gapForm))
                formFields()
                Spacer(Modifier.height(28.dp))
                UiModePicker(ctx)
                // Verzia je sucastou skrolovacieho obsahu — neprekryva volbu vzhladu
                Spacer(Modifier.height(24.dp))
                Text(
                    versionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }
}


/**
 * Volba vzhladu rozhrania (M319) — na privitacej obrazovke pri prvom spusteni.
 * Dve karty s mini nahladmi (klasik = ploche riadky, moderny = karty s cipmi);
 * vyber sa aplikuje okamzite cez UiModePref (zivy stav), takze pouzivatel hned
 * vidi rozdiel na samotnom prihlaseni. Volba sa da neskor zmenit v nastaveniach.
 */
@Composable
private fun UiModePicker(ctx: android.content.Context) {
    val mode by UiModePref.stateOf(ctx)
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.ui_mode_title).uppercase(),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = cs.primary,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp)
        )
        Row(Modifier.fillMaxWidth()) {
            UiModeCard(
                label = stringResource(R.string.ui_mode_classic),
                selected = mode != UiModePref.MODERN,
                modernPreview = false,
                badge = null,
                modifier = Modifier.weight(1f)
            ) { UiModePref.set(ctx, UiModePref.CLASSIC) }
            Spacer(Modifier.width(12.dp))
            UiModeCard(
                label = stringResource(R.string.ui_mode_modern),
                selected = mode == UiModePref.MODERN,
                modernPreview = true,
                badge = stringResource(R.string.ui_mode_new_badge),
                modifier = Modifier.weight(1f)
            ) { UiModePref.set(ctx, UiModePref.MODERN) }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ui_mode_hint),
            style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant.copy(alpha = 0.8f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** Karta jednej volby vzhladu: mini nahlad + radio + nazov (+ badge NOVE). */
@Composable
private fun UiModeCard(
    label: String,
    selected: Boolean,
    modernPreview: Boolean,
    badge: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val light = isLightTheme()
    Box(modifier) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(
                    if (selected) cs.primaryContainer.copy(alpha = if (light) 0.45f else 0.5f)
                    else if (light) cs.surfaceContainerLowest else cs.surfaceContainer
                )
                .border(
                    if (selected) 2.dp else 1.dp,
                    if (selected) cs.primary else cs.outlineVariant,
                    RoundedCornerShape(18.dp)
                )
                .dpadFocusable(RoundedCornerShape(18.dp))
                .clickable { onClick() }
                .padding(12.dp)
        ) {
            // mini nahlad
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (light) cs.surfaceContainer else cs.surfaceContainerLowest)
                    .padding(8.dp)
            ) {
                if (modernPreview) {
                    val dots = listOf(cs.primary, cs.secondary, cs.tertiary)
                    dots.forEachIndexed { i, dot ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (light) cs.surfaceContainerLowest else cs.surfaceContainerHigh)
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(dot))
                            Spacer(Modifier.width(5.dp))
                            Box(
                                Modifier.height(5.dp)
                                    .fillMaxWidth(fraction = 0.5f + i * 0.15f)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(cs.onSurfaceVariant.copy(alpha = 0.5f))
                            )
                        }
                        if (i < dots.lastIndex) Spacer(Modifier.height(4.dp))
                    }
                } else {
                    val widths = listOf(0.6f, 0.95f, 0.95f, 0.8f)
                    widths.forEachIndexed { i, w ->
                        Box(
                            Modifier.height(6.dp)
                                .fillMaxWidth(fraction = w)
                                .clip(RoundedCornerShape(2.dp))
                                .background(
                                    cs.onSurfaceVariant.copy(alpha = if (i == 0) 0.55f else 0.3f)
                                )
                        )
                        if (i < widths.lastIndex) Spacer(Modifier.height(6.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selected) {
                    Box(
                        Modifier.size(18.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(cs.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("\u2713", color = cs.onPrimary,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold)
                    }
                } else {
                    Box(
                        Modifier.size(18.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .border(2.dp, cs.outline, androidx.compose.foundation.shape.CircleShape)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) {
                        if (isLightTheme()) cs.onPrimaryContainer else cs.onSurface
                    } else cs.onSurface,
                    maxLines = 1
                )
            }
        }
        if (badge != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 0.dp, end = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(cs.primary)
                    .padding(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = cs.onPrimary,
                    maxLines = 1
                )
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
            .focusGroup()
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
