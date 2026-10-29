package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.Test

/**
 * PaddleOCR language packs against Tesseract (tessdata_best) on the typeset test pages, clean and
 * degraded like a phone photo: ./gradlew :scancore:test -Deval=langs --tests '*LanguageEngineEval'
 */
class LanguageEngineEval {
    init { TestSupport.init() }

    private val sp = File("../../testdata")

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

    /** Smaller, blurred, noisy, unevenly lit, strongly compressed. */
    private fun degrade(img: Mat): Mat {
        val small = Mat(); Imgproc.resize(img, small, Size(img.cols() * 0.6, img.rows() * 0.6), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), 1.1)
        val f = Mat(); small.convertTo(f, CvType.CV_32FC3)
        val light = Mat(small.size(), CvType.CV_32FC3)
        for (y in 0 until light.rows()) { val v = 0.75 + 0.25 * y / light.rows(); light.put(y, 0, FloatArray(light.cols() * 3) { v.toFloat() }) }
        Core.multiply(f, light, f)
        val noise = Mat(small.size(), CvType.CV_32FC3); Core.randn(noise, 0.0, 8.0); Core.add(f, noise, f)
        f.convertTo(small, CvType.CV_8UC3)
        val buf = MatOfByte(); Imgcodecs.imencode(".jpg", small, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 45))
        return Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR)
    }

    @Test
    fun compare() {
        if (System.getProperty("eval") != "langs") return
        val texts = File(sp, "langtest/texts.json").readText()
        val models = File("../../models/paddle")
        val cases = listOf(Triple("zh", "chi_sim", "PP-OCRv5_mobile_rec_onnx"), Triple("ar", "ara", "arabic_PP-OCRv5_mobile_rec_onnx"),
            Triple("hi", "hin", "devanagari_PP-OCRv5_mobile_rec_onnx"), Triple("ru", "rus", "cyrillic_PP-OCRv5_mobile_rec_onnx"),
            Triple("uk", "ukr", "cyrillic_PP-OCRv5_mobile_rec_onnx"))
        for ((lang, tess, pack) in cases) {
            val dir = File(sp, "packs/$pack")
            val paddle = PaddleOcr(File(models, "ch_PP-OCRv5_det_mobile.onnx").readBytes(), File(dir, "inference.onnx").readBytes(),
                characters = PaddleOcr.charactersFromYaml(File(dir, "inference.yml").readText()), rtl = lang == "ar",
                preferredTextHeight = System.getProperty("paddleHeight")?.toDouble() ?: PaddleOcr.TEXT_HEIGHT)
            val tesseract = TesseractCli(tess)
            val block = Regex("\"$lang\": \\[(.*?)]", RegexOption.DOT_MATCHES_ALL).find(texts)!!.groupValues[1]
            val truth = norm(Regex("\"([^\"]*)\"").findAll(block).joinToString(" ") { it.groupValues[1] }, lang)
            val clean = Imgcodecs.imread(File(sp, "langtest/$lang.png").path)
            for ((kind, img) in listOf("clean" to clean, "photo-like" to degrade(clean))) {
                val gray = Enhance.toGray(Enhance.readable(img)) // as the pipeline feeds the OCR
                for ((name, engine) in listOf("PaddleOCR" to paddle as OcrEngine, "Tesseract" to tesseract as OcrEngine)) {
                    val t0 = System.currentTimeMillis()
                    val read = norm(pageText(TextSize.recognize(engine, gray, TextSize.TARGET, true)).replace("\n", " "), lang)
                    println("LANG %-3s %-10s %-9s CER %5.1f %%  %5d ms".format(lang, kind, name, 100.0 * distance(read, truth) / truth.length, System.currentTimeMillis() - t0))
                    if (System.getProperty("evalPhoto") == lang) println("  $name: $read\n  truth: $truth")
                }
            }
            paddle.close()
        }
    }
}
