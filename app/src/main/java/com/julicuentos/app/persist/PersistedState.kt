package com.julicuentos.app.persist

import com.julicuentos.app.playback.TimerLogic
import com.julicuentos.app.playback.TimerState

/**
 * The one persisted player blob (tasks S5.5; specs/persistence "Single storage
 * key, fixed JSON schema"). Everything the player needs across cold starts:
 * current story id, the single global resume position, the ordered queue ids,
 * the sleep-timer, and a write-time stamp that is never read.
 *
 * Schema (nothing more, nothing less):
 * ```
 * { "currentStoryId": string|null, "positionMs": 0, "queueIds": [..],
 *   "timer": { "kind": "off"|"minutes"|"end_of_story", "minutes": 15|30|45?,
 *              "endsAt": 0? }, "updatedAt": 0 }
 * ```
 * `timer.endsAt` stores the `SystemClock.elapsedRealtime()` anchor
 * (design.md D3) — the runtime countdown is anchored to the monotonic clock,
 * never wall-clock. `updatedAt` is written (timestamp) but never read (no
 * TTL/expiry logic).
 */
data class PersistedState(
    val currentStoryId: String?,
    val positionMs: Long,
    val queueIds: List<String>,
    val timer: TimerState,
    val updatedAt: Long
) {
    companion object {
        /** Fresh-install defaults (specs/persistence "Fresh install has clean defaults"). */
        fun empty(): PersistedState = PersistedState(
            currentStoryId = null,
            positionMs = 0L,
            queueIds = emptyList(),
            timer = TimerState.Off,
            updatedAt = 0L
        )
    }

    /** Serialises to the fixed JSON schema (via [MiniJson]; quoted/escaped safely). */
    fun toJson(): String {
        val (kind, minutes, endsAt) = when (val t = timer) {
            is TimerState.Off -> Triple("off", null as Long?, null as Long?)
            is TimerState.Minutes -> Triple("minutes", t.minutes.toLong(), t.endsAt)
            is TimerState.EndOfStory -> Triple("end_of_story", null, null)
        }
        return MiniJson.writeObject(
            listOf(
                "currentStoryId" to currentStoryId,
                "positionMs" to positionMs,
                "queueIds" to queueIds,
                "timer" to MiniJson.raw(
                    MiniJson.writeObject(
                        listOf(
                            "kind" to kind,
                            "minutes" to minutes,
                            "endsAt" to endsAt
                        )
                    )
                ),
                "updatedAt" to updatedAt
            )
        )
    }
}

/**
 * Tolerant parser (specs/persistence "Tolerant parser with safe defaults";
 * tasks S5.5). Validator-first: bad/truncated JSON, wrong types or missing
 * fields yield safe defaults instead of a crash — currentStoryId null,
 * positionMs 0, queueIds [], timer off. Specifically:
 *  - a non-numeric / NaN / negative `positionMs` → 0;
 *  - non-string `queueIds` entries are filtered out (unknown ids are dropped by
 *    the restore coordinator, not here);
 *  - `timer.minutes ∉ {15,30,45}` or a missing/invalid `endsAt` → `off`;
 *  - `end_of_story` and `off` are accepted as-is;
 *  - the D3 validity window is applied to `minutes` timers at parse time
 *    (expired/reboot-artifact anchors → `off` — the "no self-pause" rule).
 */
object PersistedStateParser {

    fun parse(raw: String?, nowMs: Long): PersistedState {
        if (raw.isNullOrBlank()) return PersistedState.empty()
        val root = MiniJson.parseObject(raw) ?: return PersistedState.empty()

        val currentStoryId = (root["currentStoryId"] as? String)?.takeIf { it.isNotEmpty() }
        val positionMs = toNonNegativeLong(root["positionMs"])
        val queueIds = (root["queueIds"] as? List<*>)
            ?.filterIsInstance<String>()
            ?.filter { it.isNotEmpty() } ?: emptyList()
        val timer = parseTimer(root["timer"], nowMs)
        val updatedAt = toNonNegativeLong(root["updatedAt"])

        return PersistedState(
            currentStoryId = currentStoryId,
            positionMs = positionMs,
            queueIds = queueIds,
            timer = timer,
            updatedAt = updatedAt
        )
    }

    private fun parseTimer(value: Any?, nowMs: Long): TimerState {
        val obj = value as? Map<*, *> ?: return TimerState.Off
        val kind = obj["kind"] as? String ?: return TimerState.Off
        return when (kind) {
            "off" -> TimerState.Off
            "end_of_story" -> TimerState.EndOfStory
            "minutes" -> {
                val minutes = (obj["minutes"] as? Number)?.toInt() ?: return TimerState.Off
                val endsAt = (obj["endsAt"] as? Number)?.toLong() ?: return TimerState.Off
                TimerLogic.normalizeRestore(endsAt, minutes, nowMs)
            }
            else -> TimerState.Off
        }
    }

    /** Long from number/null; anything else (or negative/NaN) → 0. */
    private fun toNonNegativeLong(value: Any?): Long {
        val n = value as? Number ?: return 0L
        return when (n) {
            is Long -> n.coerceAtLeast(0L)
            is Int -> n.toLong().coerceAtLeast(0L)
            is Double -> if (n.isFinite() && n >= 0.0) n.toLong() else 0L
            else -> n.toLong().coerceAtLeast(0L)
        }
    }
}