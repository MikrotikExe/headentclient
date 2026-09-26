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
 * M655: opening a stream in the player (extracted from PlayerActivity): direct HTTP, HTTP via
 * feeder (digest-only, M255), DVR via feeder (M253), HTSP live (M162) and auth auto-detection
 * (M390) including the fix for an old HTSP channel identity in HTTP mode (M390-fix4).
 *
 * The order of steps in each path is identical to the original code: ensureHealthyPlayer (M539) ->
 * teletext reset (M552) -> stopping the feeders + HTSP flags -> resetTimeshift -> URL ->
 * medium -> startPlayback (M539-fix2). The state is held by [StreamState]; whatever touches the activity
 * (player, teletext, subtitle overlay, playlist) goes through [Hooks].
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
        /** Substitute channel name used when fixing the identity (EXTRA_TITLE from the intent). */
        fun fallbackTitle(): String?
    }

    /** M255 — live over HTTP on a digest-only server: download via the feeder (same
     *  as DVR), because libVLC cannot handle digest via the URL. No seek is needed for live. */
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

    /** M390-fix4: in HTTP mode an old (HTSP numeric) channel identity arrived —
     *  the server rejects it (HTTP 400). Find the correct uuid via REST by channel name
     *  or number, fix the playlist and play with the corrected uuid.
     *  Returns true if it handles the url itself (it started an asynchronous resolution). */
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
                playHttp(url)   // not found -> the original path (reconnect will report it)
            }
        }
        return true
    }

    /** Live HTTP with auth auto-detection: digest-only -> feeder, otherwise the direct path. */
    fun playLiveAuto(server: TvhServer, url: String) {
        if (server.connectionMode != "htsp" && healStaleLiveId(server, url)) return
        if (server.username.isEmpty()) { playHttp(url); return }
        val cached = stream.liveNeedsFeeder
        if (cached != null) {
            if (cached) playLiveViaFeeder(server, url) else playHttp(url)
            return
        }
        scope.launch {
            // M390: null = the probe failed -> try the direct path, but do not cache the result;
            // if the direct path fails, scheduleReconnect switches to the feeder.
            val nf = withContext(Dispatchers.IO) { DvrAuthProbe.needsFeederOrNull(server, MediaFactory.stripCreds(url)) }
            stream.liveNeedsFeeder = nf
            if (nf == true) playLiveViaFeeder(server, url) else playHttp(url)
        }
    }

    /** Ordinary HTTP playback (stops any HTSP feed). */
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
     * M253 — DVR/archive via HttpTsFeeder: the app downloads the dvrfile with digest auth
     * (OkHttp + DigestAuthenticator from shared/net) and feeds libVLC through a pipe. Same principle as
     * HTSP live; solves digest-only servers where libVLC cannot handle creds in the URL
     * (user:pass@host). startByte = an optional offset for resume via HTTP Range.
     */
    fun playDvrViaFeeder(server: TvhServer, url: String, startByte: Long = 0L) {
        hooks.ensureHealthyPlayer()
        hooks.resetTeletext()   // archive: teletext for live only so far
        stream.resetForHttp(keepHttpFeeder = true)
        hooks.resetTimeshift()
        stream.currentStreamUrl = url
        val feeder = HttpTsFeeder(server, MediaFactory.stripCreds(url), startByte)
        stream.httpFeeder = feeder
        val fd = feeder.start(scope)
        // M509: do NOT force the TS demuxer (demux = null). A recording can be in any container
        // depending on the DVR profile (matroska, mp4, webm) — hard-coded ts meant that
        // VLC did not parse the file, found no video track and the app showed black with
        // the radio logo. The file is read from the beginning, so it determines the container
        // reliably by itself (EBML / ftyp / TS sync header).
        val m = media.forFeeder(fd, null, BufferPref.htspMs(ctx))
        player().media = m
        m.release()
        hooks.startPlayback()
    }

    /**
     * M162 — a live channel over HTSP (remuxed to MPEG-TS, fed to libVLC through a pipe).
     * Returns true if it started successfully. Used only if timeshift is enabled and the server
     * supports it; otherwise the HTTP path remains.
     */
    fun playHtspLive(server: TvhServer, channelId: Long, timeshift: Boolean): Boolean {
        return try {
            hooks.ensureHealthyPlayer()
            stream.htspFeeder?.stop()
            stream.httpFeeder?.stop(); stream.httpFeeder = null
            val feeder = HtspTsFeeder(server, if (timeshift) 3600 else 0)
            stream.htspFeeder = feeder
            // our own subtitles: send the decoded page to the overlay (it syncs on time)
            feeder.onSubtitlePage = { page, ms -> hooks.subtitlePage(page, ms) }
            hooks.subtitleReset()
            // M552: teletext — the TELETEXT track goes to our own decoder, not to libVLC
            hooks.resetTeletext()
            feeder.onTeletextAvailable = { a -> hooks.teletextSetHtspAvailable(a) }
            feeder.onTeletext = { es -> hooks.teletextFeedHtsp(es) }
            // a new channel = a new subtitle list, reset the chosen language
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

    // ---- M670: reconnect / reopen / seek (lambda bodies from the activity) ----

    /** A direct HTTP medium without resetting the feeders/teletext (reconnect, reopen, seek), optionally with :start-time. */
    private fun playUrlDirect(url: String, startTimeSec: Long?) {
        hooks.ensureHealthyPlayer()   // M539
        val m = media.forUrl(url)
        if (startTimeSec != null) m.addOption(":start-time=$startTimeSec")
        player().media = m
        m.release()
        hooks.startPlayback()   // M539-fix2
    }

    /** One attempt to reconnect the live stream (ReconnectController.scheduleReconnect). */
    fun reconnectAttempt(attempt: Int, seekable: Boolean) {
        val srv = live.server
        val cid = live.uuids.getOrNull(live.index)?.toLongOrNull()
        val url = stream.currentStreamUrl
        if (stream.htspStream && srv != null && cid != null) {
            // HTSP channel -> hook it up via HTSP again (keeps HTSP/timeshift)
            playHtspLive(srv, cid, stream.htspLive)
        } else if (stream.liveNeedsFeeder == true && srv != null && url != null) {
            playLiveViaFeeder(srv, url)   // HTTP digest-only -> feeder
        } else if (url != null) {
            // M390: direct HTTP live crashes in libVLC on some boxes (auth/transport),
            // even though the feeder (OkHttp -> pipe) works — after the 2nd failed attempt switch to the feeder.
            if (attempt >= 2 && !seekable && srv != null && srv.username.isNotEmpty()) {
                stream.liveNeedsFeeder = true
                playLiveViaFeeder(srv, url)
            } else if (attempt == 1 && !seekable && srv != null) {
                // M694: the first attempt goes through the feeder (without remembering it): libVLC does
                // not tell us the HTTP status, OkHttp does — so a refusal because of the account's
                // connection limit is recognised here and the player shows it instead of retrying.
                // It is a real playback attempt, not an extra probe (a probe on the stream URL
                // would itself take the account's only slot, M394).
                playLiveViaFeeder(srv, url)
            } else {
                stream.httpFeeder?.stop(); stream.httpFeeder = null   // M694: the attempt-1 feeder must not keep its connection
                playUrlDirect(url, null)   // ordinary HTTP
            }
        }
    }

    /** Restarts the current live channel by the same path (HTSP / feeder / HTTP) — after a player swap. */
    fun replayCurrentLive() {
        val srv = live.server
        val cid = live.uuids.getOrNull(live.index)?.toLongOrNull()
        val url = stream.currentStreamUrl
        runCatching {
            if (stream.htspStream && srv != null && cid != null) {
                playHtspLive(srv, cid, stream.htspLive)
            } else if (stream.liveNeedsFeeder == true && srv != null && url != null) {
                playLiveViaFeeder(srv, url)
            } else if (url != null) {
                playHttp(url)
            }
        }
    }

    /** In-progress recording: reopen the stream from [startSec] (feeder: from the point we got to). */
    fun reopenDvrAt(url: String, startSec: Long) {
        if (stream.dvrViaFeeder) {
            // continue from the point we got to (a growing file) via HTTP Range
            val srv = live.server ?: return
            val from = stream.httpFeeder?.bytesWritten ?: 0L
            playDvrViaFeeder(srv, url, from)
        } else {
            playUrlDirect(url, startSec)
        }
    }

    /**
     * Seek a DVR recording by REBUILDING the stream: direct URL -> a new Media with :start-time
     * (libVLC seeks via HTTP Range); feeder/pipe -> restart the HTTP feed at an estimated
     * byte offset (a pipe cannot be seeked). [fileMs] = target time in the file, [offsetMs] = start of the
     * programme in the file, [fromMs] = where we are seeking from, [dur] = currently recorded duration of the programme.
     */
    fun seekDvrFile(url: String, fileMs: Long, offsetMs: Long, fromMs: Long, dur: Long) {
        runCatching {
            if (stream.dvrViaFeeder) {
                val srv = live.server ?: return
                val feeder = stream.httpFeeder
                // Exact time->byte conversion from the GLOBAL average: total file size
                // (Content-Range "/N") / total file time (offset + recorded duration).
                // A local estimate from bytesWritten/playhead is unreliable (bytes vs time do not match).
                val total = feeder?.totalBytes ?: 0L
                val fileDurMs = offsetMs + dur            // dur = currently recorded duration of the programme
                val targetByte: Long = if (total > 0 && fileDurMs > 0) {
                    (total.toDouble() / fileDurMs * fileMs).toLong().coerceIn(0L, total - 1)
                } else {
                    // fallback: a local estimate if the total size is not known yet
                    val bytes = feeder?.bytesWritten ?: 0L
                    val fromFileMs = (offsetMs + fromMs).coerceAtLeast(1L)
                    val bpms = if (bytes > 0) bytes.toDouble() / fromFileMs else 0.0
                    if (bpms > 0) (bpms * fileMs).toLong().coerceAtLeast(0L) else 0L
                }
                playDvrViaFeeder(srv, url, targetByte)
            } else {
                playUrlDirect(url, fileMs / 1000)
            }
        }
    }
}
