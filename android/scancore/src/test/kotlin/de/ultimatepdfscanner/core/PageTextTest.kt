package de.ultimatepdfscanner.core

import kotlin.test.Test
import kotlin.test.assertEquals

class PageTextTest {
    private fun line(n: Int, vararg words: String) = words.mapIndexed { i, t -> Word(t, 95f, i * 100, n * 40, i * 100 + 90, n * 40 + 30, 1, 1, n) }

    @Test
    fun hyphenatedWordsAreJoinedAcrossLines() {
        val words = line(1, "Bei", "Elektrolyt-", "Konden-") + line(2, "satoren", "mit", "Gleich-") + line(3, "und", "Wechsel-") +
            line(4, "Strom", "Hochfrequenz-") + line(5, "technik.")
        assertEquals("Bei Elektrolyt- Kondensatoren\nmit Gleich-\nund Wechsel-\nStrom Hochfrequenztechnik.", pageText(words))
    }

    @Test
    fun hyphenRules() {
        assertEquals(true, isLineBreakHyphen("Konden-", "satoren"))
        assertEquals(true, isLineBreakHyphen("Konden¬", "satoren,"))
        assertEquals(false, isLineBreakHyphen("Gleich-", "und")) // suspended hyphen
        assertEquals(false, isLineBreakHyphen("Wechsel-", "Strom")) // capital: a new word
        assertEquals(false, isLineBreakHyphen("3-", "fach"))
        assertEquals(false, isLineBreakHyphen("-", "satoren"))
    }
}
