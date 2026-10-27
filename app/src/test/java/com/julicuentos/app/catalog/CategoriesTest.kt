package com.julicuentos.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoriesTest {

    // --- normalize: el id lo escribe quien aporta el contenido ---

    @Test
    fun normalize_keepsAWellFormedId() {
        assertEquals("disney", Categories.normalize("disney"))
        assertEquals("lee-y-escucha", Categories.normalize("lee-y-escucha"))
        assertEquals("con-c-de-cuentacuentos", Categories.normalize("con-c-de-cuentacuentos"))
    }

    @Test
    fun normalize_slugifiesLooseText() {
        assertEquals("con-c-de-cuentacuentos", Categories.normalize("Con C de cuentacuentos"))
        assertEquals("con-c-de-cuentacuentos", Categories.normalize("  CON-C-DE-CUENTACUENTOS  "))
        assertEquals("lee-y-escucha", Categories.normalize("Lee y Escucha"))
        assertEquals("poesia", Categories.normalize("Poesía"))
        assertEquals("cuentos-de-2-a-3-anos", Categories.normalize("Cuentos de 2 a 3 años"))
        assertEquals("a-b", Categories.normalize("a---b"))
        assertEquals("x", Categories.normalize(" -x- "))
    }

    @Test
    fun normalize_emptyOrUnusableFallsToOtros() {
        assertEquals(Categories.OTROS, Categories.normalize(""))
        assertEquals(Categories.OTROS, Categories.normalize("   "))
        assertEquals(Categories.OTROS, Categories.normalize("¡!"))
    }

    // --- label: nombre visible derivado del id, sin listas en el código ---

    @Test
    fun label_derivesSentenceCase() {
        assertEquals("Disney", Categories.label("disney"))
        assertEquals("Lee y escucha", Categories.label("lee-y-escucha"))
        assertEquals("Salvat", Categories.label("salvat"))
        assertEquals("Con C de cuentacuentos", Categories.label("con-c-de-cuentacuentos"))
        assertEquals("Donde viven los monstruos", Categories.label("donde-viven-los-monstruos"))
        // Un slug no puede recuperar acentos: la etiqueta derivada los pierde.
        assertEquals("Cuentos de 2 a 3 anos", Categories.label("cuentos-de-2-a-3-anos"))
        assertEquals("Otros", Categories.label(Categories.OTROS))
    }

    @Test
    fun label_acceptsRawText() {
        assertEquals("Con C de cuentacuentos", Categories.label(" Con-C-de-cuentacuentos "))
        assertEquals("Otros", Categories.label(""))
    }

    // --- fromStories: la lista sale del contenido, no del código ---

    @Test
    fun fromStories_returnsOnlyCategoriesWithStoriesSortedById() {
        val stories = listOf(
            fakeStory("a", "salvat"),
            fakeStory("b", "disney"),
            fakeStory("c", "disney"),
            fakeStory("d", "con-c-de-cuentacuentos")
        )
        assertEquals(
            listOf("con-c-de-cuentacuentos", "disney", "salvat"),
            Categories.fromStories(stories)
        )
    }

    @Test
    fun fromStories_putsOtrosLast() {
        val stories = listOf(
            fakeStory("a", Categories.OTROS),
            fakeStory("b", "disney")
        )
        assertEquals(listOf("disney", Categories.OTROS), Categories.fromStories(stories))
        assertEquals(
            listOf(Categories.OTROS),
            Categories.fromStories(listOf(fakeStory("a", Categories.OTROS)))
        )
    }

    @Test
    fun fromStories_emptyCatalogHasNoCategories() {
        assertTrue(Categories.fromStories(emptyList()).isEmpty())
    }

    // --- select: qué categoría se ve al abrir y al cambiar ---

    @Test
    fun select_usesFirstCategoryWhenNothingChosen() {
        val available = listOf("con-c-de-cuentacuentos", "disney", Categories.OTROS)
        assertEquals("con-c-de-cuentacuentos", Categories.select(available, ""))
        assertEquals("con-c-de-cuentacuentos", Categories.select(available, "   "))
    }

    @Test
    fun select_keepsValidSelectionEvenIfNotFirst() {
        val available = listOf("disney", "lee-y-escucha", "salvat")
        assertEquals("salvat", Categories.select(available, "salvat"))
        assertEquals("salvat", Categories.select(available, " SALVAT "))
    }

    @Test
    fun select_fallsBackWhenSelectionDisappeared() {
        val available = listOf("disney", "poesia")
        // La categoría elegida ya no tiene cuentos: no se queda en una rejilla vacía.
        assertEquals("disney", Categories.select(available, "salvat"))
        assertEquals("", Categories.select(emptyList(), "disney"))
    }

    // --- comportamiento existente que comparten estos cambios ---

    @Test
    fun filterByCategory_preservesCatalogOrder() {
        val stories = listOf(
            fakeStory("a-disney", "disney"),
            fakeStory("b-lee", "lee-y-escucha"),
            fakeStory("c-disney", "disney")
        )
        assertEquals(
            listOf("a-disney", "c-disney"),
            stories.filter { it.categoria == "disney" }.map { it.id }
        )
    }

    private fun fakeStory(id: String, categoria: String): Story = Story(
        id = id,
        titulo = id,
        descripcion = "",
        duracionSegundos = 0,
        cover = "",
        thumbnail = "",
        categoria = categoria
    )
}
