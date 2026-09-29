package sk.tvhclient.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** M711: channel numbers with a minor part, 0 = no number, "Other" service type = unknown. */
class ChannelNumberTest {
    private fun ch(n: Int?, minor: Int = 0, name: String = "X") =
        Channel(uuid = name, name = name, number = n, numberMinor = minor)

    @Test
    fun numberText() {
        assertEquals("5", ch(5).numberText)
        assertEquals("5.1", ch(5, 1).numberText)
        assertEquals("", ch(null).numberText)
        assertEquals("", ch(0).numberText)
    }

    @Test
    fun sortByMajorThenMinorUnnumberedLast() {
        val list = listOf(ch(null, name = "a"), ch(5, 2, "b"), ch(0, name = "c"), ch(5, 1, "d"), ch(1, name = "e"))
        assertEquals(listOf("e", "d", "b", "a", "c"), list.sortedWith(Channel.byNumber).map { it.name })
    }

    @Test
    fun otherServiceTypeIsUnknown() {
        assertNull(Channel(uuid = "1", serviceTypes = listOf("Other")).isRadioByService)
        assertEquals(true, Channel(uuid = "1", serviceTypes = listOf("Other", "Radio")).isRadioByService)
    }
}
