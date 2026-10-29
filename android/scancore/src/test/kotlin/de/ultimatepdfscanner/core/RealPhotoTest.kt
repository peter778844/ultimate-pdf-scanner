package de.ultimatepdfscanner.core

import org.opencv.core.Mat
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Parity with the Python prototype on the real test photos (skipped when they are absent):
 * detection and fold must match python_reference.json, and the full pipeline must reach the
 * prototype's OCR quality.
 */
class RealPhotoTest {
    init { TestSupport.init() }

    private val folder = File(System.getProperty("testfotos") ?: "C:/Users/Peter/Desktop/testfotos")
    private val photos = folder.listFiles { f -> f.extension.lowercase() == "jpg" }?.sortedBy { it.name } ?: emptyList()

    /** Minimal reader for the reference file (flat structure, numbers only). */
    private fun reference(): Map<String, Pair<List<Double>, List<Double>>> {
        val text = javaClass.getResource("/python_reference.json")?.readText() ?: return emptyMap()
        val entry = Regex("\"([^\"]+\\.jpg)\": \\{(.*?)\\n }", RegexOption.DOT_MATCHES_ALL)
        return entry.findAll(text).associate { m ->
            val body = m.groupValues[2]
            val quad = Regex("\"quad\": \\[(.*?)\\]\\s*,\\s*\"spread", RegexOption.DOT_MATCHES_ALL).find(body)!!.groupValues[1]
                .split(Regex("[^0-9.eE+-]+")).filter { it.isNotBlank() }.map { it.toDouble() }
            val fold = Regex("\"axis\": (\\d+),\\s*\"pos\": (\\d+),\\s*\"dip\": ([0-9.eE+-]+),\\s*\"confident\": (true|false)").find(body)!!.groupValues
            m.groupValues[1] to (quad to listOf(fold[1].toDouble(), fold[2].toDouble(), fold[3].toDouble(), if (fold[4] == "true") 1.0 else 0.0))
        }
    }

    @Test
    fun detectionAndFoldMatchPython() {
        val ref = reference()
        if (photos.isEmpty() || ref.isEmpty()) return
        for (photo in photos) {
            val (rq, rf) = ref.getValue(photo.name)
            val image = TestSupport.load(photo)
            // parity with the prototype's algorithm; the later colour stage (Detect.COLOR_SEED) only trims
            // background and moves the green cover strip of these magazines (checked by eye, ColorSeedEval)
            Detect.COLOR_SEED = false
            val det = try { Detect.detectDocument(image) } finally { Detect.COLOR_SEED = true }
            val quad = assertNotNull(det.quad, photo.name)
            val diag = hypot(image.cols().toDouble(), image.rows().toDouble())
            val err = (0 until 4).maxOf { hypot(quad[it].x - rq[2 * it], quad[it].y - rq[2 * it + 1]) }
            val (spread, smask) = Warp.warpQuadWithMask(image, det.mask!!, quad, Pipeline.SPREAD_MARGIN)
            val fold = Spread.findFold(Enhance.toGray(spread), smask)
            println("${photo.name}: corner diff ${"%.1f".format(err)} px (${"%.2f".format(100 * err / diag)} %), " +
                "fold kotlin $fold  python axis=${rf[0].toInt()} pos=${rf[1].toInt()} dip=${"%.3f".format(rf[2])}")
            assertTrue(err / diag < 0.01, "${photo.name}: corners differ by $err px")
            assertEquals(rf[3] == 1.0, fold.confident, "${photo.name}: split decision")
            if (fold.confident) {
                assertEquals(rf[0].toInt(), fold.axis, photo.name)
                assertTrue(abs(fold.pos - rf[1]) < 0.02 * spread.rows(), "${photo.name}: fold at ${fold.pos}, python ${rf[1]}")
            }
            listOf(image, spread, smask).forEach(Mat::release)
            det.close()
        }
    }

    /**
     * Photos taken with the app (hand touching the magazine, lit wooden table, legs in the frame):
     * each must give two upright, cleanly cropped pages with plenty of recognised text.
     */
    @Test
    fun phonePhotosWithHandAndGlare() {
        val ocr = TesseractCli("deu")
        val phone = File("../../testdata/phone").listFiles { f -> f.extension == "jpg" }?.sortedBy { it.name } ?: return
        if (phone.isEmpty() || !ocr.available) return
        for (photo in phone) {
            val image = TestSupport.load(photo)
            for (split in listOf(SplitMode.AUTO, SplitMode.DOUBLE)) {
                val pages = Pipeline.processPhoto(image, PipelineConfig(mode = Mode.COLOR, split = split), ocr)
                val words = pages.map { p -> p.words.count { it.text.length > 2 && it.conf >= 80 } }
                println("${photo.name} $split: ${pages.size} page(s), good words $words, sizes ${pages.map { "${it.image.cols()}x${it.image.rows()}" }}")
                assertEquals(2, pages.size, "${photo.name} $split")
                pages.forEach { p -> assertTrue(p.image.rows() > p.image.cols(), "${photo.name}: page should be upright portrait") }
                assertTrue(words.all { it >= 250 }, "${photo.name} $split: words $words")
                pages.forEach { it.close() }
            }
            image.release()
        }
    }

    @Test
    fun fullPipelineReachesPrototypeQuality() {
        val ocr = TesseractCli("deu")
        if (photos.isEmpty() || !ocr.available) return
        val out = File("build/realphoto-pages")
        val pages = ArrayList<PageResult>()
        for (photo in photos) {
            val t0 = System.currentTimeMillis()
            val image = TestSupport.load(photo)
            val result = Pipeline.processPhoto(image, PipelineConfig(mode = Mode.AUTO), ocr)
            image.release()
            println("${photo.name}: ${result.size} page(s) in ${(System.currentTimeMillis() - t0) / 1000.0} s ${result.map { it.notes }}")
            pages += result
        }
        pages.forEachIndexed { i, p -> TestSupport.write(File(out, "page%02d.jpg".format(i + 1)), p.image) }

        assertEquals(11, pages.size, "1 cover + 5 spreads x 2")
        assertEquals(3, pages[0].image.channels(), "cover stays in colour (AUTO)")
        assertEquals(1, pages[4].image.channels(), "text pages are grey")
        val confs = pages.flatMap { p -> p.words.filter { it.text.length > 2 }.map { it.conf } }
        val good = confs.count { it >= 90 }.toDouble() / confs.size
        println("words>2ch=${confs.size} conf>=90: ${"%.1f".format(100 * good)} %  (Python prototype: 95.8 %)")
        assertTrue(good >= 0.94, "OCR quality ${100 * good} %")
        pages.forEach { it.close() }
    }
}
