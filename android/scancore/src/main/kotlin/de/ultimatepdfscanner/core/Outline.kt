package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Exact page outline and the page cut out along it. The page mask comes from a 600-px
 * segmentation, i.e. its edge is off by up to ~10 px in a 12 MP photo, which left thin dark
 * strips of table or book edge at the borders of finished pages. Here every boundary point of
 * the mask is moved onto the strongest paper/background step nearby in the full-resolution
 * image; robust polynomials through these points give the four sides: x = left(y), x = right(y),
 * y = top(x), y = bottom(x). A side where the mask was cut off (the fold of a split spread, or
 * the image border) stays the straight cut. The page is then resampled with a Coons patch
 * between the four curves (each side parametrised by its arc length), which also flattens a
 * curved outer edge instead of leaving dark wedges in the corners.
 */
object Outline {
    enum class Side { TOP, BOTTOM, LEFT, RIGHT }
    var DEBUG = false

    const val SEARCH = 0.012 // searched distance from the mask edge, as share of the page size across that side
    const val MIN_SEARCH = 8 // px
    const val MIN_STEP = 10.0 // grey-level step of a usable edge sample
    const val STEP_SHARE = 0.6 // of the steps within the window, the one nearest the mask that reaches this share of the strongest
    const val SAMPLES = 160 // edge samples per side
    const val INSET = 0.0015 // the outermost share of the page is cut away: the blurred edge transition is a grey line
    const val CUT_FILL = 0.6 // a side whose border line is this full of mask is a cut (fold or image border)

    class Sides(val left: Dewarp.Poly, val right: Dewarp.Poly, val top: Dewarp.Poly, val bottom: Dewarp.Poly,
                val corners: List<Point>) // tl, tr, br, bl

    /**
     * The page cut out along its exact outline, or null if the outline cannot be measured (then the
     * caller keeps its previous method). [skin]: fingers at the edge, whose points are left out.
     */
    fun cut(image: Mat, mask: Mat, skin: Mat? = null, fold: Side? = null): Mat? {
        val sides = sides(image, mask, skin, fold) ?: return null
        return resample(image, sides)
    }

    const val REFINE_SIZE = 800 // working size of the mask refinement
    const val REFINE_BAND = 0.10 // undecided band (wider than the widest table strip seen: 7 %) on both sides of the mask edge, as share of the short side
    const val REFINE_MIN_KEEP = 0.8 // the refined mask stays within this share (and 1 / it) of the mask's area

    /**
     * The mask re-segmented around its edge: the photo-wide GrabCut (600 px) sometimes takes a strip
     * of table along a page edge (measured up to 7 % of the page width), far more than the edge
     * search can correct. Here GrabCut runs on the single page with its colour models learned right
     * at the edge: the page's inside is certain paper, everything beyond a band around the mask
     * edge is certain ground, the band is open. Its smoothness term puts the border on the strong
     * paper/table step rather than on soft shadows. (A colour rule failed: shaded paper is as warm
     * as wood.) Null if nothing changes or the result is implausible.
     */
    fun refine(image: Mat, mask: Mat): Mat? = matScope {
        if (image.channels() != 3) return@matScope null
        val (small, _) = downscale(image, REFINE_SIZE)
        Imgproc.GaussianBlur(small, small, Size(3.0, 3.0), 0.0)
        val sm = mat(); Imgproc.resize(mask, sm, small.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        val maskArea = Core.countNonZero(sm).toDouble()
        if (maskArea < 1000) return@matScope null
        val b = odd(max(5, (REFINE_BAND * min(small.rows(), small.cols())).roundToInt()))
        val inner = mat(); Imgproc.erode(sm, inner, kernel(b))
        val outer = mat(); Imgproc.dilate(sm, outer, kernel(b))
        if (Core.countNonZero(inner) < 0.3 * maskArea) return@matScope null
        val gc = track(Mat(small.size(), CvType.CV_8U, Scalar(Imgproc.GC_BGD.toDouble())))
        gc.setTo(Scalar(Imgproc.GC_PR_BGD.toDouble()), outer)
        gc.setTo(Scalar(Imgproc.GC_PR_FGD.toDouble()), sm)
        gc.setTo(Scalar(Imgproc.GC_FGD.toDouble()), inner)
        if (small.total() - Core.countNonZero(sm) < 500) return@matScope null // no ground to learn from
        Core.setRNGSeed(Detect.GRABCUT_SEED)
        try {
            Imgproc.grabCut(small, gc, org.opencv.core.Rect(), mat(), mat(), 3, Imgproc.GC_INIT_WITH_MASK)
        } catch (e: Exception) {
            return@matScope null
        }
        val lut = track(Mat(1, 256, CvType.CV_8U, Scalar(0.0)))
        lut.put(0, Imgproc.GC_FGD, byteArrayOf(-1)); lut.put(0, Imgproc.GC_PR_FGD, byteArrayOf(-1))
        val fg = mat(); Core.LUT(gc, lut, fg)
        Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_OPEN, kernel(5))
        val page = Detect.largestComponent(fg)?.let { track(it) } ?: return@matScope null // outer contour, holes filled
        val area = Core.countNonZero(page).toDouble()
        val diff = mat(); Core.bitwise_xor(page, sm, diff)
        val changed = Core.countNonZero(diff) / maskArea
        if (DEBUG) {
            println("REFINE area %.3f changed %.3f".format(area / maskArea, changed))
            val vis = small.clone()
            val removed = mat(); Core.subtract(sm, page, removed)
            val added = mat(); Core.subtract(page, sm, added)
            vis.setTo(Scalar(0.0, 0.0, 255.0), removed); vis.setTo(Scalar(0.0, 255.0, 0.0), added)
            org.opencv.imgcodecs.Imgcodecs.imwrite("build/refine_${System.nanoTime()}.jpg", vis)
            vis.release()
        }
        if (area < REFINE_MIN_KEEP * maskArea || area > maskArea / REFINE_MIN_KEEP || changed < 0.005) return@matScope null
        val up = mat(); Imgproc.resize(page, up, mask.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        Mat().also { Imgproc.threshold(up, it, 127.0, 255.0, Imgproc.THRESH_BINARY) }
    }

    /** The four side curves of one mask, before the corners are known. */
    private class Curves(val left: Dewarp.Poly, val right: Dewarp.Poly, val top: Dewarp.Poly, val bottom: Dewarp.Poly,
                         val pageW: Double, val pageH: Double)

    const val BACKGROUND_LEVEL = 0.55 // a strip darker than this share of the paper is ground, not shaded paper

    /**
     * [fold]: the side where a spread was cut at the fold (kept as the straight cut). Other sides
     * count as cut where the paper itself runs into the image border. Each side is measured on the
     * mask as it came and on its [refine]ment; the inner of the two is only taken where the strip
     * between them is dark like the ground: the refinement once took the shaded, curled edge of a
     * page with the first letters of every line for table, which cut the text.
     */
    fun sides(image: Mat, mask0: Mat, skin: Mat? = null, fold: Side? = null): Sides? = matScope {
        val h = mask0.rows(); val w = mask0.cols()
        if (h < 40 || w < 40) return@matScope null
        val gray = track(Enhance.toGray(image))
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val g = bytes(gray)
        fun px(r: Int, c: Int) = g[r.coerceIn(0, h - 1) * w + c.coerceIn(0, w - 1)].toInt() and 0xff
        val plain = curves(g, w, h, mask0, skin, fold) ?: return@matScope null
        val refined = refine(image, mask0)?.let { track(it) }?.let { curves(g, w, h, it, skin, fold) }
        val (l, r, t, b) = if (refined == null) listOf(plain.left, plain.right, plain.top, plain.bottom) else {
            val paper = histPercentile(histogram(gray, mask0), 90.0)
            // brightness of the paper (80th percentile) in the strip between two versions of a side
            fun strip(a: Dewarp.Poly, c: Dewarp.Poly, from: Int, to: Int, alongRows: Boolean): Double {
                val values = ArrayList<Int>()
                for (u in from until to step max(1, (to - from) / 60)) {
                    val x0 = minOf(a(u.toDouble()), c(u.toDouble())).roundToInt(); val x1 = maxOf(a(u.toDouble()), c(u.toDouble())).roundToInt()
                    for (x in x0..x1) values += if (alongRows) px(u, x) else px(x, u)
                }
                if (values.size < 50) return 0.0
                values.sort()
                return values[values.size * 4 / 5].toDouble()
            }
            fun pick(p: Dewarp.Poly, q: Dewarp.Poly, alongRows: Boolean): Dewarp.Poly {
                val len = if (alongRows) h else w
                return if (strip(p, q, len / 10, len * 9 / 10, alongRows) >= BACKGROUND_LEVEL * paper) p else q
            }
            listOf(pick(plain.left, refined.left, true), pick(plain.right, refined.right, true),
                pick(plain.top, refined.top, false), pick(plain.bottom, refined.bottom, false))
        }
        // corners: intersections of neighbouring curves (fixed point, the curves cross at nearly right angles)
        fun corner(vertical: Dewarp.Poly, horizontal: Dewarp.Poly, x0: Double, y0: Double): Point? {
            var x = x0; var y = y0
            repeat(30) { y = horizontal(x); x = vertical(y) }
            return if (x.isFinite() && y.isFinite() && abs(horizontal(x) - y) < 1.0) Point(x, y) else null
        }
        val corners = listOf(corner(l, t, 0.0, 0.0), corner(r, t, w - 1.0, 0.0), corner(r, b, w - 1.0, h - 1.0), corner(l, b, 0.0, h - 1.0))
        if (corners.any { it == null }) return@matScope null
        val c = corners.filterNotNull()
        // plausibility: inside the (slightly grown) image, a sensible page size
        val tol = 0.05 * max(w, h)
        if (c.any { it.x < -tol || it.y < -tol || it.x > w - 1 + tol || it.y > h - 1 + tol }) return@matScope null
        val width = 0.5 * (c[1].x - c[0].x + c[2].x - c[3].x)
        val height = 0.5 * (c[3].y - c[0].y + c[2].y - c[1].y)
        if (width < 0.7 * plain.pageW || height < 0.7 * plain.pageH || width > 1.3 * plain.pageW || height > 1.3 * plain.pageH) return@matScope null
        Sides(l, r, t, b, c)
    }

    /** Side curves from the edge of [mask], each moved onto the paper/background step nearby in [g] (grey, w x h). */
    private fun curves(g: ByteArray, w: Int, h: Int, mask: Mat, skin: Mat?, fold: Side?): Curves? {
        val m = bytes(mask)
        fun on(r: Int, c: Int) = m[r * w + c].toInt() != 0
        val rowFill = IntArray(h) { r -> var n = 0; for (c in 0 until w) if (on(r, c)) n++; n }
        val colFill = IntArray(w) { c -> var n = 0; for (r in 0 until h) if (on(r, c)) n++; n }
        val pageW = median(rowFill.filter { it > 0 }.map { it.toDouble() }.toDoubleArray())
        val pageH = median(colFill.filter { it > 0 }.map { it.toDouble() }.toDoubleArray())
        if (pageW.isNaN() || pageH.isNaN() || pageW < 40 || pageH < 40) return null
        val rows = (0 until h).filter { rowFill[it] >= 0.5 * pageW }
        val cols = (0 until w).filter { colFill[it] >= 0.5 * pageH }
        if (rows.size < 40 || cols.size < 40) return null
        fun px(r: Int, c: Int) = g[r.coerceIn(0, h - 1) * w + c.coerceIn(0, w - 1)].toInt() and 0xff
        val sk = skin?.let { bytes(it) }
        fun skinAt(r: Int, c: Int): Boolean {
            sk ?: return false
            val d = 12
            for (rr in max(0, r - d)..min(h - 1, r + d) step 4) for (cc in max(0, c - d)..min(w - 1, c + d) step 4) if (sk[rr * w + cc].toInt() != 0) return true
            return false
        }

        // a side is a cut where the mask runs into the image border along most of it
        val topCut = fold == Side.TOP || rowFill[0] >= CUT_FILL * pageW
        val bottomCut = fold == Side.BOTTOM || rowFill[h - 1] >= CUT_FILL * pageW
        val leftCut = fold == Side.LEFT || colFill[0] >= CUT_FILL * pageH
        val rightCut = fold == Side.RIGHT || colFill[w - 1] >= CUT_FILL * pageH

        /**
         * One side: samples at the positions [along] (rows for left/right, columns for top/bottom),
         * the mask edge across it, then the paper/background step nearest to that edge.
         * [inward] +1: the paper lies at larger coordinates. [acrossX]: the search runs along x.
         * Returns (along, across) of the edge points.
         */
        fun side(along: List<Int>, maskEdge: (Int) -> Int?, inward: Int, acrossX: Boolean, size: Double): Pair<DoubleArray, DoubleArray>? {
            val every = max(1, along.size / SAMPLES)
            val window = max(MIN_SEARCH, (SEARCH * size).roundToInt())
            fun at(a: Int, x: Int) = if (acrossX) px(a, x) else px(x, a)
            val pos = ArrayList<Double>(); val edges = ArrayList<Double>(); val profiles = ArrayList<DoubleArray>()
            for (k in along.indices step every) {
                val a = along[k]
                val e = maskEdge(a) ?: continue
                if (if (acrossX) skinAt(a, e) else skinAt(e, a)) continue
                // step at each candidate position: mean of 3 px inside minus 3 px outside, over 3 lines
                profiles += DoubleArray(2 * window + 1) { j ->
                    val x = e + (j - window)
                    var d = 0.0
                    for (t in 1..3) for (o in -1..1) d += at(a + o, x + inward * t) - at(a + o, x - inward * t)
                    d / 9
                }
                pos += a.toDouble(); edges += e.toDouble()
            }
            if (pos.size < 20) return null
            // paper brighter than the background (usual) or darker (white table): the stronger total decides
            var up = 0.0; var down = 0.0
            for (p in profiles) { up += max(0.0, p.max()); down += max(0.0, -p.min()) }
            val pol = if (up >= down) 1.0 else -1.0
            val xs = DoubleArray(pos.size)
            for (i in pos.indices) {
                val v = DoubleArray(profiles[i].size) { profiles[i][it] * pol }
                val strongest = v.max()
                xs[i] = edges[i]
                if (strongest < MIN_STEP) continue
                // of the local maxima reaching STEP_SHARE of the strongest, the one nearest the mask edge
                var bestJ = -1
                for (j in v.indices) {
                    val peak = (j == 0 || v[j] >= v[j - 1]) && (j == v.lastIndex || v[j] >= v[j + 1])
                    if (peak && v[j] >= STEP_SHARE * strongest && (bestJ < 0 || abs(j - window) < abs(bestJ - window))) bestJ = j
                }
                xs[i] = edges[i] + (bestJ - window)
            }
            return pos.toDoubleArray() to xs
        }

        fun firstOn(r: Int): Int? { for (c in 0 until w) if (on(r, c)) return c; return null }
        fun lastOn(r: Int): Int? { for (c in w - 1 downTo 0) if (on(r, c)) return c; return null }
        fun firstOnCol(c: Int): Int? { for (r in 0 until h) if (on(r, c)) return r; return null }
        fun lastOnCol(c: Int): Int? { for (r in h - 1 downTo 0) if (on(r, c)) return r; return null }

        // the inner 90 % of each side: the corners are often bent, covered or rounded by the mask
        fun inner(l: List<Int>) = l.subList(l.size / 20, l.size - l.size / 20)
        val left = if (leftCut) null else side(inner(rows), ::firstOn, 1, true, pageW) ?: return null
        val right = if (rightCut) null else side(inner(rows), ::lastOn, -1, true, pageW) ?: return null
        val top = if (topCut) null else side(inner(cols), ::firstOnCol, 1, false, pageH) ?: return null
        val bottom = if (bottomCut) null else side(inner(cols), ::lastOnCol, -1, false, pageH) ?: return null

        fun fit(p: Pair<DoubleArray, DoubleArray>?, line: Double, degree: Int): Dewarp.Poly =
            if (p == null) Dewarp.Poly(doubleArrayOf(line), 0.0, 1.0)
            else ransacFit(p.first, p.second, min(degree, max(1, p.first.size / 30)))
        // sides along the rows bend like text lines near a fold (up to degree 4); the others are nearly straight
        return Curves(fit(left, 0.0, Dewarp.POLY_DEGREE), fit(right, w - 1.0, Dewarp.POLY_DEGREE),
            fit(top, 0.0, 2), fit(bottom, h - 1.0, 2), pageW, pageH)
    }

    const val RANSAC_TOL = 0.01 // inlier distance as share of the side's length (at least RANSAC_MIN_TOL px)
    const val RANSAC_MIN_TOL = 4.0

    /**
     * Side curve through edge points with outliers (notches of the mask, fingers, a corner of the
     * table): a parabola through three random points that explains the most points first, then the
     * polynomial of [degree] through its supporters and a robust refit. A plain least-squares start
     * was pulled away by a notch covering a quarter of a side.
     */
    internal fun ransacFit(pos: DoubleArray, edge: DoubleArray, degree: Int, iterations: Int = 300): Dewarp.Poly {
        val n = pos.size
        val tol = max(RANSAC_MIN_TOL, RANSAC_TOL * (pos.max() - pos.min()))
        val rng = java.util.Random(Detect.GRABCUT_SEED.toLong())
        var best: BooleanArray? = null
        var bestCount = 0
        val sampleDegree = min(2, degree)
        repeat(iterations) {
            val idx = IntArray(sampleDegree + 1) { rng.nextInt(n) }
            if (idx.toSet().size < idx.size) return@repeat
            val ys = DoubleArray(idx.size) { pos[idx[it]] }
            if (ys.toSet().size < ys.size) return@repeat
            val model = Dewarp.polyfit(ys, DoubleArray(idx.size) { edge[idx[it]] }, sampleDegree)
            val inl = BooleanArray(n) { abs(edge[it] - model(pos[it])) <= tol }
            val c = inl.count { it }
            if (c > bestCount) { bestCount = c; best = inl }
        }
        val keep = best ?: return Dewarp.robustPolyfit(pos, edge, degree)
        if (bestCount < max(10, n / 3)) return Dewarp.robustPolyfit(pos, edge, degree)
        // refine: the full degree on the supporters, then once more with the points that fit it
        var poly = Dewarp.polyfit(pos, edge, min(degree, max(1, bestCount / 30)), keep)
        repeat(2) {
            val k2 = BooleanArray(n) { abs(edge[it] - poly(pos[it])) <= 1.5 * tol }
            if (k2.count { it } >= degree + 2) poly = Dewarp.polyfit(pos, edge, min(degree, max(1, k2.count { it } / 30)), k2)
        }
        return poly
    }

    /** Points along a curve between two corners, parametrised by arc length (0..1). */
    private class Curve(val xs: DoubleArray, val ys: DoubleArray) {
        val s = DoubleArray(xs.size).also { for (i in 1 until xs.size) it[i] = it[i - 1] + hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]) }
        val length get() = s.last()
        fun at(u: Double): Point {
            val target = u * length
            var lo = 0; var hi = s.size - 1
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (s[mid] <= target) lo = mid else hi = mid }
            val f = if (s[hi] > s[lo]) (target - s[lo]) / (s[hi] - s[lo]) else 0.0
            return Point(xs[lo] + f * (xs[hi] - xs[lo]), ys[lo] + f * (ys[hi] - ys[lo]))
        }
    }

    private fun horizontalCurve(p: Dewarp.Poly, x0: Double, x1: Double): Curve {
        val n = max(2, abs(x1 - x0).roundToInt() / 4)
        val xs = DoubleArray(n + 1) { x0 + (x1 - x0) * it / n }
        return Curve(xs, DoubleArray(n + 1) { p(xs[it]) })
    }

    private fun verticalCurve(p: Dewarp.Poly, y0: Double, y1: Double): Curve {
        val n = max(2, abs(y1 - y0).roundToInt() / 4)
        val ys = DoubleArray(n + 1) { y0 + (y1 - y0) * it / n }
        return Curve(DoubleArray(n + 1) { p(ys[it]) }, ys)
    }

    /** Coons patch between the four sides, output size from their arc lengths, minus [INSET] on each side. */
    fun resample(image: Mat, sides: Sides): Mat? {
        val (tl, tr, br, bl) = sides.corners
        val top = horizontalCurve(sides.top, tl.x, tr.x)
        val bottom = horizontalCurve(sides.bottom, bl.x, br.x)
        val left = verticalCurve(sides.left, tl.y, bl.y)
        val right = verticalCurve(sides.right, tr.y, br.y)
        val fullW = 0.5 * (top.length + bottom.length)
        val fullH = 0.5 * (left.length + right.length)
        val ix = max(1, (INSET * fullW).roundToInt()); val iy = max(1, (INSET * fullH).roundToInt())
        val w = fullW.roundToInt() - 2 * ix
        val h = fullH.roundToInt() - 2 * iy
        if (w < 20 || h < 20) return null
        val n = fullW.roundToInt(); val mRows = fullH.roundToInt()
        val tp = Array(w) { top.at((it + ix).toDouble() / (n - 1)) }
        val bp = Array(w) { bottom.at((it + ix).toDouble() / (n - 1)) }
        val mapX = FloatArray(w * h)
        val mapY = FloatArray(w * h)
        for (r in 0 until h) {
            val v = (r + iy).toDouble() / (mRows - 1)
            val lp = left.at(v); val rp = right.at(v)
            for (c in 0 until w) {
                val u = (c + ix).toDouble() / (n - 1)
                val x = (1 - v) * tp[c].x + v * bp[c].x + (1 - u) * lp.x + u * rp.x -
                    ((1 - u) * (1 - v) * tl.x + u * (1 - v) * tr.x + (1 - u) * v * bl.x + u * v * br.x)
                val y = (1 - v) * tp[c].y + v * bp[c].y + (1 - u) * lp.y + u * rp.y -
                    ((1 - u) * (1 - v) * tl.y + u * (1 - v) * tr.y + (1 - u) * v * bl.y + u * v * br.y)
                mapX[r * w + c] = x.toFloat(); mapY[r * w + c] = y.toFloat()
            }
        }
        return Dewarp.remap(image, mapX, mapY, w, h)
    }
}
