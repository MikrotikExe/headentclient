package sk.tvhclient.android

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.MediaPlayer

/**
 * M539 / M649: hlídač zaseknutého zvukového výstupu (vyclenené z PlayerActivity).
 *
 * Na Strongu (Amlogic) po prebudení zo standby a krátko po boote AudioTrack neodoberá
 * dáta: kanál hrá bez zvuku a každé stop()/set_media by na hlavnom vlákne čakalo na audio
 * dekodér donekonečna (ANR). libVLC drží aout v input_resource a znovu ho používa, takže
 * prepnutie kanála na tom istom MediaPlayeri mŕtvy AudioTrack nevymení — jediná cesta je
 * nový MediaPlayer (nový aout).
 *
 * Každú sekundu: ak hrá (Playing už prišlo), demux číta (demuxReadBytes rastie), ale
 * playedAbuffers stojí (M539-fix: `time` nestačí, pri mŕtvom zvuku obraz beží podľa PCR),
 * počítame vzorky. >= GUARD → [outputStalled] a každé ďalšie médium ide cez nový prehrávač;
 * >= RECREATE → [onRecreate] (nový prehrávač + znovunaladenie), max [MAX_RECREATES] za sebou,
 * počítadlo sa nuluje, keď zvuk začne bežať.
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
    /** Počet automatických obnov za sebou (číta createPlayer: od 2. skúsi OpenSL ES). */
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

    /** Nové médium / nový prehrávač = nové počítanie; Playing musí prísť znova. */
    fun reset() { lastAudio = -1; lastDemux = -1; samples = 0; playingSeen = false }

    /** Event.Playing: od teraz má zmysel merať; vzorky vynuluj. */
    fun onPlaying() { playingSeen = true; samples = 0 }

    /** Výstup nereaguje — stop() by zablokoval hlavné vlákno; nové médium má ísť cez nový prehrávač. */
    fun outputStalled(): Boolean = playingSeen && samples >= GUARD

    fun destroy() = handler.removeCallbacksAndMessages(null)

    private companion object {
        const val GUARD = 3
        const val RECREATE = 5
        const val MAX_RECREATES = 4
    }
}
