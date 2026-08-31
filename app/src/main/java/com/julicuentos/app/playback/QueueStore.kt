package com.julicuentos.app.playback

/**
 * Full ids-only playback queue (tasks S2.2 + S5.1; design.md D5; specs/queue).
 *
 * The queue is an ordered list of story ids — never story objects; story data is
 * always resolved from the compiled-in catalog by id (specs/queue "Ids-only queue
 * model"). Operations: append + de-duplicated [enqueue], clamped adjacent
 * [moveUp]/[moveDown] (no wraparound), filter-by-id [remove], [clear], and
 * [takeNext] — the shared "next resolution" primitive that pops the queue head
 * (consuming it) while never letting a current-in-queue entry re-arm itself.
 *
 * [takeNext] semantics (specs/queue "Next resolution and end-of-queue":
 *  - the current story is never the answer: if [currentStoryId] is present in the
 *    queue it is removed first, so a queue entry for the playing story can never
 *    re-play itself (enqueueing the current story is allowed — "the queue entry
 *    simply waits" — and becomes the successor when it is no longer current);
 *  - the head of the remaining queue is then popped and returned (consuming it);
 *  - an empty queue yields null → the caller falls through to the alphabetical
 *    catalog fallback (manual ⏭) or stops (natural end).
 *
 * [InMemoryQueueStore] is the pure in-memory implementation (no Android imports;
 * JVM unit-tested). Unknown ids are dropped at materialization time by the
 * restore path ([RestoreCoordinator]) — the queue self-heals rather than crashes.
 */
interface QueueStore {

    /** Ordered copy of the current queue ids (for UI snapshots and persistence). */
    fun snapshot(): List<String>

    /** True when [storyId] is currently queued (player "＋ → ✓" membership). */
    fun contains(storyId: String): Boolean

    /** Appends [storyId] unless already present (append + de-dup; no reorder).
     *  Returns true when the queue actually changed. */
    fun enqueue(storyId: String): Boolean

    /** Swaps index with the previous one; no-op at the head. Returns change flag. */
    fun moveUp(index: Int): Boolean

    /** Swaps index with the next one; no-op at the tail. Returns change flag. */
    fun moveDown(index: Int): Boolean

    /** Removes every occurrence of [storyId] (filter by id). Returns change flag. */
    fun remove(storyId: String): Boolean

    /** Empties the queue. */
    fun clear()

    /**
     * Non-destructive twin of [takeNext] used by the manual ⏭ fallback decision:
     * the id that would play next WITHOUT consuming it, or null.
     */
    fun peekNext(currentStoryId: String?): String?

    /**
     * Destructive next-resolution: removes [currentStoryId] if queued, then pops
     * and returns the queue head — or null when nothing remains.
     */
    fun takeNext(currentStoryId: String?): String?

    /** Replaces the whole queue (restore path only — [RestoreCoordinator]). */
    fun setInitial(ids: List<String>)
}

/** Pure in-memory implementation (tasks S2.2/S5.1; specs/queue "Enqueue is append
 *  + de-duplicated", "Reorder and remove", "Next resolution and end-of-queue"). */
class InMemoryQueueStore : QueueStore {

    private val ids = ArrayList<String>()

    override fun snapshot(): List<String> = ArrayList(ids)

    override fun contains(storyId: String): Boolean = ids.contains(storyId)

    override fun enqueue(storyId: String): Boolean {
        if (storyId.isEmpty() || ids.contains(storyId)) return false
        ids.add(storyId)
        return true
    }

    override fun moveUp(index: Int): Boolean {
        if (index <= 0 || index >= ids.size) return false
        val tmp = ids[index - 1]
        ids[index - 1] = ids[index]
        ids[index] = tmp
        return true
    }

    override fun moveDown(index: Int): Boolean {
        if (index < 0 || index >= ids.size - 1) return false
        val tmp = ids[index + 1]
        ids[index + 1] = ids[index]
        ids[index] = tmp
        return true
    }

    override fun remove(storyId: String): Boolean {
        val sizeBefore = ids.size
        ids.removeAll { it == storyId }
        return ids.size != sizeBefore
    }

    override fun clear() {
        ids.clear()
    }

    override fun peekNext(currentStoryId: String?): String? {
        if (ids.isEmpty()) return null
        val idx = currentStoryId?.let { ids.indexOf(it) } ?: -1
        return when {
            idx >= 0 && idx < ids.size - 1 -> ids[idx + 1] // successor in queue order
            idx >= 0 -> null                                // last-in-queue: no wrap here
            else -> ids[0]                                  // current not in queue → head
        }
    }

    override fun takeNext(currentStoryId: String?): String? {
        if (currentStoryId != null) ids.remove(currentStoryId)
        if (ids.isEmpty()) return null
        return ids.removeAt(0)
    }

    override fun setInitial(ids: List<String>) {
        this.ids.clear()
        for (id in ids) {
            if (id.isNotEmpty() && !this.ids.contains(id)) this.ids.add(id)
        }
    }
}