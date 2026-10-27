package com.julicuentos.app.catalog

import java.text.Normalizer

/**
 * Catalog categories derived from the content itself (stories.json).
 *
 * There is no category registry in the code: any `categoria` value in the JSON
 * defines a category, its visible name comes from its id, and the list only
 * contains categories that actually have stories. Adding a category is a
 * content change (`adb push`), never a rebuild.
 */
object Categories {

    /** Fallback id for stories whose `categoria` is missing or unusable. */
    const val OTROS = "otros"

    /** Single letters that stay lowercase when deriving a label (`a`, `y`...). */
    private val LOWERCASE_SINGLE_LETTERS = setOf("a", "e", "o", "u", "y")

    private val COMBINING_MARKS = Regex("\\p{M}+")
    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")

    /**
     * Canonical id for [raw]: trimmed, lowercased, unaccented, alphanumerics
     * joined by single hyphens. `"Con C de cuentacuentos"` → `"con-c-de-cuentacuentos"`.
     * Blank or letter-less input becomes [OTROS].
     */
    fun normalize(raw: String): String {
        val ascii = Normalizer.normalize(raw.trim().lowercase(), Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
        val slug = ascii.replace(NON_ALPHANUMERIC, "-").trim('-')
        return slug.ifEmpty { OTROS }
    }

    /**
     * Visible name derived from [id]: sentence case, single letters capitalized
     * so `con-c-de-cuentacuentos` reads `Con C de cuentacuentos`.
     *
     * Accents are not recoverable from a slug, so `poesia` stays `Poesia`.
     */
    fun label(id: String): String = normalize(id)
        .split('-')
        .filter { it.isNotEmpty() }
        .mapIndexed { index, word ->
            when {
                index == 0 -> word.replaceFirstChar { it.uppercaseChar() }
                word.length == 1 && word !in LOWERCASE_SINGLE_LETTERS -> word.uppercase()
                else -> word
            }
        }
        .joinToString(" ")

    /**
     * Category to show given the [available] ones and the [selected] id:
     * the selection if it still exists, otherwise the first available one
     * (empty catalog → empty string, so no category is invented).
     */
    fun select(available: List<String>, selected: String): String {
        val chosen = if (selected.isBlank()) "" else normalize(selected)
        return if (chosen.isNotEmpty() && chosen in available) chosen else available.firstOrNull() ?: ""
    }

    /**
     * Categories present in [stories], sorted by id with [OTROS] always last.
     * A category with no stories never appears (no empty categories).
     */
    fun fromStories(stories: List<Story>): List<String> {
        val ids = stories.map { normalize(it.categoria) }.distinct().sorted()
        val (others, named) = ids.partition { it == OTROS }
        return named + others
    }
}
