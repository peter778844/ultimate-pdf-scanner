package de.ultimatepdfscanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TablesTest {
    /** Words of one line laid out at the given x positions (30 px high, ~18 px per char). */
    private fun line(y: Int, vararg cells: Pair<Int, String>): List<Word> = cells.flatMap { (x, text) ->
        var cx = x
        text.split(' ').map { t -> Word(t, 90f, cx, y, cx + t.length * 18, y + 30, 0, 0, 0).also { cx += t.length * 18 + 10 } }
    }

    @Test
    fun recognisesColumnsWithMultiWordCellsAndATitle() {
        val words = line(100, 100 to "Artikelliste Oktober") +
            line(200, 100 to "Artikel", 700 to "Menge", 1000 to "Preis") +
            line(260, 100 to "Rote Äpfel", 700 to "3", 1000 to "2,49") +
            line(320, 100 to "Brot", 1000 to "3,10") + // empty middle cell
            line(380, 100 to "Milch frisch 1 l", 700 to "12", 1000 to "1.234,50")
        val t = Tables.extract(words)
        assertEquals(listOf("Artikelliste Oktober", "", ""), t[0])
        assertEquals(listOf("Artikel", "Menge", "Preis"), t[1])
        assertEquals(listOf("Rote Äpfel", "3", "2,49"), t[2])
        assertEquals(listOf("Brot", "", "3,10"), t[3])
        assertEquals(listOf("Milch frisch 1 l", "12", "1.234,50"), t[4])
    }

    @Test
    fun csvQuotesAndNumbers() {
        assertEquals("﻿a;\"b;c\"\r\n\"x\"\"y\";", Tables.toCsv(listOf(listOf("a", "b;c"), listOf("x\"y", ""))))
        assertEquals(1234.5, Tables.number("1.234,50"))
        assertEquals(-12.5, Tables.number("-12,5"))
        assertEquals(1234.56, Tables.number("1,234.56"))
        assertEquals(12.5, Tables.number("12.5"))
        assertEquals(7.0, Tables.number("7 €"))
        assertNull(Tables.number("Brot"))
        assertNull(Tables.number("12.10.2026"))
    }
}
