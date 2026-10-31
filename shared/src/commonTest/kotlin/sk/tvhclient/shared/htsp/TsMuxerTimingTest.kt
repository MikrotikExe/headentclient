package sk.tvhclient.shared.htsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M659: timing of the TsMuxer output — the PCR must not be clipped to 0, and video that
 * arrives after audio with a smaller DTS must not get flattened timestamps. The test takes apart
 * the produced TS (188 B packets, adaptation field with PCR, PES header with PTS/DTS) without libVLC.
 */
class TsMuxerTimingTest {

    private data class Pes(val pid: Int, val pts: Long?, val dts: Long?)

    private class Parsed {
        val pcr = ArrayList<Pair<Int, Long>>()   // (pid, pcr base 90 kHz)
        val pes = ArrayList<Pes>()
    }

    private fun parse(ts: ByteArray): Parsed {
        val out = Parsed()
        assertEquals(0, ts.size % 188, "TS must be a multiple of 188 B")
        var off = 0
        while (off + 188 <= ts.size) {
            val p = ts.copyOfRange(off, off + 188)
            off += 188
            assertEquals(0x47, p[0].toInt() and 0xFF, "sync byte")
            val pusi = (p[1].toInt() and 0x40) != 0
            val pid = ((p[1].toInt() and 0x1F) shl 8) or (p[2].toInt() and 0xFF)
            val afc = (p[3].toInt() shr 4) and 0x03
            var pos = 4
            if (afc == 2 || afc == 3) {
                val afLen = p[4].toInt() and 0xFF
                if (afLen > 0) {
                    val flags = p[5].toInt() and 0xFF
                    if ((flags and 0x10) != 0) {   // PCR_flag
                        var base = 0L
                        for (i in 0 until 4) base = (base shl 8) or (p[6 + i].toLong() and 0xFF)
                        base = (base shl 1) or ((p[10].toLong() and 0x80) ushr 7)
                        out.pcr.add(pid to base)
                    }
                }
                pos = 5 + afLen
            }
            if (afc == 1 || afc == 3) {
                if (pusi && pos + 9 <= 188 && p[pos].toInt() == 0 && p[pos + 1].toInt() == 0 && p[pos + 2].toInt() == 1) {
                    val ptsDtsFlags = (p[pos + 7].toInt() shr 6) and 0x03
                    var pts: Long? = null
                    var dts: Long? = null
                    var q = pos + 9
                    if (ptsDtsFlags >= 2) { pts = ts33(p, q); q += 5 }
                    if (ptsDtsFlags == 3) dts = ts33(p, q)
                    out.pes.add(Pes(pid, pts, dts))
                }
            }
        }
        return out
    }

    private fun ts33(p: ByteArray, q: Int): Long {
        val b0 = p[q].toLong() and 0xFF
        val b1 = p[q + 1].toLong() and 0xFF
        val b2 = p[q + 2].toLong() and 0xFF
        val b3 = p[q + 3].toLong() and 0xFF
        val b4 = p[q + 4].toLong() and 0xFF
        return ((b0 and 0x0E) shl 29) or (b1 shl 22) or ((b2 and 0xFE) shl 14) or (b3 shl 7) or (b4 ushr 1)
    }

    private fun muxer() = TsMuxer(listOf(
        TsMuxer.Stream(index = 1, type = "H264"),
        TsMuxer.Stream(index = 2, type = "MPEG2AUDIO", language = "slk")
    ))

    private val es = ByteArray(400) { (it and 0x7F).toByte() }

    @Test
    fun pcrNeverZeroAndMonotonic() {
        val m = muxer()
        var all = m.start()
        // audio as the first packet (typical for Tvheadend), video with a DTS 10 ms earlier
        all += m.mux(2, es, pts = 1_000_000L, dts = null, randomAccess = false)
        var dts = 990_000L
        for (i in 0 until 30) {
            all += m.mux(1, es, pts = dts + 7_200L, dts = dts, randomAccess = i == 0)
            dts += 3_600L
        }
        val parsed = parse(all)
        assertTrue(parsed.pcr.isNotEmpty(), "video frames must carry PCR")
        assertTrue(parsed.pcr.all { it.second > 0L }, "PCR must not be clamped to 0 (M659): ${parsed.pcr.take(5)}")
        var last = -1L
        for ((_, v) in parsed.pcr) { assertTrue(v >= last, "PCR must be non-decreasing"); last = v }
    }

    @Test
    fun videoBeforeAudioKeepsRelativeTiming() {
        val m = muxer()
        var all = m.start()
        all += m.mux(2, es, pts = 1_000_000L, dts = null, randomAccess = false)
        all += m.mux(1, es, pts = 997_200L, dts = 990_000L, randomAccess = true)
        val parsed = parse(all)
        val audio = parsed.pes.first { it.pid == 0x1002 }
        val video = parsed.pes.first { it.pid == 0x1001 }
        assertNotNull(video.dts)
        assertTrue(video.dts!! > 0L, "video DTS before the first audio must not be clamped to 0")
        // the difference between the audio PTS and the video DTS must stay at 10 000 ticks, as on the input
        assertEquals(10_000L, audio.pts!! - video.dts!!)
        assertEquals(7_200L, video.pts!! - video.dts!!)
    }

    @Test
    fun timelineOriginIsFirstInputPts() {
        val m = muxer()
        m.start()
        m.mux(2, es, pts = 1_234_567L, dts = null, randomAccess = false)
        // subtitles are synchronized via (pts - origin) — origin must be the original input pts
        assertEquals(1_234_567L, m.timelineOriginPts())
    }

    @Test
    fun rebaseAfterJumpStaysContinuous() {
        val m = muxer()
        var all = m.start()
        var dts = 5_000_000L
        for (i in 0 until 5) { all += m.mux(1, es, pts = dts + 3_600L, dts = dts, randomAccess = i == 0); dts += 3_600L }
        val before = parse(all).pes.last { it.pid == 0x1001 }.dts!!
        // a jump of 10 minutes (subscriptionSkip) -> the output axis must continue right after the previous one
        val jumped = m.mux(1, es, pts = dts + 54_000_000L + 3_600L, dts = dts + 54_000_000L, randomAccess = true)
        val after = parse(jumped).pes.first { it.pid == 0x1001 }.dts!!
        assertTrue(after > before && after - before < 90_000L, "the timeline must continue smoothly after a jump: $before -> $after")
    }

    /** M674: video is silent for > 250 ms, audio keeps running -> PCR-only packets on the video PID, the PCR keeps growing. */
    @Test
    fun pcrKeepsRunningWhenVideoStalls() {
        val m = muxer()
        var all = m.start()
        var vdts = 1_000_000L
        for (i in 0 until 5) { all += m.mux(1, es, pts = vdts + 7_200L, dts = vdts, randomAccess = i == 0); vdts += 3_600L }
        val beforeStall = parse(all)
        val lastVideoPcr = beforeStall.pcr.last().second
        // 2 s of audio only (every 24 ms), video is stalled
        var apts = vdts
        var audioOnly = ByteArray(0)
        for (i in 0 until 80) { audioOnly += m.mux(2, es, pts = apts, dts = null, randomAccess = false); apts += 2_160L }
        val stall = parse(audioOnly)
        assertTrue(stall.pcr.isNotEmpty(), "PCR-only packets must be sent while video is stalled")
        assertTrue(stall.pcr.all { it.first == 0x1001 }, "PCR stays on the original PCR PID (video)")
        var last = lastVideoPcr
        for ((_, v) in stall.pcr) { assertTrue(v >= last, "PCR must keep rising even without video: $last -> $v"); last = v }
        // the spacing of the PCR-only packets is ~500 ms (M732, was 250 ms), not on every audio packet
        assertTrue(stall.pcr.size in 3..6, "PCR-only paketov: ${stall.pcr.size}")
        // video returns: the PCR does not go backwards
        val resumed = parse(m.mux(1, es, pts = apts + 7_200L, dts = apts, randomAccess = true))
        assertTrue(resumed.pcr.first().second >= last, "PCR must not go backwards after video returns")
    }

    /** M674: a PCR-only packet = just an adaptation field, without a payload; it does not change the CC of the video track. */
    @Test
    fun pcrOnlyPacketHasNoPayloadAndKeepsCc() {
        val m = muxer()
        m.start()
        val video = m.mux(1, es, pts = 1_007_200L, dts = 1_000_000L, randomAccess = true)
        val out = m.mux(2, es, pts = 1_000_000L + 60_000L, dts = null, randomAccess = false)   // audio 667 ms ahead of the video (M732: > 500 ms)
        val first = out.copyOfRange(0, 188)
        // M743 (audit R14): the CC of the PCR-only packet = the CC of the last video packet (no payload, no increment)
        var lastVideoCc = -1
        var o = 0
        while (o + 188 <= video.size) {
            val vpid = ((video[o + 1].toInt() and 0x1F) shl 8) or (video[o + 2].toInt() and 0xFF)
            if (vpid == 0x1001) lastVideoCc = video[o + 3].toInt() and 0x0F
            o += 188
        }
        assertTrue(lastVideoCc >= 0)
        assertEquals(lastVideoCc, first[3].toInt() and 0x0F, "PCR-only CC repeats the last video CC")
        assertEquals(0x47, first[0].toInt() and 0xFF)
        val pid = ((first[1].toInt() and 0x1F) shl 8) or (first[2].toInt() and 0xFF)
        assertEquals(0x1001, pid, "the first packet is PCR-only on the video PID")
        assertEquals(0x2, (first[3].toInt() shr 4) and 0x3, "AFC=10: len adaptation field")
        assertEquals(183, first[4].toInt() and 0xFF)
        assertEquals(0x10, first[5].toInt() and 0xFF, "PCR_flag")
        // the next video packet continues with the CC that the PCR-only packet did not advance
        val nextVideo = parse(m.mux(1, es, pts = 1_010_800L, dts = 1_003_600L, randomAccess = false))
        assertTrue(nextVideo.pes.any { it.pid == 0x1001 })
    }

    /** M732 (audit R4): a frame with a DTS but no PTS gets PTS = DTS instead of no timestamp. */
    @Test
    fun dtsWithoutPtsBecomesPts() {
        val m = muxer()
        m.start()
        val out = parse(m.mux(1, es, pts = null, dts = 1_000_000L, randomAccess = true))
        val v = out.pes.first { it.pid == 0x1001 }
        assertNotNull(v.pts)
        assertEquals(null, v.dts, "PTS only — no separate DTS")
    }

    /** M732 (audit B2): a second subscriptionStart with other tracks rebuilds the PMT; the same list is a no-op. */
    @Test
    fun replaceStreamsRebuildsOnlyOnChange() {
        val m = muxer()
        m.start()
        assertTrue(!m.replaceStreams(listOf(TsMuxer.Stream(index = 1, type = "H264"), TsMuxer.Stream(index = 2, type = "MPEG2AUDIO", language = "slk"))))
        assertTrue(m.replaceStreams(listOf(TsMuxer.Stream(index = 3, type = "HEVC"), TsMuxer.Stream(index = 4, type = "AC3", language = "eng"))))
        // the new indexes are muxed, the old ones are not
        assertTrue(m.mux(3, es, pts = 1_007_200L, dts = 1_000_000L, randomAccess = true).isNotEmpty())
        assertEquals(0, m.mux(1, es, pts = 1_010_800L, dts = 1_003_600L, randomAccess = false).size)
        assertTrue(m.hasTracks())
    }

    /** M732 (audit R5): AAC-LATM is announced in the PMT like AAC (the server hands over ADTS frames). */
    @Test
    fun aacLatmIsATrack() {
        val m = TsMuxer(listOf(TsMuxer.Stream(index = 1, type = "H264"), TsMuxer.Stream(index = 2, type = "AAC-LATM", language = "eng")))
        m.start()
        assertTrue(m.mux(2, es, pts = 1_000_000L, dts = null, randomAccess = false).isNotEmpty())
    }

    /** M737 (audit K4): a PMT larger than one TS packet (many audio tracks) is split, not overrun. */
    @Test
    fun pmtWithManyAudioTracksSpansSeveralPackets() {
        val streams = ArrayList<TsMuxer.Stream>()
        streams.add(TsMuxer.Stream(index = 1, type = "H264"))
        for (i in 0 until 24) streams.add(TsMuxer.Stream(index = 2 + i, type = "AC3", language = "eng"))
        val m = TsMuxer(streams)
        val out = m.start()
        assertEquals(0, out.size % 188)
        // PAT (1 packet) + PMT (24 x 11 + 5 + 16 bytes > 183 -> 2 packets)
        assertTrue(out.size / 188 >= 3, "packets: ${out.size / 188}")
        val pmtPackets = (0 until out.size / 188).map { out.copyOfRange(it * 188, it * 188 + 188) }
            .filter { ((it[1].toInt() and 0x1F) shl 8 or (it[2].toInt() and 0xFF)) == 0x1000 }
        assertTrue(pmtPackets.size >= 2)
        assertEquals(0x40, pmtPackets[0][1].toInt() and 0x40, "first PMT packet has PUSI")
        assertEquals(0, pmtPackets[1][1].toInt() and 0x40, "continuation has no PUSI")
        // the continuity counters of the PMT packets follow each other
        val cc0 = pmtPackets[0][3].toInt() and 0x0F
        val cc1 = pmtPackets[1][3].toInt() and 0x0F
        assertEquals((cc0 + 1) and 0x0F, cc1)
        // and muxing a packet of the last audio track works
        assertTrue(m.mux(25, es, pts = 1_000_000L, dts = null, randomAccess = false).isNotEmpty())
    }
}
