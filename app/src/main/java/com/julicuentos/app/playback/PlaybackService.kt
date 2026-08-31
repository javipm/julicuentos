package com.julicuentos.app.playback

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.julicuentos.app.notify.MediaNotificationProvider

/**
 * Background playback core (tasks S2.5 + S5.3; design.md D5/D8). A single
 * [MediaSessionService] owning the ONLY ExoPlayer in the process:
 *
 *  - Audio attributes: USAGE_MEDIA + CONTENT_TYPE_MUSIC with handleAudioFocus=true
 *    (ExoPlayer's AudioFocusManager implements the spec matrix on API 22 via the
 *    legacy AudioManager.requestAudioFocus path — D8).
 *  - `setHandleAudioBecomingNoisy(true)` — headphone unplug pauses (no speaker blast).
 *  - `setWakeMode(WAKE_MODE_LOCAL)` — PARTIAL_WAKE_LOCK while playing (D8),
 *    plus the media foreground service pins the process.
 *
 *  - Custom session commands SKIP_BACK_15 / SKIP_FWD_15 / NEXT (design D2) plus
 *    the timer pair TIMER_EXPIRE / TIMER_CANCEL_FADE (S5.3). The transport ones
 *    forward to [PlaybackRepository] (one playback code path); the timer ones are
 *    executed HERE because only the service may touch the ExoPlayer volume —
 *    expiry runs a ~10 s stepped volume fade ending in pause() (the "gentle
 *    expiry" behavior delta vs the RN hard cut).
 *  - The foreground/notification handoff is owned by the media3 session service
 *    manager (via [MediaNotificationProvider], set before onCreate returns).
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /** Fade machinery (S5.3): 10 steps x 1 s, volume 1.0 -> 0.0, then pause. */
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fadeStep = -1
    private val fadeRunnable = object : Runnable {
        override fun run() {
            val p = player ?: return finishFade()
            // Belt-and-braces abort: if the user paused mid-fade (or any other
            // route stopped the player), cancel the fade and restore full volume —
            // never finish fading a paused silent player.
            if (!p.isPlaying) {
                finishFade()
                return
            }
            fadeStep += 1
            if (fadeStep >= TimerLogic.FADE_STEPS) {
                // Last step at 0.0 -> pause; volume back to 1.0; timer already off.
                p.pause()
                finishFade()
                return
            }
            p.volume = TimerLogic.fadeVolumeAtStep(fadeStep)
            mainHandler.postDelayed(this, TimerLogic.FADE_STEP_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val p = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        this.player = p

        mediaSession = MediaSession.Builder(this, p)
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo
                ): MediaSession.ConnectionResult {
                    // Stock session commands plus our custom ones (D2 + S5.3).
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(MediaNotificationProvider.ACTION_SKIP_BACK_15, Bundle()))
                        .add(SessionCommand(MediaNotificationProvider.ACTION_SKIP_FWD_15, Bundle()))
                        .add(SessionCommand(MediaNotificationProvider.ACTION_NEXT, Bundle()))
                        .add(SessionCommand(SessionActions.ACTION_TIMER_EXPIRE, Bundle()))
                        .add(SessionCommand(SessionActions.ACTION_TIMER_CANCEL_FADE, Bundle()))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle
                ): ListenableFuture<SessionResult> {
                    handleCustomCommand(customCommand)
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        setMediaNotificationProvider(MediaNotificationProvider(applicationContext))

        // Belt-and-braces: at natural end (empty stage) playback must not linger
        // in a play-when-ready=true ENDED state; the repository drives the authored
        // behavior (Ended + stop), this just keeps the ExoPlayer honest.
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && p.playWhenReady) {
                    p.setPlayWhenReady(false)
                }
            }
        })
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    /** "Stop if not playing" (delegation S2.5): swiping the task away while
     *  nothing is audible stops playback; while actually playing, playback
     *  continues (foreground service + wake lock; notification drives control). */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.isPlaying) {
            stopPlaybackQuietly()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        finishFade()
        // Immediate store flush per S5.6 (serve onDestroy flush).
        PlaybackRepository.get(applicationContext).flushNow()
        mediaSession?.release()
        mediaSession = null
        player?.release()
        player = null
        super.onDestroy()
    }

    // --- custom commands ---

    private fun handleCustomCommand(command: SessionCommand) {
        when (command.customAction) {
            MediaNotificationProvider.ACTION_SKIP_BACK_15 ->
                PlaybackRepository.get(applicationContext).skipBy(-15_000L)
            MediaNotificationProvider.ACTION_SKIP_FWD_15 ->
                PlaybackRepository.get(applicationContext).skipBy(15_000L)
            MediaNotificationProvider.ACTION_NEXT ->
                PlaybackRepository.get(applicationContext).playNext()
            SessionActions.ACTION_TIMER_EXPIRE -> startFade()
            SessionActions.ACTION_TIMER_CANCEL_FADE -> finishFade()
        }
    }

    /** Starts (or restarts) the ~10 s stepped fade to silence (S5.3; the expiry
     *  command is fired only while audio is actually playing — the repository
     *  holds that check; this method still double-checks per step). */
    private fun startFade() {
        val p = player ?: return
        if (!p.isPlaying) return
        mainHandler.removeCallbacks(fadeRunnable)
        fadeStep = -1
        mainHandler.post(fadeRunnable)
    }

    /** Cancels any running fade and resets the volume to 1.0 (spec cancel rules;
     *  also called on destroy — always idempotent). */
    private fun finishFade() {
        mainHandler.removeCallbacks(fadeRunnable)
        fadeStep = -1
        player?.volume = 1.0f
    }

    private fun stopPlaybackQuietly() {
        val p = player ?: return
        if (p.playbackState != Player.STATE_IDLE) {
            p.stop()
            p.clearMediaItems()
        }
        stopSelf()
    }
}
