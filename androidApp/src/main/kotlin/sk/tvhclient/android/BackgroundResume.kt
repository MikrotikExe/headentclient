package sk.tvhclient.android

import android.app.Activity
import android.os.SystemClock
import org.videolan.libvlc.util.VLCVideoLayout
import sk.tvhclient.shared.Tvh

/**
 * M672: the player going to the background and coming back (the onStop / onStart bodies, split out of PlayerActivity).
 *
 * onStop: saves the DVR position, in PiP keeps playing, when finishing hands libVLC to a worker thread
 * (M535), radio with the "plays in background" option keeps playing / is handed to the service (M623/M624), otherwise pause
 * and detach the surface. onStart: return after standby on TV = a new player and retuning
 * (M540), parental lock = PIN again (M263), otherwise attachViews + play.
 */
internal class BackgroundResume(
    private val activity: Activity,
    private val engine: VlcEngine,
    private val live: LiveSession,
    private val hooks: Hooks
) {
    interface Hooks {
        fun seekable(): Boolean
        fun isTvDevice(): Boolean
        fun pinPromptShown(): Boolean
        fun inPip(): Boolean
        fun videoLayout(): VLCVideoLayout?
        fun saveDvrProgress()
        fun teardownPlayerAsync()
        fun radioBackground(): Boolean
        fun radioHandoffIfPossible(): Boolean
        fun recreatePlayer()
        fun replayCurrentLive()
        fun requestPin(onOk: () -> Unit, onCancel: () -> Unit, channelIndex: Int)
    }

    private var wasPlaying = false
    /** M540: when we last went to the background (elapsedRealtime). */
    private var stoppedAt = 0L

    /** Call from onStop BEFORE super.onStop(); returns true if the activity should call super immediately and finish (PiP). */
    fun onStopBeforeSuper(): Boolean {
        stoppedAt = SystemClock.elapsedRealtime()
        hooks.saveDvrProgress()
        // Let the PiP window keep playing ONLY if we really are in PiP (our own flag from the callback, not the live
        // isInPictureInPictureMode - that one often still reports true while PiP is closing) and the app is only going
        // to the background. If the activity is finishing, fall through and stop playback.
        if (android.os.Build.VERSION.SDK_INT >= 24 && hooks.inPip() && !activity.isFinishing) return true
        wasPlaying = engine.ready && engine.player.isPlaying
        return false
    }

    /** Call from onStop AFTER super.onStop() (when [onStopBeforeSuper] returned false). */
    fun onStopAfterSuper() {
        if (!engine.ready) return
        val mp = engine.player
        if (activity.isFinishing) {
            // M535: stop() MUST NOT be called on the main thread — see teardownPlayerAsync
            hooks.teardownPlayerAsync()
            return
        }
        // M623: "Radio plays in background" — screen off/lock/another app does not pause the radio.
        // HOME is handled by onUserLeaveHint (handoff + finish -> the branch above); what gets
        // here is the screen turning off and being covered by another activity. Modern
        // mode (M624: classic on a phone too): handoff to RadioPlayerService
        // (notification + mini bar; finish() -> onDestroy releases this player).
        // Where there is no handoff (TV), the player itself keeps playing — the wake/wifi lock
        // (M452) holds until onDestroy; we do not detach the surface, on return onStart only
        // does attachViews again (runCatching).
        // TV: no handoff — TvHomeHost has no mini bar, the radio would play with no controls;
        // the player stays playing on its own (e.g. covered by another app; after standby M540
        // onStart recreates the player and retunes).
        if (wasPlaying && hooks.radioBackground()) {
            if (hooks.isTvDevice() || !hooks.radioHandoffIfPossible()) {
                CrashLogger.report(activity, "PlayerActivity.radioBg", "keep playing in background (tv=${hooks.isTvDevice()})")
            }
            return
        }
        if (mp.isPlaying) {
            mp.pause()
        }
        // release the surface so it can be attached again after returning (otherwise a black screen)
        runCatching { mp.detachViews() }
    }

    /** Call from onStart after super.onStart(). */
    fun onStart() {
        // return from the background: attach the video to the surface again and resume playback
        if (!engine.ready) return
        val mp = engine.player
        val seekable = hooks.seekable()
        val curUuid = live.uuids.getOrNull(live.index)
        val locked = wasPlaying && !seekable && !hooks.pinPromptShown() &&
            ParentalLock.channelLockedProtected(activity, live.server?.id ?: Tvh.store.active()?.id, curUuid)
        // M540: return after standby (the screen went off while we were stopped) — on
        // Amlogic the old AudioTrack is dead after wake-up (M539). Instead of 5 s
        // waiting for the watchdog, go straight to a new player and retune; the PIN still applies.
        if (wasPlaying && !seekable && !engine.tornDown && hooks.isTvDevice() &&
            WakeTracker.screenWentOffSince(stoppedAt)
        ) {
            CrashLogger.report(activity, "PlayerActivity.wake", "resume after standby -> new player")
            hooks.recreatePlayer()
            if (locked) {
                ParentalLock.clearGrace(activity)
                hooks.requestPin(
                    onOk = { hooks.replayCurrentLive() },
                    onCancel = { activity.finish() },
                    channelIndex = live.index
                )
            } else {
                hooks.replayCurrentLive()
            }
            return
        }
        hooks.videoLayout()?.let { runCatching { mp.attachViews(it, null, false, false) } }
        // parental lock: if we are returning from the background to a locked LIVE channel,
        // ask for the PIN again (every return to the player = PIN, as at start).
        if (locked) {
            runCatching { if (mp.isPlaying) mp.pause() }
            ParentalLock.clearGrace(activity)   // M263: same as at start
            hooks.requestPin(
                onOk = { runCatching { engine.player.play() } },
                onCancel = { activity.finish() },
                channelIndex = live.index
            )
        } else if (wasPlaying) {
            runCatching { mp.play() }
        }
    }
}
