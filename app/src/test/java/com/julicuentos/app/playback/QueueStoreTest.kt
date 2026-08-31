package com.julicuentos.app.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Full queue-logic table (tasks S2.2/S5.1; specs/queue "Enqueue is append +
 * de-duplicated", "Reorder and remove", "Next resolution and end-of-queue").
 */
class QueueStoreTest {

    // ---------- enqueue: append + de-dup ----------

    @Test
    fun enqueue_appendsInOrder() {
        val q = InMemoryQueueStore()
        q.enqueue("b")
        q.enqueue("c")
        q.enqueue("d")
        assertEquals(listOf("b", "c", "d"), q.snapshot())
    }

    @Test
    fun enqueue_isIdempotent_noReorder() {
        val q = InMemoryQueueStore()
        q.enqueue("b")
        q.enqueue("c")
        assertFalse(q.enqueue("b")) // already present — no change
        assertEquals(listOf("b", "c"), q.snapshot())
    }

    @Test
    fun enqueue_emptyId_rejected() {
        val q = InMemoryQueueStore()
        assertFalse(q.enqueue(""))
        assertEquals(emptyList<String>(), q.snapshot())
    }

    @Test
    fun enqueue_currentStoryAllowed_waits() {
        val q = InMemoryQueueStore()
        assertTrue(q.enqueue("a")) // the + button can queue the current story
        assertEquals(listOf("a"), q.snapshot())
    }

    // ---------- reorder: adjacent swap, clamped ----------

    @Test
    fun moveUp_swapsNeighbors() {
        val q = queueOf("a", "b", "c")
        assertTrue(q.moveUp(2)) // c up -> [a, c, b]
        assertEquals(listOf("a", "c", "b"), q.snapshot())
    }

    @Test
    fun moveUp_head_isNoOp() {
        val q = queueOf("a", "b")
        assertFalse(q.moveUp(0))
        assertEquals(listOf("a", "b"), q.snapshot())
    }

    @Test
    fun moveDown_swapsNeighbors() {
        val q = queueOf("a", "b", "c")
        assertTrue(q.moveDown(0)) // a down -> [b, a, c]
        assertEquals(listOf("b", "a", "c"), q.snapshot())
    }

    @Test
    fun moveDown_tail_isNoOp() {
        val q = queueOf("a", "b")
        assertFalse(q.moveDown(1))
        assertEquals(listOf("a", "b"), q.snapshot())
    }

    @Test
    fun move_outOfRange_isNoOp() {
        val q = queueOf("a")
        assertFalse(q.moveUp(1))
        assertFalse(q.moveDown(-1))
        assertEquals(listOf("a"), q.snapshot())
    }

    // ---------- remove / clear ----------

    @Test
    fun remove_filtersById_playbackUntouched() {
        val q = queueOf("a", "b", "c")
        assertTrue(q.remove("b"))
        assertEquals(listOf("a", "c"), q.snapshot())
        assertFalse(q.remove("b")) // already gone
    }

    @Test
    fun clear_empties() {
        val q = queueOf("a", "b")
        q.clear()
        assertEquals(emptyList<String>(), q.snapshot())
    }

    // ---------- next resolution ----------

    @Test
    fun peekNext_currentNotInQueue_returnsHead() {
        val q = queueOf("b", "c")
        assertEquals("b", q.peekNext("a"))
    }

    @Test
    fun peekNext_currentInQueue_returnsSuccessor() {
        val q = queueOf("a", "b", "c")
        assertEquals("b", q.peekNext("a"))
        assertNull(q.peekNext("c")) // last in queue: no wrap
    }

    @Test
    fun peekNext_emptyQueue_null() {
        assertNull(InMemoryQueueStore().peekNext("a"))
    }

    @Test
    fun takeNext_consumesHead_whenCurrentNotInQueue() {
        val q = queueOf("b", "c")
        assertEquals("b", q.takeNext("a"))
        assertEquals(listOf("c"), q.snapshot()) // consumed
    }

    @Test
    fun takeNext_removesCurrentFirst_neverReplays() {
        val q = queueOf("a", "b") // a queued AND playing
        assertEquals("b", q.takeNext("a"))
        assertEquals(emptyList<String>(), q.snapshot())
    }

    @Test
    fun takeNext_exhaustsQueue() {
        val q = queueOf("b", "c")
        assertEquals("b", q.takeNext("a"))
        assertEquals("c", q.takeNext("b")) // current not in queue -> head again
        assertNull(q.takeNext("c"))
    }

    @Test
    fun takeNext_emptyQueue_null() {
        assertNull(InMemoryQueueStore().takeNext("a"))
    }

    // ---------- restore seeding ----------

    @Test
    fun setInitial_replaces_dedups() {
        val q = InMemoryQueueStore()
        q.enqueue("x")
        q.setInitial(listOf("b", "c", "b", ""))
        assertEquals(listOf("b", "c"), q.snapshot())
    }

    private fun queueOf(vararg ids: String): QueueStore =
        InMemoryQueueStore().apply { setInitial(ids.toList()) }
}
