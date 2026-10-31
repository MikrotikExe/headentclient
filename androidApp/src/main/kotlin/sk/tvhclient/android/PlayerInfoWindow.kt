package sk.tvhclient.android

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import sk.tvhclient.shared.model.DvrEntry

/*
 * M661: the player info window extracted from PlayerUi (PlayerActivity.kt) — the 64 kB method limit.
 * A pure composable; the state (showInfo) is held by the caller and closed via [setShowInfo].
 */

/** Info window: detail of the currently running programme (INFO key / button). Call only when showInfo == true. */
@Composable
internal fun PlayerInfoWindow(
    setShowInfo: (Boolean) -> Unit,
    title: String,
    seekable: Boolean,
    progStart: Long,
    progStop: Long,
    progTitle: String,
    progDesc: String,
    nextTitle: String,
    nextStart: Long,
    nextStop: Long,
    liveNowSec: Long,
    liveChannels: List<LivePlaylist.LiveChannel>,
    liveCurrentIndex: Int,
    dvrActivity: PlayerActivity?,
    dvrEntry: DvrEntry? = null,
    /** M724: the "Stream" section (Settings → Advanced); null = off / not available. */
    streamDetails: StreamDetails? = null,
    /** M728 (issue #31): the image of the running programme (EPG), "" = none. */
    progImage: String = "",
    /** M724-fix2: a D-pad scroll request from the key router (tick changes, dir = -1 up / +1 down). */
    scrollTick: Int = 0,
    scrollDir: Int = 0
) {
    val infoScroll = androidx.compose.foundation.rememberScrollState()
    val stepPx = with(androidx.compose.ui.platform.LocalDensity.current) { 120.dp.toPx() }
    var seenTick by remember { mutableStateOf(scrollTick) }   // a tick from before the window opened is ignored
    LaunchedEffect(scrollTick) {
        if (scrollTick != seenTick) {
            seenTick = scrollTick
            if (scrollDir != 0) infoScroll.animateScrollBy(scrollDir * stepPx)
        }
    }
    // Info window: detail of the currently running programme (INFO key / button)
    androidx.activity.compose.BackHandler { setShowInfo(false) }
    val clk: (Long) -> String = { sec ->
        if (sec <= 0) "" else sk.tvhclient.shared.DateFmtCache.format(sk.tvhclient.shared.TimeFormatConfig.hm, java.util.Locale.getDefault(), sec * 1000)   // M743
    }
    val tRange = if (progStart > 0 && progStop > progStart)
        clk(progStart) + " \u2013 " + clk(progStop) else ""
    // M735: on a landscape screen (TV) the window is compact and at most 70 % of the screen height
    // (it used to grow to the full height and cut off the bottom); upright phone: 80 %, sizes as before
    val conf = androidx.compose.ui.platform.LocalConfiguration.current
    val compact = conf.orientation != android.content.res.Configuration.ORIENTATION_PORTRAIT
    val maxH = (conf.screenHeightDp * (if (compact) 0.70f else 0.80f)).dp
    val gapL = if (compact) 8.dp else 14.dp    // the larger gaps between blocks
    val gapXL = if (compact) 10.dp else 16.dp
    Box(
        Modifier
            .fillMaxSize()
            .background(playerScrim())
            .clickable { setShowInfo(false) },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.Surface(
            color = playerScrim(),
            shape = RoundedCornerShape(16.dp),
            // M734: a quarter narrower in landscape (TV) — the text was stretched across three
            // quarters of the screen; a phone held upright keeps the old width (already narrow)
            modifier = (if (!compact) Modifier.fillMaxWidth(0.72f).widthIn(max = 560.dp)
                else Modifier.fillMaxWidth(0.54f).widthIn(max = 420.dp)).heightIn(max = maxH)
        ) {
          Box {
            Column(
                Modifier
                    .padding(if (compact) 20.dp else 28.dp)
                    .verticalScroll(infoScroll)
            ) {
                val dvrTitle = dvrEntry?.dispTitle?.ifBlank { null } ?: progTitle.ifBlank { title }
                if (dvrEntry != null) {
                    // recording: title, channel / date / time, description, file size
                    // (same colours and sizes as the live info, readable in light and dark player theme)
                    Text(
                        dvrTitle,
                        color = playerFg(),
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                    val dvrDate = if (dvrEntry.start > 0) java.text.SimpleDateFormat(
                        "EEE, d. MMM yyyy", java.util.Locale.getDefault()
                    ).format(java.util.Date(dvrEntry.start * 1000)) else ""
                    val dvrMeta = listOf(dvrEntry.channelName, dvrDate, fmtRange(dvrEntry.start, dvrEntry.stop))
                        .filter { it.isNotBlank() }
                    if (dvrMeta.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(dvrMeta.joinToString("  \u2022  "), color = playerFgDim(), fontSize = 15.sp)
                    }
                    // the description stays in the outer scrolling column (an inner scroll can't be moved with a TV remote)
                    val displayDesc = dvrEntry.dispDescription.ifBlank { dvrEntry.dispSubtitle.ifBlank { progDesc } }
                    if (displayDesc.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Text(displayDesc, color = playerFgDim(), fontSize = 16.sp, lineHeight = 22.sp)
                    }
                    if (dvrEntry.fileSize > 0L) {
                        val units = arrayOf("B", "KiB", "MiB", "GiB", "TiB")
                        var size = dvrEntry.fileSize.toDouble()
                        var unit = 0
                        while (size >= 1024.0 && unit < units.size - 1) {
                            size /= 1024.0
                            unit++
                        }
                        val sizeText = if (unit == 0) "${dvrEntry.fileSize} B" else "%.1f %s".format(size, units[unit])
                        Spacer(Modifier.height(14.dp))
                        Text(
                            stringResource(R.string.dvr_info_file_size) + ": " + sizeText,
                            color = playerFgFaint(),
                            fontSize = 13.sp
                        )
                    }
                } else {
                    // channel header (live broadcast only)
                    val infoCh = liveChannels.getOrNull(liveCurrentIndex)
                    if (infoCh != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (infoCh.number > 0) {
                                Text(
                                    "${infoCh.number}",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                                Spacer(Modifier.width(10.dp))
                            }
                            Text(
                                infoCh.name,
                                color = playerFgDim(),
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        Spacer(Modifier.height(gapL))
                    }
                    Text(
                        dvrTitle,
                        color = playerFg(),
                        fontWeight = FontWeight.Bold,
                        fontSize = if (compact) 19.sp else 22.sp
                    )
                    val liveProg = !seekable && progStart > 0 && progStop > progStart
                    val remainMin = ((progStop - liveNowSec) / 60).coerceAtLeast(0)
                    if (tRange.isNotBlank()) {
                        Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
                        // M735: compact = the time and "remaining" on one line
                        val line = if (compact && liveProg) tRange + "  \u00B7  " + stringResource(R.string.time_remaining, remainMin) else tRange
                        Text(line, color = playerFgDim(), fontSize = if (compact) 14.sp else 15.sp)
                    }
                    // progress + remaining time (live broadcast only)
                    if (liveProg) {
                        val totalI = (progStop - progStart).coerceAtLeast(1)
                        val fracI = ((liveNowSec - progStart).toFloat() / totalI.toFloat())
                            .coerceIn(0f, 1f)
                        Spacer(Modifier.height(if (compact) 8.dp else 12.dp))
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { fracI },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            trackColor = playerTrack()
                        )
                        if (!compact) {
                            Spacer(Modifier.height(6.dp))
                            Text(stringResource(R.string.time_remaining, remainMin), color = playerFgFaint(), fontSize = 13.sp)
                        }
                    }
                    if (!seekable && progImage.isNotBlank()) {   // M728 (issue #31)
                        Spacer(Modifier.height(gapL))
                        ProgrammeImage(progImage, maxHeight = if (compact) 110.dp else 160.dp, spaceAfter = 0.dp)
                    }
                    if (progDesc.isNotBlank()) {
                        Spacer(Modifier.height(gapL))
                        // M735: compact = at most two lines (the full text is in the programme detail)
                        Text(progDesc, color = playerFgDim(),
                            fontSize = if (compact) 14.sp else 16.sp, lineHeight = if (compact) 19.sp else 22.sp,
                            maxLines = if (compact) 2 else Int.MAX_VALUE,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    }
                }
                // M490: recording from the info window too (phone — touch, without focus)
                if (dvrActivity?.dvrRecordVisible() == true) {
                    Spacer(Modifier.height(gapXL))
                    androidx.compose.material3.OutlinedButton(
                        onClick = { setShowInfo(false); dvrActivity?.toggleRecordCurrent() },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        androidx.compose.material3.Icon(
                            if (dvrActivity?.dvrExistingState?.value != null) Icons.Default.Stop
                            else Icons.Default.FiberManualRecord,
                            contentDescription = null
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(
                            if (dvrActivity?.dvrExistingState?.value != null)
                                R.string.dvr_rec_cancel_button else R.string.dvr_rec_button
                        ))
                    }
                }
                if (nextTitle.isNotBlank()) {
                    Spacer(Modifier.height(gapXL))
                    val nr = when {
                        nextStart > 0 && nextStop > nextStart ->
                            clk(nextStart) + " \u2013 " + clk(nextStop) + "  "
                        nextStart > 0 -> clk(nextStart) + "  "
                        else -> ""
                    }
                    Text(
                        // M491: it was hardcoded in Slovak
                        stringResource(R.string.mh_next) + " " + nr + nextTitle,
                        color = playerFgFaint(),
                        fontSize = if (compact) 13.sp else 14.sp
                    )
                }
                if (streamDetails != null) StreamInfoSection(streamDetails, compact)   // M724 / M735
            }
            // M735: a thin scroll indicator when the content is taller than the window
            if (infoScroll.maxValue > 0) {
                val frac = infoScroll.value.toFloat() / infoScroll.maxValue.toFloat()
                Box(Modifier.matchParentSize().padding(end = 6.dp, top = 12.dp, bottom = 12.dp)) {
                    androidx.compose.foundation.layout.BoxWithConstraints(
                        Modifier.align(Alignment.TopEnd).fillMaxHeight().width(3.dp)
                    ) {
                        val thumb = maxHeight * 0.3f
                        Box(
                            Modifier.padding(top = (maxHeight - thumb) * frac).width(3.dp).height(thumb)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
          }
        }
    }
}
