package com.julicuentos.app.persist

import com.julicuentos.app.playback.TimerState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hydration decision table (tasks S5.7; specs/persistence "Never overwrite user
 * actions taken during restore", "Cold restore is paused at the last position",
 * "Restore drops unknown ids").
 */
class RestoreCoordinatorTest {

    private val known = setOf("a", "b", "c")

    @Test
    fun userMutated_discardsSnapshot_wholesale() {
        val snapshot = PersistedState("a", 12_345L, listOf("b", "c"), TimerState.Minutes(15, 99L), 7L)
        val d = RestoreCoordinator.decide(snapshot, userMutated = true, knownIds = known)

        assertFalse(d.applySnapshot)
        assertEquals(null, d.currentStoryId)
        assertEquals(emptyList<String>(), d.queueIds)
        assertEquals(TimerState.Off, d.timer)
    }

    @Test
    fun freshInstall_noState_defaults_noBogusWrite() {
        val d = RestoreCoordinator.decide(PersistedState.empty(), userMutated = false, knownIds = known)

        assertTrue(d.applySnapshot)
        assertEquals(null, d.currentStoryId) // Idle
        assertEquals(emptyList<String>(), d.queueIds)
    }

    @Test
    fun knownCurrentAndQueue_restoresUnchanged() {
        val snapshot = PersistedState("a", 20_000L, listOf("b", "c"), TimerState.Off, 9L)
        val d = RestoreCoordinator.decide(snapshot, false, known)


        assertTrue(d.applySnapshot)
        assertEquals("a", d.currentStoryId)
        assertEquals(20_000L, d.positionMs)
        assertEquals(listOf("b", "c"), d.queueIds)
        assertEquals(TimerState.Off, d.timer)
    }

    @Test
    fun unknownCurrent_droppedToIdle_knownQueueRestores() {
        val snapshot = PersistedState("historia-borrada", 20_000L, listOf("b", "x", "c"), TimerState.Off, 0L)
        val d = RestoreCoordinator.decide(snapshot, false, known)


        assertEquals(null, d.currentStoryId) // Idle,no miniplayer
        assertEquals(listOf("b", "c"), d.queueIds) // unknown queue id dropped
        assertTrue(d.applySnapshot)
    }

    @Test
    fun unknownQueueIds_filtered_knownKeptInOrder() {
        val snapshot = PersistedState("a", 1L, listOf("z", "b", "y", "c", "w"), TimerState.Off, 0L)
        val d = RestoreCoordinator.decide(snapshot, false, known)



        assertEquals(listOf("b", "c"), d.queueIds)
    }

    @Test
    fun expiredTimer_off_storyAndQueueRestoreNormally() {
        // Parsed already normalized the timer to off;the coordinator passes it through.
        val snapshot = PersistedState("a", 5_000L, listOf("b"), TimerState.Off, 0L)
        val d = RestoreCoordinator.decide(snapshot, false, known)


        assertEquals("a", d.currentStoryId)
        assertEquals(listOf("b"), d.queueIds)
        assertEquals(TimerState.Off, d.timer)
        assertTrue(d.applySnapshot)
    }

    @Test
    fun negativePositionMs_clamped() {
        val snapshot = PersistedState("a", -10L, emptyList(), TimerState.Off, 0L)
        val d = RestoreCoordinator.decide(snapshot, false, known)

        assertEquals(0L, d.positionMs)
    }
}
