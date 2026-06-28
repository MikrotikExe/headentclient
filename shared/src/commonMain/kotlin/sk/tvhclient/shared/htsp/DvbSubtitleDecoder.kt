package sk.tvhclient.shared.htsp

/**
 * Vlastný dekodér DVB titulkov (ETSI EN 300 743) — portovaný z overeného FFmpeg
 * libavcodec/dvbsubdec.c. Vstup: holé titulkové segmenty z HTSP (jeden display-set
 * na volanie decode()). Výstup: zložená RGBA stránka (DecodedPage) pripravená na
 * vykreslenie ako overlay nad videom. libVLC sa titulkov vôbec nedotýka, takže
 * o zobrazení rozhodujeme len my a nič sa nezahadzuje.
 *
 * Farby sú v ARGB_8888 (0xAARRGGBB), priamo použiteľné pre Android Bitmap.
 */
class DvbSubtitleDecoder {

    /** Zložená titulková stránka. pixels==null alebo isEmpty=true znamená "skry titulok". */
    class DecodedPage(
        val pts: Long,
        val timeoutMs: Int,
        val width: Int,
        val height: Int,
        val pixels: IntArray?,
        val isEmpty: Boolean
    )

    private class Clut {
        var version = -1
        val clut4 = IntArray(4)
        val clut16 = IntArray(16)
        val clut256 = IntArray(256)
    }

    private class Region {
        var version = -1
        var width = 0
        var height = 0
        var depth = 0
        var clutId = 0
        var bgcolor = 0
        var pbuf = ByteArray(0)   // CLUT indexy
    }

    private class ObjDisplay(val objectId: Int, val regionId: Int, val xPos: Int, val yPos: Int)
    private class PageDisplay(val regionId: Int, val xPos: Int, val yPos: Int)

    private val regions = HashMap<Int, Region>()
    private val cluts = HashMap<Int, Clut>()
    private val objectDisplays = ArrayList<ObjDisplay>()
    private var pageDisplays = ArrayList<PageDisplay>()
    private var pageTimeOut = 0
    private var pageVersion = -1

    // display definition (rozlíšenie titulkovej plochy); default 720x576
    private var ddsW = 720
    private var ddsH = 576
    private var ddsX = 0
    private var ddsY = 0
    private var ddsVersion = -1

    private val defaultClut = Clut().also { buildDefaultClut(it) }

    /** Resetuj celý stav (pri prepnutí kanála / jazyka). */
    fun reset() {
        regions.clear(); cluts.clear(); objectDisplays.clear()
        pageDisplays = ArrayList(); pageTimeOut = 0; pageVersion = -1
        ddsW = 720; ddsH = 576; ddsX = 0; ddsY = 0; ddsVersion = -1
    }

    /**
     * Dekóduj jeden display-set. data = obsah jedného HTSP muxpkt titulku (zreťazené
     * segmenty 0x0F ...). Vráti zloženú stránku, alebo null ak set neukončil display
     * (chýba 0x80) — v praxi HTSP posiela kompletný set, takže vracia vždy.
     */
    fun decode(pts: Long, data: ByteArray): DecodedPage? {
        var i = 0
        var produced: DecodedPage? = null
        while (i + 6 <= data.size) {
            if ((data[i].toInt() and 0xFF) != 0x0F) { i++; continue }
            val segType = data[i + 1].toInt() and 0xFF
            val segLen = ((data[i + 4].toInt() and 0xFF) shl 8) or (data[i + 5].toInt() and 0xFF)
            val segStart = i + 6
            if (segStart + segLen > data.size) break
            when (segType) {
                0x10 -> parsePage(data, segStart, segLen)
                0x11 -> parseRegion(data, segStart, segLen)
                0x12 -> parseClut(data, segStart, segLen)
                0x13 -> parseObject(data, segStart, segLen)
                0x14 -> parseDisplayDefinition(data, segStart, segLen)
                0x80 -> produced = compose(pts)
            }
            i = segStart + segLen
        }
        // ak set neobsahoval 0x80 (nemalo by sa stať), zlož aj tak
        return produced ?: compose(pts)
    }

    // ---- segment parsery ----

    private fun parsePage(d: ByteArray, off: Int, len: Int) {
        if (len < 2) return
        val timeout = d[off].toInt() and 0xFF
        val b = d[off + 1].toInt() and 0xFF
        val version = (b ushr 4) and 0x0F
        val pageState = (b ushr 2) and 0x03
        pageTimeOut = timeout
        pageVersion = version
        if (pageState == 1 || pageState == 2) {
            regions.clear(); objectDisplays.clear(); cluts.clear()
        }
        val list = ArrayList<PageDisplay>()
        var p = off + 2
        while (p + 5 < off + len) {
            val regionId = d[p].toInt() and 0xFF
            val x = ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
            val y = ((d[p + 4].toInt() and 0xFF) shl 8) or (d[p + 5].toInt() and 0xFF)
            list.add(PageDisplay(regionId, x, y))
            p += 6
        }
        pageDisplays = list
    }

    private fun parseRegion(d: ByteArray, off: Int, len: Int) {
        if (len < 10) return
        var p = off
        val regionId = d[p++].toInt() and 0xFF
        val region = regions.getOrPut(regionId) { Region() }
        val b = d[p++].toInt() and 0xFF
        var fill = (b ushr 3) and 1
        val width = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
        val height = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
        region.width = width
        region.height = height
        if (width * height != region.pbuf.size) {
            region.pbuf = ByteArray(width * height)
            fill = 1
        }
        val depthByte = d[p++].toInt() and 0xFF
        region.depth = 1 shl ((depthByte ushr 2) and 7)
        if (region.depth < 2 || region.depth > 8) region.depth = 4
        region.clutId = d[p++].toInt() and 0xFF
        if (region.depth == 8) {
            region.bgcolor = d[p].toInt() and 0xFF; p += 2
        } else {
            p += 1
            region.bgcolor = if (region.depth == 4) ((d[p].toInt() and 0xFF) ushr 4) and 0x0F
            else ((d[p].toInt() and 0xFF) ushr 2) and 0x03
            p += 1
        }
        if (fill == 1) region.pbuf.fill(region.bgcolor.toByte())

        // delete_region_display_list pre tento región, potom pridaj nové objekty
        objectDisplays.removeAll { it.regionId == regionId }
        while (p + 5 < off + len) {
            val objectId = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)
            val xPos = (((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)) and 0x0FFF
            val yPos = (((d[p + 4].toInt() and 0xFF) shl 8) or (d[p + 5].toInt() and 0xFF)) and 0x0FFF
            val objType = (d[p + 2].toInt() and 0xFF) ushr 6
            p += 6
            if ((objType == 1 || objType == 2) && p + 1 < off + len) p += 2  // fg/bg color (preskoč)
            if (xPos < region.width && yPos < region.height) {
                objectDisplays.add(ObjDisplay(objectId, regionId, xPos, yPos))
            }
        }
    }

    private fun parseClut(d: ByteArray, off: Int, len: Int) {
        var p = off
        val clutId = d[p++].toInt() and 0xFF
        val version = ((d[p++].toInt() and 0xFF) ushr 4) and 0x0F
        val clut = cluts.getOrPut(clutId) { Clut() }   // vynulovana (nedefinovane = priehladne)
        if (clut.version == version) return
        clut.version = version
        val end = off + len
        while (p + 4 < end) {
            val entryId = d[p++].toInt() and 0xFF
            val depth = (d[p].toInt() and 0xFF) and 0xE0
            val fullRange = (d[p++].toInt() and 0xFF) and 1
            val y: Int; val cr: Int; val cb: Int; val alpha: Int
            if (fullRange == 1) {
                y = d[p++].toInt() and 0xFF
                cr = d[p++].toInt() and 0xFF
                cb = d[p++].toInt() and 0xFF
                alpha = d[p++].toInt() and 0xFF
            } else {
                val b0 = d[p].toInt() and 0xFF
                val b1 = d[p + 1].toInt() and 0xFF
                y = b0 and 0xFC
                cr = (((b0 and 3) shl 2) or ((b1 ushr 6) and 3)) shl 4
                cb = (b1 shl 2) and 0xF0
                alpha = (b1 shl 6) and 0xC0
                p += 2
            }
            val a = if (y == 0) 0xFF else alpha
            val argb = yuvToArgb(y, cb, cr, 255 - a)
            if ((depth and 0x80) != 0 && entryId < 4) clut.clut4[entryId] = argb
            else if ((depth and 0x40) != 0 && entryId < 16) clut.clut16[entryId] = argb
            else if ((depth and 0x20) != 0 && entryId < 256) clut.clut256[entryId] = argb
        }
    }

    private fun parseObject(d: ByteArray, off: Int, len: Int) {
        if (len < 3) return
        var p = off
        val objectId = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
        val b = d[p++].toInt() and 0xFF
        val codingMethod = (b ushr 2) and 3
        val nonMod = (b ushr 1) and 1
        if (codingMethod != 0) return   // 1/2 (text/progresívne) nepodporované, ako FFmpeg
        if (p + 4 > off + len) return
        val topLen = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
        val botLen = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
        if (p + topLen + botLen > off + len) return
        val topStart = p
        val botStart = if (botLen > 0) p + topLen else p
        val botUse = if (botLen > 0) botLen else topLen
        for (od in objectDisplays) {
            if (od.objectId != objectId) continue
            val region = regions[od.regionId] ?: continue
            parsePixelBlock(region, od, d, topStart, topLen, 0, nonMod)
            parsePixelBlock(region, od, d, botStart, botUse, 1, nonMod)
        }
    }

    private fun parseDisplayDefinition(d: ByteArray, off: Int, len: Int) {
        if (len < 5) return
        var p = off
        val info = d[p++].toInt() and 0xFF
        val version = (info ushr 4) and 0x0F
        if (ddsVersion == version) return
        ddsVersion = version
        ddsX = 0; ddsY = 0
        ddsW = (((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)) + 1; p += 2
        ddsH = (((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF)) + 1; p += 2
        if ((info and (1 shl 3)) != 0 && len >= 13) {
            val xMin = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
            val xMax = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
            val yMin = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
            val yMax = ((d[p].toInt() and 0xFF) shl 8) or (d[p + 1].toInt() and 0xFF); p += 2
            ddsX = xMin; ddsW = xMax - xMin + 1
            ddsY = yMin; ddsH = yMax - yMin + 1
        }
    }

    // ---- pixel data block (RLE) ----

    private fun parsePixelBlock(region: Region, od: ObjDisplay, d: ByteArray, start: Int, len: Int, topBottom: Int, nonMod: Int) {
        if (len <= 0) return
        val map2to4 = intArrayOf(0x0, 0x7, 0x8, 0xF)
        val map2to8 = intArrayOf(0x00, 0x77, 0x88, 0xFF)
        val map4to8 = intArrayOf(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)
        var xPos = od.xPos
        var yPos = od.yPos + topBottom
        var i = start
        val end = start + len
        while (i < end) {
            val ctrl = d[i].toInt() and 0xFF
            if ((ctrl != 0xF0 && xPos >= region.width) || yPos >= region.height) return
            i++
            when (ctrl) {
                0x10 -> {
                    val map = when (region.depth) { 8 -> map2to8; 4 -> map2to4; else -> null }
                    val r = read2bit(region.pbuf, yPos * region.width, region.width, d, i, end - i, nonMod, map, xPos)
                    xPos = r.first; i += r.second
                }
                0x11 -> {
                    if (region.depth < 4) return
                    val map = if (region.depth == 8) map4to8 else null
                    val r = read4bit(region.pbuf, yPos * region.width, region.width, d, i, end - i, nonMod, map, xPos)
                    xPos = r.first; i += r.second
                }
                0x12 -> {
                    if (region.depth < 8) return
                    val r = read8bit(region.pbuf, yPos * region.width, region.width, d, i, end - i, nonMod, null, xPos)
                    xPos = r.first; i += r.second
                }
                0x20 -> { map2to4[0] = (d[i].toInt() and 0xFF) ushr 4; map2to4[1] = d[i].toInt() and 0x0F; i++
                          map2to4[2] = (d[i].toInt() and 0xFF) ushr 4; map2to4[3] = d[i].toInt() and 0x0F; i++ }
                0x21 -> { for (k in 0 until 4) map2to8[k] = d[i++].toInt() and 0xFF }
                0x22 -> { for (k in 0 until 16) map4to8[k] = d[i++].toInt() and 0xFF }
                0xF0 -> { xPos = od.xPos; yPos += 2 }
                else -> return
            }
        }
    }

    private class BitReader(val data: ByteArray, val start: Int, val byteLen: Int) {
        var bitPos = 0
        fun getBits(n: Int): Int {
            var v = 0; var k = n
            while (k-- > 0) {
                val bi = start + (bitPos ushr 3)
                val bit = if (bi < data.size) (data[bi].toInt() ushr (7 - (bitPos and 7))) and 1 else 0
                v = (v shl 1) or bit; bitPos++
            }
            return v
        }
        fun getBits1() = getBits(1)
        fun bytesConsumed() = (bitPos + 7) ushr 3
    }

    /** Vráti (newXPos, bytesConsumed). Pri skipe (non-mod) posunie pozíciu bez prepisu. */
    private fun read2bit(dest: ByteArray, destOff: Int, dbufLen: Int, src: ByteArray, srcOff: Int, srcLen: Int, nonMod: Int, map: IntArray?, xPosIn: Int): Pair<Int, Int> {
        val gb = BitReader(src, srcOff, srcLen)
        var pixels = xPosIn
        var d = destOff + xPosIn
        val totalBits = srcLen * 8
        while (gb.bitPos < totalBits && pixels < dbufLen) {
            var bits = gb.getBits(2)
            if (bits != 0) {
                if (nonMod != 1 || bits != 1) dest[d] = (if (map != null) map[bits] else bits).toByte()
                d++; pixels++
            } else {
                if (gb.getBits1() == 1) {
                    var run = gb.getBits(3) + 3
                    bits = gb.getBits(2)
                    val v = if (nonMod == 1 && bits == 1) -1 else if (map != null) map[bits] else bits
                    while (run-- > 0 && pixels < dbufLen) { if (v >= 0) dest[d] = v.toByte(); d++; pixels++ }
                } else {
                    if (gb.getBits1() == 0) {
                        bits = gb.getBits(2)
                        when (bits) {
                            2 -> { var run = gb.getBits(4) + 12; val c = gb.getBits(2)
                                   val v = if (nonMod == 1 && c == 1) -1 else if (map != null) map[c] else c
                                   while (run-- > 0 && pixels < dbufLen) { if (v >= 0) dest[d] = v.toByte(); d++; pixels++ } }
                            3 -> { var run = gb.getBits(8) + 29; val c = gb.getBits(2)
                                   val v = if (nonMod == 1 && c == 1) -1 else if (map != null) map[c] else c
                                   while (run-- > 0 && pixels < dbufLen) { if (v >= 0) dest[d] = v.toByte(); d++; pixels++ } }
                            1 -> { val v = if (map != null) map[0] else 0; var run = 2
                                   while (run-- > 0 && pixels < dbufLen) { dest[d] = v.toByte(); d++; pixels++ } }
                            else -> return Pair(pixels, gb.bytesConsumed())
                        }
                    } else {
                        val v = if (map != null) map[0] else 0
                        dest[d] = v.toByte(); d++; pixels++
                    }
                }
            }
        }
        gb.getBits(6)   // FFmpeg parity: dočítaj koncový/zarovnávaci kód reťazca, nech kurzor sedí
        return Pair(pixels, gb.bytesConsumed())
    }

    private fun read4bit(dest: ByteArray, destOff: Int, dbufLen: Int, src: ByteArray, srcOff: Int, srcLen: Int, nonMod: Int, map: IntArray?, xPosIn: Int): Pair<Int, Int> {
        val gb = BitReader(src, srcOff, srcLen)
        var pixels = xPosIn
        var d = destOff + xPosIn
        val totalBits = srcLen * 8
        while (gb.bitPos < totalBits && pixels < dbufLen) {
            var bits = gb.getBits(4)
            if (bits != 0) {
                if (nonMod != 1 || bits != 1) dest[d] = (if (map != null) map[bits] else bits).toByte()
                d++; pixels++
            } else {
                if (gb.getBits1() == 0) {
                    var run = gb.getBits(3)
                    if (run == 0) return Pair(pixels, gb.bytesConsumed())
                    run += 2
                    val v = if (map != null) map[0] else 0
                    while (run-- > 0 && pixels < dbufLen) { dest[d] = v.toByte(); d++; pixels++ }
                } else {
                    if (gb.getBits1() == 0) {
                        var run = gb.getBits(2) + 4
                        bits = gb.getBits(4)
                        val v = if (nonMod == 1 && bits == 1) -1 else if (map != null) map[bits] else bits
                        while (run-- > 0 && pixels < dbufLen) { if (v >= 0) dest[d] = v.toByte(); d++; pixels++ }
                    } else {
                        bits = gb.getBits(2)
                        when (bits) {
                            2 -> { var run = gb.getBits(4) + 9; val c = gb.getBits(4)
                                   val v = if (nonMod == 1 && c == 1) -1 else if (map != null) map[c] else c
                                   while (run-- > 0 && pixels < dbufLen) { if (v >= 0) dest[d] = v.toByte(); d++; pixels++ } }
                            3 -> { var run = gb.getBits(8) + 25; val c = gb.getBits(4)
                                   val v = if (nonMod == 1 && c == 1) -1 else if (map != null) map[c] else c
                                   while (run-- > 0 && pixels < dbufLen) { if (v >= 0) dest[d] = v.toByte(); d++; pixels++ } }
                            1 -> { val v = if (map != null) map[0] else 0; var run = 2
                                   while (run-- > 0 && pixels < dbufLen) { dest[d] = v.toByte(); d++; pixels++ } }
                            else -> { val v = if (map != null) map[0] else 0; dest[d] = v.toByte(); d++; pixels++ }
                        }
                    }
                }
            }
        }
        gb.getBits(8)   // FFmpeg parity: dočítaj koncový/zarovnávaci kód 4-bit reťazca
        return Pair(pixels, gb.bytesConsumed())
    }

    private fun read8bit(dest: ByteArray, destOff: Int, dbufLen: Int, src: ByteArray, srcOff: Int, srcLen: Int, nonMod: Int, map: IntArray?, xPosIn: Int): Pair<Int, Int> {
        var pixels = xPosIn
        var d = destOff + xPosIn
        var i = srcOff
        val end = srcOff + srcLen
        while (i < end && pixels < dbufLen) {
            var bits = src[i++].toInt() and 0xFF
            if (bits != 0) {
                if (nonMod != 1 || bits != 1) dest[d] = (if (map != null) map[bits] else bits).toByte()
                d++; pixels++
            } else {
                if (i >= end) break
                val b2 = src[i++].toInt() and 0xFF
                var run = b2 and 0x7F
                if ((b2 and 0x80) == 0) {
                    if (run == 0) return Pair(pixels, i - srcOff)
                    bits = 0
                } else {
                    if (i >= end) break
                    bits = src[i++].toInt() and 0xFF
                }
                val v = if (nonMod == 1 && bits == 1) -1 else if (map != null) map[bits] else bits
                while (run-- > 0 && pixels < dbufLen) { if (v >= 0) dest[d] = v.toByte(); d++; pixels++ }
            }
        }
        if (i < end) i++   // FFmpeg parity: dočítaj koncový bajt 8-bit reťazca
        return Pair(pixels, i - srcOff)
    }

    // ---- kompozícia ----

    private fun compose(pts: Long): DecodedPage {
        val w = ddsW
        val h = ddsH
        if (pageDisplays.isEmpty()) {
            return DecodedPage(pts, pageTimeOut * 1000, w, h, null, true)
        }
        val out = IntArray(w * h)
        var painted = false
        for (pd in pageDisplays) {
            val region = regions[pd.regionId] ?: continue
            if (region.width == 0 || region.height == 0) continue
            val clut = cluts[region.clutId] ?: defaultClut
            val table = when (region.depth) { 8 -> clut.clut256; 4 -> clut.clut16; else -> clut.clut4 }
            val baseX = ddsX + pd.xPos
            val baseY = ddsY + pd.yPos
            for (ry in 0 until region.height) {
                val oy = baseY + ry
                if (oy < 0 || oy >= h) continue
                val rowR = ry * region.width
                val rowO = oy * w
                for (rx in 0 until region.width) {
                    val ox = baseX + rx
                    if (ox < 0 || ox >= w) continue
                    val idx = region.pbuf[rowR + rx].toInt() and 0xFF
                    val color = if (idx < table.size) table[idx] else 0
                    if ((color ushr 24) != 0) { out[rowO + ox] = color; painted = true }
                }
            }
        }
        return DecodedPage(pts, pageTimeOut * 1000, w, h, if (painted) out else null, !painted)
    }

    // ---- farby ----

    private fun yuvToArgb(y0: Int, cb0: Int, cr0: Int, alpha: Int): Int {
        val cb = cb0 - 128
        val cr = cr0 - 128
        val y = (y0 - 16) * 1192
        val r = clamp((y + 1634 * cr + 512) shr 10)
        val g = clamp((y - 401 * cb - 833 * cr + 512) shr 10)
        val b = clamp((y + 2066 * cb + 512) shr 10)
        return (alpha shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(v: Int): Int = if (v < 0) 0 else if (v > 255) 255 else v

    private fun buildDefaultClut(c: Clut) {
        c.clut4[0] = argb(0, 0, 0, 0)
        c.clut4[1] = argb(255, 255, 255, 255)
        c.clut4[2] = argb(0, 0, 0, 255)
        c.clut4[3] = argb(127, 127, 127, 255)
        c.clut16[0] = argb(0, 0, 0, 0)
        for (i in 1 until 16) {
            val r: Int; val g: Int; val b: Int
            if (i < 8) { r = if (i and 1 != 0) 255 else 0; g = if (i and 2 != 0) 255 else 0; b = if (i and 4 != 0) 255 else 0 }
            else { r = if (i and 1 != 0) 127 else 0; g = if (i and 2 != 0) 127 else 0; b = if (i and 4 != 0) 127 else 0 }
            c.clut16[i] = argb(r, g, b, 255)
        }
        c.clut256[0] = argb(0, 0, 0, 0)
        for (i in 1 until 256) {
            var r = 0; var g = 0; var b = 0; var a = 0
            if (i < 8) {
                r = if (i and 1 != 0) 255 else 0; g = if (i and 2 != 0) 255 else 0; b = if (i and 4 != 0) 255 else 0; a = 63
            } else when (i and 0x88) {
                0x00 -> { r = (if (i and 1 != 0) 85 else 0) + (if (i and 0x10 != 0) 170 else 0)
                          g = (if (i and 2 != 0) 85 else 0) + (if (i and 0x20 != 0) 170 else 0)
                          b = (if (i and 4 != 0) 85 else 0) + (if (i and 0x40 != 0) 170 else 0); a = 255 }
                0x08 -> { r = (if (i and 1 != 0) 85 else 0) + (if (i and 0x10 != 0) 170 else 0)
                          g = (if (i and 2 != 0) 85 else 0) + (if (i and 0x20 != 0) 170 else 0)
                          b = (if (i and 4 != 0) 85 else 0) + (if (i and 0x40 != 0) 170 else 0); a = 127 }
                0x80 -> { r = 127 + (if (i and 1 != 0) 43 else 0) + (if (i and 0x10 != 0) 85 else 0)
                          g = 127 + (if (i and 2 != 0) 43 else 0) + (if (i and 0x20 != 0) 85 else 0)
                          b = 127 + (if (i and 4 != 0) 43 else 0) + (if (i and 0x40 != 0) 85 else 0); a = 255 }
                0x88 -> { r = (if (i and 1 != 0) 43 else 0) + (if (i and 0x10 != 0) 85 else 0)
                          g = (if (i and 2 != 0) 43 else 0) + (if (i and 0x20 != 0) 85 else 0)
                          b = (if (i and 4 != 0) 43 else 0) + (if (i and 0x40 != 0) 85 else 0); a = 255 }
            }
            c.clut256[i] = argb(r, g, b, a)
        }
    }

    private fun argb(r: Int, g: Int, b: Int, a: Int): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b
}
