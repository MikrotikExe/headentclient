package sk.tvhclient.android

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.MediaPlayer
import sk.tvhclient.shared.Tvh
import sk.tvhclient.shared.model.TvhServer

/**
 * M655: otváranie streamu v prehrávači (vyclenené z PlayerActivity): priame HTTP, HTTP cez
 * feeder (digest-only, M255), DVR cez feeder (M253), HTSP live (M162) a auto-detekcia auth
 * (M390) vrátane opravy starej HTSP identity kanála v HTTP režime (M390-fix4).
 *
 * Poradie krokov v každej ceste je zhodné s pôvodným kódom: ensureHealthyPlayer (M539) ->
 * teletext reset (M552) -> zastavenie feederov + HTSP príznaky -> resetTimeshift -> URL ->
 * médium -> startPlayback (M539-fix2). Stav drží [StreamState]; čo siaha na aktivitu
 * (prehrávač, teletext, titulkový overlay, playlist) chodí cez [Hooks].
 */
internal class StreamOpener(
    private val ctx: Context,
    private val scope: CoroutineScope,
    private val stream: StreamState,
    private val live: LiveSession,
    private val media: MediaFactory,
    private val tracks: TrackState,
    private val player: () -> MediaPlayer,
    private val hooks: Hooks
) {
    interface Hooks {
        fun ensureHealthyPlayer()          // M539
        fun startPlayback()                // M539-fix2
        fun resetTimeshift()
        /** closeTeletext() + teletext.reset() (M552/M553). */
        fun resetTeletext()
        fun teletextSetHtspAvailable(available: Boolean)
        fun teletextFeedHtsp(es: ByteArray)
        fun subtitlePage(page: sk.tvhclient.shared.htsp.DvbSubtitleDecoder.DecodedPage, ms: Long)
        fun subtitleReset()
        /** Náhradný názov kanála pri oprave identity (EXTRA_TITLE z intentu). */
        fun fallbackTitle(): String?
    }

    /** M255 — live cez HTTP na digest-only serveri: stiahnut cez feeder (rovnako
     *  ako DVR), lebo libVLC digest cez URL nezvlada. Pre live netreba seek. */
    fun playLiveViaFeeder(server: TvhServer, url: String) {
        hooks.ensureHealthyPlayer()
        hooks.resetTeletext()
        stream.resetForHttp(keepHttpFeeder = true)
        hooks.resetTimeshift()
        stream.currentStreamUrl = url
        val feeder = HttpTsFeeder(server, MediaFactory.stripCreds(url), 0L)
        stream.httpFeeder = feeder
        val fd = feeder.start(scope)
        val m = media.forFeeder(fd, media.feederDemuxFor(url), BufferPref.ms(ctx))   // M381/M509
        player().media = m
        m.release()
        hooks.startPlayback()
    }

    /** M390-fix4: v HTTP rezime prisla stara (HTSP ciselna) identita kanala —
     *  server ju odmieta (HTTP 400). Najdi cez REST spravne uuid podla nazvu
     *  alebo cisla kanala, oprav playlist a prehraj s opravenym uuid.
     *  Vracia true, ak sa o url postara sama (spustila asynchronne riesenie). */
    private fun healStaleLiveId(server: TvhServer, url: String): Boolean {
        val pathId = MediaFactory.stripCreds(url).substringAfter("/stream/channel/", "").substringBefore('?')
        if (pathId.isBlank() || MediaFactory.looksLikeRestUuid(pathId)) return false
        val entry = LivePlaylist.channels.firstOrNull { it.uuid == pathId }
        val wantName = entry?.name ?: live.names.getOrNull(live.index) ?: hooks.fallbackTitle()
        val wantNum = entry?.number ?: 0
        scope.launch {
            val fixed = withContext(Dispatchers.IO) {
                runCatching {
                    val api = Tvh.apiFor(server)
                    try {
                        val chs = api.channels()
                        (wantName?.let { n -> chs.firstOrNull { it.name.equals(n, ignoreCase = true) } }
                            ?: if (wantNum > 0) chs.firstOrNull { (it.number ?: -1) == wantNum } else null)
                            ?.uuid
                    } finally { api.close() }
                }.getOrNull()
            }
            if (fixed != null && MediaFactory.looksLikeRestUuid(fixed)) {
                LivePlaylist.channels = LivePlaylist.channels.map { if (it.uuid == pathId) it.copy(uuid = fixed) else it }
                LivePlaylist.allChannels = LivePlaylist.allChannels.map { if (it.uuid == pathId) it.copy(uuid = fixed) else it }
                live.uuids = live.uuids.map { if (it == pathId) fixed else it }
                val newUrl = Tvh.liveUrl(server, fixed, wantName, server.profile.ifBlank { "pass" })
                stream.currentStreamUrl = newUrl
                playLiveAuto(server, newUrl)
            } else {
                playHttp(url)   // nenaslo sa -> povodna cesta (reconnect to ohlasi)
            }
        }
        return true
    }

    /** Live HTTP s auto-detekciou auth: digest-only -> feeder, inak priama cesta. */
    fun playLiveAuto(server: TvhServer, url: String) {
        if (server.connectionMode != "htsp" && healStaleLiveId(server, url)) return
        if (server.username.isEmpty()) { playHttp(url); return }
        val cached = stream.liveNeedsFeeder
        if (cached != null) {
            if (cached) playLiveViaFeeder(server, url) else playHttp(url)
            return
        }
        scope.launch {
            // M390: null = sonda zlyhala -> skus priamu cestu, ale vysledok necachuj;
            // ak priama cesta pada, scheduleReconnect prepne na feeder.
            val nf = withContext(Dispatchers.IO) { DvrAuthProbe.needsFeederOrNull(server, MediaFactory.stripCreds(url)) }
            stream.liveNeedsFeeder = nf
            if (nf == true) playLiveViaFeeder(server, url) else playHttp(url)
        }
    }

    /** Bezne HTTP prehravanie (zastavi pripadny HTSP feed). */
    fun playHttp(url: String) {
        hooks.ensureHealthyPlayer()
        hooks.resetTeletext()
        stream.resetForHttp(keepHttpFeeder = false)
        hooks.resetTimeshift()
        stream.currentStreamUrl = url
        val m = media.forUrl(url)
        player().media = m
        m.release()
        hooks.startPlayback()
    }

    /**
     * M253 — DVR/archiv cez HttpTsFeeder: appka stiahne dvrfile s digest auth
     * (OkHttp + DigestAuthenticator) a podava libVLC cez pipe. Rovny princip ako
     * HTSP live; rieši digest-only servery kde creds v URL (user:pass@host)
     * libVLC nezvladne. startByte = pripadny offset pre resume cez HTTP Range.
     */
    fun playDvrViaFeeder(server: TvhServer, url: String, startByte: Long = 0L) {
        hooks.ensureHealthyPlayer()
        hooks.resetTeletext()   // archív: teletext zatiaľ len pri živom
        stream.resetForHttp(keepHttpFeeder = true)
        hooks.resetTimeshift()
        stream.currentStreamUrl = url
        val feeder = HttpTsFeeder(server, MediaFactory.stripCreds(url), startByte)
        stream.httpFeeder = feeder
        val fd = feeder.start(scope)
        // M509: NEvnucuj TS demuxer (demux = null). Nahravka moze byt v lubovolnom kontajneri
        // podla DVR profilu (matroska, mp4, webm) — natvrdo ts znamenalo, ze
        // VLC subor nerozobral, nenasiel video stopu a appka zobrazila cierno s
        // radiovym logom. Subor sa cita od zaciatku, takze si kontajner urci
        // spolahlivo sam (EBML / ftyp / TS sync hlavicka).
        val m = media.forFeeder(fd, null, BufferPref.htspMs(ctx))
        player().media = m
        m.release()
        hooks.startPlayback()
    }

    /**
     * M162 — zivy kanal cez HTSP (premuxovany na MPEG-TS, podavany libVLC cez pipe).
     * Vracia true ak sa podarilo spustit. Pouzite len ak je timeshift zapnuty a server
     * ho podporuje; inak ostava HTTP cesta.
     */
    fun playHtspLive(server: TvhServer, channelId: Long, timeshift: Boolean): Boolean {
        return try {
            hooks.ensureHealthyPlayer()
            stream.htspFeeder?.stop()
            stream.httpFeeder?.stop(); stream.httpFeeder = null
            val feeder = HtspTsFeeder(server, if (timeshift) 3600 else 0)
            stream.htspFeeder = feeder
            // vlastne titulky: dekódovanu stranku posli do overlay-u (synchronizuje sa na cas)
            feeder.onSubtitlePage = { page, ms -> hooks.subtitlePage(page, ms) }
            hooks.subtitleReset()
            // M552: teletext — stopa TELETEXT ide do vlastného dekodéra, nie do libVLC
            hooks.resetTeletext()
            feeder.onTeletextAvailable = { a -> hooks.teletextSetHtspAvailable(a) }
            feeder.onTeletext = { es -> hooks.teletextFeedHtsp(es) }
            // novy kanal = novy zoznam titulkov, vynuluj zvoleny jazyk
            tracks.selectedSubEs.value = -1
            tracks.desiredSubName = null
            hooks.resetTimeshift()
            val fd = feeder.start(channelId, scope, live.server?.profile)   // M476
            val m = media.forFeeder(fd, "ts", BufferPref.htspMs(ctx))
            player().media = m
            m.release()
            hooks.startPlayback()
            true
        } catch (e: Throwable) {
            stream.htspFeeder?.stop()
            stream.htspFeeder = null
            false
        }
    }
}
