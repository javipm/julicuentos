package com.julicuentos.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAccessTest {

    // --- needsManageAccess: only modern Android requires the special access ---

    @Test
    fun needsManageAccess_onlyOnApi30Plus() {
        assertFalse(StorageAccess.needsManageAccess(sdkInt = 22))
        assertFalse(StorageAccess.needsManageAccess(sdkInt = 29))
        assertTrue(StorageAccess.needsManageAccess(sdkInt = 30))
        assertTrue(StorageAccess.needsManageAccess(sdkInt = 34))
    }

    // --- stateFor: what the empty-catalog screen should offer, per API ---

    @Test
    fun stateFor_onLegacyApiPermissionIsEnough() {
        // API 22-29: READ_EXTERNAL_STORAGE at install + legacy storage is enough.
        assertEquals(
            StorageAccess.State.READY,
            StorageAccess.stateFor(sdkInt = 22, permissionGranted = true, isStorageManager = false)
        )
        assertEquals(
            StorageAccess.State.READY,
            StorageAccess.stateFor(sdkInt = 29, permissionGranted = true, isStorageManager = false)
        )
    }

    @Test
    fun stateFor_onModernApiNeedsManageAllFiles() {
        // On API 30+ the runtime permission exists but no longer grants /sdcard access.
        assertEquals(
            StorageAccess.State.NEEDS_MANAGE_ALL_FILES,
            StorageAccess.stateFor(sdkInt = 34, permissionGranted = true, isStorageManager = false)
        )
        assertEquals(
            StorageAccess.State.NEEDS_MANAGE_ALL_FILES,
            StorageAccess.stateFor(sdkInt = 30, permissionGranted = false, isStorageManager = false)
        )
    }

    @Test
    fun stateFor_readyWhenStorageManagerOnModernApi() {
        assertEquals(
            StorageAccess.State.READY,
            StorageAccess.stateFor(sdkInt = 34, permissionGranted = false, isStorageManager = true)
        )
    }

    // --- settingsIntentAction: where the onboarding button sends the user ---

    @Test
    fun settingsIntentAction_manageAllFilesOnApi30Plus() {
        assertEquals(
            StorageAccess.MANAGE_APP_ALL_FILES_ACTION,
            StorageAccess.settingsIntentAction(sdkInt = 30)
        )
        assertEquals(
            StorageAccess.MANAGE_APP_ALL_FILES_ACTION,
            StorageAccess.settingsIntentAction(sdkInt = 34)
        )
    }

    @Test
    fun settingsIntentAction_nullOnLegacyApi() {
        assertEquals(null, StorageAccess.settingsIntentAction(sdkInt = 22))
        assertEquals(null, StorageAccess.settingsIntentAction(sdkInt = 29))
    }
}
