package sk.tvhclient.shared.stream

import kotlin.test.Test
import kotlin.test.assertEquals

/** M737 (audit K5): percent-encoding of the UTF-8 bytes, not of UTF-16 code units. */
class StreamUrlEncodeTest {
    @Test
    fun asciiUnreservedStaysAsIs() {
        assertEquals("abc-XYZ_0.9~", StreamUrlBuilder.encode("abc-XYZ_0.9~"))
    }

    @Test
    fun reservedAndSpaceAreEncoded() {
        assertEquals("a%20b%40c%3A%2F%25%2B", StreamUrlBuilder.encode("a b@c:/%+"))
    }

    @Test
    fun nonAsciiIsEncodedAsUtf8() {
        assertEquals("%E2%82%AC", StreamUrlBuilder.encode("\u20AC"))     // euro sign
        assertEquals("%C2%A7", StreamUrlBuilder.encode("\u00A7"))        // section sign
        assertEquals("%C4%8D", StreamUrlBuilder.encode("\u010D"))        // c with caron
    }
}
