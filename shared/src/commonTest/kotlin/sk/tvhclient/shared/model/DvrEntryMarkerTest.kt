package sk.tvhclient.shared.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Computation of the position of the programme start/end within the recording (a marker on the seek bar). */
class DvrEntryMarkerTest {

    // a 60 min programme, 15 min of padding before and 15 min after -> a 90 min file.
    // start_real = start - 900s, stop_real = stop + 900s.
    private val padded = DvrEntry(
        start = 10_000,
        stop = 10_000 + 3600,            // a 60 min programme
        startReal = 10_000 - 900,        // 15 min before
        stopReal = 10_000 + 3600 + 900   // 15 min after
    )

    @Test
    fun realLengthIncludesPadding() {
        // 60 + 15 + 15 = 90 min = 5400 s
        assertEquals(5400, padded.realLengthSec)
    }

    @Test
    fun programStartIsPaddingFraction() {
        // 900 / 5400 = 0.1667
        assertEquals(0.1667f, padded.programStartFraction, 0.001f)
    }

    @Test
    fun programStopIsBeforeTrailingPadding() {
        // (900 + 3600) / 5400 = 0.8333
        assertEquals(0.8333f, padded.programStopFraction, 0.001f)
    }

    @Test
    fun fallbackToStartExtraWhenNoRealFields() {
        val e = DvrEntry(
            start = 10_000,
            stop = 10_000 + 3600,
            startExtra = 15,  // minutes
            stopExtra = 15
        )
        assertEquals(5400, e.realLengthSec)
        assertEquals(0.1667f, e.programStartFraction, 0.001f)
    }

    @Test
    fun noPaddingMeansNoMarker() {
        val e = DvrEntry(start = 10_000, stop = 10_000 + 3600)
        assertEquals(3600, e.realLengthSec)
        // no padding -> no marker (0 and 1)
        assertEquals(0f, e.programStartFraction)
        assertEquals(1f, e.programStopFraction)
    }

    @Test
    fun markerStaysInRange() {
        assertTrue(padded.programStartFraction in 0f..1f)
        assertTrue(padded.programStopFraction in 0f..1f)
        assertTrue(padded.programStartFraction < padded.programStopFraction)
    }
}
