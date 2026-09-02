package sk.tvhclient.shared.teletext

import kotlin.concurrent.Volatile

/**
 * M552 — dekodér EBU teletextu (EN 300 706, Level 1) z DVB dátových jednotiek
 * (EN 300 472). Spoločný pre HTSP (payload muxpkt stopy TELETEXT) aj HTTP
 * (PES payload teletextového PID z TS). Zbiera stránky do pamäte, UI si ich
 * pýta cez [page]/[subpages]; o zmene sa dozvie cez [onPageUpdated].
 *
 * Nerobí nič s vykresľovaním — [TeletextRenderer] premení uložený riadok
 * (40 bajtov, 7-bit) na bunky s farbou, mozaikou a dvojitou výškou.
 */
class TeletextDecoder {

    /** Prijatá stránka. [rows] = 25 riadkov × 40 bajtov (riadok 0 = hlavička, 24 = Fastext). */
    class Page(
        val number: Int,          // hex, napr. 0x100
        val subpage: Int,         // hex, 0 ak bez podstránok
        val rows: Array<ByteArray>,
        val rowPresent: BooleanArray,
        val charset: Int,         // národná sada (C12–C14), 0..7
        val flags: Int,           // C4..C14 bity ako v hlavičke
        val links: IntArray,      // Fastext: 6 čísel stránok (hex) alebo -1
        val receivedAt: Long
    ) {
        val isSubtitle: Boolean get() = (flags and FLAG_SUBTITLE) != 0
        val isNewsflash: Boolean get() = (flags and FLAG_NEWSFLASH) != 0
        val inhibitDisplay: Boolean get() = (flags and FLAG_INHIBIT) != 0
    }

    private class Building(
        val number: Int, val subpage: Int, val charset: Int, val flags: Int,
        val rows: Array<ByteArray>, val rowPresent: BooleanArray, val links: IntArray
    )

    // stránky: number -> (subpage -> Page). Copy-on-write snapshot: zapisuje jedno
    // vlákno (dekodér), čítá UI — bez zámku (common kód, žiadne synchronized).
    @Volatile private var pages: Map<Int, Map<Int, Page>> = emptyMap()
    private val building = arrayOfNulls<Building>(8)

    /** Volá sa (z vlákna dekodéra!) po prijatí kompletnej stránky. */
    var onPageUpdated: ((Int) -> Unit)? = null

    /** Posledná prijatá hlavička (32 znakov, už so znakovou sadou) — „rolujúce“ hodiny. */
    @Volatile var lastHeader: String = ""
        private set
    @Volatile var lastHeaderPage: Int = -1
        private set
    @Volatile var packetsSeen: Long = 0
        private set
    @Volatile var pagesSeen: Long = 0
        private set

    var now: () -> Long = { 0L }

    fun clear() {
        pages = emptyMap()
        for (i in building.indices) building[i] = null
        lastHeader = ""; lastHeaderPage = -1
    }

    fun page(number: Int, subpage: Int = -1): Page? {
        val m = pages[number] ?: return null
        if (subpage >= 0) return m[subpage]
        // bez podstránok / neurčené: posledná prijatá
        return m.values.lastOrNull()
    }

    fun subpages(number: Int): List<Int> = pages[number]?.keys?.sorted() ?: emptyList()

    fun hasPage(number: Int): Boolean = pages[number]?.isNotEmpty() == true

    fun knownPages(): List<Int> = pages.keys.sorted()

    // ------------------------------------------------------------------ vstup

    /**
     * PES payload teletextu (EN 300 472): data_identifier (0x10..0x1F) + dátové
     * jednotky po 46 B (id, dĺžka 0x2C, field/line, framing 0xE4, 42 B dát).
     * Tolerantné voči chýbajúcemu data_identifier.
     */
    fun feedPes(buf: ByteArray, off: Int = 0, len: Int = buf.size - off) {
        var p = off
        val end = off + len
        if (p < end) {
            val id = buf[p].toInt() and 0xFF
            if (id in 0x10..0x1F) p++
        }
        while (p + 2 <= end) {
            val unitId = buf[p].toInt() and 0xFF
            val unitLen = buf[p + 1].toInt() and 0xFF
            p += 2
            if (p + unitLen > end) break
            if ((unitId == 0x02 || unitId == 0x03) && unitLen >= 44) {
                // p: field/line, p+1: framing code, p+2.. 42 B
                // framing code 0xE4 — niektoré servery ho neprenášajú spoľahlivo, riadok
                // berieme aj tak (hamming adresy odfiltruje smeti)
                feedLine(buf, p + 2)
            }
            p += unitLen
        }
    }

    /** 42 bajtov VBI riadku (2 B adresa + 40 B dát), bity v prenosovom poradí (LSB first). */
    private fun feedLine(buf: ByteArray, off: Int) {
        val d = ByteArray(42)
        for (i in 0 until 42) d[i] = REV[buf[off + i].toInt() and 0xFF].toByte()
        val h1 = unham(d[0]); val h2 = unham(d[1])
        if (h1 < 0 || h2 < 0) return
        packetsSeen++
        var mag = h1 and 7
        val packet = ((h2 shl 1) or (h1 shr 3)) and 0x1F
        if (mag == 0) mag = 8
        when {
            packet == 0 -> header(mag, d)
            packet in 1..24 -> row(mag, packet, d)
            packet == 27 -> fastext(mag, d)
            // 26/28/29/30/31: rozšírenia (Level 1.5+), broadcast service data — ignorujeme
        }
    }

    private fun header(mag: Int, d: ByteArray) {
        val h = IntArray(8)
        for (i in 0 until 8) { h[i] = unham(d[2 + i]); if (h[i] < 0) return }
        val units = h[0]; val tens = h[1]
        val idx = mag and 7
        // predchádzajúca stránka tohto magazínu je hotová; v sériovom režime (C11)
        // ukončuje hlavička stránku ktoréhokoľvek magazínu
        commit(idx)
        if ((h[7] and 0x1) != 0) for (i in 0 until 8) commit(i)
        // hlavička s číslom xFF = výplň/čas, stránku nezakladá
        if (units == 0xF && tens == 0xF) {
            lastHeader = headerText(d, 0); lastHeaderPage = -1
            return
        }
        val number = (mag shl 8) or (tens shl 4) or units
        val subpage = (h[2]) or ((h[3] and 0x7) shl 4) or (h[4] shl 8) or ((h[5] and 0x3) shl 12)
        val erase = (h[3] and 0x8) != 0
        val flags = (if (erase) FLAG_ERASE else 0) or
            (if ((h[5] and 0x4) != 0) FLAG_NEWSFLASH else 0) or
            (if ((h[5] and 0x8) != 0) FLAG_SUBTITLE else 0) or
            (if ((h[6] and 0x1) != 0) FLAG_SUPPRESS_HDR else 0) or
            (if ((h[6] and 0x2) != 0) FLAG_UPDATE else 0) or
            (if ((h[6] and 0x4) != 0) FLAG_INTERRUPTED else 0) or
            (if ((h[6] and 0x8) != 0) FLAG_INHIBIT else 0) or
            (if ((h[7] and 0x1) != 0) FLAG_SERIAL else 0)
        // C12 C13 C14 (bity 1..3 bajtu 8) — v tabuľke národných sád je C12 NAJVYŠŠÍ bit
        // (M553-fix: opačné poradie dávalo pre češtinu/slovenčinu (110) taliančinu (011)).
        val charset = (((h[7] shr 1) and 1) shl 2) or (((h[7] shr 2) and 1) shl 1) or ((h[7] shr 3) and 1)
        // nová stránka: bez erase preberá riadky z uloženej verzie (prenášajú sa len zmenené)
        val prev = if (!erase) page(number, subpage) else null
        val rows = Array(25) { i -> if (prev != null && i > 0) prev.rows[i].copyOf() else ByteArray(40) { 0x20 } }
        val present = BooleanArray(25) { i -> prev != null && i > 0 && prev.rowPresent[i] }
        val links = if (prev != null) prev.links.copyOf() else IntArray(6) { -1 }
        // riadok 0 = hlavička: prvých 8 bajtov nie sú znaky (adresné), zobrazuje sa od stĺpca 8
        for (i in 0 until 8) rows[0][i] = 0x20
        for (i in 8 until 40) rows[0][i] = parity(d[2 + i])
        present[0] = true
        building[idx] = Building(number, subpage, charset, flags, rows, present, links)
        lastHeader = headerText(d, charset); lastHeaderPage = number
    }

    private fun headerText(d: ByteArray, charset: Int): String {
        val sb = StringBuilder(32)
        for (i in 8 until 40) {
            val c = parity(d[2 + i]).toInt()
            sb.append(if (c < 0x20) ' ' else TeletextCharset.g0(c, charset))
        }
        return sb.toString()
    }

    private fun row(mag: Int, packet: Int, d: ByteArray) {
        val b = building[mag and 7] ?: return
        val r = b.rows[packet]
        for (i in 0 until 40) r[i] = parity(d[2 + i])
        b.rowPresent[packet] = true
    }

    private fun fastext(mag: Int, d: ByteArray) {
        val b = building[mag and 7] ?: return
        val dc = unham(d[2])
        if (dc != 0) return   // len X/27/0 = editorial links
        for (l in 0 until 6) {
            val o = 3 + l * 6
            val u = unham(d[o]); val t = unham(d[o + 1])
            val s2 = unham(d[o + 3]); val s4 = unham(d[o + 5])
            if (u < 0 || t < 0 || s2 < 0 || s4 < 0) { continue }
            val relMag = ((s2 shr 3) and 1) or (((s4 shr 2) and 3) shl 1)
            var lm = (mag and 7) xor relMag
            if (lm == 0) lm = 8
            b.links[l] = if (u == 0xF && t == 0xF) -1 else (lm shl 8) or (t shl 4) or u
        }
    }

    private fun commit(idx: Int) {
        val b = building[idx] ?: return
        building[idx] = null
        val pg = Page(b.number, b.subpage, b.rows, b.rowPresent, b.charset, b.flags, b.links, now())
        val m = LinkedHashMap(pages[b.number] ?: emptyMap())
        m.remove(b.subpage)
        m[b.subpage] = pg
        // podstránky držíme max 80 na stránku
        if (m.size > 80) m.remove(m.keys.first())
        val np = HashMap(pages); np[b.number] = m
        pages = np
        pagesSeen++
        onPageUpdated?.invoke(b.number)
    }

    // ------------------------------------------------------------------ pomocné

    private fun parity(b: Byte): Byte {
        val v = b.toInt() and 0xFF
        // nepárna parita: počet jednotiek musí byť nepárny
        return if (v.countOneBits() and 1 == 1) (v and 0x7F).toByte() else 0x20
    }

    private fun unham(b: Byte): Int = HAM8[b.toInt() and 0xFF]

    companion object {
        const val FLAG_ERASE = 1
        const val FLAG_NEWSFLASH = 2
        const val FLAG_SUBTITLE = 4
        const val FLAG_SUPPRESS_HDR = 8
        const val FLAG_UPDATE = 16
        const val FLAG_INTERRUPTED = 32
        const val FLAG_INHIBIT = 64
        const val FLAG_SERIAL = 128

        /** Obrátenie bitov v bajte (VBI prenos je LSB first). */
        private val REV = IntArray(256) { v ->
            var x = v; var r = 0
            for (i in 0 until 8) { r = (r shl 1) or (x and 1); x = x shr 1 }
            r
        }

        /** Hamming 8/4 dekódovanie: tabuľka 256 → 0..15, -1 = neopraviteľná chyba.
         *  Kódové slová podľa EN 300 706 tab. 8 (P1 D1 P2 D2 P3 D3 P4 D4, po obrátení bitov). */
        private val HAM8: IntArray = run {
            val codes = IntArray(16) { dv ->
                val d1 = dv and 1; val d2 = (dv shr 1) and 1; val d3 = (dv shr 2) and 1; val d4 = (dv shr 3) and 1
                val p1 = 1 xor d1 xor d3 xor d4
                val p2 = 1 xor d1 xor d2 xor d4
                val p3 = 1 xor d1 xor d2 xor d3
                val p4 = 1 xor p1 xor d1 xor p2 xor d2 xor p3 xor d3 xor d4
                p1 or (d1 shl 1) or (p2 shl 2) or (d2 shl 3) or (p3 shl 4) or (d3 shl 5) or (p4 shl 6) or (d4 shl 7)
            }
            val t = IntArray(256) { -1 }
            for (v in 0 until 16) t[codes[v]] = v
            // oprava jednobitových chýb
            for (v in 0 until 16) for (bit in 0 until 8) {
                val c = codes[v] xor (1 shl bit)
                if (t[c] < 0) t[c] = v
            }
            t
        }
    }
}

/** Zobrazovaná bunka (Level 1). */
class TeletextCell(
    val ch: Char,
    val fg: Int,           // 0..7 (čierna, červená, zelená, žltá, modrá, purpurová, azúrová, biela)
    val bg: Int,
    val mosaic: Int,       // -1 = text, inak 6-bit vzor (b0 ľavý horný … b5 pravý dolný)
    val separated: Boolean,
    val doubleHeight: Boolean,
    val conceal: Boolean,
    val flash: Boolean
)

object TeletextRenderer {
    /** Prevedie stránku na 25 riadkov × 40 buniek. Riadok pod dvojitou výškou = null (preskočiť). */
    fun render(page: TeletextDecoder.Page, reveal: Boolean = false): Array<Array<TeletextCell>?> {
        val out = arrayOfNulls<Array<TeletextCell>>(25)
        var skipNext = false
        for (r in 0 until 25) {
            if (skipNext) { out[r] = null; skipNext = false; continue }
            val cells = renderRow(page.rows[r], page.charset, reveal)
            out[r] = cells
            if (cells.any { it.doubleHeight }) skipNext = true
        }
        return out
    }

    fun renderRow(row: ByteArray, charset: Int, reveal: Boolean): Array<TeletextCell> {
        var fg = 7; var bg = 0
        var mosaic = false; var separated = false; var dbl = false
        var hold = false; var conceal = false; var flash = false
        var heldChar = 0x20; var heldSeparated = false
        val cells = ArrayList<TeletextCell>(40)
        for (i in 0 until 40) {
            val c = row[i].toInt() and 0x7F
            if (c < 0x20) {
                // set-at atribúty platia už pre túto bunku
                when (c) {
                    0x09 -> flash = false
                    0x0C -> dbl = false
                    0x18 -> conceal = true
                    0x19 -> separated = false
                    0x1A -> separated = true
                    0x1C -> bg = 0
                    0x1D -> bg = fg
                    0x1E -> hold = true
                }
                // riadiaci znak sa zobrazí ako medzera, alebo držaná mozaika
                if (hold && mosaic) {
                    cells.add(cell(heldChar, fg, bg, true, heldSeparated, dbl, conceal, flash, charset, reveal))
                } else {
                    cells.add(TeletextCell(' ', fg, bg, -1, false, dbl, conceal, flash))
                }
                // set-after
                when (c) {
                    in 0x00..0x07 -> { fg = c; mosaic = false; conceal = false }
                    0x08 -> flash = true
                    0x0D -> dbl = true
                    in 0x10..0x17 -> { fg = c - 0x10; mosaic = true; conceal = false }
                    0x1F -> hold = false
                }
                continue
            }
            if (mosaic && (c in 0x20..0x3F || c in 0x60..0x7F)) {
                heldChar = c; heldSeparated = separated
                cells.add(cell(c, fg, bg, true, separated, dbl, conceal, flash, charset, reveal))
            } else {
                cells.add(cell(c, fg, bg, false, false, dbl, conceal, flash, charset, reveal))
            }
        }
        return cells.toTypedArray()
    }

    private fun cell(
        c: Int, fg: Int, bg: Int, mosaic: Boolean, separated: Boolean, dbl: Boolean,
        conceal: Boolean, flash: Boolean, charset: Int, reveal: Boolean
    ): TeletextCell {
        if (conceal && !reveal) return TeletextCell(' ', fg, bg, -1, false, dbl, true, flash)
        if (mosaic) {
            val pattern = (c and 0x1F) or ((c and 0x40) shr 1)
            return TeletextCell(' ', fg, bg, pattern, separated, dbl, false, flash)
        }
        return TeletextCell(TeletextCharset.g0(c, charset), fg, bg, -1, false, dbl, false, flash)
    }
}

/** G0 Latin + národné podmnožiny (EN 300 706 tab. 36), výber podľa C12–C14 (región 0). */
object TeletextCharset {
    private val POS = intArrayOf(0x23, 0x24, 0x40, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F, 0x60, 0x7B, 0x7C, 0x7D, 0x7E)
    private val NATIONAL = arrayOf(
        "#¤@←½→↑#‐¼‖¾÷",   // 0 English
        "#\$§ÄÖÜ^_°äöüß",   // 1 German
        "#¤ÉÄÖÅÜ_éäöåü",   // 2 Swedish / Finnish / Hungarian
        "£\$é°ç→↑#ùàòèì",   // 3 Italian
        "éïàëêùî#èâôûç",   // 4 French
        "ç\$¡áéíóú¿üñèà",   // 5 Portuguese / Spanish
        "#ůčťžýířéáěúš",   // 6 Czech / Slovak
        "#ńąƵŚŁćóężśłź"    // 7 (rezervované; použijeme poľštinu z regiónu 1)
    )

    fun g0(c: Int, charset: Int): Char {
        if (c < 0x20 || c > 0x7F) return ' '
        if (c == 0x7F) return '█'
        val idx = POS.indexOf(c)
        if (idx >= 0) return NATIONAL[charset and 7][idx]
        return c.toChar()
    }
}
