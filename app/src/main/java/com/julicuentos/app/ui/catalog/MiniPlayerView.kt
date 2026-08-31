package com.julicuentos.app.ui.catalog

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Story
import com.julicuentos.app.media.Bitmaps

class MiniPlayerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val progressStrip: MiniProgressStrip
    private val coverView: ImageView
    private val titleView: TextView
    private val statusView: TextView
    private val playIcon: ImageView

    private var currentStoryId: String? = null

    var onBarClick: (( ) -> Unit)? = null

    var onPlayPauseClick: (( ) -> Unit)? = null

    var onNextClick: (( ) -> Unit)? = null

    init {
        inflate(context, R.layout.view_mini_player, this)
        // Bind AFTER inflate: field initializers ran before the children existed and
        // would NPE on first bind (review R3-001).
        progressStrip = findViewById(R.id.mini_progress)
        coverView = findViewById(R.id.mini_cover)
        titleView = findViewById(R.id.mini_title)
        statusView = findViewById(R.id.mini_status)
        playIcon = findViewById(R.id.mini_play_icon)
        setOnClickListener { onBarClick?.invoke() }
        findViewById<View>(R.id.mini_play_btn).setOnClickListener { onPlayPauseClick?.invoke() }
        findViewById<View>(R.id.mini_next_btn).setOnClickListener { onNextClick?.invoke() }
    }

    fun bindStory(story: Story) {
        if (story.id == currentStoryId) return
        currentStoryId = story.id
        titleView.text = story.titulo
        Bitmaps.loadCover(context, story) { bm ->
            if (currentStoryId == story.id && bm != null) {
                coverView.setImageBitmap(bm)
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        val statusRes = if (playing) R.string.sonando else R.string.en_pausa
        statusView.setText(statusRes)
        val iconRes = if (playing) R.drawable.ic_pause_dark else R.drawable.ic_play_dark
        playIcon.setImageResource(iconRes)
        val descRes = if (playing) R.string.pause else R.string.play
        playIcon.contentDescription = context.getString(descRes)
    }

    fun setProgress(ratio: Float) {
        progressStrip.setProgress(ratio)
    }

    fun setVisible(visible: Boolean) {
        visibility = if (visible) VISIBLE else GONE
    }
}
