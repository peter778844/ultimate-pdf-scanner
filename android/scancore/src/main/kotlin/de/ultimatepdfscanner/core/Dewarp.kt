package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Curved book pages: straighten a page between its two curved side edges.
 *
 * Works on a page whose fold is horizontal (as produced by [Spread.splitSpread]). The side
 * edges, which run from the outer edge to the fold, bend like the text lines do. Every row
 * is resampled between the two fitted edge curves, and rows are spaced by the edges' arc
 * length so the compressed area near the fold is stretched back. A second stage
 * ([straightenTextLines]) measures and removes the remaining bend of the text lines.
 */
object Dewarp {
    const val POLY_DEGREE = 4
    const val MIN_ROW_FILL = 0.5 // rows with less mask than this fraction of the median width are outside the page

    /** Polynomial in a normalised variable t = (y - centre) / scale (keeps the fit well conditioned). */
    class Poly(private val coef: DoubleArray, private val centre: Double, private val scale: Double) {
        operator fun invoke(y: Double): Double {
            val t = (y - centre) / scale
            var v = 0.0
            for (i in coef.indices.reversed()) v = v * t + coef[i]
            return v
        }
    }

    internal fun polyfit(y: DoubleArray, x: DoubleArray, degree: Int, keep: BooleanArray? = null): Poly {
        val idx = y.indices.filter { keep == null || keep[it] }
        val lo = idx.minOf { y[it] }
        val hi = idx.maxOf { y[it] }
        val centre = (lo + hi) / 2
        val scale = max((hi - lo) / 2, 1.0)
        val a = Mat(idx.size, degree + 1, CvType.CV_64F)
        val b = Mat(idx.size, 1, CvType.CV_64F)
        val row = DoubleArray(degree + 1)
        idx.forEachIndexed { r, i ->
            val t = (y[i] - centre) / scale
            var p = 1.0
            for (d in 0..degree) { row[d] = p; p *= t }
            a.put(r, 0, *row)
            b.put(r, 0, x[i])
        }
        val sol = Mat()
        Core.solve(a, b, sol, Core.DECOMP_SVD)
        val coef = DoubleArray(degree + 1).also { sol.get(0, 0, it) }
        a.release(); b.release(); sol.release()
        return Poly(coef, centre, scale)
    }

    internal fun robustPolyfit(y: DoubleArray, x: DoubleArray, degree: Int, rounds: Int = 3): Poly {
        var keep = BooleanArray(y.size) { true }
        var poly = polyfit(y, x, degree)
        repeat(rounds) {
            val res = DoubleArray(y.size) { abs(x[it] - poly(y[it])) }
            val mad = median(res.filterIndexed { i, _ -> keep[i] }.toDoubleArray()) + 1e-6
            keep = BooleanArray(y.size) { res[it] < 3 * 1.4826 * mad + 2 }
            poly = polyfit(y, x, degree, keep)
        }
        return poly
    }

    class Edges(val y0: Int, val y1: Int, val left: Poly, val right: Poly)

    const val SKIN_WINDOW = 12 // px around an edge point that must be free of skin for the point to count

    /**
     * Row range of the page and polynomials x = left(y), x = right(y) of its side edges. Edge
     * points next to [skin] (a hand holding the book) are left out: the fit bridges them from the
     * rest of the edge instead of following the fingers.
     */
    fun pageEdges(mask: Mat, skin: Mat? = null): Edges? {
        val rows = mask.rows()
        val cols = mask.cols()
        val m = bytes(mask)
        val fill = IntArray(rows) { r -> (0 until cols).count { m[r * cols + it].toInt() != 0 } }
        val positive = fill.filter { it > 0 }
        if (positive.isEmpty()) return null
        val minFill = MIN_ROW_FILL * median(positive.map { it.toDouble() }.toDoubleArray())
        val good = (0 until rows).filter { fill[it] >= minFill }
        if (good.size < 20) return null
        val y0 = good.first()
        val y1 = good.last()
        val sk = skin?.let { bytes(it) }
        fun skinNear(r: Int, c: Int): Boolean {
            sk ?: return false
            for (x in max(0, c - SKIN_WINDOW)..min(cols - 1, c + SKIN_WINDOW)) if (sk[r * cols + x].toInt() != 0) return true
            return false
        }
        val all = ArrayList<Double>()
        val lys = ArrayList<Double>(); val ls = ArrayList<Double>()
        val rys = ArrayList<Double>(); val rs = ArrayList<Double>()
        val lAll = ArrayList<Double>(); val rAll = ArrayList<Double>()
        for (r in y0..y1) {
            if (fill[r] == 0) continue
            var l = 0
            while (m[r * cols + l].toInt() == 0) l++
            var rr = cols - 1
            while (m[r * cols + rr].toInt() == 0) rr--
            all += r.toDouble(); lAll += l.toDouble(); rAll += rr.toDouble()
            if (!skinNear(r, l)) { lys += r.toDouble(); ls += l.toDouble() }
            if (!skinNear(r, rr)) { rys += r.toDouble(); rs += rr.toDouble() }
        }
        fun fit(ys: List<Double>, xs: List<Double>, fallbackX: List<Double>): Poly {
            // a hand along most of the edge: better the edge as it is than a fit to a few points
            val (y, x) = if (ys.size >= max(20, all.size / 3)) ys to xs else all to fallbackX
            val deg = min(POLY_DEGREE, max(1, y.size / 50))
            return robustPolyfit(y.toDoubleArray(), x.toDoubleArray(), deg)
        }
        return Edges(y0, y1, fit(lys, ls, lAll), fit(rys, rs, rAll))
    }

    /**
     * Straightened page, cut out along its exact outline ([Outline]); if that cannot be measured,
     * between the mask's side edges as before. Without a usable mask a copy of the image is returned.
     */
    fun dewarpPage(image: Mat, mask: Mat?, fold: Outline.Side? = null): Mat {
        val skin = if (mask != null && image.channels() == 3) Fingers.fingerMask(image) else null
        try {
            mask?.let { Outline.cut(image, it, skin, fold) }?.let { return it }
            return dewarpBetweenEdges(image, mask?.let { pageEdges(it, skin) })
        } finally {
            skin?.release()
        }
    }

    private fun dewarpBetweenEdges(image: Mat, edges: Edges?): Mat {
        edges ?: return image.clone()
        val n = edges.y1 - edges.y0 + 1
        val ys = DoubleArray(n) { (edges.y0 + it).toDouble() }
        val lx = DoubleArray(n) { edges.left(ys[it]) }
        val rx = DoubleArray(n) { edges.right(ys[it]) }
        val width = median(DoubleArray(n) { rx[it] - lx[it] }).roundToInt()
        if (width < 20) return image.clone()

        // arc length along both side edges -> output row spacing
        val dl = gradient(lx)
        val dr = gradient(rx)
        val s = DoubleArray(n)
        for (i in 1 until n) s[i] = s[i - 1] + 0.5 * (sqrt(1 + dl[i] * dl[i]) + sqrt(1 + dr[i] * dr[i]))
        val height = s[n - 1].roundToInt() + 1
        val mapX = FloatArray(height * width)
        val mapY = FloatArray(height * width)
        for (r in 0 until height) {
            val sy = interp(r.toDouble(), s, ys)
            val l = edges.left(sy)
            val span = edges.right(sy) - l
            for (c in 0 until width) {
                val u = if (width > 1) c.toDouble() / (width - 1) else 0.0
                mapX[r * width + c] = (l + span * u).toFloat()
                mapY[r * width + c] = sy.toFloat()
            }
        }
        return remap(image, mapX, mapY, width, height)
    }

    internal fun remap(image: Mat, mapX: FloatArray, mapY: FloatArray, width: Int, height: Int): Mat {
        val mx = Mat(height, width, CvType.CV_32F).also { it.put(0, 0, mapX) }
        val my = Mat(height, width, CvType.CV_32F).also { it.put(0, 0, mapY) }
        val out = Mat()
        Imgproc.remap(image, out, mx, my, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        mx.release(); my.release()
        return out
    }

    /** numpy.gradient: central differences inside, one-sided at the ends. */
    private fun gradient(v: DoubleArray): DoubleArray {
        val n = v.size
        if (n < 2) return DoubleArray(n)
        return DoubleArray(n) { i ->
            when (i) {
                0 -> v[1] - v[0]
                n - 1 -> v[n - 1] - v[n - 2]
                else -> (v[i + 1] - v[i - 1]) / 2
            }
        }
    }

    /** numpy.interp for increasing xp. */
    private fun interp(x: Double, xp: DoubleArray, fp: DoubleArray): Double {
        if (x <= xp[0]) return fp[0]
        if (x >= xp[xp.size - 1]) return fp[fp.size - 1]
        var lo = 0
        var hi = xp.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (xp[mid] <= x) lo = mid else hi = mid
        }
        val t = (x - xp[lo]) / (xp[hi] - xp[lo])
        return fp[lo] + t * (fp[hi] - fp[lo])
    }

    // --- second stage: straighten the text lines themselves -------------------------------
    // Text lines run perpendicular to the fold, i.e. vertically in this frame. The page is cut
    // into tiles; each tile's ink profile across x is aligned to its neighbour in y, the shifts
    // are chained outwards from the middle row and a smooth surface d(x, y) is fitted to them.

    val GRID = 6 to 28 // tiles across x, strips along y
    val SHIFT_DEG = 2 to 5 // polynomial degree of the correction surface in x and y

    /** 0/1 ink map; flattened first because a global threshold would count gutter shadow as ink. */
    private fun ink(image: Mat): Mat = matScope {
        val gray = track(Enhance.toGray(image))
        val flat = track(Enhance.flatten(gray))
        Mat().also { Imgproc.threshold(flat, it, 0.0, 1.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU) }
    }

    internal fun linePeriod(profile: DoubleArray): Double? {
        val n = profile.size
        val mean = profile.average()
        val p = DoubleArray(n) { profile[it] - mean }
        val ac = DoubleArray(n) { lag -> var s = 0.0; for (i in 0 until n - lag) s += p[i] * p[i + lag]; s }
        if (ac[0] <= 0) return null
        val norm = DoubleArray(n) { ac[it] / ac[0] }
        for (i in 1 until n - 1) {
            if (norm[i] > norm[i - 1] && norm[i] >= norm[i + 1] && norm[i] > 0.2) return i.toDouble()
        }
        return null
    }

    /** Sub-pixel shift s with b(x) ~ a(x - s), and the normalised correlation at s. */
    internal fun shift(a0: DoubleArray, b0: DoubleArray, maxShift: Int): Pair<Double, Double> {
        fun normalise(v: DoubleArray): DoubleArray {
            val m = v.average()
            val sd = sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
            return DoubleArray(v.size) { (v[it] - m) / (sd + 1e-6) }
        }
        val a = normalise(a0)
        val b = normalise(b0)
        val n = a.size
        val scores = DoubleArray(2 * maxShift + 1) { k ->
            val s = k - maxShift
            var dotv = 0.0
            val from = max(0, -s)
            val to = n - max(0, s)
            for (i in from until to) dotv += a[i] * b[i + s]
            dotv / (n - abs(s))
        }
        var i = 0
        for (k in scores.indices) if (scores[k] > scores[i]) i = k
        var off = 0.0
        if (i > 0 && i < scores.size - 1) {
            val den = scores[i - 1] - 2 * scores[i] + scores[i + 1]
            off = if (den != 0.0) 0.5 * (scores[i - 1] - scores[i + 1]) / den else 0.0
        }
        return (i - maxShift + off) to scores[i]
    }

    const val MAX_LINE_SHIFT = 0.1 // a correction beyond this share of the width is a misfit (real curl at the fold: up to ~5 %)

    /**
     * Remove the remaining bend of the (vertical) text lines; returns a copy of the image if
     * there is not enough regular text to measure. [Spread.splitSpread] hands over every page
     * turned so that its lines run vertically, whichever way the book was photographed.
     */
    fun straightenTextLines(image: Mat): Mat {
        val inkMat = ink(image)
        val h = inkMat.rows()
        val w = inkMat.cols()
        val inkData = bytes(inkMat)
        inkMat.release()
        val (nx, ny) = GRID
        val xs = IntArray(nx + 1) { (it.toDouble() * w / nx).toInt() }
        val ys = IntArray(ny + 1) { (it.toDouble() * h / ny).toInt() }

        // column sums per strip
        fun columnSums(r0: Int, r1: Int): DoubleArray {
            val out = DoubleArray(w)
            for (r in r0 until r1) for (c in 0 until w) out[c] += inkData[r * w + c].toDouble()
            return out
        }
        val strips = Array(ny) { columnSums(ys[it], ys[it + 1]) }
        fun profile(j: Int, i: Int) = strips[j].copyOfRange(xs[i], xs[i + 1])

        val period = linePeriod(columnSums(h / 3, 2 * h / 3)) ?: return image.clone()
        if (period < 4) return image.clone()
        val maxStep = max(2, (0.4 * period).toInt())

        val px = ArrayList<Double>()
        val py = ArrayList<Double>()
        val vals = ArrayList<Double>()
        val weights = ArrayList<Double>()
        val mid = ny / 2
        for (i in 0 until nx) {
            val cx = 0.5 * (xs[i] + xs[i + 1])
            for (direction in intArrayOf(-1, 1)) {
                var acc = 0.0
                var j = mid
                while (j + direction in 0 until ny) {
                    val next = j + direction
                    val a = profile(j, i)
                    val b = profile(next, i)
                    if (std(a) < 1e-3 || std(b) < 1e-3) break // blank strip: stop chaining, the error would accumulate
                    val (s, corr) = shift(a, b, maxStep)
                    if (corr < 0.3) break
                    acc += s
                    px += cx; py += 0.5 * (ys[next] + ys[next + 1]); vals += acc; weights += corr
                    j = next
                }
            }
            px += cx; py += 0.5 * (ys[mid] + ys[mid + 1]); vals += 0.0; weights += 1.0
        }

        val measured = vals.size - nx // minus the zero anchors of the middle row
        // pages with little text: lower the degrees to what the measured tiles can support
        val degX = min(SHIFT_DEG.first, vals.indices.filter { vals[it] != 0.0 }.map { px[it] }.toSet().size - 1)
        val degY = min(SHIFT_DEG.second, measured / (2 * max(1, degX + 1)) - 1)
        if (degX < 0 || degY < 1) return image.clone()
        val terms = (0..degX).flatMap { p -> (0..degY).map { q -> p to q } }

        val a = Mat(vals.size, terms.size, CvType.CV_64F)
        val b = Mat(vals.size, 1, CvType.CV_64F)
        for (k in vals.indices) {
            val sw = sqrt(weights[k])
            val x = px[k] / w
            val y = py[k] / h
            a.put(k, 0, *DoubleArray(terms.size) { t -> Math.pow(x, terms[t].first.toDouble()) * Math.pow(y, terms[t].second.toDouble()) * sw })
            b.put(k, 0, vals[k] * sw)
        }
        val sol = Mat()
        Core.solve(a, b, sol, Core.DECOMP_SVD)
        val coef = DoubleArray(terms.size).also { sol.get(0, 0, it) }
        a.release(); b.release(); sol.release()

        // d(x, y) = sum_p x^p * (sum_q c_pq y^q): evaluate the y-polynomials once per row
        val mapX = FloatArray(h * w)
        val mapY = FloatArray(h * w)
        val xpow = Array(degX + 1) { p -> DoubleArray(w) { Math.pow(it.toDouble() / w, p.toDouble()) } }
        val maxShift = MAX_LINE_SHIFT * w
        for (r in 0 until h) {
            val y = r.toDouble() / h
            val rowCoef = DoubleArray(degX + 1)
            terms.forEachIndexed { t, (p, q) -> rowCoef[p] += coef[t] * Math.pow(y, q.toDouble()) }
            for (c in 0 until w) {
                var d = 0.0
                for (p in 0..degX) d += xpow[p][c] * rowCoef[p]
                if (kotlin.math.abs(d) > maxShift) return image.clone()
                mapX[r * w + c] = (c + d).toFloat()
                mapY[r * w + c] = r.toFloat()
            }
        }
        return remap(image, mapX, mapY, w, h)
    }

    private fun std(v: DoubleArray): Double {
        val m = v.average()
        return sqrt(v.sumOf { (it - m) * (it - m) } / v.size)
    }
}
