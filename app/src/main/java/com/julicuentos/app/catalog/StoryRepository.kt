package com.julicuentos.app.catalog

import android.content.Context
import java.nio.charset.StandardCharsets

/**
 * Process-scoped singleton holding the catalog (tasks S2.1; design.md D5.
 *
 * Loads `/sdcard/Julicuentos/stories.json` exactly once (lazy), caches an
 * immutable alphabetical list, and indexes by id. Consumers get `stories()`
 * or `getById(id)` (null-safe). Missing file → empty catalog, no crash.
 *
 * Design D4 wants the parse on a worker thread "before any hydration"; slice 2 has
 * no hydration path yet (restore lands in slice 5), so the first touch parses
 * on the calling thread once and caches — a ~1 ms parse of 20 entries. The
 * worker-thread + latch ordering lands with the slice-5 RestoreCoordinator (flagged in
 * apply-progress).
 */
class StoryRepository private constructor() {

    @Volatile
    private var cachedStories: List<Story>? = null
    private var byIdCache: Map<String, Story>? = null

    /** In-memory category filter for the catalog grid. Not persisted. */
    var selectedCategoryId: String = Categories.DISNEY
        set(value) {
            field = Categories.normalize(value)
        }

    /** Immutable catalog list, alphabetical by id (JSON order by design D4. */
    fun stories(): List<Story> {
        return loadIfNeeded().stories
    }

    /** Stories in [categoryId], alphabetical by id (same order as [stories]). */
    fun storiesIn(categoryId: String): List<Story> {
        val normalized = Categories.normalize(categoryId)
        return stories().filter { it.categoria == normalized }
    }

    /** Lookup by id; null-safe. */
    fun getById(id: String): Story? {
        return loadIfNeeded().byId[id]
    }

    fun hasStory(id: String): Boolean = getById(id) != null

    // --- lazy load (synchronized, once) ---

    private fun loadIfNeeded(): Loaded {

        if (cachedStories != null && byIdCache != null) {
            return Loaded(cachedStories!!, byIdCache!!)
        }
        return synchronized(this) {
            if (cachedStories == null) {
                val parsed = parse()
                cachedStories = parsed
                byIdCache = parsed.associateBy { it.id }
            }
            Loaded(cachedStories!!, byIdCache!!)
        }
    }

    private class Loaded(val stories: List<Story>, val byId: Map<String, Story>)

    private fun parse(): List<Story> {
        val json = readJson() ?: return emptyList()
        return CatalogParser.parse(json)
    }

    private fun readJson(): String? {
        return try {
            val file = ContentStore.storiesJson()
            if (!file.exists()) return null
            file.readBytes().toString(StandardCharsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        @Volatile
        private var instance: StoryRepository? = null

        /** Process-scoped singleton (design D5: repository owns processlifetime state). */
        fun get(@Suppress("UNUSED_PARAMETER") context: Context): StoryRepository {
            return instance ?: synchronized(this) {
                instance ?: StoryRepository().also { instance = it }
            }
        }
    }
}