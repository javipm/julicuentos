package com.julicuentos.app.ui.catalog

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Categories
import com.julicuentos.app.catalog.StoryRepository

class CategorySheet : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), R.style.Theme_Julicuentos_Dialog)
        val inflater = LayoutInflater.from(requireContext())
        val root = inflater.inflate(R.layout.dialog_category, null) as ViewGroup
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
        val selected = StoryRepository.get(requireContext()).selectedCategoryId
        val rows = listOf(
            Categories.DISNEY to root.findViewById<TextView>(R.id.category_row_disney),
            Categories.LEE_Y_ESCUCHA to root.findViewById(R.id.category_row_lee_y_escucha),
            Categories.SALVAT to root.findViewById(R.id.category_row_salvat)
        )
        for ((id, row) in rows) {
            styleRow(row, selected = id == selected)
            row.setOnClickListener {
                parentFragmentManager.setFragmentResult("category", bundleOf("id" to id))
                dismiss()
            }
        }
        root.findViewById<TextView>(R.id.action_cancel).setOnClickListener { dismiss() }
    }

    private fun styleRow(row: TextView, selected: Boolean) {
        row.setBackgroundResource(
            if (selected) R.drawable.bg_action_solid else R.drawable.bg_row_surface
        )
        row.setTextColor(
            ContextCompat.getColor(
                requireContext(), if (selected) R.color.fondo else R.color.texto
            )
        )
        row.setTypeface(row.typeface, if (selected) Typeface.BOLD else Typeface.NORMAL)
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(
            0, 0, if (selected) R.drawable.ic_check_dark else 0, 0
        )
    }
}
