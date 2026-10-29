package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Receipt and whiteboard detection and their enhancement. */
class ProfileTest {
    init { TestSupport.init() }

    /** Narrow till receipt with faint grey thermal print and slight shading. */
    private fun receipt(): Pair<Mat, Mat> {
        val w = 600; val h = 1800
        val img = Mat(h, w, CvType.CV_8UC3, Scalar(236.0, 238.0, 240.0))
        val textMask = Mat.zeros(h, w, CvType.CV_8U)
        for (i in 0 until 30) {
            val y = 80 + i * 55.0
            Imgproc.putText(img, "ARTIKEL ${i + 1}   ${(i * 37) % 100},${i % 10}9", Point(40.0, y), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(150.0, 150.0, 150.0), 2)
            Imgproc.putText(textMask, "ARTIKEL ${i + 1}   ${(i * 37) % 100},${i % 10}9", Point(40.0, y), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(255.0), 2)
        }
        return img to textMask
    }

    @Test
    fun receiptGetsDeepBlackText() {
        val (img, text) = receipt()
        assertEquals(Enhance.Profile.RECEIPT, Enhance.profile(img))
        val out = Enhance.enhance(img, Mode.COLOR)
        assertEquals(1, out.channels(), "receipts come out in black and white tones")
        val core = Mat().also { Imgproc.erode(text, it, ones(2)) } // stroke centres
        val inkGrey = Core.mean(out, core).`val`[0]
        val paper = Mat().also { Core.bitwise_not(Mat().also { m -> Imgproc.dilate(text, m, ones(9)) }, it) }
        val paperGrey = Core.mean(out, paper).`val`[0]
        println("receipt: ink $inkGrey, paper $paperGrey (input ink 150)")
        assertTrue(inkGrey < 60, "faint print should become deep black: $inkGrey")
        assertTrue(paperGrey > 245, "paper should be white: $paperGrey")
    }

    /** Landscape board, slightly grey and unevenly lit, with blue/red/black marker strokes. */
    private fun whiteboard(): Mat {
        val w = 1600; val h = 1000
        val img = Mat(h, w, CvType.CV_8UC3, Scalar(205.0, 210.0, 212.0))
        Imgproc.line(img, Point(100.0, 200.0), Point(1400.0, 230.0), Scalar(200.0, 80.0, 30.0), 10) // blue
        Imgproc.putText(img, "Projektplan", Point(150.0, 450.0), Imgproc.FONT_HERSHEY_SIMPLEX, 4.0, Scalar(40.0, 40.0, 210.0), 12) // red
        Imgproc.circle(img, Point(1200.0, 650.0), 150, Scalar(60.0, 150.0, 40.0), 10) // green
        Imgproc.putText(img, "Q4", Point(200.0, 800.0), Imgproc.FONT_HERSHEY_SIMPLEX, 4.0, Scalar(30.0, 30.0, 30.0), 12) // black
        return img
    }

    @Test
    fun whiteboardGetsWhiteBackgroundAndVividColours() {
        val img = whiteboard()
        assertEquals(Enhance.Profile.WHITEBOARD, Enhance.profile(img))
        val out = Enhance.enhance(img, Mode.COLOR)
        val bg = Core.mean(Mat(out, Rect(700, 850, 300, 100))).`val`
        assertTrue(bg.take(3).all { it > 245 }, "background should be white: ${bg.toList()}")
        val hsvIn = Mat().also { Imgproc.cvtColor(img, it, Imgproc.COLOR_BGR2HSV) }
        val hsvOut = Mat().also { Imgproc.cvtColor(out, it, Imgproc.COLOR_BGR2HSV) }
        val stroke = Rect(400, 205, 400, 10) // inside the blue line
        val satIn = Core.mean(Mat(hsvIn, stroke)).`val`[1]
        val satOut = Core.mean(Mat(hsvOut, stroke)).`val`[1]
        println("whiteboard: blue saturation $satIn -> $satOut, bg ${bg.toList()}")
        assertTrue(satOut >= satIn, "marker colours should stay at least as vivid")
    }

    @Test
    fun normalPagesStayPaper() {
        assertEquals(Enhance.Profile.PAPER, Enhance.profile(TestSupport.makePage()))
        assertEquals(Enhance.Profile.PAPER, Enhance.profile(TestSupport.rotate(TestSupport.makePage(fill = true), 90)))
    }
}
