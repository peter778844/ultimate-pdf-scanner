package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PDF writer: text layer (Unicode, word gaps, positions), PDF/A structure, encryption. Checked
 * with the external tools poppler (pdftotext) and Ghostscript when they are installed.
 */
class PdfTest {
    init { TestSupport.init() }

    private val pdftotext = listOf("C:/msys64/mingw64/bin/pdftotext.exe", "C:/Program Files/Git/mingw64/bin/pdftotext.exe")
        .firstOrNull { File(it).exists() }
    private val gs = File("C:/Program Files/gs").listFiles()?.map { File(it, "bin/gswin64c.exe") }?.firstOrNull { it.exists() }

    private fun run(vararg cmd: String): Pair<Int, String> {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor(60, TimeUnit.SECONDS)
        return p.exitValue() to text
    }

    private fun jpeg(m: Mat): ByteArray {
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", m, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90))
        return buf.toArray()
    }

    private val words = listOf(
        Word("Widerstand", 95f, 100, 100, 400, 140, 1, 1, 1),
        Word("R", 95f, 420, 100, 445, 140, 1, 1, 1),
        Word("=", 95f, 460, 110, 480, 135, 1, 1, 1),
        Word("U/I", 95f, 495, 100, 560, 140, 1, 1, 1),
        Word("in", 95f, 575, 105, 600, 140, 1, 1, 1),
        Word("Ω", 95f, 615, 100, 640, 140, 1, 1, 1),
        Word("Größe:", 95f, 100, 200, 260, 245, 1, 1, 2),
        Word("α≤∫μ€", 95f, 280, 200, 420, 245, 1, 1, 2),
    )

    private fun page(): Mat = Mat(1000, 800, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0)).also {
        Imgproc.putText(it, "Widerstand R = U/I in Ohm", Point(100.0, 140.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.5, Scalar(0.0, 0.0, 0.0), 3)
    }

    @Test
    fun jpegHeader() {
        val img = page()
        val j = PdfImage.jpeg(jpeg(img))
        assertEquals(800, j.width); assertEquals(1000, j.height); assertEquals(3, j.components)
        val g = Mat(); Imgproc.cvtColor(img, g, Imgproc.COLOR_BGR2GRAY)
        assertEquals(1, PdfImage.jpeg(jpeg(g)).components)
        img.release(); g.release()
    }

    @Test
    fun numbers() {
        assertEquals("0", PdfWriter.n(0.0)); assertEquals("1.5", PdfWriter.n(1.5)); assertEquals("-0.25", PdfWriter.n(-0.25))
        assertEquals("595.2756", PdfWriter.n(595.27559)); assertEquals("0", PdfWriter.n(-0.00001)); assertEquals("12", PdfWriter.n(12.0))
    }

    private fun write(file: File, info: PdfInfo, bilevel: Boolean = false) {
        val img = page()
        val image = if (bilevel) {
            val g = Mat(); Imgproc.cvtColor(img, g, Imgproc.COLOR_BGR2GRAY)
            PdfImage.bilevel(g).also { g.release() }
        } else PdfImage.jpeg(jpeg(img))
        img.release()
        file.parentFile.mkdirs()
        file.outputStream().use { o ->
            PdfWriter(o, info).use { w ->
                w.addPage(PdfPage.natural(image, words, dpi = 100.0))
                w.addPage(PdfPage.onPaper(image, PdfPage.A4.first, PdfPage.A4.second, words, dpi = 100.0, rotation = 90))
            }
        }
    }

    @Test
    fun textLayer() {
        val f = File("build/pdftest/plain.pdf")
        write(f, PdfInfo(title = "Prüfung – Größen", author = "Test"))
        val bytes = f.readBytes()
        val head = String(bytes, 0, 20, Charsets.ISO_8859_1)
        assertTrue(head.startsWith("%PDF-1.7"))
        val exe = pdftotext ?: return
        val (code, _) = run(exe, "-enc", "UTF-8", "-f", "1", "-l", "1", f.path, "build/pdftest/plain.txt")
        assertEquals(0, code)
        val text = File("build/pdftest/plain.txt").readText(Charsets.UTF_8)
        println("PDFTEXT: $text")
        assertTrue(text.contains("Widerstand R = U/I in Ω"), text)
        assertTrue(text.contains("Größe: α≤∫μ€"), text)
        // word positions (Ghostscript's text extraction): page 1 is 800x1000 px at 100 dpi, 0.72 pt per pixel, y from the top
        val g = gs ?: return
        val (_, xml) = run(g.path, "-q", "-dBATCH", "-dNOPAUSE", "-dFirstPage=1", "-dLastPage=1", "-sDEVICE=txtwrite", "-dTextFormat=0",
            "-sOutputFile=-", f.path)
        val span = Regex("""<span bbox="([\d.]+) ([\d.]+) ([\d.]+) ([\d.]+)" font="GlyphLessFont[^"]*" size="([\d.]+)">""").find(xml)!!
        val (x0, base, x1, _, size) = span.destructured.toList().map { it.toDouble() }
        assertEquals(100 * 0.72, x0, 1.0) // first word starts at its box
        assertEquals(140 * 0.72, base, 1.0) // baseline = bottom of the line box
        assertEquals(40 * 0.72, size, 0.1) // font size = line height, so a selection covers the line
        assertEquals(420 * 0.72, x1, 1.0) // the first word with its space reaches the next word
    }

    @Test
    fun ghostscriptRenders() {
        val exe = gs ?: return
        for ((name, info, bilevel) in listOf(Triple("pdfa", PdfInfo(title = "A"), false), Triple("bw", PdfInfo(title = "B"), true))) {
            val f = File("build/pdftest/$name.pdf")
            write(f, info, bilevel)
            val (code, out) = run(exe.path, "-dBATCH", "-dNOPAUSE", "-dPDFSTOPONERROR", "-sDEVICE=png16m", "-r30",
                "-sOutputFile=build/pdftest/$name-%d.png", f.path)
            assertEquals(0, code, out)
            assertTrue(!out.contains("Error", ignoreCase = true) && !out.contains("repaired", ignoreCase = true), out)
            val png = Imgcodecs.imread("build/pdftest/$name-1.png")
            assertTrue(png.cols() in 235..245 && png.rows() in 295..305, "${png.cols()}x${png.rows()}")
            // the drawn text is visible (dark pixels in the first line), the invisible layer is not
            val row = png.rowRange(35, 45)
            assertTrue(Core.minMaxLoc(Mat().also { Imgproc.cvtColor(row, it, Imgproc.COLOR_BGR2GRAY) }).minVal < 100)
            val blank = png.rowRange(70, 80) // second text line exists only in the invisible layer
            assertTrue(Core.minMaxLoc(Mat().also { Imgproc.cvtColor(blank, it, Imgproc.COLOR_BGR2GRAY) }).minVal > 200)
        }
    }

    @Test
    fun encrypted() {
        val f = File("build/pdftest/secret.pdf")
        write(f, PdfInfo(title = "Geheim", password = "Passwört 123"))
        val g = gs ?: return
        fun open(vararg pw: String) = run(g.path, "-q", "-dBATCH", "-dNOPAUSE", *pw, "-sDEVICE=txtwrite", "-sOutputFile=-", f.path)
        assertTrue(open().second.contains("requires a password"), "opened without password")
        assertTrue(open("-sPDFPassword=falsch").second.contains("Password did not work"), "opened with a wrong password")
        val (ok, text) = open("-sPDFPassword=Passwört 123")
        assertEquals(0, ok, text)
        assertTrue(text.contains("Widerstand R = U/I in Ω"), text)
        // a second, independent reader (xpdf); ASCII password because of the Windows command line
        val exe = pdftotext ?: return
        val f2 = File("build/pdftest/secret2.pdf")
        write(f2, PdfInfo(title = "Geheim", password = "geheim 123"))
        assertTrue(run(exe, f2.path, "build/pdftest/secret-bad.txt").first != 0, "opened without password")
        val (ok2, msg) = run(exe, "-enc", "UTF-8", "-upw", "geheim 123", f2.path, "build/pdftest/secret.txt")
        assertEquals(0, ok2, msg)
        assertTrue(File("build/pdftest/secret.txt").readText(Charsets.UTF_8).contains("Widerstand R = U/I in Ω"))
    }

    @Test
    fun streamingManyPages() {
        // 300 pages of 2 MB each must not need 600 MB: pages are written as they come
        val img = page()
        val image = PdfImage.jpeg(jpeg(img))
        img.release()
        val sink = object : java.io.OutputStream() { var n = 0L; override fun write(b: Int) { n++ }; override fun write(b: ByteArray, o: Int, l: Int) { n += l } }
        PdfWriter(sink, PdfInfo()).use { w -> repeat(300) { w.addPage(PdfPage.natural(image, words)) } }
        assertTrue(sink.n > 300L * image.let { (it as PdfImage.Jpeg).bytes.size })
    }

    @Test
    fun icc() {
        val p = Icc.srgb()
        assertEquals(p.size, java.nio.ByteBuffer.wrap(p, 0, 4).int)
        assertEquals("acsp", String(p, 36, 4, Charsets.US_ASCII))
        assertEquals(0, p.size % 4)
    }
}

/** PDF/A-2b conformance of the writer's output, checked with veraPDF. */
class PdfaTest {
    init { TestSupport.init() }

    @Test
    fun validPdfA2b() {
        val img = Mat(600, 400, CvType.CV_8UC3, Scalar(250.0, 250.0, 250.0))
        Imgproc.putText(img, "Test", Point(50.0, 100.0), Imgproc.FONT_HERSHEY_SIMPLEX, 2.0, Scalar(0.0, 0.0, 0.0), 3)
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", img, buf)
        val gray = Mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_BGR2GRAY) }
        val f = File("build/pdftest/pdfa2b.pdf").apply { parentFile.mkdirs() }
        val words = listOf(Word("Test", 90f, 50, 60, 200, 100, 1, 1, 1), Word("Größe Ω", 90f, 220, 60, 380, 100, 1, 1, 1))
        f.outputStream().use { o ->
            PdfWriter(o, PdfInfo(title = "Prüfung (A)", author = "Ä & <B>", subject = "s", keywords = "k")).use { w ->
                w.addPage(PdfPage.natural(PdfImage.jpeg(buf.toArray()), words, dpi = 150.0))
                w.addPage(PdfPage.onPaper(PdfImage.bilevel(gray), PdfPage.A4.first, PdfPage.A4.second, words, rotation = 270))
                val g = MatOfByte().also { Imgcodecs.imencode(".jpg", gray, it) }
                w.addPage(PdfPage.natural(PdfImage.jpeg(g.toArray()), words))
            }
        }
        org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider.initialise()
        val foundry = org.verapdf.pdfa.Foundries.defaultInstance()
        foundry.createParser(f.inputStream(), org.verapdf.pdfa.flavours.PDFAFlavour.PDFA_2_B).use { parser ->
            val validator = foundry.createValidator(org.verapdf.pdfa.flavours.PDFAFlavour.PDFA_2_B, false)
            val result = validator.validate(parser)
            val failed = result.testAssertions.filter { it.status != org.verapdf.pdfa.results.TestAssertion.Status.PASSED }
            failed.distinctBy { it.ruleId }.forEach { println("PDFA ${it.ruleId.clause}-${it.ruleId.testNumber}: ${it.message}") }
            println("PDFA checks: ${result.totalAssertions}")
            assertTrue(result.isCompliant, "PDF/A-2b violations: ${failed.map { it.ruleId.clause }.distinct()}")
        }
        // the validator really checks: the same document without the PDF/A parts is rejected
        val plain = File("build/pdftest/notpdfa.pdf")
        plain.outputStream().use { o -> PdfWriter(o, PdfInfo(title = "x", pdfa = false)).use { it.addPage(PdfPage.natural(PdfImage.jpeg(buf.toArray()), words)) } }
        foundry.createParser(plain.inputStream(), org.verapdf.pdfa.flavours.PDFAFlavour.PDFA_2_B).use { parser ->
            assertTrue(!foundry.createValidator(org.verapdf.pdfa.flavours.PDFAFlavour.PDFA_2_B, false).validate(parser).isCompliant)
        }
    }

    /** Validates any PDF file (-DpdfFile=...), e.g. one exported on the phone. */
    @Test
    fun validateFile() {
        val f = System.getProperty("pdfFile")?.let(::File) ?: return
        org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider.initialise()
        val foundry = org.verapdf.pdfa.Foundries.defaultInstance()
        foundry.createParser(f.inputStream(), org.verapdf.pdfa.flavours.PDFAFlavour.PDFA_2_B).use { parser ->
            val result = foundry.createValidator(org.verapdf.pdfa.flavours.PDFAFlavour.PDFA_2_B, false).validate(parser)
            result.testAssertions.filter { it.status != org.verapdf.pdfa.results.TestAssertion.Status.PASSED }
                .distinctBy { it.ruleId }.forEach { println("PDFA ${it.ruleId.clause}-${it.ruleId.testNumber}: ${it.message}") }
            println("PDFA FILE ${f.name}: compliant=${result.isCompliant} checks=${result.totalAssertions}")
            assertTrue(result.isCompliant)
        }
    }
}
