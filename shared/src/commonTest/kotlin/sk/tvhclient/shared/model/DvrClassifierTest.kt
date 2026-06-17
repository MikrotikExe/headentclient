package sk.tvhclient.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DvrClassifierTest {

    private fun entry(
        title: String = "",
        subtitle: String = "",
        description: String = "",
        channel: String = "",
        contentType: Int = 0
    ) = DvrEntry(
        dispTitle = title,
        dispSubtitle = subtitle,
        dispDescription = description,
        channelName = channel,
        contentType = contentType
    )

    // --- classify(): DVB content_type ma prednost ---

    @Test
    fun film_z_dvb_nibble() {
        assertEquals(DvrClassifier.FILM, DvrClassifier.classify(entry(title = "Hocico", contentType = 1)))
    }

    @Test
    fun sport_z_dvb_nibble() {
        assertEquals(DvrClassifier.SPORT, DvrClassifier.classify(entry(title = "Zapas", contentType = 4)))
    }

    @Test
    fun news_z_dvb_nibble() {
        assertEquals(DvrClassifier.NEWS, DvrClassifier.classify(entry(title = "Bulletin", contentType = 2)))
    }

    @Test
    fun dokument_z_dvb_nibble() {
        assertEquals(DvrClassifier.DOCUMENTARY, DvrClassifier.classify(entry(title = "Nieco", contentType = 9)))
    }

    @Test
    fun plny_dvb_bajt_sa_normalizuje_na_nibble() {
        // 0x41 = 65 -> /16 = 4 -> SPORT
        assertEquals(DvrClassifier.SPORT, DvrClassifier.classify(entry(title = "Zapas", contentType = 0x41)))
    }

    // --- classify(): serial podla podnazvu / epizodneho sufixu ---

    @Test
    fun serial_podla_podnazvu_cislo_lomka() {
        assertEquals(DvrClassifier.SERIAL, DvrClassifier.classify(entry(title = "Otec Brown", subtitle = "3/12")))
    }

    @Test
    fun serial_podla_epizodneho_sufixu() {
        assertEquals(DvrClassifier.SERIAL, DvrClassifier.classify(entry(title = "Otec Brown IV (1)")))
    }

    // --- classify(): napoveda kanala ---

    @Test
    fun detsky_kanal() {
        assertEquals(DvrClassifier.CHILDREN, DvrClassifier.classify(entry(title = "Program", channel = "Disney Channel")))
    }

    @Test
    fun dokument_kanal_pri_neznamom_zanri() {
        assertEquals(DvrClassifier.DOCUMENTARY, DvrClassifier.classify(entry(title = "Program", channel = "National Geographic")))
    }

    // --- classify(): fallback podla klucovych slov ---

    @Test
    fun sport_podla_klucoveho_slova() {
        assertEquals(DvrClassifier.SPORT, DvrClassifier.classify(entry(title = "Futbal: Slovan - Trnava")))
    }

    // --- classify(): film podla roku v nazve a filmoveho kanala ---

    @Test
    fun film_podla_roku_v_nazve() {
        assertEquals(DvrClassifier.FILM, DvrClassifier.classify(entry(title = "Matrix (1999)")))
    }

    @Test
    fun film_podla_filmoveho_kanala() {
        assertEquals(DvrClassifier.FILM, DvrClassifier.classify(entry(title = "Akcny trhak", channel = "HBO")))
    }

    @Test
    fun neznamy_program_je_other() {
        assertEquals(DvrClassifier.OTHER, DvrClassifier.classify(entry(title = "Testovaci program", channel = "Test kanal")))
    }

    // --- isSeriesLike: filmy/sport/other sa nezoskupuju ---

    @Test
    fun isSeriesLike_spravne() {
        assertFalse(DvrClassifier.isSeriesLike(DvrClassifier.FILM))
        assertFalse(DvrClassifier.isSeriesLike(DvrClassifier.SPORT))
        assertFalse(DvrClassifier.isSeriesLike(DvrClassifier.OTHER))
        assertTrue(DvrClassifier.isSeriesLike(DvrClassifier.SERIAL))
        assertTrue(DvrClassifier.isSeriesLike(DvrClassifier.NEWS))
        assertTrue(DvrClassifier.isSeriesLike(DvrClassifier.DOCUMENTARY))
    }

    // --- seriesCanonicalTitle: odstrani epizodny sufix, rok nechá ---

    @Test
    fun kanonicky_nazov_odstrani_epizodu() {
        assertEquals("Otec Brown IV", DvrClassifier.seriesCanonicalTitle("Otec Brown IV (1)"))
    }

    @Test
    fun kanonicky_nazov_necha_rok() {
        assertEquals("Matrix (1999)", DvrClassifier.seriesCanonicalTitle("Matrix (1999)"))
    }

    @Test
    fun kanonicky_nazov_bulletinu_zostane() {
        assertEquals("TV Noviny", DvrClassifier.seriesCanonicalTitle("TV Noviny"))
    }

    // --- order: 11 kategorii, FILM prvy, OTHER posledny ---

    @Test
    fun poradie_kategorii() {
        assertEquals(11, DvrClassifier.order.size)
        assertEquals(DvrClassifier.FILM, DvrClassifier.order.first())
        assertEquals(DvrClassifier.OTHER, DvrClassifier.order.last())
    }
}
