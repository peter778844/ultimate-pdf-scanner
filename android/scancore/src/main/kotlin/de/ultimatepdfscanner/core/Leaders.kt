package de.ultimatepdfscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Dot leaders ("Gefaltete Dipole . . . . . . . 539") in tables of contents. Tesseract reads them
 * as junk words ("isi tinh si Von ais vie") that end up in the search text and lower the page's
 * confidence. They are found as long, regular rows of small dots on one baseline and painted
 * over; such pages are then read as one block, so each entry stays with its page number
 * (with the dots gone, the automatic layout analysis would make the numbers a column of their own).
 */
object Leaders {
    const val DOT_SIZE = 0.45 // a dot is at most this share of the letter height wide and high
    const val MAX_GAP = 1.6 // next dot of a row within this many letter heights ...
    const val MAX_DRIFT = 0.25 // ... and on the same baseline
    const val MIN_DOTS = 5 // a row of fewer dots may be an ellipsis or decimals
    const val REGULARITY = 0.35 // spacing spread (std / mean) of a leader row
    const val MIN_ROWS = 5 // this many leader rows: a table of contents

    class Result(val image: Mat, val rows: Int)

    /** First index with xs[i] >= x (xs ascending). */
    private fun lowerBound(xs: DoubleArray, x: Double): Int {
        var lo = 0; var hi = xs.size
        while (lo < hi) { val mid = (lo + hi) / 2; if (xs[mid] < x) lo = mid + 1 else hi = mid }
        return lo
    }

    /** [gray] with its leader dots painted in the paper tone, and the number of leader rows. */
    fun remove(gray: Mat, letterHeight: Double): Result = matScope {
        val ink = mat()
        Imgproc.threshold(gray, ink, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val labels = mat(); val stats = mat(); val centroids = mat()
        val n = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
        val maxDot = DOT_SIZE * letterHeight
        val cx = DoubleArray(n); val cy = DoubleArray(n)
        val dots = ArrayList<Int>()
        for (i in 1 until n) {
            val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]; val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]
            if (w > maxDot || h > maxDot || w / h < 0.4 || w / h > 2.5) continue
            cx[i] = centroids.get(i, 0)[0]; cy[i] = centroids.get(i, 1)[0]
            dots += i
        }
        dots.sortBy { cx[it] }
        val xsSorted = DoubleArray(dots.size) { cx[dots[it]] }
        // rows: from each free dot, keep taking the nearest free dot to the right on the same baseline
        // (candidates via the x-sorted list: only dots within MAX_GAP are looked at)
        val free = BooleanArray(n)
        for (d in dots) free[d] = true
        val leaders = ArrayList<Int>()
        var rows = 0
        for (start in dots) {
            if (!free[start]) continue
            free[start] = false
            val row = arrayListOf(start)
            while (true) {
                val last = row.last()
                var i = lowerBound(xsSorted, cx[last])
                var next = -1
                while (i < dots.size && xsSorted[i] - cx[last] <= MAX_GAP * letterHeight) {
                    val j = dots[i]
                    if (free[j] && cx[j] > cx[last] && abs(cy[j] - cy[last]) <= MAX_DRIFT * letterHeight) { next = j; break }
                    i++
                }
                if (next < 0) break
                row += next; free[next] = false
            }
            if (row.size < MIN_DOTS) continue
            val gaps = (1 until row.size).map { cx[row[it]] - cx[row[it - 1]] }
            val mean = gaps.average()
            val sd = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
            if (sd > REGULARITY * mean) continue
            leaders += row; rows++
        }
        val out = gray.clone()
        if (leaders.isNotEmpty()) {
            val paper = histPercentile(histogram(gray), 90.0)
            // one pass over the labels instead of one full-image comparison per dot
            val isLeader = BooleanArray(n).also { for (k in leaders) it[k] = true }
            val lab = IntArray(labels.total().toInt()).also { labels.get(0, 0, it) }
            val px = ByteArray(lab.size) { if (isLeader[lab[it]]) 255.toByte() else 0 }
            val mask = track(Mat(gray.size(), CvType.CV_8U)).also { it.put(0, 0, px) }
            Imgproc.dilate(mask, mask, kernel(3)) // the grey halo around each dot too
            out.setTo(Scalar(paper), mask)
        }
        Result(out, rows)
    }
}
