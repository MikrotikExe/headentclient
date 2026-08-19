package sk.tvhclient.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * M504: rozpoznanie radia podla TYPU SLUZBY (ako v Kodi/pvr.hts) — typ pochadza
 * z DVB tabuliek, takze nezavisi od pomenovania tagov.
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
        // TVH pise typy roznymi velkostami pismen podla verzie
        assertTrue(ch("digital radio sound service").isRadioByService!!)
    }

    @Test
    fun noServiceTypesMeansUnknown() {
        // null = server typy neposlal -> volajuci musi pouzit zalohu (tagy)
        assertNull(Channel(uuid = "1", name = "X").isRadioByService)
    }

    @Test
    fun anyRadioServiceWins() {
        // kanal viazany na viac sluzieb: staci jedna rozhlasova
        assertTrue(ch("SDTV", "Radio").isRadioByService!!)
    }

    // --- zaloha podla tagov ostava funkcna ---

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
