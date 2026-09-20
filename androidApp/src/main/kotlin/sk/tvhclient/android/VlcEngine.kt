package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * M656: libVLC + MediaPlayer lifecycle (extracted from PlayerActivity): creation
 * with the options and the audio output, replacement by a new one (M539 stuck AudioTrack), a deferred
 * play() once the new surface is attached (M539-fix2) and asynchronous shutdown on a worker
 * thread (M535/M622 — stop() on the main thread hung on a dead audio output → ANR).
 *
 * The activity accesses the player via [player]/[libVlc] (delegates under the original names),
 * [ready] replaces `::mediaPlayer.isInitialized`. The event listener and the stall watchdog
 * stay in the activity and come in here via the constructor / lambdas.
 */
internal class VlcEngine(
    private val ctx: Context,
    private val media: MediaFactory,
    private val events: MediaPlayer.EventListener,
    /** Number of player replacements so far (StallWatchdog.recreates) — M539-fix opensles fallback. */
    private val recreates: () -> Int,
    /** M539-fix2: a new SurfaceView for the new player (PlayerActivity.videoSurfaceGen++). */
    private val bumpSurfaceGen: () -> Unit,
    /** After (re)creation and before every new medium: StallWatchdog.reset(). */
    private val resetStall: () -> Unit
) {
    lateinit var libVlc: LibVLC
        private set
    lateinit var player: MediaPlayer
        private set
    val ready: Boolean get() = ::player.isInitialized

    /** M535: the teardown has already run — the player must not be touched any more. */
    var tornDown = false
        private set
    /** M539-fix2: after a player replacement the new surface is not attached yet — play() is
     *  deferred to onAttach (playback without a window would have no video). Otherwise immediately. */
    var awaitingSurface = false
        private set
    private var pendingPlayAfterAttach = false
    private val destroyedLatch = CountDownLatch(1)

    /** The player, if it exists and has not been shut down (for the controllers). */
    fun live(): MediaPlayer? = if (!tornDown && ready) player else null

    /** Creates LibVLC + MediaPlayer, sets the audio output and the event listener.
     *  Called from onCreate and from [recreate]. */
    fun create() {
        val options = arrayListOf(
            "--network-caching=" + BufferPref.ms(ctx),
            if (VlcVerbosePref.get(ctx)) "-vv" else "--quiet",  // M448
            // M539-fix: statistics ON — the stuck-audio watchdog reads
            // playedAbuffers/demuxReadBytes (with --no-stats they are always 0)
            "--http-user-agent=" + media.userAgent()
        )
        // Deinterlacing (global, so that it applies already on the first open; per-medium
        // it is set again on every channel switch)
        val (dEn, dMode) = media.deinterlaceSpec()
        options.add("--deinterlace=$dEn")
        if (dMode != null) options.add("--deinterlace-mode=$dMode")
        libVlc = LibVLC(ctx, options)
        player = MediaPlayer(libVlc)
        // Audio output from the settings. Both the module (phone: AudioTrack/OpenSL ES) and
        // the device (TV: passthrough/pcm/stereo) must be set before playback;
        // they only change when the player is (re)opened.
        AudioModulePref.module(ctx)?.let { aout -> runCatching { player.setAudioOutput(aout) } }
        // M539-fix: if even the second new AudioTrack does not play after a wake-up, try OpenSL ES
        // (a different path into the audio HAL); applies only to this player instance.
        val n = recreates()
        if (n >= 2 && AudioModulePref.module(ctx) == null) {
            runCatching { player.setAudioOutput("opensles") }
            CrashLogger.report(ctx, "PlayerActivity.stall", "new player #$n uses opensles")
        }
        AudioOutputPref.deviceId(ctx)?.let { dev -> runCatching { player.setAudioOutputDevice(dev) } }

        player.setEventListener(events)   // M650: VlcEvents.kt
    }

    /** Replaces libVLC + MediaPlayer with new ones; releases the old ones on a worker thread. */
    fun recreate() {
        if (ready) {
            val oldMp = player
            val oldLib = libVlc
            runCatching { oldMp.setEventListener(null) }
            // M539-fix3: detach the surface from the old player HERE, still BEFORE its stop().
            // detachViews waits until the old vout releases the surface — while the input thread is alive,
            // that is immediate (verified in M539-fix). If stop() were already running, the input thread
            // hangs on the audio decoder, the vout never releases the surface and the wait (including the one
            // Compose does when removing the SurfaceView) would block the main
            // thread — exactly what happened in M539-fix2 (frozen frame, ANR after a minute).
            runCatching { oldMp.detachViews() }
            val appCtx = ctx.applicationContext
            val worker = Thread({
                val t0 = SystemClock.elapsedRealtime()
                runCatching { oldMp.stop() }
                // M539-fix4: release() only after a while — the old composition may still be
                // tearing down and its coroutines may still touch the old object
                runCatching { Thread.sleep(500) }
                releaseVlc(appCtx, oldMp, oldLib, "recreate")   // M622
                val ms = SystemClock.elapsedRealtime() - t0
                if (ms > 3500) CrashLogger.report(appCtx, "PlayerActivity.recreate", "old libVLC released after $ms ms")
            }, "HeadentClient:vlcRelease")
            worker.isDaemon = true
            worker.start()
        }
        create()
        resetStall()
        // M539-fix2: a new SurfaceView for the new player; play() only once it is attached
        awaitingSurface = true
        pendingPlayAfterAttach = false
        bumpSurfaceGen()
    }

    /** Starts playback of the new medium (or defers it until the new surface is attached). */
    fun startPlayback() {
        // M539-fix4: a new medium = new counting; Playing must arrive again, otherwise
        // an ordinary channel start (the demux is already reading, the audio not yet) would look like a stall
        resetStall()
        if (awaitingSurface) { pendingPlayAfterAttach = true; return }
        player.play()
    }

    /** onAttach of the new surface (VideoSurface): if a play() was waiting, start it. Returns true if so. */
    fun onSurfaceAttached(): Boolean {
        awaitingSurface = false
        if (!pendingPlayAfterAttach) return false
        pendingPlayAfterAttach = false
        return true
    }

    /**
     * M535: shutting libVLC down off the main thread.
     *
     * `MediaPlayer.stop()` is synchronous: it waits for the libVLC input thread to finish,
     * and that in turn waits for the decoders. On the Strong (Amlogic) after a wake-up from standby
     * and shortly after boot AudioTrack does not consume data — the audio decoder hangs in a write
     * into it and cannot be interrupted, so stop() on the main thread never finished:
     * after 5 s an ANR, the system "Activity destroy timeout" and the system killed the app
     * (bugreport 1 September 2026, five identical stacks). Closing the player therefore
     * hands the whole libVLC object over to a worker thread; the activity closes immediately.
     * If libVLC hangs, only that background thread hangs and a WARN is written to the
     * diagnostic log. [stopFeeders] is called first — closing the pipe terminates the
     * demux immediately, so in the normal case stop() takes a few tens of ms.
     */
    fun teardownAsync(stopFeeders: () -> Unit) {
        if (tornDown) return
        tornDown = true
        stopFeeders()
        if (!ready) {
            if (::libVlc.isInitialized) runCatching { libVlc.release() }
            return
        }
        val mp = player
        val lib = if (::libVlc.isInitialized) libVlc else null
        val appCtx = ctx.applicationContext
        runCatching { mp.setEventListener(null) }
        val destroyed = destroyedLatch
        val worker = Thread({
            val t0 = SystemClock.elapsedRealtime()
            runCatching { mp.stop() }
            // release() only after onDestroy — until then UI loops/handlers may still turn
            // to the (already stopped) player, and a call on a released object
            // would throw IllegalStateException.
            runCatching { destroyed.await(5, TimeUnit.SECONDS) }
            runCatching { mp.detachViews() }
            releaseVlc(appCtx, mp, lib, "teardown")   // M622
            val ms = SystemClock.elapsedRealtime() - t0
            if (ms > 3000) {
                CrashLogger.report(appCtx, "PlayerActivity.teardown", "libVLC stop/release took $ms ms")
            }
        }, "HeadentClient:vlcRelease")
        worker.isDaemon = true
        worker.start()
        Handler(Looper.getMainLooper()).postDelayed({
            if (worker.isAlive) {
                CrashLogger.report(
                    appCtx, "PlayerActivity.teardown",
                    "libVLC stop hangs >8 s (audio output stalled after standby/boot?) — left in background"
                )
            }
        }, 8_000)
    }

    /** onDestroy has run — the worker thread may release(). */
    fun allowRelease() { destroyedLatch.countDown() }

    companion object {
        /**
         * M622: safe release of libVLC from the worker thread.
         *
         * Crash from Play (1.0.6, armeabi-v7a): SIGABRT in vlc_mutex_destroy, called from
         * libvlc_media_player_release -> MediaPlayer.nativeRelease -> our releasing
         * thread. vlc_mutex_destroy fails on an assert when a mutex is destroyed that someone
         * still holds — that is, the player was being released before its input
         * thread had finished. On slower 32-bit boxes stop() does not manage that within the fixed 500 ms.
         *
         * Therefore before release() we WAIT until the player really stops playing (at most
         * 2 s, sampled every 50 ms), then a short pause for the internal threads to finish,
         * and only then release. LibVLC is released a little later still — never before
         * the player that was created from it. All on the worker thread, the main thread
         * does not wait.
         */
        fun releaseVlc(ctx: Context, mp: MediaPlayer, lib: LibVLC?, where: String) {
            val t0 = SystemClock.elapsedRealtime()
            var waited = 0L
            while (waited < 2_000L) {
                val playing = runCatching { mp.isPlaying }.getOrDefault(false)
                if (!playing) break
                runCatching { Thread.sleep(50) }
                waited += 50
            }
            if (waited >= 2_000L) {
                CrashLogger.report(ctx, "PlayerActivity.$where", "player still playing 2 s after stop()")
            }
            // letting the internal libVLC threads (vout/audio) finish before the mutexes are destroyed
            runCatching { Thread.sleep(150) }
            runCatching { mp.release() }
            runCatching { Thread.sleep(100) }
            runCatching { lib?.release() }
            val ms = SystemClock.elapsedRealtime() - t0
            if (ms > 3_000L) CrashLogger.report(ctx, "PlayerActivity.$where", "release took $ms ms")
        }
    }
}
