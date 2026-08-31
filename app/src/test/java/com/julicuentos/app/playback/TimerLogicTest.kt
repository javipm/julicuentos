package com.julicuentos.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Sleep-timer math table (tasks S2.3/S5.5; design.md D3; specs/sleep-timer
 * "Minutes countdown (screen-off-proof)", "Expiry = ~10 s fade…").
 */
class TimerLogicTest {

    private val now = 1_000_000L

    // ---------- set-time anchor ----------

    @Test
    fun endsAtFor_anchorsToElapsedRealtime() {
        assertEquals(now + 15 * 60_000L, TimerLogic.endsAtFor(15, now))
        assertEquals(now + 45 * 60_000L, TimerLogic.endsAtFor(45, now))
    }

    @Test
    fun isValidMinutes_only_15_30_45() {
        assertTrue(TimerLogic.isValidMinutes(15))
        assertTrue(TimerLogic.isValidMinutes(30))
        assertTrue(TimerLogic.isValidMinutes(45))
        assertFalse(TimerLogic.isValidMinutes(37))
        assertFalse(TimerLogic.isValidMinutes(0))
        assertFalse(TimerLogic.isValidMinutes(-15))
    }

    // ---------- restore validity window (D3) ----------

    @Test
    fun normalize_validRestore_keepsCountdown() {
        val endsAt = now + 9 * 60_000L // 6 min into a 15-min timer
        val state = TimerLogic.normalizeRestore(endsAt, 15, now)
        assertEquals(TimerState.Minutes(15, endsAt), state)
        assertEquals(9 * 60_000L, TimerLogic.remainingMs(state, now))
    }

    @Test
    fun normalize_expiredTimer_off_noSelfPause() {
        assertEquals(TimerState.Off, TimerLogic.normalizeRestore(now - 1L, 15, now))
        assertEquals(TimerState.Off, TimerLogic.normalizeRestore(now, 15, now))
    }

    @Test
    fun normalize_rebootArtifact_farFuture_off() {
        // Uptime clock reset: the old anchor looks like it fires in the far future.
        val endsAt = now + 15 * 60_000L + 5 * 60_000L // > minutes*60k + 60s
        assertEquals(TimerState.Off, TimerLogic.normalizeRestore(endsAt, 15, now))
    }

    @Test
    fun normalize_absoluteSanityCap_24h_off() {
        // |now - endsAt| > 24 h → off, even for a past-anchor far away.
        val farPast = now - 25L * 60 * 60 * 1000L
        assertEquals(TimerState.Off, TimerLogic.normalizeRestore(farPast, 45, now))
    }

    @Test
    fun normalize_invalidMinutes_off() {
        val endsAt = now + 30 * 60_000L
        assertEquals(TimerState.Off, TimerLogic.normalizeRestore(endsAt, 37, now))
        assertEquals(TimerState.Off, TimerLogic.normalizeRestore(endsAt, 0, now))
    }

    @Test
    fun normalize_missingEndsAt_off() {
        assertEquals(TimerState.Off, TimerLogic.normalizeRestore(0L, 15, now))
        assertEquals(TimerState.Off, TimerLogic.normalizeRestore(-1L, 15, now))
    }

    @Test
    fun normalize_justSetTimer_survives() {
        val endsAt = now + 15 * 60_000L
        assertEquals(TimerState.Minutes(15, endsAt), TimerLogic.normalizeRestore(endsAt, 15, now))
    }

    // ---------- remaining ----------

    @Test
    fun remainingMs_clampsAtZero() {
        assertEquals(0L, TimerLogic.remainingMs(TimerState.Off, now))
        assertEquals(0L, TimerLogic.remainingMs(TimerState.EndOfStory, now))
        assertEquals(0L, TimerLogic.remainingMs(TimerState.Minutes(15, now - 5_000L), now))
    }

    // ---------- fade schedule (10 x 1 s, 1.0 -> 0.0) ----------

    @Test
    fun fadeVolumeAtStep_monotonicDownToZero() {
        assertEquals(0.9f, TimerLogic.fadeVolumeAtStep(0))
        assertEquals(0.5f, TimerLogic.fadeVolumeAtStep(4))
        assertEquals(0.1f, TimerLogic.fadeVolumeAtStep(8))
        assertEquals(0.0f, TimerLogic.fadeVolumeAtStep(9))
    }

    @Test
    fun fadeVolumeAtStep_clamps() {
        assertEquals(0.9f, TimerLogic.fadeVolumeAtStep(-5))
        assertEquals(0.0f, TimerLogic.fadeVolumeAtStep(99))
    }
}
