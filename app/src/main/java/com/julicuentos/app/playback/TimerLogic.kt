package com.julicuentos.app.playback

import kotlin.math.abs

/**
 * Pure sleep-timer math (tasks S2.3/S5.3/S5.5; design.md D3; specs/sleep-timer).
 * No Android imports: every function takes the "now" anchor explicitly so the
 * logic is JVM unit-testable; the runtime caller passes
 * `SystemClock.elapsedRealtime()` (monotonic — never wall-clock, per spec).
 *
 * Covers: [endsAtFor] (set-time anchor), [isValidMinutes], [normalizeRestore]
 * (the D3 validity window applied at restore/parse time), and the 10-step
 * 1-second fade schedule [fadeVolumeAtStep] (expiry = ~10 s fade → pause).
 */
object TimerLogic {

    /** The only legal countdown lengths (specs/sleep-timer "Timer modes"). */
    val MINUTES = setOf(15, 30, 45)

    /** Expiry fade: 10 steps × 1 s, volume 1.0 → 0.0 (API-22-legal stepped fade). */
    const val FADE_STEPS = 10
    const val FADE_STEP_MS = 1000L

    /** Set-time anchor: `endsAt = now + minutes*60_000` (design.md D3). */
    fun endsAtFor(minutes: Int, nowMs: Long): Long = nowMs + minutes * 60_000L

    fun isValidMinutes(minutes: Int): Boolean = minutes in MINUTES

    /** Remaining countdown at [nowMs]; clamped at 0 (expired). */
    fun remainingMs(state: TimerState, nowMs: Long): Long {
        val s = state as? TimerState.Minutes ?: return 0L
        return (s.endsAt - nowMs).coerceAtLeast(0L)
    }

    /**
     * Restore-time normalization (design.md D3 "Restore validity window"; spec
     * "Invalid timer value normalizes to off" / "Expired minutes timer does not
     * self-pause on restore"): a Minutes timer is accepted ONLY when
     *  - [minutes] ∈ {15,30,45},
     *  - [endsAt] is a positive anchor,
     *  - the remaining window is sane: not already expired (≤0) and not
     *    far-future (a reboot resets uptime, making an old anchor look like it
     *    fires in the distant future → invalid → off), and
     *  - within the absolute 24 h sanity cap (|now - endsAt| > 24 h → off).
     *
     * An expired/invalid timer degrades to [TimerState.Off] — the restore is
     * paused by definition, so nothing self-pauses; story/position/queue restore
     * normally (the "no self-pause surprise" rule).
     */
    fun normalizeRestore(endsAt: Long, minutes: Int, nowMs: Long): TimerState {
        if (!isValidMinutes(minutes)) return TimerState.Off
        if (endsAt <= 0L) return TimerState.Off
        val remaining = endsAt - nowMs
        if (remaining <= 0L) return TimerState.Off                            // expired
        if (remaining > minutes * 60_000L + 60_000L) return TimerState.Off    // reboot artifact
        if (abs(remaining) > 24L * 60L * 60L * 1000L) return TimerState.Off   // absolute sanity cap
        return TimerState.Minutes(minutes, endsAt)
    }

    /**
     * Volume for fade [step] (0..[FADE_STEPS]-1): `(FADE_STEPS-(step+1))/FADE_STEPS`
     * → 0.9, 0.8, … 0.1, 0.0. The final step reaching 0.0 is immediately followed
     * by pause() + volume reset to 1.0 (specs/sleep-timer "Expiry = ~10 s fade…").
     */
    fun fadeVolumeAtStep(step: Int, steps: Int = FADE_STEPS): Float {
        if (steps <= 0) return 0f
        val clamped = step.coerceIn(0, steps - 1)
        return (steps - (clamped + 1)).toFloat() / steps.toFloat()
    }
}