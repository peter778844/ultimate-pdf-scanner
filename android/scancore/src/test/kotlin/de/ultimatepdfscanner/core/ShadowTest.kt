package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Phone shadow: the phone held above the page casts a large dark blob with a soft edge. After
 * enhancement the paper must be uniformly white and the text equally dark inside and outside
 * the shadow, without a grey band along the shadow edge.
 */
class ShadowTest {
    init { TestSupport.init() }

    private fun shadowMask(w: Int, h: Int, edgeSigma: Double): Mat {
        val m = Mat(h, w, CvType.CV_32F, Scalar(1.0))
        // phone + hand silhouette: big ellipse entering from the bottom right
        Imgproc.ellipse(m, Point(w * 0.85, h * 0.95), Size(w * 0.55, h * 0.45), -20.0, 0.0, 360.0, Scalar(0.45), -1)
        Imgproc.GaussianBlur(m, m, Size(0.0, 0.0), edgeSigma)
        return m
    }

    private fun measure(edgeSigma: Double): Triple<Double, Double, Double> {
        val clean = Enhance.toGray(TestSupport.makePage(fill = true))
        val shadow = shadowMask(clean.cols(), clean.rows(), edgeSigma)
        val f = Mat().also { clean.convertTo(it, CvType.CV_32F) }
        Core.multiply(f, shadow, f)
        val shaded = Mat().also { f.convertTo(it, CvType.CV_8U) }
        val out = Enhance.enhance(shaded, Mode.GRAY)

        val inShadow = Mat().also { Core.compare(shadow, Scalar(0.5), it, Core.CMP_LT) }
        val lit = Mat().also { Core.compare(shadow, Scalar(0.98), it, Core.CMP_GT) }
        val edge = Mat().also { e -> Core.inRange(shadow, Scalar(0.55), Scalar(0.9), e) }
        val ink = Mat().also { Core.compare(clean, Scalar(100.0), it, Core.CMP_LT) }
        val paper = Mat().also { Core.compare(clean, Scalar(200.0), it, Core.CMP_GT) }
        fun and(a: Mat, b: Mat) = Mat().also { Core.bitwise_and(a, b, it) }
        fun meanOf(m: Mat) = Core.mean(out, m).`val`[0]

        val paperDiff = kotlin.math.abs(meanOf(and(paper, inShadow)) - meanOf(and(paper, lit)))
        val inkDiff = kotlin.math.abs(meanOf(and(ink, inShadow)) - meanOf(and(ink, lit)))
        val edgePaper = meanOf(and(paper, edge))
        println("edge sigma $edgeSigma: paper diff ${"%.1f".format(paperDiff)}, ink diff ${"%.1f".format(inkDiff)}, paper at shadow edge ${"%.1f".format(edgePaper)}")
        return Triple(paperDiff, inkDiff, edgePaper)
    }

    @Test
    fun removesPhoneShadowWithoutFadingText() {
        for (sigma in doubleArrayOf(40.0, 15.0, 6.0)) { // soft to fairly hard shadow edges
            val (paperDiff, inkDiff, edgePaper) = measure(sigma)
            assertTrue(paperDiff < 6, "paper differs by $paperDiff (sigma $sigma)")
            assertTrue(inkDiff < 25, "text darkness differs by $inkDiff (sigma $sigma)")
            assertTrue(edgePaper > 240, "grey band at the shadow edge: $edgePaper (sigma $sigma)")
        }
    }
}
