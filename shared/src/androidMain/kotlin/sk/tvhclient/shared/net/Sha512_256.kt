package sk.tvhclient.shared.net

/**
 * M256 — a pure Kotlin implementation of SHA-512/256 (FIPS 180-4), independent of
 * the system MessageDigest. The reason: Android < 8 (and some Conscrypt builds)
 * do not have "SHA-512/256" in JCA, so getInstance(...) threw an exception and the app
 * crashed against a digest server using this hash type. Here we compute it ourselves -> it works
 * on all devices. The algorithm is verified against hashlib (identical vectors).
 */
internal object Sha512_256 {

    private val K = longArrayOf(
        0x428a2f98d728ae22UL.toLong(), 0x7137449123ef65cdUL.toLong(), 0xb5c0fbcfec4d3b2fUL.toLong(), 0xe9b5dba58189dbbcUL.toLong(),
        0x3956c25bf348b538UL.toLong(), 0x59f111f1b605d019UL.toLong(), 0x923f82a4af194f9bUL.toLong(), 0xab1c5ed5da6d8118UL.toLong(),
        0xd807aa98a3030242UL.toLong(), 0x12835b0145706fbeUL.toLong(), 0x243185be4ee4b28cUL.toLong(), 0x550c7dc3d5ffb4e2UL.toLong(),
        0x72be5d74f27b896fUL.toLong(), 0x80deb1fe3b1696b1UL.toLong(), 0x9bdc06a725c71235UL.toLong(), 0xc19bf174cf692694UL.toLong(),
        0xe49b69c19ef14ad2UL.toLong(), 0xefbe4786384f25e3UL.toLong(), 0x0fc19dc68b8cd5b5UL.toLong(), 0x240ca1cc77ac9c65UL.toLong(),
        0x2de92c6f592b0275UL.toLong(), 0x4a7484aa6ea6e483UL.toLong(), 0x5cb0a9dcbd41fbd4UL.toLong(), 0x76f988da831153b5UL.toLong(),
        0x983e5152ee66dfabUL.toLong(), 0xa831c66d2db43210UL.toLong(), 0xb00327c898fb213fUL.toLong(), 0xbf597fc7beef0ee4UL.toLong(),
        0xc6e00bf33da88fc2UL.toLong(), 0xd5a79147930aa725UL.toLong(), 0x06ca6351e003826fUL.toLong(), 0x142929670a0e6e70UL.toLong(),
        0x27b70a8546d22ffcUL.toLong(), 0x2e1b21385c26c926UL.toLong(), 0x4d2c6dfc5ac42aedUL.toLong(), 0x53380d139d95b3dfUL.toLong(),
        0x650a73548baf63deUL.toLong(), 0x766a0abb3c77b2a8UL.toLong(), 0x81c2c92e47edaee6UL.toLong(), 0x92722c851482353bUL.toLong(),
        0xa2bfe8a14cf10364UL.toLong(), 0xa81a664bbc423001UL.toLong(), 0xc24b8b70d0f89791UL.toLong(), 0xc76c51a30654be30UL.toLong(),
        0xd192e819d6ef5218UL.toLong(), 0xd69906245565a910UL.toLong(), 0xf40e35855771202aUL.toLong(), 0x106aa07032bbd1b8UL.toLong(),
        0x19a4c116b8d2d0c8UL.toLong(), 0x1e376c085141ab53UL.toLong(), 0x2748774cdf8eeb99UL.toLong(), 0x34b0bcb5e19b48a8UL.toLong(),
        0x391c0cb3c5c95a63UL.toLong(), 0x4ed8aa4ae3418acbUL.toLong(), 0x5b9cca4f7763e373UL.toLong(), 0x682e6ff3d6b2b8a3UL.toLong(),
        0x748f82ee5defb2fcUL.toLong(), 0x78a5636f43172f60UL.toLong(), 0x84c87814a1f0ab72UL.toLong(), 0x8cc702081a6439ecUL.toLong(),
        0x90befffa23631e28UL.toLong(), 0xa4506cebde82bde9UL.toLong(), 0xbef9a3f7b2c67915UL.toLong(), 0xc67178f2e372532bUL.toLong(),
        0xca273eceea26619cUL.toLong(), 0xd186b8c721c0c207UL.toLong(), 0xeada7dd6cde0eb1eUL.toLong(), 0xf57d4f7fee6ed178UL.toLong(),
        0x06f067aa72176fbaUL.toLong(), 0x0a637dc5a2c898a6UL.toLong(), 0x113f9804bef90daeUL.toLong(), 0x1b710b35131c471bUL.toLong(),
        0x28db77f523047d84UL.toLong(), 0x32caab7b40c72493UL.toLong(), 0x3c9ebe0a15c9bebcUL.toLong(), 0x431d67c49c100d4cUL.toLong(),
        0x4cc5d4becb3e42b6UL.toLong(), 0x597f299cfc657e2aUL.toLong(), 0x5fcb6fab3ad6faecUL.toLong(), 0x6c44198c4a475817UL.toLong()
    )

    private fun rr(x: Long, n: Int): Long = (x ushr n) or (x shl (64 - n))

    fun hex(data: ByteArray): String {
        var h0 = 0x22312194FC2BF72CUL.toLong()
        var h1 = 0x9F555FA3C84C64C2UL.toLong()
        var h2 = 0x2393B86B6F53B151UL.toLong()
        var h3 = 0x963877195940EABDUL.toLong()
        var h4 = 0x96283EE2A88EFFE3UL.toLong()
        var h5 = 0xBE5E1E2553863992UL.toLong()
        var h6 = 0x2B0199FC2C85B8AAUL.toLong()
        var h7 = 0x0EB72DDC81C52CA2UL.toLong()

        val ml = data.size.toLong() * 8
        var n = data.size + 1
        while (n % 128 != 112) n++
        val padLen = n + 16
        val msg = ByteArray(padLen)
        data.copyInto(msg)
        msg[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            msg[padLen - 1 - i] = ((ml ushr (8 * i)) and 0xff).toByte()
        }

        val w = LongArray(80)
        var off = 0
        while (off < padLen) {
            for (i in 0 until 16) {
                var word = 0L
                for (j in 0 until 8) word = (word shl 8) or (msg[off + i * 8 + j].toLong() and 0xff)
                w[i] = word
            }
            for (i in 16 until 80) {
                val s0 = rr(w[i - 15], 1) xor rr(w[i - 15], 8) xor (w[i - 15] ushr 7)
                val s1 = rr(w[i - 2], 19) xor rr(w[i - 2], 61) xor (w[i - 2] ushr 6)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }
            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7
            for (i in 0 until 80) {
                val t1s = rr(e, 14) xor rr(e, 18) xor rr(e, 41)
                val ch = (e and f) xor (e.inv() and g)
                val t1 = h + t1s + ch + K[i] + w[i]
                val t2s = rr(a, 28) xor rr(a, 34) xor rr(a, 39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val t2 = t2s + maj
                h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
            }
            h0 += a; h1 += b; h2 += c; h3 += d; h4 += e; h5 += f; h6 += g; h7 += h
            off += 128
        }

        val hexd = "0123456789abcdef"
        val sb = StringBuilder(64)
        for (hv in longArrayOf(h0, h1, h2, h3)) {
            for (i in 7 downTo 0) {
                val bv = ((hv ushr (8 * i)) and 0xff).toInt()
                sb.append(hexd[bv ushr 4]); sb.append(hexd[bv and 0x0f])
            }
        }
        return sb.toString()
    }
}
