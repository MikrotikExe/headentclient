package sk.tvhclient.android

import android.app.Activity
import android.os.SystemClock
import org.videolan.libvlc.util.VLCVideoLayout
import sk.tvhclient.shared.Tvh

/**
 * M672: odchod prehrávača na pozadie a návrat (telá onStop / onStart, vyclenené z PlayerActivity).
 *
 * onStop: uloží pozíciu DVR, v PiP nechá hrať, pri ukončovaní odovzdá libVLC pracovnému vláknu
 * (M535), rádio s voľbou „hrá na pozadí" nechá hrať / odovzdá službe (M623/M624), inak pauza
 * a odpojenie surface. onStart: návrat po standby na TV = nový prehrávač a znovunaladenie
 * (M540), rodičovský zámok = PIN znova (M263), inak attachViews + play.
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
    /** M540: kedy sme naposledy isli do pozadia (elapsedRealtime). */
    private var stoppedAt = 0L

    /** Volať z onStop PRED super.onStop(); vráti true, ak má aktivita hned zavolať super a skončiť (PiP). */
    fun onStopBeforeSuper(): Boolean {
        stoppedAt = SystemClock.elapsedRealtime()
        hooks.saveDvrProgress()
        // PiP okno nechaj hrat LEN ak sme realne v PiP (vlastny priznak z callbacku, nie zivy
        // isInPictureInPictureMode - ten pri zatvarani PiP casto este hlasi true) a appka len ide
        // na pozadie. Ak sa aktivita ukoncuje, prepadni dole a zastav prehravanie.
        if (android.os.Build.VERSION.SDK_INT >= 24 && hooks.inPip() && !activity.isFinishing) return true
        wasPlaying = engine.ready && engine.player.isPlaying
        return false
    }

    /** Volať z onStop PO super.onStop() (keď [onStopBeforeSuper] vrátil false). */
    fun onStopAfterSuper() {
        if (!engine.ready) return
        val mp = engine.player
        if (activity.isFinishing) {
            // M535: stop() sa NESMIE volat na hlavnom vlakne — pozri teardownPlayerAsync
            hooks.teardownPlayerAsync()
            return
        }
        // M623: "Radio hra na pozadi" — zhasnutie/zamok/ina appka radio nepozastavi.
        // HOME riesi onUserLeaveHint (handoff + finish -> vetva vyssie); sem sa
        // dostane zhasnutie obrazovky a prekrytie inou aktivitou. Moderny
        // rezim (M624: aj klasik na telefone): handoff do RadioPlayerService
        // (notifikacia + mini lista; finish() -> onDestroy uvolni tento prehravac).
        // Kde handoff nie je (TV), ostane hrat samotny prehravac — wake/wifi lock
        // (M452) drzi az do onDestroy; surface neodpajame, po navrate onStart len
        // znova attachViews (runCatching).
        // TV: ziadny handoff — TvHomeHost nema mini listu, radio by hralo bez ovladania;
        // prehravac ostane hrat sam (napr. prekryty inou appkou; po standby M540
        // v onStart prehravac obnovi a naladi znova).
        if (wasPlaying && hooks.radioBackground()) {
            if (hooks.isTvDevice() || !hooks.radioHandoffIfPossible()) {
                CrashLogger.report(activity, "PlayerActivity.radioBg", "keep playing in background (tv=${hooks.isTvDevice()})")
            }
            return
        }
        if (mp.isPlaying) {
            mp.pause()
        }
        // uvolni surface, nech sa po navrate da znova pripojit (inak cierna obrazovka)
        runCatching { mp.detachViews() }
    }

    /** Volať z onStart po super.onStart(). */
    fun onStart() {
        // navrat z pozadia: znova pripoj video na surface a obnov prehravanie
        if (!engine.ready) return
        val mp = engine.player
        val seekable = hooks.seekable()
        val curUuid = live.uuids.getOrNull(live.index)
        val locked = wasPlaying && !seekable && !hooks.pinPromptShown() &&
            ParentalLock.channelLockedProtected(activity, live.server?.id ?: Tvh.store.active()?.id, curUuid)
        // M540: navrat po standby (obrazovka zhasla, kym sme boli zastaveni) — na
        // Amlogicu je stary AudioTrack po prebudeni mrtvy (M539). Namiesto 5 s
        // cakania na hlidac rovno novy prehravac a znovunaladenie; PIN plati dalej.
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
        // rodicovsky zamok: ak sa vraciame z pozadia na zamknuty ZIVY kanal,
        // vyziadaj PIN znova (kazdy navrat do prehravaca = PIN, ako pri starte).
        if (locked) {
            runCatching { if (mp.isPlaying) mp.pause() }
            ParentalLock.clearGrace(activity)   // M263: rovnako ako pri starte
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
