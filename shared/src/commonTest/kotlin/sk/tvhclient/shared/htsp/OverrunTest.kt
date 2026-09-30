package sk.tvhclient.shared.htsp

import sk.tvhclient.shared.model.EpgEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** M715: a programme running past its scheduled end stays "now" while the server points at it. */
class OverrunTest {
    private val match = EpgEvent(eventId = 1, start = 1000, stop = 2000, title = "Match")
    private val news = EpgEvent(eventId = 2, start = 2000, stop = 3000, title = "News")

    @Test
    fun overrunKeptWhenServerPointsAtIt() {
        val now = 2500L
        val out = HtspData.markOverrun(listOf(match, news), nowId = 1, nowSec = now)
            .filter { it.stop > now || it.running }
        assertEquals(listOf("Match", "News"), out.map { it.title })
        assertEquals("Match", out.first { it.isCurrentAt(now) }.title)
    }

    @Test
    fun noPointerOrOtherPointerKeepsTimeRule() {
        val now = 2500L
        assertFalse(HtspData.markOverrun(listOf(match, news), null, now)[0].running)
        val moved = HtspData.markOverrun(listOf(match, news), nowId = 2, nowSec = now)
        assertFalse(moved[0].running)
        assertEquals("News", moved.first { it.isCurrentAt(now) }.title)
    }

    @Test
    fun stuckPointerIsCapped() {
        val now = 2000L + EpgEvent.MAX_OVERRUN_SEC + 10
        assertFalse(HtspData.markOverrun(listOf(match), 1, now)[0].running)
        val r = match.copy(running = true, runningAt = 2000L + EpgEvent.MAX_OVERRUN_SEC - 60)
        assertTrue(r.isCurrentAt(2000L + EpgEvent.MAX_OVERRUN_SEC - 30))
        assertFalse(r.isCurrentAt(2000L + EpgEvent.MAX_OVERRUN_SEC + 1))
    }

    @Test
    fun clockSkewIsNotAnOverrun() {
        // the server moves its "now" at the stop by its own clock; 30 s late here is not an overrun
        assertFalse(HtspData.markOverrun(listOf(match), 1, 2030L)[0].running)
        assertTrue(HtspData.markOverrun(listOf(match), 1, 2000L + EpgEvent.OVERRUN_MIN_SEC)[0].running)
    }

    @Test
    fun staleRunningFlagExpires() {
        val r = HtspData.markOverrun(listOf(match), 1, 2100L)[0]
        assertTrue(r.isCurrentAt(2100L + EpgEvent.RUNNING_VALID_SEC - 1))
        assertFalse(r.isCurrentAt(2100L + EpgEvent.RUNNING_VALID_SEC))
    }
}
