package sk.tvhclient.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.api.ConnectionResult
import sk.tvhclient.shared.model.TvhServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Obrazovky nastaveni (vyclenene z MainActivity.kt kvoli prehladnosti).

@Composable
internal fun SettingsCategory(
    label: String,
    focusRequester: androidx.compose.ui.focus.FocusRequester? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    subtitle: String? = null,
    badge: String? = null,
    chipBgL: Long = 0xFFE0F2EF, chipFgL: Long = 0xFF0F8A63,
    chipBgD: Long = 0xFF0F2E22, chipFgD: Long = 0xFF7FE3BF,
    onClick: () -> Unit
) {
    if (isModernUi() && icon != null) {
        // Moderny rezim: karta s farebnym ikonovym cipom, podtitulkom a badge
        val cs = MaterialTheme.colorScheme
        val light = isLightTheme()
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 5.dp)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .background(if (light) cs.surfaceContainerLowest else cs.surfaceContainer)
                .border(1.dp, cs.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .dpadFocusable(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
                .then(
                    if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier
                )
                .clickable { onClick() }
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(46.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
                    .background(Color(if (light) chipBgL else chipBgD)),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Icon(
                    icon, contentDescription = null,
                    tint = Color(if (light) chipFgL else chipFgD),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (badge != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(13.dp))
                        .background(if (light) cs.surfaceContainer else cs.surfaceContainerHigh)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        badge,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = cs.onSurfaceVariant, maxLines = 1
                    )
                }
            }
            Text(
                "  \u203A",
                style = MaterialTheme.typography.titleMedium,
                color = cs.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .dpadFocusable()
            .then(
                if (focusRequester != null)
                    Modifier.focusRequester(focusRequester)
                else Modifier
            )
            .clickable { onClick() }
            .padding(vertical = 16.dp, horizontal = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

/**
 * Skupina nastaveni moderneho rezimu: teal kapitalkovy nadpis + karta,
 * v ktorej su riadky oddelene jemnou linkou (pouzije M318 v podkategoriach).
 * V klasiku vykresli len obsah bez ramca (fallback pre spolocne pouzitie).
 */
@Composable
internal fun SettingsGroup(
    title: String? = null,
    classicTitle: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    if (!isModernUi()) {
        // Klasik: bez ramca; nadpis len tam, kde bol povodne (classicTitle);
        // rozostup 16dp za skupinou drzi povodny rytmus zoznamu
        if (classicTitle && title != null) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
        }
        Column(content = content)
        Spacer(Modifier.height(16.dp))
        return
    }
    val cs = MaterialTheme.colorScheme
    val light = isLightTheme()
    if (title != null) {
        Text(
            title.uppercase(),
            Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = cs.primary
        )
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .background(if (light) cs.surfaceContainerLowest else cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            // M336: plynula zmena vysky karty (napr. ked prepinac odhali notu)
            .animateContentSize(animationSpec = androidx.compose.animation.core.tween(180))
            .padding(horizontal = 14.dp, vertical = 6.dp),
        content = content
    )
}

/** Oddelenie riadkov v SettingsGroup: moderny rezim jemna linka, klasik povodny 16dp rozostup. */
@Composable
internal fun SettingsGroupDivider() {
    if (!isModernUi()) {
        Spacer(Modifier.height(16.dp))
        return
    }
    Box(
        Modifier.fillMaxWidth().height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
    )
}

/**
 * Riadok s prepinacom: moderny rezim nazov (+ volitelny popis) vlavo a Switch
 * vpravo; klasik povodne rozlozenie Switch + text (a popis pod tym).
 */
@Composable
internal fun SettingsSwitchRow(
    label: String,
    note: String? = null,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    // M367: jediny fokusovatelny/klikatelny prvok je CELY riadok (dpad ram + OK
    // prepina, dotyk kdekolvek na riadku). Vnutorny Switch je len zobrazovaci
    // (onCheckedChange = null -> nefokusovatelny), inak by na TV kradol D-pad
    // fokus a vonkajsi ram by sa nikdy neukazal.
    if (isModernUi()) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .dpadFocusable()
                .clickable { onChange(!checked) }
        ) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (note != null) {
                        Text(
                            note,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                Switch(checked = checked, onCheckedChange = null)
            }
        }
        return
    }
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .dpadFocusable()
            .clickable { onChange(!checked) }
    ) {
        Column(Modifier.padding(vertical = 4.dp, horizontal = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = checked, onCheckedChange = null)
                Spacer(Modifier.width(8.dp))
                Text(label)
            }
            if (note != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// --- Vseobecne: jazyk + autostart ---
@Composable
internal fun AppearanceSettings(ctx: android.content.Context) {
    SettingsGroup(null) {
    // Rezim rozhrania: klasicky / moderny
    var uiMode by remember { mutableStateOf(UiModePref.get(ctx)) }
    val uiModeLabel: @Composable (String) -> String = { v ->
        when (v) {
            UiModePref.MODERN -> stringResource(R.string.ui_mode_modern)
            else -> stringResource(R.string.ui_mode_classic)
        }
    }
    DropdownField(
        label = stringResource(R.string.ui_mode_title),
        value = uiMode,
        options = UiModePref.options,
        optionLabel = uiModeLabel,
        onSelect = { v ->
            uiMode = v
            UiModePref.set(ctx, v)
            TabController.settingsDirty.value = true
        }
    )
    SettingsGroupDivider()

    // Tema aplikacie: automaticky (system) / svetla / tmava
    var theme by remember { mutableStateOf(ThemePref.get(ctx)) }
    val themeLabel: @Composable (String) -> String = { v ->
        when (v) {
            ThemePref.LIGHT -> stringResource(R.string.theme_light)
            ThemePref.DARK -> stringResource(R.string.theme_dark)
            else -> stringResource(R.string.theme_auto)
        }
    }
    DropdownField(
        label = stringResource(R.string.theme_title),
        value = theme,
        options = ThemePref.options,
        optionLabel = themeLabel,
        onSelect = { v ->
            theme = v
            ThemePref.set(ctx, v)
            TabController.settingsDirty.value = true
        }
    )
    SettingsGroupDivider()

    // Tema prehravaca (samostatne od temy aplikacie)
    var playerTheme by remember { mutableStateOf(PlayerThemePref.get(ctx)) }
    val playerThemeLabel: @Composable (String) -> String = { v ->
        when (v) {
            PlayerThemePref.LIGHT -> stringResource(R.string.theme_light)
            PlayerThemePref.DARK -> stringResource(R.string.theme_dark)
            else -> stringResource(R.string.theme_auto)
        }
    }
    DropdownField(
        label = stringResource(R.string.player_theme_title),
        value = playerTheme,
        options = PlayerThemePref.options,
        optionLabel = playerThemeLabel,
        onSelect = { v ->
            playerTheme = v
            PlayerThemePref.set(ctx, v)
            TabController.settingsDirty.value = true
        }
    )

    // Plny podklad informacnej listy prehravaca — len na TV v modernom rezime
    // (na telefone taky overlay nie je, preto sa tam nezobrazuje). Default vypnute.
    val isTvDevice = ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
    if (isModernUi() && isTvDevice) {
        SettingsGroupDivider()
        var overlaySolid by remember { mutableStateOf(ModernOverlayPref.isSolidBg(ctx)) }
        SettingsSwitchRow(
            label = stringResource(R.string.overlay_solid_bg),
            note = stringResource(R.string.overlay_solid_bg_desc),
            checked = overlaySolid,
            onChange = { on ->
                overlaySolid = on
                ModernOverlayPref.setSolidBg(ctx, on)
                TabController.settingsDirty.value = true
            }
        )
    }

    SettingsGroupDivider()
    // Pozadie piconu (loga kanala) — Predvolene / Priehladne / farebne swatche.
    // Ovladatelne D-padom (TV) aj dotykom (telefon), prejavi sa vsade cez piconBackground().
    val light = isLightTheme()
    val piconSel = PiconBgPref.stateOf(ctx).value
    Text(
        stringResource(R.string.picon_bg_title),
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface
    )
    Text(
        stringResource(R.string.picon_bg_desc),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(top = 10.dp)
    ) {
        PiconBgPref.options.forEach { v ->
            val selected = piconSel == v
            val fill = when (v) {
                PiconBgPref.DEFAULT -> if (light) Color(0xFFA2A8B4) else Color(0xFF353B47)
                PiconBgPref.TRANSPARENT -> Color.Transparent
                else -> runCatching { Color(android.graphics.Color.parseColor(v)) }.getOrDefault(Color.Gray)
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(end = 12.dp)
            ) {
                Box(
                    Modifier
                        .size(64.dp, 46.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(fill)
                        .border(
                            if (selected) 3.dp else 1.dp,
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            RoundedCornerShape(10.dp)
                        )
                        .dpadFocusable(RoundedCornerShape(10.dp))
                        .clickable {
                            PiconBgPref.set(ctx, v)
                            TabController.settingsDirty.value = true
                        }
                )
                if (v == PiconBgPref.DEFAULT || v == PiconBgPref.TRANSPARENT) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(
                            if (v == PiconBgPref.DEFAULT) R.string.picon_bg_default
                            else R.string.picon_bg_transparent
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
    }
}

@Composable
internal fun GeneralSettings(ctx: android.content.Context) {
    SettingsGroup(stringResource(R.string.language)) {
    var lang by remember { mutableStateOf(LocaleHelper.getLang(ctx)) }
    DropdownField(
        label = stringResource(R.string.language),
        value = lang,
        options = listOf("", "in", "cs", "de", "en", "es", "fr", "hr", "it", "hu", "nl", "pl", "pt", "ro", "sk", "sl", "vi", "tr", "el", "bg", "ru", "sr", "uk", "ur", "ar", "fa", "hi", "bn", "th", "ko", "zh", "ja"),
        optionLabel = {
            when (it) {
                "sk" -> "Slovenčina"
                "cs" -> "Čeština"
                "en" -> "English"
                "de" -> "Deutsch"
                "es" -> "Español"
                "fr" -> "Français"
                "it" -> "Italiano"
                "hu" -> "Magyar"
                "nl" -> "Nederlands"
                "pl" -> "Polski"
                "pt" -> "Português"
                "ro" -> "Română"
                "ru" -> "Русский"
                "uk" -> "Українська"
                "zh" -> "中文"
                "hi" -> "हिन्दी"
                "ja" -> "日本語"
                "ko" -> "한국어"
                "tr" -> "Türkçe"
                "vi" -> "Tiếng Việt"
                "ar" -> "العربية"
                "in" -> "Bahasa Indonesia"
                "bn" -> "বাংলা"
                "el" -> "Ελληνικά"
                "fa" -> "فارسی"
                "sr" -> "Српски"
                "hr" -> "Hrvatski"
                "bg" -> "Български"
                "sl" -> "Slovenščina"
                "ur" -> "اردو"
                "th" -> "ไทย"
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
    }
    SettingsGroup("EPG") {

    // EPG: kolko dni dozadu si appka pamata (lokalny cache) a kolko dopredu nacita
    var epgBack by remember { mutableStateOf(EpgRangePref.daysBack(ctx)) }
    DropdownField(
        label = stringResource(R.string.epg_days_back_title),
        value = epgBack.toString(),
        options = EpgRangePref.dayOptions,
        optionLabel = { it },
        onSelect = { v ->
            val n = v.toIntOrNull() ?: epgBack
            epgBack = n
            EpgRangePref.setBack(ctx, n)
            TabController.settingsDirty.value = true
        }
    )
    SettingsGroupDivider()

    var epgFwd by remember { mutableStateOf(EpgRangePref.daysForward(ctx)) }
    DropdownField(
        label = stringResource(R.string.epg_days_forward_title),
        value = epgFwd.toString(),
        options = EpgRangePref.dayOptions,
        optionLabel = { it },
        onSelect = { v ->
            val n = v.toIntOrNull() ?: epgFwd
            epgFwd = n
            EpgRangePref.setForward(ctx, n)
            TabController.settingsDirty.value = true
        }
    )
    }

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
    SettingsGroup(stringResource(R.string.set_grp_autostart)) {
    var autostart by remember { mutableStateOf(AutostartPref.isEnabled(ctx)) }
    SettingsSwitchRow(
        label = stringResource(R.string.autostart_enable),
        checked = autostart,
        onChange = { on ->
            autostart = on; AutostartPref.setEnabled(ctx, on)
            TabController.settingsDirty.value = true
            if (on) requestOverlay()
        }
    )
    var autostartWake by remember { mutableStateOf(AutostartPref.isWakeEnabled(ctx)) }
    SettingsSwitchRow(
        label = stringResource(R.string.autostart_wake),
        checked = autostartWake,
        onChange = { on ->
            autostartWake = on; AutostartPref.setWakeEnabled(ctx, on)
            TabController.settingsDirty.value = true
            if (on) requestOverlay()
        }
    )
    Text(
        stringResource(R.string.autostart_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    }
}

// --- Prehravanie: predvolene audio stopy ---
@Composable
internal fun PlaybackSettings(ctx: android.content.Context) {
    SettingsGroup(stringResource(R.string.audio_pref_title), classicTitle = true) {
    var audio by remember {
        mutableStateOf(AudioPref.get(ctx).let { l -> List(3) { l.getOrNull(it) ?: "" } })
    }
    fun setSlot(i: Int, code: String) {
        val list = audio.toMutableList()
        list[i] = code
        audio = list
        AudioPref.set(ctx, list)
        TabController.settingsDirty.value = true
    }
    val audioLabels: @Composable (String) -> String = { code ->
        AudioPref.options.firstOrNull { it.first == code }?.second ?: "—"
    }
    val audioOptions = AudioPref.options.map { it.first }
    DropdownField(stringResource(R.string.audio_pref_1), audio[0], audioOptions, audioLabels) { setSlot(0, it) }
    SettingsGroupDivider()
    DropdownField(stringResource(R.string.audio_pref_2), audio[1], audioOptions, audioLabels) { setSlot(1, it) }
    SettingsGroupDivider()
    DropdownField(stringResource(R.string.audio_pref_3), audio[2], audioOptions, audioLabels) { setSlot(2, it) }
    }

    // Predvolene otacanie obrazovky v prehravaci — na TV/STB nema zmysel, skry
    val isTvDev = remember {
        val um = ctx.getSystemService(android.content.Context.UI_MODE_SERVICE) as? android.app.UiModeManager
        um?.currentModeType == android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
    }
    SettingsGroup(stringResource(R.string.set_grp_video)) {
    // AFR — automaticka obnovovacia frekvencia (M346; M348 aj telefony
    // cez Surface.setFrameRate — tam bez pauzy, system prepina plynulo)
    run {
        var afr by remember { mutableStateOf(AfrPref.get(ctx)) }
        SettingsSwitchRow(
            label = stringResource(R.string.afr_title),
            note = stringResource(R.string.afr_note),
            checked = afr,
            onChange = { on ->
                afr = on
                AfrPref.set(ctx, on)
                TabController.settingsDirty.value = true
            }
        )
        if (afr && isTvDev) {
            var afrDelay by remember { mutableStateOf(AfrDelayPref.get(ctx)) }
            DropdownField(
                label = stringResource(R.string.afr_delay_title),
                value = afrDelay.toString(),
                options = AfrDelayPref.options.map { it.toString() },
                optionLabel = { v ->
                    if (v == "0") stringResource(R.string.sleep_off) else "$v s"
                },
                onSelect = { v ->
                    afrDelay = v.toIntOrNull() ?: 2
                    AfrDelayPref.set(ctx, afrDelay)
                    TabController.settingsDirty.value = true
                }
            )
            // Prepnutie do HDR: vypnutim sa AFR obmedzi na plynulu zmenu
            // frekvencie (bez HDMI re-syncu), takze box neflipne do HDR.
            var hdrSwitch by remember { mutableStateOf(AfrHdrSwitchPref.get(ctx)) }
            SettingsSwitchRow(
                label = stringResource(R.string.afr_hdr_switch),
                note = stringResource(R.string.afr_hdr_switch_desc),
                checked = hdrSwitch,
                onChange = { on ->
                    hdrSwitch = on
                    AfrHdrSwitchPref.set(ctx, on)
                    TabController.settingsDirty.value = true
                }
            )
        }
        SettingsGroupDivider()
    }
    if (!isTvDev) {
        var orient by remember { mutableStateOf(OrientationPref.get(ctx)) }
        val orientLabel: @Composable (String) -> String = { v ->
            when (v) {
                OrientationPref.PORTRAIT -> stringResource(R.string.orient_portrait)
                OrientationPref.LANDSCAPE -> stringResource(R.string.orient_landscape)
                else -> stringResource(R.string.orient_auto)
            }
        }
        DropdownField(
            label = stringResource(R.string.orient_title),
            value = orient,
            options = OrientationPref.options,
            optionLabel = orientLabel,
            onSelect = { v ->
                orient = v
                OrientationPref.set(ctx, v)
                TabController.settingsDirty.value = true
            }
        )
        SettingsGroupDivider()
    }

    // Deinterlacing — odstranuje hrebenove pasy (combing) pri prekladanom DVB
    // videu na rychlych zaberoch. AUTO deinterlacuje len ked treba.
    var deint by remember { mutableStateOf(DeinterlacePref.get(ctx)) }
    val deintLabel: @Composable (String) -> String = { v ->
        when (v) {
            DeinterlacePref.OFF -> stringResource(R.string.track_off)
            DeinterlacePref.BOB -> "Bob"
            DeinterlacePref.YADIF -> "Yadif"
            DeinterlacePref.YADIF2X -> "Yadif (2x)"
            DeinterlacePref.X -> "X"
            else -> stringResource(R.string.orient_auto)
        }
    }
    DropdownField(
        label = stringResource(R.string.deint_title),
        value = deint,
        options = DeinterlacePref.options,
        optionLabel = deintLabel,
        onSelect = { v ->
            deint = v
            DeinterlacePref.set(ctx, v)
            TabController.settingsDirty.value = true
        }
    )

    // Zvukovy vystup — riesi rozchadzajuci sa / oneskoreny zvuk. PASSTHROUGH
    // (priamy prenos) posiela zvuk priamo do TV/AVR (zarovna sync na niektorych boxoch).
    // Len na TV/boxoch — na telefone nema zmysel (zvuk ide do reproduktora).
    }
    SettingsGroup(stringResource(R.string.set_grp_sound)) {
    if (isTvDev) {
        var aout by remember { mutableStateOf(AudioOutputPref.get(ctx)) }
        val aoutLabel: @Composable (String) -> String = { v ->
            when (v) {
                AudioOutputPref.PASSTHROUGH -> stringResource(R.string.audio_out_passthrough)
                AudioOutputPref.PCM -> "PCM"
                AudioOutputPref.STEREO -> "Stereo"
                else -> stringResource(R.string.orient_auto)
            }
        }
        DropdownField(
            label = stringResource(R.string.audio_out_title),
            value = aout,
            options = AudioOutputPref.options,
            optionLabel = aoutLabel,
            onSelect = { v ->
                aout = v
                AudioOutputPref.set(ctx, v)
                TabController.settingsDirty.value = true
            }
        )
        // Kompenzacia oneskorenia zvuku (M349) — sink latencia TV/AVR
        var adelay by remember { mutableStateOf(AudioDelayPref.get(ctx)) }
        DropdownField(
            label = stringResource(R.string.audio_delay_title),
            value = adelay,
            options = AudioDelayPref.options,
            optionLabel = { v ->
                if (v == AudioDelayPref.AUTO) stringResource(R.string.orient_auto) else "$v ms"
            },
            onSelect = { v ->
                adelay = v
                AudioDelayPref.set(ctx, v)
                TabController.settingsDirty.value = true
            }
        )
    }

    // Zvukovy vystup (telefon) — pri rozchadzajucom sa / oneskorenom zvuku, ktory
    // narasta v case, skus prepnut na OpenSL ES (ina sprava latencie).
    if (!isTvDev) {
        var amod by remember { mutableStateOf(AudioModulePref.get(ctx)) }
        val amodLabel: @Composable (String) -> String = { v ->
            when (v) {
                AudioModulePref.OPENSLES -> "OpenSL ES"
                else -> stringResource(R.string.orient_auto)
            }
        }
        DropdownField(
            label = stringResource(R.string.audio_out_title),
            value = amod,
            options = AudioModulePref.options,
            optionLabel = amodLabel,
            onSelect = { v ->
                amod = v
                AudioModulePref.set(ctx, v)
                TabController.settingsDirty.value = true
            }
        )
    }

    }
    SettingsGroup(stringResource(R.string.set_grp_behavior)) {
    // Automaticky PiP rezim (len zariadenia s podporou PiP - telefony/tablety)
    if (ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
        var autoPip by remember { mutableStateOf(AutoPipPref.get(ctx)) }
        SettingsSwitchRow(
            label = stringResource(R.string.auto_pip_title),
            checked = autoPip,
            onChange = { on ->
                autoPip = on
                AutoPipPref.set(ctx, on)
                TabController.settingsDirty.value = true
            }
        )
    }

    // Vyber pri archivovanom kanali v prehravaci (nazivo / od zaciatku) — len TV/box
    if (isTvDev) {
        SettingsGroupDivider()
        var archiveChoice by remember { mutableStateOf(ArchiveChoicePref.get(ctx)) }
        SettingsSwitchRow(
            label = stringResource(R.string.archive_choice_title),
            note = stringResource(R.string.archive_choice_note),
            checked = archiveChoice,
            onChange = { on ->
                archiveChoice = on
                ArchiveChoicePref.set(ctx, on)
                TabController.settingsDirty.value = true
            }
        )
    }

    // Timeshift (pauza/pretacanie zivej TV) — len pri HTSP pripojeni (9982); pri HTTP nema zmysel
    val htspMode = remember { sk.tvhclient.shared.Tvh.store.active()?.connectionMode == "htsp" }
    if (htspMode) {
        SettingsGroupDivider()
        var timeshift by remember { mutableStateOf(TimeshiftPref.get(ctx)) }
        SettingsSwitchRow(
            label = stringResource(R.string.ts_enable_title),
            note = stringResource(R.string.ts_enable_note),
            checked = timeshift,
            onChange = { on ->
                timeshift = on
                TimeshiftPref.set(ctx, on)
                TabController.settingsDirty.value = true
            }
        )
    }

    }
    SettingsGroup(stringResource(R.string.set_grp_maintenance)) {
    // M272: rucne obnovenie zoznamu kanalov, EPG a piconov — vymaze cache a stiahne nanovo.
    val reloadDone = stringResource(R.string.reload_data_done)
    OutlinedButton(onClick = {
        val srv = sk.tvhclient.shared.Tvh.store.active()
        LivePlaylist.clearEpg()
        LivePlaylist.channels = emptyList()
        if (srv != null) EpgCache.clearLive(ctx, srv.id)
        PiconImageLoader.clearCache(ctx, srv)
        TabController.dataReload.value++
        android.widget.Toast.makeText(ctx, reloadDone, android.widget.Toast.LENGTH_SHORT).show()
    }) {
        Text(stringResource(R.string.reload_data_title))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.reload_data_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    }
}

// --- Playlist: rodicovsky zamok (PIN) ---
@Composable
internal fun ParentalSettings(ctx: android.content.Context) {
    var lockEnabled by remember { mutableStateOf(ParentalLock.isEnabled(ctx)) }
    var pinStage by remember { mutableStateOf(0) }
    var firstPin by remember { mutableStateOf("") }
    SettingsGroup("PIN") {
    SettingsSwitchRow(
        label = stringResource(R.string.plock_enable),
        checked = lockEnabled,
        onChange = { on ->
            TabController.settingsDirty.value = true
            if (on) {
                if (ParentalLock.hasPin(ctx)) {
                    ParentalLock.setEnabled(ctx, true); lockEnabled = true
                } else pinStage = 1
            } else {
                ParentalLock.setEnabled(ctx, false); lockEnabled = false
            }
        }
    )
    OutlinedButton(
        onClick = { firstPin = ""; pinStage = 1 },
        modifier = Modifier.padding(top = 4.dp)
    ) {
        Text(
            if (ParentalLock.hasPin(ctx)) stringResource(R.string.plock_change_pin)
            else stringResource(R.string.plock_set_pin)
        )
    }

    // Sposob zadavania PIN
    SettingsGroupDivider()
    var pinInput by remember { mutableStateOf(ParentalLock.pinInput(ctx)) }
    DropdownField(
        label = stringResource(R.string.plock_pin_input_title),
        value = pinInput,
        options = listOf("picker", "keyboard"),
        optionLabel = { v ->
            if (v == "keyboard") stringResource(R.string.plock_pin_input_keyboard)
            else stringResource(R.string.plock_pin_input_picker)
        },
        onSelect = { v ->
            pinInput = v
            ParentalLock.setPinInput(ctx, v)
            TabController.settingsDirty.value = true
        }
    )

    // Okno po odomknuti (kym sa PIN znovu nepyta)
    SettingsGroupDivider()
    val graceOpts = listOf("0", "5", "10", "30", "60", "120")
    var grace by remember { mutableStateOf(ParentalLock.graceMinutes(ctx).toString()) }
    val graceLabel: @Composable (String) -> String = { v ->
        when (v) {
            "0" -> stringResource(R.string.plock_grace_always)
            "60" -> "1 h"
            "120" -> "2 h"
            else -> "$v min"
        }
    }
    DropdownField(
        label = stringResource(R.string.plock_grace_title),
        value = grace,
        options = graceOpts,
        optionLabel = graceLabel,
        onSelect = { v ->
            grace = v
            ParentalLock.setGraceMinutes(ctx, v.toIntOrNull() ?: ParentalLock.DEFAULT_GRACE_MIN)
            TabController.settingsDirty.value = true
        }
    )

    }
    // Co PIN chrani
    SettingsGroup(stringResource(R.string.plock_scope_title), classicTitle = true) {
    var protCh by remember { mutableStateOf(ParentalLock.protectChannels(ctx)) }
    SettingsSwitchRow(
        label = stringResource(R.string.plock_scope_channels),
        checked = protCh,
        onChange = { protCh = it; ParentalLock.setProtectChannels(ctx, it); TabController.settingsDirty.value = true }
    )
    var protSet by remember { mutableStateOf(ParentalLock.protectSettings(ctx)) }
    SettingsSwitchRow(
        label = stringResource(R.string.plock_scope_settings),
        checked = protSet,
        onChange = { protSet = it; ParentalLock.setProtectSettings(ctx, it); TabController.settingsDirty.value = true }
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
                } else { firstPin = ""; pinStage = 1; true }
            }
        )
    }
}

// --- Servery: zoznam serverov + zaloha/obnova ---
@Composable
internal fun ServersSettings(
    vm: ServersViewModel,
    servers: List<TvhServer>,
    activeId: String?,
    onAdd: () -> Unit,
    onEdit: (TvhServer) -> Unit,
    restoreEditId: String? = null,
    restoreEditFocus: androidx.compose.ui.focus.FocusRequester? = null,
    addFocus: androidx.compose.ui.focus.FocusRequester? = null
) {
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
                onDelete = { vm.delete(server.id) },
                editFocusRequester = if (server.id == restoreEditId) restoreEditFocus else null
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(
        onClick = onAdd,
        modifier = (if (addFocus != null) Modifier.focusRequester(addFocus) else Modifier)
            .fillMaxWidth()
    ) {
        Text(stringResource(R.string.add_server))
    }
    Spacer(Modifier.height(24.dp))
    SettingsGroup(stringResource(R.string.backup_section), classicTitle = true) {
    BackupControls(onImported = { vm.refresh() })
    }
}

// --- Informacie: verzia appky + aktivny server ---
@Composable
internal fun RemoteSettings(
    ctx: android.content.Context,
    servers: List<TvhServer>,
    activeId: String?
) {
    SettingsGroup(stringResource(R.string.remote_debug_title), classicTitle = true) {
    var on by remember { mutableStateOf(RemoteDebugPref.isEnabled(ctx)) }
    SettingsSwitchRow(
        label = stringResource(R.string.remote_debug_enable),
        note = stringResource(R.string.remote_debug_note),
        checked = on,
        onChange = { v -> on = v; RemoteDebugPref.setEnabled(ctx, v) }
    )
    }
}

@Composable
internal fun InfoSettings(
    ctx: android.content.Context,
    servers: List<TvhServer>,
    activeId: String?,
    onOpenDoc: (LegalDoc) -> Unit
) {
    val lang = remember { LocaleHelper.getLang(ctx) }
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    SettingsGroup(null) {
    // M395: na TV je informacny blok fokusovatelna karta — inak D-pad skoci rovno
    // na Dokumenty, obsah sa odroluje a vrchne informacie sa nedaju precitat.
    // M395-fix2: neviditelny fokus (dpadReadable) — blok nevyzera klikatelne,
    // ale D-pad sa nan vie postavit a odroluje sa do vyhladu
    Column(
        Modifier
            .fillMaxWidth()
            .dpadReadable()
            .padding(8.dp)
    ) {
    Text(
        stringResource(R.string.info_app_version) + ": " + version +
            " (" + BuildConfig.VERSION_CODE + ") \u2022 " + BuildConfig.BUILD_DATE,
        style = MaterialTheme.typography.bodyLarge
    )
    Spacer(Modifier.height(12.dp))
    val active = servers.firstOrNull { it.id == activeId }
    if (active != null) {
        var tvhSw by remember(active.id) { mutableStateOf<String?>(null) }
        var tvhApi by remember(active.id) { mutableStateOf<String?>(null) }
        LaunchedEffect(active.id) {
            withContext(Dispatchers.IO) {
                when (val r = Tvh.testConnection(active)) {
                    is ConnectionResult.Success -> {
                        tvhSw = r.info.swVersion ?: "?"
                        val proto = if (active.connectionMode == "htsp") "HTSP v" else "v"
                        tvhApi = proto + (r.info.apiVersion ?: 0)
                    }
                    else -> { tvhSw = "?"; tvhApi = "?" }
                }
            }
        }
        val labelStyle = MaterialTheme.typography.bodyMedium
        val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
        Text(stringResource(R.string.info_active_server), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.field_name) + ": " + active.name, style = labelStyle, color = labelColor)
        Text(stringResource(R.string.field_host) + ": " + active.host, style = labelStyle, color = labelColor)
        Text(stringResource(R.string.field_port) + ": " + active.port +
            if (active.useHttps) " (HTTPS)" else "", style = labelStyle, color = labelColor)
        Text(stringResource(R.string.field_conn_mode) + ": " + active.connectionMode.uppercase(),
            style = labelStyle, color = labelColor)
        Text(stringResource(R.string.info_tvh_version) + ": " + (tvhSw ?: "\u2026"),
            style = labelStyle, color = labelColor)
        Text(stringResource(R.string.info_api_version) + ": " + (tvhApi ?: "\u2026"),
            style = labelStyle, color = labelColor)
    } else {
        Text(stringResource(R.string.no_servers))
    }
    }

    }
    SettingsGroup(stringResource(R.string.set_grp_docs)) {
    InfoLinkRow(stringResource(R.string.privacy_policy)) { onOpenDoc(LegalText.privacy(lang)) }
    Spacer(Modifier.height(8.dp))
    InfoLinkRow(stringResource(R.string.terms_of_use)) { onOpenDoc(LegalText.terms(lang)) }
    }

    // Diagnosticky log (M353) — zobrazit / odoslat / vymazat
    SettingsGroup(stringResource(R.string.set_grp_diag)) {
        var logText by remember { mutableStateOf(CrashLogger.readText(ctx)) }
        val hasLog = logText.isNotBlank()
        Text(
            stringResource(R.string.diag_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.dpadReadable()   // M395-fix2: docitatelne D-padom
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
        if (hasLog) {
            InfoLinkRow(stringResource(R.string.diag_send)) { CrashLogReporter.share(ctx) }
            Spacer(Modifier.height(8.dp))
            InfoLinkRow(stringResource(R.string.diag_clear)) {
                CrashLogger.clear(ctx); logText = ""
            }
            Spacer(Modifier.height(8.dp))
            Text(
                logText.take(4000),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .dpadReadable()   // M395-fix2: dlhy log sa da docitat D-padom
                    .padding(horizontal = 8.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    .padding(10.dp)
            )
        } else {
            Text(
                stringResource(R.string.diag_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
    }
}

@Composable
private fun InfoLinkRow(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .dpadFocusable()
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary)
    }
}
