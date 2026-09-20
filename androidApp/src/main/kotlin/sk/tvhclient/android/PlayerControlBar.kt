package sk.tvhclient.android

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import sk.tvhclient.shared.model.TvhServer

/*
 * M661: the player control bar (AnimatedVisibility with the top info block, the DVR
 * seekbar and the classic / portrait / modern button row) extracted
 * from PlayerUi (PlayerActivity.kt), which was just under the 64 KB JVM method limit.
 * A purely mechanical move: all state is held by PlayerUi and passed here as values,
 * writes to states go through *Set lambdas. It is called from the root Box of PlayerUi
 * at the original place (between the small overlays from M630 and the "More" panel).
 */

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerControlsOverlay(
    controlsVisible: Boolean,
    onControlsVisibleSet: (Boolean) -> Unit,
    ctx: Context,
    dvrActivity: PlayerActivity?,
    title: String,
    seekable: Boolean,
    pipButton: Boolean,
    pipSupported: Boolean,
    timeshiftEngaged: Boolean,
    profileSwitch: Boolean,
    controlNavIndex: Int,
    liveChannels: List<LivePlaylist.LiveChannel>,
    liveCurrentIndex: Int,
    server: TvhServer?,
    liveNowSec: Long,
    progStart: Long,
    progStop: Long,
    progTitle: String,
    progDesc: String,
    nextTitle: String,
    nextStart: Long,
    nextStop: Long,
    sleepLeftMin: Long,
    timeshiftOffsetMs: Long,
    barLengthMs: Long,
    lengthMs: Long,
    recordingOffsetMs: Long,
    scrubFrac: Float,
    progStartFrac: Float,
    progStopFrac: Float,
    dragging: Boolean,
    onDraggingSet: (Boolean) -> Unit,
    dragValue: Float,
    onDragValueSet: (Float) -> Unit,
    posTimeMs: Long,
    onPosTimeMsSet: (Long) -> Unit,
    onPosFractionSet: (Float) -> Unit,
    onSeekToMs: (Long) -> Unit,
    isPlaying: Boolean,
    menu: String?,
    onMenuSet: (String?) -> Unit,
    onShowChannelListSet: (Boolean) -> Unit,
    showInfo: Boolean,
    onShowInfoSet: (Boolean) -> Unit,
    onShowMoreSheetSet: (Boolean) -> Unit,
    orientationLocked: Boolean,
    onOrientationLockedSet: (Boolean) -> Unit,
    onOrientationLockChange: (Boolean) -> Unit,
    onPrevChannel: (() -> Unit)?,
    onNextChannel: (() -> Unit)?,
    onTogglePlay: () -> Unit,
    onSkipBack: () -> Unit,
    onSkipFwd: () -> Unit,
    onOpenEpg: () -> Unit,
    onEnterPip: () -> Unit,
    onOpenSleep: () -> Unit,
    onClose: () -> Unit
) {
    AnimatedVisibility(
        visible = controlsVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier.fillMaxSize()
    ) {
        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            val order = playerControlOrder(onPrevChannel != null, seekable, pipButton, timeshiftEngaged, profileSwitch,
                dvrActivity?.dvrRecordVisible() == true, dvrActivity?.teletextVisible() == true)
            // focus highlight only on TV (D-pad); on a phone (touch) no "selected" button
            val isTvDevice = remember { isTvUiMode(ctx) }   // M679
            val selCtrl = if (isTvDevice) order.getOrNull(controlNavIndex) else null
            val curCh = liveChannels.getOrNull(liveCurrentIndex)
            val infoLoader = remember(server?.id) { PiconImageLoader.get(ctx, server) }
            fun clock(sec: Long): String =
                if (sec <= 0) "" else java.text.SimpleDateFormat(sk.tvhclient.shared.TimeFormatConfig.hm, java.util.Locale.getDefault())
                    .format(java.util.Date(sec * 1000))
            val dateTime = java.text.SimpleDateFormat("EEE d. M., " + sk.tvhclient.shared.TimeFormatConfig.hm, java.util.Locale.getDefault())
                .format(java.util.Date(liveNowSec * 1000))
            val hasNow = !seekable && progStart > 0 && progStop > progStart
            val total = (progStop - progStart).coerceAtLeast(1)
            val elapsed = (liveNowSec - progStart).coerceIn(0, total)
            val fracNow = elapsed.toFloat() / total.toFloat()
            val remainMin = if (hasNow) ((progStop - liveNowSec) / 60).coerceAtLeast(0) else 0
            // scaling by the box resolution (a compact, readable bar)
            // Measure the real window width (BoxWithConstraints), not Configuration.screenWidthDp —
            // on some devices it reports a wrong value (compact layout in landscape).
            // maxWidth reflects the real window pixels, so a wide window always gets the landscape layout.
            BoxWithConstraints(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
            ) {
                val k = (maxWidth.value / 640f).coerceIn(0.9f, 1.25f)
                val portrait = maxWidth < 600.dp

                // One shared info + control bar at the bottom
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(playerScrim())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                Row(verticalAlignment = Alignment.Top) {
                    // number + logo + channel name (live only; not needed for DVR)
                    if (!seekable) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width((76 * k).dp)
                    ) {
                        if ((curCh?.number ?: 0) > 0) {
                            Text(
                                "${curCh?.number}",
                                color = playerFg(),
                                fontWeight = FontWeight.Bold,
                                fontSize = (24 * k).sp
                            )
                        }
                        if (curCh?.piconUrl != null) {
                            Spacer(Modifier.height(2.dp))
                            AsyncImage(
                                model = ImageRequest.Builder(ctx).data(curCh.piconUrl).build(),
                                contentDescription = null,
                                imageLoader = infoLoader,
                                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
                                modifier = Modifier.size((56 * k).dp, (32 * k).dp)
                            )
                        }
                        Text(
                            title,
                            color = playerFgDim(),
                            fontSize = (11 * k).sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                    Spacer(Modifier.width(14.dp))
                    }
                    // programme description: title, time, progress, description, next
                    Column(Modifier.weight(1f)) {
                        val headline = if (seekable) title else progTitle
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                headline,
                                color = playerFg(),
                                fontWeight = FontWeight.Bold,
                                fontSize = (16 * k).sp,
                                maxLines = if (seekable) 2 else 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    dateTime,
                                    color = playerFgDim(),
                                    fontSize = (12 * k).sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                if (sleepLeftMin > 0) {
                                    Text(
                                        "\u23F2 ${sleepLeftMin} min",
                                        color = Color(0xCC8AB4F8),
                                        fontSize = (12 * k).sp,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                            }
                        }
                        if (hasNow) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    clock(progStart) + " \u2013 " + clock(progStop),
                                    color = playerFgDim(),
                                    fontSize = (12 * k).sp,
                                    maxLines = 1,
                                    softWrap = false
                                )
                                androidx.compose.material3.LinearProgressIndicator(
                                    progress = { fracNow },
                                    modifier = Modifier
                                        .width((88 * k).dp)
                                        .padding(horizontal = 8.dp),
                                    trackColor = playerTrack()
                                )
                                Text(
                                    "$remainMin min",
                                    color = playerFgDim(),
                                    fontSize = (12 * k).sp, maxLines = 1, softWrap = false
                                )
                                if (timeshiftOffsetMs > 0L) {
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        "\u2212" + fmtMs(timeshiftOffsetMs),
                                        color = androidx.compose.ui.graphics.Color(0xFFFF3B30),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = (12 * k).sp, maxLines = 1, softWrap = false
                                    )
                                }
                            }
                        }
                        if (progDesc.isNotBlank()) {
                            Text(
                                progDesc,
                                color = playerFgDim(),
                                fontSize = (12 * k).sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                        if (nextTitle.isNotBlank()) {
                            Text(
                                clock(nextStart) + " \u2013 " + clock(nextStop) + "  " + nextTitle,
                                color = playerFgFaint(),
                                fontSize = (12 * k).sp,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Spacer(Modifier.height((4 * k).dp))
                // DVR: seek bar (highlighted when "seek" is selected)
                if (seekable && barLengthMs > 0) {
                    val seekFocused = selCtrl == "seek"
                    val frac = when {
                        seekFocused -> scrubFrac
                        dragging -> dragValue
                        else -> (posTimeMs.toFloat() / barLengthMs).coerceIn(0f, 1f)
                    }
                    // Left side: during dragging/selection the target time, otherwise the actual playback time
                    val cur = if (dragging || seekFocused) (frac * barLengthMs).toLong() else posTimeMs
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                            .then(
                                if (seekFocused) Modifier.border(
                                    2.dp, playerFg(), RoundedCornerShape(8.dp)
                                ) else Modifier
                            )
                            .padding(horizontal = 4.dp)
                    ) {
                        Text(fmtMs(cur), color = playerFg(),
                            style = MaterialTheme.typography.bodySmall)
                        Box(
                            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            androidx.compose.material3.Slider(
                                value = frac.coerceIn(0f, 1f),
                                onValueChange = { onDraggingSet(true); onDragValueSet(it) },
                                onValueChangeFinished = {
                                    // target within the programme time (within the reachable range)
                                    val progMs = (dragValue.coerceIn(0f, 1f) * barLengthMs).toLong()
                                        .coerceIn(0L, barLengthMs)
                                    onPosTimeMsSet(progMs)           // immediate UI feedback
                                    onPosFractionSet(if (lengthMs > 0)
                                        ((recordingOffsetMs + progMs).toFloat() /
                                            (recordingOffsetMs + lengthMs)).coerceIn(0f, 1f)
                                    else 0f)
                                    onDraggingSet(false)
                                    // the real seek by rebuilding the stream (feeder byte-restart /
                                    // direct :start-time) - player.position does not work on a pipe
                                    onSeekToMs(progMs)
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                            // Programme markers: red = start (end of the leading margin),
                            // lighter = end of the programme (start of the trailing margin).
                            if (progStartFrac > 0.002f || progStopFrac < 0.998f) {
                                androidx.compose.foundation.Canvas(
                                    modifier = Modifier.matchParentSize()
                                ) {
                                    val thumb = 10.dp.toPx()
                                    val usable = (size.width - 2 * thumb).coerceAtLeast(0f)
                                    val w = 3.dp.toPx()
                                    // height exactly across the bar (~16 dp track), centred
                                    val half = 8.dp.toPx()
                                    val cy = size.height / 2f
                                    fun tick(f: Float, c: Color) {
                                        val x = thumb + f.coerceIn(0f, 1f) * usable
                                        drawLine(c, Offset(x, cy - half), Offset(x, cy + half), w)
                                    }
                                    if (progStopFrac < 0.998f)
                                        tick(progStopFrac, Color(0x80FF5252))
                                    if (progStartFrac > 0.002f)
                                        tick(progStartFrac, Color(0xFFFF1744))
                                }
                            }
                        }
                        Text(fmtMs(barLengthMs), color = playerFg(),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Spacer(Modifier.height((6 * k).dp))
                // Buttons: close, list, prev, play, next, audio, subtitles, sw
                val bk = if (portrait) 0.95f else (0.78f * k)
                // the rotation lock button only makes sense when the orientation is automatic;
                // hide it when the orientation is fixed (portrait/landscape)
                val lockVisible = remember {
                    pipSupported && OrientationPref.get(ctx) == OrientationPref.AUTO
                }
                fun has(id: String) = order.contains(id)
                // a single button by id (captures the surrounding state)
                @Composable
                fun barCtrl(c: String) {
                    when (c) {
                        "close" -> CircleButton(Icons.AutoMirrored.Filled.ArrowBack, selected = selCtrl == "close", scale = bk, onClick = onClose)
                        "list" -> CircleButton(
                            icon = Icons.AutoMirrored.Filled.List, selected = selCtrl == "list", scale = bk,
                            onClick = { onShowChannelListSet(true); onControlsVisibleSet(false) }
                        )
                        "prev" -> if (onPrevChannel != null) CircleButton(
                            icon = Icons.Default.SkipPrevious, selected = selCtrl == "prev", scale = bk, onClick = onPrevChannel
                        )
                        "play" -> PlayPauseButton(
                            isPlaying = isPlaying,
                            selected = selCtrl == "play",
                            scale = bk,
                            onClick = onTogglePlay
                        )
                        "tsrew" -> CircleButton(
                            icon = Icons.Default.Replay30, selected = selCtrl == "tsrew", scale = bk, onClick = onSkipBack
                        )
                        "tsff" -> CircleButton(
                            icon = Icons.Default.Forward30, selected = selCtrl == "tsff", scale = bk, onClick = onSkipFwd
                        )
                        "next" -> if (onNextChannel != null) CircleButton(
                            icon = Icons.Default.SkipNext, selected = selCtrl == "next", scale = bk, onClick = onNextChannel
                        )
                        "epg" -> CircleButton(
                            icon = Icons.Default.GridView, selected = selCtrl == "epg", scale = bk, onClick = onOpenEpg
                        )
                        "pip" -> CircleButton(
                            icon = Icons.Default.PictureInPictureAlt, selected = selCtrl == "pip", scale = bk, onClick = onEnterPip
                        )
                        // M490: record / cancel the recording of the currently running programme
                        "rec" -> CircleButton(
                            icon = if (dvrActivity?.dvrExistingState?.value != null)
                                Icons.Default.Stop else Icons.Default.FiberManualRecord,
                            selected = selCtrl == "rec", scale = bk,
                            onClick = { dvrActivity?.toggleRecordCurrent() }
                        )
                        "info" -> CircleButton(
                            icon = Icons.Default.Info, selected = selCtrl == "info", scale = bk,
                            onClick = { onShowInfoSet(!showInfo) }
                        )
                        "sleep" -> CircleButton(
                            icon = Icons.Default.Timer, selected = selCtrl == "sleep", scale = bk,
                            onClick = onOpenSleep
                        )
                        // M553: teletext (live channel only; HTSP if it has the track)
                        "txt" -> CircleButton(
                            icon = Icons.AutoMirrored.Filled.Article, selected = selCtrl == "txt", scale = bk,
                            onClick = { dvrActivity?.openTeletext() }
                        )
                        "audio" -> CircleButton(
                            icon = Icons.Default.MusicNote, selected = selCtrl == "audio", scale = bk,
                            onClick = { onMenuSet(if (menu == "audio") null else "audio") }
                        )
                        "subs" -> CircleButton(
                            icon = Icons.Default.ClosedCaption, selected = selCtrl == "subs", scale = bk,
                            onClick = { onMenuSet(if (menu == "spu") null else "spu") }
                        )
                        "profile" -> CircleButton(
                            icon = Icons.Default.Tune, selected = selCtrl == "profile", scale = bk,
                            onClick = { onMenuSet(if (menu == "profile") null else "profile") }
                        )
                        "lock" -> CircleButton(
                            icon = Icons.Default.Lock, selected = orientationLocked, scale = bk,
                            onClick = {
                                val locked = !orientationLocked
                                onOrientationLockedSet(locked)
                                onOrientationLockChange(locked)
                            }
                        )
                    }
                }
                val gap = Arrangement.spacedBy((8 * k).dp)
                if (isModernUi() && !isTvDevice) {
                    // Modern mode (phone): 3 main buttons + a strip with labels;
                    // the remaining functions are in the "More" slide-out panel (showMoreSheet).
                    ModernPhoneControls(
                        isPlaying = isPlaying,
                        timeshiftEngaged = timeshiftEngaged,
                        hasPrev = has("prev") && onPrevChannel != null,
                        hasNext = has("next") && onNextChannel != null,
                        hasList = has("list") && liveChannels.isNotEmpty(),
                        hasEpg = has("epg"),
                        onClose = onClose,
                        onAudio = { onMenuSet("audio") },
                        onList = { onShowChannelListSet(true); onControlsVisibleSet(false) },
                        onEpg = onOpenEpg,
                        onTogglePlay = onTogglePlay,
                        onPrev = onPrevChannel,
                        onNext = onNextChannel,
                        onSkipBack = onSkipBack,
                        onSkipFwd = onSkipFwd,
                        onMore = { onShowMoreSheetSet(true) },
                    )
                } else if (portrait) {
                    // PORTRAIT: buttons in several rows and larger (a single row was unusably small).
                    // Row 1: navigation/window, Row 2: playback (play in the middle), Row 3: audio/extras.
                    val rowGap = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(horizontalArrangement = rowGap, verticalAlignment = Alignment.CenterVertically) {
                            barCtrl("close")
                            if (has("pip")) barCtrl("pip")
                            if (has("list") && liveChannels.isNotEmpty()) barCtrl("list")
                            if (has("epg")) barCtrl("epg")
                            barCtrl("info")
                        }
                        Row(horizontalArrangement = rowGap, verticalAlignment = Alignment.CenterVertically) {
                            if (timeshiftEngaged) barCtrl("tsrew")
                            if (has("prev")) barCtrl("prev")
                            barCtrl("play")
                            if (has("next")) barCtrl("next")
                            if (timeshiftEngaged) barCtrl("tsff")
                        }
                        Row(horizontalArrangement = rowGap, verticalAlignment = Alignment.CenterVertically) {
                            barCtrl("audio")
                            barCtrl("subs")
                            if (has("profile")) barCtrl("profile")
                            if (has("rec")) barCtrl("rec")     // M490-fix: the three-row bar too
                            if (has("txt")) barCtrl("txt")     // M553
                            barCtrl("sleep")
                            if (lockVisible) barCtrl("lock")
                        }
                    }
                } else
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // left: close, list, EPG
                    Row(
                        horizontalArrangement = gap,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        barCtrl("close")
                        if (has("pip")) barCtrl("pip")
                        if (has("list") && liveChannels.isNotEmpty()) barCtrl("list")
                        if (has("epg")) barCtrl("epg")
                        if (has("txt")) barCtrl("txt")     // M554: left
                        barCtrl("info")                    // M554: left
                    }
                    // middle: switching + play/stop
                    Row(
                        horizontalArrangement = gap,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (timeshiftEngaged) barCtrl("tsrew")
                        if (has("prev")) barCtrl("prev")
                        barCtrl("play")
                        if (has("next")) barCtrl("next")
                        if (timeshiftEngaged) barCtrl("tsff")
                    }
                    // right: audio, subtitles, info, SW
                    Row(
                        horizontalArrangement = gap,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                        // align to the right edge
                    ) {
                        Spacer(Modifier.weight(1f))
                        barCtrl("audio")
                        barCtrl("subs")
                        if (has("profile")) barCtrl("profile")
                        if (has("rec")) barCtrl("rec")     // M490
                        barCtrl("sleep")
                        if (lockVisible) barCtrl("lock")
                    }
                }
            }
            }
        }
    }
}
