package com.julicuentos.app.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary table from tasks S4.6: 0 / 59k /⌊59.999⌋  /61k /⌊3599.999⌋  /3600k,
 * plus negative and extreme (NaN-ish: Long has no NaN, so Long.MIN_VALUE and MAX_VALUE
 * must neither crash nor overflow).
 */
class TimeFormatTest {

    @Test
    fun exactBoundaries_mSecs() {
        assertEquals("0:00", TimeFormat.formatTime(0L))
        assertEquals("0:59", TimeFormat.formatTime(59_000L))
        assertEquals("0:59", TimeFormat.formatTime(59_999L))
        assertEquals("1:01", TimeFormat.formatTime(61_000L))
        assertEquals("59:59", TimeFormat.formatTime(3_599_999L))
        assertEquals("1:00:00", TimeFormat.formatTime(3_600_000L))
    }

    @Test
    fun floorBehaviorAtHalfSecond() {
        // 59.4 s floors down
        assertEquals("0:59", TimeFormat.formatTime(59_400L))
        // exactly a minute
        assertEquals("1:00", TimeFormat.formatTime(60_000L))
    }

    @Test
    fun formatTime_negativeAndExtremes_clampNoCrash() {
        assertEquals("0:00", TimeFormat.formatTime(-1L))
        assertEquals("0:00", TimeFormat.formatTime(Long.MIN_VALUE))
        assertTrue(
            "extreme positive must render h:mm:ss without overflow",
            Regex("\\d+:\\d{2}:\\d{2}").matches(TimeFormat.formatTime(Long.MAX_VALUE))
    )
    }

    @Test
    fun formatRemaining_ceilNeverEarlyZero() {
        assertEquals("0:00", TimeFormat.formatRemaining(0L))
        assertEquals("0:01", TimeFormat.formatRemaining(1L))
        assertEquals("0:01", TimeFormat.formatRemaining(400L))
        assertEquals("0:59", TimeFormat.formatRemaining(58_001L))
        // 59.4 s remaining ceils up to a full minute
        assertEquals("1:00", TimeFormat.formatRemaining(59_400L))
    }

    @Test
    fun formatRemaining_negativeClamps() {
        assertEquals("0:00", TimeFormat.formatRemaining(-1L))
        assertEquals("0:00", TimeFormat.formatRemaining(Long.MIN_VALUE))
    }
}