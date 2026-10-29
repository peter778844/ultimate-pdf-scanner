package de.ultimatepdfscanner.core

import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Handwriting fallback of the OCR: on handwritten pages (ScaDS.AI German handwriting, CC BY 4.0,
 * downloaded to [SCADS]) the character error rate must drop clearly; on printed book pages the
 * text must stay (nearly) the same. Skipped when the data is not there.
 */
class HandwritingReaderTest {
    init { TestSupport.init() }

    private val models = File("../../models")
    private val SCADS = File(System.getProperty("scads") ?: "../../testdata/scads")

    private fun engine(handwriting: Boolean) = PaddleOcr(File(models, "paddle/ch_PP-OCRv5_det_mobile.onnx").readBytes(),
        File(models, "paddle/latin_PP-OCRv5_rec_mobile.onnx").readBytes(), threads = 4,
        handwriting = if (handwriting) HandwritingReader(File(models, "handwriting/dehtr2_uint8.onnx").readBytes(),
            File(models, "handwriting/dehtr2_alphabet.txt").readText()) else null)

    private fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()

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
    fun handwrittenPagesAreReadMuchBetter() {
        val csv = File(SCADS, "ground_truth/csv/line_annotations.csv")
        if (!csv.isFile) return
        // page_id,line_id,xml_file,line_file,line_idx,x,y,width,height,text (the text may contain commas)
        val truth = csv.readLines().drop(1).map { it.split(",", limit = 10) }.filter { it.size == 10 }
            .groupBy { it[0] }.mapValues { (_, rows) -> norm(rows.sortedBy { it[4].toInt() }.joinToString(" ") { it[9] }) }
        val pages = truth.keys.sorted().filterIndexed { i, _ -> i % 25 == 0 }.take(20)
        val plain = engine(false); val hw = engine(true)
        var errPlain = 0; var errHw = 0; var chars = 0
        for (id in pages) {
            val img = Imgcodecs.imread(File(SCADS, "images/pages/$id.jpg").path)
            if (img.empty()) continue
            val gt = truth.getValue(id)
            val a = norm(pageText(plain.recognize(img)).replace("\n", " "))
            val b = norm(pageText(hw.recognize(img)).replace("\n", " "))
            val ea = distance(a, gt); val eb = distance(b, gt)
            errPlain += ea; errHw += eb; chars += gt.length
            println("$id  print-only CER %.3f  with handwriting %.3f".format(ea.toDouble() / gt.length, eb.toDouble() / gt.length))
            img.release()
        }
        println("handwriting pages: print-only CER %.3f, with handwriting %.3f (%d chars)".format(errPlain.toDouble() / chars, errHw.toDouble() / chars, chars))
        assertTrue(errHw < errPlain * 0.6, "handwriting fallback must cut the errors clearly")
    }

    @Test
    fun printedPagesStayTheSame() {
        val dir = File("../../testdata/series/1792753488220_Buch_2026-10-23_13-04/pages")
        if (!dir.isDirectory) return
        val plain = engine(false); val hw = engine(true)
        var changed = 0; var total = 0
        for (f in dir.listFiles { f -> f.name.endsWith(".jpg") }!!.sorted().take(12)) {
            val img = Imgcodecs.imread(f.path)
            val a = norm(pageText(plain.recognize(img)).replace("\n", " "))
            val b = norm(pageText(hw.recognize(img)).replace("\n", " "))
            val d = distance(a, b)
            changed += d; total += a.length
            if (d > 0) println("${f.name}: $d of ${a.length} characters changed")
            img.release()
        }
        println("printed pages: %.4f of the characters changed by the handwriting fallback".format(changed.toDouble() / total))
        assertTrue(changed < total * 0.01, "printed text must stay")
    }
}
