package com.julicuentos.app.ui.queue

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.julicuentos.app.R
import com.julicuentos.app.catalog.StoryRepository
import com.julicuentos.app.playback.PlaybackRepository

/**
 * Queue screen (tasks S5.1; specs/queue "Queue screen"): header with ✕ back,
 * "Cola" title, and a "Vaciar" text action — peach/enabled when the queue is
 * non-empty, soft/disabled when empty; the RecyclerView of queue rows; and the
 * corrected empty-state copy. Pure view work over the repository's QueueStore —
 * every mutation (move up/down, remove, Vaciar) routes through the repository and
 * the list re-binds on the QueueListener (queue-mutation events ONLY; the adapter
 * never subscribes to the 500 ms progress cadence).
 *
 * Listeners attach in onStart and are removed in onStop + onDestroyView (design D5
 * "fragments re-subscribe in onStart/onStop", same contract as PlayerFragment).
 */
class QueueFragment : Fragment() {

    private val repo: PlaybackRepository by lazy { PlaybackRepository.get(requireContext()) }

    private lateinit var listView: RecyclerView
    private lateinit var emptyView: View
    private lateinit var vaciar: TextView
    private lateinit var adapter: QueueAdapter

    private val queueListener = PlaybackRepository.QueueListener { onQueueChanged() }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_queue, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        listView = view.findViewById(R.id.queue_list)
        emptyView = view.findViewById(R.id.queue_empty)
        vaciar = view.findViewById(R.id.queue_vaciar)

        adapter = QueueAdapter(
            resolveStory = { id -> StoryRepository.get(requireContext()).getById(id) },
            onMoveUp = { index -> repo.moveQueueUp(index) },
            onMoveDown = { index -> repo.moveQueueDown(index) },
            onRemove = { storyId -> repo.removeFromQueue(storyId) }
        )
        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.adapter = adapter

        view.findViewById<View>(R.id.queue_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
        vaciar.setOnClickListener { repo.clearQueue() }
    }

    override fun onStart() {
        super.onStart()
        repo.connect()
        repo.addQueueListener(queueListener)
        onQueueChanged() // initial bind (cold open + rotation re-entry)
    }

    override fun onStop() {
        super.onStop()
        repo.removeQueueListener(queueListener)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        repo.removeQueueListener(queueListener)
    }

    /** Re-snapshots the queue and mirrors header/empty state. Queue events only. */
    private fun onQueueChanged() {
        val ids = repo.queueSnapshot()
        adapter.setCurrentId(repo.currentStory?.id)
        adapter.submit(ids)
        val hasItems = ids.isNotEmpty()
        emptyView.visibility = if (hasItems) View.GONE else View.VISIBLE
        vaciar.isEnabled = hasItems
        val tint = ContextCompat.getColor(
            requireContext(),
            if (hasItems) R.color.temporizador else R.color.textoSuave
        )
        vaciar.setTextColor(tint)
    }
}