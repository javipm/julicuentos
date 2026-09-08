package com.julicuentos.app.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class CategoriesTest {

    @Test
    fun all_hasThreeIdsInOrder() {
        assertEquals(
            listOf(Categories.DISNEY, Categories.LEE_Y_ESCUCHA, Categories.SALVAT),
            Categories.ALL
        )
    }

    @Test
    fun normalize_disneyVariants() {
        assertEquals(Categories.DISNEY, Categories.normalize("Disney"))
        assertEquals(Categories.DISNEY, Categories.normalize(" disney "))
        assertEquals(Categories.DISNEY, Categories.normalize("DISNEY"))
    }

    @Test
    fun normalize_leeYEscucha() {
        assertEquals(Categories.LEE_Y_ESCUCHA, Categories.normalize("lee-y-escucha"))
    }

    @Test
    fun normalize_unknownFallsBackToDisney() {
        assertEquals(Categories.DISNEY, Categories.normalize(""))
        assertEquals(Categories.DISNEY, Categories.normalize("foo"))
    }

    @Test
    fun filterByCategory_preservesOrder() {
        val stories = listOf(
            fakeStory("a-disney", Categories.DISNEY),
            fakeStory("b-lee", Categories.LEE_Y_ESCUCHA),
            fakeStory("c-disney", Categories.DISNEY),
            fakeStory("d-salvat", Categories.SALVAT)
        )
        val disney = stories.filter { it.categoria == Categories.DISNEY }
        assertEquals(listOf("a-disney", "c-disney"), disney.map { it.id })

        val lee = stories.filter { it.categoria == Categories.LEE_Y_ESCUCHA }
        assertEquals(listOf("b-lee"), lee.map { it.id })

        val salvat = stories.filter { it.categoria == Categories.SALVAT }
        assertEquals(listOf("d-salvat"), salvat.map { it.id })
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
