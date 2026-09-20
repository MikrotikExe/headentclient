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
 * M653: the player's Picture-in-Picture (split out of PlayerActivity) — the PiP window's parameters
 * (play/pause actions + Close on TV, M576), entering PiP (manual and auto), refreshing the icon
 * to match the playback state, a BroadcastReceiver for the window's actions and a MediaSession during PiP
 * (M578: the remote's media keys go to the active session regardless of focus; STOP and
 * a long PLAY/PAUSE close the window, issue #11).
 *
 * The radio gates (handoff to RadioPlayerService instead of PiP) stay in the activity — only
 * TV playback comes here. [close] = LastPlayback.clear + finish.
 */
internal class PipController(
    private val activity: Activity,
    private val isPlaying: () -> Boolean,
    private val playerReady: () -> Boolean,
    private val isTv: () -> Boolean,
    private val togglePlayPause: () -> Unit,
    private val close: () -> Unit
) {
    /** The device supports PiP (API 26+ and the system feature). */
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
        // M576 (issue #11): the Close action — on TV the PiP window is out of the remote's reach,
        // the only way to it is the system PiP menu (long Home); this action shows up there
        // and the window can be closed with a single confirmation. On a phone it is right in the window.
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
        // M576-fix: a phone/tablet always has the system X in the PiP window -> our action would be
        // a second X next to it; on TV it stays (there the system has no window controls of its own, or it
        // hides them in the PiP menu)
        val actions = if (isTv()) listOf(action, closeAction) else listOf(action)
        return PictureInPictureParams.Builder()
            .setActions(actions)
            .setAspectRatio(Rational(16, 9))
            .build()
    }

    /** Manual entry into PiP (button / BACK). */
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
     * Auto-PiP when navigating inside the app (EPG / back home). It enters only if Auto-PiP
     * is on, it is playing and it is not already in PiP. Returns true if it went into PiP.
     */
    fun autoEnterIfPossible(): Boolean {
        if (AutoPipPref.get(activity) && supported && isPlaying() &&
            Build.VERSION.SDK_INT >= 26 &&
            activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) &&
            playerReady() &&
            !activity.isInPictureInPictureMode
        ) {
            // enterPictureInPictureMode returns true if it really entered PiP (do not rely
            // on isInPictureInPictureMode right after the call - it updates asynchronously)
            return runCatching { activity.enterPictureInPictureMode(buildParams()) }.getOrDefault(false)
        }
        return false
    }

    /** Update the play/pause icon in PiP (and the MediaSession state) to match the real playback state. */
    fun refreshIfActive() {
        if (Build.VERSION.SDK_INT >= 26 && activity.isInPictureInPictureMode) {
            runCatching { activity.setPictureInPictureParams(buildParams()) }
            updateMediaState()
        }
    }

    /** Call from onPictureInPictureModeChanged: session + receiver according to the mode. */
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

    // ---- M578: media keys in PiP via MediaSession ----
    // The floating PiP window gets no keys (on TV it cannot even be focused), but the remote's
    // media buttons are delivered by the system to the active MediaSession regardless of focus. During
    // PiP we therefore keep an active session: STOP closes the window, PLAY/PAUSE toggles pause and
    // a LONG hold of PLAY/PAUSE closes it as well — for remotes that have only that one
    // button (issue #11). Outside PiP the keys are handled in dispatchKeyEvent as before.
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

    /** onDestroy: session and receiver gone. */
    fun destroy() {
        stopMediaSession()
        unregisterReceiver()
    }

    private companion object {
        const val ACTION_TOGGLE = "sk.tvhclient.android.PIP_TOGGLE"
        const val ACTION_CLOSE = "sk.tvhclient.android.PIP_CLOSE"   // M576
    }
}
