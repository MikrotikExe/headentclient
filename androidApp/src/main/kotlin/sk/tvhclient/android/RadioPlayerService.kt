package sk.tvhclient.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer

/**
 * Foreground service pre prehravanie radia na pozadi (M340). Vlastni vlastnu
 * libVLC instanciu (len audio, bez video vystupu), drzi notifikaciu s
 * ovladanim (pauza/prehrat, zastavit) a audio focus. Stav zrkadli do
 * RadioCenter pre mini listu v appke. Spustenie ineho prehravania
 * (PlayerActivity) service zastavi — nikdy nehraju dve veci naraz.
 */
class RadioPlayerService : Service() {

    private var libVlc: LibVLC? = null
    private var player: MediaPlayer? = null
    private var focusRequest: AudioFocusRequest? = null
    private var curName = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: ""
                val uuid = intent.getStringExtra(EXTRA_UUID) ?: ""
                startPlayback(url, name, uuid)
            }
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_STOP -> stopEverything()
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(url: String, name: String, uuid: String) {
        curName = name
        createChannel()
        startForeground(NOTIF_ID, buildNotification(playing = true))
        releasePlayer()
        runCatching {
            val vlc = LibVLC(this, arrayListOf(
                "--no-video",
                "--network-caching=1500",
                "--quiet",
                "--no-stats",
                "--http-user-agent=HeadentClient"
            ))
            libVlc = vlc
            val p = MediaPlayer(vlc)
            p.setEventListener { ev ->
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        RadioCenter.playing.value = true
                        updateNotification(playing = true)
                    }
                    MediaPlayer.Event.Paused -> {
                        RadioCenter.playing.value = false
                        updateNotification(playing = false)
                    }
                    MediaPlayer.Event.EncounteredError,
                    MediaPlayer.Event.EndReached -> stopEverything()
                }
            }
            val m = Media(vlc, Uri.parse(url))
            m.addOption(":no-video")
            p.media = m
            m.release()
            player = p
            requestFocus()
            p.play()
        }.onFailure { stopEverything(); return }
        RadioCenter.active.value = true
        RadioCenter.playing.value = true
        RadioCenter.stationName.value = name
        RadioCenter.stationUuid.value = uuid
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else { requestFocus(); p.play() }
    }

    private fun stopEverything() {
        RadioCenter.active.value = false
        RadioCenter.playing.value = false
        releasePlayer()
        abandonFocus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        runCatching {
            player?.setEventListener(null)
            player?.stop()
            player?.release()
        }
        player = null
        runCatching { libVlc?.release() }
        libVlc = null
    }

    // --- audio focus: pri strate pauza (telefonat, ina appka) ---
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> player?.pause()
            AudioManager.AUDIOFOCUS_GAIN -> { /* nechavame na pouzivatela */ }
        }
    }

    private fun requestFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    // --- notifikacia ---
    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.radio_notif_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }

    private fun pending(action: String): PendingIntent =
        PendingIntent.getService(
            this, action.hashCode(),
            Intent(this, RadioPlayerService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun buildNotification(playing: Boolean): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(curName)
            .setContentText(getString(R.string.tab_radio))
            .setContentIntent(openApp)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                getString(if (playing) R.string.pause else R.string.play),
                pending(ACTION_TOGGLE)
            )
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.pm_close),
                pending(ACTION_STOP)
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }

    private fun updateNotification(playing: Boolean) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification(playing)) }
    }

    override fun onDestroy() {
        releasePlayer()
        abandonFocus()
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "sk.tvhclient.radio.PLAY"
        const val ACTION_TOGGLE = "sk.tvhclient.radio.TOGGLE"
        const val ACTION_STOP = "sk.tvhclient.radio.STOP"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_UUID = "uuid"
        private const val CHANNEL_ID = "radio_playback"
        private const val NOTIF_ID = 4210

        /** Zastavi mini radio (napr. pri starte plneho prehravaca). */
        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, RadioPlayerService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
