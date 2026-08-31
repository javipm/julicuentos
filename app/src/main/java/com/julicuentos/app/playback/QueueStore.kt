package com.julicuentos.app.playback

/**
 * Seam between the playback core and the (future) queue (design.md D5;
 * delegation see S2.5: "implement a QueueStore interface with an in-memory empty
 * implementation now; full queue is slice 5").
 *
 * Slice 5's [QueueLogic](pure, JUnit-tested) will implement the full
 * enqueue/reorder/remove semantics behind this interface; slice 2 only needs
 * the next-resolution question "what is the queue head?". [PlaybackRepository.playNext]
 * falls back to a circular next in the alphabetical catalog when this returns null.
 */
interface QueueStore {

    /**
     * The next story id in queue order after [currentStoryId], or null when
     * the queue is empty / has no successor. The queue never wraps: manual
     * next-resolution wraps THROUGH the catalog fallback instead (specs/playback
     * "Manual next wraps the catalog"), auto-advance stops when queue empty.
 */
    fun peekNext(currentStoryId: String?): String?
}

/** In-memory empty implementation for slice 2. Full queue lands in slice 5. */
class InMemoryQueueStore : QueueStore {
    override fun peekNext(currentStoryId: String?): String? = null
}