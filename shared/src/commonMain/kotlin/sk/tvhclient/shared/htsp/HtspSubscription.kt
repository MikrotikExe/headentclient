package sk.tvhclient.shared.htsp

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException

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
     * Messages from the session reader. Bounded: when the consumer (libVLC via the TS queue) stops
     * reading, the reader waits — the same back pressure as the former own connection had (TCP),
     * only with a bigger reserve. Kept small on purpose: a muxpkt can carry a 100 kB key frame.
     */
    internal val inbox = Channel<Map<String, Any?>>(capacity = 256)

    private var muxer: TsMuxer? = null
    private val subDecoder = DvbSubtitleDecoder()
    @kotlin.concurrent.Volatile private var subDecodeEs = -1   // which ES is decoded into subtitles (-1 = none)

    /** Set the subtitle track that is to be decoded and rendered (esIndex; -1 = none).
     *  Subtitles are NOT sent to libVLC — we decode and render them ourselves. */
    fun selectSubtitle(esIndex: Int) {
        subDecodeEs = esIndex
        subDecoder.reset()
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
        onTeletext: (ByteArray) -> Unit = {}
    ) {
        var teletextEs = -1   // M552
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
                    "subscriptionStop" -> {
                        onStop(m["subscriptionError"] as? String)
                        return
                    }
                }
            }
        } finally {
            session.endSubscription(this)
        }
    }
}
