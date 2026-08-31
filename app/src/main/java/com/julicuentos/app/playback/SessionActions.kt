package com.julicuentos.app.playback

/**
 * Custom session commands exchanged between [PlaybackRepository] and
 * [PlaybackService] (tasks S5.3; design.md D2 pattern — the skip/next custom
 * commands live in MediaNotificationProvider; these are the timer's two).
 * The repository owns the timer state and the 1 Hz countdown; the service owns
 * the ExoPlayer, so the two player-touching outcomes of an expiry are requested
 * through the session:
 *  - [ACTION_TIMER_EXPIRE]: service runs the ~10 s volume fade then pause
 *    (specs/sleep-timer "Expiry = ~10 s fade…");
 *  - [ACTION_TIMER_CANCEL_FADE]: user pause/play/timer-change mid-fade cancels
 *    the fade runnable and resets volume to 1.0 (spec cancel rules; idempotent).
 */
object SessionActions {
    const val ACTION_TIMER_EXPIRE = "jc.action.timer_expire"
    const val ACTION_TIMER_CANCEL_FADE = "jc.action.timer_cancel_fade"
}
