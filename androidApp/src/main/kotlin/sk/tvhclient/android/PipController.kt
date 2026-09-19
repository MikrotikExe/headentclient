package sk.tvhclient.android

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.util.Rational
import android.view.KeyEvent
import androidx.annotation.RequiresApi

/**
 * M653: Picture-in-Picture prehrávača (vyclenené z PlayerActivity) — parametre PiP okna
 * (akcie play/pauza + Zavrieť na TV, M576), vstup do PiP (ručný aj auto), obnova ikony
 * podľa stavu prehrávania, BroadcastReceiver pre akcie okna a MediaSession počas PiP
 * (M578: mediálne klávesy diaľkového idú aktívnej session bez ohľadu na fokus; STOP a
 * dlhé PLAY/PAUSE okno zavrú, issue #11).
 *
 * Rádio brány (handoff do RadioPlayerService namiesto PiP) ostávajú v aktivite — sem
 * chodí len TV prehrávanie. [close] = LastPlayback.clear + finish.
 */
internal class PipController(
    private val activity: Activity,
    private val isPlaying: () -> Boolean,
    private val playerReady: () -> Boolean,
    private val isTv: () -> Boolean,
    private val togglePlayPause: () -> Unit,
    private val close: () -> Unit
) {
    /** Zariadenie PiP podporuje (API 26+ a systémová funkcia). */
    val supported: Boolean by lazy {
        Build.VERSION.SDK_INT >= 26 &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
    }

    private var receiver: BroadcastReceiver? = null
    private var session: MediaSession? = null
    private var longFired = false

    @RequiresApi(26)
    private fun buildParams(): PictureInPictureParams {
        val playing = isPlaying()
        val icon = Icon.createWithResource(
            activity,
            if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        val label = if (playing) activity.getString(R.string.pip_pause) else activity.getString(R.string.pip_play)
        val pi = PendingIntent.getBroadcast(
            activity, 1,
            Intent(ACTION_TOGGLE).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val action = RemoteAction(icon, label, label, pi)
        // M576 (issue #11): akcia Zavriet — na TV je PiP okno mimo dosahu dialkoveho,
        // jedina cesta k nemu je systemova ponuka PiP (dlhe Home); tam sa tato akcia
        // zobrazi a okno sa da zavriet jednym potvrdenim. Na telefone je priamo v okne.
        val closeLabel = activity.getString(R.string.pip_close)
        val closePi = PendingIntent.getBroadcast(
            activity, 2,
            Intent(ACTION_CLOSE).setPackage(activity.packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val closeAction = RemoteAction(
            Icon.createWithResource(activity, android.R.drawable.ic_menu_close_clear_cancel),
            closeLabel, closeLabel, closePi
        )
        // M576-fix: telefon/tablet ma systemovy krizik v PiP okne vzdy -> nasa akcia by bola
        // druhe X vedla neho; na TV ostava (system tam vlastne ovladanie okna nema alebo ho
        // skryva v ponuke PiP)
        val actions = if (isTv()) listOf(action, closeAction) else listOf(action)
        return PictureInPictureParams.Builder()
            .setActions(actions)
            .setAspectRatio(Rational(16, 9))
            .build()
    }

    /** Ručný vstup do PiP (tlačidlo / BACK). */
    fun enterIfPossible(): Boolean {
        if (Build.VERSION.SDK_INT >= 26 &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            playerReady()
        ) {
            return runCatching { activity.enterPictureInPictureMode(buildParams()) }.getOrDefault(false)
        }
        return false
    }

    /**
     * Auto-PiP pri navigácii v rámci appky (EPG / návrat domov). Vstúpi len ak je Auto-PiP
     * zapnutý, hrá a ešte nie je v PiP. Vráti true, ak prešiel do PiP.
     */
    fun autoEnterIfPossible(): Boolean {
        if (AutoPipPref.get(activity) && supported && isPlaying() &&
            Build.VERSION.SDK_INT >= 26 &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            playerReady() &&
            !activity.isInPictureInPictureMode
        ) {
            // enterPictureInPictureMode vrati true, ak realne vstupil do PiP (nespoliehaj sa
            // na isInPictureInPictureMode hned po volani - aktualizuje sa az asynchronne)
            return runCatching { activity.enterPictureInPictureMode(buildParams()) }.getOrDefault(false)
        }
        return false
    }

    /** Aktualizuj ikonu play/pauza v PiP (a stav MediaSession) podľa skutočného stavu prehrávania. */
    fun refreshIfActive() {
        if (Build.VERSION.SDK_INT >= 26 && activity.isInPictureInPictureMode) {
            runCatching { activity.setPictureInPictureParams(buildParams()) }
            updateMediaState()
        }
    }

    /** Volať z onPictureInPictureModeChanged: session + receiver podľa režimu. */
    fun onModeChanged(inPip: Boolean) {
        if (inPip) {
            startMediaSession()   // M578
            if (receiver == null) {
                receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, i: Intent?) {
                        when (i?.action) {
                            ACTION_TOGGLE -> togglePlayPause()
                            ACTION_CLOSE -> close()   // M576
                        }
                    }
                }
                val filter = IntentFilter(ACTION_TOGGLE).apply { addAction(ACTION_CLOSE) }
                if (Build.VERSION.SDK_INT >= 33) {
                    activity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    activity.registerReceiver(receiver, filter)
                }
            }
        } else {
            stopMediaSession()   // M578
            unregisterReceiver()
        }
    }

    private fun unregisterReceiver() {
        receiver?.let { runCatching { activity.unregisterReceiver(it) } }
        receiver = null
    }

    // ---- M578: medialne klavesy v PiP cez MediaSession ----
    // Plavajuce PiP okno nedostava klavesy (na TV sa nan neda ani zamerat), ale medialne
    // tlacidla dialkoveho system doruci aktivnej MediaSession bez ohladu na fokus. Pocas
    // PiP preto drzime aktivnu session: STOP okno zavrie, PLAY/PAUSE prepina pauzu a
    // DLHE podrzanie PLAY/PAUSE zavrie tiez — pre ovladace, ktore maju len to jedno
    // tlacidlo (issue #11). Mimo PiP sa klavesy spracuvaju v dispatchKeyEvent ako doteraz.
    private fun startMediaSession() {
        if (session != null) return
        val ms = runCatching { MediaSession(activity, "headent-pip") }.getOrNull() ?: return
        ms.setCallback(object : MediaSession.Callback() {
            override fun onStop() { close() }
            override fun onPlay() { if (!isPlaying()) togglePlayPause() }
            override fun onPause() { if (isPlaying()) togglePlayPause() }
            override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                val ke = if (Build.VERSION.SDK_INT >= 33)
                    mediaButtonIntent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                else @Suppress("DEPRECATION") mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                ke ?: return super.onMediaButtonEvent(mediaButtonIntent)
                when (ke.keyCode) {
                    KeyEvent.KEYCODE_MEDIA_STOP -> { if (ke.action == KeyEvent.ACTION_DOWN) close(); return true }
                    KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    KeyEvent.KEYCODE_HEADSETHOOK -> {
                        when {
                            ke.action == KeyEvent.ACTION_DOWN && ke.repeatCount == 1 -> { longFired = true; close() }
                            ke.action == KeyEvent.ACTION_UP -> {
                                if (!longFired) togglePlayPause()
                                longFired = false
                            }
                        }
                        return true
                    }
                }
                return super.onMediaButtonEvent(mediaButtonIntent)
            }
        })
        session = ms
        updateMediaState()
        runCatching { ms.isActive = true }
    }

    private fun updateMediaState() {
        val ms = session ?: return
        val playing = isPlaying()
        val st = PlaybackState.Builder()
            .setActions(
                PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY or
                    PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE
            )
            .setState(
                if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f
            )
            .build()
        runCatching { ms.setPlaybackState(st) }
    }

    private fun stopMediaSession() {
        session?.let { runCatching { it.isActive = false; it.release() } }
        session = null
        longFired = false
    }

    /** onDestroy: session aj receiver preč. */
    fun destroy() {
        stopMediaSession()
        unregisterReceiver()
    }

    private companion object {
        const val ACTION_TOGGLE = "sk.tvhclient.android.PIP_TOGGLE"
        const val ACTION_CLOSE = "sk.tvhclient.android.PIP_CLOSE"   // M576
    }
}
