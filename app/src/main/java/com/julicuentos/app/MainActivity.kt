package com.julicuentos.app

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Slice 1 placeholder screen: proves the scaffold builds, installs and renders
 * with the locked theme. Replaced by the fragment-based catalog in slice 3.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val placeholder = TextView(this).apply {
            text = getString(R.string.slice1_placeholder)
            setTextColor(ContextCompat.getColor(context, R.color.texto))
            setPadding(48, 48, 48, 48)
        }
        setContentView(placeholder)
    }
}
