package de.ultimatepdfscanner.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextEditTest {
    private fun line(y: Int, par: Int, ln: Int, vararg texts: String): List<Word> {
        var x = 100
        return texts.map { t -> Word(t, 80f, x, y, x + t.length * 20, y + 30, 1, par, ln).also { x += t.length * 20 + 12 } }
    }

    private val words = line(100, 1, 1, "Sehr", "geehrte", "Datnen") + line(140, 1, 2, "und", "Herren,") +
        line(220, 2, 1, "vielen", "Dank", "fur", "lhre", "Anfrage.")

    @Test
    fun correctionsKeepPositionsAndText() {
        val edited = "Sehr geehrte Damen\nund Herren,\n\nvielen Dank für Ihre Anfrage."
        val out = TextEdit.apply(words, edited)
        assertEquals(edited, pageText(out))
        // the unchanged second line keeps its exact boxes
        assertEquals(words.filter { it.y0 == 140 }.map { it.x0 to it.x1 }, out.filter { it.text in setOf("und", "Herren,") }.map { it.x0 to it.x1 })
        // corrected words stay inside their original line
        val fuer = out.first { it.text == "für" }
        assertEquals(220, fuer.y0)
        assertTrue(fuer.x0 in 100..900)
    }

    @Test
    fun insertedAndDeletedLines() {
        val out = TextEdit.apply(words, "Sehr geehrte Damen\nNEUE ZEILE\n\nvielen Dank für Ihre Anfrage.")
        assertEquals("Sehr geehrte Damen\nNEUE ZEILE\n\nvielen Dank für Ihre Anfrage.", pageText(out))
        val neu = out.first { it.text == "NEUE" }
        assertTrue(neu.y0 > 130 && neu.y0 < 220, "new line placed below its predecessor: ${neu.y0}")
        assertEquals(emptyList(), TextEdit.apply(words, "  \n "))
    }
}
