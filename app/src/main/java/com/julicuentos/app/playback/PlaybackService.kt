package com.julicuentos.app.playback

import android.content.Intent

import android.os.Bundle
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
  *  Background playback core (tasks S2.5; design.md D5/D8). A single
  *  [MediaSessionService] owning the ONLY ExoPlayer in the process:
  *
  *  - Audio attributes: USAGE_MEDIA + CONTENT_TYPE_MUSIC with handleAudioFocus=true
  *    (ExoPlayer's AudioFocusManager implements the spec matrix on API 22 via the
  *    legacy AudioManager.requestAudioFocus path — D8).
  *  - `setHandleAudioBecomingNoisy(true)` — headphone unplug pauses (no speaker blast).
  *  - `setWakeMode(WAKE_MODE_LOCAL)` — PARTIAL_WAKE_LOCK while playing (D8),
  *    plus the media foreground service pins the process.
  *
  *  - Custom session commands SKIP_BACK_15 / SKIP_FWD_15 / NEXT (design D2:
  *    they must exist as custom SessionCommands on API 22 where framework skip
  *    presets do not). All three are forwarded to [PlaybackRepository] so there is
  *    exactly one playback code path (repository owns the load contract and the clamp
  *    semantics; design D5 layering`).
  *  - The foreground/notification handoff is owned by the media3 session service
  *    manager (via [MediaNotificationProvider], set before onCreate returns); no
  *    manual `startForeground` here (S2.5; design D2).
 */
class PlaybackService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

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
                    // Keep the stock session commands and add our custom ones (D2).
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(MediaNotificationProvider.ACTION_SKIP_BACK_15, Bundle()))
                        .add(SessionCommand(MediaNotificationProvider.ACTION_SKIP_FWD_15, Bundle()))
                        .add(SessionCommand(MediaNotificationProvider.ACTION_NEXT, Bundle()))
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

        // Belt-and-braces:at natural end (empty queue) playback must not linger
        // in a play-when-ready=true ENDED state;the repository drives the authored
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

    /**
      *  "Stop if not playing" (delegation S2.5):when the the user swipes the task away
      *  while nothing is audible, playback is stopped. While actually playing, playback
      *  continues (the foreground service + wake lock keep it alive; the notification drives
      *  control). Note: [PlaybackRepository] holds a controller binding for the process
      *  lifetime,, so the service is normally not destroyed here — this is the authored
      *  "sensible behavior" gate, not a hard unload..
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = player
        if (p == null || !p.isPlaying) {
            stopPlaybackQuietly()
        }
        super.onTaskRemoved(rootIntent)

    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null


        player?.release()
        player = null
        super.onDestroy()
    }

    // --- custom commands (single code path via the repository) ---

    private fun handleCustomCommand(command: SessionCommand) {
        val repository = PlaybackRepository.get(applicationContext)
        when (command.customAction) {
            MediaNotificationProvider.ACTION_SKIP_BACK_15 -> repository.skipBy(-15_000L)
            MediaNotificationProvider.ACTION_SKIP_FWD_15 -> repository.skipBy(15_000L)
            MediaNotificationProvider.ACTION_NEXT -> repository.playNext()
        }
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
