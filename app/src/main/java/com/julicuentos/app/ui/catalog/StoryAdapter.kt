package com.julicuentos.app.ui.catalog

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.common.TimeFormat
import com.julicuentos.app.media.ThumbCache

class StoryAdapter(
    private val stories: List<Story>,
    private val onStoryClick: (Story) -> Unit,
    private val onOverflowClick: (Story) -> Unit
) : RecyclerView.Adapter<StoryAdapter.StoryHolder>() {

    companion object {
        const val PAYLOAD_CURRENT = "current"
    }

    private var currentId: String? = null

    fun setCurrentStory(next: String?) {
        val prev = currentId
        if (prev == next) return
        currentId = next
        val affected = mutableListOf<Int>()
        stories.forEachIndexed { index, story ->
            if (story.id == prev || story.id == next) {
                affected.add(index)
            }
        }
        for (pos in affected) notifyItemChanged(pos, PAYLOAD_CURRENT)
    }

    override fun getItemCount(): Int = stories.size

    override fun getItemId(position: Int): Long = stories[position].id.hashCode().toLong()

    override fun getItemViewType(position: Int): Int = 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): StoryHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_story_card, parent, false)
        val holder = StoryHolder(view)
        view.setOnClickListener { holder.run { onStoryClick(stories[adapterPosition]) } }
        view.findViewById<View>(R.id.overflow_btn).setOnClickListener {
            holder.run { onOverflowClick(stories[adapterPosition]) }
        }
        return holder
    }

    override fun onBindViewHolder(holder: StoryHolder, position: Int) {
        bindFull(holder, position)
    }

    override fun onBindViewHolder(holder: StoryHolder, position: Int, payloads: MutableList<Any>) {
        val full = payloads.isEmpty()
        if (full) {
            bindFull(holder, position)
        } else {
            bindPill(holder, position)
        }
    }

    private fun bindFull(holder: StoryHolder, position: Int) {
        val story = stories[position]
        holder.boundStoryId = story.id
        holder.title.text = story.titulo
        val desc = story.descripcion.ifEmpty { holder.itemView.context.getString(R.string.story_description_fallback) }
        holder.synopsis.text = desc
        holder.duration.text = TimeFormat.formatTime(story.duracionSegundos.toLong() * 1000L)
        holder.cover.setImageDrawable(null)
        ThumbCache.loadThumb(holder.itemView.context, story) { bm ->
            if (holder.boundStoryId == story.id && bm != null) {
                holder.cover.setImageBitmap(bm)
            }
        }
        bindPill(holder, position)
    }

    private fun bindPill(holder: StoryHolder, position: Int) {
        val isCurrent = stories[position].id == currentId
        holder.pill.visibility = if (isCurrent) View.VISIBLE else View.GONE
        // The whole card announces "playing": 4 dp mint frame (design-consult C11).
        holder.itemView.setBackgroundResource(
            if (isCurrent) R.drawable.bg_card_sonando else R.drawable.bg_card
        )
    }

    class StoryHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var boundStoryId: String? = null
        val title = itemView.findViewById<TextView>(R.id.story_title)
        val synopsis = itemView.findViewById<TextView>(R.id.story_synopsis)
        val duration = itemView.findViewById<TextView>(R.id.chip_duration)
        val cover = itemView.findViewById<ImageView>(R.id.story_cover)
        val pill = itemView.findViewById<TextView>(R.id.pill_sonando)

        init {
            // Real rounded corners on the cover bitmap (design-consult C5); the
            // matte background supplies the outline, clipToOutline does the rest.
            cover.clipToOutline = true
        }
    }
}
