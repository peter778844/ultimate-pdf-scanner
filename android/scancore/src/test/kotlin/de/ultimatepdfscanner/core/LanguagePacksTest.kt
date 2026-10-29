package de.ultimatepdfscanner.core

import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PaddleOCR language packs (official PP-OCRv5 ONNX recognisers + inference.yml): character lists as a
 * YAML parser reads them, and test pages typeset by Word (Chinese, Arabic right to left, Hindi) read
 * back. Skipped without the downloaded packs in [PACKS].
 */
class LanguagePacksTest {
    init { TestSupport.init() }

    private val PACKS = File(System.getProperty("packs") ?: "../../testdata/packs")
    private val PAGES = File(PACKS.parentFile, "langtest")

    @Test
    fun characterListsMatchAYamlParser() {
        val dirs = PACKS.listFiles { f -> f.isDirectory && File(f, "characters.json").isFile }.orEmpty()
        for (d in dirs) {
            val expected = Regex(""""((?:[^"\\]|\\.)*)"""").findAll(File(d, "characters.json").readText()).map { m ->
                m.groupValues[1].replace(Regex("""\\u([0-9a-fA-F]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
                    .replace("\\\"", "\"").replace("\\/", "/").replace("\\\\", "\\")
            }.toList()
            assertEquals(expected, PaddleOcr.charactersFromYaml(File(d, "inference.yml").readText()), d.name)
        }
    }

    @Test
    fun logicalOrderKeepsNumbersAndLatinRuns() {
        // visual (left to right) "ABC 400 XY" with A..Z standing for Arabic letters is not testable with ASCII, so:
        assertEquals("نسمة 400 مليون", PaddleOcr.logicalOrder("نويلم 400 ةمسن"))
    }

    private fun mark(c: Char) = Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }
    private fun norm(s: String, lang: String) = s.filter { it.isLetterOrDigit() || mark(it) || (lang != "zh" && it == ' ') }.replace(Regex(" +"), " ").trim()

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1); cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    @Test
    fun typesetPagesAreReadInReadingOrder() {
        val texts = File(PAGES, "texts.json").takeIf { it.isFile }?.readText() ?: return
        val models = File("../../models/paddle")
        for ((lang, pack) in listOf("zh" to "PP-OCRv5_mobile_rec_onnx", "ar" to "arabic_PP-OCRv5_mobile_rec_onnx", "hi" to "devanagari_PP-OCRv5_mobile_rec_onnx",
                "ru" to "eslav_PP-OCRv5_mobile_rec_onnx", "uk" to "eslav_PP-OCRv5_mobile_rec_onnx", "ru" to "cyrillic_PP-OCRv5_mobile_rec_onnx", "uk" to "cyrillic_PP-OCRv5_mobile_rec_onnx")) {
            val dir = File(PACKS, pack)
            val ocr = PaddleOcr(File(models, "ch_PP-OCRv5_det_mobile.onnx").readBytes(), File(dir, "inference.onnx").readBytes(),
                characters = PaddleOcr.charactersFromYaml(File(dir, "inference.yml").readText()), rtl = lang == "ar", trustLineClassifier = lang == "zh" || lang == "ru" || lang == "uk",
                clsModel = File(models, "ch_ppocr_mobile_v2.0_cls_mobile.onnx").readBytes())
            val block = Regex("\"$lang\": \\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(texts)!!.groupValues[1]
            val truth = norm(Regex("\"([^\"]*)\"").findAll(block).joinToString(" ") { it.groupValues[1] }, lang)
            val img = Imgcodecs.imread(File(PAGES, "$lang.png").path)
            val words = ocr.recognize(img)
            val read = norm(pageText(words).replace("\n", " "), lang)
            // PDF with the text layer (build/langpacks/<lang>.pdf), for a look at copying text out of it
            val buf = org.opencv.core.MatOfByte().also { Imgcodecs.imencode(".jpg", img, it) }
            File("build/langpacks/$lang.pdf").apply { parentFile.mkdirs() }.outputStream().use { out ->
                PdfWriter(out).use { it.addPage(PdfPage.natural(PdfImage.jpeg(buf.toArray()), words, dpi = 200.0)) }
            }
            val cer = distance(read, truth).toDouble() / truth.length
            println("$lang CER %.3f\n  read:  $read\n  truth: $truth".format(cer))
            assertEquals(0, ocr.orientation(Enhance.toGray(img), null), "$lang upright")
            assertEquals(180, ocr.orientation(Orientation.rotate(Enhance.toGray(img), 180), null), "$lang upside down")
            assertTrue(cer < 0.05, "$lang CER $cer")
            ocr.close(); img.release()
        }
    }
}
