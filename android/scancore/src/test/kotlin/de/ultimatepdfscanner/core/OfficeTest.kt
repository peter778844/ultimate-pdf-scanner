package de.ultimatepdfscanner.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OfficeTest {
    /** All parts of the package; every XML part must be well-formed. */
    private fun parts(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> while (true) { val e = z.nextEntry ?: break; out[e.name] = z.readBytes() } }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        out.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.forEach { (name, b) ->
            runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(b)) }.onFailure { throw AssertionError("$name: ${it.message}") }
        }
        return out
    }

    @Test
    fun docxHasTextImagesAndPageBreaks() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val buf = ByteArrayOutputStream()
        Office.writeDocx("Test & <Titel>", listOf(Office.DocxPage(listOf("Erste Zeile\nzweite", "Größe < 5 & \"x\"").map { Office.Block.Text(it) }, jpeg, 1000, 1414),
            Office.DocxPage(listOf(Office.Block.Text("Seite zwei"), Office.Block.Text("مرحبا بالعالم 2026")))), buf)
        val p = parts(buf.toByteArray())
        assertTrue("word/media/image1.jpeg" in p)
        val doc = String(p.getValue("word/document.xml"))
        assertTrue("Größe &lt; 5 &amp; &quot;x&quot;" in doc)
        assertEquals(1, Regex("""w:type="page"""").findAll(doc).count())
        assertEquals(1, Regex("<w:bidi/>").findAll(doc).count()) // only the Arabic paragraph runs right to left
        File("build/office/test.docx").apply { parentFile.mkdirs() }.writeBytes(buf.toByteArray()) // for a manual look in Word
    }

    /** Table of contents: dot leaders become a right tab with dot leader, the page number stays on the entry's line. */
    @Test
    fun tableOfContentsUsesDotLeaderTabs() {
        val buf = ByteArrayOutputStream()
        Office.writeDocx("TOC", listOf(Office.DocxPage(listOf(Office.Block.Text("Exzitonen . . . . . . 315\nOberflächenimpedanz ..........326\n15. Halbleiter-Kristalle")))), buf)
        val doc = String(parts(buf.toByteArray()).getValue("word/document.xml"))
        assertTrue("""<w:tab w:val="right" w:leader="dot" w:pos="9072"/>""" in doc, doc)
        assertTrue("""<w:t xml:space="preserve">Exzitonen</w:t></w:r><w:r><w:tab/><w:t>315</w:t>""" in doc, doc)
        assertTrue("""<w:t xml:space="preserve">15. Halbleiter-Kristalle</w:t>""" in doc, doc)
        assertTrue(". . ." !in doc)
    }

    @Test
    fun xlsxNumbersAndStrings() {
        val buf = ByteArrayOutputStream()
        Office.writeXlsx(listOf("Seite 1" to listOf(listOf("Artikel", "Preis"), listOf("Brot", "3,10"), listOf("", "1.234,50"))), buf)
        val sheet = String(parts(buf.toByteArray()).getValue("xl/worksheets/sheet1.xml"))
        assertTrue("""<c r="B2"><v>3.1</v></c>""" in sheet, sheet)
        assertTrue("""<c r="B3"><v>1234.5</v></c>""" in sheet, sheet)
        assertTrue("""<c r="A2" t="inlineStr"><is><t xml:space="preserve">Brot</t></is></c>""" in sheet)
        assertEquals("AA", Office.column(26))
        assertEquals("AZ", Office.column(51))
        File("build/office/test.xlsx").apply { parentFile.mkdirs() }.writeBytes(buf.toByteArray())
    }
}
