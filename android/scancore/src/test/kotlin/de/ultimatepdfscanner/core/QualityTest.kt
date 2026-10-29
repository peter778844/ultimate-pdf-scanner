package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QualityTest {
    init { TestSupport.init() }

    private fun issues(photo: Mat): List<Issue> {
        val pages = Pipeline.processPhoto(photo, PipelineConfig(mode = Mode.GRAY, ocr = false, orient = false), null)
        return pages.flatMap { it.issues }.also { pages.forEach { p -> p.close() } }
    }

    @Test
    fun goodScanHasNoIssues() {
        val (photo, _) = TestSupport.makePhoto(TestSupport.makePage())
        assertEquals(emptyList(), issues(photo))
    }

    @Test
    fun flagsBlurDarknessAndMissingPage() {
        val (photo, _) = TestSupport.makePhoto(TestSupport.makePage())
        val blurred = Mat().also { Imgproc.GaussianBlur(photo, it, Size(0.0, 0.0), 6.0) }
        issues(blurred).let { assertTrue(Issue.BLURRY in it, "blurred: $it") }

        val dark = Mat().also { Core.multiply(photo, org.opencv.core.Scalar(0.3, 0.3, 0.3), it) }
        issues(dark).let { assertTrue(Issue.DARK in it, "dark: $it") }

        val floor = Mat(1200, 900, org.opencv.core.CvType.CV_8UC3)
        Core.randu(floor, 60.0, 70.0)
        issues(floor).let { assertTrue(Issue.NO_PAGE in it, "floor: $it") }
    }

    @Test
    fun flagsPagesWithoutText() {
        val page = org.opencv.core.Mat(400, 300, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar(240.0, 240.0, 240.0))
        val w = { t: String -> Word(t, 90f, 0, 0, 10, 10, 0, 0, 0) }
        assertEquals(listOf(Issue.NO_TEXT), Quality.assess(true, 1.0, page, listOf(w("|"), w("ab"))))
        assertEquals(emptyList(), Quality.assess(true, 1.0, page, listOf(w("Das"), w("ist"), w("Text"))))
        assertEquals(emptyList(), Quality.assess(true, 1.0, page, null)) // no OCR: not judged
    }

    @Test
    fun confidenceIsWeightedByWordLength() {
        val w = { t: String, c: Float -> Word(t, c, 0, 0, 1, 1, 0, 0, 0) }
        assertEquals(77.0, Quality.meanConfidence(listOf(w("abcd", 90f), w("a", 40f), w("bcde", 80f), w("x", 50f))), 1e-9)
    }
}
