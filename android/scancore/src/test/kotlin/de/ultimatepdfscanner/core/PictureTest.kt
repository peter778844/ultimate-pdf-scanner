package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PictureTest {
    init { TestSupport.init() }

    /** A smooth "photo": diagonal grey gradient with a soft disc. */
    private fun photo(n: Int = 900): Mat {
        val m = Mat(n, n, CvType.CV_8UC3)
        val row = ByteArray(n * 3)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val d = Math.hypot(x - n * 0.6, y - n * 0.4) / n
                val v = (40 + 160.0 * (x + y) / (2 * n) + if (d < 0.2) 40 else 0).toInt().coerceIn(0, 255)
                row[3 * x] = v.toByte(); row[3 * x + 1] = v.toByte(); row[3 * x + 2] = v.toByte()
            }
            m.put(y, 0, row)
        }
        return m
    }

    /** The same photo printed with a 45-degree dot screen of [period] px (amplitude-modulated). */
    private fun halftone(src: Mat, period: Double): Mat {
        val n = src.rows()
        val out = Mat(n, n, CvType.CV_8UC3)
        val g = ByteArray(n * n * 3).also { src.get(0, 0, it) }
        val row = ByteArray(n * 3)
        val c = cos(PI / 4); val s = sin(PI / 4)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val tone = (g[3 * (y * n + x)].toInt() and 0xff) / 255.0
                val u = (x * c + y * s) * 2 * PI / period; val v = (-x * s + y * c) * 2 * PI / period
                val dot = (cos(u) + cos(v)) / 4 + 0.5 // 0..1
                val ink = if (dot > tone) 20 else 245
                row[3 * x] = ink.toByte(); row[3 * x + 1] = ink.toByte(); row[3 * x + 2] = ink.toByte()
            }
            out.put(y, 0, row)
        }
        return out
    }

    @Test
    fun screenIsFoundAndRemoved() {
        val printed = halftone(photo(), 6.0)
        val period = assertNotNull(Picture.screenPeriod(printed))
        assertTrue(abs(period - 6.0 / Math.sqrt(2.0)) < 1.0 || abs(period - 6.0) < 1.0, "period $period")
        val clean = Picture.descreen(printed, period)
        assertNull(Picture.screenPeriod(clean), "screen still visible")
        // the picture itself survives: close to the original tones on average
        val diff = Mat().also { Core.absdiff(Enhance.toGray(clean), Enhance.toGray(photo()), it) }
        assertTrue(Core.mean(diff).`val`[0] < 25, "mean difference ${Core.mean(diff).`val`[0]}")
    }

    @Test
    fun smoothPhotoHasNoScreen() {
        assertNull(Picture.screenPeriod(photo()))
    }

    @Test
    fun bluishPaperIsNeutralised() {
        val page = Mat(400, 300, CvType.CV_8UC3, Scalar(235.0, 222.0, 215.0)) // BGR: blue cast
        Imgproc.rectangle(page, org.opencv.core.Point(60.0, 80.0), org.opencv.core.Point(240.0, 300.0), Scalar(40.0, 90.0, 160.0), -1)
        val out = Picture.whiteBalance(page)
        val paper = out.get(10, 10)
        assertTrue(paper.max() - paper.min() < 6, "paper ${paper.toList()}")
    }
}
