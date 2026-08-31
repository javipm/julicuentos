package com.julicuentos.app.persist

import com.julicuentos.app.playback.TimerState

/**
 * Cold-restore decision logic (tasks S5.7; specs/persistence; design.md §3
 * "Cold restore"). Pure function — JVM unit-tested. The repository orchestrates
 * the actual load after [decide] returns.
 *
 * Guards, in order:
 *  1. [RestoreDecision.applySnapshot] = false when [userMutated] — a user action
 *     during the async restore wins; the stale snapshot is discarded wholesale
 *     (specs/persistence "Never overwrite user actions taken during restore").
 *  2. An unknown/missing `currentStoryId` → `currentStoryId = null` (state stays
 *     Idle, no miniplayer, no load) while the known queue ids still restore
 *     (specs/persistence "Restore drops unknown ids").
 *  3. Unknown queue ids are filtered silently (queue self-heals; specs/queue
 *     "Ids-only queue model" / "Unknown ids are dropped safely").
 *  4. The timer arrives already normalized (`endsAt` window applied at parse
 *     time), so an expired/invalid timer is already `Off` — story/position/queue
 *     restore normally, nothing self-pauses.
 */
data class RestoreDecision(
    val applySnapshot: Boolean,
    val currentStoryId: String?,
    val positionMs: Long,
    val queueIds: List<String>,
    val timer: TimerState
)

object RestoreCoordinator {

    fun decide(
        snapshot: PersistedState,
        userMutated: Boolean,
        knownIds: Set<String>
    ): RestoreDecision {
        if (userMutated) {
            return RestoreDecision(
                applySnapshot = false,
                currentStoryId = null,
                positionMs = 0L,
                queueIds = emptyList(),
                timer = TimerState.Off
            )
        }
        val current = snapshot.currentStoryId?.takeIf { it in knownIds }
        val queue = snapshot.queueIds.filter { it in knownIds }
        return RestoreDecision(
            applySnapshot = true,
            currentStoryId = current,
            positionMs = snapshot.positionMs.coerceAtLeast(0L),
            queueIds = queue,
            timer = snapshot.timer
        )
    }
}