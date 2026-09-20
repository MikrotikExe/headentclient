package sk.tvhclient.shared.htsp

/**
 * Pure Kotlin SHA-1 (FIPS 180-1) for HTSP digest auth. Without platform crypto,
 * so that it works the same on Android and iOS without expect/actual and cinterop.
 * HTSP auth = SHA1(password_bytes + challenge_bytes).
 */
internal object Sha1 {
    fun digest(message: ByteArray): ByteArray {
        var h0 = 0x67452301
        var h1 = 0xEFCDAB89.toInt()
        var h2 = 0x98BADCFE.toInt()
        var h3 = 0x10325476
        var h4 = 0xC3D2E1F0.toInt()

        val ml = message.size.toLong() * 8
        // padding
        val withOne = message + byteArrayOf(0x80.toByte())
        var padLen = (56 - withOne.size % 64)
        if (padLen <= 0) padLen += 64
        val padded = withOne + ByteArray(padLen) + ByteArray(8) { i ->
            (ml ushr (56 - i * 8)).toByte()
        }

        val w = IntArray(80)
        var off = 0
        while (off < padded.size) {
            for (i in 0 until 16) {
                w[i] = ((padded[off + i * 4].toInt() and 0xFF) shl 24) or
                        ((padded[off + i * 4 + 1].toInt() and 0xFF) shl 16) or
                        ((padded[off + i * 4 + 2].toInt() and 0xFF) shl 8) or
                        (padded[off + i * 4 + 3].toInt() and 0xFF)
            }
            for (i in 16 until 80) {
                val v = w[i - 3] xor w[i - 8] xor w[i - 14] xor w[i - 16]
                w[i] = (v shl 1) or (v ushr 31)
            }
            var a = h0; var b = h1; var c = h2; var d = h3; var e = h4
            for (i in 0 until 80) {
                val f: Int; val k: Int
                when {
                    i < 20 -> { f = (b and c) or (b.inv() and d); k = 0x5A827999 }
                    i < 40 -> { f = b xor c xor d; k = 0x6ED9EBA1 }
                    i < 60 -> { f = (b and c) or (b and d) or (c and d); k = 0x8F1BBCDC.toInt() }
                    else -> { f = b xor c xor d; k = 0xCA62C1D6.toInt() }
                }
                val tmp = ((a shl 5) or (a ushr 27)) + f + e + k + w[i]
                e = d; d = c; c = (b shl 30) or (b ushr 2); b = a; a = tmp
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e
            off += 64
        }

        val out = ByteArray(20)
        intToBytes(h0, out, 0); intToBytes(h1, out, 4); intToBytes(h2, out, 8)
        intToBytes(h3, out, 12); intToBytes(h4, out, 16)
        return out
    }

    private fun intToBytes(v: Int, out: ByteArray, off: Int) {
        out[off] = (v ushr 24).toByte()
        out[off + 1] = (v ushr 16).toByte()
        out[off + 2] = (v ushr 8).toByte()
        out[off + 3] = v.toByte()
    }
}
