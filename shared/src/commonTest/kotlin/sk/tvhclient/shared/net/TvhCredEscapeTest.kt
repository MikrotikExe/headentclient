package sk.tvhclient.shared.net

import kotlin.test.Test
import kotlin.test.assertEquals

/** M712: Tvheadend de-escapes Basic credentials ("+" -> space, "%xx" decoded). */
class TvhCredEscapeTest {
    @Test
    fun plainUnchanged() = assertEquals("user:pass", TvhCredEscape.basicPair("user", "pass"))

    @Test
    fun plusAndPercentEscaped() {
        assertEquals("a%2Bb", TvhCredEscape.escape("a+b"))
        assertEquals("100%25", TvhCredEscape.escape("100%"))
        assertEquals("u:p%2B%25x", TvhCredEscape.basicPair("u", "p+%x"))
    }
}
