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

    // --- classify(): the DVB content_type takes priority ---

    @Test
    fun filmFromDvbNibble() {
        assertEquals(DvrClassifier.FILM, DvrClassifier.classify(entry(title = "Hocico", contentType = 1)))
    }

    @Test
    fun sportFromDvbNibble() {
        assertEquals(DvrClassifier.SPORT, DvrClassifier.classify(entry(title = "Match", contentType = 4)))
    }

    @Test
    fun newsFromDvbNibble() {
        assertEquals(DvrClassifier.NEWS, DvrClassifier.classify(entry(title = "Bulletin", contentType = 2)))
    }

    @Test
    fun documentaryFromDvbNibble() {
        assertEquals(DvrClassifier.DOCUMENTARY, DvrClassifier.classify(entry(title = "Something", contentType = 9)))
    }

    @Test
    fun fullDvbByteIsNormalizedToNibble() {
        // 0x41 = 65 -> /16 = 4 -> SPORT
        assertEquals(DvrClassifier.SPORT, DvrClassifier.classify(entry(title = "Match", contentType = 0x41)))
    }

    // --- classify(): series based on the subtitle / episode suffix ---

    @Test
    fun seriesFromSubtitleNumberSlash() {
        assertEquals(DvrClassifier.SERIAL, DvrClassifier.classify(entry(title = "Otec Brown", subtitle = "3/12")))
    }

    @Test
    fun seriesFromEpisodeSuffix() {
        assertEquals(DvrClassifier.SERIAL, DvrClassifier.classify(entry(title = "Otec Brown IV (1)")))
    }

    // --- classify(): channel hint ---

    @Test
    fun childrenChannel() {
        assertEquals(DvrClassifier.CHILDREN, DvrClassifier.classify(entry(title = "Program", channel = "Disney Channel")))
    }

    @Test
    fun documentaryChannelWithUnknownGenre() {
        assertEquals(DvrClassifier.DOCUMENTARY, DvrClassifier.classify(entry(title = "Program", channel = "National Geographic")))
    }

    // --- classify(): fallback based on keywords ---

    @Test
    fun sportFromKeyword() {
        assertEquals(DvrClassifier.SPORT, DvrClassifier.classify(entry(title = "Futbal: Slovan - Trnava")))
    }

    // --- classify(): film based on the year in the title and on a movie channel ---

    @Test
    fun filmFromYearInTitle() {
        assertEquals(DvrClassifier.FILM, DvrClassifier.classify(entry(title = "Matrix (1999)")))
    }

    @Test
    fun filmFromMovieChannel() {
        assertEquals(DvrClassifier.FILM, DvrClassifier.classify(entry(title = "Action blockbuster", channel = "HBO")))
    }

    @Test
    fun unknownProgrammeIsOther() {
        assertEquals(DvrClassifier.OTHER, DvrClassifier.classify(entry(title = "Test programme", channel = "Test Channel")))
    }

    // --- isSeriesLike: films/sport/other are not grouped ---

    @Test
    fun isSeriesLikeIsCorrect() {
        assertFalse(DvrClassifier.isSeriesLike(DvrClassifier.FILM))
        assertFalse(DvrClassifier.isSeriesLike(DvrClassifier.SPORT))
        assertFalse(DvrClassifier.isSeriesLike(DvrClassifier.OTHER))
        assertTrue(DvrClassifier.isSeriesLike(DvrClassifier.SERIAL))
        assertTrue(DvrClassifier.isSeriesLike(DvrClassifier.NEWS))
        assertTrue(DvrClassifier.isSeriesLike(DvrClassifier.DOCUMENTARY))
    }

    // --- seriesCanonicalTitle: removes the episode suffix, keeps the year ---

    @Test
    fun canonicalTitleStripsEpisode() {
        assertEquals("Otec Brown IV", DvrClassifier.seriesCanonicalTitle("Otec Brown IV (1)"))
    }

    @Test
    fun canonicalTitleKeepsYear() {
        assertEquals("Matrix (1999)", DvrClassifier.seriesCanonicalTitle("Matrix (1999)"))
    }

    @Test
    fun canonicalTitleKeepsBulletin() {
        assertEquals("TV Noviny", DvrClassifier.seriesCanonicalTitle("TV Noviny"))
    }

    // --- order: 11 categories, FILM first, OTHER last ---

    @Test
    fun categoryOrder() {
        assertEquals(11, DvrClassifier.order.size)
        assertEquals(DvrClassifier.FILM, DvrClassifier.order.first())
        assertEquals(DvrClassifier.OTHER, DvrClassifier.order.last())
    }
}
