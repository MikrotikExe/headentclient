package sk.tvhclient.shared.htsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M659: casovanie vystupu TsMuxera — PCR sa nesmie orezavat na 0 a video, ktore pride
 * po audiu s mensim DTS, nesmie dostat splostene znacky. Test rozobera vyrobeny TS
 * (188 B pakety, adaptation field s PCR, PES hlavicka s PTS/DTS) bez libVLC.
 */
class TsMuxerTimingTest {

    private data class Pes(val pid: Int, val pts: Long?, val dts: Long?)

    private class Parsed {
        val pcr = ArrayList<Pair<Int, Long>>()   // (pid, pcr base 90 kHz)
        val pes = ArrayList<Pes>()
    }

    private fun parse(ts: ByteArray): Parsed {
        val out = Parsed()
        assertEquals(0, ts.size % 188, "TS musi byt nasobok 188 B")
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
        // audio ako prvy paket (typicke pre Tvheadend), video s DTS o 10 ms skor
        all += m.mux(2, es, pts = 1_000_000L, dts = null, randomAccess = false)
        var dts = 990_000L
        for (i in 0 until 30) {
            all += m.mux(1, es, pts = dts + 7_200L, dts = dts, randomAccess = i == 0)
            dts += 3_600L
        }
        val parsed = parse(all)
        assertTrue(parsed.pcr.isNotEmpty(), "video snimky musia niest PCR")
        assertTrue(parsed.pcr.all { it.second > 0L }, "PCR sa nesmie orezat na 0 (M659): ${parsed.pcr.take(5)}")
        var last = -1L
        for ((_, v) in parsed.pcr) { assertTrue(v >= last, "PCR musi byt neklesajuce"); last = v }
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
        assertTrue(video.dts!! > 0L, "video DTS pred prvym audiom sa nesmie orezat na 0")
        // rozdiel medzi audio PTS a video DTS musi ostat 10 000 tikov ako na vstupe
        assertEquals(10_000L, audio.pts!! - video.dts!!)
        assertEquals(7_200L, video.pts!! - video.dts!!)
    }

    @Test
    fun timelineOriginIsFirstInputPts() {
        val m = muxer()
        m.start()
        m.mux(2, es, pts = 1_234_567L, dts = null, randomAccess = false)
        // titulky sa synchronizuju cez (pts - origin) — origin musi byt povodny vstupny pts
        assertEquals(1_234_567L, m.timelineOriginPts())
    }

    @Test
    fun rebaseAfterJumpStaysContinuous() {
        val m = muxer()
        var all = m.start()
        var dts = 5_000_000L
        for (i in 0 until 5) { all += m.mux(1, es, pts = dts + 3_600L, dts = dts, randomAccess = i == 0); dts += 3_600L }
        val before = parse(all).pes.last { it.pid == 0x1001 }.dts!!
        // skok o 10 minut (subscriptionSkip) -> vystupna os musi pokracovat tesne za predoslym
        val jumped = m.mux(1, es, pts = dts + 54_000_000L + 3_600L, dts = dts + 54_000_000L, randomAccess = true)
        val after = parse(jumped).pes.first { it.pid == 0x1001 }.dts!!
        assertTrue(after > before && after - before < 90_000L, "po skoku ma os pokracovat plynulo: $before -> $after")
    }
}
