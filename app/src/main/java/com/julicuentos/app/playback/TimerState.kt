package com.julicuentos.app.playback

/**
 * Sleep-timer modes (specs/sleep-timer "Timer modes"; tasks S2.3/S5.3). Exactly
 * three modes, one active at a time:
 *  - [Off] — no timer;
 *  - [Minutes] — countdown anchored to `SystemClock.elapsedRealtime()`
 *    (`endsAt = elapsedRealtime + minutes*60_000`, design.md D3 — monotonic,
 *    screen-off-proof, never wall-clock);
 *  - [EndOfStory] — expires at the natural end of the current story (suppresses
 *    auto-advance, queue preserved).
 */
sealed class TimerState {
    object Off : TimerState()
    data class Minutes(val minutes: Int, val endsAt: Long) : TimerState()
    data object EndOfStory : TimerState()
}