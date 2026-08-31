package com.julicuentos.app.ui.queue

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.media.ThumbCache

/**
 * Queue rows (tasks S5.1; specs/queue "Queue screen"). Renders the ordered id list
 * from the repository's QueueStore, resolving story data from the compiled-in
 * catalog by id (ids-only queue model — never story objects in the adapter).
 *
 * Row composition (item_queue_row.xml): 56 dp cover via the shared thumbnail
 * pipeline, bold 2-line title, and the chevron-up / chevron-down / ✕ buttons
 * (48 dp visuals inside 52 dp touch targets; remove tinted peach).
 *
 * Updates are driven EXCLUSIVELY by [submit] — the fragment calls it on
 * queue-mutation events only, so rows never re-bind on the 500 ms progress
 * cadence (specs/playback "Progress updates are targeted, not global").
 * Stable ids so RecyclerView keeps state through move/remove diffs.
 */
class QueueAdapter(
    private val resolveStory: (String) -> Story?,
    private val onMoveUp: (Int) -> Unit,
    private val onMoveDown: (Int) -> Unit,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueHolder>() {

    private var ids: List<String> = emptyList()

    init {
        setHasStableIds(true)
    }

    /** Replaces the whole ordered id list. Queue mutations only — never progress. */
    fun submit(next: List<String>) {
        if (ids == next) return
        ids = next
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = ids.size

    override fun getItemId(position: Int): Long = ids[position].hashCode().toLong()

    override fun getItemViewType(position: Int): Int = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): QueueHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_queue_row, parent, false)
        val holder = QueueHolder(view)
        view.findViewById<View>(R.id.queue_move_up).setOnClickListener {
            holder.run { onMoveUp(bindingAdapterPosition) }
        }
        view.findViewById<View>(R.id.queue_move_down).setOnClickListener {
            holder.run { onMoveDown(bindingAdapterPosition) }
        }
        view.findViewById<View>(R.id.queue_remove).setOnClickListener {
            holder.run {
                val id = ids.getOrNull(bindingAdapterPosition)
                if (id != null) onRemove(id)
            }
        }
        return holder
    }

    override fun onBindViewHolder(holder: QueueHolder, position: Int) {
        val storyId = ids[position]
        holder.boundStoryId = storyId
        holder.cover.setImageDrawable(null)
        val story = resolveStory(storyId)
        holder.title.text = story?.titulo.orEmpty()
        if (story != null && story.titulo.isNotEmpty()) {
            ThumbCache.loadThumb(holder.itemView.context, story) { bm ->
                if (holder.boundStoryId == storyId && bm != null) {
                    holder.cover.setImageBitmap(bm)
                }
            }
        }
    }

    class QueueHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        /** Story id the cover currently targets; guards async thumb decode. */
        var boundStoryId: String? = null
        val cover = itemView.findViewById<ImageView>(R.id.queue_cover)
        val title = itemView.findViewById<TextView>(R.id.queue_title)
    }
}