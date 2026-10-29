package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Range
import org.opencv.imgproc.Imgproc
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Two-page spreads: find the book fold (gutter) and split into single pages. */
object Spread {
    val BAND = 0.4 to 0.6 // both pages have the same size, so the fold lies near the middle (measured 0.47-0.49)
    const val NEAR = 0.08 // both flanks of the gutter are compared within this fraction of the length
    const val MIN_DIP = 0.10 // relative darkening of the paper at the gutter (measured: covers <= 0.065, spreads 0.15-0.93; flat spreads are the weak ones)
    // Book mode expects an open book, so a weaker gutter suffices. Measured on the user's series
    // (testdata/series): receipts, single sheets, title pages <= 0.043; open books 0.063-0.90.
    const val BOOK_MIN_DIP = 0.06
    // Covers are dark or coloured: 90th percentile of the grey "paper" 120-149 vs. 186-231 for pages.
    const val MIN_PAGE_PAPER = 170.0

    /**
     * [axis] 0: fold is a horizontal line (pages stacked vertically), 1: vertical line. [sharp]: the
     * cut lies on the gutter's shadow line ([sharpenFold]), so nothing of the other page is beyond it.
     */
    data class Fold(val axis: Int, val pos: Int, val dip: Double, val sharp: Boolean = false) {
        val confident get() = dip >= MIN_DIP
    }

    /** Paper brightness without the text: dilation removes dark strokes. */
    private fun paperBrightness(gray: Mat): Mat = matScope {
        val k = max(9, odd(min(gray.rows(), gray.cols()) / 40))
        val dil = mat()
        Imgproc.dilate(gray, dil, kernel(k))
        Mat().also { Imgproc.medianBlur(dil, it, 5) }
    }

    /** cv2.GaussianBlur on an (n, 1) array with kernel height k and default sigma, BORDER_REFLECT_101. */
    internal fun smooth(profile: DoubleArray): DoubleArray {
        val n = profile.size
        val k = max(5, odd(n / 100))
        val sigma = 0.3 * ((k - 1) * 0.5 - 1) + 0.8
        val r = k / 2
        val weights = DoubleArray(k) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)) }
        val sum = weights.sum()
        fun at(i: Int): Double {
            var j = i
            if (n == 1) return profile[0]
            while (j < 0 || j >= n) j = if (j < 0) -j else 2 * (n - 1) - j
            return profile[j]
        }
        return DoubleArray(n) { i -> (0 until k).sumOf { weights[it] * at(i + it - r) } / sum }
    }

    /** Deepest valley in the central band; a step edge has one low flank and scores ~0. */
    internal fun twoSidedDip(profile: DoubleArray): Pair<Int, Double> {
        val n = profile.size
        val a = (n * BAND.first).toInt()
        val b = (n * BAND.second).toInt()
        val w = max(1, (n * NEAR).toInt())
        var best = a to 0.0
        for (pos in a until b) {
            val flank = min(maxOf(profile, pos - w, pos), maxOf(profile, pos + 1, pos + 1 + w))
            val dip = (flank - profile[pos]) / max(flank, 1e-6)
            if (dip > best.second) best = pos to dip
        }
        return best
    }

    private fun maxOf(p: DoubleArray, from: Int, to: Int): Double {
        var m = Double.NEGATIVE_INFINITY
        for (i in max(0, from) until min(p.size, to)) m = max(m, p[i])
        return m
    }

    private fun dipAt(profile: DoubleArray, pos: Int): Double {
        val n = profile.size
        val w = max(1, (n * NEAR).toInt())
        val r = max(1, n / 100)
        var lo = Double.POSITIVE_INFINITY
        for (i in max(0, pos - r) until min(n, pos + r + 1)) lo = min(lo, profile[i])
        val flank = min(maxOf(profile, pos - w, pos), maxOf(profile, pos + 1, pos + 1 + w))
        return (flank - lo) / max(flank, 1e-6)
    }

    /**
     * Mean paper brightness per line (lines run along the fold candidate), using only mask
     * pixels, restricted to the cross range [from, to).
     */
    private fun lineMeans(paper: FloatArray, inside: BooleanArray, rows: Int, cols: Int, axis: Int, from: Int, to: Int): DoubleArray {
        val n = if (axis == 0) rows else cols
        return smooth(DoubleArray(n) { line ->
            var sum = 0.0
            var count = 0
            for (c in from until to) {
                val idx = if (axis == 0) line * cols + c else c * cols + line
                if (inside[idx]) { sum += paper[idx]; count++ }
            }
            if (count > 0) sum / count else 0.0
        })
    }

    /**
     * Best fold candidate over both axes (check [Fold.confident] before splitting). A gutter
     * darkens the paper along its whole length, so the dip is re-measured in [strips]
     * parallel strips and the median is kept; printed bands only cover a part.
     */
    fun findFold(gray: Mat, mask: Mat? = null, strips: Int = 5, onlyAxis: Int? = null): Fold {
        val rows = gray.rows()
        val cols = gray.cols()
        val paperMat = paperBrightness(gray)
        val paper32 = Mat().also { paperMat.convertTo(it, CvType.CV_32F) }
        val paper = floats(paper32)
        paperMat.release(); paper32.release()
        val inside = if (mask != null) bytes(mask).let { b -> BooleanArray(b.size) { b[it].toInt() != 0 } } else BooleanArray(rows * cols) { true }

        val axes = if (onlyAxis != null) listOf(onlyAxis) else listOf(0, 1)
        val candidates = axes.map { axis ->
            val across = if (axis == 0) cols else rows
            val (pos, _) = twoSidedDip(lineMeans(paper, inside, rows, cols, axis, 0, across))
            val edges = IntArray(strips + 1) { (it.toDouble() * across / strips).toInt() }
            val dips = DoubleArray(strips) { s -> dipAt(lineMeans(paper, inside, rows, cols, axis, edges[s], edges[s + 1]), pos) }
            Fold(axis, pos, median(dips))
        }
        return sharpenFold(gray, candidates.maxBy { it.dip })
    }

    const val SHARP_DARKER = 0.8 // the gutter's shadow line is this much darker than the paper at the found position

    /**
     * The fold moved onto the narrow shadow line of the gutter if there is one nearby. The paper
     * brightness smears a thin shadow away, and the mask may leave it out; what remains is the broad
     * shading of a page that bulges towards the camera. Measured on the user's Meinke spread: the
     * cut lay 70 px inside the left page (line ends "zurück, derart" lost), the shadow line at
     * median grey 72 against 138. Lines along the fold use the median of the raw grey (10-90 % of
     * the length), so most text does not count; a line must be [SHARP_DARKER] darker and at least
     * 1/300 of the length wide, so a printed rule is not taken for the gutter, and [SHARP_DARKER]
     * darker than every other line of the window too: a bold text line is dark in the median as
     * well, but it has neighbours just as dark (measured: a single sheet forced into two pages was
     * cut through a line of text, median 138 against 140 for the next line; the Meinke gutter 72
     * against 136).
     */
    internal fun sharpenFold(gray: Mat, fold: Fold): Fold {
        val rows = gray.rows(); val cols = gray.cols()
        val n = if (fold.axis == 0) rows else cols
        val len = if (fold.axis == 0) cols else rows
        val w = max(1, (n * NEAR).toInt())
        val from = max(0, fold.pos - w); val to = min(n, fold.pos + w + 1)
        val px = bytes(gray)
        val a = (len * 0.1).toInt(); val b = (len * 0.9).toInt()
        val values = IntArray(b - a)
        val med = DoubleArray(to - from) { k ->
            val line = from + k
            for (i in a until b) values[i - a] = px[if (fold.axis == 0) line * cols + i else i * cols + line].toInt() and 0xff
            values.sort()
            values[values.size / 2].toDouble()
        }
        val here = med[fold.pos - from]
        val k = med.indices.minBy { med[it] }
        if (med[k] >= SHARP_DARKER * here) return fold
        val half = (med[k] + here) / 2
        var lo = k; while (lo > 0 && med[lo - 1] < half) lo--
        var hi = k; while (hi < med.lastIndex && med[hi + 1] < half) hi++
        if (hi - lo + 1 < max(4, n / 300)) return fold
        val margin = hi - lo + 1
        val other = med.indices.filter { it < lo - margin || it > hi + margin }.minOfOrNull { med[it] } ?: return fold
        if (med[k] >= SHARP_DARKER * other) return fold
        return fold.copy(pos = from + (lo + hi) / 2, sharp = true)
    }

    /**
     * Fold for a photo the user declared a two-page spread: the fold runs across the longer side
     * (pages side by side -> vertical fold), positioned at the darkest gutter line even if the
     * shading is too weak for automatic detection (pages photographed completely flat).
     */
    fun forcedFold(gray: Mat, mask: Mat? = null): Fold =
        findFold(gray, mask, onlyAxis = if (gray.rows() >= gray.cols()) 0 else 1)

    /** Bright paper (not a dark or coloured book cover, whose shading can look like a gutter). */
    fun looksLikePages(gray: Mat, mask: Mat?): Boolean = histPercentile(histogram(gray, mask), 90.0) >= MIN_PAGE_PAPER

    /**
     * Cut at the fold. Returns the two halves (image, mask), rotated so that the fold is
     * horizontal; the final reading orientation is fixed later.
     */
    fun splitSpread(image: Mat, mask: Mat?, fold: Fold): List<Pair<Mat, Mat?>> = matScope {
        var img = image
        var msk = mask
        if (fold.axis == 1) {
            img = mat().also { Core.rotate(image, it, Core.ROTATE_90_CLOCKWISE) }
            msk = mask?.let { m -> mat().also { Core.rotate(m, it, Core.ROTATE_90_CLOCKWISE) } }
        }
        val p = fold.pos
        // a slanted gutter (thick book, perspective): shift the columns so the fold becomes the row p;
        // the text lines run along the columns in this frame, so they only move along themselves
        val shift = foldShift(img, msk, p)
        if (shift != null) {
            img = track(Dewarp.remap(img, shift.first, shift.second, img.cols(), img.rows()))
            msk = msk?.let { m ->
                val mx = Mat(m.rows(), m.cols(), CvType.CV_32F).also { track(it); it.put(0, 0, shift.first) }
                val my = Mat(m.rows(), m.cols(), CvType.CV_32F).also { track(it); it.put(0, 0, shift.second) }
                mat().also { Imgproc.remap(m, it, mx, my, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, org.opencv.core.Scalar(0.0)) }
            }
        }
        listOf(Range(0, p), Range(p, img.rows())).map { r ->
            img.rowRange(r).clone() to msk?.rowRange(r)?.clone()
        }
    }

    const val FOLD_STRIPS = 12
    const val FOLD_WINDOW = 0.06 // the gutter is searched within this share of the height around the fold
    const val MIN_FOLD_SLANT = 3.0 // px: smaller deviations are left alone

    /**
     * Remap (x, y source coordinates) that moves the gutter, measured as the darkest paper row in
     * [FOLD_STRIPS] strips along the fold and fitted by a robust line (Theil-Sen), onto the row [p];
     * null if the gutter runs straight. Measured on the user's series: a Kittel spread whose gutter
     * ran from one page into the other lost the line ends of the left page at the straight cut.
     */
    internal fun foldShift(img: Mat, mask: Mat?, p: Int): Pair<FloatArray, FloatArray>? = matScope {
        val (small, scale) = downscale(track(Enhance.toGray(img)), 900)
        val paper = track(paperBrightness(small))
        val sm = mask?.let { m -> mat().also { Imgproc.resize(m, it, small.size(), 0.0, 0.0, Imgproc.INTER_NEAREST) } }
        val h = small.rows(); val w = small.cols()
        val pv = bytes(paper); val mv = sm?.let { bytes(it) }
        val ps = p * scale
        val win = max(3, (FOLD_WINDOW * h).toInt())
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (s in 0 until FOLD_STRIPS) {
            val c0 = s * w / FOLD_STRIPS; val c1 = (s + 1) * w / FOLD_STRIPS
            val rows = (max(0, (ps - win).toInt())..min(h - 1, (ps + win).toInt())).toList()
            val prof = DoubleArray(rows.size) { k ->
                var sum = 0.0; var n = 0
                for (c in c0 until c1) { val i = rows[k] * w + c; if (mv == null || mv[i].toInt() != 0) { sum += pv[i].toInt() and 0xff; n++ } }
                if (n < (c1 - c0) / 2) Double.NaN else sum / n
            }
            if (prof.any { it.isNaN() }) continue
            val sm2 = smooth(prof)
            val k = sm2.indices.minBy { sm2[it] }
            // a real gutter: clearly darker than the paper on both sides of it
            if (k == 0 || k == sm2.lastIndex) continue
            val flank = min(sm2.copyOfRange(0, k).max(), sm2.copyOfRange(k + 1, sm2.size).max())
            if ((flank - sm2[k]) / max(flank, 1.0) < MIN_DIP / 2) continue
            xs += (c0 + c1) / 2.0; ys += rows[k].toDouble()
        }
        if (xs.size < FOLD_STRIPS / 2) return@matScope null
        // Theil-Sen: median of pairwise slopes, then the median intercept
        val slopes = ArrayList<Double>()
        for (i in xs.indices) for (j in i + 1 until xs.size) slopes += (ys[j] - ys[i]) / (xs[j] - xs[i])
        val b = median(slopes.toDoubleArray())
        val a = median(DoubleArray(xs.size) { ys[it] - b * xs[it] })
        // offset of the fitted gutter from the cut, per column at full size
        val full = DoubleArray(img.cols()) { x -> (a + b * x * scale) / scale - p }
        if (full.maxOf { kotlin.math.abs(it) } < MIN_FOLD_SLANT) return@matScope null
        val mapX = FloatArray(img.rows() * img.cols()); val mapY = FloatArray(mapX.size)
        for (y in 0 until img.rows()) for (x in 0 until img.cols()) {
            mapX[y * img.cols() + x] = x.toFloat(); mapY[y * img.cols() + x] = (y + full[x]).toFloat()
        }
        mapX to mapY
    }
}
