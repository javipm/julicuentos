package com.julicuentos.app.playback

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.julicuentos.app.catalog.Story
import java.io.File

/**
 * Audio-URI resolution seam (design.md D1: "the repository resolves audio URIs
 * through one seam AudioSourceResolver"). The device spike (S1.11) verdict
 * gates the strategy at construction — a later flip changes no other code.
 *
 * - [Strategy.ASSET_DIRECT] (default): `asset:///audio/<id>.mp3` via Media3
 *   AssetDataSource (STOCKED entries are mmap-backed on API 22; O(1 seek).
 * - [Strategy.COPY_TO_FILES]: first-run copy of the asset to `filesDir/audio/`
 *   with a per-file `.done` marker, URI becomes `file://` (design D1 fallback 2).
 *
 * A story whose audio file does not exist surfaces an error condition — [resolve]
 * returns null (caller maps it to [PlayerState.Error] without crashing). For
 * ASSET_DIRECT an absent file fails at playback time (player error → Error
 * state; for COPY_TO_FILES the copy step pre-checks existence and returns null. */

class AudioSourceResolver(
    private val context: Context,
    private val strategy: Strategy = Strategy.ASSET_DIRECT
) {

    enum class Strategy {
        /** Direct asset playback (chosen default; spike verdict may flip below). */
        ASSET_DIRECT,

        /** Copy assets → filesDir once(first run, `.done` marker per file), then file:// URIs. */
        COPY_TO_FILES
    }

    private val appContext = context.applicationContext
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Resolves a [MediaItem] for the story, or null when the audio is known-missing
     * (error condition — the caller surfaces [PlayerState.Error], no crash).
     */
    fun resolve(story: Story): MediaItem? {
        return when (strategy) {
            Strategy.ASSET_DIRECT -> resolveAssetDirect(story)
            Strategy.COPY_TO_FILES -> resolveFileCopy(story)
        }
    }

    /**
     * Non-blocking resolution (review R2-004): for COPY_TO_FILES the one-time
     * asset copy (~60 MB per story) runs on a worker thread and the callback is
     * delivered on the main looper; ASSET_DIRECT resolves synchronously. The
     * callback receives null only for a known-missing audio asset.
     */
    fun resolveAsync(story: Story, callback: (MediaItem?) -> Unit) {
        when (strategy) {
            Strategy.ASSET_DIRECT -> callback(resolveAssetDirect(story))
            Strategy.COPY_TO_FILES -> {
                val cached = cachedCopy(story.id)
                if (cached != null) {
                    callback(buildItem(story, cached.toURI().toString()))
                    return
                }
                Thread {
                    val file = ensureCopied(story.id)
                    val item = file?.let { buildItem(story, it.toURI().toString()) }
                    mainHandler.post { callback(item) }
                }.start()
            }
        }
    }

    // --- primary: asset:///audio/<id>.mp3 ---

    private fun resolveAssetDirect(story: Story): MediaItem {

        return buildItem(story, "asset:///audio/${story.id}.mp3")
    }

    // --- fallback: copy asset → filesDir, one-time per file ---

    private fun resolveFileCopy(story: Story): MediaItem? {
        val target = ensureCopied(story.id) ?: return null // missing asset → error condition
        return buildItem(story, target.toURI().toString())
    }

    /**
     * Copies `assets/audio/<id>.mp3` → `filesDir/audio/<id>.mp3` once, gated by
     * a `<id>.mp3.done` marker. Returns the file when available,or null when the
     * asset is absent (error condition).
     */
    private fun cachedCopy(storyId: String): File? {
        val dir = File(appContext.filesDir, "audio")
        val target = File(dir, "$storyId.mp3")
        val marker = File(dir, "$storyId.mp3.done")
        if (marker.exists() && target.exists() && target.length() > 0L) return target
        return null
    }

    private fun ensureCopied(storyId: String): File? {
        val dir = File(appContext.filesDir, "audio")
        val target = File(dir, "$storyId.mp3")
        val marker = File(dir, "$storyId.mp3.done")
        if (marker.exists() && target.exists() && target.length() > 0L) {

            return target
        }
        return synchronized(AudioSourceResolver.LOCK) {
            if (marker.exists() && target.exists() && target.length() > 0L) { return target }
            val input = try {
                appContext.assets.open("audio/$storyId.mp3")
            } catch (e: Exception) {
                return null // asset absent (or unreadable) → error condition
            }
            return try {
                dir.mkdirs()
                input.use { raw ->
                    if (!target.exists()) target.createNewFile()
                    target.outputStream().use { out -> raw.copyTo(out) }
                }
                marker.writeText("done")
                target
            } catch (e: Exception) {
                marker.delete()
                target.delete()
                null
            }
        }
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

    companion object {
        private val LOCK = Any()
    }
}