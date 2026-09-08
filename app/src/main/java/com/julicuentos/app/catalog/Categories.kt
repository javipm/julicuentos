package com.julicuentos.app.catalog

import com.julicuentos.app.R

/**
 * Catalog category ids and labels. Pure helpers — safe for JVM unit tests
 * except [labelRes], which returns Android string resource ids.
 */
object Categories {
    const val DISNEY = "disney"
    const val LEE_Y_ESCUCHA = "lee-y-escucha"
    const val SALVAT = "salvat"

    val ALL: List<String> = listOf(DISNEY, LEE_Y_ESCUCHA, SALVAT)

    fun normalize(raw: String): String {
        val id = raw.trim().lowercase()
        return if (id in ALL) id else DISNEY
    }

    fun labelRes(id: String): Int = when (normalize(id)) {
        LEE_Y_ESCUCHA -> R.string.categoria_lee_y_escucha
        SALVAT -> R.string.categoria_salvat
        else -> R.string.categoria_disney
    }
}
