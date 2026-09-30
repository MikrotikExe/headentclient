package sk.tvhclient.shared.htsp

import kotlin.test.Test
import kotlin.test.assertEquals

/** M715: the HTSP episode label follows the HTTP grid format (api_epg.c). */
class EpisodeTextTest {
    @Test
    fun formats() {
        assertEquals("s01.e02", HtspData.episodeText(1, 2))
        assertEquals("s03", HtspData.episodeText(3, null))
        assertEquals("e12", HtspData.episodeText(0, 12))
        assertEquals("s10.e100", HtspData.episodeText(10, 100))
        assertEquals("", HtspData.episodeText(null, null))
    }
}
