package com.julicuentos.app.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.catalog.StoryRepository
import com.julicuentos.app.persist.PersistedState
import com.julicuentos.app.persist.PlayerStore
import com.julicuentos.app.persist.RestoreCoordinator
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-scoped singleton bridging UI to the media session through a single
 * MediaController (design.md D5; tasks S2.7 + slice 5). The repository owns:
 *  - the seven-state PlayerState machine (translated from controller callbacks
 *    plus the app intent flags pendingLoad/userPaused/endedFlag/errorMssg),
 *  - the load-story contract (select state first, replace media item, prepare,
 *    autoplay; a monotonic loadGen generation guard discards stale events),
 *  - ~500 ms progress cadence (spec ~2 Hz targeted updates; only while the
 *    player holds content Ready/Playing/Paused),
 *  - play/pause/toggle/seek/skip/next API used by UI and the notification alike,
 *  - queue mutations (S5.1/S2.2): enqueue de-dup, move up/down clamped, remove,
 *    clear, consuming takeNext for manual next and auto-advance + QueueListener,
 *  - the sleep timer (S5.3/S2.3): set/clear/minutes/end_of_story, a 1 Hz
 *    elapsedRealtime-anchored countdown publishing remaining ms to listeners;
 *    expiry arms the service fade (timer = off, queue untouched); end_of_story
 *    suppresses auto-advance at natural end,
 *  - hydration + restore (S5.6/S5.7): one PlayerStore read on first connect,
 *    RestoreCoordinator decision (user-wins guard, unknown ids dropped,
 *    expired timer -> off -> no self-pause), hydrationComplete gate so NO write
 *    ever happens before the initial restore finished (the blank-queue bug),
 *  - the 5 s flush cadence (dirty-tracked) + flushNow() on release/expiry.
 *
 * All callbacks land on the main looper (controller application looper + main
 * Handler); writes are single-threaded on main - no locks beyond the singleton
 * and connection guards.
 */
class PlaybackRepository private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val catalog = StoryRepository.get(context)
    private val queue: QueueStore = InMemoryQueueStore()
    private val store = PlayerStore(appContext)
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

    // A load requested before the controller finished connecting; replayed on connect.
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
    private val queueListeners = CopyOnWriteArrayList<QueueListener>()
    private val timerListeners = CopyOnWriteArrayList<TimerListener>()

    fun interface StateListener {
        fun onStateChanged(state: PlayerState)
    }

    fun interface ProgressListener {
        fun onProgress(snapshot: ProgressSnapshot)
    }

    fun interface ConnectListener {
        fun onConnectedChanged(connected: Boolean)
    }

    /** Fired on ANY queue content change (enqueue/move/remove/clear/restore/advance). */
    fun interface QueueListener {
        fun onQueueChanged()
    }

    /** Carries the timer mode + remaining ms for the 1 Hz player line. */
    fun interface TimerListener {
        fun onTimerChanged(snapshot: TimerSnapshot)
    }

    data class TimerSnapshot(val state: TimerState, val remainingMs: Long)

    data class ProgressSnapshot(
        val storyId: String?,
        val positionMs: Long,
        val durationMs: Long,
        val isPlaying: Boolean
    )

    // =====================================================================
    // Connection
    // =====================================================================

    /** Starts (or confirms) an async controller connection. Idempotent;
     *  unexpected disconnects are retried with a bounded backoff (1,2,4...16 s). */
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
        // Replay any user request parked during connect BEFORE hydration: user
        // intent wins over the stale snapshot (specs/persistence guard 2).
        val deferred = deferredLoad
        deferredLoad = null
        if (deferred != null) load(deferred.storyId, deferred.startPositionMs, deferred.autoplay)
        hydrate()
        connectListeners.forEach { it.onConnectedChanged(true) }
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

    /** Snapshot for targeted progress listeners; -1 sentinels when unknown. */
    fun progressSnapshot(): ProgressSnapshot {
        val c = controller
        return ProgressSnapshot(
            storyId = current?.id,
            positionMs = if (c != null) c.currentPosition else -1L,
            durationMs = if (c != null) c.duration else -1L,
            isPlaying = c?.isPlaying == true
        )
    }

    // =====================================================================
    // Commands (UI + notification; single code path)
    // =====================================================================

    /** Loads a story per the load contract: UI state first (Loading), then
     *  release previous media item via setMediaItem, prepare, autoplay. */
    fun load(storyId: String, startPositionMs: Long = 0L, autoplay: Boolean = true) {
        markUserMutated()
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
        cancelAnyFade()
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
        markUserMutated()
        endedFlag = false
        userPaused = false
        cancelAnyFade()
        val c = controller ?: return
        if (c.playbackState == Player.STATE_ENDED && current != null) {
            c.seekTo(0L)
        }
        c.play()
        publishState()
        markDirty()
    }

    fun pause() {
        markUserMutated()
        userPaused = true
        cancelAnyFade()
        val c = controller ?: return
        c.pause()
        publishState()
        markDirty()
    }

    fun togglePlayPause() {
        if (state == PlayerState.Playing) pause() else play()
    }

    fun seekTo(positionMs: Long) {
        markUserMutated()
        val c = controller ?: return
        if (current == null) return
        // Real duration wins; until metadata arrives, clamp against the catalog
        // duration instead of Long.MAX_VALUE (a pre-READY +15s could otherwise
        // drive the player to its internal end clamp -> premature Ended, R2-006).
        val real = c.duration
        val fallback = (current?.duracionSegundos ?: 0).toLong() * 1000L
        val duration = if (real > 0L) real else fallback
        if (duration <= 0L) return
        val clamped = positionMs.coerceIn(0L, duration)
        c.seekTo(clamped)
        publishState()
        markDirty()
    }

    fun skipBy(deltaMs: Long) {
        val c = controller ?: return
        seekTo(c.currentPosition + deltaMs)
    }

    /**
     * Manual next (player transport and notification; specs/queue "Next
     * resolution and end-of-queue"). Consumes the queue head when non-empty
     * (current-in-queue can never re-arm itself), else wraps circularly in the
     * alphabetical catalog — the manual button wraps, auto-advance does not.
     */
    fun playNext() {
        markUserMutated()
        val nextId = queue.takeNext(current?.id) ?: circularNext(current?.id)
        if (nextId != null) {
            load(nextId, 0L, true)
            notifyQueueChanged()
            markDirty()
        }
    }

    // =====================================================================
    // Queue mutations (S5.1/S2.2; specs/queue) — every op notifies + dirties
    // =====================================================================

    /** Ordered copy of the current queue ids (UI snapshot). */
    fun queueSnapshot(): List<String> = queue.snapshot()

    /** Membership check for the player's + / mint-check button. */
    fun isQueued(storyId: String?): Boolean = storyId != null && queue.contains(storyId)

    fun enqueue(storyId: String) {
        markUserMutated()
        if (queue.enqueue(storyId)) {
            notifyQueueChanged()
            markDirty()
        }
    }

    fun moveQueueUp(index: Int) {
        markUserMutated()
        if (queue.moveUp(index)) {
            notifyQueueChanged()
            markDirty()
        }
    }

    fun moveQueueDown(index: Int) {
        markUserMutated()
        if (queue.moveDown(index)) {
            notifyQueueChanged()
            markDirty()
        }
    }

    fun removeFromQueue(storyId: String) {
        markUserMutated()
        if (queue.remove(storyId)) {
            notifyQueueChanged()
            markDirty()
        }
    }

    fun clearQueue() {
        markUserMutated()
        queue.clear()
        notifyQueueChanged()
        markDirty()
    }

    private fun notifyQueueChanged() {
        for (listener in queueListeners) listener.onQueueChanged()
    }

    // =====================================================================
    // Sleep timer (S5.3/S2.3; specs/sleep-timer; design.md D3)
    // =====================================================================

    private var timerState: TimerState = TimerState.Off
    private var timerRunnable: Runnable? = null

    val currentTimer: TimerState get() = timerState

    /** Remaining ms for the player line; 0 when no Minutes timer is active. */
    fun timerRemainingMs(): Long = TimerLogic.remainingMs(timerState, SystemClock.elapsedRealtime())

    fun setTimerMinutes(minutes: Int) {
        markUserMutated()
        val state = if (TimerLogic.isValidMinutes(minutes)) {
            TimerState.Minutes(minutes, TimerLogic.endsAtFor(minutes, SystemClock.elapsedRealtime()))
        } else {
            TimerState.Off
        }
        applyTimer(state)
    }

    fun setTimerEndOfStory() {
        markUserMutated()
        applyTimer(TimerState.EndOfStory)
    }

    fun clearTimer() {
        markUserMutated()
        applyTimer(TimerState.Off)
    }

    /** Single internal path: sets mode, (re)starts the 1 Hz countdown, notifies
     *  listeners, cancels any in-flight service fade (spec cancel rules). */
    private fun applyTimer(next: TimerState) {
        timerState = next
        cancelTimerTicks()
        if (next is TimerState.Minutes) startTimerTicks()
        cancelAnyFade() // timer change mid-fade cancels the fade, volume 1.0
        notifyTimer()
        markDirty()
    }

    /** Restore path (hydration): same as [applyTimer] but without marking the
     *  user-mutated flag or touching the service (nothing to fade at cold start). */
    private fun restoreTimer(next: TimerState) {
        timerState = next
        cancelTimerTicks()
        if (next is TimerState.Minutes) startTimerTicks()
        notifyTimer()
    }

    /** 1 Hz countdown on the main looper, anchored to elapsedRealtime (the
     *  countdown survives screen-off; expiry logic below). */
    private fun startTimerTicks() {
        if (timerRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                tickTimer()
                if (timerState is TimerState.Minutes && !released) {
                    mainHandler.postDelayed(this, 1000L)
                } else {
                    timerRunnable = null
                }
            }
        }
        timerRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun cancelTimerTicks() {
        val r = timerRunnable
        if (r == null) return
        timerRunnable = null
        mainHandler.removeCallbacks(r)
    }

    private fun tickTimer() {
        val s = timerState as? TimerState.Minutes ?: return
        val remaining = s.endsAt - SystemClock.elapsedRealtime()
        if (remaining <= 0L) expireTimer() else notifyTimer()
    }

    /**
     * Expiry: timer -> off immediately (UI line + ring clear), then, only when
     * audio is actually playing, arm the service's ~10 s volume fade which ends
     * in pause() + volume 1.0 (design.md D3 "the fade arms only if playback is
     * actually playing"). Queue untouched. Immediate store flush per S5.3.
     */
    private fun expireTimer() {
        timerState = TimerState.Off
        cancelTimerTicks()
        val wasPlaying = deriveState() is PlayerState.Playing
        notifyTimer()
        if (wasPlaying) sendCustomCommand(SessionActions.ACTION_TIMER_EXPIRE)
        markDirty()
        flushNow() // immediate flush: timer off + position persist BEFORE the fade
    }

    private fun notifyTimer() {
        val remaining = TimerLogic.remainingMs(timerState, SystemClock.elapsedRealtime())
        val snapshot = TimerSnapshot(timerState, remaining)
        for (listener in timerListeners) listener.onTimerChanged(snapshot)
    }

    /** User pause/play/load/timer-change mid-fade cancels the service fade runnable
     *  and resets volume to 1.0 (specs/sleep-timer "cancel rules"; idempotent). */
    private fun cancelAnyFade() {
        sendCustomCommand(SessionActions.ACTION_TIMER_CANCEL_FADE)
    }

    private fun sendCustomCommand(action: String) {
        val c = controller ?: return
        val command = SessionCommand(action, Bundle())
        c.sendCustomCommand(command, Bundle())
    }

    // =====================================================================
    // Hydration + restore (S5.7) and the never-write-before-hydration gate
    // =====================================================================

    private var hydrationComplete = false

    /** Set by any user command issued before hydration finished. When set, the
     *  in-flight restore MUST be discarded — user intent wins (spec guard 2). */
    private var userMutated = false

    private fun markUserMutated() {
        if (!hydrationComplete) userMutated = true
    }

    /**
     * Cold-restore (specs/persistence; design.md §3): read the one key, decide
     * (user-wins / unknown ids filtered / expired timer already off), then apply
     * queue + timer and load the last story PAUSED at its last position
     * (autoplay=false — the only non-autoplay load in the app).
     */
    private fun hydrate() {
        if (hydrationComplete) return
        // Complete the gate first: every flush from here on is legal, and a
        // re-entrant call can never re-run the restore.
        hydrationComplete = true
        if (userMutated) {
            // Discard the snapshot wholesale (user intent wins) — but the user's own
            // mutations issued during connect are still UNPERSISTED (markDirty
            // early-returned pre-hydration). Re-arm the dirty flag so the next 5 s
            // tick / onStop flush writes the user's fresh state instead of silently
            // losing it (review R5-002).
            markDirty()
            return
        }
        val snapshot = store.read()
        val knownIds = catalog.stories().map { it.id }.toSet()
        val decision = RestoreCoordinator.decide(snapshot, false, knownIds)
        if (!decision.applySnapshot) return
        queue.setInitial(decision.queueIds)
        notifyQueueChanged()
        restoreTimer(decision.timer)
        val story = decision.currentStoryId?.let { catalog.getById(it) }
        if (story != null) {
            // Restore is paused by definition (spec "Cold restore is paused…").
            performLoad(story, decision.positionMs, autoplay = false)
        }
    }

    // =====================================================================
    // Persistence flush (S5.6): 5 s cadence while dirty + immediate flushNow
    // =====================================================================

    private var dirty = false
    private var flushScheduled = false

    private val flushRunnable = Runnable {
        flushScheduled = false
        if (hydrationComplete && dirty) {
            dirty = false
            writeSnapshot()
        }
    }

    /** Marks state as needing a (5 s-delayed) persist. Early-returns before
     *  hydration — writes are impossible before the restore completed. */
    private fun markDirty() {
        if (!hydrationComplete) return
        dirty = true
        if (flushScheduled) return
        flushScheduled = true
        mainHandler.postDelayed(flushRunnable, FLUSH_CADENCE_MS)
    }

    /** Immediate persist (expiry, repository release, service onDestroy,
     *  MainActivity.onStop). Same hydration guard. */
    fun flushNow() {
        if (!hydrationComplete || released) return
        dirty = false
        mainHandler.removeCallbacks(flushRunnable)
        flushScheduled = false
        writeSnapshot()
    }

    private fun writeSnapshot() {
        val c = controller
        val positionMs = if (c != null && current != null) c.currentPosition.coerceAtLeast(0L) else 0L
        val snapshot = PersistedState(
            currentStoryId = current?.id,
            positionMs = positionMs,
            queueIds = queue.snapshot(),
            timer = timerState,
            updatedAt = System.currentTimeMillis()
        )
        // Fresh installs never produce a bogus blob (spec "no bogus write"):
        // nothing current + nothing queued + timer off == state never had content.
        if (snapshot.currentStoryId == null && snapshot.queueIds.isEmpty() && snapshot.timer == TimerState.Off) return
        store.write(snapshot)
    }

    /** Process-lifetime teardown hook (service onDestroy): flush + stop tickers. */
    fun release() {
        if (released) return
        flushNow()
        released = true
        cancelTimerTicks()
        maybeStopProgress()
        mainHandler.removeCallbacks(flushRunnable)
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
    fun addQueueListener(listener: QueueListener) { queueListeners.addIfAbsent(listener) }
    fun removeQueueListener(listener: QueueListener) { queueListeners.remove(listener) }
    fun addTimerListener(listener: TimerListener) { timerListeners.addIfAbsent(listener) }
    fun removeTimerListener(listener: TimerListener) { timerListeners.remove(listener) }

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
        for (listener in stateListeners) listener.onStateChanged(state)
    }

    // =====================================================================
    // Controller listener + progress ticks
    // =====================================================================

    /** Session-events listener (media3 1.2.1: MediaController.Listener registered
     *  via Builder.setListener). Handles service death with bounded backoff. */
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
        override fun onPlaybackStateChanged(playbackState: Int) = onPlaybackEvent()
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = onPlaybackEvent()
        override fun onIsPlayingChanged(isPlaying: Boolean) = onPlaybackEvent()
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) =
            onPlayerErrorInternal(error)
    }

    /** Central gate: only events tied to the CURRENT generation / current item may
     *  alter the derived state; events from a superseded load are discarded. */
    private fun onPlaybackEvent() {
        val c = controller ?: return
        if (pendingLoad != null && !isCurrentItem(c, pendingLoad!!.id)) return
        if (c.playbackState == Player.STATE_ENDED) {
            onEnded()
            return
        }
        if (pendingLoad != null && c.playbackState == Player.STATE_READY) {
            pendingLoad = null
        }
        publishState()
        maybeStartProgress()
    }

    /**
     * Natural end (specs/playback "End of story vs manual next (queue
     * asymmetry)"; specs/sleep-timer "End-of-story timer suppresses
     * auto-advance"):
     *  - end_of_story timer → pause, timer off, NO auto-advance, queue preserved;
     *  - queue head (consumed) → auto-advance with autoplay;
     *  - empty queue → stop (Ended), queue preserved, no catalog wrap.
     */
    private fun onEnded() {
        pendingLoad = null
        endedFlag = true
        val c = controller ?: return
        if (timerState is TimerState.EndOfStory) {
            timerState = TimerState.Off
            cancelTimerTicks()
            notifyTimer()
            markDirty()
            c.pause()
            publishState()
            maybeStopProgress()
            return
        }
        val next = queue.takeNext(current?.id)
        if (next != null) {
            // Queue-head auto-advance consumes the head and autoplays (spec).
            notifyQueueChanged()
            load(next, 0L, true)
            markDirty()
            return
        }
        c.pause()
        markDirty()
        publishState()
        maybeStopProgress()
    }

    private fun onPlayerErrorInternal(error: androidx.media3.common.PlaybackException) {
        val c = controller ?: return
        if (pendingLoad == null && !isCurrentItem(c, current?.id)) return
        pendingLoad = null
        // Media3 dispatches listener events on the main thread and callbacks read
        // the player's CURRENT state at dispatch time; c.playerError is cleared by
        // the next prepare(). Surfacing it instead of the raw callback payload
        // means a superseded same-id (stale-generation) error can never label a
        // healthy new load as failed (review R2-003).
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
                    // Clear the slot so a later maybeStartProgress() can re-arm
                    // (review R2-002).
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
        markDirty() // position advances → the 5 s flush eventually persists it
        if (progressListeners.isEmpty()) return
        if (current == null) return
        for (listener in progressListeners) listener.onProgress(progressSnapshot())
    }

    /** Circular next in the alphabetical catalog (manual ⏭ fallback, spec). */
    private fun circularNext(currentStoryId: String?): String? {
        val ids = catalog.stories().map { it.id }
        if (ids.isEmpty()) return null
        val idx = currentStoryId?.let { ids.indexOf(it) } ?: -1
        return if (idx >= 0) ids[(idx + 1) % ids.size] else ids[0]
    }

    // =====================================================================
    // Singleton
    // =====================================================================

    companion object {
        private const val FLUSH_CADENCE_MS = 5000L

        @Volatile private var instance: PlaybackRepository? = null

        /** Process-scoped singleton (design D5: process-lifetime state). */
        fun get(context: Context): PlaybackRepository {
            return instance ?: synchronized(this) {
                instance ?: PlaybackRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}
