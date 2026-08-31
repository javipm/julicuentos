package com.julicuentos.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.catalog.StoryRepository
import java.util.concurrent.CopyOnWriteArrayList

// Process-scoped singleton bridging UI to the media session through a single
// MediaController (design.md D5; tasks S2.7). The repository owns:
//  - the seven-state PlayerState machine (translated from controller callbacks
//    plus the app intent flags pendingLoad/userPaused/endedFlag/errorMssg),
//  -the load-story contract (select state first, replace media item, prepare,
//    autoplay; a monotonic loadGen generation guard discards stale events),
//  -~500 ms progress cadence (spec ~2 Hz targeted updates; only while the
//    player holds content Ready/Playing/Paused),
//  -play/pause/toggle/seek/skip/next API used by UI and the notification alike.

// All callbacks land on the main looper (controller application looper + main
// Handler); writes are single-threaded on main — no locks beyond the singleton
// and connection guards.
class PlaybackRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val catalog = StoryRepository.get(context)
    private val queue: QueueStore = InMemoryQueueStore()
    private val audioResolver = AudioSourceResolver(appContext, AudioSourceResolver.Strategy.ASSET_DIRECT)

    // ---------- connection state ----------
    @Volatile private var controller: MediaController? = null
    @Volatile private var connected = false
    private var connectInFlight = false
    private var released = false

    // ---------- derived-state inputs ----------
    private var current: Story? = null
    private var pendingLoad: Story? = null
    private var pendingAutoplay = false
    private var userPaused = false
    private var endedFlag = false
    private var errorMessage: String? = null
    private var loadGen = 0L

    // A load requested before the controller finished connecting;replayed on connect.,

    private data class PendingLoad(
        val storyId: String,
        val startPositionMs: Long,
        val autoplay: Boolean
    )

    private var deferredLoad: PendingLoad? = null

    // ---------- listener registries (main-thread updates only) ----------
    private val stateListeners = CopyOnWriteArrayList<StateListener>()
    private val progressListeners = CopyOnWriteArrayList<ProgressListener>()
    private val connectListeners = CopyOnWriteArrayList<ConnectListener>()

    fun interface StateListener {
        fun onStateChanged(state: PlayerState)
    }

    fun interface ProgressListener {
        fun onProgress(snapshot: ProgressSnapshot)
    }

    fun interface ConnectListener {
        fun onConnectedChanged(connected: Boolean)
    }

    data class ProgressSnapshot(
        val storyId: String?,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean
    )

    // =====================================================================
    // Connection
    // =====================================================================

    // Starts (or confirms) an async controller connection. Idempotent;
    // unexpected disconnects are retried with a bounded backoff (1,2,4...16 s.



    fun connect() {
        if (connected || connectInFlight || released) return
        connectInFlight = true
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = MediaController.Builder(appContext, token)
            .setListener(sessionListener)
            .buildAsync()
        future.addListener({
            try {
                val c = future.get()
                onControllerReady(c)
            } catch (e: Exception) {
                onConnectFailed()
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    private fun onControllerReady(c: MediaController) {
        controller = c
        connectInFlight = false
        connected = true
        connectFailures = 0
        c.addListener(playerListener)
        connectListeners.forEach { it.onConnectedChanged(true) }
        val deferred = deferredLoad
        deferredLoad = null
        if (deferred != null) load(deferred.storyId, deferred.startPositionMs, deferred.autoplay)
        publishState()
    }

    private fun onConnectFailed() {
        connectInFlight = false
        connectFailures += 1
        val delay = (1L shl minOf(connectFailures, 4)).coerceAtMost(16000L)
        mainHandler.postDelayed({
            if (!connected && !released) connect()
        }, delay)
    }

    private var connectFailures = 0


    // =====================================================================
    // Observable state snapshot
    // =====================================================================

    val isConnected: Boolean get() = connected
    val currentStory: Story? get() = current

    val state: PlayerState get() = deriveState()

    // Snapshot for targeted progress listeners; -1 sentinels when unknown.
    fun progressSnapshot(): ProgressSnapshot {
        val c = controller
        val playing = c?.isPlaying == true


        return ProgressSnapshot(
            storyId = current?.id,
            positionMs = if (c != null) c.currentPosition else -1L,
            durationMs = if (c != null) c.duration else -1L,
            isPlaying = playing
        )
    }

    // =====================================================================
    // Commands (UI + notification; single code path).
    // =====================================================================

    // Loads a story per the load contract: UI state first (Loading), then
    // release previous media item via setMediaItem, prepare, autoplay opon.

    fun load(storyId: String, startPositionMs: Long = 0L, autoplay: Boolean = true) {
        val story = catalog.getById(storyId)
        if (story == null) {
            surfaceError("Unknown story: $storyId")
            return
        }
        if (controller == null) {
            // Controller still connecting: park the request and replay on ready.


            deferredLoad = PendingLoad(storyId, startPositionMs, autoplay)

            return
        }
        performLoad(story, startPositionMs, autoplay)

    }

    private fun performLoad(story: Story, startPositionMs: Long, autoplay: Boolean) {
        loadGen += 1
        val myGen = loadGen
        pendingLoad = story
        pendingAutoplay = autoplay
        errorMessage = null
        userPaused = false
        endedFlag = false
        current = story
        publishState() // Loading paints before the player is touched.

        val c = controller ?: return
        // Async resolution: ASSET_DIRECT answers inline; COPY_TO_FILES copies on a
        // worker thread first (review R2-004). The generation guard discards answers
        // belonging to a superseded load (design D5 stale-event contract).
        audioResolver.resolveAsync(story) { item ->
            if (loadGen != myGen) return@resolveAsync
            if (item == null) {
                // Missing audio asset — error condition, no crash (D1).
                surfaceError("Audio file missing for ${story.id}")
                return@resolveAsync
            }
            c.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
            c.prepare()
            if (autoplay) c.play() else c.pause()
            // NOTE: no clearing of pendingLoad here — events from the newer item
            // settle it once READY is observed, discarding any stale generations.
        }
    }

    fun play() {
        endedFlag = false
        userPaused = false
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED && current != null) {
            // Replay from 0 after an ended story (UI layer decides on copy; sane default.



            c.seekTo(0L)
        }
        c.play()
        publishState()
    }

    fun pause() {
        userPaused = true
        val c = controller ?: return
        c.pause()
        publishState()
    }

    fun togglePlayPause() {
        if (state == PlayerState.Playing) pause() else play()
    }

    fun seekTo(positionMs: Long) {

        val c = controller ?: return
        if (current == null) return
                // Real duration wins; until metadata arrives, clamp against the catalog
        // duration instead of Long.MAX_VALUE (a pre-READY +15s could otherwise drive
        // the player to its internal end clamp -> premature Ended, review R2-006).
        val real = c.duration
        val fallback = (current?.duracionSegundos ?: 0).toLong() * 1000L
        val duration = if (real > 0L) real else fallback
        if (duration <= 0L) return
        val clamped = positionMs.coerceIn(0L, duration)
        c.seekTo(clamped)
        publishState()
    }

    fun skipBy(deltaMs: Long) {
        val c = controller ?: return
        seekTo(c.currentPosition + deltaMs)

    }

    // Manual next (player transport and notification): queue head if any, else
    // circular next in the alphabetical catalog (specs/playback; S2.5).

    fun playNext() {
        val nextId = resolveNextId(current?.id)
        if (nextId != null) load(nextId, 0L, true)
    }

    // ---------- next-resolution (queue head else circular catalog next ----------

    private fun resolveNextId(currentStoryId: String?): String? {
        queue.peekNext(currentStoryId)?.let { return it }
        val ids = catalog.stories().map { it.id }
        if (ids.isEmpty()) return null
        val idx = currentStoryId?.let { ids.indexOf(it) } ?: -1
        return if (idx >= 0) ids[(idx + 1) % ids.size] else ids[0]
    }

    // =====================================================================
    // Listener registries
    // =====================================================================

    fun addStateListener(listener: StateListener) { stateListeners.addIfAbsent(listener) }
    fun removeStateListener(listener: StateListener) { stateListeners.remove(listener) }
    fun addProgressListener(listener: ProgressListener) { progressListeners.addIfAbsent(listener) }
    fun removeProgressListener(listener: ProgressListener) { progressListeners.remove(listener) }
    fun addConnectListener(listener: ConnectListener) { connectListeners.addIfAbsent(listener) }
    fun removeConnectListener(listener: ConnectListener) { connectListeners.remove(listener) }

    // =====================================================================
    // State derivation
    // =====================================================================

    private fun deriveState(): PlayerState {

        val mssg = errorMessage
        if (mssg != null) return PlayerState.Error(mssg)


        val pending = pendingLoad

        if (pending != null) return PlayerState.Loading(pending, pendingAutoplay)


        if (endedFlag) return PlayerState.Ended
        val c = controller ?: return PlayerState.Idle

        return when (c.playbackState) {

            Player.STATE_READY -> {
                if (c.isPlaying) PlayerState.Playing
                else if (userPaused) PlayerState.Paused
                else PlayerState.Ready
            }

            Player.STATE_BUFFERING -> {
                if (current != null) PlayerState.Loading(current!!, pendingAutoplay)
 else PlayerState.Idle
            }

            else -> PlayerState.Idle
        }
    }

    private fun surfaceError(message: String) {
        pendingLoad = null
        endedFlag = false
        errorMessage = message
        publishState()
    }

    private fun publishState() {
        val snapshot = state
        for (listener in stateListeners) listener.onStateChanged(snapshot)




    }

    // =====================================================================
    // Controller listener + progress ticks
    // =====================================================================

    /**
     * Session-events listener (separate from [playerListener]: in media3 1.2.1
     * MediaController.Listener does not extend Player.Listener and is registered
     * via MediaController.Builder.setListener). Handles unexpected service death
     * with the bounded reconnect backoff (review R2-005).
     */
    private val sessionListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            connected = false
            connectInFlight = false
            if (controller === this@PlaybackRepository.controller) {
                this@PlaybackRepository.controller = null
            }
            connectListeners.forEach { it.onConnectedChanged(false) }
            if (!released) connect()
        }
    }

    private val playerListener = object : Player.Listener {

        override fun onPlaybackStateChanged(playbackState: Int) {
            onPlaybackEvent()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            onPlaybackEvent()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {

            onPlaybackEvent()
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            onPlayerErrorInternal(error)
        }
    }

    // Central gate: only events tied to the CURRENT generation / current item may
    // alter the derived state; events from a superseded load are discarded (D5; the
    // load contract step 4: "discard any stale completion/error events".

    private fun onPlaybackEvent() {
        val c = controller ?: return
        // If a newer load is in flight, events describing the previous item state

        // are irrelevant;the settle happens for the current generation below.


        if (pendingLoad != null && !isCurrentItem(c, pendingLoad!!.id))return


        if (c.playbackState == Player.STATE_ENDED) {

            onEnded()
            return
        }
        // Settle the pending load once READY arrives for the current item.


        if (pendingLoad != null && c.playbackState == Player.STATE_READY) {

            pendingLoad = null
        }
        publishState()
        maybeStartProgress()
    }

    private fun onEnded() {
        pendingLoad = null
        endedFlag = true
        val c = controller ?: return
        c.pause()
        // Queue auto-advance / end_of_story suppression land in slice 5; with an
        // empty queue (slice-2 state) playback stops per specs/playback "End of story
        // vs manual next (queue asymmetry)".


        publishState()
        maybeStopProgress()
    }

    private fun onPlayerErrorInternal(error: androidx.media3.common.PlaybackException) {
        val c = controller ?: return
        if (pendingLoad == null && !isCurrentItem(c, current?.id)) return

        pendingLoad = null
        // Media3 dispatches listener events on the main thread and callbacks read the
        // player's CURRENT state at dispatch time; c.playerError is cleared by the next
        // prepare(). Surfacing it instead of the raw callback payload means a superseded
        // same-id (stale-generation) error event can never label a healthy new load as
        // failed (review R2-003).
        surfaceError(c.playerError?.message ?: error.message ?: "Playback error")
        maybeStopProgress()
    }

    private fun isCurrentItem(c: MediaController, storyId: String?): Boolean {

        if (storyId == null) return false
        return c.currentMediaItem?.mediaId == storyId
    }

    // ---------- 500 ms progress cadence ----------

    private var progressRunnable: Runnable? = null

    private fun maybeStartProgress() {

        if (progressRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {

                tickProgress()
                if (shouldKeepTicking()) {
                    mainHandler.postDelayed(this, 500L)
                } else {
                    // Clear the slot so a later maybeStartProgress() can re-arm; leaving
                    // it set blocked every future progress tick (review R2-002).
                    progressRunnable = null
                }

            }
        }
        progressRunnable = runnable
        mainHandler.post(runnable)




    }

    private fun shouldKeepTicking(): Boolean {

        val s = state
        return s is PlayerState.Ready || s is PlayerState.Playing || s is PlayerState.Paused
    }

    private fun maybeStopProgress() {

        val r = progressRunnable ?: return
        progressRunnable = null
        mainHandler.removeCallbacks(r)

    }

    private fun tickProgress() {
        if (progressListeners.isEmpty()) return
        val c = controller ?: return
        if (current == null) return
        val snapshot = progressSnapshot()
        for (listener in progressListeners) listener.onProgress(snapshot)

    }

    // =====================================================================
    // Singleton

    // =====================================================================

    companion object {
        @Volatile private var instance: PlaybackRepository? = null

        // Process-scoped singleton (design D5: process-lifetime state).
        fun get(context: Context): PlaybackRepository {




            return instance ?: synchronized(this) {
                instance ?: PlaybackRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
