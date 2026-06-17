package sk.tvhclient.android

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import sk.tvhclient.shared.model.TvhServer

// Obrazovky nastaveni (vyclenene z MainActivity.kt kvoli prehladnosti).

@Composable
internal fun SettingsCategory(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(vertical = 16.dp, horizontal = 8.dp)
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
    }
}

// --- Vseobecne: jazyk + autostart ---
@Composable
internal fun GeneralSettings(ctx: android.content.Context) {
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

    Text(stringResource(R.string.autostart_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
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
                TabController.settingsDirty.value = true
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
                TabController.settingsDirty.value = true
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
}

// --- Prehravanie: predvolene audio stopy ---
@Composable
internal fun PlaybackSettings(ctx: android.content.Context) {
    Text(stringResource(R.string.audio_pref_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
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
    DropdownField(stringResource(R.string.audio_pref_2), audio[1], audioOptions, audioLabels) { setSlot(1, it) }
    DropdownField(stringResource(R.string.audio_pref_3), audio[2], audioOptions, audioLabels) { setSlot(2, it) }
}

// --- Playlist: rodicovsky zamok (PIN) ---
@Composable
internal fun PlaylistSettings(ctx: android.content.Context) {
    Text(stringResource(R.string.plock_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    var lockEnabled by remember { mutableStateOf(ParentalLock.isEnabled(ctx)) }
    var pinStage by remember { mutableStateOf(0) }
    var firstPin by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = lockEnabled,
            onCheckedChange = { on ->
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
    onEdit: (TvhServer) -> Unit
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
                onDelete = { vm.delete(server.id) }
            )
            Spacer(Modifier.height(8.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    Button(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.add_server))
    }
    Spacer(Modifier.height(24.dp))
    Text(stringResource(R.string.backup_section), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(8.dp))
    BackupControls(onImported = { vm.refresh() })
}

// --- Informacie: verzia appky + aktivny server ---
@Composable
internal fun RemoteSettings(ctx: android.content.Context) {
    Text(stringResource(R.string.remote_debug_title), style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    var on by remember { mutableStateOf(RemoteDebugPref.isEnabled(ctx)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = on,
            onCheckedChange = { v -> on = v; RemoteDebugPref.setEnabled(ctx, v) }
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.remote_debug_enable))
    }
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.remote_debug_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
internal fun InfoSettings(ctx: android.content.Context, servers: List<TvhServer>, activeId: String?) {
    val version = remember {
        runCatching {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    Text(stringResource(R.string.info_app_version) + ": " + version,
        style = MaterialTheme.typography.bodyLarge)
    Spacer(Modifier.height(12.dp))
    val active = servers.firstOrNull { it.id == activeId }
    if (active != null) {
        Text(stringResource(R.string.info_active_server), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(active.name, style = MaterialTheme.typography.bodyLarge)
        Text("${active.host}:${active.port}" + if (active.useHttps) " (HTTPS)" else "",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.field_conn_mode) + ": " + active.connectionMode.uppercase(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Text(stringResource(R.string.no_servers))
    }
}
