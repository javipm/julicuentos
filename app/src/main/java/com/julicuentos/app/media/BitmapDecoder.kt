package com.julicuentos.app.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

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
     * Decodes an asset-relative JPEG path (e.g. "covers/<id>/cover.jpg") downsampled
     * with power-of-two `inSampleSize`. Because of that granularity the decoded
     * longer side lands in the (maxPx, 2*maxPx] window (review R3-003: the previous
     * doc overclaimed "<= maxPx"). Memory stays bounded by the caller-side caches.
     */
    fun decodeSampled(context: Context, assetPath: String, maxPx: Int): Bitmap? {

        if (assetPath.isEmpty()) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.assets.open(assetPath).use { input ->
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
            context.assets.open(assetPath).use { input ->
                BitmapFactory.decodeStream(input, null, options)
            }
        } catch (e: Exception) {
            null // artwork missing/corrupt → no crash
        }
    }
}