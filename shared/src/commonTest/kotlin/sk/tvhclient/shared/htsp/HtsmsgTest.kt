package sk.tvhclient.shared.htsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests of the binary HTSMSG serialization (Htsmsg).
 * Field format: [type:1][nameLen:1][dataLen:4 BE] + name + data.
 * Message: [bodyLen:4 BE] + body. deserializeMap takes the body WITHOUT the prefix.
 */
class HtsmsgTest {

    /** serialize() -> strip the 4-byte prefix -> deserializeMap(). */
    private fun rt(m: Map<String, Any?>): Map<String, Any?> {
        val bytes = Htsmsg.serialize(m)
        return Htsmsg.deserializeMap(bytes.copyOfRange(4, bytes.size))
    }

    @Test
    fun stringRoundTrip() {
        assertEquals("STV1 HD", rt(mapOf("name" to "STV1 HD"))["name"])
    }

    @Test
    fun longRoundTrip() {
        // both single-byte and multi-byte values (little-endian minimum bytes)
        assertEquals(1L, rt(mapOf("v" to 1L))["v"])
        assertEquals(256L, rt(mapOf("v" to 256L))["v"])
        assertEquals(1718000000L, rt(mapOf("start" to 1718000000L))["start"])
    }

    @Test
    fun zeroRoundTrip() {
        assertEquals(0L, rt(mapOf("v" to 0L))["v"])
    }

    @Test
    fun intComesBackAsLong() {
        // S64 is always deserialized as a Long
        assertEquals(42L, rt(mapOf("i" to 42))["i"])
    }

    @Test
    fun booleanAsS64() {
        assertEquals(1L, rt(mapOf("b" to true))["b"])
        assertEquals(0L, rt(mapOf("b" to false))["b"])
    }

    @Test
    fun byteArrayRoundTrip() {
        val bin = byteArrayOf(0x01, 0x7F, 0x00, 0xFF.toByte(), 0x10)
        val out = rt(mapOf("chal" to bin))["chal"]
        // M673: BIN is deserialized as a slice of the message body (Htsmsg.Bin), without a copy
        assertTrue(out is Htsmsg.Bin)
        assertTrue(bin.contentEquals(out.toByteArray()))
    }

    @Test
    fun nestedMapRoundTrip() {
        val out = rt(mapOf("outer" to mapOf("x" to 5L, "name" to "abc")))
        @Suppress("UNCHECKED_CAST")
        val inner = out["outer"] as Map<String, Any?>
        assertEquals(5L, inner["x"])
        assertEquals("abc", inner["name"])
    }

    @Test
    fun listRoundTrip() {
        val out = rt(mapOf("tags" to listOf(1L, 2L, 3L)))["tags"]
        assertEquals(listOf<Any?>(1L, 2L, 3L), out)
    }

    @Test
    fun multipleFieldsPreservedInOrder() {
        val out = rt(mapOf("a" to "x", "b" to 2L, "c" to "z"))
        assertEquals(listOf("a", "b", "c"), out.keys.toList())
        assertEquals("x", out["a"]); assertEquals(2L, out["b"]); assertEquals("z", out["c"])
    }

    @Test
    fun lengthPrefixEqualsBodySize() {
        val bytes = Htsmsg.serialize(mapOf("a" to "bc"))
        val prefix = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        assertEquals(bytes.size - 4, prefix)
    }

    @Test
    fun exactWireBytesForSimpleField() {
        // {"a":"bc"} -> [len=9][STR=3][nameLen=1][dataLen=2 BE]["a"]["bc"]
        val expected = byteArrayOf(0, 0, 0, 9, 3, 1, 0, 0, 0, 2, 97, 98, 99)
        assertTrue(expected.contentEquals(Htsmsg.serialize(mapOf("a" to "bc"))))
    }

    @Test
    fun emptyMapSerializesToZeroLength() {
        assertTrue(byteArrayOf(0, 0, 0, 0).contentEquals(Htsmsg.serialize(emptyMap())))
        assertTrue(Htsmsg.deserializeMap(ByteArray(0)).isEmpty())
    }

    @Test
    fun truncatedHeaderDoesNotThrow() {
        // too short for a field header -> an empty map, no crash
        assertTrue(Htsmsg.deserializeMap(byteArrayOf(3, 1, 0)).isEmpty())
    }

    @Test
    fun declaredLengthBeyondBufferDoesNotThrow() {
        // dataLen claims 9 bytes, but only 1 follows -> break, an empty map
        assertTrue(Htsmsg.deserializeMap(byteArrayOf(3, 1, 0, 0, 0, 9, 97)).isEmpty())
    }

    @Test
    fun realisticChannelMessage() {
        // like a real HTSP channelAdd -> mapping in HtspData (longOf/strOf)
        val out = rt(
            mapOf(
                "channelId" to 12L,
                "channelName" to "Markíza",
                "channelNumber" to 3L,
                "tags" to listOf(1L, 4L)
            )
        )
        assertEquals(12L, out["channelId"])
        assertEquals("Markíza", out["channelName"])
        assertEquals(3L, out["channelNumber"])
        assertEquals(listOf<Any?>(1L, 4L), out["tags"])
    }
}
