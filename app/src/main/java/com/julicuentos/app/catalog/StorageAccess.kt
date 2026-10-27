package com.julicuentos.app.catalog

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * Content-storage access across Android versions (API 22 → 34+).
 *
 * The content lives in `/sdcard/Julicuentos`, outside the app-private dirs:
 * - API 22–29: `READ_EXTERNAL_STORAGE` (granted at install on the Fire HD) plus
 *   `requestLegacyExternalStorage` is enough. No extra step.
 * - API 30+: scoped storage blocks direct `/sdcard` reads; the app must hold
 *   [android.Manifest.permission.MANAGE_EXTERNAL_STORAGE] ("All files access",
 *   user-approved in system settings, checkable with
 *   [Environment.isExternalStorageManager]).
 *
 * Pure decision helpers (JVM-testable, `sdkInt`/`isStorageManager` injected);
 * [state] is the Android-bound convenience wrapper.
 */
object StorageAccess {

    /** Special-access action for the "All files access" settings page. */
    const val MANAGE_APP_ALL_FILES_ACTION = android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION

    /** What the catalog screen should offer when the grid is empty. */
    enum class State {
        /** Content can be read; an empty grid means the catalog itself is empty. */
        READY,

        /** API 30+ without "All files access": show the onboarding button. */
        NEEDS_MANAGE_ALL_FILES
    }

    fun needsManageAccess(sdkInt: Int): Boolean = sdkInt >= Build.VERSION_CODES.R

    /**
     * State for the given API level and permission facts. On API 22–29 the
     * install-time `READ_EXTERNAL_STORAGE` suffices ([isStorageManager] is
     * ignored there); on API 30+ only "All files access" grants the folder.
     */
    fun stateFor(sdkInt: Int, permissionGranted: Boolean, isStorageManager: Boolean): State {
        return if (needsManageAccess(sdkInt)) {
            if (isStorageManager) State.READY else State.NEEDS_MANAGE_ALL_FILES
        } else {
            if (permissionGranted) State.READY else State.NEEDS_MANAGE_ALL_FILES
        }
    }

    /**
     * Settings action the onboarding button should open, or null when no extra
     * step exists (API 22–29, where the install-time permission is enough).
     */
    fun settingsIntentAction(sdkInt: Int): String? {
        return if (needsManageAccess(sdkInt)) MANAGE_APP_ALL_FILES_ACTION else null
    }

    // --- Android-bound convenience ---

    /** Live state on this device. */
    fun state(context: Context): State {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
        val manager = !needsManageAccess(Build.VERSION.SDK_INT) || Environment.isExternalStorageManager()
        return stateFor(Build.VERSION.SDK_INT, granted, manager)
    }
}
