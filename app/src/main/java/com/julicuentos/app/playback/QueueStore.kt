package com.julicuentos.app.playback

/**
 * Seam between the playback core and the (future) queue (design.md D5;
 * tasks S2.2/S3.5). Full queue logic (reorder/remove/persistence+) lands
 * in slice 5; this slice only needs the minimal append+de-dup `enqueue`, `clear`,
 * and the next-resolution question `peekNext` the catalog's action sheet routes to
 * (repository.enqueue; S3.5).
 *
 * [peekNext] semantics (design D5 + specs/queue: the queue never wraps — a "next"
 * from the player/miniplayer falls through to the alphabetical catalog fallback in
 * [PlaybackRepository.resolveNextId] instead. Current-not-in-queue resolves to the head
 * of the queue (so A playing + queue [B] → ⏭ = B.
 */
interface QueueStore {
    /** The next story id in queue order after [currentStoryId], or null when
     * the queue is empty / has no successor (current is the last entry..
     */
    fun peekNext(currentStoryId: String?): String?

    /** Appends [storyId] unless already present (enqueue is append + de-dup,
     * specs/queue "Enqueue is append + de-duplicated").
   */
    fun enqueue(storyId: String)

    /** Empties the queue (slice-5 screen "Vaciar" target; kept minimal now). */
    fun clear()
}

/** In-memory queue for slice 3. Full persistent QueueLogic lands in slice  ️5. */
class InMemoryQueueStore : QueueStore {

    private val ids = ArrayList<String>()

    override fun peekNext(currentStoryId: String?): String? {
        if (ids.isEmpty()) return null
        val idx = currentStoryId?.let { ids.indexOf(it) } ?: -1
        return when {
            idx >= 0 && idx < ids.size - 1 -> ids[idx + 1]      // successor in queue order
            idx >= 0 -> null                                       // last-in-queue: no wrap here
            else -> ids[0]                                        // current not in queue → head (specs miniplayer next test)
        }
    }

    override fun enqueue(storyId: String) {
        if (storyId.isEmpty()) return
        if (!ids.contains(storyId)) ids.add(storyId)
    }

    override fun clear() {
        ids.clear()
    }
}