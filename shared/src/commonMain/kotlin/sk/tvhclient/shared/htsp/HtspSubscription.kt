package sk.tvhclient.shared.htsp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import sk.tvhclient.shared.currentTimeSeconds

/**
 * M693: one live HTSP subscription on the shared [HtspSession] connection, remuxed into MPEG-TS.
 *
 * Until now every stream had its own HTSP connection with its own receive loop
 * (HtspClient.streamSubscribe). Like Kodi (pvr.hts), the stream now runs as a subscription on the
 * app's single connection: the session's reader hands this subscription its messages (muxpkt,
 * subscriptionStart/Stop, timeshiftStatus...) through [inbox] and [run] processes them — the logic is
 * the same as the former streamSubscribe loop (M162, M552, M659, M673). Tvheadend only re-launches
 * the same connection as a streaming one, so an account with a connection limit of 1 is no longer
 * refused when the app loads EPG, channels or recordings during playback.
 */
class HtspSubscription internal constructor(
    private val session: HtspSession,
    val subscriptionId: Int
) {
    /**
     * Messages from the session reader. M715: unlimited, the reader never waits; the number of
     * waiting media packets is bounded in [fromReader] (a muxpkt can carry a 100 kB key frame).
     */
    internal val inbox = Channel<Map<String, Any?>>(capacity = Channel.UNLIMITED)

    // M715: media packets waiting in [inbox] = pktIn - pktOut. Each counter has a single writer
    // (pktIn the session reader, pktOut [run]), so @Volatile is enough without atomics.
    @kotlin.concurrent.Volatile private var pktIn = 0L
    @kotlin.concurrent.Volatile private var pktOut = 0L
    /** M715: the stream has a video track (set by [run] on subscriptionStart). */
    @kotlin.concurrent.Volatile private var hasVideo = false
    // reader-only state
    private var dropping = false
    private var dropSince = 0L

    /**
     * M715: called by the session reader for every message of this subscription; never waits.
     * Control messages (start/stop/status/queueStatus/signalStatus/timeshiftStatus) always go
     * through, in order. Media packets beyond [MAX_PENDING] waiting ones are dropped, and then
     * dropped until the backlog has halved and a video key frame arrives (so the decoder does not
     * get broken references); a stream without video, or after [RESYNC_MAX_SEC], resumes as soon
     * as there is room.
     */
    internal fun fromReader(m: Map<String, Any?>) {
        if (m["method"] == "muxpkt") {
            val pending = pktIn - pktOut
            if (dropping) {
                val key = (m["frametype"] as? Long)?.toInt() == 'I'.code
                val late = currentTimeSeconds() - dropSince >= RESYNC_MAX_SEC
                if (pending <= MAX_PENDING / 2 && (!hasVideo || key || late)) dropping = false else return
            } else if (pending >= MAX_PENDING) {
                dropping = true
                dropSince = currentTimeSeconds()
                return
            }
            pktIn++
        }
        inbox.trySend(m)   // unlimited: fails only once the subscription has ended
    }

    private var muxer: TsMuxer? = null
    private val subDecoder = DvbSubtitleDecoder()
    @kotlin.concurrent.Volatile private var subDecodeEs = -1   // which ES is decoded into subtitles (-1 = none)

    /** Set the subtitle track that is to be decoded and rendered (esIndex; -1 = none).
     *  Subtitles are NOT sent to libVLC — we decode and render them ourselves. */
    fun selectSubtitle(esIndex: Int) {
        subDecodeEs = esIndex
        subDecoder.reset()
    }

    private companion object {
        /** M715: ~5 s of packets of an HD channel; more is dropped (see [fromReader]). */
        const val MAX_PENDING = 512L
        const val RESYNC_MAX_SEC = 3L
        val VIDEO_TYPES = setOf("MPEG2VIDEO", "H264", "HEVC", "VP8", "VP9", "AV1", "MPEG4VIDEO", "THEORA")
        val TUNER_LOST = setOf("subscriptionOverridden", "noFreeAdapter")
    }

    /** subscriptionSpeed: 0 = pause, 100 = normal (positive FF, negative RW). */
    suspend fun setSpeed(speed: Int) {
        session.sendNoReply("subscriptionSpeed", mapOf(
            "subscriptionId" to subscriptionId.toLong(), "speed" to speed.toLong()))
    }

    /**
     * Relative seek within the buffer. Since we subscribe with 90khz=1, the server reads `time`
     * in 90 kHz ticks (htsp_server.c: skip.time = hs_90khz ? s64 : rescale). Negative =
     * backwards, positive = forwards. Without `absolute` => a relative seek.
     */
    suspend fun skip(seconds: Int) {
        val ticks = seconds.toLong() * 90000L
        session.sendNoReply("subscriptionSkip", mapOf(
            "subscriptionId" to subscriptionId.toLong(), "time" to ticks))
    }

    /**
     * Processes the subscription until subscriptionStop arrives, the connection dies (throws) or
     * the coroutine is cancelled. Always unsubscribes at the end.
     *
     * [onStatus]: M508-fix2 — the state of the timeshift buffer (`timeshiftStatus`, once per second):
     * [shift] = the CURRENT POSITION relative to live (0 = at live), [startPts]/[endPts] = the PTS of
     * the first and last frame in the buffer, all in 90 kHz ticks.
     */
    suspend fun run(
        onTs: suspend (ByteArray) -> Unit,
        onStatus: (shift: Long, full: Boolean, startPts: Long, endPts: Long) -> Unit = { _, _, _, _ -> },
        onStop: (String?) -> Unit = {},
        onSubtitles: (List<TsMuxer.SubtitleInfo>) -> Unit = {},
        onSubtitlePage: (DvbSubtitleDecoder.DecodedPage, Long) -> Unit = { _, _ -> },
        /** M552: the channel has a teletext track (from subscriptionStart). */
        onTeletextAvailable: (Boolean) -> Unit = {},
        /** M552: the teletext PES payload (the TELETEXT track does not go to libVLC, the app decodes it). */
        onTeletext: (ByteArray) -> Unit = {},
        /** M714: subscriptionStatus — [status] text and [error] code (e.g. "noFreeAdapter"); both null = OK again. */
        onSubStatus: (status: String?, error: String?) -> Unit = { _, _ -> },
        /**
         * M715 (R3): a running stream lost its tuner (subscriptionStop "subscriptionOverridden" /
         * "noFreeAdapter" — a recording or a client with a higher priority took it). Tvheadend keeps
         * the subscription and starts it again once a tuner is free (subscriptions.c reschedule);
         * the subscription therefore waits instead of ending. [run] returns when the server starts
         * it again, so the caller opens a fresh stream (a new timeline for libVLC).
         */
        onWaitingForTuner: ((String?) -> Unit)? = null
    ) {
        var teletextEs = -1   // M552
        var waitingForTuner = false   // M715
        try {
            while (true) {
                val m = try {
                    inbox.receive()
                } catch (e: ClosedReceiveChannelException) {
                    // the session was closed (connection lost) — the caller handles it like a broken stream
                    throw IllegalStateException("HTSP: connection closed")
                }
                when (m["method"] as? String) {
                    "subscriptionStart" -> {
                        // M715: the tuner is back after a takeover — end here, the caller starts afresh
                        if (waitingForTuner) return
                        @Suppress("UNCHECKED_CAST")
                        val sl = (m["streams"] as? List<Any?>) ?: emptyList()
                        val streams = sl.mapNotNull {
                            val sm = it as? Map<*, *> ?: return@mapNotNull null
                            val idx = (sm["index"] as? Long)?.toInt() ?: return@mapNotNull null
                            val typ = sm["type"] as? String ?: return@mapNotNull null
                            val lang = (sm["language"] as? String) ?: ""
                            val comp = (sm["composition_id"] as? Long)?.toInt() ?: 0
                            val anc = (sm["ancillary_id"] as? Long)?.toInt() ?: 0
                            val ch = (sm["channels"] as? Long)?.toInt() ?: 0
                            val sri = (sm["rate"] as? Long)?.toInt() ?: 0   // es_sri = sample-rate index
                            TsMuxer.Stream(idx, typ, lang, comp, anc, ch, sri)
                        }
                        // M552: teletext — the first track of type TELETEXT
                        teletextEs = streams.firstOrNull { it.type == "TELETEXT" }?.index ?: -1
                        hasVideo = streams.any { it.type in VIDEO_TYPES }   // M715
                        onTeletextAvailable(teletextEs >= 0)
                        val existing = muxer
                        if (existing == null) {
                            val mx = TsMuxer(streams)
                            muxer = mx
                            onSubtitles(mx.subtitleStreams())
                            if (mx.hasTracks()) onTs(mx.start())
                        } else {
                            // another subscriptionStart (e.g. after a seek) — keep a continuous
                            // timeline, just send PAT/PMT again
                            onTs(existing.start())
                        }
                    }
                    "muxpkt" -> {
                        pktOut++   // M715: every dequeued media packet, before any early continue
                        val mx = muxer ?: continue
                        val esBin = (m["payload"] as? Htsmsg.Bin) ?: continue   // M673: a slice without a copy
                        val streamIdx = (m["stream"] as? Long)?.toInt() ?: continue
                        val pts = m["pts"] as? Long
                        val dts = m["dts"] as? Long
                        if (streamIdx == teletextEs) { onTeletext(esBin.toByteArray()); continue }   // M552
                        if (streamIdx == subDecodeEs) {
                            // the selected subtitle track: we decode and render it ourselves (it does not go to libVLC)
                            val page = if (pts != null) subDecoder.decode(pts, esBin.toByteArray()) else null
                            if (page != null) {
                                val origin = mx.timelineOriginPts()
                                val targetMs = if (origin != null) (page.pts - origin) / 90L else page.pts / 90L
                                onSubtitlePage(page, targetMs)
                            }
                            continue
                        }
                        val rap = ((m["frametype"] as? Long)?.toInt() ?: 0) == 'I'.code
                        val ts = mx.mux(streamIdx, esBin.data, esBin.offset, esBin.length, pts, dts, rap)
                        if (ts.isNotEmpty()) onTs(ts)
                    }
                    "timeshiftStatus" -> {
                        val shift = (m["shift"] as? Long) ?: 0L
                        val full = ((m["full"] as? Long) ?: 0L) != 0L
                        // start/end are optional — when they are missing, the caller uses a fallback
                        val st = (m["start"] as? Long) ?: 0L
                        val en = (m["end"] as? Long) ?: 0L
                        onStatus(shift, full, st, en)
                    }
                    "subscriptionStatus" -> {
                        // M714: sent while the server cannot start the subscription (SMT_NOSTART:
                        // no free tuner, scrambled, bad signal...) and when packets flow again
                        onSubStatus(m["status"] as? String, m["subscriptionError"] as? String)
                    }
                    "subscriptionStop" -> {
                        val err = m["subscriptionError"] as? String
                        // M715 (R3): a running stream whose tuner was taken — wait for the restart
                        if (onWaitingForTuner != null && muxer != null && err in TUNER_LOST) {
                            waitingForTuner = true
                            onWaitingForTuner(err)
                            continue
                        }
                        onStop(err)
                        return
                    }
                }
            }
        } finally {
            session.endSubscription(this)
        }
    }
}
