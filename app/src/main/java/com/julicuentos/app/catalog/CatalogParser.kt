package com.julicuentos.app.catalog

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Validator-first parser for `assets/stories.json` (design.md D4, tasks S2.1).
 *
 * Drops entries with a bad/missing `id` (must match `^[a-z0-9-]+$`) or an
 * (TextUtils.isEmpty titulo; coerces `duracionSegundos` to an int >= 0 else 0;
 * tolerates an empty `descripcion` and missing `cover`/`thumbnail` paths. The result
 * is sorted by id (== alphabetical for the `^[a-z0-9-]+$` charset; the JSON is
 * already alphabetical but the app sorts defensively by design). Purely functional:
 * org.json is the platform parser (no serialization dependency, design D9).
 *
 * NOTE: intentionally NOT unit-tested in this slice: org.json classes resolve
 * to android.jar stubs in local JVM tests — the pure-JVM logic that exists here is
 * deferred to the slice with the QueueLogic/TimeFormat test harness (slice 2 has none).
 */
object CatalogParser {

    private val ID_REGEX = Regex("^[a-z0-9-]+$")

    /** Parses and validates the array; returns entries sorted by id. */
    fun parse(json: String): List<Story> {
        val result = mutableListOf<Story>()
        try {
            val root = JSONArray(json)
            for (i in 0 until root.length()) {
                try {
                    val obj: JSONObject = root.getJSONObject(i)
                    val id = obj.optString("id", "").trim()
                    val titulo = obj.optString("titulo", "").trim()
                    if (!ID_REGEX.matches(id) || titulo.isEmpty()) {
                        continue // drop bad/missing id or titulo
                    }
                    val duration = obj.optInt("duracionSegundos", 0).coerceAtLeast(0)
                    result += Story(
                        id = id,
                        titulo = titulo,
                        descripcion = obj.optString("descripcion", "").trim(),
                        duracionSegundos = duration,
                        cover = obj.optString("cover", ""),
                        thumbnail = obj.optString("thumbnail", "")
                    )
                } catch (e: JSONException) {
                    // Drop a malformed entry without failing the catalog.
                    continue
                }
            }
        } catch (e: JSONException) {
            // Unparseable document → empty catalog (self-heals to an empty grid.
        }
        return result.sortedBy { it.id }
    }
}