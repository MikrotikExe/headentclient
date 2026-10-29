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
    /** M732 (audit R3): the muxer's audio-minus-video input pts offset in ms, null = unknown. */
    fun avDeltaMs(): Long? = muxer?.avDeltaMs()
    /** M732: media packets waiting for the pipe right now. */
    fun pendingPackets(): Long = pktIn - pktOut

    /** M732: when the first media packet arrived (epoch s), 0 = none yet — the session holds the EPG burst until the player has a buffer. */
    @kotlin.concurrent.Volatile internal var firstDataSec = 0L
        private set
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
            if (firstDataSec == 0L) firstDataSec = currentTimeSeconds()
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
        // M737 (audit R4): the decoder belongs to the run() loop — resetting it from the UI thread
        // raced with a decode in progress; the loop resets it before its next packet instead
        subResetPending = true
    }
    @kotlin.concurrent.Volatile private var subResetPending = false

    internal companion object {
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
        /** M722: the type of the first video track (from subscriptionStart), e.g. "MPEG2VIDEO", and
         *  M730: its picture height in lines (0 = the server does not know it yet). */
        onVideoCodec: (codec: String, height: Int) -> Unit = { _, _ -> },
        /** M724: the source of the stream (subscriptionStart: sourceinfo + the tracks) for the info window. */
        onSourceInfo: (HtspSourceInfo) -> Unit = {},
        /** M724: signalStatus (about once a second) for the info window. */
        onSignal: (HtspSignal) -> Unit = {},
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
                        streams.firstOrNull { it.type in VIDEO_TYPES }?.let { v ->   // M722 / M730
                            val vm = sl.firstOrNull { (it as? Map<*, *>)?.get("index") == v.index.toLong() } as? Map<*, *>
                            onVideoCodec(v.type, (vm?.get("height") as? Long)?.toInt() ?: 0)
                        }
                        onSourceInfo(HtspSourceInfo.from(m, sl))   // M724
                        val existing = muxer
                        if (existing == null) {
                            val mx = TsMuxer(streams)
                            muxer = mx
                            onSubtitles(mx.subtitleStreams())
                            if (mx.hasTracks()) onTs(mx.start())
                        } else {
                            // another subscriptionStart (e.g. after a seek) — keep a continuous
                            // timeline, just send PAT/PMT again; M732 (audit B2): with a different
                            // stream list (the service restarted with other tracks) the tracks are
                            // rebuilt first and the subtitle menu refreshed
                            if (existing.replaceStreams(streams)) onSubtitles(existing.subtitleStreams())
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
                            if (subResetPending) { subResetPending = false; subDecoder.reset() }
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
                    "signalStatus" -> {   // M724
                        onSignal(HtspSignal(
                            status = m["feStatus"] as? String,
                            snr = (m["feSNR"] as? Long)?.toInt() ?: -1,
                            signal = (m["feSignal"] as? Long)?.toInt() ?: -1,
                            ber = (m["feBER"] as? Long)?.toInt() ?: -1,
                            unc = (m["feUNC"] as? Long)?.toInt() ?: -1
                        ))
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

/**
 * M724: what the server says about the stream's source in subscriptionStart (`sourceinfo`, htsp.c)
 * and the first video / audio track — shown in the player's info window ("Stream" section).
 */
data class HtspSourceInfo(
    val adapter: String?,
    val network: String?,
    val mux: String?,
    val provider: String?,
    val service: String?,
    val satpos: String?,
    val videoType: String?,
    val width: Int,
    val height: Int,
    val audioType: String?,
    val audioChannels: Int,
    val audioRate: Int
) {
    companion object {
        /** HTSP `rate` of an audio track is the MPEG sample-rate index (es_sri). */
        private val SRI = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

        fun from(m: Map<String, Any?>, streams: List<Any?>): HtspSourceInfo {
            val si = m["sourceinfo"] as? Map<*, *>
            fun s(k: String) = (si?.get(k) as? String)?.takeIf { it.isNotBlank() }
            val maps = streams.mapNotNull { it as? Map<*, *> }
            val v = maps.firstOrNull { (it["type"] as? String) in HtspSubscription.VIDEO_TYPES }
            val a = maps.firstOrNull { (it["type"] as? String) in AUDIO_TYPES }
            val sri = (a?.get("rate") as? Long)?.toInt() ?: -1
            return HtspSourceInfo(
                adapter = s("adapter"), network = s("network"), mux = s("mux"),
                provider = s("provider"), service = s("service"), satpos = s("satpos"),
                videoType = v?.get("type") as? String,
                width = (v?.get("width") as? Long)?.toInt() ?: 0,
                height = (v?.get("height") as? Long)?.toInt() ?: 0,
                audioType = a?.get("type") as? String,
                audioChannels = (a?.get("channels") as? Long)?.toInt() ?: 0,
                audioRate = if (sri in SRI.indices) SRI[sri] else 0
            )
        }

        val AUDIO_TYPES = setOf("MPEG2AUDIO", "AC3", "EAC3", "AAC", "AAC-LATM", "VORBIS", "OPUS", "AC4")
    }
}

/** M724: HTSP signalStatus. [snr] and [signal] are relative 0..65535 (as Kodi shows them, in %), -1 = unknown. */
data class HtspSignal(val status: String?, val snr: Int, val signal: Int, val ber: Int, val unc: Int)
