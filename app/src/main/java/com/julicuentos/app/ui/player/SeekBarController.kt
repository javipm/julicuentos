package com.julicuentos.app.ui.player

import android.widget.SeekBar

import android.widget.TextView
import com.julicuentos.app.common.TimeFormat

/**
 * Drag-preview + commit-on-release seek controller (tasks S4.4; specs/playback
 * "Seek = drag-preview + commit-on-release"). Wraps the framework [SeekBar]
 * (layer-list track + 16 dp flat thumb, both in the drawables) and the two 12 sp
 * time labels around it.

 * Contract:
 *  - [onStartTrackingTouch]: dragging=true — incoming position writes stop moving
 *    the thumb (and stop rewriting the position label;only non-thumb UI, e.g. the
 *    duration label, may still update while dragging(.
 *  - [onProgressChanged(fromUser=true]: thumb tracks the finger;preview label,
 *    `TimeFormat.formatTime(ratio * realDurationMs)`, updates — ZERO player calls;
 *  - [onStopTrackingTouch]: exactly ONE [onCommit] callback with the committed target (:the
 *    repository seekTo clamps against real duration (or catalog fallback). theᅟ
 *    thumb stays at the finger position forever (no snap-back: nothing rewrites
 *    the thumb until the next progress tick,and by then playback already resumed
 *    at ≈the released position, so no backward jump at all(. The commit is
 *    exactly one seekTo call per gesture — local assets make it effectively instant.
 *
 * tap-to-seek is just a 0-length drag — same single-commit path. ±15 buttons
 * live on the fragment and route through `repo.skipBy(...)`, fully independent of the
 * drag state.
 */

class SeekBarController(
    private val seekBar: SeekBar,
    private val positionLabel: TextView,
    private val durationLabel: TextView
) {

    private var dragging = false
    private var durationMs =  0L

    /** Called exactly once per gesture,with the released position in ms (clamped
     *  inside by the repository seekTo). Wired by the fragment to `repo::seekTo`.
     */
    var onCommit: ((Long) -> Unit)? = null

    /** Real duration from ExoPlayer metadata (fallback catalog duration passed by the caller. */
    fun setDuration(durationMs: Long) {
        this.durationMs = durationMs.coerceAtLeast(0L)
        if (this.durationMs > 0L) {
            val max = (this.durationMs / 1000L).toInt().coerceAtLeast(1)
            // Update max even mid-drag: a metadata tick mixing the new duration with
            // the old max made the preview label drift under the finger (R4-002).
            seekBar.max = max
            durationLabel.text = TimeFormat.formatTime(this.durationMs)
        } else {
            durationLabel.text = "0:00"
        }
    }

    /** 500 ms repository tick: moves the thumb + position label only when not dragging. */
    fun onProgress(positionMs: Long) {
        if (!dragging) {
            // After a commit, hold the thumb at the target until the player position
            // catches up (±1.5 s keyframe tolerance); a pre-seek tick snapshot must
            // never snap the thumb backwards (review R4-001).
            val pending = pendingTargetMs
            if (pending != null) {
                if (kotlin.math.abs(positionMs - pending) <= 1500L || positionMs >= pending) {
                    pendingTargetMs = null
                } else {
                    positionLabel.text = TimeFormat.formatTime(pending)
                    return
                }
            }
            val pos = positionMs.coerceAtLeast(0L)
            val max = seekBar.max.coerceAtLeast(1)
            seekBar.progress = (pos / 1000L).toInt().coerceIn(0, max)
            positionLabel.text = TimeFormat.formatTime(pos)
        }
    }

    /** Target of the last committed seek while the player position lags behind (R4-001). */
    private var pendingTargetMs: Long? = null

    /** Attaches the framework listeners; called once from the fragment after view binding. */
    fun attach() {
        seekBar.max = 1
        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    positionLabel.text = previewLabel(progress)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {
                dragging = true
                pendingTargetMs = null
            }

            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val targetMs = progressToMs(seekBar.progress)
                dragging = false
                pendingTargetMs = targetMs
                positionLabel.text = TimeFormat.formatTime(targetMs)
                onCommit?.invoke(targetMs)
            }
        })
    }

    private fun previewLabel(progress: Int): String {
        if (durationMs <= 0L) return TimeFormat.formatTime(0L)
        val ratio = if (seekBar.max >  0) progress.toFloat() / seekBar.max.toFloat() else 0f
        return TimeFormat.formatTime((ratio * durationMs).toLong().coerceAtLeast(0L))
    }

    private fun progressToMs(progress: Int): Long {
        return progress.toLong() * 1000L
    }
}