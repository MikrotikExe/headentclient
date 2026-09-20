package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.MediaPlayer

/**
 * M539 / M649: watchdog for a stalled audio output (extracted from PlayerActivity).
 *
 * On the Strong (Amlogic), after waking from standby and shortly after boot, AudioTrack does not take
 * data: the channel plays with no sound and every stop()/set_media would wait on the main thread for the audio
 * decoder forever (ANR). libVLC keeps the aout in input_resource and reuses it, so
 * switching the channel on the same MediaPlayer does not replace the dead AudioTrack — the only way out is
 * a new MediaPlayer (a new aout).
 *
 * Every second: if it is playing (Playing has already arrived), the demux is reading (demuxReadBytes is growing), but
 * playedAbuffers is stuck (M539-fix: `time` is not enough, with dead audio the picture runs off the PCR),
 * we count samples. >= GUARD → [outputStalled] and every further medium goes through a new player;
 * >= RECREATE → [onRecreate] (a new player + retune), at most [MAX_RECREATES] in a row,
 * the counter is reset once the audio starts running.
 */
internal class StallWatchdog(
    private val ctx: Context,
    private val player: () -> MediaPlayer?,
    private val recreateAllowed: () -> Boolean,
    private val onRecreate: () -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var lastAudio = -1
    private var lastDemux = -1
    private var samples = 0
    private var playingSeen = false
    /** Number of automatic recreations in a row (read by createPlayer: from the 2nd it tries OpenSL ES). */
    var recreates = 0
        private set

    private val tick = object : Runnable {
        override fun run() {
            val mp = player() ?: return
            val playing = runCatching { mp.isPlaying }.getOrDefault(false)
            var audio = -1; var demux = -1
            val m = runCatching { mp.media }.getOrNull()
            if (m != null) {
                runCatching { m.stats }.getOrNull()?.let { st -> audio = st.playedAbuffers; demux = st.demuxReadBytes }
                runCatching { m.release() }
            }
            val hasAudioTrack = runCatching { mp.audioTrack }.getOrDefault(-1) != -1
            val demuxAlive = demux >= 0 && demux != lastDemux
            val audioStuck = audio >= 0 && audio == lastAudio
            if (playing && playingSeen && hasAudioTrack && demuxAlive && audioStuck) {
                samples++
            } else {
                samples = 0
                if (audio > 0 && audio != lastAudio) recreates = 0
            }
            lastAudio = audio
            lastDemux = demux
            if (samples >= RECREATE && recreateAllowed() && recreates < MAX_RECREATES) {
                recreates++
                samples = 0
                CrashLogger.report(ctx, "PlayerActivity.stall",
                    "audio output stalled ${RECREATE}s (playedAbuffers=$audio, demux=$demux) -> new player #$recreates")
                onRecreate()
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun start() {
        handler.removeCallbacksAndMessages(null)
        handler.postDelayed(tick, 1000)
    }

    /** New medium / new player = new counting; Playing has to arrive again. */
    fun reset() { lastAudio = -1; lastDemux = -1; samples = 0; playingSeen = false }

    /** Event.Playing: from now on measuring makes sense; reset the samples. */
    fun onPlaying() { playingSeen = true; samples = 0 }

    /** The output is not responding — stop() would block the main thread; a new medium has to go through a new player. */
    fun outputStalled(): Boolean = playingSeen && samples >= GUARD

    fun destroy() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val GUARD = 3
        const val RECREATE = 5
        const val MAX_RECREATES = 4
    }
}
