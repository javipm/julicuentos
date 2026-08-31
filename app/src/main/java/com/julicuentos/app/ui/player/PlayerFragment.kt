package com.julicuentos.app.ui.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import com.julicuentos.app.MainActivity
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.common.TimeFormat
import com.julicuentos.app.media.Bitmaps
import com.julicuentos.app.playback.PlaybackRepository
import com.julicuentos.app.playback.PlayerState
import com.julicuentos.app.playback.TimerState

/**
 * Player screen (tasks S4.5; specs/playback "Play/pause and transport", "Error
 * handling (bundled audio only)"; specs/theme-design "Dual-orientation player
 * layouts").
 *
 * Pure view work over the shared [PlaybackRepository] snapshot — no business
 * logic here (design.md D5). It binds:
 *  - the cover (rounded-square halo [CoverHaloView], 500 ms ring progress),
 *  - the sleep-timer state (slice 5): a 1 Hz peach countdown line
 *    ("Temporizador: m:ss" / "Temporizador: al terminar este cuento") driven by
 *    the repository timer listener, GONE when off, with the halo's peach outer
 *    ring mirroring the active mode),
 *  - [SeekBarController] (drag-preview + exactly one commit-on-release; zero
 *    player calls while dragging — specs/playback "Seek = drag-preview +
 *    commit-on-release"),
 *  - transport row: timer → TimerFragment, −15 s/+15 s via [skipBy], play/pause
 *    toggle, ＋ → [enqueue] (append + de-dup inside QueueStore), "Ver cola" →
 *    QueueFragment,
 *  - state overlays: Loading ("Cargando audio…" mint line), Error (message +
 *    Reintentar/Cerrar), Idle ("No hay ningún cuento…" + Volver al catálogo).
 *
 * Listeners attach in onStart and are removed in onStop + onDestroyView (design
 * D5 "fragments re-subscribe in onStart/onStop"; rotation-safe: the repository
 * snapshot rebinds on return). All view ids come from the surviving
 * fragment_player.xml layouts (portrait + layout-w600dp-land), identical across
 * both.
 */
class PlayerFragment : Fragment() {

    private val repo: PlaybackRepository by lazy { PlaybackRepository.get(requireContext()) }

    private lateinit var content: View
    private lateinit var loading: TextView
    private lateinit var halo: CoverHaloView
    private lateinit var coverImg: ImageView
    private lateinit var title: TextView
    private lateinit var desc: TextView
    private lateinit var playIcon: ImageView
    private lateinit var empty: View
    private lateinit var emptyBack: View
    private lateinit var error: View
    private lateinit var errorMsg: TextView
    private lateinit var errorRetry: View
    private lateinit var errorClose: View
    private lateinit var timerLine: TextView
    private lateinit var timerChip: View
    private lateinit var timerChipIcon: ImageView
    private lateinit var timerChipLabel: TextView
    private lateinit var seekController: SeekBarController

    /** Story id the cover/title currently show; guards async cover decode. */
    private var boundStoryId: String? = null

    /** Real duration once metadata arrives (replaces the catalog placeholder). */
    private var lastDurationMs = 0L

    private val stateListener = object : PlaybackRepository.StateListener {
        override fun onStateChanged(state: PlayerState) = bindState(state)
    }

    private val progressListener = object : PlaybackRepository.ProgressListener {
        override fun onProgress(snapshot: PlaybackRepository.ProgressSnapshot) =
            onProgressInternal(snapshot)
    }

    private val timerListener = object : PlaybackRepository.TimerListener {
        override fun onTimerChanged(snapshot: PlaybackRepository.TimerSnapshot) =
            bindTimer(snapshot)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_player, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Returning from queue/timer recreates this view: the fragment survives but
        // its views do not, so force bindStory to rebind (otherwise the cover image
        // disappears — boundStoryId still matched from the destroyed view).
        boundStoryId = null

        // Chip row (design pass C3): lateinits must bind here — bindTimer() runs in
        // onStart and touches them even when the timer is off (review R6-001).
        timerChip = view.findViewById(R.id.player_timer_btn)
        timerChipIcon = view.findViewById(R.id.player_timer_icon)
        timerChipLabel = view.findViewById(R.id.player_timer_label)

        content = view.findViewById(R.id.player_content)
        loading = view.findViewById(R.id.player_loading)
        halo = view.findViewById(R.id.player_cover_wrap)
        coverImg = view.findViewById(R.id.player_cover_img)
        title = view.findViewById(R.id.player_title)
        desc = view.findViewById(R.id.player_desc)
        playIcon = view.findViewById(R.id.player_play_icon)
        empty = view.findViewById(R.id.player_empty)
        emptyBack = view.findViewById(R.id.player_empty_back)
        error = view.findViewById(R.id.player_error)
        errorMsg = view.findViewById(R.id.player_error_msg)
        errorRetry = view.findViewById(R.id.player_error_retry)
        errorClose = view.findViewById(R.id.player_error_close)
        timerLine = view.findViewById(R.id.player_timer_line)

        val seekBar = view.findViewById<SeekBar>(R.id.player_seek)
        val position = view.findViewById<TextView>(R.id.player_position)
        val duration = view.findViewById<TextView>(R.id.player_duration)
        seekController = SeekBarController(seekBar, position, duration).also { c ->
            c.onCommit = { targetMs -> repo.seekTo(targetMs) }
            c.attach()
        }

        view.findViewById<View>(R.id.player_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        view.findViewById<View>(R.id.player_timer_btn).setOnClickListener {
            (activity as? MainActivity)?.openTimer()
        }
        view.findViewById<View>(R.id.player_skip_back_btn).setOnClickListener {
            repo.skipBy(-15_000L)
        }
        view.findViewById<View>(R.id.player_play_btn).setOnClickListener {
            repo.togglePlayPause()
        }
        view.findViewById<View>(R.id.player_skip_fwd_btn).setOnClickListener {
            repo.skipBy(15_000L)
        }
        view.findViewById<View>(R.id.player_queue_btn).setOnClickListener {
            addCurrentToQueue()
        }
        view.findViewById<View>(R.id.player_ver_cola).setOnClickListener {
            (activity as? MainActivity)?.openQueue()
        }
        emptyBack.setOnClickListener { parentFragmentManager.popBackStack() }
        errorRetry.setOnClickListener { retry() }
        errorClose.setOnClickListener { parentFragmentManager.popBackStack() }
    }

    override fun onStart() {
        super.onStart()
        repo.connect()
        repo.addStateListener(stateListener)
        repo.addProgressListener(progressListener)
        repo.addTimerListener(timerListener)
        bindState(repo.state)
        bindTimer(PlaybackRepository.TimerSnapshot(repo.currentTimer, repo.timerRemainingMs()))
    }

    override fun onStop() {
        super.onStop()
        repo.removeStateListener(stateListener)
        repo.removeProgressListener(progressListener)
        repo.removeTimerListener(timerListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        repo.removeStateListener(stateListener)
        repo.removeProgressListener(progressListener)
        repo.removeTimerListener(timerListener)
    }

    // =====================================================================
    // State binding
    // =====================================================================

    private fun bindState(state: PlayerState) {
        when (state) {
            is PlayerState.Idle -> showIdle()
            is PlayerState.Loading -> {
                bindStory(state.story)
                showLoading()
            }
            is PlayerState.Error -> showError(state.message)
            else -> {
                // Ready / Playing / Paused / Ended — content visible with the
                // current story; play glyph reflects playing.
                bindStory(repo.currentStory)
                showContent(state is PlayerState.Playing)
            }
        }
    }

    private fun bindStory(story: Story?) {
        if (story == null || story.id == boundStoryId) return
        boundStoryId = story.id
        lastDurationMs = 0L
        title.text = story.titulo
        desc.text = story.descripcion
            .ifEmpty { getString(R.string.story_description_fallback) }
        coverImg.setImageDrawable(null)
        Bitmaps.loadCover(requireContext(), story) { bm ->
            if (boundStoryId == story.id && bm != null) {
                coverImg.setImageBitmap(bm)
            }
        }
        // Catalog duration as the initial placeholder; the real metadata replaces
        // it on the first progress snapshot (specs/playback "Duration from real
        // metadata", S4.7).
        seekController.setDuration(story.duracionSegundos.toLong() * 1000L)
        seekController.onProgress(0L)
        halo.setProgress(0f)
        updatePlayButton(false)
    }

    private fun showIdle() {
        content.visibility = View.GONE
        loading.visibility = View.GONE
        error.visibility = View.GONE
        empty.visibility = View.VISIBLE
    }

    private fun showLoading() {
        content.visibility = View.VISIBLE
        loading.visibility = View.VISIBLE
        error.visibility = View.GONE
        empty.visibility = View.GONE
        updatePlayButton(false)
    }

    private fun showContent(playing: Boolean) {
        content.visibility = View.VISIBLE
        loading.visibility = View.GONE
        error.visibility = View.GONE
        empty.visibility = View.GONE
        updatePlayButton(playing)
    }

    private fun showError(message: String) {
        content.visibility = View.GONE
        loading.visibility = View.GONE
        empty.visibility = View.GONE
        // Repository/library messages are English diagnostics; the card shows
        // Spanish copy per specs/playback, detail kept for logcat (R4-004).
        android.util.Log.w("PlayerFragment", "Playback error: $message")
        errorMsg.text = when {
            message.contains("missing", ignoreCase = true) ->
                getString(R.string.error_audio_faltante)
            else -> getString(R.string.error_generico)
        }
        error.visibility = View.VISIBLE
    }

    private fun updatePlayButton(playing: Boolean) {
        playIcon.setImageResource(if (playing) R.drawable.ic_pause_dark else R.drawable.ic_play_dark)
        playIcon.contentDescription = getString(if (playing) R.string.pause else R.string.play)
    }

    // =====================================================================
    // Targeted progress updates (500 ms; seek bar + labels + halo only)
    // =====================================================================

    private fun onProgressInternal(snapshot: PlaybackRepository.ProgressSnapshot) {
        val story = repo.currentStory ?: return
        if (snapshot.storyId != story.id) return
        val duration = snapshot.durationMs
        if (duration > 0L && duration != lastDurationMs) {
            lastDurationMs = duration
            seekController.setDuration(duration)
        }
        seekController.onProgress(snapshot.positionMs)
        if (duration > 0L) {
            val ratio = (snapshot.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            halo.setProgress(ratio)
        }
    }

    // =====================================================================
    // Sleep-timer visibility (1 Hz repository ticks; specs/sleep-timer
    // "Timer visibility on the player")
    // =====================================================================

    private fun bindTimer(snapshot: PlaybackRepository.TimerSnapshot) {
        val armed = snapshot.state != TimerState.Off
        when (snapshot.state) {
            is TimerState.Minutes -> {
                timerLine.text = getString(
                    R.string.timer_line_minutes,
                    TimeFormat.formatRemaining(snapshot.remainingMs)
                )
                timerLine.visibility = View.VISIBLE
                halo.setTimerActive(true)
            }
            TimerState.EndOfStory -> {
                timerLine.text = getString(R.string.timer_line_end)
                timerLine.visibility = View.VISIBLE
                halo.setTimerActive(true)
            }
            TimerState.Off -> {
                timerLine.visibility = View.GONE
                halo.setTimerActive(false)
            }
        }
        setTimerChipArmed(armed)
    }

    /** Peach-filled chip + dark bold label while the sleep timer is armed (C3). */
    private fun setTimerChipArmed(armed: Boolean) {
        timerChip.setBackgroundResource(
            if (armed) R.drawable.bg_chip_timer_on else R.drawable.bg_chip_control
        )
        val labelColor = ContextCompat.getColor(
            requireContext(), if (armed) R.color.fondo else R.color.temporizador
        )
        timerChipLabel.setTextColor(labelColor)
        ImageViewCompat.setImageTintList(
            timerChipIcon,
            ContextCompat.getColorStateList(
                requireContext(), if (armed) R.color.fondo else R.color.temporizador
            )
        )
    }

    // =====================================================================
    // Actions
    // =====================================================================

    private fun addCurrentToQueue() {
        // enqueue is append + de-duplicated inside QueueStore (specs/queue).
        repo.currentStory?.id?.let { repo.enqueue(it) }
    }

    private fun retry() {
        val story = repo.currentStory ?: return
        val positionMs = repo.progressSnapshot().positionMs.coerceAtLeast(0L)
        repo.load(story.id, positionMs, true)
    }
}