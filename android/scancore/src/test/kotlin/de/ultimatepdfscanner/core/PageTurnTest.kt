package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PageTurnTest {
    init { TestSupport.init() }

    @Test
    fun shootsOnlyAfterTurnAndSettle() {
        val pt = PageTurn(steadyMs = 500)
        var t = 0L
        fun feed(m: Double, page: Boolean = true) = pt.update(m, page, t).also { t += 100 }
        // first page: steady -> shoot after 500 ms
        assertFalse(feed(1.0)); repeat(4) { assertFalse(feed(1.0)) }
        assertEquals(0.8f, pt.progress(400), 1e-3f) // countdown for the overlay
        assertTrue(feed(1.0))
        pt.shot()
        // still steady after the shot: no second picture of the same page
        repeat(20) { assertFalse(feed(1.0)) }
        assertEquals(PageTurn.Phase.TURN_PAGE, pt.phase)
        // page turned (motion), hand still in the picture, then steady
        assertFalse(feed(15.0)); assertEquals(PageTurn.Phase.HOLD_STILL, pt.phase)
        assertFalse(feed(5.0)); assertFalse(feed(1.0, page = false))
        repeat(5) { assertFalse(feed(1.0)) }
        assertTrue(feed(1.0))
    }

    @Test
    fun motionMeasure() {
        val pt = PageTurn()
        val a = Mat(480, 640, CvType.CV_8UC3, Scalar(200.0, 200.0, 200.0))
        assertEquals(0.0, pt.motion(a))
        val noise = Mat(480, 640, CvType.CV_8UC3).also { Core.randn(it, 0.0, 3.0) }
        val b = Mat().also { Core.add(a, noise, it) }
        assertTrue(pt.motion(b) < 2.5, "sensor noise is not motion")
        val c = a.clone().also { Imgproc.rectangle(it, Point(0.0, 0.0), Point(320.0, 480.0), Scalar(60.0, 60.0, 60.0), -1) }
        assertTrue(pt.motion(c) > 9.0, "half the frame covered by a hand")
    }
}
