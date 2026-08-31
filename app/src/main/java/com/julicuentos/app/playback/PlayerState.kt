package com.julicuentos.app.playback

import com.julicuentos.app.catalog.Story

/**
 * The ONE observable playback state machine (specs/playback "Single ExoPlayer
 * source of truth"; design.md §1). Derived in [PlaybackRepository] from
 * ExoPlayer callbacks (playbackState, playWhenReady, isPlaying, errors)
 * plus the app intent flags (pendingLoad, userPaused, endedFlag). UI must
 * never derive playback state from loosely-consistent booleans.
     */

sealed class PlayerState {

    /** Nothing current (fresh install / unknown-id restore / after close. */
    data object Idle : PlayerState()

    /**
     * A load has been requested but the player has not settled yet.
     * Set BEFORE the player is touched (load contract step 1), so the UI
     * paints immediately. Cleared when the player reaches READY (or ENDED/ERROR
     * for that load; stale events from a superseded load never clear a newer one.
     */
    data class Loading(val story: Story, val autoplay: Boolean) : PlayerState()

    /** Prepared, paused, not-yet-started (cold restore lands here by design). */
    data object Ready : PlayerState()

    data object Playing : PlayerState()

    /** The user paused (userPaused latch distinguishes this from Ready. */
    data object Paused : PlayerState()

    /** Natural end reached with an empty queue → stopped, queue preserved. */
    data object Ended : PlayerState()

    /**
     * A load/decoder error surfaced. Nothing auto-recovers (no recovery layer
     * by spec"; a new load retries.This slice carries the library message verbatim;r
     * the Spanish error card arrives with the UI slices.
     */
    data class Error(val message: String) : PlayerState()
}