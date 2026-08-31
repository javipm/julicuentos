package com.julicuentos.app.ui.catalog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.julicuentos.app.MainActivity
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.catalog.StoryRepository
import com.julicuentos.app.playback.PlaybackRepository
import com.julicuentos.app.playback.PlayerState

class CatalogFragment : Fragment() {

    private lateinit var adapter: StoryAdapter
    private lateinit var grid: RecyclerView
    private lateinit var miniPlayer: MiniPlayerView
    private val repo by lazy { PlaybackRepository.get(requireContext()) }
    private var visible = false

    private val stateListener = object : PlaybackRepository.StateListener {
        override fun onStateChanged(state: PlayerState) = onStateChangedInternal(state)
    }

    private val progressListener = object : PlaybackRepository.ProgressListener {
        override fun onProgress(snapshot: PlaybackRepository.ProgressSnapshot) = onProgressInternal(snapshot)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_catalog, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        grid = view.findViewById(R.id.catalog_grid)
        miniPlayer = view.findViewById(R.id.mini_player)
        val stories = StoryRepository.get(requireContext()).stories()
        adapter = StoryAdapter(stories, ::onStoryClick, ::onOverflow)
        grid.layoutManager = GridLayoutManager(requireContext(), resources.getInteger(R.integer.catalog_grid_columns))
        grid.adapter = adapter
        miniPlayer.onBarClick = { openPlayer() }
        miniPlayer.onPlayPauseClick = { repo.togglePlayPause() }
        miniPlayer.onNextClick = { repo.playNext() }
        repo.connect()
        updateMiniVisibility()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        repo.addStateListener(stateListener)
        repo.addProgressListener(progressListener)
        onStateChangedInternal(repo.state)
    }

    override fun onStop() {
        super.onStop()
        visible = false
        repo.removeStateListener(stateListener)
        repo.removeProgressListener(progressListener)
    }

    private fun onStoryClick(story: Story) = resolveOpenStoryAction(story)

    private fun onOverflow(story: Story) {
        val sheet = StoryActionSheet.newInstance(story.id)
        sheet.onPlayNow = { openPlayer() }
        sheet.show(parentFragmentManager, "story_actions")
    }

    private fun onStateChangedInternal(state: PlayerState) {
        val current = repo.currentStory
        adapter.setCurrentStory(current?.id)
        updateMiniVisibility()
        if (current != null) {
            miniPlayer.bindStory(current)
            miniPlayer.setPlaying(state is PlayerState.Playing)
        }
    }

    private fun onProgressInternal(snapshot: PlaybackRepository.ProgressSnapshot) {
        if (!visible) return
        if (miniPlayer.visibility != View.VISIBLE) return
        if (snapshot.storyId == null || snapshot.durationMs <= 0L) return
        val ratio = (snapshot.positionMs.toFloat() / snapshot.durationMs.toFloat()).coerceIn(0f, 1f)
        miniPlayer.setProgress(ratio)
    }

    private fun updateMiniVisibility() {
        val current = repo.currentStory
        val show = current != null
        miniPlayer.setVisible(show)
        val bottomRes = if (show) R.dimen.grid_padding_bottom_mini else R.dimen.grid_padding_bottom
        val bottom = resources.getDimensionPixelSize(bottomRes)
        grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, bottom)
    }

    private fun resolveOpenStoryAction(story: Story) {
        val current = repo.currentStory?.id
        val state = repo.state
        when {
            story.id == current && state is PlayerState.Playing -> openPlayer()
            story.id == current && state is PlayerState.Paused -> {
                repo.togglePlayPause()
                openPlayer()
            }
            else -> {
                repo.load(story.id, 0L, true)
                openPlayer()
            }
        }
    }

    private fun openPlayer() {
        val activity = activity
        if (activity is MainActivity) activity.openPlayer()
    }
}
