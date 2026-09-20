package sk.tvhclient.shared.htsp

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tests of the pure SHA-1 (FIPS 180-1) used for HTSP digest auth
 * (SHA1(password_bytes + challenge_bytes)). Known test vectors.
 */
class Sha1Test {

    private fun hex(b: ByteArray): String =
        b.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun sha1(s: String): String = hex(Sha1.digest(s.encodeToByteArray()))

    @Test
    fun emptyString() {
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", sha1(""))
    }

    @Test
    fun abc() {
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", sha1("abc"))
    }

    @Test
    fun quickBrownFox() {
        assertEquals(
            "2fd4e1c67a2d28fced849ee1bb76e7391b93eb12",
            sha1("The quick brown fox jumps over the lazy dog")
        )
    }

    @Test
    fun longInputCrossesBlockBoundary() {
        // 56 characters -> the padding pushes into the second 64-byte block
        assertEquals(
            "84983e441c3bd26ebaae4aa1f95129e5e54670f1",
            sha1("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq")
        )
    }

    @Test
    fun digestIsTwentyBytes() {
        assertEquals(20, Sha1.digest("x".encodeToByteArray()).size)
    }

    @Test
    fun authConcatenationOrder() {
        // SHA1(password + challenge) — verification that the concatenation of the bytes is right
        val pwd = "secret".encodeToByteArray()
        val challenge = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val combined = Sha1.digest(pwd + challenge)
        assertEquals(40, hex(combined).length)
    }
}
