package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression (phone, 2026-10-23): a two-page spread whose gutter shadow is brownish lost one
 * page, because the skin/wood filter cut the spread in two at the fold and only the larger half
 * was kept as "the page".
 */
class SpreadPhotoTest {
    init { TestSupport.init() }

    /** Two pages side by side with a warm brown gutter shadow (Cr ~150, i.e. inside the skin range). */
    private fun brownGutterSpread(): Mat {
        // the nearer page looks larger in a photo: fold at 53 % of the width
        val left = Mat().also { Imgproc.resize(TestSupport.makePage(fill = true), it, Size(1400.0, 1754.0)) }
        val right = TestSupport.makePage(fill = true)
        val spread = Mat().also { Core.hconcat(listOf(left, right), it) }
        val small = Mat().also { Imgproc.resize(spread, it, Size(1800.0, 1273.0), 0.0, 0.0, Imgproc.INTER_AREA) }
        val f = Mat().also { small.convertTo(it, CvType.CV_32FC3) }
        val brown = doubleArrayOf(70.0, 105.0, 150.0) // BGR
        val mid = small.cols() * 1400 / 2640
        val row = FloatArray(small.cols() * 3)
        for (r in 0 until f.rows()) {
            f.get(r, 0, row)
            for (c in 0 until small.cols()) {
                val a = Math.exp(-Math.pow((c - mid) / 65.0, 2.0)) // 1 at the fold, fading out
                for (ch in 0 until 3) row[c * 3 + ch] = (row[c * 3 + ch] * (1 - a) + brown[ch] * a).toFloat()
            }
            f.put(r, 0, row)
        }
        return Mat().also { f.convertTo(it, CvType.CV_8UC3) }
    }

    @Test
    fun brownGutterKeepsBothPages() {
        val spread = brownGutterSpread()
        val (photo, truth) = TestSupport.makePhoto(spread)
        val det = Detect.detectDocument(photo)
        val quad = assertNotNull(det.quad)
        val area = { q: Quad -> Imgproc.contourArea(org.opencv.core.MatOfPoint2f(*q)) }
        val share = area(quad) / area(truth)
        assertTrue(share in 0.9..1.1, "detected outline covers ${"%.2f".format(share)} of the spread")

        val pages = Pipeline.processPhoto(photo, PipelineConfig(mode = Mode.GRAY, ocr = false, orient = false), null)
        assertEquals(2, pages.size, "a spread must give two pages")
        pages.forEach { it.close() }
    }

    /** The two phone photos of the bug report (book turned sideways on a wooden table). */
    @Test
    fun realPhoneSpreadsGiveTwoPages() {
        val dir = java.io.File("../../testdata/phone_spread")
        val photos = dir.listFiles { f -> f.extension == "jpg" }.orEmpty()
        if (photos.isEmpty()) return
        for (f in photos) {
            val img = TestSupport.load(f)
            val quad = assertNotNull(Detect.detectDocument(img).quad, f.name)
            val top = quad.minOf { it.y } / img.rows()
            val bottom = quad.maxOf { it.y } / img.rows()
            assertTrue(bottom - top > 0.7, "${f.name}: outline covers only ${"%.2f".format(top)}..${"%.2f".format(bottom)} of the height")
            val pages = Pipeline.processPhoto(img, PipelineConfig(mode = Mode.GRAY, ocr = false, orient = false), null, 0.54)
            assertEquals(2, pages.size, f.name)
            pages.forEach { it.close() }
        }
    }
}
