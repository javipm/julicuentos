package com.julicuentos.app.catalog

import android.os.Environment
import java.io.File

/**
 * On-device content root. The APK is code-only; stories, audio and covers live
 * in `/sdcard/Julicuentos` (pushed with `tools/push-content.sh`).
 */
object ContentStore {

    const val DIR_NAME = "Julicuentos"

    fun root(): File = File(Environment.getExternalStorageDirectory(), DIR_NAME)

    fun storiesJson(): File = File(root(), "stories.json")

    fun audioFile(id: String): File = File(File(root(), "audio"), "$id.mp3")

    /** Resolves a catalog-relative path such as `covers/aladdin/cover.jpg`. */
    fun mediaFile(relativePath: String): File = File(root(), relativePath)
}
