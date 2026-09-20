package sk.tvhclient.android

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateOf
import org.videolan.libvlc.MediaPlayer

/**
 * M650: handling of the libVLC MediaPlayer events (extracted from PlayerActivity.createPlayer).
 *
 * The order and meaning of the branches is identical to the original listener: error/end of a live stream ->
 * reconnect, in-progress recording -> reopen, finished recording -> back off after a seek (M594)
 * or end; Playing resets the attempts, starts AFR, the watchdog and the track refresh; ES* events
 * enforce the subtitle choice. It also holds [hasVideo] (radio = logo instead of video) and its
 * delayed check after Playing (videoTracksCount is only reliable after ~1.5 s).
 *
 * Everything that touches the stream / the feeders / the activity UI goes through [Actions] — it runs on
 * the libVLC thread just as before, the coroutine handling (lifecycleScope) is done by the activity.
 */
internal class VlcEvents(
    private val player: () -> MediaPlayer?,
    private val seekable: () -> Boolean,
    private val dvrRecording: () -> Boolean,
    private val htspStream: () -> Boolean,
    private val dvrProgStopSec: () -> Long,
    private val actions: Actions
) : MediaPlayer.EventListener {

    interface Actions {
        fun scheduleReconnect()
        fun cancelReconnect()
        fun resetDvrReopen()
        fun hideReconnecting()
        fun reopenDvrLive()
        /** M594: false = nothing to recover (the error is not a consequence of a seek). */
        fun recoverAfterSeek(): Boolean
        fun setPlaying(playing: Boolean)
        fun refreshPipIfActive()
        fun onPlayingForSeek()      // DvrSeek.onPlaying
        fun onPlayingForStall()     // StallWatchdog.onPlaying
        fun applyPendingSpuRestore()
        fun applyDesiredSpu()
        fun maybeApplyAfr()
        fun keepScreenOn(on: Boolean)
        fun hideSeekSpinner()
        fun scheduleTrackRefresh()
        fun maybeReparseForTracks()
        fun bumpTrackList()
        fun saveDvrProgress()
        fun onReachedEnd()
        fun showPlaybackError()
    }

    /** true = the stream has video; false = radio (PlayerUi shows the logo). */
    val hasVideo = mutableStateOf(true)
    private val videoCheckHandler = Handler(Looper.getMainLooper())

    override fun onEvent(event: MediaPlayer.Event) {
        when (event.type) {
            MediaPlayer.Event.EncounteredError -> {
                // live broadcast: try to reconnect (network dropout)
                if (!seekable()) {
                    actions.scheduleReconnect()
                } else if (dvrRecording()) {
                    // the DVR seek/feeder failed -> reopen the stream at the current playhead
                    // (reopenDvrLive has a backoff and clears the spinner once the attempts are exhausted),
                    // so that it does not stay stuck on "Opatovne pripajanie"
                    actions.reopenDvrLive()
                } else if (actions.recoverAfterSeek()) {
                    // M594: a finished recording — an error right after a seek means
                    // the target is past the end of the file; back off and retry instead of stopping
                } else {
                    actions.hideReconnecting()
                    actions.showPlaybackError()
                }
            }
            MediaPlayer.Event.Playing -> {
                actions.setPlaying(true); actions.refreshPipIfActive()
                actions.onPlayingForSeek()   // M594
                actions.onPlayingForStall()   // M539
                if (!htspStream()) actions.applyPendingSpuRestore()  // M392-fix2
                actions.maybeApplyAfr()  // AFR (M346): switch the display Hz according to the stream fps
                actions.keepScreenOn(true)  // during playback do not allow the screensaver/ambient mode on boxes
                actions.cancelReconnect()  // successful connection -> reset the attempts
                actions.resetDvrReopen()  // successful continuation -> reset the reopen attempts
                actions.hideSeekSpinner()  // the resync after the seek has completed
                // once it is up, find out whether the stream has video; if not -> radio (logo)
                videoCheckHandler.removeCallbacksAndMessages(null)
                videoCheckHandler.postDelayed({
                    val n = runCatching { player()?.videoTracksCount }.getOrNull()
                    if (n != null && n >= 0) hasVideo.value = n > 0
                }, 1500)
                // filling in the audio languages / DVB subtitles that libVLC only finishes parsing after start
                actions.scheduleTrackRefresh()
                actions.maybeReparseForTracks()
            }
            MediaPlayer.Event.Buffering -> {
                if (event.buffering >= 100f) actions.hideSeekSpinner()
            }
            MediaPlayer.Event.Paused -> { actions.setPlaying(false); actions.keepScreenOn(false); actions.refreshPipIfActive() }
            MediaPlayer.Event.Stopped -> { actions.setPlaying(false); actions.keepScreenOn(false); actions.refreshPipIfActive() }
            MediaPlayer.Event.Vout -> { if (event.voutCount > 0) hasVideo.value = true }
            MediaPlayer.Event.ESSelected -> {
                // M392-fix2: libVLC has just picked a track on its own (e.g. the default subtitles
                // in a matroska) — enforce the user's wish (off / a specific language)
                if (!htspStream()) actions.applyPendingSpuRestore()
            }
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted -> {
                // libVLC registers tracks as it goes (DVB subtitles / audio languages
                // only appear a few seconds after start) -> refresh the open track menu
                actions.bumpTrackList()
                // if the user picked a subtitle language that was not available yet,
                // set it as soon as its track appears (outside the libVLC callback)
                if (htspStream()) actions.applyDesiredSpu()
                // M392: HTTP live after a profile change — restore the original subtitle choice
                if (!htspStream()) actions.applyPendingSpuRestore()
            }
            MediaPlayer.Event.EndReached -> {
                actions.setPlaying(false)
                if (!seekable()) {
                    // a live stream "ended" = a dropout -> reconnect
                    actions.scheduleReconnect()
                } else if (dvrRecording() &&
                    (dvrProgStopSec() <= 0 || System.currentTimeMillis() / 1000 < dvrProgStopSec())) {
                    // an in-progress recording has reached the end of the written data -> reopen
                    // the stream (a new GET brings newer data), not the end of playback
                    actions.saveDvrProgress()
                    actions.reopenDvrLive()
                } else if (actions.recoverAfterSeek()) {
                    // M594: the seek hit the end of the file — back off and keep playing
                } else {
                    actions.onReachedEnd()
                    actions.keepScreenOn(false)
                    actions.saveDvrProgress()
                }
            }
        }
    }

    fun destroy() = videoCheckHandler.removeCallbacksAndMessages(null)
}
