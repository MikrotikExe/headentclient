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
 * M656: životný cyklus libVLC + MediaPlayer (vyclenené z PlayerActivity): vytvorenie
 * s voľbami a zvukovým výstupom, výmena za nový (M539 zaseknutý AudioTrack), odložené
 * play() po pripojení nového surface (M539-fix2) a asynchrónne ukončenie na pracovnom
 * vlákne (M535/M622 — stop() na hlavnom vlákne visel na mŕtvom audio výstupe → ANR).
 *
 * Aktivita k prehrávaču pristupuje cez [player]/[libVlc] (delegáty pod pôvodnými názvami),
 * [ready] nahrádza `::mediaPlayer.isInitialized`. Event listener a hlídač zaseknutia
 * ostávajú v aktivite a chodia sem cez konštruktor / lambdy.
 */
internal class VlcEngine(
    private val ctx: Context,
    private val media: MediaFactory,
    private val events: MediaPlayer.EventListener,
    /** Počet doterajších výmen prehrávača (StallWatchdog.recreates) — M539-fix opensles fallback. */
    private val recreates: () -> Int,
    /** M539-fix2: nový SurfaceView pre nový prehrávač (PlayerActivity.videoSurfaceGen++). */
    private val bumpSurfaceGen: () -> Unit,
    /** Po (znovu)vytvorení a pred každým novým médiom: StallWatchdog.reset(). */
    private val resetStall: () -> Unit
) {
    lateinit var libVlc: LibVLC
        private set
    lateinit var player: MediaPlayer
        private set
    val ready: Boolean get() = ::player.isInitialized

    /** M535: teardown už prebehol — na prehrávač sa už nesiaha. */
    var tornDown = false
        private set
    /** M539-fix2: po výmene prehrávača ešte nie je pripojený nový surface — play() sa
     *  odloží do onAttach (prehrávanie bez okna by nemalo video). Inak hneď. */
    var awaitingSurface = false
        private set
    private var pendingPlayAfterAttach = false
    private val destroyedLatch = CountDownLatch(1)

    /** Prehrávač, ak existuje a nebol ukončený (pre controllery). */
    fun live(): MediaPlayer? = if (!tornDown && ready) player else null

    /** Vytvori LibVLC + MediaPlayer, nastavi zvukovy vystup a event listener.
     *  Volane z onCreate a z [recreate]. */
    fun create() {
        val options = arrayListOf(
            "--network-caching=" + BufferPref.ms(ctx),
            if (VlcVerbosePref.get(ctx)) "-vv" else "--quiet",  // M448
            // M539-fix: statistiky ZAPNUTE — hlidac zaseknuteho zvuku cita
            // playedAbuffers/demuxReadBytes (s --no-stats su vzdy 0)
            "--http-user-agent=" + media.userAgent()
        )
        // Deinterlacing (globalne, nech plati uz na prvom otvoreni; per-medium
        // sa nastavi znova pri kazdom prepnuti kanala)
        val (dEn, dMode) = media.deinterlaceSpec()
        options.add("--deinterlace=$dEn")
        if (dMode != null) options.add("--deinterlace-mode=$dMode")
        libVlc = LibVLC(ctx, options)
        player = MediaPlayer(libVlc)
        // Zvukovy vystup z nastaveni. Modul (telefon: AudioTrack/OpenSL ES) aj
        // zariadenie (TV: passthrough/pcm/stereo) sa musia nastavit pred prehravanim;
        // menia sa az pri (znovu)otvoreni prehravaca.
        AudioModulePref.module(ctx)?.let { aout -> runCatching { player.setAudioOutput(aout) } }
        // M539-fix: ak ani druhy novy AudioTrack po prebudeni nehra, skus OpenSL ES
        // (ina cesta do audio HAL); plati len pre tuto instanciu prehravaca.
        val n = recreates()
        if (n >= 2 && AudioModulePref.module(ctx) == null) {
            runCatching { player.setAudioOutput("opensles") }
            CrashLogger.report(ctx, "PlayerActivity.stall", "new player #$n uses opensles")
        }
        AudioOutputPref.deviceId(ctx)?.let { dev -> runCatching { player.setAudioOutputDevice(dev) } }

        player.setEventListener(events)   // M650: VlcEvents.kt
    }

    /** Vymeni libVLC + MediaPlayer za nove; stare uvolni na pracovnom vlakne. */
    fun recreate() {
        if (ready) {
            val oldMp = player
            val oldLib = libVlc
            runCatching { oldMp.setEventListener(null) }
            // M539-fix3: surface odpojit od stareho prehravaca TU, este PRED jeho stop().
            // detachViews caka, kym stary vout surface pusti — kym vstupne vlakno zije,
            // je to okamzite (overene v M539-fix). Ak by uz bezal stop(), vstupne vlakno
            // visi na audio dekoderi, vout uz surface nikdy nepusti a cakanie (aj to,
            // ktore robi Compose pri odstraneni SurfaceView) by zablokovalo hlavne
            // vlakno — presne to sa stalo v M539-fix2 (zamrznuty snimok, po minute ANR).
            runCatching { oldMp.detachViews() }
            val appCtx = ctx.applicationContext
            val worker = Thread({
                val t0 = SystemClock.elapsedRealtime()
                runCatching { oldMp.stop() }
                // M539-fix4: release() az o chvilu — stara kompozicia sa este moze
                // rozkladat a jej korutiny sa stareho objektu dotknut
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
        // M539-fix2: novy SurfaceView pre novy prehravac; play() az po jeho pripojeni
        awaitingSurface = true
        pendingPlayAfterAttach = false
        bumpSurfaceGen()
    }

    /** Spusti prehrávanie nového média (alebo ho odloží, kým sa pripojí nový surface). */
    fun startPlayback() {
        // M539-fix4: nove medium = nove pocitanie; Playing musi prist znova, inak by
        // bezny start kanala (demux uz cita, zvuk este nie) vyzeral ako zaseknutie
        resetStall()
        if (awaitingSurface) { pendingPlayAfterAttach = true; return }
        player.play()
    }

    /** onAttach nového surface (VideoSurface): ak čakal play(), spusti ho. Vráti true, ak áno. */
    fun onSurfaceAttached(): Boolean {
        awaitingSurface = false
        if (!pendingPlayAfterAttach) return false
        pendingPlayAfterAttach = false
        return true
    }

    /**
     * M535: ukoncenie libVLC mimo hlavneho vlakna.
     *
     * `MediaPlayer.stop()` je synchronne: caka, kym skonci vstupne vlakno libVLC,
     * a to zas caka na dekodery. Na Strongu (Amlogic) po prebudeni zo standby
     * a krátko po boote AudioTrack neodobera data — audio dekoder visi v zapise
     * do neho a neda sa prerusit, takze stop() na hlavnom vlakne nikdy neskoncil:
     * po 5 s ANR, systemove „Activity destroy timeout" a appku zabil system
     * (bugreport 1. 9. 2026, pat identickych stackov). Zavretie prehravaca preto
     * odovzda cely libVLC objekt pracovnemu vlaknu; aktivita sa zavrie hned.
     * Ak libVLC visi, visi len to vlakno na pozadi a zapise sa WARN do
     * diagnostickeho logu. [stopFeeders] sa zavola ako prve — zavretie pipe ukonci
     * demux okamzite, takze v beznom pripade stop() trva par desiatok ms.
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
            // release() az po onDestroy — dovtedy sa na (uz zastaveny) prehravac
            // mozu este obratit UI slucky/handlery a volanie na uvolneny objekt
            // by hodilo IllegalStateException.
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

    /** onDestroy prebehol — pracovné vlákno smie release(). */
    fun allowRelease() { destroyedLatch.countDown() }

    companion object {
        /**
         * M622: bezpecne uvolnenie libVLC z pracovneho vlakna.
         *
         * Pad z Play (1.0.6, armeabi-v7a): SIGABRT vo vlc_mutex_destroy, volane z
         * libvlc_media_player_release -> MediaPlayer.nativeRelease -> nase uvolnovacie
         * vlakno. vlc_mutex_destroy spadne na assert, ked sa rusi mutex, ktory este
         * niekto drzi — teda prehravac sa uvolnoval skor, nez dobehlo jeho vstupne
         * vlakno. Na pomalsich 32-bitovych boxoch to stop() nestihne za pevnych 500 ms.
         *
         * Preto sa pred release() POCKA, kym prehravac naozaj prestane hrat (najviac
         * 2 s, vzorka po 50 ms), potom kratka pauza na dobehnutie vnutornych vlakien,
         * a az potom release. LibVLC sa uvolni este o kusok neskor — nikdy pred
         * prehravacom, ktory z neho vznikol. Vsetko na pracovnom vlakne, hlavne vlakno
         * sa necaka.
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
            // dobehnutie vnutornych vlakien libVLC (vout/audio) pred zrusenim mutexov
            runCatching { Thread.sleep(150) }
            runCatching { mp.release() }
            runCatching { Thread.sleep(100) }
            runCatching { lib?.release() }
            val ms = SystemClock.elapsedRealtime() - t0
            if (ms > 3_000L) CrashLogger.report(ctx, "PlayerActivity.$where", "release took $ms ms")
        }
    }
}
