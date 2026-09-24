package sk.tvhclient.shared.storage

import sk.tvhclient.shared.model.EpgEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/** M690: merging fresh EPG into the cache must not leave a moved programme twice. */
class EpgCacheMergeTest {

    private fun ev(id: Long, start: Long, stop: Long, title: String = "P$id") =
        EpgEvent(eventId = id, channelUuid = "1", start = start, stop = stop, title = title)

    @Test
    fun movedProgrammeReplacesTheCachedOne() {
        // cached: 16:35-17:50 (one grabber); fresh: the same programme at 16:40-17:50 (the other one)
        val base = mapOf("1" to listOf(ev(1, 1000, 5500), ev(2, 5500, 9000)))
        val fresh = listOf(ev(10, 1300, 5500), ev(11, 5500, 9000))
        val merged = EpgCacheCodec.mergeChannel(base, "1", fresh)["1"]!!
        assertEquals(listOf(10L, 11L), merged.map { it.eventId })
    }

    @Test
    fun pastOutsideTheFreshSpanIsKept() {
        val base = mapOf("1" to listOf(ev(1, 0, 1000), ev(2, 1000, 2000)))
        val fresh = listOf(ev(3, 2000, 3000), ev(4, 3000, 4000))
        val merged = EpgCacheCodec.mergeChannel(base, "1", fresh)["1"]!!
        assertEquals(listOf(1L, 2L, 3L, 4L), merged.map { it.eventId })
    }

    @Test
    fun cachedDaysBeyondTheFreshSpanAreKept() {
        val base = mapOf("1" to listOf(ev(1, 1000, 2000), ev(9, 50000, 51000)))
        val fresh = listOf(ev(2, 1000, 2000), ev(3, 2000, 3000))
        val merged = EpgCacheCodec.mergeChannel(base, "1", fresh)["1"]!!
        assertEquals(listOf(2L, 3L, 9L), merged.map { it.eventId })
    }

    @Test
    fun emptyFreshKeepsTheCache() {
        val base = mapOf("1" to listOf(ev(1, 1000, 2000)))
        assertEquals(base, EpgCacheCodec.mergeChannel(base, "1", emptyList()))
    }
}
