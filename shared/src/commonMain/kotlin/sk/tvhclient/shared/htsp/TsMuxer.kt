package sk.tvhclient.shared.htsp

/**
 * Minimalny MPEG-TS muxer. Zo subscriptionStart stop a HTSP muxpkt paketov
 * (payload = surove ES, pts/dts v 90kHz timebase — subscribe ziadame s 90khz=1)
 * sklada validny TS stream, ktory libVLC vie demuxovat z lokalneho pipe.
 *
 * Generuje PAT (PID 0) + PMT (PID 0x1000) periodicky, PES pakety s PTS/DTS a
 * PCR na PCR PID (prve video, inak prva stopa). Nesnazi sa o dokonalost — cielom
 * je rozbehat prehravanie HTSP zdroja cez libVLC (zaklad pre timeshift).
 *
 * Titulky/neznáme typy stop sa zatial preskakuju.
 */
class TsMuxer(streams: List<Stream>) {

    data class Stream(val index: Int, val type: String)

    private class Track(
        val esIndex: Int,
        val pid: Int,
        val streamType: Int,
        val streamId: Int,
        val isVideo: Boolean
    ) {
        var cc = 0
    }

    private val patPid = 0x0000
    private val pmtPid = 0x1000
    private val programNumber = 1
    private val siInterval = 20   // re-emit PAT/PMT po kazdych N muxpkt

    private val tracks: List<Track>
    private val trackByEs: Map<Int, Track>
    private val pcrPid: Int
    private var patCc = 0
    private var pmtCc = 0
    private var psiCounter = 0

    // Prepis casovych znaciek na spojitu vystupnu os — aby libVLC nevidel spatny/dopredny
    // skok pri subscriptionSkip (RW/FF). Pri beznom zivom je offset konstantny (= pass-through).
    private var hasOffset = false
    private var tsOffset = 0L
    private var lastOut = 0L
    private val discontTicks = 90000L * 4   // 4 s = diskontinuita -> re-base
    private val frameGapTicks = 3000L        // ~33 ms medzera po skoku

    init {
        var nextPid = 0x1001
        val list = ArrayList<Track>()
        for (s in streams) {
            val m = mapType(s.type) ?: continue
            list.add(Track(s.index, nextPid++, m.first, m.second, m.third))
        }
        tracks = list
        trackByEs = list.associateBy { it.esIndex }
        pcrPid = (tracks.firstOrNull { it.isVideo } ?: tracks.firstOrNull())?.pid ?: 0x1001
    }

    fun hasTracks(): Boolean = tracks.isNotEmpty()

    /** stream_type, PES stream_id, isVideo. null = nepodporovany typ (preskoc). */
    private fun mapType(t: String): Triple<Int, Int, Boolean>? = when (t) {
        "MPEG2VIDEO" -> Triple(0x02, 0xE0, true)
        "H264" -> Triple(0x1B, 0xE0, true)
        "HEVC" -> Triple(0x24, 0xE0, true)
        "MPEG2AUDIO" -> Triple(0x03, 0xC0, false)
        "AC3" -> Triple(0x81, 0xBD, false)
        "EAC3" -> Triple(0x87, 0xBD, false)
        "AAC" -> Triple(0x0F, 0xC0, false)
        else -> null
    }

    /** Uvodne PAT+PMT (poslat hned po subscriptionStart). */
    fun start(): ByteArray {
        psiCounter = siInterval
        return flatten(listOf(pat(), pmt()))
    }

    /** Jeden muxpkt → TS bajty. Prazdne ak je stopa nepodporovana. */
    fun mux(esIndex: Int, payload: ByteArray, pts: Long?, dts: Long?, randomAccess: Boolean): ByteArray {
        val t = trackByEs[esIndex] ?: return ByteArray(0)
        val (outPts, outDts) = remap(pts, dts)
        val packets = ArrayList<ByteArray>()
        psiCounter -= 1
        if (psiCounter <= 0) {
            packets.add(pat()); packets.add(pmt()); psiCounter = siInterval
        }
        val pes = buildPes(t, payload, outPts, outDts)
        val pcr = if (t.pid == pcrPid) (outDts ?: outPts) else null
        writePackets(t, pes, pcr, randomAccess, packets)
        return flatten(packets)
    }

    /** Premapuj vstupne pts/dts na spojitu rastucu vystupnu os. */
    private fun remap(pts: Long?, dts: Long?): Pair<Long?, Long?> {
        val ref = pts ?: dts ?: return Pair(pts, dts)
        if (!hasOffset) { hasOffset = true; tsOffset = ref; lastOut = 0L }
        var out = ref - tsOffset
        if (out < lastOut - discontTicks || out > lastOut + discontTicks) {
            tsOffset = ref - (lastOut + frameGapTicks)   // re-base po skoku
            out = ref - tsOffset
        }
        if (out > lastOut) lastOut = out
        val outPts = pts?.let { (it - tsOffset).coerceAtLeast(0L) }
        val outDts = dts?.let { (it - tsOffset).coerceAtLeast(0L) }
        return Pair(outPts, outDts)
    }

    // ---- PSI ----

    private fun pat(): ByteArray {
        val body = ArrayList<Byte>()
        body.add(0x00)                                  // table_id PAT
        val sectionLen = 5 + 4 + 4
        body.add((0xB0 or ((sectionLen ushr 8) and 0x0F)).toByte())
        body.add((sectionLen and 0xFF).toByte())
        body.add(0x00); body.add(0x01)                  // transport_stream_id = 1
        body.add(0xC1.toByte())                         // ver=0, current_next=1
        body.add(0x00); body.add(0x00)                  // section / last
        body.add(((programNumber ushr 8) and 0xFF).toByte())
        body.add((programNumber and 0xFF).toByte())
        body.add((0xE0 or ((pmtPid ushr 8) and 0x1F)).toByte())
        body.add((pmtPid and 0xFF).toByte())
        appendCrc(body)
        val cc = patCc; patCc = (patCc + 1) and 0x0F
        return psiToTs(patPid, body.toByteArray(), cc)
    }

    private fun pmt(): ByteArray {
        val body = ArrayList<Byte>()
        body.add(0x02)                                  // table_id PMT
        val sectionLen = 5 + 4 + tracks.size * 5 + 4
        body.add((0xB0 or ((sectionLen ushr 8) and 0x0F)).toByte())
        body.add((sectionLen and 0xFF).toByte())
        body.add(((programNumber ushr 8) and 0xFF).toByte())
        body.add((programNumber and 0xFF).toByte())
        body.add(0xC1.toByte())
        body.add(0x00); body.add(0x00)
        body.add((0xE0 or ((pcrPid ushr 8) and 0x1F)).toByte())
        body.add((pcrPid and 0xFF).toByte())
        body.add(0xF0.toByte()); body.add(0x00)         // program_info_length = 0
        for (t in tracks) {
            body.add((t.streamType and 0xFF).toByte())
            body.add((0xE0 or ((t.pid ushr 8) and 0x1F)).toByte())
            body.add((t.pid and 0xFF).toByte())
            body.add(0xF0.toByte()); body.add(0x00)     // ES_info_length = 0
        }
        appendCrc(body)
        val cc = pmtCc; pmtCc = (pmtCc + 1) and 0x0F
        return psiToTs(pmtPid, body.toByteArray(), cc)
    }

    private fun appendCrc(body: ArrayList<Byte>) {
        val crc = crc32(body.toByteArray())
        body.add(((crc ushr 24) and 0xFF).toByte())
        body.add(((crc ushr 16) and 0xFF).toByte())
        body.add(((crc ushr 8) and 0xFF).toByte())
        body.add((crc and 0xFF).toByte())
    }

    /** PSI sekcia do jedneho TS paketu (PUSI=1, AFC=01), zvysok stuffing 0xFF. */
    private fun psiToTs(pid: Int, section: ByteArray, cc: Int): ByteArray {
        val pkt = ByteArray(188) { 0xFF.toByte() }
        pkt[0] = 0x47
        pkt[1] = (0x40 or ((pid ushr 8) and 0x1F)).toByte()
        pkt[2] = (pid and 0xFF).toByte()
        pkt[3] = (0x10 or (cc and 0x0F)).toByte()
        pkt[4] = 0x00                                   // pointer_field
        var p = 5
        for (b in section) { pkt[p++] = b }
        return pkt
    }

    // ---- PES ----

    private fun buildPes(t: Track, es: ByteArray, pts: Long?, dts: Long?): ByteArray {
        val hasPts = pts != null
        val hasDts = dts != null && dts != pts
        val ptsDtsFlags = if (hasPts && hasDts) 0xC0 else if (hasPts) 0x80 else 0x00
        val headerDataLen = if (hasPts && hasDts) 10 else if (hasPts) 5 else 0
        val out = ArrayList<Byte>(es.size + 14)
        out.add(0x00); out.add(0x00); out.add(0x01)
        out.add((t.streamId and 0xFF).toByte())
        val pesPayloadLen = 3 + headerDataLen + es.size
        val lenField = if (t.isVideo) 0 else if (pesPayloadLen <= 0xFFFF) pesPayloadLen else 0
        out.add(((lenField ushr 8) and 0xFF).toByte())
        out.add((lenField and 0xFF).toByte())
        out.add(0x80.toByte())                          // '10' marker
        out.add((ptsDtsFlags and 0xC0).toByte())
        out.add((headerDataLen and 0xFF).toByte())
        if (hasPts && hasDts) {
            addTimestamp(out, 0x3, pts!!)
            addTimestamp(out, 0x1, dts!!)
        } else if (hasPts) {
            addTimestamp(out, 0x2, pts!!)
        }
        for (b in es) out.add(b)
        return out.toByteArray()
    }

    private fun addTimestamp(out: ArrayList<Byte>, prefix: Int, ts: Long) {
        val v = ts and 0x1FFFFFFFFL                     // 33 bitov
        out.add(((prefix shl 4) or ((((v ushr 30) and 0x07).toInt()) shl 1) or 0x01).toByte())
        out.add(((v ushr 22) and 0xFF).toByte())
        out.add((((((v ushr 15) and 0x7F).toInt()) shl 1) or 0x01).toByte())
        out.add(((v ushr 7) and 0xFF).toByte())
        out.add((((((v and 0x7F).toInt())) shl 1) or 0x01).toByte())
    }

    // ---- TS packetizacia ----

    private fun writePackets(t: Track, pes: ByteArray, pcrBase: Long?, rap: Boolean, packets: MutableList<ByteArray>) {
        var pos = 0
        var first = true
        val n = pes.size
        while (pos < n) {
            val remaining = n - pos
            val pcrHere = first && pcrBase != null && t.pid == pcrPid
            val rapHere = first && rap
            val indicators = pcrHere || rapHere

            val pkt = ByteArray(188)
            pkt[0] = 0x47
            var b1 = (t.pid ushr 8) and 0x1F
            if (first) b1 = b1 or 0x40                  // PUSI
            pkt[1] = b1.toByte()
            pkt[2] = (t.pid and 0xFF).toByte()
            val cc = t.cc; t.cc = (t.cc + 1) and 0x0F

            val payloadStart: Int
            val take: Int
            if (!indicators && remaining >= 184) {
                pkt[3] = (0x10 or cc).toByte()          // AFC=01
                payloadStart = 4
                take = 184
            } else if (!indicators && remaining == 183) {
                pkt[3] = (0x30 or cc).toByte()          // AFC=11, afLen=0
                pkt[4] = 0x00
                payloadStart = 5
                take = 183
            } else {
                pkt[3] = (0x30 or cc).toByte()          // AFC=11
                val mandatory = if (pcrHere) 7 else 1   // flags(+pcr)
                val payloadAvail = 184 - 1 - mandatory
                take = if (remaining >= payloadAvail) payloadAvail else remaining
                val stuffing = payloadAvail - take
                val afContentLen = mandatory + stuffing
                pkt[4] = afContentLen.toByte()
                var idx = 5
                var flags = 0
                if (rapHere) flags = flags or 0x40
                if (pcrHere) flags = flags or 0x10
                pkt[idx++] = flags.toByte()
                if (pcrHere) {
                    val base = pcrBase!! and 0x1FFFFFFFFL
                    pkt[idx++] = ((base ushr 25) and 0xFF).toByte()
                    pkt[idx++] = ((base ushr 17) and 0xFF).toByte()
                    pkt[idx++] = ((base ushr 9) and 0xFF).toByte()
                    pkt[idx++] = ((base ushr 1) and 0xFF).toByte()
                    pkt[idx++] = ((((base and 1L).toInt()) shl 7) or 0x7E).toByte()
                    pkt[idx++] = 0x00
                }
                var s = stuffing
                while (s > 0) { pkt[idx++] = 0xFF.toByte(); s-- }
                payloadStart = 5 + afContentLen
            }
            pes.copyInto(pkt, payloadStart, pos, pos + take)
            pos += take
            packets.add(pkt)
            first = false
        }
    }

    private fun flatten(packets: List<ByteArray>): ByteArray {
        var total = 0
        for (p in packets) total += p.size
        val res = ByteArray(total)
        var o = 0
        for (p in packets) { p.copyInto(res, o); o += p.size }
        return res
    }

    private fun crc32(data: ByteArray): Long {
        var crc = 0xFFFFFFFFL
        for (byte in data) {
            crc = crc xor ((byte.toLong() and 0xFF) shl 24)
            for (i in 0 until 8) {
                crc = if ((crc and 0x80000000L) != 0L) ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL
                else (crc shl 1) and 0xFFFFFFFFL
            }
        }
        return crc and 0xFFFFFFFFL
    }
}
