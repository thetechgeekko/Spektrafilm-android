/*
 * Spektrafilm for Android -- Film & look auto-open and strip ordering. GPLv3.
 * Film modeling powered by spektrafilm.
 */
package com.spectrafilm.app

import org.junit.Assert.assertEquals
import org.junit.Test

class FilmLookStripTest {
    @Test
    fun newPhotoOpensFilmAndLookWhenNoPanelIsOpen() {
        assertEquals(Category.SIMULATION, filmLookCategoryAfterOpen(null))
    }

    @Test
    fun newPhotoMovesFromSourceToFilmAndLook() {
        assertEquals(Category.SIMULATION, filmLookCategoryAfterOpen(Category.SOURCE))
    }

    @Test
    fun newPhotoLeavesAChosenPanelAlone() {
        for (c in Category.entries.filter { it != Category.SOURCE }) {
            assertEquals(c, filmLookCategoryAfterOpen(c))
        }
    }

    private val groups = listOf(
        DropdownGroup("Colour negative", listOf(DropdownOption("a", "A"), DropdownOption("b", "B"))),
        DropdownGroup("Slide", listOf(DropdownOption("c", "C"))),
    )

    @Test
    fun stripListsStocksInBrowserOrder() {
        assertEquals(listOf("a", "b", "c"), stripStocks(groups).map { it.id })
    }

    @Test
    fun stripScrollsToTheSelectedStock() {
        val stocks = stripStocks(groups)
        assertEquals(2, stripSelectedIndex(stocks, "c"))
    }

    @Test
    fun unknownStockFallsBackToTheFirstTile() {
        assertEquals(0, stripSelectedIndex(stripStocks(groups), "custom"))
    }
}
