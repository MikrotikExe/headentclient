package sk.tvhclient.android

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.formatDateFull
import sk.tvhclient.shared.formatTimeHm
import sk.tvhclient.shared.model.DupDetect
import sk.tvhclient.shared.model.DvrAutorec
import sk.tvhclient.shared.model.DvrEntry
import sk.tvhclient.shared.model.DvrTimerec
import sk.tvhclient.shared.model.TvhServer
import sk.tvhclient.shared.model.formatHm
import sk.tvhclient.shared.model.weekdayBit

/**
 * M696: recording rules ("timers") and the list of scheduled recordings.
 *
 * Shown only when "Enable timers" is on (TimersPref). The same content is used by the phone
 * archive (DvrNav.Scheduled / DvrNav.Timers), the TV archive rail ("_scheduled" / "_timers") and
 * the programme detail ("Record series"), in classic as well as modern mode. Everything is
 * operable with a D-pad: rows and chips are dpadFocusable, pickers are the same TvSelectDialog the
 * settings use, times are hour/minute pickers (no keyboard needed on a remote).
 *
 * The rules themselves live on the server (Tvheadend autorec / timerec); the app only edits the
 * everyday fields, the rest stays at the server's defaults.
 */

// ---------------------------------------------------------------------------------------------
// Data
// ---------------------------------------------------------------------------------------------

internal data class TimersData(
    val autorecs: List<DvrAutorec> = emptyList(),
    val timerecs: List<DvrTimerec> = emptyList(),
    val scheduled: List<DvrEntry> = emptyList(),
    /** channel uuid -> name (for the rows and the channel picker) */
    val channels: List<Pair<String, String>> = emptyList(),
    val loaded: Boolean = false,
    val failed: Boolean = false
)

internal class TimersViewModel : ViewModel() {
    private val _data = MutableStateFlow(TimersData())
    val data: StateFlow<TimersData> = _data
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private var loadedFor: String? = null

    fun loadIfNeeded(server: TvhServer) {
        if (loadedFor == server.id && _data.value.loaded) return
        refresh(server)
    }

    private var again = false

    /** A refresh while one is running is not lost — it runs once more when the current one ends. */
    fun refresh(server: TvhServer) {
        if (_busy.value) { again = true; return }
        _busy.value = true
        loadedFor = server.id
        viewModelScope.launch {
            val chans = withContext(Dispatchers.IO) {
                runCatching {
                    val api = Tvh.apiFor(server)
                    try { Tvh.fetchChannels(server, api).map { it.uuid to it.name } } finally { api.close() }
                }.getOrNull()
            }
            DvrController.refreshScheduled(server.id)
            val a = DvrController.autorecs(server)
            val t = DvrController.timerecs(server)
            val s = runCatching { DvrController.scheduledAll(server) }.getOrNull()
            val old = _data.value
            _data.value = TimersData(
                autorecs = a ?: old.autorecs,
                timerecs = t ?: old.timerecs,
                scheduled = s ?: old.scheduled,
                channels = chans ?: old.channels,
                loaded = true,
                failed = a == null || t == null
            )
            _busy.value = false
            if (again) { again = false; refresh(server) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------------------------

/** Locale weekday abbreviations Mon..Sun (index 0 = Monday). */
private fun weekdayNames(): List<String> {
    val s = java.text.DateFormatSymbols.getInstance().shortWeekdays   // 1 = Sunday ... 7 = Saturday
    val order = listOf(java.util.Calendar.MONDAY, java.util.Calendar.TUESDAY, java.util.Calendar.WEDNESDAY,
        java.util.Calendar.THURSDAY, java.util.Calendar.FRIDAY, java.util.Calendar.SATURDAY, java.util.Calendar.SUNDAY)
    return order.map { s.getOrNull(it)?.trimEnd('.')?.replaceFirstChar { c -> c.uppercase() } ?: "?" }
}

@Composable
private fun daysLabel(mask: Int): String {
    if (mask and DvrAutorec.ALL_DAYS == DvrAutorec.ALL_DAYS) return stringResource(R.string.timers_every_day)
    val names = remember { weekdayNames() }
    return (1..7).filter { mask and weekdayBit(it) != 0 }.joinToString(", ") { names[it - 1] }
}

@Composable
private fun dupLabel(v: Int): String = when (v) {
    DupDetect.UNIQUE -> stringResource(R.string.timers_new_only)
    DupDetect.ONCE_PER_DAY -> stringResource(R.string.timers_dup_day)
    DupDetect.ONCE_PER_WEEK -> stringResource(R.string.timers_dup_week)
    DupDetect.ALL -> stringResource(R.string.timers_dup_all)
    else -> "#$v"
}

private fun channelName(data: TimersData, uuid: String): String? =
    data.channels.firstOrNull { it.first == uuid }?.second

@Composable
private fun TimersLoading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        androidx.compose.material3.CircularProgressIndicator()
    }
}

private fun toast(ctx: Context, text: String) = Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show()

private fun resultText(ctx: Context, r: sk.tvhclient.shared.api.DvrResult, okRes: Int): String =
    if (r.success) ctx.getString(okRes)
    else ConnLimitText.of(ctx, r.error) ?: ctx.getString(
        R.string.timers_save_failed, if (r.timeout) ctx.getString(R.string.err_timeout) else (r.error ?: "?"))

// ---------------------------------------------------------------------------------------------
// Rows
// ---------------------------------------------------------------------------------------------

@Composable
private fun RuleRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    val cs = MaterialTheme.colorScheme
    val modern = isModernUi()
    val shape = RoundedCornerShape(if (modern) 13.dp else 8.dp)
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(if (modern) (if (isLightTheme()) cs.surfaceContainerLowest else cs.surfaceContainer)
                        else cs.surfaceVariant.copy(alpha = 0.35f))
            .border(1.dp, cs.outlineVariant, shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            Modifier.weight(1f).clip(shape).dpadFocusable(shape).clickable { onClick() }.padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null,
                tint = if (enabled) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = if (enabled) cs.onSurface else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        // the switch is display-only; the focusable box around it toggles (M367 pattern)
        Box(Modifier.clip(RoundedCornerShape(16.dp)).dpadFocusable(RoundedCornerShape(16.dp))
            .clickable { onToggle(!enabled) }.padding(horizontal = 4.dp)) {
            Switch(checked = enabled, onCheckedChange = null)
        }
        Spacer(Modifier.width(4.dp))
        TextButton(onClick = onDelete, modifier = Modifier.dpadFocusable()) {
            Text(stringResource(R.string.delete))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text.uppercase(), Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary)
}

// ---------------------------------------------------------------------------------------------
// Timers (rules) section
// ---------------------------------------------------------------------------------------------

/**
 * The list of rules with the add buttons. [header] = the section title shown above (the phone
 * and TV callers already draw their own folder header, so it is optional).
 */
@Composable
internal fun TimersSection(server: TvhServer, vm: TimersViewModel = viewModel(), header: Boolean = false) {
    val ctx = LocalContext.current
    val data by vm.data.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(server.id) { vm.loadIfNeeded(server) }

    var editAutorec by remember { mutableStateOf<DvrAutorec?>(null) }
    var editTimerec by remember { mutableStateOf<DvrTimerec?>(null) }
    var delAutorec by remember { mutableStateOf<DvrAutorec?>(null) }
    var delTimerec by remember { mutableStateOf<DvrTimerec?>(null) }

    var seenFail by remember { mutableStateOf(false) }
    LaunchedEffect(data.failed) {
        if (data.failed && !seenFail) toast(ctx, ctx.getString(R.string.load_error))
        seenFail = data.failed
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (header) Text(stringResource(R.string.dvr_timers), style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = { editTimerec = DvrTimerec(startMin = 20 * 60, stopMin = 21 * 60) },
                modifier = Modifier.dpadFocusable(RoundedCornerShape(20.dp))) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.timers_add_timer))
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { editAutorec = DvrAutorec(dupDetect = DupDetect.UNIQUE) },
                modifier = Modifier.dpadFocusable(RoundedCornerShape(20.dp))) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.timers_add_rule))
            }
        }
        if (!data.loaded) { TimersLoading(); return@Column }
        if (data.autorecs.isEmpty() && data.timerecs.isEmpty()) {
            EmptyStatus(stringResource(R.string.timers_empty)); return@Column
        }
        val anyCh = stringResource(R.string.timers_any_channel)
        val anyTime = stringResource(R.string.timers_any_time)
        val disabled = stringResource(R.string.timers_disabled)
        LazyColumn(Modifier.fillMaxSize()) {
            if (data.autorecs.isNotEmpty()) {
                item("hdr_a") { SectionLabel(stringResource(R.string.timers_rules_hdr)) }
                items(data.autorecs.size, key = { "a" + data.autorecs[it].id }) { i ->
                    val a = data.autorecs[i]
                    val parts = ArrayList<String>()
                    parts += channelName(data, a.channelUuid) ?: anyCh
                    parts += daysLabel(a.daysOfWeek)
                    parts += if (a.startMin >= 0) formatHm(a.startMin) else anyTime
                    parts += dupLabel(a.dupDetect)
                    if (!a.enabled) parts += disabled
                    RuleRow(
                        title = a.name.ifBlank { a.title }, subtitle = parts.joinToString(" · "),
                        enabled = a.enabled, icon = Icons.Default.Repeat,
                        onClick = { editAutorec = a },
                        onToggle = { on -> scope.launch {
                            val r = DvrController.saveAutorec(server, a.copy(enabled = on))
                            if (!r.success) toast(ctx, resultText(ctx, r, R.string.timers_saved))
                            DvrController.invalidateRules(server.id); vm.refresh(server)
                        } },
                        onDelete = { delAutorec = a }
                    )
                }
            }
            if (data.timerecs.isNotEmpty()) {
                item("hdr_t") { SectionLabel(stringResource(R.string.timers_time_hdr)) }
                items(data.timerecs.size, key = { "t" + data.timerecs[it].id }) { i ->
                    val t = data.timerecs[i]
                    val parts = ArrayList<String>()
                    parts += channelName(data, t.channelUuid) ?: anyCh
                    parts += daysLabel(t.daysOfWeek)
                    parts += formatHm(t.startMin) + "–" + formatHm(t.stopMin)
                    if (!t.enabled) parts += disabled
                    RuleRow(
                        title = t.name.ifBlank { t.title }, subtitle = parts.joinToString(" · "),
                        enabled = t.enabled, icon = Icons.Default.Schedule,
                        onClick = { editTimerec = t },
                        onToggle = { on -> scope.launch {
                            val r = DvrController.saveTimerec(server, t.copy(enabled = on))
                            if (!r.success) toast(ctx, resultText(ctx, r, R.string.timers_saved))
                            vm.refresh(server)
                        } },
                        onDelete = { delTimerec = t }
                    )
                }
            }
            item("pad") { Spacer(Modifier.height(24.dp)) }
        }
    }

    editAutorec?.let { a ->
        AutorecEditDialog(server, a, data, onDismiss = { editAutorec = null }) { saved ->
            scope.launch {
                val r = DvrController.saveAutorec(server, saved)
                toast(ctx, resultText(ctx, r, R.string.timers_saved))
                if (r.success) { editAutorec = null; DvrController.invalidateRules(server.id); vm.refresh(server) }
            }
        }
    }
    editTimerec?.let { t ->
        TimerecEditDialog(server, t, data, onDismiss = { editTimerec = null }) { saved ->
            scope.launch {
                val r = DvrController.saveTimerec(server, saved)
                toast(ctx, resultText(ctx, r, R.string.timers_saved))
                if (r.success) { editTimerec = null; vm.refresh(server) }
            }
        }
    }
    delAutorec?.let { a ->
        RuleDeleteDialog(a.name.ifBlank { a.title }, onDismiss = { delAutorec = null }) {
            scope.launch {
                val r = DvrController.deleteAutorec(server, a.id)
                toast(ctx, resultText(ctx, r, R.string.timers_deleted))
                delAutorec = null; DvrController.invalidateRules(server.id); vm.refresh(server)
            }
        }
    }
    delTimerec?.let { t ->
        RuleDeleteDialog(t.name.ifBlank { t.title }, onDismiss = { delTimerec = null }) {
            scope.launch {
                val r = DvrController.deleteTimerec(server, t.id)
                toast(ctx, resultText(ctx, r, R.string.timers_deleted))
                delTimerec = null; vm.refresh(server)
            }
        }
    }
}

@Composable
private fun RuleDeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.timers_delete_title)) },
        text = { Text(stringResource(R.string.timers_delete_msg, name)) },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.dpadFocusable()) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(fr).dpadFocusable()) { Text(stringResource(R.string.cancel)) } }
    )
}

// ---------------------------------------------------------------------------------------------
// Scheduled recordings section
// ---------------------------------------------------------------------------------------------

@Composable
internal fun ScheduledSection(server: TvhServer, vm: TimersViewModel = viewModel(), header: Boolean = false) {
    val ctx = LocalContext.current
    val data by vm.data.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(server.id) { vm.loadIfNeeded(server) }
    var cancelEntry by remember { mutableStateOf<DvrEntry?>(null) }
    val cs = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize()) {
        if (header) Text(stringResource(R.string.dvr_scheduled), style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        if (!data.loaded) { TimersLoading(); return@Column }
        if (data.scheduled.isEmpty()) { EmptyStatus(stringResource(R.string.dvr_scheduled_empty)); return@Column }
        val shape = RoundedCornerShape(if (isModernUi()) 13.dp else 8.dp)
        LazyColumn(Modifier.fillMaxSize()) {
            items(data.scheduled.size, key = { data.scheduled[it].commandId }) { i ->
                val e = data.scheduled[i]
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(shape).dpadFocusable(shape)
                        .background(if (isModernUi()) (if (isLightTheme()) cs.surfaceContainerLowest else cs.surfaceContainer)
                                    else cs.surfaceVariant.copy(alpha = 0.35f))
                        .border(1.dp, cs.outlineVariant, shape)
                        .clickable { cancelEntry = e }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Event, null, tint = cs.primary, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(e.channelName, formatDateFull(e.start), formatTimeHm(e.start) + "–" + formatTimeHm(e.stop))
                                .filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1
                        )
                    }
                }
            }
            item("pad") { Spacer(Modifier.height(24.dp)) }
        }
    }
    cancelEntry?.let { e ->
        val fr = remember { FocusRequester() }
        LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
        AlertDialog(
            onDismissRequest = { cancelEntry = null },
            title = { Text(stringResource(R.string.dvr_cancel_rec_title)) },
            text = { Text(stringResource(R.string.dvr_cancel_rec_msg, e.title)) },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val r = DvrController.cancel(server, e)
                        toast(ctx, if (r.success) ctx.getString(R.string.dvr_rec_cancelled)
                                   else ConnLimitText.of(ctx, r.error) ?: ctx.getString(if (r.timeout) R.string.err_timeout else R.string.dvr_rec_failed))
                        cancelEntry = null; vm.refresh(server)
                    }
                }, modifier = Modifier.dpadFocusable()) { Text(stringResource(R.string.dvr_rec_cancel_button)) }
            },
            dismissButton = { TextButton(onClick = { cancelEntry = null }, modifier = Modifier.focusRequester(fr).dpadFocusable()) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

// ---------------------------------------------------------------------------------------------
// Form widgets
// ---------------------------------------------------------------------------------------------

/** Hour + minute pickers (D-pad friendly; no keyboard). [minutes] = minutes after midnight. */
@Composable
private fun TimeField(label: String, minutes: Int, onChange: (Int) -> Unit) {
    val hours = remember { (0..23).map { it.toString() } }
    val mins = remember { (0..55 step 5).map { it.toString() } }
    val h = (minutes / 60).coerceIn(0, 23)
    val m = ((minutes % 60) / 5 * 5).coerceIn(0, 55)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) {
            DropdownField(label, h.toString(), hours, optionLabel = { if (it.length == 1) "0$it" else it }) {
                onChange((it.toIntOrNull() ?: h) * 60 + m)
            }
        }
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            DropdownField("min", m.toString(), mins, optionLabel = { if (it.length == 1) "0$it" else it }) {
                onChange(h * 60 + (it.toIntOrNull() ?: m))
            }
        }
    }
}

@Composable
private fun DaysField(mask: Int, onChange: (Int) -> Unit) {
    val names = remember { weekdayNames() }
    val cs = MaterialTheme.colorScheme
    Text(stringResource(R.string.timers_field_days), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (d in 1..7) {
            val on = mask and weekdayBit(d) != 0
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(if (on) cs.primary else cs.surfaceVariant)
                    .dpadFocusable(RoundedCornerShape(16.dp))
                    .clickable { onChange(mask xor weekdayBit(d)) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(names[d - 1], style = MaterialTheme.typography.labelLarge,
                    color = if (on) cs.onPrimary else cs.onSurface, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ChannelField(data: TimersData, uuid: String, allowAny: Boolean, onChange: (String) -> Unit) {
    val any = stringResource(R.string.timers_any_channel)
    val options = remember(data.channels, allowAny) {
        (if (allowAny) listOf("") else emptyList()) + data.channels.map { it.first }
    }
    DropdownField(stringResource(R.string.timers_field_channel), uuid, options,
        optionLabel = { u -> if (u.isBlank()) any else channelName(data, u) ?: u }) { onChange(it) }
}

@Composable
private fun ProfileField(server: TvhServer, value: String, onChange: (String) -> Unit) {
    val ctx = LocalContext.current
    var opts by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(server.id) { opts = runCatching { DvrProfileAsk.options(ctx, server) }.getOrDefault(emptyList()) }
    val def = stringResource(R.string.timers_profile_default)
    DropdownField(stringResource(R.string.timers_field_profile), value, listOf("") + opts,
        optionLabel = { if (it.isBlank()) def else it }) { onChange(it) }
}

@Composable
private fun FormDialog(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 6.dp,
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.94f)) {
            Column(Modifier.padding(20.dp).heightIn(max = 620.dp).verticalScroll(rememberScrollState())) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                content()
            }
        }
    }
}

@Composable
private fun EnabledRow(enabled: Boolean, onChange: (Boolean) -> Unit) {
    SettingsSwitchRow(label = stringResource(R.string.timers_enabled), checked = enabled, onChange = onChange)
}

// ---------------------------------------------------------------------------------------------
// Forms
// ---------------------------------------------------------------------------------------------

@Composable
private fun AutorecEditDialog(
    server: TvhServer, initial: DvrAutorec, data: TimersData,
    onDismiss: () -> Unit, onSave: (DvrAutorec) -> Unit
) {
    val ctx = LocalContext.current
    var rule by remember { mutableStateOf(initial) }
    val anyTime = stringResource(R.string.timers_any_time)
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    FormDialog(stringResource(if (initial.id.isBlank()) R.string.timers_add_rule else R.string.timers_edit_rule), onDismiss) {
        TvTextField(stringResource(R.string.timers_field_title), rule.title, { rule = rule.copy(title = it) },
            focusRequester = fr)
        Spacer(Modifier.height(8.dp))
        TvTextField(stringResource(R.string.timers_field_name), rule.name, { rule = rule.copy(name = it) })
        Spacer(Modifier.height(8.dp))
        ChannelField(data, rule.channelUuid, allowAny = true) { rule = rule.copy(channelUuid = it) }
        Spacer(Modifier.height(8.dp))
        DaysField(rule.daysOfWeek) { rule = rule.copy(daysOfWeek = it) }
        Spacer(Modifier.height(8.dp))
        // start around: "any" or a time (the window itself stays at the server default)
        val startOpts = remember { listOf("-1") + (0 until 24 * 60 step 30).map { it.toString() } }
        DropdownField(stringResource(R.string.timers_field_start_around),
            (if (rule.startMin < 0) -1 else rule.startMin / 30 * 30).toString(), startOpts,
            optionLabel = { v -> val n = v.toInt(); if (n < 0) anyTime else formatHm(n) }) {
            rule = rule.copy(startMin = it.toInt())
        }
        Spacer(Modifier.height(8.dp))
        val dupOpts = remember { listOf(DupDetect.UNIQUE, DupDetect.ALL, DupDetect.ONCE_PER_DAY, DupDetect.ONCE_PER_WEEK).map { it.toString() } }
        val curDup = if (rule.dupDetect.toString() in dupOpts) rule.dupDetect.toString() else DupDetect.ALL.toString()
        DropdownField(stringResource(R.string.timers_field_dup), curDup, dupOpts,
            optionLabel = { dupLabel(it.toInt()) }) { rule = rule.copy(dupDetect = it.toInt()) }
        Spacer(Modifier.height(8.dp))
        ProfileField(server, rule.configName) { rule = rule.copy(configName = it) }
        Spacer(Modifier.height(4.dp))
        EnabledRow(rule.enabled) { rule = rule.copy(enabled = it) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.dpadFocusable()) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (rule.title.isBlank()) { toast(ctx, ctx.getString(R.string.timers_title_required)); return@Button }
                onSave(rule.copy(title = rule.title.trim(), name = rule.name.trim().ifBlank { rule.title.trim() }))
            }, modifier = Modifier.dpadFocusable(RoundedCornerShape(20.dp))) { Text(stringResource(R.string.save)) }
        }
    }
}

@Composable
private fun TimerecEditDialog(
    server: TvhServer, initial: DvrTimerec, data: TimersData,
    onDismiss: () -> Unit, onSave: (DvrTimerec) -> Unit
) {
    val ctx = LocalContext.current
    var rule by remember { mutableStateOf(initial) }
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    FormDialog(stringResource(if (initial.id.isBlank()) R.string.timers_add_timer else R.string.timers_edit_timer), onDismiss) {
        TvTextField(stringResource(R.string.timers_field_name), rule.name, { rule = rule.copy(name = it) },
            focusRequester = fr)
        Spacer(Modifier.height(8.dp))
        ChannelField(data, rule.channelUuid, allowAny = false) { rule = rule.copy(channelUuid = it) }
        Spacer(Modifier.height(8.dp))
        TimeField(stringResource(R.string.timers_field_start), rule.startMin) { rule = rule.copy(startMin = it) }
        Spacer(Modifier.height(4.dp))
        TimeField(stringResource(R.string.timers_field_stop), rule.stopMin) { rule = rule.copy(stopMin = it) }
        Spacer(Modifier.height(8.dp))
        DaysField(rule.daysOfWeek) { rule = rule.copy(daysOfWeek = it) }
        Spacer(Modifier.height(8.dp))
        ProfileField(server, rule.configName) { rule = rule.copy(configName = it) }
        Spacer(Modifier.height(4.dp))
        EnabledRow(rule.enabled) { rule = rule.copy(enabled = it) }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss, modifier = Modifier.dpadFocusable()) { Text(stringResource(R.string.cancel)) }
            Spacer(Modifier.width(8.dp))
            Button(onClick = {
                if (rule.name.isBlank()) { toast(ctx, ctx.getString(R.string.timers_title_required)); return@Button }
                if (rule.channelUuid.isBlank()) { toast(ctx, ctx.getString(R.string.timers_channel_required)); return@Button }
                if (rule.stopMin <= rule.startMin) { toast(ctx, ctx.getString(R.string.timers_stop_before_start)); return@Button }
                // Tvheadend names the recordings by `title` (a format string); the app keeps it equal
                // to the rule's name unless the user set one on the web UI before
                val title = rule.title.ifBlank { rule.name.trim() }
                onSave(rule.copy(name = rule.name.trim(), title = title))
            }, modifier = Modifier.dpadFocusable(RoundedCornerShape(20.dp))) { Text(stringResource(R.string.save)) }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// "Record series" in the programme detail
// ---------------------------------------------------------------------------------------------

/**
 * One-click rule for the programme's title on its channel (new episodes only). If such a rule
 * already exists, the button removes it instead. Hidden when timers are off or the user cannot
 * record. [dpad] = focus the button like the Record button does on TV.
 */
@Composable
internal fun RecordSeriesButton(
    server: TvhServer,
    title: String,
    channelUuid: String,
    canRecord: Boolean,
    modifier: Modifier = Modifier
) {
    val ctx = LocalContext.current
    if (!TimersPref.stateOf(ctx).value || !canRecord || title.isBlank()) return
    val scope = rememberCoroutineScope()
    var existing by remember(title, channelUuid) { mutableStateOf<DvrAutorec?>(null) }
    var known by remember(title, channelUuid) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var askProfiles by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(title, channelUuid, reload) {
        existing = DvrController.seriesRuleFor(server, title, channelUuid)
        known = true
    }
    fun create(profile: String?) {
        busy = true
        scope.launch {
            if (profile != null) DvrAskPref.setLastUsed(ctx, server.id, profile)
            val r = DvrController.recordSeries(server, title, channelUuid, profile)
            toast(ctx, resultText(ctx, r, R.string.rec_series_done))
            busy = false; reload++
        }
    }
    if (askProfiles.isNotEmpty()) {
        DvrProfilePickDialog(
            options = askProfiles, subtitle = title,
            lastUsed = DvrAskPref.lastUsed(ctx, server.id),
            selected = -1,
            onPick = { name -> askProfiles = emptyList(); create(name) },
            onDismiss = { askProfiles = emptyList() },
            dpad = true
        )
    }
    OutlinedButton(
        onClick = {
            if (busy || !known) return@OutlinedButton
            val ex = existing
            if (ex != null) {
                busy = true
                scope.launch {
                    val r = DvrController.deleteAutorec(server, ex.id)
                    toast(ctx, resultText(ctx, r, R.string.rec_series_removed))
                    DvrController.invalidateRules(server.id)
                    busy = false; reload++
                }
            } else {
                scope.launch {
                    val opts = DvrProfileAsk.options(ctx, server)
                    if (opts.isEmpty()) create(null) else askProfiles = opts
                }
            }
        },
        enabled = !busy && known,
        modifier = modifier.fillMaxWidth()
    ) {
        Icon(Icons.Default.Repeat, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(if (existing != null) R.string.rec_series_cancel else R.string.rec_series))
    }
}
