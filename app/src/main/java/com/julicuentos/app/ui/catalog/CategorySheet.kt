package com.julicuentos.app.ui.catalog

import android.app.Dialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.julicuentos.app.R
import com.julicuentos.app.catalog.Categories
import com.julicuentos.app.catalog.StoryRepository

/**
 * Bottom sheet listing every category present in the content. Rows are built
 * from [StoryRepository.categories], so a new category needs no code here.
 */
class CategorySheet : DialogFragment() {

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = Dialog(requireContext(), R.style.Theme_Julicuentos_Dialog)
        val inflater = LayoutInflater.from(requireContext())
        val root = inflater.inflate(R.layout.dialog_category, null) as ViewGroup
        bindContent(root, inflater)
        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        return dialog
    }

    private fun bindContent(root: ViewGroup, inflater: LayoutInflater) {
        val catalog = StoryRepository.get(requireContext())
        val selected = catalog.currentCategoryId()
        val rows = root.findViewById<LinearLayout>(R.id.category_rows)
        for (id in catalog.categories()) {
            val row = inflater.inflate(R.layout.item_category_row, rows, false) as TextView
            row.text = Categories.label(id)
            val params = row.layoutParams as ViewGroup.MarginLayoutParams
            params.topMargin = resources.getDimensionPixelSize(
                if (rows.childCount == 0) R.dimen.sheet_padding else R.dimen.action_row_gap
            )
            row.layoutParams = params
            styleRow(row, selected = id == selected)
            row.setOnClickListener {
                parentFragmentManager.setFragmentResult("category", bundleOf("id" to id))
                dismiss()
            }
            rows.addView(row)
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
