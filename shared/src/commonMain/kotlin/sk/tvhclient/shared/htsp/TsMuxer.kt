package sk.tvhclient.shared.htsp

/**
 * Minimal MPEG-TS muxer. From the subscriptionStart tracks and HTSP muxpkt packets
 * (payload = raw ES, pts/dts in the 90kHz timebase — we subscribe asking for 90khz=1)
 * it assembles a valid TS stream that libVLC can demux from a local pipe.
 *
 * It generates PAT (PID 0) + PMT (PID 0x1000) periodically, PES packets with PTS/DTS and
 * PCR on the PCR PID (the first video track, otherwise the first track). It does not aim for perfection — the goal
 * is to get playback of an HTSP source through libVLC working (the basis for timeshift).
 *
 * Subtitles/unknown track types are skipped for now.
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
    private val siInterval = 20   // re-emit PAT/PMT after every N muxpkt

    /** M463: by how much the PCR leads the DTS (90 kHz). The server's stream (HTTP pass)
     *  keeps a stable 700-780 ms, we stick to that. */
    private val PCR_LEAD = 63_000L

    /** M659: the start of the output timeline (90 kHz, 1 s). Until now it started at 0 = the pts of
     *  the FIRST received packet. Two consequences: (1) PCR = DTS - PCR_LEAD was clamped to 0 for the first
     *  700 ms, so libVLC saw a standing PCR with rising PTS and derived pts_delay wrongly
     *  (resync, audio ahead of video after a channel switch); (2) the first packet is usually audio,
     *  video with a smaller DTS was clamped to 0 and the first frames had flattened timestamps. With the start
     *  at 1 s nothing gets clamped; libVLC's time is relative to the first pts, so the position,
     *  timeshift and subtitles do not shift ([timelineOriginPts] returns the original pts). */
    private val START_BASE = 90_000L

    /** M463: at most this many TS packets between two PCR marks. At 1080p50
     *  HEVC a keyframe is up to ~480 packets — without intermediate marks the decoder
     *  has no reference for half a second and its clock regulation goes off
     *  exactly once per GOP. The server's stream has a mark every ~235 packets. */
    private val PCR_MAX_GAP_PKT = 200

    /** M463: the DTS of the previous frame — for interpolating the PCR within a frame. */
    private var lastPcrDts: Long? = null
    /** M674: the last written PCR (90 kHz) — the PCR must not go backwards, not even after a PCR-only packet. */
    private var lastPcrOut: Long = -1L
    /** M674: if audio gets ahead of the last PCR (the video PID is silent — an outage, scrambling, waiting for a
     *  keyframe) by more than 250 ms, I send a PCR-only packet on the PCR PID so that libVLC's clock does not stall.
     *  250 ms: audio DTS normally leads video DTS by the B-frame reorder delay (up to ~120 ms)
     *  and after a seek (re-base) by frameGap + the PTS-DTS gap (~115 ms) — that is not a stall. */
    private val PCR_STALL_TICKS = 22_500L

    private val tracks = ArrayList<Track>()
    private var trackByEs: Map<Int, Track> = emptyMap()
    private val pendingSubs = ArrayList<Track>()   // DVB subtitles waiting for the first packet
    private val pcrPid: Int
    private var patCc = 0
    private var pmtCc = 0
    private var psiCounter = 0
    private var pmtVersion = 0
    private var selectedSubEs = -1   // only this subtitle track flows into libVLC (-1 = none)

    // Suppressing a pointless "clear" set right before "content": the broadcast sends a pair
    // (an empty clear + content ~120 ms behind it). In dense dialogue this doubles the number of
    // subtitle events and libVLC starts dropping them. We hold the clear back: if content arrives right away,
    // we discard it (content redraws the whole page anyway); if not (a real pause), we send it.
    private var heldClearPayload: ByteArray? = null
    private var heldClearTrack: Track? = null
    private var heldClearPts: Long? = null
    private var heldClearDts: Long? = null
    private val subClearHold = 27000L   // a ~300 ms window for a possible following content

    // Rewriting the timestamps onto a continuous output timeline — so that libVLC does not see a backwards/forwards
    // jump on subscriptionSkip (RW/FF). In normal live the offset is constant (= pass-through).
    private var hasOffset = false
    private var tsOffset = 0L
    private var lastOut = 0L
    private val discontTicks = 90000L * 4   // 4 s = discontinuity -> re-base
    private val frameGapTicks = 3000L        // a ~33 ms gap after a seek

    init {
        var nextPid = 0x1001
        for (s in streams) {
            if (s.type == "DVBSUB") {
                // We do NOT announce DVB subtitles right away — libVLC over a pipe crashes if the track is
                // silent at startup (subtitles come sparsely). We add it only when the first subtitle packet arrives.
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

    /** The timeline origin (the first pts), which mediaPlayer.time is aligned to. null = not yet known. */
    fun timelineOriginPts(): Long? = if (hasOffset) tsOffset + START_BASE else null   // M659: the original pts of the first packet


    /** Identification of a subtitle track from subscriptionStart (the complete list, independently
     *  of whether it has already "spoken" and is in libVLC). esIndex = the HTSP stream index. */
    data class SubtitleInfo(val esIndex: Int, val language: String)

    /** All DVB subtitle tracks of the channel (active as well as waiting), in stream index order.
     *  Used to build a complete menu that is the same on all devices. */
    fun subtitleStreams(): List<SubtitleInfo> {
        val subs = ArrayList<SubtitleInfo>()
        for (t in tracks) if (t.isSubtitle) subs.add(SubtitleInfo(t.esIndex, t.language))
        for (t in pendingSubs) subs.add(SubtitleInfo(t.esIndex, t.language))
        subs.sortBy { it.esIndex }
        return subs
    }

    /** Selection of the subtitle track that is actually to be sent to libVLC (esIndex; -1 = none).
     *  Only ever one is sent — the decoder mixes several DVB subtitles at once. */
    fun selectSubtitle(esIndex: Int) { selectedSubEs = esIndex }

    /** stream_type, PES stream_id, isVideo. null = unsupported type (skip). */
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

    /** The initial PAT+PMT (send right after subscriptionStart). */
    fun start(): ByteArray {
        psiCounter = siInterval
        return flatten(listOf(pat(), pmt()))
    }

    /** One muxpkt → TS bytes. Empty if the track is unsupported. */
    fun mux(esIndex: Int, payload: ByteArray, pts: Long?, dts: Long?, randomAccess: Boolean): ByteArray =
        mux(esIndex, payload, 0, payload.size, pts, dts, randomAccess)

    /**
     * M673: ES as a slice (off, len) of a bigger array (the body of the HTSP message) — video/audio is
     * copied out of it straight into TS packets without an intermediate copy (Htsmsg.Bin). Subtitles and AAC
     * (the ADTS wrapper) copy the slice, they are small packets.
     */
    fun mux(esIndex: Int, buf: ByteArray, off: Int, len: Int, pts: Long?, dts: Long?, randomAccess: Boolean): ByteArray {
        var t = trackByEs[esIndex]
        var activated = ByteArray(0)
        if (t == null) {
            val pend = pendingSubs.firstOrNull { it.esIndex == esIndex } ?: return ByteArray(0)
            // Subtitles: we push ONLY the one selected track into libVLC. Several DVB subtitle
            // streams at once get mixed by the decoder and every second set is dropped. Ignore unselected ones.
            if (esIndex != selectedSubEs) return ByteArray(0)
            pendingSubs.remove(pend)
            tracks.add(pend)
            trackByEs = tracks.associateBy { it.esIndex }
            pmtVersion = (pmtVersion + 1) and 0x1F
            t = pend
            activated = flatten(listOf(pat(), pmt()))
            psiCounter = siInterval
        } else if (t.isSubtitle && esIndex != selectedSubEs) {
            return ByteArray(0)   // the subtitle track is no longer selected -> do not mux
        }

        // Subtitles: the bare segments from HTSP have to be wrapped into a PES data-field; timing via
        // remapSub (it must not overwrite the common timeline). A pointless clear before content is suppressed.
        if (t.isSubtitle) {
            val payload = buf.copyOfRange(off, off + len)
            if (isSubtitleClear(payload)) {
                // hold the clear back; if we are already holding something, send that first
                var pre = ByteArray(0)
                val hp = heldClearPayload; val ht = heldClearTrack
                if (hp != null && ht != null) {
                    val (op, od) = remapSub(heldClearPts, heldClearDts)
                    pre = emitPes(ht, wrapDvbSub(hp), op, od, false)
                }
                heldClearPayload = payload; heldClearTrack = t
                heldClearPts = pts; heldClearDts = dts
                return activated + pre
            } else {
                heldClearPayload = null; heldClearTrack = null   // content -> the clear is not needed
                val (op, od) = remapSub(pts, dts)
                return activated + emitPes(t, wrapDvbSub(payload), op, od, false)
            }
        }

        // Video/audio: if we are holding a clear and the pause has elapsed (no content arrived), send it
        // at the current time, so it does not hang in the past. AAC: an ADTS header in front of every frame.
        var flushed = ByteArray(0)
        val hp = heldClearPayload; val ht = heldClearTrack; val hpts = heldClearPts
        if (hp != null && ht != null && pts != null && hpts != null && pts - hpts >= subClearHold) {
            val (op, od) = remapSub(pts, dts)
            flushed = emitPes(ht, wrapDvbSub(hp), op, od, false)
            heldClearPayload = null; heldClearTrack = null
        }
        val (op, od) = remap(pts, dts)
        val body = if (t.isAac) {
            val au = adtsWrap(t, buf.copyOfRange(off, off + len))
            emitPes(t, au, 0, au.size, op, od, randomAccess)
        } else emitPes(t, buf, off, len, op, od, randomAccess)
        // M454: `a + b` on a ByteArray creates a new array and copies everything into it.
        // flushed/activated are empty for an ordinary frame, so the concatenation
        // meant a pointless copy of the WHOLE TS block on every frame (a large
        // array -> Large Object Space -> GC every 2-3 s for ~1 s on a Mi Box).
        return if (flushed.isEmpty() && activated.isEmpty()) body
        else flushed + activated + body
    }

    /** Builds the PES of one track into TS packets (+ periodic PAT/PMT). */


    /**
     * M453: the result is assembled into a SINGLE array.
     *
     * Originally a separate array was allocated for every 188-byte TS packet, those
     * were collected into an ArrayList and flatten() copied them into the result once more.
     * At 1080p50 HEVC that is ~7000 allocations per second + doubled copying —
     * on a Cortex-A53 (Xiaomi Mi Box S) it ate a whole core (94 % CPU
     * in `top`, while the hardware decoder had 11 %). Now the exact size is computed,
     * one array is allocated and the packets are written straight into it.
     */
    private fun emitPes(t: Track, es: ByteArray, outPts: Long?, outDts: Long?, rap: Boolean): ByteArray =
        emitPes(t, es, 0, es.size, outPts, outDts, rap)

    private fun emitPes(t: Track, es: ByteArray, esOff: Int, esLen: Int, outPts: Long?, outDts: Long?, rap: Boolean): ByteArray {
        psiCounter -= 1
        val withPsi = psiCounter <= 0
        val patPkt = if (withPsi) pat() else null
        val pmtPkt = if (withPsi) pmt() else null
        if (withPsi) psiCounter = siInterval

        val hdrLen = buildPesHeader(t, esLen, outPts, outDts)   // M668: only the header, the ES is not copied
        val pesLen = hdrLen + esLen
        // M457: the PCR must LEAD the DTS by the value of the decoding buffer.
        // Originally the PCR equalled the frame's DTS — the decoder thus got the frame exactly
        // at the moment it was supposed to display it already, with no margin for decoding. Ordinary
        // frames survived that, but a 10-bit HEVC keyframe (70+ kB) did not make it
        // and the picture stuttered ONCE PER GOP, i.e. once a second (Xiaomi Mi Box S; the audio
        // meanwhile ran smoothly, which showed that the data was arriving fine and the
        // problem is in the timing). Stronger boxes did not feel it, because they decode
        // faster than the deadline elapses. On the HTTP path Tvheadend generates the PCR
        // with a margin — that is why the picture was smooth there.
        val dtsNow = if (t.pid == pcrPid) (outDts ?: outPts) else null
        // M674: the PCR never goes backwards (after a PCR-only packet derived from audio)
        val pcr = if (dtsNow != null) maxOf((dtsNow - PCR_LEAD).coerceAtLeast(0L), lastPcrOut) else null
        // M674: the video PID is silent and audio has already got ahead of the last PCR -> a PCR-only packet before this PES
        // audio only — subtitles have a PTS (the display time) far ahead of the video, the PCR cannot be derived from them
        val otherDts = if (dtsNow == null && !t.isSubtitle) (outDts ?: outPts) else null
        val prevPcrDts = lastPcrDts
        val stallPcr: Long? = if (otherDts != null && prevPcrDts != null && otherDts - prevPcrDts > PCR_STALL_TICKS) {
            lastPcrDts = otherDts
            maxOf((otherDts - PCR_LEAD).coerceAtLeast(0L), lastPcrOut)
        } else null
        // the frame duration for interpolation (the first frame: 20 ms as a reasonable estimate)
        val frameSpan = if (dtsNow != null) {
            val prev = lastPcrDts
            val d = if (prev != null && dtsNow > prev) dtsNow - prev else 1_800L
            lastPcrDts = dtsNow
            d
        } else 0L

        val psiLen = (patPkt?.size ?: 0) + (pmtPkt?.size ?: 0)
        val stallLen = if (stallPcr != null) 188 else 0
        val out = ByteArray(psiLen + stallLen + tsPacketCount(pesLen, pcr != null, rap) * 188)
        var off = 0
        patPkt?.let { it.copyInto(out, off); off += it.size }
        pmtPkt?.let { it.copyInto(out, off); off += it.size }
        if (stallPcr != null) { writePcrOnly(stallPcr, out, off); off += 188 }
        writePackets(t, pesHdr, hdrLen, es, esOff, esLen, pcr, frameSpan, rap, out, off)
        return out
    }

    /**
     * How many 188-byte TS packets a PES of the given size takes. It has to exactly
     * mirror the decisions in writePackets, otherwise the resulting array would not match.
     */
    private fun tsPacketCount(pesSize: Int, hasPcr: Boolean, rap: Boolean): Int {
        var pos = 0
        var first = true
        var count = 0
        var sincePcr = 0
        while (pos < pesSize) {
            val remaining = pesSize - pos
            val pcrHere = hasPcr && (first || sincePcr >= PCR_MAX_GAP_PKT)
            val indicators = pcrHere || (first && rap)
            val take = if (!indicators && remaining >= 184) 184
            else if (!indicators && remaining == 183) 183
            else {
                val mandatory = if (pcrHere) 7 else 1
                val avail = 184 - 1 - mandatory
                if (remaining >= avail) avail else remaining
            }
            pos += take
            count++
            sincePcr = if (pcrHere) 0 else sincePcr + 1
            first = false
        }
        return count
    }

    /** A DVB subtitle display-set without a region (0x11) and an object (0x13) = an empty "clear". */
    private fun isSubtitleClear(p: ByteArray): Boolean {
        var i = 0
        while (i + 6 <= p.size) {
            if ((p[i].toInt() and 0xFF) != 0x0F) break
            val type = p[i + 1].toInt() and 0xFF
            if (type == 0x11 || type == 0x13) return false   // has a region/object -> content
            val segLen = ((p[i + 4].toInt() and 0xFF) shl 8) or (p[i + 5].toInt() and 0xFF)
            i += 6 + segLen
        }
        return true   // no region/object -> an empty set (clear)
    }

    /** Wraps the bare DVB subtitle payload from HTSP back into the PES data-field format. */
    private fun wrapDvbSub(payload: ByteArray): ByteArray {
        val out = ByteArray(payload.size + 3)
        out[0] = 0x20                       // data_identifier
        out[1] = 0x00                       // subtitle_stream_id
        payload.copyInto(out, 2)
        out[out.size - 1] = 0xFF.toByte()   // end_of_PES_data_field_marker
        return out
    }

    /** Prepends a 7-byte ADTS header to a raw AAC frame (TVH sends it without ADTS).
     *  profile = AAC LC (object_type 2), sample-rate index and channels from subscriptionStart
     *  (rate = es_sri, channels). Without them the fallback is 48 kHz / 2 channels (usual for TV). */
    private fun adtsWrap(t: Track, au: ByteArray): ByteArray {
        val sfi = if (t.sampleRateIndex in 0..15) t.sampleRateIndex else 3   // 3 = 48 kHz
        val ch = if (t.channels in 1..7) t.channels else 2
        val frameLen = au.size + 7
        val out = ByteArray(au.size + 7)
        out[0] = 0xFF.toByte()
        out[1] = 0xF1.toByte()                                  // MPEG-4, no CRC
        out[2] = (((1 and 0x03) shl 6) or ((sfi and 0x0F) shl 2) or ((ch shr 2) and 0x01)).toByte()  // profile AAC LC=1
        out[3] = (((ch and 0x03) shl 6) or ((frameLen shr 11) and 0x03)).toByte()
        out[4] = ((frameLen shr 3) and 0xFF).toByte()
        out[5] = (((frameLen and 0x07) shl 5) or 0x1F).toByte() // + buffer_fullness hi
        out[6] = 0xFC.toByte()                                  // buffer_fullness lo + 1 block
        au.copyInto(out, 7)
        return out
    }

    /** Remap the subtitle time using the existing offset, without a re-base and without affecting the timeline. */
    private fun remapSub(pts: Long?, dts: Long?): Pair<Long?, Long?> {
        if (!hasOffset) return Pair(pts, dts)
        val outPts = pts?.let { (it - tsOffset).coerceAtLeast(0L) }
        val outDts = dts?.let { (it - tsOffset).coerceAtLeast(0L) }
        return Pair(outPts, outDts)
    }

    /** Remap the input pts/dts onto a continuous rising output timeline. */
    private fun remap(pts: Long?, dts: Long?): Pair<Long?, Long?> {
        val ref = pts ?: dts ?: return Pair(pts, dts)
        if (!hasOffset) { hasOffset = true; tsOffset = ref - START_BASE; lastOut = START_BASE }   // M659
        var out = ref - tsOffset
        if (out < lastOut - discontTicks || out > lastOut + discontTicks) {
            tsOffset = ref - (lastOut + frameGapTicks)   // re-base after a seek
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

    /** ES_info for a track: audio -> ISO_639_language_descriptor (0x0A); DVB subtitles ->
     *  subtitling_descriptor (0x59) with the language + composition/ancillary page id. */
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
        // M463: the registration descriptor for HEVC (05 04 "HEVC") — exactly as
        // Tvheadend sends it in the HTTP stream. Some demuxers/decoders use it to
        // recognize the format more reliably than by stream_type 0x24.
        if (t.isVideo && t.streamType == 0x24) {
            return byteArrayOf(0x05, 0x04, 0x48, 0x45, 0x56, 0x43)   // "HEVC"
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
            body.add((0xF0 or ((info.size ushr 8) and 0x0F)).toByte())  // ES_info_length (reserved 1111)
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

    /** A PSI section into a single TS packet (PUSI=1, AFC=01), the rest stuffing 0xFF. */
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

    /**
     * M449: the PES is assembled directly into a ByteArray.
     *
     * Originally it was an ArrayList<Byte> and every byte of the elementary stream was
     * added individually (`for (b in es) out.add(b)`) — that is boxing and then
     * unboxing again in toByteArray(). With ordinary frames (tens of kB) nobody
     * felt it, but a 10-bit HEVC keyframe is 300-600 kB and on a
     * weaker 32-bit CPU (Xiaomi Mi Box S, Cortex-A53) that took hundreds of ms.
     * Result: once per GOP the whole data path stopped and libVLC then reported a
     * batch of "picture is too late to be displayed (missing ~380 ms)". Now
     * exactly one array is allocated and the body is copied in one go (copyInto).
     */
    /** M454: a reused buffer for the PES — an array is not allocated for every frame. */
    /** M668: the PES header is built into a small array (max 19 B); the ES payload is no longer copied
     *  into the PES — writePackets takes it directly from the input. It saves one copy of the whole frame
     *  per muxpkt (300-600 kB for an HEVC keyframe) and the 64 kB+ working buffer. */
    private val pesHdr = ByteArray(19)

    /** Builds the PES header for an ES of length [esLen] into [pesHdr]; returns its length (9, 14 or 19). */
    private fun buildPesHeader(t: Track, esLen: Int, pts: Long?, dts: Long?): Int {
        val hasPts = pts != null
        // DVB subtitles must have only a PTS (a DTS is invalid for them and some stream sources
        // send it inconsistently -> sometimes the subtitle shows up, sometimes it does not). We force PTS-only.
        val hasDts = dts != null && dts != pts && !t.isSubtitle
        val ptsDtsFlags = if (hasPts && hasDts) 0xC0 else if (hasPts) 0x80 else 0x00
        val headerDataLen = if (hasPts && hasDts) 10 else if (hasPts) 5 else 0
        val pesPayloadLen = 3 + headerDataLen + esLen
        val lenField = if (t.isVideo) 0 else if (pesPayloadLen <= 0xFFFF) pesPayloadLen else 0
        val out = pesHdr
        out[0] = 0x00; out[1] = 0x00; out[2] = 0x01
        out[3] = (t.streamId and 0xFF).toByte()
        out[4] = ((lenField ushr 8) and 0xFF).toByte()
        out[5] = (lenField and 0xFF).toByte()
        // the '10' marker; for subtitles also data_alignment_indicator (0x04), so that libVLC correctly
        // delimits every subtitle display-set
        out[6] = (if (t.isSubtitle) 0x84 else 0x80).toByte()
        out[7] = (ptsDtsFlags and 0xC0).toByte()
        out[8] = (headerDataLen and 0xFF).toByte()
        var i = 9
        if (hasPts && hasDts) {
            i = putTimestamp(out, i, 0x3, pts!!)
            i = putTimestamp(out, i, 0x1, dts!!)
        } else if (hasPts) {
            i = putTimestamp(out, i, 0x2, pts!!)
        }
        return i
    }

    /** Writes a 5-byte timestamp at the given index, returns the new index. */
    private fun putTimestamp(out: ByteArray, at: Int, prefix: Int, ts: Long): Int {
        val v = ts and 0x1FFFFFFFFL                     // 33 bits
        out[at] = ((prefix shl 4) or ((((v ushr 30) and 0x07).toInt()) shl 1) or 0x01).toByte()
        out[at + 1] = ((v ushr 22) and 0xFF).toByte()
        out[at + 2] = (((((v ushr 15) and 0x7F).toInt()) shl 1) or 0x01).toByte()
        out[at + 3] = ((v ushr 7) and 0xFF).toByte()
        out[at + 4] = (((((v and 0x7F).toInt())) shl 1) or 0x01).toByte()
        return at + 5
    }

    // ---- TS packetization ----

    /** M453: writes the TS packets directly into `out` starting at index `startOff`. */
    /** M674: a TS packet with only an adaptation field and a PCR on the PCR PID (no payload, the CC is not incremented). */
    private fun writePcrOnly(pcrVal: Long, out: ByteArray, startOff: Int) {
        val pkt = TsPacketView(out, startOff)
        val cc = trackByPid(pcrPid)?.cc ?: 0
        pkt[0] = 0x47
        pkt[1] = ((pcrPid ushr 8) and 0x1F).toByte()
        pkt[2] = (pcrPid and 0xFF).toByte()
        pkt[3] = (0x20 or cc).toByte()          // AFC=10: adaptation field only
        pkt[4] = 183.toByte()                    // afLen = the rest of the packet
        pkt[5] = 0x10                            // PCR_flag
        val base = pcrVal and 0x1FFFFFFFFL
        pkt[6] = ((base ushr 25) and 0xFF).toByte()
        pkt[7] = ((base ushr 17) and 0xFF).toByte()
        pkt[8] = ((base ushr 9) and 0xFF).toByte()
        pkt[9] = ((base ushr 1) and 0xFF).toByte()
        pkt[10] = ((((base and 1L).toInt()) shl 7) or 0x7E).toByte()
        pkt[11] = 0x00
        for (i in 12 until 188) pkt[i] = 0xFF.toByte()
        lastPcrOut = pcrVal
    }

    private fun trackByPid(pid: Int): Track? = tracks.firstOrNull { it.pid == pid }

    /** M668: PES = header (hdrLen bytes) + ES — copied in parts directly into the TS packets. */
    private fun writePackets(t: Track, hdr: ByteArray, hdrLen: Int, es: ByteArray, esOff: Int, esLen: Int, pcrBase: Long?, frameSpan: Long, rap: Boolean, out: ByteArray, startOff: Int) {
        var pos = 0
        var first = true
        val pesLen = hdrLen + esLen
        val n = pesLen
        var base = startOff
        var sincePcr = 0
        var pcrIdx = 0
        // M463: how many packets the frame takes — for spreading the PCR evenly
        val totalPkts = if (pcrBase != null && t.pid == pcrPid) tsPacketCount(pesLen, true, rap) else 0
        while (pos < n) {
            val remaining = n - pos
            val pcrHere = pcrBase != null && t.pid == pcrPid && (first || sincePcr >= PCR_MAX_GAP_PKT)
            val rapHere = first && rap
            val indicators = pcrHere || rapHere

            val pkt = TsPacketView(out, base)
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
                    // M463: the value is interpolated across the frame, so that the PCR
                    // rises smoothly even inside a large keyframe
                    val interp = if (totalPkts > 1)
                        pcrBase!! + frameSpan * pcrIdx.toLong() / totalPkts.toLong()
                    else pcrBase!!
                    pcrIdx += PCR_MAX_GAP_PKT
                    lastPcrOut = interp   // M674
                    val base = interp and 0x1FFFFFFFFL
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
            // M668: a chunk of the PES from pos of length take is made up of the header and/or the ES — without an intermediate copy
            var dst = base + payloadStart
            var p = pos
            val end = pos + take
            if (p < hdrLen) {
                val h = minOf(end, hdrLen)
                hdr.copyInto(out, dst, p, h)
                dst += h - p; p = h
            }
            if (p < end) es.copyInto(out, dst, esOff + (p - hdrLen), esOff + (end - hdrLen))
            pos += take
            base += 188
            sincePcr = if (pcrHere) 0 else sincePcr + 1
            first = false
        }
    }

    /** Joining several packets (PAT/PMT at startup) — outside the hot path. */
    private fun flatten(packets: List<ByteArray>): ByteArray {
        var total = 0
        for (p in packets) total += p.size
        val res = ByteArray(total)
        var o = 0
        for (p in packets) { p.copyInto(res, o); o += p.size }
        return res
    }

    /**
     * A thin view of a 188-byte slice of the resulting array — so that the body of writePackets
     * can write through `pkt[i]` without allocating a separate array per packet.
     */
    private class TsPacketView(private val buf: ByteArray, private val off: Int) {
        operator fun set(i: Int, v: Byte) { buf[off + i] = v }
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
