package com.julicuentos.app.ui.catalog

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.ImageViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.julicuentos.app.MainActivity
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Categories
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.catalog.StoryRepository
import com.julicuentos.app.playback.PlaybackRepository
import com.julicuentos.app.playback.PlayerState

class CatalogFragment : Fragment() {

    private lateinit var adapter: StoryAdapter
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var categoryBtn: LinearLayout
    private lateinit var categoryLabel: TextView
    private lateinit var miniPlayer: MiniPlayerView
    private val playback by lazy { PlaybackRepository.get(requireContext()) }
    private val catalog by lazy { StoryRepository.get(requireContext()) }
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
        empty = view.findViewById(R.id.catalog_empty)
        categoryBtn = view.findViewById(R.id.category_btn)
        categoryLabel = view.findViewById(R.id.category_btn_label)
        miniPlayer = view.findViewById(R.id.mini_player)

        val chevron = view.findViewById<ImageView>(R.id.category_btn_chevron)
        ImageViewCompat.setImageTintList(
            chevron,
            ContextCompat.getColorStateList(requireContext(), R.color.accion)
        )

        val stories = catalog.storiesIn(catalog.selectedCategoryId)
        adapter = StoryAdapter(stories, ::onStoryClick, ::onOverflow)
        grid.layoutManager = GridLayoutManager(requireContext(), resources.getInteger(R.integer.catalog_grid_columns))
        grid.adapter = adapter
        updateCategoryLabel()
        updateEmptyState(stories.isEmpty())

        categoryBtn.setOnClickListener { openCategorySheet() }
        parentFragmentManager.setFragmentResultListener("category", viewLifecycleOwner) { _, bundle ->
            val id = bundle.getString("id") ?: return@setFragmentResultListener
            applyCategory(id)
        }
        miniPlayer.onBarClick = { openPlayer() }
        miniPlayer.onPlayPauseClick = { playback.togglePlayPause() }
        miniPlayer.onNextClick = { playback.playNext() }
        playback.connect()
        updateMiniVisibility()
    }

    override fun onStart() {
        super.onStart()
        visible = true
        playback.addStateListener(stateListener)
        playback.addProgressListener(progressListener)
        onStateChangedInternal(playback.state)
    }

    override fun onStop() {
        super.onStop()
        visible = false
        playback.removeStateListener(stateListener)
        playback.removeProgressListener(progressListener)
    }

    private fun openCategorySheet() {
        CategorySheet().show(parentFragmentManager, "category_sheet")
    }

    private fun applyCategory(id: String) {
        catalog.selectedCategoryId = id
        val stories = catalog.storiesIn(catalog.selectedCategoryId)
        updateCategoryLabel()
        adapter.replaceStories(stories)
        updateEmptyState(stories.isEmpty())
        if (stories.isNotEmpty()) {
            grid.scrollToPosition(0)
        }
    }

    private fun updateCategoryLabel() {
        categoryLabel.setText(Categories.labelRes(catalog.selectedCategoryId))
    }

    private fun updateEmptyState(isEmpty: Boolean) {
        if (isEmpty) {
            grid.visibility = View.GONE
            empty.visibility = View.VISIBLE
        } else {
            empty.visibility = View.GONE
            grid.visibility = View.VISIBLE
        }
    }

    private fun onStoryClick(story: Story) = resolveOpenStoryAction(story)

    private fun onOverflow(story: Story) {
        val sheet = StoryActionSheet.newInstance(story.id)
        sheet.onPlayNow = { openPlayer() }
        sheet.show(parentFragmentManager, "story_actions")
    }

    private fun onStateChangedInternal(state: PlayerState) {
        val current = playback.currentStory
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
        val current = playback.currentStory
        val show = current != null
        miniPlayer.setVisible(show)
        val bottomRes = if (show) R.dimen.grid_padding_bottom_mini else R.dimen.grid_padding_bottom
        val bottom = resources.getDimensionPixelSize(bottomRes)
        grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, bottom)
    }

    private fun resolveOpenStoryAction(story: Story) {
        val current = playback.currentStory?.id
        val state = playback.state
        when {
            story.id == current && state is PlayerState.Playing -> openPlayer()
            story.id == current && state is PlayerState.Paused -> {
                playback.togglePlayPause()
                openPlayer()
            }
            else -> {
                playback.load(story.id, 0L, true)
                openPlayer()
            }
        }
    }

    private fun openPlayer() {
        val activity = activity
        if (activity is MainActivity) activity.openPlayer()
    }
}
