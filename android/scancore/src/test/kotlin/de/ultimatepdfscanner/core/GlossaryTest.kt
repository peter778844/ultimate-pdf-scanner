package de.ultimatepdfscanner.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GlossaryTest {
    private fun gz(vararg lines: String) = ByteArrayOutputStream().also { b ->
        GZIPOutputStream(b).bufferedWriter().use { w -> lines.forEach { w.write(it); w.write("\n") } }
    }.toByteArray().inputStream()

    private fun glossary(fields: Set<String> = emptySet()) = Glossary.load(
        gz("1\t12\tZuständigkeit der Mitgliedstaaten", "2\t12\tZuständigkeit", "3\t28\tBlutdruck", "4\t10\tEU", "5\t12,24\tRat", "6\t28\tRat", "7\t10\tEU-Recht"),
        gz("1\t12\tcompetence of the Member States", "2\t12\tcompetence", "3\t28\tblood pressure", "4\t10\tEU", "5\t12,24\tCouncil", "6\t28\tadvice", "7\t10\tEU law"),
        fields)

    @Test
    fun longestTermWinsAndPlaceholdersRoundTrip() {
        val g = glossary(setOf("12"))
        val (text, targets) = g.protect("Die Zuständigkeit der\nMitgliedstaaten und die Zuständigkeit des Rates.")
        assertEquals("Die ⟦1⟧ und die ⟦2⟧ des Rates.", text)
        assertEquals(listOf("competence of the Member States", "competence"), targets)
        assertEquals("The competence of the Member States and the competence of the Council.",
            g.restore("The ⟦1⟧ and the ⟦ 2 ⟧ of the Council.", targets))
        // a lost placeholder: the caller translates without the glossary
        assertNull(g.restore("The ⟦1⟧ of the Council.", targets))
    }

    @Test
    fun onlyMissingTermsAndSentenceStart() {
        val g = glossary(setOf("12"))
        // the translator already wrote "competence": that term stays text, words inside it are not matched on their own
        val (text, targets) = g.protect("Die Zuständigkeit der Mitgliedstaaten und der Rat.") { it != "competence of the Member States" }
        assertEquals("Die Zuständigkeit der Mitgliedstaaten und der ⟦1⟧.", text)
        assertEquals("Council meets. And the council.", g.restore("⟦1⟧ meets. And the ⟦1⟧.", listOf("council")))
    }

    @Test
    fun fieldsAmbiguityAndAbbreviations() {
        // "Rat" is the Council in law but advice in social questions: with both fields it is left to the translator
        assertEquals(listOf("Council"), glossary(setOf("12")).protect("Der Rat tagt.").second)
        assertEquals(emptyList(), glossary().protect("Der Rat tagt.").second)
        // abbreviations only match in their own spelling; "eu" in Portuguese is "I"
        val g = glossary(setOf("10"))
        assertEquals(listOf("EU law"), g.protect("Das EU-Recht gilt.").second)
        assertEquals(emptyList(), g.protect("eu sei").second)
        assertEquals(listOf("blood pressure"), glossary(setOf("28")).protect("BLUTDRUCK messen").second)
    }

    /** The real IATE files if built (tools/build_glossaries.py): load time and size of German -> English, law. */
    @Test
    fun realPacks() {
        val dir = File("../../glossary_src/packs")
        val de = File(dir, "glossary-de.tsv.gz")
        val en = File(dir, "glossary-en.tsv.gz")
        if (!de.isFile || !en.isFile) return
        val t0 = System.currentTimeMillis()
        val g = Glossary.load(de.inputStream(), en.inputStream(), setOf("12"))
        println("de->en law: ${g.size} terms in ${System.currentTimeMillis() - t0} ms")
        val (text, targets) = g.protect("Die Zuständigkeit der Mitgliedstaaten bleibt unberührt; die Beweislast trägt der Kläger.")
        println("$text $targets")
        assertTrue(g.size > 1000)
        // only the terms of the text's words: the same result from a tiny glossary
        val page = "Die Zuständigkeit der Mitgliedstaaten bleibt unberührt; die Beweislast trägt der Kläger. Der Anspruch auf Schadensersatz verjährt."
        val vocabulary = Regex("[\\p{L}\\p{N}]+").findAll(page).map { it.value.lowercase() }.toSet()
        for (fields in listOf(setOf("12"), emptySet())) {
            val full = Glossary.load(de.inputStream(), en.inputStream(), fields)
            val small = Glossary.load(de.inputStream(), en.inputStream(), fields, vocabulary)
            println("fields=$fields: ${full.size} -> ${small.size} terms")
            assertEquals(full.protect(page), small.protect(page))
            assertTrue(small.size < 200)
        }
    }
}
