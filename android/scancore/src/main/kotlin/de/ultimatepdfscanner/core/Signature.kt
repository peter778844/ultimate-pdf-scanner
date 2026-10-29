package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/** Places a hand-drawn signature (transparent PNG) on a page like ink: only the strokes cover the paper. */
object Signature {
    /**
     * Alpha-blend [signature] (BGRA) into [page] (BGR or grey) in place, scaled into the
     * rectangle [rel] = x0, y0, x1, y1 relative to the page (0..1).
     */
    fun blend(page: Mat, signature: Mat, rel: List<Double>) = matScope {
        val x0 = (rel[0] * page.cols()).roundToInt().coerceIn(0, page.cols() - 1)
        val y0 = (rel[1] * page.rows()).roundToInt().coerceIn(0, page.rows() - 1)
        val x1 = (rel[2] * page.cols()).roundToInt().coerceIn(x0 + 1, page.cols())
        val y1 = (rel[3] * page.rows()).roundToInt().coerceIn(y0 + 1, page.rows())
        val size = Size((x1 - x0).toDouble(), (y1 - y0).toDouble())
        val sig = mat()
        Imgproc.resize(signature, sig, size, 0.0, 0.0, Imgproc.INTER_AREA)
        val ch = ArrayList<Mat>()
        Core.split(sig, ch)
        ch.forEach { track(it) }
        val alpha = mat()
        ch[3].convertTo(alpha, CvType.CV_32F, 1.0 / 255)
        val ink = mat()
        if (page.channels() == 1) Imgproc.cvtColor(sig, ink, Imgproc.COLOR_BGRA2GRAY) else Imgproc.cvtColor(sig, ink, Imgproc.COLOR_BGRA2BGR)
        val roi = track(page.submat(Rect(x0, y0, x1 - x0, y1 - y0)))
        val roiF = mat(); roi.convertTo(roiF, CvType.CV_32F)
        val inkF = mat(); ink.convertTo(inkF, CvType.CV_32F)
        val a = mat()
        if (page.channels() == 1) alpha.copyTo(a) else Core.merge(listOf(alpha, alpha, alpha), a)
        // all channels 1.0 (Mat.ones would only set the first channel of a multi-channel Mat)
        val inv = mat(); Core.subtract(track(Mat(a.size(), a.type(), org.opencv.core.Scalar.all(1.0))), a, inv)
        Core.multiply(roiF, inv, roiF)
        Core.multiply(inkF, a, inkF)
        Core.add(roiF, inkF, roiF)
        roiF.convertTo(roi, page.type())
    }
}
