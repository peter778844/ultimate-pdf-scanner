package de.ultimatepdfscanner.core

import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Rect
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormulasTest {
    init { TestSupport.init() }

    private fun word(text: String, x0: Int, y0: Int, x1: Int, y1: Int, par: Int, line: Int) = Word(text, 90f, x0, y0, x1, y1, 0, par, line)

    @Test
    fun formulaGoesAfterTheLineAboveAndItsWordsAreDropped() {
        val words = listOf(
            word("Es", 100, 100, 140, 130, 0, 0), word("gilt", 150, 100, 220, 130, 0, 0),
            word("H=Σ", 300, 200, 400, 240, 1, 0), // OCR garbage inside the formula
            word("wobei", 100, 300, 200, 330, 2, 0), word("k", 210, 300, 230, 330, 2, 0),
        )
        val parts = Formulas.layoutText(words, listOf(Rect(250, 180, 300, 80)))
        assertEquals(listOf("Es gilt", "#0", "wobei k"), parts.map { if (it is Formulas.Part.Text) it.text else "#" + (it as Formulas.Part.Formula).index })
        // a formula above all text comes first
        val top = Formulas.layoutText(words.drop(3), listOf(Rect(100, 10, 200, 50)))
        assertTrue(top.first() is Formulas.Part.Formula)
        // the equation number beside it goes with the formula; text left of it on its row comes before it
        val row = listOf(word("(92)", 20, 205, 70, 235, 3, 0), word("Damit", 100, 400, 180, 430, 4, 0),
            word("ist", 190, 400, 230, 430, 4, 0), word("wobei", 100, 500, 200, 530, 5, 0))
        val numbered = Formulas.layoutText(row, listOf(Rect(250, 180, 300, 80), Rect(250, 390, 200, 50)))
        assertEquals(listOf("#0(92)", "Damit ist", "#1", "wobei"),
            numbered.map { if (it is Formulas.Part.Text) it.text else (it as Formulas.Part.Formula).let { f -> "#" + f.index + (f.number ?: "") } })
    }

    @Test
    fun overlappingBoxesAreMerged() {
        val m = Formulas.merged(listOf(Rect(100, 100, 400, 60), Rect(300, 110, 400, 60), Rect(100, 300, 400, 60)))
        assertEquals(listOf(Rect(100, 100, 600, 70), Rect(100, 300, 400, 60)), m)
    }

    /** The app's <page>.words.json: [["text", conf, x0, y0, x1, y1, block, paragraph, line], ...] */
    private fun wordsFromJson(json: String) =
        Regex("""\["((?:[^"\\]|\\.)*)",([^,]+),(-?\d+),(-?\d+),(-?\d+),(-?\d+),(-?\d+),(-?\d+),(-?\d+)]""").findAll(json).map { m ->
            val v = m.groupValues
            Word(v[1].replace("\\\"", "\"").replace("\\\\", "\\"), v[2].toFloat(), v[3].toInt(), v[4].toInt(), v[5].toInt(), v[6].toInt(), v[7].toInt(), v[8].toInt(), v[9].toInt())
        }.toList()

    /** A real book page: formulas found and written as pictures into a Word file (build/office/formulas.docx, for a look in Word). */
    @Test
    fun bookPageToWord() {
        val model = File("../../models/formula/pp_doclayout_s.onnx")
        val page = File("../../testdata/series/1792753488220_Buch_2026-10-23_13-04/pages/a8bfd329.jpg")
        if (!model.isFile || !page.isFile) return
        val words = File(page.path.removeSuffix(".jpg") + ".words.json").takeIf { it.isFile }?.let { wordsFromJson(it.readText()) } ?: emptyList()
        Formulas(model.readBytes()).use { f ->
            val img = Imgcodecs.imread(page.path)
            val found = f.detect(img)
            assertTrue(found.size >= 10, "formulas found: ${found.size}")
            val parts = Formulas.layoutText(words, found)
            assertEquals(found.size, parts.count { it is Formulas.Part.Formula })
            assertTrue(parts.count { it is Formulas.Part.Formula && it.number != null } >= 6, "equation numbers beside the formulas")
            val blocks = parts.map { part ->
                when (part) {
                    is Formulas.Part.Text -> Office.Block.Text(part.text)
                    is Formulas.Part.Formula -> {
                        val box = found[part.index]
                        val buf = MatOfByte()
                        Imgcodecs.imencode(".jpg", img.submat(box), buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90))
                        Office.Block.Picture(buf.toArray(), box.width, box.height, box.width.toDouble() / img.cols(), part.number)
                    }
                }
            }
            File("build/office/formulas.docx").apply { parentFile.mkdirs() }.outputStream().use { Office.writeDocx("Formeln", listOf(Office.DocxPage(blocks)), it) }
        }
    }
}
