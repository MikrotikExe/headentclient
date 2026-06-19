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

    var serverName: String? = null
        private set
    var serverVersion: Long? = null
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
        var s = -1
        if (withSeq) {
            seq += 1
            s = seq
            msg["seq"] = s.toLong()
        }
        write!!.writeByteArray(Htsmsg.serialize(msg))
        write!!.flush()
        return s
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
            "clientname" to "tvhclient-android",
            "clientversion" to "1.0"
        ))
        val r = recvReply(s)
        serverVersion = r["htspversion"] as? Long
        serverName = r["servername"] as? String
        challenge = r["challenge"] as? ByteArray
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

    /** Vysledok diagnostiky HTSP subscription s timeshiftom (M159). */
    data class TimeshiftProbe(
        val started: Boolean,
        val channelId: Long,
        val streams: List<String>,     // "index:type" zo subscriptionStart
        val muxPackets: Long,
        val totalBytes: Long,
        val timeshiftSeen: Boolean,
        val tsFull: Boolean,
        val tsStart: Long?,
        val tsEnd: Long?,
        val statuses: List<String>,
        val stopped: String?           // dovod subscriptionStop, ak prisiel
    )

    /**
     * M159 — overenie datoveho toku: otvori HTSP subscription so zapnutym
     * server-side timeshiftom (timeshiftPeriod), pocita prijate muxpkt a
     * zachyti subscriptionStart (stopy), timeshiftStatus a subscriptionStatus.
     * Nic neprehrava — len overuje, ze data tecu a server timeshift drzi.
     * Spojenie je dedikovane pre tento test; po dobehu ho zavri (close()).
     */
    suspend fun probeTimeshift(
        channelId: Long,
        timeshiftPeriodSec: Int = 3600,
        durationMs: Long = 8_000,
        profile: String? = null
    ): TimeshiftProbe {
        seq += 1
        val subId = seq
        val args = HashMap<String, Any?>()
        args["channelId"] = channelId
        args["subscriptionId"] = subId.toLong()
        args["timeshiftPeriod"] = timeshiftPeriodSec.toLong()
        args["normts"] = 1L
        if (!profile.isNullOrBlank()) args["profile"] = profile
        send("subscribe", args, withSeq = false)

        val streams = ArrayList<String>()
        val statuses = ArrayList<String>()
        var started = false
        var muxPackets = 0L
        var totalBytes = 0L
        var timeshiftSeen = false
        var tsFull = false
        var tsStart: Long? = null
        var tsEnd: Long? = null
        var stopped: String? = null

        withTimeoutOrNull(durationMs) {
            while (true) {
                val m = recv()
                val sid = (m["subscriptionId"] as? Long)?.toInt()
                if (sid != null && sid != subId) continue
                when (m["method"] as? String) {
                    "subscriptionStart" -> {
                        started = true
                        @Suppress("UNCHECKED_CAST")
                        val sl = m["streams"] as? List<Any?> ?: emptyList()
                        for (s in sl) {
                            val sm = s as? Map<*, *> ?: continue
                            val idx = sm["index"] as? Long
                            val typ = sm["type"] as? String
                            streams.add("$idx:$typ")
                        }
                    }
                    "muxpkt" -> {
                        muxPackets += 1
                        (m["payload"] as? ByteArray)?.let { totalBytes += it.size }
                    }
                    "timeshiftStatus" -> {
                        timeshiftSeen = true
                        tsFull = ((m["full"] as? Long) ?: 0L) != 0L
                        tsStart = m["start"] as? Long
                        tsEnd = m["end"] as? Long
                    }
                    "subscriptionStatus" -> {
                        val st = m["status"] as? String
                        val err = m["subscriptionError"] as? String
                        if (!st.isNullOrBlank() || !err.isNullOrBlank()) {
                            statuses.add((st ?: "") + (err?.let { " err=$it" } ?: ""))
                        }
                    }
                    "subscriptionStop" -> {
                        stopped = (m["subscriptionError"] as? String) ?: "stop"
                        return@withTimeoutOrNull
                    }
                }
            }
            @Suppress("UNREACHABLE_CODE") Unit
        }
        try { send("unsubscribe", mapOf("subscriptionId" to subId.toLong()), withSeq = false) } catch (_: Throwable) {}

        return TimeshiftProbe(
            started, channelId, streams, muxPackets, totalBytes,
            timeshiftSeen, tsFull, tsStart, tsEnd, statuses, stopped
        )
    }
}
