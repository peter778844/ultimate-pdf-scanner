package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import org.opencv.core.Rect
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BordersTest {
    init { TestSupport.init() }

    private fun std(m: Mat): Double {
        val mean = MatOfDouble(); val sd = MatOfDouble()
        Core.meanStdDev(m, mean, sd)
        return sd.toArray().max()
    }

    @Test
    fun cleanPageStaysUntouched() {
        val page = TestSupport.makePage(fill = true) // includes a shadow towards one edge, but no trough
        val (out, share) = Borders.clean(page)
        assertEquals(0.0, share)
        val diff = Mat().also { Core.absdiff(page, out, it) }
        assertEquals(0, Core.countNonZero(Enhance.toGray(diff)))
    }

    @Test
    fun neighbourStripBeyondTheGutterIsRemoved() {
        val own = TestSupport.makePage(fill = true)
        val h = own.rows(); val strip = 120; val gutter = 90
        // [neighbour's text | gutter shadow | own page], the gutter darkens the paper by half at its centre
        val page = Mat(h, strip + gutter + own.cols(), own.type())
        own.submat(Rect(600, 0, strip, h)).copyTo(page.submat(Rect(0, 0, strip, h)))
        own.submat(Rect(1100, 0, gutter, h)).copyTo(page.submat(Rect(strip, 0, gutter, h))) // blank margin
        own.copyTo(page.submat(Rect(strip + gutter, 0, own.cols(), h)))
        val f = Mat(); page.convertTo(f, CvType.CV_32FC3)
        val centre = strip + gutter / 2.0
        for (x in 0 until page.cols()) {
            val k = 1 - 0.5 * exp(-((x - centre) / 25.0).let { it * it })
            Core.multiply(f.colRange(x, x + 1), org.opencv.core.Scalar(k, k, k), f.colRange(x, x + 1))
        }
        f.convertTo(page, CvType.CV_8UC3)

        val (out, share) = Borders.clean(page, Outline.Side.LEFT)
        assertTrue(share > 0.1, "painted $share")
        // the neighbour's text is gone (uniform paper), the own text block is untouched
        assertTrue(std(out.submat(Rect(0, 100, strip - 10, h - 200))) < 3.0, "strip std ${std(out.submat(Rect(0, 100, strip - 10, h - 200)))}")
        val own0 = Rect(strip + gutter + 100, 0, own.cols() - 100, h)
        val diff = Mat().also { Core.absdiff(page.submat(own0), out.submat(own0), it) }
        assertEquals(0, Core.countNonZero(Enhance.toGray(diff)))
    }

    /** The same strip with text in the shade, but not beyond a fold (e.g. a running footer): it is kept. */
    @Test
    fun textStripAwayFromTheFoldIsKept() {
        val own = TestSupport.makePage(fill = true)
        val h = own.rows(); val strip = 120; val gutter = 90
        // [neighbour's text | gutter shadow | own page], the gutter darkens the paper by half at its centre
        val page = Mat(h, strip + gutter + own.cols(), own.type())
        own.submat(Rect(600, 0, strip, h)).copyTo(page.submat(Rect(0, 0, strip, h)))
        own.submat(Rect(1100, 0, gutter, h)).copyTo(page.submat(Rect(strip, 0, gutter, h))) // blank margin
        own.copyTo(page.submat(Rect(strip + gutter, 0, own.cols(), h)))
        val f = Mat(); page.convertTo(f, CvType.CV_32FC3)
        val centre = strip + gutter / 2.0
        for (x in 0 until page.cols()) {
            val k = 1 - 0.5 * exp(-((x - centre) / 25.0).let { it * it })
            Core.multiply(f.colRange(x, x + 1), org.opencv.core.Scalar(k, k, k), f.colRange(x, x + 1))
        }
        f.convertTo(page, CvType.CV_8UC3)

        val (out, _) = Borders.clean(page)
        // the letters survive (blank shaded paper there may be lightened)
        val stripRect = Rect(0, 0, strip, h)
        fun ink(m: Mat) = Core.countNonZero(Mat().also { Imgproc.threshold(Enhance.toGray(m.submat(stripRect)), it, 90.0, 255.0, Imgproc.THRESH_BINARY_INV) })
        assertTrue(ink(out) >= 0.95 * ink(page), "text strip was painted: ink ${ink(out)} of ${ink(page)}")
    }
}
