package sk.tvhclient.android

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.dateKey
import sk.tvhclient.shared.formatDateFull
import sk.tvhclient.shared.formatTimeHm
import sk.tvhclient.shared.model.DvrClassifier
import sk.tvhclient.shared.model.DvrEntry

// Navigacia v archive (read-only zlozky)
private sealed class DvrNav {
    data object Root : DvrNav()
    data object Recent : DvrNav()
    data object Channels : DvrNav()
    data class Dates(val channel: String) : DvrNav()
    data class Day(val channel: String, val dateKey: String) : DvrNav()
    data class Category(val catKey: String) : DvrNav()
    data class Subgenre(val catKey: String, val subKey: String) : DvrNav()
    data class Series(val catKey: String, val subKey: String, val seriesTitle: String) : DvrNav()
}

@Composable
fun DvrScreen(vm: DvrViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var nav by remember { mutableStateOf<DvrNav>(DvrNav.Root) }

    // Po navrate z prehravaca obnov priznaky sledovania (hviezdicka/pozicia)
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    var progressTick by remember { mutableStateOf(0) }
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) progressTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(Unit) { vm.load() }

    // Spat: ak nie sme v root, vrat sa o uroven vyssie (nie zavri appku)
    BackHandler(enabled = nav != DvrNav.Root) {
        nav = when (val n = nav) {
            is DvrNav.Day -> DvrNav.Dates(n.channel)
            is DvrNav.Dates -> DvrNav.Channels
            is DvrNav.Channels -> DvrNav.Root
            is DvrNav.Recent -> DvrNav.Root
            is DvrNav.Series -> DvrNav.Subgenre(n.catKey, n.subKey)
            is DvrNav.Subgenre -> DvrNav.Category(n.catKey)
            is DvrNav.Category -> DvrNav.Root
            else -> DvrNav.Root
        }
    }

    Box(Modifier.fillMaxSize()) {
        when (val s = state) {
            is DvrState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
            is DvrState.NoServer -> Text(
                stringResource(R.string.no_active_server), Modifier.align(Alignment.Center)
            )
            is DvrState.Error -> Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(s.message, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                androidx.compose.material3.Button(onClick = { vm.load() }) {
                    Text(stringResource(R.string.retry))
                }
            }
            is DvrState.Loaded -> {
                if (s.entries.isEmpty()) {
                    Text(stringResource(R.string.dvr_empty), Modifier.align(Alignment.Center))
                } else {
                    DvrContent(s.entries, s.channelOrder, s.channelPicons, nav, context, progressTick, onNav = { nav = it })
                }
            }
        }
    }
}

@Composable
private fun DvrContent(
    entries: List<DvrEntry>,
    channelOrder: Map<String, Int>,
    channelPicons: Map<String, String?>,
    nav: DvrNav,
    context: Context,
    progressTick: Int,
    onNav: (DvrNav) -> Unit
) {
    val server = remember { Tvh.store.active() }
    val piconLoader = remember(server?.id) { PiconImageLoader.get(context, server) }
    when (nav) {
        is DvrNav.Root -> {
            // Zlozky: Posledne sledovane + Podla kanalov + kategorie
            val byCat = entries.groupBy { DvrClassifier.classify(it) }
            val recentCount = remember(progressTick, entries) {
                if (server == null) 0 else {
                    val uuids = entries.mapTo(HashSet()) { it.uuid }
                    WatchProgress.recent(context, server.id).count { it.first in uuids }
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                item("hdr") { Header(stringResource(R.string.dvr_archive)) }
                if (recentCount > 0) {
                    item("recent") {
                        FolderRow(
                            "\u25B6  " + stringResource(R.string.dvr_recent),
                            sub = "$recentCount"
                        ) { onNav(DvrNav.Recent) }
                    }
                }
                item("by_channel") {
                    FolderRow(
                        "\uD83D\uDCFA  " + stringResource(R.string.dvr_by_channel),
                        sub = "${entries.map { it.channelName }.distinct().size} " +
                            stringResource(R.string.dvr_channels_count)
                    ) { onNav(DvrNav.Channels) }
                }
                item("cat_hdr") { Header(stringResource(R.string.dvr_by_genre)) }
                items(DvrClassifier.order.filter { byCat.containsKey(it) }, key = { it }) { cat ->
                    val cnt = byCat[cat]?.size ?: 0
                    FolderRow(
                        "\uD83D\uDCC1  " + catLabel(cat),
                        sub = "$cnt"
                    ) { onNav(DvrNav.Category(cat)) }
                }
            }
        }

        is DvrNav.Recent -> {
            val list = remember(progressTick, entries) {
                if (server == null) emptyList() else {
                    val byUuid = entries.associateBy { it.uuid }
                    WatchProgress.recent(context, server.id).mapNotNull { byUuid[it.first] }
                }
            }
            RecordingList(list, context, progressTick, header = stringResource(R.string.dvr_recent))
        }

        is DvrNav.Channels -> {
            // Kanaly ktore maju nahravky, zoradene podla cisla kanala (ako v zozname),
            // kanaly bez cisla na koniec podla abecedy.
            val byChannel = entries.groupBy { it.channelName.ifBlank { "—" } }
            val channels = byChannel.keys.sortedWith(
                compareBy({ channelOrder[it] ?: Int.MAX_VALUE }, { it.lowercase() })
            )
            LazyColumn(Modifier.fillMaxSize()) {
                item("hdr") { Header(stringResource(R.string.dvr_by_channel)) }
                items(channels, key = { it }) { ch ->
                    val cnt = byChannel[ch]?.size ?: 0
                    ChannelFolderRow(ch, channelPicons[ch], piconLoader, context, sub = "$cnt") {
                        onNav(DvrNav.Dates(ch))
                    }
                }
            }
        }

        is DvrNav.Dates -> {
            val chEntries = entries.filter { it.channelName.ifBlank { "—" } == nav.channel }
            val byDate = chEntries.groupBy { dateKey(it.start) }
            val dates = byDate.keys.sortedDescending()
            LazyColumn(Modifier.fillMaxSize()) {
                item("hdr") { Header(nav.channel) }
                items(dates, key = { it }) { dk ->
                    val list = byDate[dk] ?: emptyList()
                    val label = list.firstOrNull()?.let { formatDateFull(it.start) } ?: dk
                    FolderRow("\uD83D\uDCC5  $label", sub = "${list.size}") {
                        onNav(DvrNav.Day(nav.channel, dk))
                    }
                }
            }
        }

        is DvrNav.Day -> {
            val list = entries
                .filter { it.channelName.ifBlank { "—" } == nav.channel && dateKey(it.start) == nav.dateKey }
                .sortedByDescending { it.start }
            RecordingList(list, context, progressTick, header = nav.channel)
        }

        is DvrNav.Category -> {
            val inCat = entries.filter { DvrClassifier.classify(it) == nav.catKey }
            if (DvrClassifier.hasSubgenres(nav.catKey)) {
                // Zlozky sub-zanrov ktore maju zaznamy
                val bySub = inCat.groupBy { DvrClassifier.subgenre(it, nav.catKey) }
                val order = DvrClassifier.subOrderFor(nav.catKey)
                LazyColumn(Modifier.fillMaxSize()) {
                    item("hdr") { Header(catLabel(nav.catKey)) }
                    items(order.filter { bySub.containsKey(it) }, key = { it }) { sub ->
                        FolderRow("\uD83D\uDCC1  " + subLabel(sub), sub = "${bySub[sub]?.size ?: 0}") {
                            onNav(DvrNav.Subgenre(nav.catKey, sub))
                        }
                    }
                }
            } else {
                RecordingList(inCat.sortedByDescending { it.start }, context, progressTick, header = catLabel(nav.catKey))
            }
        }

        is DvrNav.Subgenre -> {
            val inSub = entries.filter {
                DvrClassifier.classify(it) == nav.catKey &&
                DvrClassifier.subgenre(it, nav.catKey) == nav.subKey
            }
            if (DvrClassifier.isSeriesLike(nav.catKey)) {
                // Zoskup epizody pod serial (canonical title). Vzdy zlozka,
                // aj ked ma serial len jednu epizodu (konzistentne).
                val bySeries = inSub.groupBy { DvrClassifier.seriesCanonicalTitle(it.title) }
                val titles = bySeries.keys.sortedBy { it.lowercase() }
                LazyColumn(Modifier.fillMaxSize()) {
                    item("hdr") { Header(subLabel(nav.subKey)) }
                    items(titles, key = { it }) { t ->
                        val grp = bySeries[t] ?: emptyList()
                        FolderRow("\uD83D\uDCFA  $t", sub = "${grp.size}") {
                            onNav(DvrNav.Series(nav.catKey, nav.subKey, t))
                        }
                    }
                }
            } else {
                RecordingList(inSub.sortedByDescending { it.start }, context, progressTick, header = subLabel(nav.subKey))
            }
        }

        is DvrNav.Series -> {
            val eps = entries.filter {
                DvrClassifier.classify(it) == nav.catKey &&
                DvrClassifier.subgenre(it, nav.catKey) == nav.subKey &&
                DvrClassifier.seriesCanonicalTitle(it.title) == nav.seriesTitle
            }.sortedByDescending { it.start }
            RecordingList(eps, context, progressTick, header = nav.seriesTitle)
        }
    }
}

@Composable
private fun RecordingList(list: List<DvrEntry>, context: Context, progressTick: Int, header: String) {
    LazyColumn(Modifier.fillMaxSize()) {
        item("hdr") { Header(header) }
        items(list, key = { it.uuid }) { entry ->
            RecordingRow(entry, context, progressTick)
        }
    }
}

@Composable
private fun RecordingRow(entry: DvrEntry, context: Context, progressTick: Int) {
    val server = remember { Tvh.store.active() }
    val info = remember(entry.uuid, progressTick) {
        server?.let { WatchProgress.get(context, it.id, entry.uuid) }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable {
                val srv = Tvh.store.active() ?: return@clickable
                val url = Tvh.dvrUrl(srv, entry.uuid)
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_URL, url)
                    putExtra(PlayerActivity.EXTRA_TITLE, entry.title)
                    putExtra(PlayerActivity.EXTRA_DURATION_MS, entry.durationSec * 1000)
                    putExtra(PlayerActivity.EXTRA_DVR_UUID, entry.uuid)
                }
                context.startActivity(intent)
            }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Dopozerane = hviezdicka
        if (info?.completed == true) {
            Text("\u2605  ", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium)
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = buildString {
                if (entry.channelName.isNotBlank()) append(entry.channelName)
                if (entry.start > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append(formatTimeHm(entry.start))
                }
                val mins = entry.durationSec / 60
                if (mins > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append("$mins min")
                }
            }
            if (meta.isNotBlank()) {
                Text(meta, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Rozpozeranie (nie dopozerane): pozicia + ciara priebehu
            if (info != null && !info.completed && info.posMs > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    stringResource(R.string.dvr_resume_at, fmtPos(info.posMs)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
                if (info.fraction > 0f) {
                    Spacer(Modifier.height(2.dp))
                    LinearProgressIndicator(
                        progress = { info.fraction },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
        }
        Text("\u25B6", Modifier.padding(start = 8.dp),
            color = MaterialTheme.colorScheme.primary)
    }
}

private fun fmtPos(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "$h:" + m.toString().padStart(2, '0') + ":" + s.toString().padStart(2, '0')
    else "$m:" + s.toString().padStart(2, '0')
}

@Composable
private fun Header(text: String) {
    Text(
        text,
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold
    )
}

@Composable
private fun ChannelFolderRow(
    name: String,
    piconUrl: String?,
    loader: coil.ImageLoader,
    context: Context,
    sub: String,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(width = 56.dp, height = 40.dp),
            contentAlignment = Alignment.Center
        ) {
            if (piconUrl != null) {
                coil.compose.AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(piconUrl).build(),
                    contentDescription = name,
                    imageLoader = loader,
                    contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(2.dp)
                )
            } else {
                Text(name.take(2).uppercase(), style = MaterialTheme.typography.labelSmall)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(8.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  \u203A", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FolderRow(label: String, sub: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.width(8.dp))
        Text(sub, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("  \u203A", style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun subLabel(key: String): String {
    val resId = when (key) {
        DvrClassifier.MV_AKCNY -> R.string.sub_mv_akcny
        DvrClassifier.MV_KOMEDIA -> R.string.sub_mv_komedia
        DvrClassifier.MV_KRIMI -> R.string.sub_mv_krimi
        DvrClassifier.MV_DRAMA -> R.string.sub_mv_drama
        DvrClassifier.MV_SCIFI -> R.string.sub_mv_scifi
        DvrClassifier.MV_ROMANTIKA -> R.string.sub_mv_romantika
        DvrClassifier.MV_HOROR -> R.string.sub_mv_horor
        DvrClassifier.MV_DOBRODR -> R.string.sub_mv_dobrodruzny
        DvrClassifier.MV_ANIMAK -> R.string.sub_mv_animovany
        DvrClassifier.MV_HISTORICKY -> R.string.sub_mv_historicky
        DvrClassifier.MV_WESTERN -> R.string.sub_mv_western
        DvrClassifier.MV_INE -> R.string.sub_mv_ine
        DvrClassifier.SP_FUTBAL -> R.string.sub_sp_futbal
        DvrClassifier.SP_HOKEJ -> R.string.sub_sp_hokej
        DvrClassifier.SP_BASKETBAL -> R.string.sub_sp_basketbal
        DvrClassifier.SP_TENIS -> R.string.sub_sp_tenis
        DvrClassifier.SP_VOLEJBAL -> R.string.sub_sp_volejbal
        DvrClassifier.SP_HADZANA -> R.string.sub_sp_hadzana
        DvrClassifier.SP_BOJOVE -> R.string.sub_sp_bojove
        DvrClassifier.SP_ATLETIKA -> R.string.sub_sp_atletika
        DvrClassifier.SP_CYKLISTIKA -> R.string.sub_sp_cyklistika
        DvrClassifier.SP_MOTORSPORT -> R.string.sub_sp_motorsport
        DvrClassifier.SP_ZIMNE -> R.string.sub_sp_zimne
        DvrClassifier.SP_VODNE -> R.string.sub_sp_vodne
        DvrClassifier.SP_NEWS -> R.string.sub_sp_news
        DvrClassifier.NW_HLAVNE -> R.string.sub_nw_hlavne
        DvrClassifier.NW_POLITIKA -> R.string.sub_nw_politika
        DvrClassifier.NW_KRIMI -> R.string.sub_nw_krimi
        DvrClassifier.NW_MAGAZINY -> R.string.sub_nw_magaziny
        DvrClassifier.NW_POCASIE -> R.string.sub_nw_pocasie
        DvrClassifier.NW_INE -> R.string.sub_nw_ine
        DvrClassifier.SH_REALITY -> R.string.sub_sh_reality
        DvrClassifier.SH_TALK -> R.string.sub_sh_talk
        DvrClassifier.SH_SUTAZ -> R.string.sub_sh_sutaz
        DvrClassifier.SH_KUCHARSKE -> R.string.sub_sh_kucharske
        DvrClassifier.SH_ZABAVA -> R.string.sub_sh_zabava
        DvrClassifier.SH_MAGAZINY -> R.string.sub_sh_magaziny
        DvrClassifier.SH_INE -> R.string.sub_sh_ine
        DvrClassifier.CH_ANIMAK -> R.string.sub_ch_animak
        DvrClassifier.CH_ROZPRAVKY -> R.string.sub_ch_rozpravky
        DvrClassifier.CH_VZDELAVAC -> R.string.sub_ch_vzdelavac
        DvrClassifier.CH_FILMY -> R.string.sub_ch_filmy
        DvrClassifier.CH_INE -> R.string.sub_ch_ine
        DvrClassifier.MU_KLASIKA -> R.string.sub_mu_klasika
        DvrClassifier.MU_KONCERT -> R.string.sub_mu_koncert
        DvrClassifier.MU_HITY -> R.string.sub_mu_hity
        DvrClassifier.MU_FOLK -> R.string.sub_mu_folk
        DvrClassifier.MU_MAGAZINY -> R.string.sub_mu_magaziny
        DvrClassifier.MU_INE -> R.string.sub_mu_ine
        DvrClassifier.AR_DIVADLO -> R.string.sub_ar_divadlo
        DvrClassifier.AR_VYTVARNE -> R.string.sub_ar_vytvarne
        DvrClassifier.AR_LITERATURA -> R.string.sub_ar_literatura
        DvrClassifier.AR_FILM -> R.string.sub_ar_film
        DvrClassifier.AR_INE -> R.string.sub_ar_ine
        DvrClassifier.DC_PRIRODA -> R.string.sub_dc_priroda
        DvrClassifier.DC_HISTORIA -> R.string.sub_dc_historia
        DvrClassifier.DC_VEDA -> R.string.sub_dc_veda
        DvrClassifier.DC_CESTOPIS -> R.string.sub_dc_cestopis
        DvrClassifier.DC_OSOBNOSTI -> R.string.sub_dc_osobnosti
        DvrClassifier.DC_SPOLOCNOST -> R.string.sub_dc_spolocnost
        DvrClassifier.DC_INE -> R.string.sub_dc_ine
        DvrClassifier.HB_ZAHRADA -> R.string.sub_hb_zahrada
        DvrClassifier.HB_BYVANIE -> R.string.sub_hb_byvanie
        DvrClassifier.HB_VARENIE -> R.string.sub_hb_varenie
        DvrClassifier.HB_AUTO -> R.string.sub_hb_auto
        DvrClassifier.HB_CESTOVANIE -> R.string.sub_hb_cestovanie
        DvrClassifier.HB_ZDRAVIE -> R.string.sub_hb_zdravie
        DvrClassifier.HB_DIY -> R.string.sub_hb_diy
        DvrClassifier.HB_INE -> R.string.sub_hb_ine
        else -> R.string.sub_mv_ine
    }
    return stringResource(resId)
}

@Composable
private fun catLabel(key: String): String {
    val resId = when (key) {
        DvrClassifier.FILM -> R.string.cat_film
        DvrClassifier.SERIAL -> R.string.cat_serial
        DvrClassifier.SPORT -> R.string.cat_sport
        DvrClassifier.NEWS -> R.string.cat_news
        DvrClassifier.SHOW -> R.string.cat_show
        DvrClassifier.CHILDREN -> R.string.cat_children
        DvrClassifier.MUSIC -> R.string.cat_music
        DvrClassifier.ARTS -> R.string.cat_arts
        DvrClassifier.DOCUMENTARY -> R.string.cat_documentary
        DvrClassifier.HOBBY -> R.string.cat_hobby
        else -> R.string.cat_other
    }
    return stringResource(resId)
}
