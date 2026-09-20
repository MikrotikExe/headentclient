package sk.tvhclient.android

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import org.videolan.libvlc.MediaPlayer

/**
 * M662: the one-second DVR position ticker extracted from PlayerUi (the JVM 64 KB method limit).
 * The body of the LaunchedEffect is 1:1 from PlayerUi; PlayerUi state is read through getters and written
 * through setters, so that the loop sees its own writes within a single tick.
 */
@Composable
internal fun DvrPositionTicker(
    player: MediaPlayer,
    ctx: Context,
    lengthMsLive: State<Long>,
    offsetMsLive: State<Long>,
    seekSeedLive: State<Long>,
    onSeekSeedHandledLive: State<() -> Unit>,
    recordingLive: Boolean,
    liveMarginMs: Long,
    dvrUuid: String?,
    serverId: String?,
    posTimeMs: () -> Long,
    onPosTimeMsSet: (Long) -> Unit,
    posFraction: () -> Float,
    onPosFractionSet: (Float) -> Unit,
    initialSeekDone: () -> Boolean,
    onInitialSeekDoneSet: (Boolean) -> Unit,
    rebuiltBySeek: () -> Boolean,
    onRebuiltBySeekSet: (Boolean) -> Unit,
    pendingResumeMs: () -> Long,
    onPendingResumeMsSet: (Long) -> Unit,
    lastPlayTickMs: () -> Long,
    onLastPlayTickMsSet: (Long) -> Unit,
    askResume: () -> Boolean,
    dragging: () -> Boolean,
    onSeekToMs: (Long) -> Unit,
    onPlayheadMs: (Long) -> Unit,
) {
    LaunchedEffect(Unit) {
        var sinceSave = 0
        while (true) {
            val nowMs = System.currentTimeMillis()
            val curLen = lengthMsLive.value
            val curOff = offsetMsLive.value
            // the reachable range (without the margin) - neither the playhead nor a reopen goes into it
            val curBar = if (recordingLive) (curLen - liveMarginMs).coerceAtLeast(1L) else curLen
            // After a seek (seekDvrTo): adopt the target time as the playhead. With feeder/pipe
            // player.position is invalid after a restart, so the subsequent resync must not read it -
            // the seed gives the clock the right point and it ticks on from there (initialSeekDone=true also
            // silences the one-off jump to the start of the programme).
            val seed = seekSeedLive.value          // M495-fix: a fresh value
            // M495-fix2: wait for a known length. When the player does not know it yet
            // (curLen == 0 — slow media loading or a failed attempt),
            // coerceIn(0, curBar) would clamp the target to ZERO and the seed would also
            // be consumed — the clock would tick from the start. We let the seed wait
            // for the next tick; until then nobody else overwrites it.
            if (seed >= 0L && curLen > 0) {
                onPosTimeMsSet(seed.coerceIn(0L, curBar))
                val denom = (curOff + curLen).coerceAtLeast(1L)
                onPosFractionSet(((curOff + posTimeMs()).toFloat() / denom).coerceIn(0f, 1f))
                onInitialSeekDoneSet(true)
                onRebuiltBySeekSet(true)      // M495
                onSeekSeedHandledLive.value()
            }
            // restore after confirmation (takes precedence over the jump to the start of the programme).
            // CAUTION: a direct player.position does not work with pipe/feeder DVR (and
            // isSeekable tends to be false, so the jump was never performed and the recording
            // played from the beginning) — use the same path as a user
            // seek (onSeekToMs -> seekDvrAbsolute: restart with :start-time,
            // the playhead clock adopts the seed).
            if (pendingResumeMs() > 0 && curLen > 0) {
                val tgt = pendingResumeMs().coerceIn(0L, curBar)
                onSeekToMs(tgt)
                onPosTimeMsSet(tgt)
                onPosFractionSet(((curOff + tgt).toFloat() / (curOff + curLen).coerceAtLeast(1L)).coerceIn(0f, 1f))
                onPendingResumeMsSet(0)
                onInitialSeekDoneSet(true)
            }
            // A one-off jump to the start of the programme within the file (a recording in progress with
            // pre-programme content). We seek by POSITION (a fraction), not setTime - on
            // a growing TS that is more reliable. Fraction = offset / (offset + elapsed time of
            // the programme) = the location of the programme start in the current buffer.
            if (!initialSeekDone() && recordingLive && curOff > 0 &&
                !askResume() && pendingResumeMs() == 0L && curLen > 0 && player.isSeekable) {
                val f = (curOff.toFloat() / (curOff + curLen)).coerceIn(0f, 1f)
                player.position = f
                onPosFractionSet(f)
                onPosTimeMsSet(0L)
                onInitialSeekDoneSet(true)
            }
            if (!dragging()) {
                val p = player.position
                if (p in 0f..1f) {
                    // A position jump = a seek occurred (slider/D-pad/double-click) -> align
                    // the playback clock with the real position. We map file->programme time:
                    // (p * (offset + length)) - offset. During normal playback p changes
                    // smoothly (<<5%), so this does not trigger and the clock ticks from the wall clock.
                    // p > 0.02: ignore a spurious zero position reading (common on a growing TS
                    // and right after a reopen), so that the clock does not jump to 0.
                    // M495: only while the media is the original one. After a seek (rebuilt
                    // with :start-time) this recalculation would reset the clock almost to
                    // zero and it would tick from the wrong point — that is exactly what caused
                    // the next seek to start from a long-since played position.
                    if (!rebuiltBySeek() && initialSeekDone() && curLen > 0 && p > 0.02f &&
                        player.isSeekable && kotlin.math.abs(p - posFraction()) > 0.05f) {
                        onPosTimeMsSet((p * (curOff + curLen) - curOff).toLong()
                            .coerceIn(0L, curBar))
                    }
                    if (!rebuiltBySeek()) onPosFractionSet(p)
                }
                // The playback clock: while playing, add the real elapsed time.
                if (lastPlayTickMs() > 0L && player.isPlaying) {
                    val d = (nowMs - lastPlayTickMs()).coerceIn(0L, 3000L)
                    onPosTimeMsSet((posTimeMs() + d).coerceIn(0L, curBar))
                }
                // M495: after the media is rebuilt the fraction from player.position is invalid,
                // so the position on the bar must be derived from the clock (otherwise the indicator
                // would jump to the start and would not match the time).
                if (rebuiltBySeek()) {
                    val denom = (curOff + curLen).coerceAtLeast(1L)
                    onPosFractionSet(((curOff + posTimeMs()).toFloat() / denom).coerceIn(0f, 1f))
                }
                // Mirror the playhead into the Activity — it is needed both by the reopen
                // of the in-progress stream AND by seeking (seekRelative/double-click take
                // their starting position from it).
                // M492: this used to be `if (recordingLive)`, so for a FINISHED recording
                // from the archive the playhead in the Activity froze at the value of the last seek
                // and every further seek went from it, not from where playback actually was.
                onPlayheadMs(posTimeMs())
            }
            onLastPlayTickMsSet(nowMs)
            // The end of available data of an in-progress recording (EOF) is handled by reopenDvrLive (it reopens
            // the stream and continues into newer data). The seek clamp (45 s) keeps the playhead
            // safely behind the live edge, so during normal playback EOF is not hit.
            // save the position continuously (every ~5s) - from the playback clock (reliable)
            sinceSave++
            if (sinceSave >= 5 && !askResume()) {
                sinceSave = 0
                if (posTimeMs() > 1000L && curLen > 0 && dvrUuid != null && serverId != null) {
                    WatchProgress.save(ctx, serverId, dvrUuid, posTimeMs().coerceAtMost(curLen), curLen)
                }
            }
            kotlinx.coroutines.delay(1000)
        }
    }
}
