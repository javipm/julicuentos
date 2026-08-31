package com.julicuentos.app.media

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.julicuentos.app.catalog.Story

/**
 * Grid thumbnail loader, tasks S3.4, design D7.
 * Decodes the 512 px thumbnails to <= ~512 px via BitmapDecoder in the shared
 * single-thread executor, RGB_565, cached in an LruCache sized
 * min(12 MB, heap/8)—— under the 8-16 MB spec window for 20 thumbs.
 * Callbacks land on the main looper and may deliver null for missing artwork.
 */
object ThumbCache {

    private val cache = object : LruCache<String, Bitmap>(maxSizeBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    private fun maxSizeBytes(): Int {
        val heap = Runtime.getRuntime().maxMemory().toInt()
        val limit = 12 * 1024 * 1024
        return minOf(limit, heap / 8)
    }

    fun loadThumb(context: Context, story: Story, callback: (Bitmap?) -> Unit) {
        val key = "t:" + story.id
        val cached = cache.get(key)
        if (cached != null) {
            Bitmaps.postOnMain { callback(cached) }
            return
        }
        Bitmaps.executor.execute {
            val bm = BitmapDecoder.decodeSampled(context.applicationContext, story.thumbnail, 512)
            if (bm != null) {
                cache.put(key, bm)
            }
            Bitmaps.postOnMain { callback(bm) }
        }
    }
}
