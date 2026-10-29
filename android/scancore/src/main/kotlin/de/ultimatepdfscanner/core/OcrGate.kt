package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Decides before the OCR whether a page can contain text at all. Tesseract's layout analysis
 * takes minutes on textures: a photo of a bare wooden table took 250 s and gave 2130 junk words,
 * a page full of table grain 110 s (user's series). Such inputs are much darker than a text page
 * after flattening (ink share 0.23-0.24 vs. at most 0.15 on all 129 text pages), their marks vary
 * wildly in height (spread 0.9-1.0 vs. typically 0.3-0.5) and they show no line rhythm
 * (periodicity 0.00-0.04). Only all three together count as texture, so dark covers, figures
 * and sparse pages are still read.
 */
object OcrGate {
    const val MAX_INK = 0.2
    const val MIN_SPREAD = 0.8 // interquartile range of the mark heights / median height
    const val MAX_PERIODICITY = 0.1
    const val WORK_SIZE = 1600

    class Verdict(val text: Boolean, val inkShare: Double, val heightSpread: Double, val periodicity: Double)

    fun assess(gray: Mat): Verdict = matScope {
        val (small, _) = downscale(gray, WORK_SIZE)
        val ink = mat()
        Imgproc.threshold(small, ink, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val share = Core.countNonZero(ink).toDouble() / ink.total()
        if (share <= MAX_INK) return@matScope Verdict(true, share, 0.0, 0.0)
        val stats = mat()
        val n = Imgproc.connectedComponentsWithStats(ink, mat(), stats, mat(), 8, CvType.CV_32S)
        val heights = ArrayList<Int>()
        for (i in 1 until n) {
            val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            if (h in 4..100 && w in 2..3 * h) heights += h
        }
        heights.sort()
        val spread = if (heights.size < 20) 9.0 else
            (heights[heights.size * 3 / 4] - heights[heights.size / 4]).toDouble() / maxOf(1, heights[heights.size / 2])
        val f = mat().also { ink.convertTo(it, CvType.CV_32F) }
        val rows = mat(); val cols = mat()
        Core.reduce(f, rows, 1, Core.REDUCE_SUM)
        Core.reduce(f, cols, 0, Core.REDUCE_SUM)
        val period = maxOf(Orientation.periodicity(floats(rows).map { it.toDouble() }.toDoubleArray()),
            Orientation.periodicity(floats(cols).map { it.toDouble() }.toDoubleArray()))
        Verdict(!(spread >= MIN_SPREAD && period < MAX_PERIODICITY), share, spread, period)
    }
}
