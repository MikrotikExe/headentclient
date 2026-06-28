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

    data class Stream(
        val index: Int,
        val type: String,
        val language: String = "",
        val compositionId: Int = 0,
        val ancillaryId: Int = 0,
        val channels: Int = 0,
        val sampleRateIndex: Int = 0
    )

    private class Track(
        val esIndex: Int,
        val pid: Int,
        val streamType: Int,
        val streamId: Int,
        val isVideo: Boolean,
        val language: String,
        val isSubtitle: Boolean = false,
        val compositionId: Int = 0,
        val ancillaryId: Int = 0,
        val channels: Int = 0,
        val sampleRateIndex: Int = 0
    ) {
        var cc = 0
        val isAac: Boolean get() = streamType == 0x0F
    }

    private val patPid = 0x0000
    private val pmtPid = 0x1000
    private val programNumber = 1
    private val siInterval = 20   // re-emit PAT/PMT po kazdych N muxpkt

    private val tracks = ArrayList<Track>()
    private var trackByEs: Map<Int, Track> = emptyMap()
    private val pendingSubs = ArrayList<Track>()   // DVB titulky cakajuce na prvy paket
    private val pcrPid: Int
    private var patCc = 0
    private var pmtCc = 0
    private var psiCounter = 0
    private var pmtVersion = 0

    // Prepis casovych znaciek na spojitu vystupnu os — aby libVLC nevidel spatny/dopredny
    // skok pri subscriptionSkip (RW/FF). Pri beznom zivom je offset konstantny (= pass-through).
    private var hasOffset = false
    private var tsOffset = 0L
    private var lastOut = 0L
    private val discontTicks = 90000L * 4   // 4 s = diskontinuita -> re-base
    private val frameGapTicks = 3000L        // ~33 ms medzera po skoku

    init {
        var nextPid = 0x1001
        for (s in streams) {
            if (s.type == "DVBSUB") {
                // DVB titulky NEohlasujeme hned — libVLC cez pipe spadne, ak stopa pri starte
                // mlci (titulky chodia riedko). Pridame ju az ked pride prvy titulkovy paket.
                pendingSubs.add(Track(
                    esIndex = s.index, pid = nextPid++, streamType = 0x06, streamId = 0xBD,
                    isVideo = false, language = s.language, isSubtitle = true,
                    compositionId = s.compositionId, ancillaryId = s.ancillaryId
                ))
                continue
            }
            val m = mapType(s.type) ?: continue
            tracks.add(Track(
                s.index, nextPid++, m.first, m.second, m.third, s.language,
                channels = s.channels, sampleRateIndex = s.sampleRateIndex
            ))
        }
        trackByEs = tracks.associateBy { it.esIndex }
        pcrPid = (tracks.firstOrNull { it.isVideo } ?: tracks.firstOrNull())?.pid ?: 0x1001
    }

    fun hasTracks(): Boolean = tracks.isNotEmpty()

    /** Identifikacia titulkovej stopy zo subscriptionStart (kompletny zoznam, nezavisle
     *  od toho ci uz "prehovorila" a je v libVLC). esIndex = HTSP stream index. */
    data class SubtitleInfo(val esIndex: Int, val language: String)

    /** Vsetky DVB titulkove stopy kanala (aktivne aj cakajuce), v poradi stream indexu.
     *  Pouziva sa na zostavenie kompletneho a na vsetkych zariadeniach rovnakeho menu. */
    fun subtitleStreams(): List<SubtitleInfo> {
        val subs = ArrayList<SubtitleInfo>()
        for (t in tracks) if (t.isSubtitle) subs.add(SubtitleInfo(t.esIndex, t.language))
        for (t in pendingSubs) subs.add(SubtitleInfo(t.esIndex, t.language))
        subs.sortBy { it.esIndex }
        return subs
    }

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
        var t = trackByEs[esIndex]
        var activated = ByteArray(0)
        if (t == null) {
            // Prvy paket cakajuceho titulku -> aktivuj LEN tuto jednu stopu a posli nove
            // PAT/PMT. Overene: pridat za behu viac titulkovych stop naraz (aj s datami)
            // zhadzuje libVLC do reconnect slucky; bezpecne je len pridanie po jednej.
            val pend = pendingSubs.firstOrNull { it.esIndex == esIndex } ?: return ByteArray(0)
            pendingSubs.remove(pend)
            tracks.add(pend)
            trackByEs = tracks.associateBy { it.esIndex }
            pmtVersion = (pmtVersion + 1) and 0x1F
            t = pend
            activated = flatten(listOf(pat(), pmt()))
            psiCounter = siInterval
        }
        // DVB titulky: Tvheadend posiela holé segmenty bez PES data-field obalu, treba
        // ho rekonstruovat (data_identifier 0x20 + subtitle_stream_id 0x00 + ... + 0xff).
        // AAC: Tvheadend posiela raw access-unit rámce, pred kazdy treba ADTS hlavicku.
        // Casovanie titulku NEsmie prepisat spolocnu os (remapSub), inak by skakalo video.
        val es = when {
            t.isSubtitle -> wrapDvbSub(payload)
            t.isAac -> adtsWrap(t, payload)
            else -> payload
        }
        val (outPts, outDts) = if (t.isSubtitle) remapSub(pts, dts) else remap(pts, dts)
        val packets = ArrayList<ByteArray>()
        psiCounter -= 1
        if (psiCounter <= 0) {
            packets.add(pat()); packets.add(pmt()); psiCounter = siInterval
        }
        val pes = buildPes(t, es, outPts, outDts)
        val pcr = if (t.pid == pcrPid) (outDts ?: outPts) else null
        writePackets(t, pes, pcr, randomAccess, packets)
        return activated + flatten(packets)
    }

    /** Obali holý DVB titulkový payload z HTSP späť do PES data-field formátu. */
    private fun wrapDvbSub(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 3)
        out[0] = 0x20                       // data_identifier
        out[1] = 0x00                       // subtitle_stream_id
        payload.copyInto(out, 2)
        out[out.size - 1] = 0xFF.toByte()   // end_of_PES_data_field_marker
        return out
    }

    /** Pred raw AAC rámec (TVH ho posiela bez ADTS) doplni 7-bajtovu ADTS hlavicku.
     *  profil = AAC LC (object_type 2), sample-rate index a kanaly zo subscriptionStart
     *  (rate = es_sri, channels). Bez nich fallback 48 kHz / 2 kanaly (bezne pre TV). */
    private fun adtsWrap(t: Track, au: ByteArray): ByteArray {
        val sfi = if (t.sampleRateIndex in 0..15) t.sampleRateIndex else 3   // 3 = 48 kHz
        val ch = if (t.channels in 1..7) t.channels else 2
        val frameLen = au.size + 7
        val out = ByteArray(au.size + 7)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte()                                  // MPEG-4, no CRC
        out[2] = (((1 and 0x03) shl 6) or ((sfi and 0x0F) shl 2) or ((ch shr 2) and 0x01)).toByte()  // profil AAC LC=1
        out[3] = (((ch and 0x03) shl 6) or ((frameLen shr 11) and 0x03)).toByte()
        out[4] = ((frameLen shr 3) and 0xFF).toByte()
        out[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte() // + buffer_fullness hi
        out[6] = 0xFC.toByte()                                  // buffer_fullness lo + 1 blok
        au.copyInto(out, 7)
        return out
    }

    /** Premap titulkoveho casu pomocou existujuceho offsetu, bez re-base a bez vplyvu na os. */
    private fun remapSub(pts: Long?, dts: Long?): Pair<Long?, Long?> {
        if (!hasOffset) return Pair(pts, dts)
        val outPts = pts?.let { (it - tsOffset).coerceAtLeast(0L) }
        val outDts = dts?.let { (it - tsOffset).coerceAtLeast(0L) }
        return Pair(outPts, outDts)
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

    /** ES_info pre stopu: audio -> ISO_639_language_descriptor (0x0A); DVB titulky ->
     *  subtitling_descriptor (0x59) s jazykom + composition/ancillary page id. */
    private fun esInfo(t: Track): ByteArray {
        if (t.isSubtitle && t.language.length == 3) {
            val l = t.language.lowercase()
            return byteArrayOf(
                0x59, 0x08,
                l[0].code.toByte(), l[1].code.toByte(), l[2].code.toByte(),
                0x10,                                       // subtitling_type = normal
                ((t.compositionId ushr 8) and 0xFF).toByte(), (t.compositionId and 0xFF).toByte(),
                ((t.ancillaryId ushr 8) and 0xFF).toByte(), (t.ancillaryId and 0xFF).toByte()
            )
        }
        if (!t.isVideo && t.language.length == 3) {
            val l = t.language.lowercase()
            return byteArrayOf(
                0x0A, 0x04,
                l[0].code.toByte(), l[1].code.toByte(), l[2].code.toByte(),
                0x00                                        // audio_type = undefined
            )
        }
        return ByteArray(0)
    }

    private fun pmt(): ByteArray {
        val body = ArrayList<Byte>()
        body.add(0x02)                                  // table_id PMT
        val esInfos = tracks.map { esInfo(it) }
        var esTotal = 0
        for (i in tracks.indices) esTotal += 5 + esInfos[i].size
        val sectionLen = 5 + 4 + esTotal + 4
        body.add((0xB0 or ((sectionLen ushr 8) and 0x0F)).toByte())
        body.add((sectionLen and 0xFF).toByte())
        body.add((programNumber ushr 8 and 0xFF).toByte())
        body.add((programNumber and 0xFF).toByte())
        body.add((0xC0 or ((pmtVersion and 0x1F) shl 1) or 0x01).toByte())  // version_number, current
        body.add(0x00); body.add(0x00)
        body.add((0xE0 or ((pcrPid ushr 8) and 0x1F)).toByte())
        body.add((pcrPid and 0xFF).toByte())
        body.add(0xF0.toByte()); body.add(0x00)         // program_info_length = 0
        for (i in tracks.indices) {
            val t = tracks[i]
            val info = esInfos[i]
            body.add((t.streamType and 0xFF).toByte())
            body.add((0xE0 or ((t.pid ushr 8) and 0x1F)).toByte())
            body.add((t.pid and 0xFF).toByte())
            body.add((0xF0 or ((info.size ushr 8) and 0x0F)).toByte())  // ES_info_length (rezerv. 1111)
            body.add((info.size and 0xFF).toByte())
            for (b in info) body.add(b)
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
        // DVB titulky musia mat len PTS (DTS je pre ne nevalidne a niektore stream zdroje
        // ho posielaju nekonzistentne -> raz sa titulok zobrazi, raz nie). Vynutime PTS-only.
        val hasDts = dts != null && dts != pts && !t.isSubtitle
        val ptsDtsFlags = if (hasPts && hasDts) 0xC0 else if (hasPts) 0x80 else 0x00
        val headerDataLen = if (hasPts && hasDts) 10 else if (hasPts) 5 else 0
        val out = ArrayList<Byte>(es.size + 14)
        out.add(0x00); out.add(0x00); out.add(0x01)
        out.add((t.streamId and 0xFF).toByte())
        val pesPayloadLen = 3 + headerDataLen + es.size
        val lenField = if (t.isVideo) 0 else if (pesPayloadLen <= 0xFFFF) pesPayloadLen else 0
        out.add(((lenField ushr 8) and 0xFF).toByte())
        out.add((lenField and 0xFF).toByte())
        // '10' marker; pre titulky aj data_alignment_indicator (0x04), aby libVLC spravne
        // ohranicil kazdy titulkovy display-set
        out.add((if (t.isSubtitle) 0x84 else 0x80).toByte())
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
