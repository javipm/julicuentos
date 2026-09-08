package com.julicuentos.app.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.julicuentos.app.catalog.ContentStore
import com.julicuentos.app.catalog.Story

/**
 * Resolves a [MediaItem] for a story from `/sdcard/Julicuentos/audio/<id>.mp3`.
 * Missing file → null (caller maps it to [PlayerState.Error], no crash).
 */
class AudioSourceResolver(
    @Suppress("UNUSED_PARAMETER") context: Context,
    @Suppress("UNUSED_PARAMETER") strategy: Strategy = Strategy.ASSET_DIRECT
) {

    enum class Strategy {
        ASSET_DIRECT,
        COPY_TO_FILES
    }

    fun resolve(story: Story): MediaItem? {
        val file = ContentStore.audioFile(story.id)
        if (!file.exists() || file.length() <= 0L) return null
        return buildItem(story, file.toURI().toString())
    }

    fun resolveAsync(story: Story, callback: (MediaItem?) -> Unit) {
        callback(resolve(story))
    }

    private fun buildItem(story: Story, uri: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(story.titulo)
            .build()
        return MediaItem.Builder()
            .setMediaId(story.id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            .build()
    }
}
