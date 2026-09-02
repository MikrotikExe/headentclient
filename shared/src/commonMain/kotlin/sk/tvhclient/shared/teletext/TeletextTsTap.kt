package sk.tvhclient.shared.teletext

/**
 * M552 — vyberie z MPEG-TS teletextový elementárny stream (stream_type 0x06 s
 * deskriptorom 0x56) a jeho PES payload podáva do [TeletextDecoder]. Používa sa
 * v HTTP režime, kde TS tečie priamo do libVLC a appka si otvorí druhé,
 * krátkodobé spojenie len kvôli teletextu.
 */
class TeletextTsTap(private val decoder: TeletextDecoder) {

    /** PID teletextu; -1 = ešte neznámy. */
    var teletextPid: Int = -1
        private set
    /** PMT už prečítaná — ak je [teletextPid] stále -1, kanál teletext nevysiela. */
    var pmtSeen: Boolean = false
        private set
    private var pmtPid = -1
    private val pes = ByteArrayBuilder()
    private var pesOpen = false
    private val carry = ByteArrayBuilder()

    fun feed(buf: ByteArray, off: Int = 0, len: Int = buf.size - off) {
        // zarovnanie na 188 B pakety cez prenos zvyšku
        carry.append(buf, off, len)
        val data = carry.toByteArray()
        var p = 0
        while (p + 188 <= data.size) {
            if (data[p] != 0x47.toByte()) { p++; continue }   // resync
            packet(data, p)
            p += 188
        }
        carry.reset()
        if (p < data.size) carry.append(data, p, data.size - p)
    }

    private fun packet(d: ByteArray, o: Int) {
        val pusi = (d[o + 1].toInt() and 0x40) != 0
        val pid = ((d[o + 1].toInt() and 0x1F) shl 8) or (d[o + 2].toInt() and 0xFF)
        val afc = (d[o + 3].toInt() shr 4) and 0x3
        if (afc == 0 || afc == 2) return   // bez payloadu
        var p = o + 4
        if (afc == 3) p += 1 + (d[o + 4].toInt() and 0xFF)
        val end = o + 188
        if (p >= end) return
        when {
            pid == 0 -> if (pusi) pat(d, p + 1 + (d[p].toInt() and 0xFF), end)
            pid == pmtPid -> if (pusi) pmt(d, p + 1 + (d[p].toInt() and 0xFF), end)
            pid == teletextPid -> pesData(d, p, end, pusi)
        }
    }

    private fun pat(d: ByteArray, s: Int, end: Int) {
        if (s + 8 > end || d[s].toInt() != 0x00) return
        val len = ((d[s + 1].toInt() and 0x0F) shl 8) or (d[s + 2].toInt() and 0xFF)
        var p = s + 8
        val stop = minOf(s + 3 + len - 4, end)
        while (p + 4 <= stop) {
            val prog = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            val pidv = ((d[p + 2].toInt() and 0x1F) shl 8) or (d[p + 3].toInt() and 0xFF)
            if (prog != 0) { pmtPid = pidv; return }
            p += 4
        }
    }

    private fun pmt(d: ByteArray, s: Int, end: Int) {
        if (s + 12 > end || d[s].toInt() != 0x02) return
        val len = ((d[s + 1].toInt() and 0x0F) shl 8) or (d[s + 2].toInt() and 0xFF)
        val stop = minOf(s + 3 + len - 4, end)
        val pil = ((d[s + 10].toInt() and 0x0F) shl 8) or (d[s + 11].toInt() and 0xFF)
        var p = s + 12 + pil
        while (p + 5 <= stop) {
            val st = d[p].toInt() and 0xFF
            val epid = ((d[p + 1].toInt() and 0x1F) shl 8) or (d[p + 2].toInt() and 0xFF)
            val il = ((d[p + 3].toInt() and 0x0F) shl 8) or (d[p + 4].toInt() and 0xFF)
            if (st == 0x06 && teletextPid < 0) {
                var q = p + 5
                val qe = minOf(q + il, stop)
                while (q + 2 <= qe) {
                    val tag = d[q].toInt() and 0xFF
                    val tl = d[q + 1].toInt() and 0xFF
                    if (tag == 0x56 || tag == 0x46) { teletextPid = epid; break }   // 0x46 = VBI teletext
                    q += 2 + tl
                }
            }
            p += 5 + il
        }
        pmtSeen = true
    }

    private fun pesData(d: ByteArray, p: Int, end: Int, pusi: Boolean) {
        if (pusi) {
            flushPes()
            pesOpen = true
        }
        if (pesOpen) pes.append(d, p, end - p)
    }

    private fun flushPes() {
        if (!pesOpen) return
        pesOpen = false
        val b = pes.toByteArray(); pes.reset()
        // 00 00 01 BD, len(2), flags(2), header_data_length(1)
        if (b.size < 9 || b[0].toInt() != 0 || b[1].toInt() != 0 || b[2].toInt() != 1) return
        val hdl = b[8].toInt() and 0xFF
        val start = 9 + hdl
        if (start >= b.size) return
        decoder.feedPes(b, start, b.size - start)
    }

    /** Jednoduchý rastúci buffer (common kód, bez java.io). */
    private class ByteArrayBuilder {
        private var buf = ByteArray(4096)
        private var size = 0
        fun append(src: ByteArray, off: Int, len: Int) {
            if (len <= 0) return
            if (size + len > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + len))
            src.copyInto(buf, size, off, off + len)
            size += len
        }
        fun toByteArray(): ByteArray = buf.copyOf(size)
        fun reset() { size = 0 }
    }
}
