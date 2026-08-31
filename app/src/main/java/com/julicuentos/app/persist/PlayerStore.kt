package com.julicuentos.app.persist

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock

/**
 * Process-scoped SharedPreferences store for the player blob (tasks S5.5/S5.6;
 * specs/persistence "Single storage key, fixed JSON schema"). Exactly ONE key —
 * `julicuentos.player.v1` — holding one JSON string; no per-story positions, no
 * history, no extra player-related keys. `updatedAt` is written but never read.
 *
 * Write guarantees:
 *  - `write` replaces the whole JSON string in one `commit()` (never a
 *    half-written key);
 *  - the repository only calls this after `hydrationComplete` (the
 *    never-write-before-hydration guard lives in the repository, which is the
 *    only writer);
 *  - identical-state rewrites are skipped (dirty tracking in the caller).
 */
class PlayerStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Reads + tolerantly parses the stored blob; defaults when unset/corrupt. */
    fun read(): PersistedState {
        if (!prefs.contains(KEY)) return PersistedState.empty()
        val raw = prefs.getString(KEY, null)
        return PersistedStateParser.parse(raw, SystemClock.elapsedRealtime())
    }

    /** Replaces the whole blob (one atomic string write). */
    fun write(state: PersistedState) {
        prefs.edit().putString(KEY, state.toJson()).commit()
    }

    companion object {
        const val PREFS_NAME = "julicuentos.player.prefs"
        const val KEY = "julicuentos.player.v1"
    }
}