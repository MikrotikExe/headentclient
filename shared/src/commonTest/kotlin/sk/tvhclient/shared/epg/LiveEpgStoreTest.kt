package sk.tvhclient.shared.epg

import kotlinx.coroutines.test.runTest
import sk.tvhclient.shared.model.EpgEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** M720: the live EPG window — add/update/delete, the window trim, now/next and the running pointer. */
class LiveEpgStoreTest {
    // real time: the store trims by the clock on its own flushes
    private val now = sk.tvhclient.shared.currentTimeSeconds()
    private fun ev(id: Long, ch: String, start: Long, stop: Long, title: String = "e$id") =
        EpgEvent(eventId = id, channelUuid = ch, start = start, stop = stop, title = title)

    @Test
    fun nowNextFromTheWindow() = runTest {
        val s = LiveEpgStore("t1")
        s.upsert(listOf(
            ev(1, "a", now - 1800, now + 1800, "Now"),
            ev(2, "a", now + 1800, now + 3600, "Next"),
            ev(3, "a", now + 3600, now + 7200, "Later"),
            ev(9, "b", now + 600, now + 1200, "B later")
        ), now)
        s.markSynced(alive = true, nowSec = now)
        assertTrue(s.isFresh(now))
        assertEquals("Now", s.currentFor("a", now)?.title)
        assertEquals(listOf("Now", "Next", "Later"), s.nowNext("a", now).map { it.title })
        assertNull(s.currentFor("b", now))                       // a gap: nothing on air
        assertEquals(listOf("B later"), s.nowNext("b", now).map { it.title })
        assertEquals("Now", s.nowMap(now)["a"]?.title)
        // the rollover needs no new data: the next programme is current later
        assertEquals("Next", s.currentFor("a", now + 2000)?.title)
    }

    @Test
    fun updateDeleteAndTrim() = runTest {
        val s = LiveEpgStore("t2")
        s.upsert(listOf(ev(1, "a", now, now + 600, "Old title"), ev(2, "a", now + 600, now + 1200)), now)
        s.upsert(listOf(ev(1, "a", now, now + 600, "Fixed title")), now)      // eventUpdate
        s.delete(listOf(2L))                                                    // eventDelete
        s.publish(now)
        assertEquals(listOf("Fixed title"), s.snapshot["a"]?.map { it.title })
        // over long ago -> dropped on the next publish; far future -> never taken
        s.upsert(listOf(ev(5, "a", now + LiveEpg.WINDOW_SEC + 7200, now + LiveEpg.WINDOW_SEC + 9000)), now)
        s.publish(now + 2000)
        assertTrue(s.snapshot["a"].isNullOrEmpty())
    }

    @Test
    fun runningPointerKeepsAnOverrunningProgramme() = runTest {
        val s = LiveEpgStore("t3")
        s.setNowPointer("a", 1)
        s.upsert(listOf(ev(1, "a", now - 3600, now - 600, "Match"), ev(2, "a", now - 600, now + 600, "News")), now)
        s.publish(now)
        val cur = s.currentFor("a", now)
        assertEquals("Match", cur?.title)        // the server still points at it (M715)
        assertTrue(cur?.running == true)
        s.setNowPointer("a", 2)                  // the server moved on
        s.publish(now)
        assertEquals("News", s.currentFor("a", now)?.title)
        assertFalse(s.snapshot["a"]!!.any { it.running })
    }

    @Test
    fun freshnessFollowsTheFeed() = runTest {
        val s = LiveEpgStore("t4")
        assertFalse(s.isFresh(now))
        s.markSynced(alive = false, nowSec = now)              // polling mode (HTTP without comet)
        assertTrue(s.isFresh(now + 60))
        assertFalse(s.isFresh(now + LiveEpgStore.POLL_FRESH_SEC + 1))
        s.markSynced(alive = true, nowSec = now)
        assertTrue(s.isFresh(now + 24 * 3600))                 // a live feed: always fresh
        s.markFeedDead()
        assertFalse(s.isFresh(now + 24 * 3600))
    }

    @Test
    fun mergeWindowKeepsTheRestOfTheDay() {
        val end = now + LiveEpg.WINDOW_SEC
        val cached = listOf(
            ev(1, "a", now - 600, now + 600, "stale title"),
            ev(2, "a", now + 600, now + 1200),
            ev(7, "a", end + 100, end + 1000, "evening"),
            ev(8, "a", end + 1000, end + 2000, "night")
        )
        val window = listOf(ev(1, "a", now - 600, now + 600, "fresh title"), ev(3, "a", now + 600, now + 1500, "replaced"))
        val out = LiveEpg.mergeWindow(cached, window, end)
        assertEquals(listOf("fresh title", "replaced", "evening", "night"), out.map { it.title })
        assertEquals(window, LiveEpg.mergeWindow(null, window, end))
        assertEquals(cached, LiveEpg.mergeWindow(cached, emptyList(), end))
    }

    @Test
    fun httpWindowEndIsFixedUntilTheNextReload() = runTest {   // M731 (audit B6)
        val s = LiveEpgStore("t5")
        assertEquals(now + LiveEpg.WINDOW_SEC, s.windowEnd(now))                 // sliding (HTSP) before any load
        s.replaceAll(listOf(ev(1, "a", now, now + 600)), now)
        s.markSynced(alive = true, nowSec = now)
        assertEquals(now + LiveEpg.WINDOW_SEC, s.windowEnd(now + 1800))          // 30 min later: still the loaded end
        assertEquals(now + LiveEpg.WINDOW_SEC, s.windowEnd(now + LiveEpg.WINDOW_SEC + 20))   // never beyond the loaded end
        s.clear()
        assertEquals(now + LiveEpg.WINDOW_SEC, s.windowEnd(now))
    }

    @Test
    fun awaitFreshWaitsForTheNextSyncAfterALostFeed() = runTest {   // M731 (audit R9)
        val s = LiveEpgStore("t6")
        // synced a while ago (older than the polling grace), kept fresh only by the live feed
        s.markSynced(alive = true, nowSec = now - LiveEpgStore.POLL_FRESH_SEC - 60)
        assertTrue(s.awaitFresh(10))
        s.markFeedDead()
        assertFalse(s.awaitFresh(50))                                             // no new sync inside the wait
        s.markSynced(alive = true, nowSec = now)
        assertTrue(s.awaitFresh(10))
    }

    @Test
    fun knownIdsFollowThePublishedWindow() = runTest {   // M731 (audit R10)
        val s = LiveEpgStore("t7")
        s.upsert(listOf(ev(1, "a", now, now + 600), ev(2, "a", now + 600, now + 1200)), now)
        s.publish(now)
        assertEquals(setOf(1L, 2L), s.knownIds)
        s.delete(listOf(1L)); s.publish(now)
        assertEquals(setOf(2L), s.knownIds)
    }
}
