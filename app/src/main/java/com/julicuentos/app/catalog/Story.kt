package com.julicuentos.app.catalog

/**
 * One catalog entry, parsed from `assets/stories.json` (design.md D4).
 *
 * `cover` / `thumbnail` are asset-relative paths carried by the JSON
 * (e.g. "covers/101-dalmatas/cover.jpg") so no consumer hardcodes the layout
 * (apply-progress slice-1 deviation 4). `duracionSegundos` is a catalog
 * placeholder only: real metadata wins after load (specs/playback "Duration from
 * real metadata"). Slice 2 keeps the class a plain immutable data holder.     */

data class Story(
    val id: String,
    val titulo: String,
    val descripcion: String,
    val duracionSegundos: Int,
    val cover: String,
    val thumbnail: String
)