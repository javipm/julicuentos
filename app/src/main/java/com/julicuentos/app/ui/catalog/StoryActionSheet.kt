package com.julicuentos.app.ui.catalog

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.text.TextUtils
import android.widget.TextView
import androidx.fragment.app.DialogFragment
import com.julicuentos.app.R
import com.julicuentos.app.catalog.StoryRepository
import com.julicuentos.app.playback.PlaybackRepository

class StoryActionSheet : DialogFragment() {

    companion object {
        fun newInstance(storyId: String): StoryActionSheet {
            val sheet = StoryActionSheet()
            sheet.storyId = storyId
            return sheet
        }
    }

    private var storyId: String? = null

    var onPlayNow: (( ) -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), R.style.Theme_Julicuentos_Dialog)
        val inflater = LayoutInflater.from(requireContext())
        val root = inflater.inflate(R.layout.dialog_story_actions, null) as ViewGroup
        bindContent(root)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        return dialog
    }

    private fun bindContent(root: ViewGroup) {
        val repo = PlaybackRepository.get(requireContext())
        val story = storyId?.let { StoryRepository.get(requireContext()).getById(it) }
        val titleView = root.findViewById<TextView>(R.id.sheet_title)
        titleView.text = story?.titulo ?: getString(R.string.app_name)

        val playNow = root.findViewById<TextView>(R.id.action_play_now)
        playNow.setOnClickListener {
            val id = storyId
            if (id != null) {
                repo.load(id, 0L, true)
            }
            dismiss()
            onPlayNow?.invoke()
        }

        val addQueue = root.findViewById<TextView>(R.id.action_add_queue)
        addQueue.setOnClickListener {
            val id = storyId
            if (id != null) {
                repo.enqueue(id)
            }
            dismiss()
        }

        val cancel = root.findViewById<TextView>(R.id.action_cancel)
        cancel.setOnClickListener { dismiss() }
    }
}
