package de.ultimatepdfscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PaddleOCR engine: models load, words with spaces and boxes in page pixels, reading order of two columns. */
class PaddleOcrTest {
    init { TestSupport.init() }

    private val dir = File("../../models/paddle")
    private fun engine() = PaddleOcr(File(dir, "ch_PP-OCRv5_det_mobile.onnx").readBytes(), File(dir, "latin_PP-OCRv5_rec_mobile.onnx").readBytes())

    @Test
    fun readsWordsWithBoxesAndOrder() {
        if (!dir.isDirectory) return
        engine().use { ocr ->
            println("PADDLE classes ${ocr.chars.size} last=${ocr.chars.takeLast(3).map { it.codePoints().toArray().toList() }}")
            val img = Mat(900, 1600, CvType.CV_8U, Scalar(255.0))
            // two columns: the left one must be read completely before the right one
            for ((i, t) in listOf("Erste Zeile links", "zweite Zeile links").withIndex())
                Imgproc.putText(img, t, Point(60.0, 200.0 + 70 * i), Imgproc.FONT_HERSHEY_SIMPLEX, 1.6, Scalar(0.0), 3)
            for ((i, t) in listOf("Rechts oben 42", "Rechts unten").withIndex())
                Imgproc.putText(img, t, Point(900.0, 200.0 + 70 * i), Imgproc.FONT_HERSHEY_SIMPLEX, 1.6, Scalar(0.0), 3)
            val words = ocr.recognize(img)
            val text = pageText(words)
            println("PADDLE text: $text")
            assertTrue(text.indexOf("links") < text.indexOf("Rechts"), text)
            assertEquals(listOf("Erste", "Zeile", "links", "zweite", "Zeile", "links"), words.take(6).map { it.text })
            val erste = words.first { it.text == "Erste" }
            assertTrue(erste.x0 in 40..80 && erste.y1 in 195..225, "box ${erste.x0},${erste.y0},${erste.x1},${erste.y1}")
        }
    }

    /**
     * Two columns with a gutter of about one line height (magazine page): the lines of the two
     * columns at the same height must not be joined into one line.
     */
    @Test
    fun narrowGutterKeepsColumnsApart() {
        val page = File("../../testdata/columns/narrow_gutter.jpg")
        if (!dir.isDirectory || !page.exists()) return
        engine().use { ocr ->
            val text = pageText(ocr.recognize(org.opencv.imgcodecs.Imgcodecs.imread(page.path, org.opencv.imgcodecs.Imgcodecs.IMREAD_GRAYSCALE)))
            val lines = text.lines()
            assertTrue(lines.none { "Fortschritt als" in it && "Bedingungen" in it }, text)
            // the left column ends ("Stützen, die wir brauchen") before the right one starts ("Bedingungen, auf die wir")
            assertTrue(text.indexOf("Orientierungsproblem") in 0 until text.indexOf("Bedingungen"), text)
        }
    }

    /** Diagnosis: lines of one OCR input image (-DpaddleImage=<png>). */
    @Test
    fun linesOfImage() {
        val f = System.getProperty("paddleImage")?.let(::File) ?: return
        engine().use { ocr ->
            val img = org.opencv.imgcodecs.Imgcodecs.imread(f.path, org.opencv.imgcodecs.Imgcodecs.IMREAD_GRAYSCALE)
            for (l in ocr.readLines(img).sortedBy { it.box[0].y }) println("LINE %.2f %s | %s".format(l.score, l.box.joinToString(" ") { "%.0f,%.0f".format(it.x, it.y) }, l.text))
        }
    }
}
