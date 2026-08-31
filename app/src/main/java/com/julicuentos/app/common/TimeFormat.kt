package com.julicuentos.app.common

/**
 * Pure time formatting shared by the player seek labels, miniplayer status timeline
 * and the timer countdown (specs/theme-design "Time formatting"; tasks S4.6).
 * Replaces the private `formatDuration` helper that lived in StoryAdapter (hoisted here; the
 * adapter now formats the catalog chip through [formatTime], the slice-2 apply-progress
 * deviation 4 is closed).
 *
 * Rules: [`formatTime`] floors to whole seconds (m:ss, or h:mm:ss when hours > 0);
 * [`formatRemaining`] ceils to the next second (always m:ss. Negative/NaN-ish inputs
 * clamp to 0:00 (inputs are Long — no NaN exists, but `Long.MIN_VALUE` and other
 * extreme values must never overflow or crash).
 */
object TimeFormat {

    /** Formats a duration/position (ms) flooring to whole seconds: `m:ss`,
     *  or `h:mm:ss` when hours > 0. Negative inputs → "0:00".*/
    fun formatTime(ms: Long): String {
        val seconds = floorSeconds(ms)
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "$hours:%02d:%02d".format(minutes, secs)
        } else {
            "$minutes:%02d".format(secs)
        }
    }

    /** Formats a remaining countdown (ms) ceiling to the next second, always `m:ss`
     *  (never "0:00" early; reaches it only at expiry). Negative inputs → "0:00".*/
    fun formatRemaining(ms: Long): String {
        val seconds = ceilSeconds(ms)
        val minutes = seconds / 60
        val secs = seconds % 60
        return "$minutes:%02d".format(secs)
    }

    private fun floorSeconds(ms: Long): Long {
        val v = ms.coerceAtLeast(0L)
        return v / 1000L
    }

    private fun ceilSeconds(ms: Long): Long {
        val v = ms.coerceAtLeast(0L)
        return v / 1000L + if (v % 1000L != 0L) 1L else 0L
    }
}