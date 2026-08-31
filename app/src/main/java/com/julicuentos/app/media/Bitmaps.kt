package com.julicuentos.app.media

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import com.julicuentos.app.catalog.Story
import java.util.LinkedHashMap
import java.util.concurrent.Executors

/**
 * Shared bitmap pipeline for the UI slices (design.md D7; tasks S3.4通知).
 *
 * One single-thread executor decodes asset JPEGs lazily + main-thread post, so
 * scroll/transition callbacks always land on the main looper (no image library;
 * no coroutines — a bounded serial worker is exactly what 40 static assets need).
 *
 * [CoverCache] keeps the up-to-1400px story covers downsampled to ~640 px in a
 * tiny 2-entry cache (current + previous story for player/miniplayer transitions,
 * design D7) — the miniplayer reuses the player-size bitmap, never re-decode.

 * NOTE: grid thumbnails live in [ThumbCache] (own LruCache budget per S3.4).
 */
object Bitmaps {

    val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    /** Posts [r] on the main looper(safe from any thread). */
    fun postOnMain(r: Runnable) {
        mainHandler.post(r)
    }

    /** Decodes [story].cover] off-main downsampled to <= ~640 px (RGB_565( via the
     * 2-entry LRU. Callback runs on the main thread with null on missing/unreadable
     * artwork (callers must not crash on artwork).
     */
    fun loadCover(context: Context, story: Story, callback: (Bitmap?) -> Unit) {
        val key = "c:" + story.id
        CoverCache.get(key)?.let { bm ->
            postOnMain { callback(bm) }
            return
        }
        executor.execute {
            val bm = BitmapDecoder.decodeSampled(context.applicationContext, story.cover, 640)
            if (bm != null) CoverCache.put(key, bm)
            postOnMain { callback(bm) }
        }
    }

    /** Two-entry LRU (access-ordered) for player/miniplayer cover bitmaps. */
    private object CoverCache {

        private const val MAX_ENTRIES= 2

        private val map = object : LinkedHashMap<String, Bitmap>(4, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean =
                size > MAX_ENTRIES
        }

        fun get(key: String): Bitmap? = map[key]
        fun put(key: String, bm: Bitmap) { map[key] = bm }
    }
}