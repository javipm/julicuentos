package com.julicuentos.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.julicuentos.app.catalog.ContentStore
import java.io.FileInputStream

/**
 * Small shared bitmap decoder for asset JPEGs (design.md D7 pipeline; slice-2
 * use: the media notification artwork, ≤256 px per story). Decodes with
 * `inSampleSize`(power-of-two downscale) and `RGB_565`(opaque JPEGs; half the
 * memory of ARGB_8888. Returnes null when missing/unreadable — callers must not
 * crash on artwork (notification still posts content-title/actions.
 *
 * NOTE: full grid ThumbCache + player-cover cache land with the UI slices
 * (S3.4, media/Bitmaps.kt). This utility stays the shared decode primitive.
     */

object BitmapDecoder {

    /**
     * Decodes a content-relative JPEG path (e.g. "covers/<id>/cover.jpg") from
     * `/sdcard/Julicuentos`, downsampled with power-of-two `inSampleSize`.
     */
    @Suppress("UNUSED_PARAMETER")
    fun decodeSampled(context: Context, relativePath: String, maxPx: Int): Bitmap? {

        if (relativePath.isEmpty()) return null
        val file = ContentStore.mediaFile(relativePath)
        if (!file.exists()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            FileInputStream(file).use { input ->
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <=  0) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxPx && bounds.outHeight / (sample *  2) >= maxPx) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            FileInputStream(file).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (e: Exception) {
            null
        }
    }
}