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
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.TvhServer

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
    private var closedTvForStart = false
    private var curEpg = ""
    // Rovnaka auth cesta ako prehravac (M352): digest-only server sa neda hrat
    // z holej user:pass@ URL — stream musi tiect cez HttpTsFeeder (OkHttp digest)
    // do VLC cez file descriptor. Preto service potrebuje vlastny scope a feeder.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var httpFeeder: HttpTsFeeder? = null
    private var curServer: TvhServer? = null
    // M623: pri zhasnutej obrazovke (volba "Radio hra na pozadi") nesmie CPU ani
    // Wi-Fi zaspat — rovnake zamky ako drzi prehravac (M452), len pocas hrania.
    // M626: spolocna trieda StreamLocks.
    private val locks by lazy { StreamLocks(this, "HeadentClient:radio") }
    // M625: medialna notifikacia (MediaStyle + MediaSession) ako Spotify —
    // picon ako obrazok, ovladanie prev/pauza/next/stop, priebeh relacie z EPG.
    private var session: android.media.session.MediaSession? = null
    private var artwork: android.graphics.Bitmap? = null
    private var artworkFor: String? = null          // uuid stanice, pre ktoru je artwork
    private val epgHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val epgExpired = Runnable { updateNotification(player?.isPlaying == true) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY -> {
                val url = intent.getStringExtra(EXTRA_URL) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: ""
                val uuid = intent.getStringExtra(EXTRA_UUID) ?: ""
                curEpg = intent.getStringExtra(EXTRA_EPG) ?: ""
                curServer = Tvh.store.active()
                startPlayback(url, name, uuid)
            }
            ACTION_TOGGLE -> togglePlayPause()
            ACTION_STOP -> stopEverything()
            ACTION_NEXT -> RadioCenter.switchStation(this, +1)    // M625
            ACTION_PREV -> RadioCenter.switchStation(this, -1)    // M625
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(url: String, name: String, uuid: String) {
        curName = name
        createChannel()
        ensureSession()                       // M625
        if (artworkFor != uuid) {             // M625: nova stanica -> zatial bez piconu
            artwork = RadioArtwork.render(this, null)
            artworkFor = uuid
            loadArtwork(uuid, RadioCenter.piconUrl.value)
        }
        updateSessionMetadata()
        updatePlaybackState(playing = true)
        startForeground(NOTIF_ID, buildNotification(playing = true))
        acquireLocks()   // M623
        releasePlayer()
        // M394-fix: uvolni pripadny TV stream (aj PiP) — pri limite 1 pripojenia
        // by server radio inak odmietol, kym stary stream drzi slot
        closedTvForStart = PlayerActivity.closeActive()
        runCatching {
            val vlc = LibVLC(this, arrayListOf(
                "--no-video",
                "--network-caching=1500",
                "--quiet",
                "--no-stats",
                "--http-user-agent=" + sk.tvhclient.shared.ClientIdent.userAgent
            ))
            libVlc = vlc
            val p = MediaPlayer(vlc)
            p.setEventListener { ev ->
                when (ev.type) {
                    MediaPlayer.Event.Playing -> {
                        RadioCenter.playing.value = true
                        acquireLocks()   // M623
                        updateNotification(playing = true)
                    }
                    MediaPlayer.Event.Paused -> {
                        RadioCenter.playing.value = false
                        releaseLocks()   // M623: v pauze zamky netreba (bateria)
                        updateNotification(playing = false)
                    }
                    MediaPlayer.Event.EncounteredError,
                    MediaPlayer.Event.EndReached -> stopEverything()
                }
            }
            player = p
            requestFocus()
            attachMediaAndPlay(vlc, p, url)
        }.onFailure { CrashLogger.report(this, "RadioPlayerService.startPlayback", it); stopEverything(); return }
        RadioCenter.active.value = true
        RadioCenter.playing.value = true
        RadioCenter.stationName.value = name
        RadioCenter.stationUuid.value = uuid
    }

    /** Rozhodne rovnako ako prehravac: digest-only -> feeder (FD), inak priama URL. */
    private fun attachMediaAndPlay(vlc: LibVLC, p: MediaPlayer, url: String) {
        val server = curServer
        if (server == null || server.username.isEmpty()) {
            // bez creds alebo neznamy server -> priama URL
            scope.launch {
                if (closedTvForStart) kotlinx.coroutines.delay(400)  // M394-fix
                if (player === p) setDirectMedia(vlc, p, url)
            }
            return
        }
        scope.launch {
            if (closedTvForStart) kotlinx.coroutines.delay(400)  // M394-fix: nech TV stihne pustit slot
            val needsFeeder = withContext(Dispatchers.IO) {
                runCatching { DvrAuthProbe.needsFeeder(server, stripCreds(url)) }.getOrDefault(false)
            }
            if (player !== p) return@launch  // medzitym prepnute/zastavene
            if (needsFeeder) {
                httpFeeder?.stop()
                val feeder = HttpTsFeeder(server, stripCreds(url), 0L)
                httpFeeder = feeder
                val fd = feeder.start(scope)
                val m = Media(vlc, fd)
                m.addOption(":no-video")
                m.addOption(":demux=ts")
                m.addOption(":file-caching=1500")
                p.media = m
                m.release()
            } else {
                setDirectMedia(vlc, p, url)
                return@launch
            }
            p.play()
        }
    }

    private fun setDirectMedia(vlc: LibVLC, p: MediaPlayer, url: String) {
        val m = Media(vlc, Uri.parse(url))
        m.addOption(":no-video")
        p.media = m
        m.release()
        p.play()
    }

    /** Odstrani user:pass@ z URL (auth riesi feeder cez OkHttp hlavicku). */
    private fun stripCreds(url: String): String {
        val i = url.indexOf("://")
        if (i < 0) return url
        val rest = url.substring(i + 3)
        val at = rest.indexOf('@')
        val slash = rest.indexOf('/')
        if (at < 0 || (slash in 0 until at)) return url
        return url.substring(0, i + 3) + rest.substring(at + 1)
    }

    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else { requestFocus(); p.play() }
    }

    private fun stopEverything() {
        RadioCenter.active.value = false
        RadioCenter.playing.value = false
        releasePlayer()
        releaseLocks()   // M623
        abandonFocus()
        releaseSession()  // M625
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun releasePlayer() {
        runCatching { httpFeeder?.stop() }
        httpFeeder = null
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

    /**
     * M625: medialna notifikacia. Framework MediaStyle (bez androidx.media) naviazany
     * na MediaSession — Android 13+ z nej kresli velku kartu s obrazkom, farbami
     * odvodenymi z artworku (RadioArtwork = farby appky) a lištou priebehu relacie;
     * starsie verzie klasicky MediaStyle s tromi tlacidlami v kompaktnom pohlade.
     * Prev/next len ak je v snapshote viac stanic.
     */
    private fun buildNotification(playing: Boolean): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(this, CHANNEL_ID)
                else @Suppress("DEPRECATION") android.app.Notification.Builder(this)
        val epg = currentEpgLine()
        b.setSmallIcon(R.drawable.ic_stat_radio)
            .setContentTitle(curName)
            .setContentText(epg.ifBlank { getString(R.string.tab_radio) })
            .setSubText(if (epg.isBlank()) null else getString(R.string.tab_radio))
            .setContentIntent(openApp)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(RadioArtwork.accent(this))
            .setVisibility(android.app.Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(pending(ACTION_STOP))
        if (Build.VERSION.SDK_INT >= 26) b.setColorized(true)
        artwork?.let { b.setLargeIcon(it) }

        val multi = RadioCenter.stations.size > 1
        val compact = ArrayList<Int>()
        fun action(icon: Int, title: Int, act: String) {
            b.addAction(android.app.Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, icon),
                getString(title), pending(act)).build())
        }
        if (multi) { action(android.R.drawable.ic_media_previous, R.string.radio_prev_station, ACTION_PREV); compact.add(0) }
        action(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (playing) R.string.pause else R.string.play, ACTION_TOGGLE); compact.add(compact.size)
        if (multi) { action(android.R.drawable.ic_media_next, R.string.radio_next_station, ACTION_NEXT); compact.add(compact.size) }
        action(android.R.drawable.ic_menu_close_clear_cancel, R.string.pm_close, ACTION_STOP)
        if (!multi) compact.add(compact.size)   // bez prev/next: pauza + zavriet v kompaktnom

        val style = android.app.Notification.MediaStyle()
        session?.let { style.setMediaSession(it.sessionToken) }
        style.setShowActionsInCompactView(*compact.take(3).toIntArray())
        b.setStyle(style)
        return b.build()
    }

    /** EPG riadok, len kym relacia realne bezi (po konci by bol zavadzajuci). */
    private fun currentEpgLine(): String {
        val title = RadioCenter.nowTitle.value.ifBlank { curEpg }
        val stop = RadioCenter.nowStop.value
        if (title.isBlank()) return ""
        if (stop > 0 && System.currentTimeMillis() / 1000 >= stop) return ""
        return title
    }

    // ---- M625: MediaSession ----
    private fun ensureSession() {
        if (session != null) return
        val ms = runCatching { android.media.session.MediaSession(this, "headent-radio") }.getOrNull() ?: return
        ms.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPlay() { val p = player ?: return; if (!p.isPlaying) { requestFocus(); p.play() } }
            override fun onPause() { player?.let { if (it.isPlaying) it.pause() } }
            override fun onStop() { stopEverything() }
            override fun onSkipToNext() { RadioCenter.switchStation(this@RadioPlayerService, +1) }
            override fun onSkipToPrevious() { RadioCenter.switchStation(this@RadioPlayerService, -1) }
        })
        ms.setSessionActivity(PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        @Suppress("DEPRECATION")
        ms.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
            android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS)
        ms.isActive = true
        session = ms
    }

    private fun releaseSession() {
        epgHandler.removeCallbacks(epgExpired)
        runCatching { session?.isActive = false; session?.release() }
        session = null
        artwork = null
        artworkFor = null
    }

    /** Nazov stanice, relacia, artwork a dlzka relacie (pre lištu priebehu). */
    private fun updateSessionMetadata() {
        val ms = session ?: return
        val epg = currentEpgLine()
        val start = RadioCenter.nowStart.value
        val stop = RadioCenter.nowStop.value
        val mb = android.media.MediaMetadata.Builder()
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, curName)
            .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE, curName)
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, epg.ifBlank { getString(R.string.tab_radio) })
            .putString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, epg)
        if (epg.isNotBlank() && start > 0 && stop > start) {
            mb.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, (stop - start) * 1000L)
        } else {
            mb.putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, -1L)   // zivy stream bez lišty
        }
        artwork?.let {
            mb.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it)
            mb.putBitmap(android.media.MediaMetadata.METADATA_KEY_ART, it)
        }
        runCatching { ms.setMetadata(mb.build()) }
        // po konci relacie prekresli (bez EPG riadku a bez lišty)
        epgHandler.removeCallbacks(epgExpired)
        if (epg.isNotBlank() && stop > 0) {
            val delay = stop * 1000L - System.currentTimeMillis()
            if (delay > 0) epgHandler.postDelayed(epgExpired, delay + 1000L)
        }
    }

    /** Stav prehravania; pozicia = cas od zaciatku relacie (system ju posuva sam pri speed 1). */
    private fun updatePlaybackState(playing: Boolean) {
        val ms = session ?: return
        val start = RadioCenter.nowStart.value
        val pos = if (currentEpgLine().isNotBlank() && start > 0)
            (System.currentTimeMillis() - start * 1000L).coerceAtLeast(0L)
        else android.media.session.PlaybackState.PLAYBACK_POSITION_UNKNOWN
        var actions = android.media.session.PlaybackState.ACTION_PLAY or
            android.media.session.PlaybackState.ACTION_PAUSE or
            android.media.session.PlaybackState.ACTION_PLAY_PAUSE or
            android.media.session.PlaybackState.ACTION_STOP
        if (RadioCenter.stations.size > 1) {
            actions = actions or android.media.session.PlaybackState.ACTION_SKIP_TO_NEXT or
                android.media.session.PlaybackState.ACTION_SKIP_TO_PREVIOUS
        }
        val st = android.media.session.PlaybackState.Builder()
            .setActions(actions)
            .setState(
                if (playing) android.media.session.PlaybackState.STATE_PLAYING
                else android.media.session.PlaybackState.STATE_PAUSED,
                pos, if (playing) 1f else 0f
            )
            .build()
        runCatching { ms.setPlaybackState(st) }
    }

    /** Picon stanice na pozadi; po nacitani prekresli artwork, metadata aj notifikaciu. */
    private fun loadArtwork(uuid: String, piconUrl: String?) {
        if (piconUrl.isNullOrBlank()) return
        scope.launch {
            val bmp = withContext(Dispatchers.IO) { RadioArtwork.loadPicon(this@RadioPlayerService, curServer, piconUrl) }
            if (bmp == null || artworkFor != uuid || session == null) return@launch
            artwork = RadioArtwork.render(this@RadioPlayerService, bmp)
            updateSessionMetadata()
            updateNotification(player?.isPlaying == true)
        }
    }

    private fun updateNotification(playing: Boolean) {
        updatePlaybackState(playing)   // M625
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        runCatching { nm.notify(NOTIF_ID, buildNotification(playing)) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Appka zmazana z recents -> radio ma stichnut, nie hrat "duchovsky" dalej
        stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    private fun acquireLocks() = locks.acquire()
    private fun releaseLocks() = locks.release()

    override fun onDestroy() {
        releasePlayer()
        releaseLocks()   // M623
        abandonFocus()
        releaseSession()  // M625
        runCatching { scope.cancel() }
        super.onDestroy()
    }

    companion object {
        const val ACTION_PLAY = "sk.tvhclient.radio.PLAY"
        const val ACTION_TOGGLE = "sk.tvhclient.radio.TOGGLE"
        const val ACTION_STOP = "sk.tvhclient.radio.STOP"
        const val ACTION_NEXT = "sk.tvhclient.radio.NEXT"     // M625
        const val ACTION_PREV = "sk.tvhclient.radio.PREV"     // M625
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        const val EXTRA_UUID = "uuid"
        const val EXTRA_EPG = "epg"
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
