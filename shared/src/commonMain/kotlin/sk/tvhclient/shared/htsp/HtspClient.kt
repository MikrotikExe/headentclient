package sk.tvhclient.shared.htsp

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readByteArray
import io.ktor.utils.io.writeByteArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * HTSP klient pre Tvheadend (port 9982). Prenos jadra z pluginu (htsp.py):
 * handshake (hello), SHA1 digest auth, enableAsyncMetadata dump kanalov,
 * tagov, EPG a DVR. Streaming sa NEROBI cez HTSP (ostava HTTP), tu len
 * metadata transport ako alternativa k /api endpointom.
 *
 * Pouziva ktor-network coroutine sockety (funguju na Android aj iOS).
 */
class HtspClient(
    private val host: String,
    private val port: Int = 9982,
    private val user: String = "",
    private val pwd: String = ""
) {
    private var selector: SelectorManager? = null
    private var socket: Socket? = null
    private var read: ByteReadChannel? = null
    private var write: ByteWriteChannel? = null
    private var seq = 0
    private val writeMutex = Mutex()
    private var streamSubId: Int = -1
    private var liveMuxer: TsMuxer? = null   // aktivny muxer streamu (pre pôvod časovej osi)
    private val subDecoder = DvbSubtitleDecoder()
    private var subDecodeEs = -1             // ktory ES sa dekóduje na titulky (-1 = ziadny)

    /** Nastav titulkovu stopu, ktora sa ma dekódovať a vykresľovať (esIndex; -1 = ziadna).
     *  Titulky sa do libVLC NEposielaju — dekódujeme a renderujeme ich sami. */
    fun selectSubtitle(esIndex: Int) {
        subDecodeEs = esIndex
        subDecoder.reset()
    }

    var serverName: String? = null
        private set
    var serverVersion: Long? = null
        private set
    var serverSwVersion: String? = null
        private set
    var serverCapabilities: List<String> = emptyList()
        private set
    private var challenge: ByteArray? = null

    data class Metadata(
        val channels: List<Map<String, Any?>>,
        val tags: List<Map<String, Any?>>,
        val events: List<Map<String, Any?>>,
        val dvr: List<Map<String, Any?>>,
        val syncDone: Boolean
    )

    suspend fun connect() {
        val sel = SelectorManager(Dispatchers.Default)
        selector = sel
        val s = withTimeoutOrNull(8_000) {
            aSocket(sel).tcp().connect(host, port)
        } ?: run {
            sel.close()
            throw IllegalStateException("port $port nedostupný (timeout) — je HTSP forwardnutý?")
        }
        socket = s
        read = s.openReadChannel()
        write = s.openWriteChannel(autoFlush = true)
        val ok = withTimeoutOrNull(8_000) {
            hello()
            auth()
        } ?: run {
            close()
            throw IllegalStateException("HTSP handshake timeout (odpovedá port HTSP serverom?)")
        }
        if (!ok) {
            close()
            throw IllegalStateException("HTSP autentifikácia zlyhala")
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Throwable) {}
        try { selector?.close() } catch (_: Throwable) {}
        socket = null; selector = null; read = null; write = null
    }

    private suspend fun send(method: String, args: Map<String, Any?> = emptyMap(), withSeq: Boolean = true): Int {
        val msg = HashMap<String, Any?>(args)
        msg["method"] = method
        return writeMutex.withLock {
            var s = -1
            if (withSeq) {
                seq += 1
                s = seq
                msg["seq"] = s.toLong()
            }
            write!!.writeByteArray(Htsmsg.serialize(msg))
            write!!.flush()
            s
        }
    }

    private suspend fun recv(): Map<String, Any?> {
        val r = read!!
        val hdr = r.readByteArray(4)
        val len = ((hdr[0].toInt() and 0xFF) shl 24) or
                ((hdr[1].toInt() and 0xFF) shl 16) or
                ((hdr[2].toInt() and 0xFF) shl 8) or
                (hdr[3].toInt() and 0xFF)
        val body = if (len > 0) r.readByteArray(len) else ByteArray(0)
        return Htsmsg.deserializeMap(body)
    }

    private suspend fun recvReply(s: Int, maxN: Int = 400): Map<String, Any?> {
        repeat(maxN) {
            val m = recv()
            if ((m["seq"] as? Long)?.toInt() == s) return m
        }
        throw IllegalStateException("HTSP: nedorazila odpoveď seq=$s")
    }

    private suspend fun hello() {
        val s = send("hello", mapOf(
            "htspversion" to 35L,
            "clientname" to "HeadentClient/1.0.0",
            "clientversion" to "1.0.0"
        ))
        val r = recvReply(s)
        serverVersion = r["htspversion"] as? Long
        serverSwVersion = r["serverversion"] as? String
        serverName = r["servername"] as? String
        challenge = r["challenge"] as? ByteArray
        @Suppress("UNCHECKED_CAST")
        serverCapabilities = (r["servercapability"] as? List<Any?>)
            ?.mapNotNull { it as? String } ?: emptyList()
    }

    private suspend fun auth(): Boolean {
        val ch = challenge
        val s = if (pwd.isNotEmpty() && ch != null) {
            val digest = Sha1.digest(pwd.encodeToByteArray() + ch)
            send("authenticate", mapOf("username" to user, "digest" to digest))
        } else {
            send("authenticate", mapOf("username" to user))
        }
        val r = recvReply(s)
        // noaccess=1 => zamietnute
        return ((r["noaccess"] as? Long) ?: 0L) == 0L
    }

    /**
     * Nacita metadata cez enableAsyncMetadata. Cita spravy kym nepride
     * initialSyncCompleted (+ pri EPG kratky idle), potom vypne async.
     * epgMaxDays obmedzi EPG (0 = bez limitu). channelsOnly = rychla cesta.
     */
    /**
     * Program pre jeden kanal (HTSP getEvents) — synchronny reply, ovela
     * rychlejsie nez cely async EPG dump. Vrati zoznam event map.
     */
    suspend fun getEvents(channelId: Long, numFollowing: Int = 60, maxTime: Long = 0): List<Map<String, Any?>> {
        val args = HashMap<String, Any?>()
        args["channelId"] = channelId
        if (numFollowing > 0) args["numFollowing"] = numFollowing.toLong()
        if (maxTime > 0) args["maxTime"] = maxTime
        val s = send("getEvents", args)
        val r = recvReply(s)
        @Suppress("UNCHECKED_CAST")
        val evs = r["events"] as? List<Any?> ?: return emptyList()
        return evs.mapNotNull { it as? Map<String, Any?> }
    }

    suspend fun fetchMetadata(
        withEpg: Boolean = false,
        epgMaxDays: Int = 2,
        channelsOnly: Boolean = false,
        nowSec: Long,
        overallTimeoutMs: Long = if (withEpg) 120_000 else 45_000
    ): Metadata {
        val args = HashMap<String, Any?>()
        args["epg"] = if (withEpg) 1L else 0L
        if (withEpg && epgMaxDays > 0) {
            args["epgMaxTime"] = nowSec + epgMaxDays * 86400L
        }
        send("enableAsyncMetadata", args, withSeq = false)

        val channels = ArrayList<Map<String, Any?>>()
        val tags = ArrayList<Map<String, Any?>>()
        val events = ArrayList<Map<String, Any?>>()
        val dvr = ArrayList<Map<String, Any?>>()
        var syncDone = false

        val result = withTimeoutOrNull(overallTimeoutMs) {
            // fast path (bez EPG): koniec hned po initialSyncCompleted.
            // EPG path: cita kym chodia spravy; ked 8s ticho -> koniec.
            val idleMs = 8_000L
            while (true) {
                if (channelsOnly && channels.isNotEmpty() && dvr.isNotEmpty()) break
                if (syncDone && !withEpg) break
                val m = if (withEpg) withTimeoutOrNull(idleMs) { recv() } else recv()
                if (m == null) break  // idle timeout pri EPG -> hotovo
                when (m["method"] as? String) {
                    "channelAdd" -> channels.add(m)
                    "tagAdd" -> tags.add(m)
                    "eventAdd" -> events.add(m)
                    "dvrEntryAdd" -> dvr.add(m)
                    "initialSyncCompleted" -> {
                        syncDone = true
                        if (!withEpg) break
                    }
                }
            }
            true
        }
        try { send("disableAsyncMetadata", emptyMap(), withSeq = false) } catch (_: Throwable) {}

        return Metadata(channels, tags, events, dvr, syncDone || result == true)
    }

    /**
     * M162 — zivy HTSP subscription premuxovany na MPEG-TS. Po subscriptionStart
     * postavi TsMuxer a cez `onTs` posiela TS bajty (PAT/PMT + PES). Ziada 90khz
     * timebase a normts (vhodne pre PES). timeshiftPeriodSec>0 zapne aj server
     * buffer (pre buduce ovladanie); 0 = ciste zive. Bezi kym sa korutina nezrusi
     * alebo nepride subscriptionStop. Na konci unsubscribe.
     */
    suspend fun streamSubscribe(
        channelId: Long,
        timeshiftPeriodSec: Int = 0,
        profile: String? = null,
        onTs: suspend (ByteArray) -> Unit,
        onStatus: (shiftUs: Long, full: Boolean) -> Unit = { _, _ -> },
        onStop: (String?) -> Unit = {},
        onSubtitles: (List<TsMuxer.SubtitleInfo>) -> Unit = {},
        onSubtitlePage: (DvbSubtitleDecoder.DecodedPage, Long) -> Unit = { _, _ -> }
    ) {
        seq += 1
        val subId = seq
        streamSubId = subId
        val args = HashMap<String, Any?>()
        args["channelId"] = channelId
        args["subscriptionId"] = subId.toLong()
        args["90khz"] = 1L
        args["normts"] = 1L
        if (timeshiftPeriodSec > 0) args["timeshiftPeriod"] = timeshiftPeriodSec.toLong()
        if (!profile.isNullOrBlank()) args["profile"] = profile
        send("subscribe", args, withSeq = false)

        var muxer: TsMuxer? = null
        try {
            while (true) {
                val m = recv()
                val sid = (m["subscriptionId"] as? Long)?.toInt()
                if (sid != null && sid != subId) continue
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
                        val existing = muxer
                        if (existing == null) {
                            val mx = TsMuxer(streams)
                            muxer = mx
                            liveMuxer = mx
                            onSubtitles(mx.subtitleStreams())
                            if (mx.hasTracks()) onTs(mx.start())
                        } else {
                            // dalsi subscriptionStart (napr. po skoku) — zachovaj spojitu
                            // casovu os, len znova posli PAT/PMT
                            onTs(existing.start())
                        }
                    }
                    "muxpkt" -> {
                        val mx = muxer ?: continue
                        val es = (m["payload"] as? ByteArray) ?: continue
                        val streamIdx = (m["stream"] as? Long)?.toInt() ?: continue
                        val pts = m["pts"] as? Long
                        val dts = m["dts"] as? Long
                        if (streamIdx == subDecodeEs) {
                            // vybrana titulkova stopa: dekóduj a renderuj sami (do libVLC nejde)
                            val page = if (pts != null) subDecoder.decode(pts, es) else null
                            if (page != null) {
                                val origin = mx.timelineOriginPts()
                                val targetMs = if (origin != null) (page.pts - origin) / 90L else page.pts / 90L
                                onSubtitlePage(page, targetMs)
                            }
                            continue
                        }
                        val rap = ((m["frametype"] as? Long)?.toInt() ?: 0) == 'I'.code
                        val ts = mx.mux(streamIdx, es, pts, dts, rap)
                        if (ts.isNotEmpty()) onTs(ts)
                    }
                    "timeshiftStatus" -> {
                        val shift = (m["shift"] as? Long) ?: 0L
                        val full = ((m["full"] as? Long) ?: 0L) != 0L
                        onStatus(shift, full)
                    }
                    "subscriptionStop" -> {
                        onStop(m["subscriptionError"] as? String)
                        return
                    }
                }
            }
        } finally {
            streamSubId = -1
            try { send("unsubscribe", mapOf("subscriptionId" to subId.toLong()), withSeq = false) } catch (_: Throwable) {}
        }
    }

    /** subscriptionSpeed: 0 = pauza, 100 = normal (kladne FF, zaporne RW). */
    /** M408: keepalive — lahka poziadavka, ktora drzi spojenie nazive. Tvheadend
     *  odpovie na akukolvek metodu; getDiskSpace nic nemeni a je nenarocna.
     *  Odpoved ignorujeme, ide len o to, aby na spojeni tiekla prevadzka. */
    suspend fun keepAlive() {
        // M408-fix: poslat BEZ seq — server potom neposle reply, ktora by inak
        // dorazila do prijimacej slucky streamu a po chvili ju zasekla (kanal
        // po minute-dvoch prestal nabiehat). Bez seq staci na udrzanie spojenia.
        runCatching { send("getDiskSpace", withSeq = false) }
    }

    suspend fun setSpeed(speed: Int) {
        val id = streamSubId
        if (id <= 0) return
        send("subscriptionSpeed", mapOf("subscriptionId" to id.toLong(), "speed" to speed.toLong()), withSeq = false)
    }

    /**
     * Relativny skok v bufferi. Kedze subscribujeme s 90khz=1, server cita `time`
     * v 90 kHz tikoch (htsp_server.c: skip.time = hs_90khz ? s64 : rescale). Zaporne =
     * vzad, kladne = vpred. Bez `absolute` => relativny skok.
     */
    suspend fun skip(seconds: Int) {
        val id = streamSubId
        if (id <= 0) return
        val ticks = seconds.toLong() * 90000L
        send("subscriptionSkip", mapOf("subscriptionId" to id.toLong(), "time" to ticks), withSeq = false)
    }
}
