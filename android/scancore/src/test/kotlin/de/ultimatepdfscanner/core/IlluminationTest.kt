package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/** Colour pages: shadow and yellowing are removed by the illumination field, colours stay true. */
class IlluminationTest {
    init { TestSupport.init() }

    /** White page with text, a red and a blue box, then yellowed paper and a gutter shadow over the left 40 %. */
    private fun photo(): Pair<Mat, Mat> {
        val w = 1600; val h = 2200
        val page = Mat(h, w, CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        for (i in 0 until 40) Imgproc.putText(page, "Zeile $i mit etwas Text und Zahlen 12345", Point(120.0, 120.0 + i * 48), Imgproc.FONT_HERSHEY_SIMPLEX, 1.1, Scalar(20.0, 20.0, 20.0), 2)
        Imgproc.rectangle(page, Rect(150, 600, 600, 500), Scalar(40.0, 40.0, 210.0), -1) // red (BGR)
        Imgproc.rectangle(page, Rect(850, 1300, 600, 500), Scalar(200.0, 90.0, 30.0), -1) // blue
        val f = Mat(); page.convertTo(f, CvType.CV_32FC3)
        val row = FloatArray(w * 3)
        for (r in 0 until h) {
            f.get(r, 0, row)
            for (c in 0 until w) {
                val light = if (c < 0.4 * w) 0.55 + 0.45 * c / (0.4 * w) else 1.0 // gutter shadow
                val tint = doubleArrayOf(0.78, 0.93, 1.0) // yellowed paper (B reduced)
                for (k in 0 until 3) row[c * 3 + k] = (row[c * 3 + k] * light * tint[k] * 0.92).toFloat()
            }
            f.put(r, 0, row)
        }
        val out = Mat(); f.convertTo(out, CvType.CV_8UC3)
        return out to page
    }

    private fun meanBgr(m: Mat, r: Rect) = Core.mean(m.submat(r)).`val`.take(3)

    @Test
    fun shadowAndYellowRemovedColoursKept() {
        val (img, truth) = photo()
        val out = Enhance.colorFlatten(img)
        TestSupport.write(java.io.File("build/illum/out.jpg"), out)
        // paper in the shadow and in the light: both white and neutral
        for (r in listOf(Rect(40, 1950, 60, 200), Rect(1500, 1950, 60, 200))) {
            val (b, g, red) = meanBgr(out, r)
            assertTrue(minOf(b, g, red) > 235, "paper not white at $r: $b $g $red")
            assertTrue(maxOf(b, g, red) - minOf(b, g, red) < 10, "paper not neutral at $r: $b $g $red")
        }
        // the red box is equally red in its shadowed and its lit part, and close to the truth
        val redShadow = meanBgr(out, Rect(170, 700, 80, 300)); val redLight = meanBgr(out, Rect(650, 700, 80, 300))
        val redTrue = meanBgr(truth, Rect(650, 700, 80, 300))
        for (k in 0 until 3) {
            assertTrue(abs(redShadow[k] - redLight[k]) < 20, "red differs between shadow and light: $redShadow vs $redLight")
            assertTrue(abs(redLight[k] - redTrue[k]) < 35, "red off: $redLight vs $redTrue")
        }
        val blue = meanBgr(out, Rect(950, 1400, 400, 300)); val blueTrue = meanBgr(truth, Rect(950, 1400, 400, 300))
        for (k in 0 until 3) assertTrue(abs(blue[k] - blueTrue[k]) < 35, "blue off: $blue vs $blueTrue")
        // the old global correction left the shadow: the paper on the left stays clearly darker
        val old = Enhance.colorPage(img)
        assertTrue(meanBgr(old, Rect(40, 1950, 60, 200)).min() < 200)
    }

    @Test
    fun fingerFilledWithPaperNotStreaks() {
        val (img, _) = photo()
        val h = img.rows(); val w = img.cols()
        val finger = Mat.zeros(h, w, CvType.CV_8U)
        Imgproc.ellipse(finger, Point(w - 60.0, 400.0), Size(260.0, 80.0), 0.0, 0.0, 360.0, Scalar(255.0), -1)
        img.setTo(Scalar(120.0, 150.0, 215.0), finger) // skin (BGR)
        val (out, share) = Fingers.removeFingers(img)
        assertTrue(share > 0.005, "finger not found ($share)")
        // inside the finger: smooth paper of the local brightness, no skin colour left
        val inside = Rect(w - 250, 370, 180, 60)
        val sd = org.opencv.core.MatOfDouble(); val mean = org.opencv.core.MatOfDouble()
        Core.meanStdDev(out.submat(inside), mean, sd)
        val m = mean.toArray(); val s = sd.toArray()
        assertTrue(s.max() < 12, "patch not smooth: sd ${s.toList()}")
        val paper = meanBgr(img, Rect(w - 250, 100, 180, 10))
        for (k in 0 until 3) assertTrue(abs(m[k] - paper[k]) < 25, "patch ${m.toList()} vs paper $paper")
    }
}
