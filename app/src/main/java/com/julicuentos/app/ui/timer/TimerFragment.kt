package com.julicuentos.app.ui.timer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.julicuentos.app.R
import com.julicuentos.app.playback.PlaybackRepository
import com.julicuentos.app.playback.TimerLogic
import com.julicuentos.app.playback.TimerState

/**
 * Timer screen (tasks S5.2; specs/sleep-timer "Timer selection UI" "Timer modes"):
 * helper copy, then five static 56 dp single-select rows — 15 / 30 / 45 minutos,
 * "Al terminar este cuento", "Desactivar". Tapping a row applies the mode
 * immediately through the repository's timer API and pops back right away
 * (spec "Choosing applies immediately and pops back").
 *
 * Selected row: solid peach (#FFB66E) background [bg_timer_row_selected] with dark
 * #17152E text (the rows already carry the bold Button label face); others:
 * surface #28244B + light #F8F7FF text. The current timer mode is highlighted on
 * open; no listener subscription is needed — the screen is transient by design.
 */
class TimerFragment : Fragment() {

    private val repo: PlaybackRepository by lazy { PlaybackRepository.get(requireContext()) }

    /** Row view id -> apply key: 15/30/45 = minutes, END_OF_STORY, OFF. */
    private val rows = listOf(
        R.id.timer_row_15 to 15,
        R.id.timer_row_30 to 30,
        R.id.timer_row_45 to 45,
        R.id.timer_row_end to KEY_END_OF_STORY,
        R.id.timer_row_off to KEY_OFF
    )

    private lateinit var rowViews: List<Pair<TextView, Int>>

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_timer, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        rowViews = rows.map { (viewId, key) ->
            view.findViewById<TextView>(viewId) to key
        }
        rowViews.forEach { (rowView, key) ->
            rowView.setOnClickListener { applyKey(key) }
        }
        view.findViewById<View>(R.id.timer_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }

        highlightRows(selectedKey(repo.currentTimer))
    }

    override fun onStart() {
        super.onStart()
        // Connect so the fade command reaches the service on expiry; idempotent.
        repo.connect()
        // Timer state may have changed while we were off-screen (e.g. restored).
        highlightRows(selectedKey(repo.currentTimer))
    }

    // =====================================================================
    // Selection + apply
    // =====================================================================

    private fun selectedKey(state: TimerState): Int = when (state) {
        is TimerState.Minutes -> state.minutes
        TimerState.EndOfStory -> KEY_END_OF_STORY
        TimerState.Off -> KEY_OFF
    }

    private fun highlightRows(selected: Int) {
        for ((rowView, key) in rowViews) {
            val isSelected = key == selected
            rowView.setBackgroundResource(
                if (isSelected) R.drawable.bg_timer_row_selected else R.drawable.bg_row_surface
            )
            rowView.setTextColor(
                ContextCompat.getColor(requireContext(), if (isSelected) R.color.fondo else R.color.texto)
            )
        }
    }

    private fun applyKey(key: Int) {
        when (key) {
            KEY_END_OF_STORY -> repo.setTimerEndOfStory()
            KEY_OFF -> repo.clearTimer()
            else -> if (TimerLogic.isValidMinutes(key)) repo.setTimerMinutes(key)
        }
        parentFragmentManager.popBackStack()
    }

    companion object {
        private const val KEY_END_OF_STORY = -1
        private const val KEY_OFF = 0
    }
}