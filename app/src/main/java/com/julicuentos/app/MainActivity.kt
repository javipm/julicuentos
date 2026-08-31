package com.julicuentos.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import com.julicuentos.app.ui.catalog.CatalogFragment
import com.julicuentos.app.ui.player.PlayerFragment
import com.julicuentos.app.ui.queue.QueueFragment
import com.julicuentos.app.ui.timer.TimerFragment

/**
 * Single-activity fragment host (design.md D5; S3.1). Catalog opens first,
 * without back-stack entry; everything else pushes a back-stack entry so the
 * system back pops naturally. No navigation library, no nav args — screens read
 * the shared PlaybackRepository.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, CatalogFragment())
                .commit()
        }
    }

    fun openPlayer() = showFragment(PlayerFragment())

    fun openQueue() = showFragment(QueueFragment())

    fun openTimer() = showFragment(TimerFragment())

    private fun showFragment(fragment: Fragment) {
        // Re-entrancy guard: rapid double taps must not stack duplicate entries of
        // the same screen (review R3-004).
        val current = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if (current != null && current::class == fragment::class) return
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .addToBackStack(null)
            .commit()
    }
}