package sk.tvhclient.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * M504: radio detection based on the SERVICE TYPE (as in Kodi/pvr.hts) — the type comes
 * from the DVB tables, so it does not depend on how the tags are named.
 */
class RadioByServiceTest {

    private fun ch(vararg types: String) =
        Channel(uuid = "1", name = "X", serviceTypes = types.toList())

    @Test
    fun tvServiceTypesAreNotRadio() {
        assertFalse(ch("SDTV").isRadioByService!!)
        assertFalse(ch("HDTV").isRadioByService!!)
        assertFalse(ch("UHDTV").isRadioByService!!)
        assertFalse(ch("MPEG2 HD Digital television service").isRadioByService!!)
    }

    @Test
    fun radioServiceTypesAreRadio() {
        assertTrue(ch("Radio").isRadioByService!!)
        assertTrue(ch("FM Radio").isRadioByService!!)
        assertTrue(ch("MPEG2 Radio").isRadioByService!!)
        // TVH writes the types with different capitalization depending on the version
        assertTrue(ch("digital radio sound service").isRadioByService!!)
    }

    @Test
    fun noServiceTypesMeansUnknown() {
        // null = the server did not send the types -> the caller must use the fallback (tags)
        assertNull(Channel(uuid = "1", name = "X").isRadioByService)
    }

    @Test
    fun anyRadioServiceWins() {
        // a channel bound to several services: one radio service is enough
        assertTrue(ch("SDTV", "Radio").isRadioByService!!)
    }

    // --- the tag-based fallback stays functional ---

    @Test
    fun tagFallbackStillDetectsRadio() {
        assertTrue(RadioDetector.isRadio(listOf("Rádiá")))
        assertTrue(RadioDetector.isRadio(listOf("Rozhlas")))
        assertTrue(RadioDetector.isRadio(listOf("Radyo")))
        assertFalse(RadioDetector.isRadio(listOf("Filmy", "Sport")))
    }

    @Test
    fun tagFallbackIsCaseAndDiacriticsInsensitive() {
        assertEquals(true, RadioDetector.isRadio(listOf("RADIOSTANICE")))
    }
}
