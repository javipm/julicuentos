package com.julicuentos.app.ui.timer

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.julicuentos.app.R

/**
 * Timer stub (S3.1). Slice 5 replaces this with the real timer screen.
 */
class TimerFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_placeholder, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<TextView>(R.id.placeholder_title).text = getString(R.string.temporizador)
        view.findViewById<TextView>(R.id.placeholder_subtitle).text = getString(R.string.timer_stub_subtitle)
        view.findViewById<TextView>(R.id.placeholder_back).setOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }
}