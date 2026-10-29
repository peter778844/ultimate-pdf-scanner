package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** Page detection: segment the document from the background and find its four corners. */
object Detect {
    const val WORK_SIZE = 600 // longer side of the downscaled working image
    const val MIN_AREA_RATIO = 0.1
    const val MAX_AREA_RATIO = 0.97
    const val GRABCUT_SEED = 1234 // same value in the Python prototype

    class Result(val quad: Quad?, val mask: Mat?) : AutoCloseable {
        override fun close() { mask?.release() }
    }

    /** Corners ordered tl, tr, br, bl (by x+y and y-x extremes). */
    fun orderQuad(pts: List<Point>): Quad {
        val s = pts.map { it.x + it.y }
        val d = pts.map { it.y - it.x }
        return arrayOf(
            pts[s.indexOf(s.min())], pts[d.indexOf(d.min())],
            pts[s.indexOf(s.max())], pts[d.indexOf(d.max())],
        ).map { Point(it.x, it.y) }.toTypedArray()
    }

    /**
     * GrabCut foreground mask. The background model is learned from a thin frame at the
     * image border, which copes with textured, paper-coloured tables where thresholds fail.
     */
    fun segmentDocument(small: Mat, iterations: Int = 4, seed: Mat? = null): Mat = matScope {
        val h = small.rows()
        val w = small.cols()
        val gc = track(Mat.zeros(h, w, CvType.CV_8U))
        val rect = grabCutFrame(small)
        Core.setRNGSeed(GRABCUT_SEED) // GrabCut's k-means is random: same photo -> same mask, regardless of earlier photos
        if (seed != null) {
            seed.copyTo(gc)
            Imgproc.grabCut(small, gc, rect, mat(), mat(), iterations, Imgproc.GC_INIT_WITH_MASK)
        } else Imgproc.grabCut(small, gc, rect, mat(), mat(), iterations, Imgproc.GC_INIT_WITH_RECT)
        // GC_FGD = 1 and GC_PR_FGD = 3 -> 255, background labels 0 and 2 -> 0
        val lut = track(Mat(1, 256, CvType.CV_8U, Scalar(0.0)))
        lut.put(0, Imgproc.GC_FGD, byteArrayOf(255.toByte()))
        lut.put(0, Imgproc.GC_PR_FGD, byteArrayOf(255.toByte()))
        val mask = mat()
        Core.LUT(gc, lut, mask)
        val k = max(3, odd(min(h, w) / 60))
        val opened = mat()
        Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, kernel(k))
        Mat().also { Imgproc.morphologyEx(opened, it, Imgproc.MORPH_CLOSE, kernel(k)) }
    }

    /** The background frame GrabCut starts from: 3 % on each side. */
    private fun grabCutFrame(small: Mat) =
        Rect((small.cols() * 0.03).toInt(), (small.rows() * 0.03).toInt(), (small.cols() * 0.94).toInt(), (small.rows() * 0.94).toInt())

    @JvmStatic var COLOR_SEED = true // switchable for the A/B evaluation (ColorSeedEval, -Deval=colorseed)
    const val SEED_MIN_KEEP = 0.75 // the colour-seeded outline may trim at most a quarter of the plain one
    const val SEED_MIN_PAPER = 0.15 // a paper area inside the photo must cover at least this share of the image
    const val SEED_BORDER = 0.01 // margin (share of the image size) that counts as touching the border
    const val SEED_CHROMA = 1.5 // "clearly coloured": beyond 1.5x the paper limits PAPER_CR / PAPER_CB
    const val SEED_DARK_Y = 60.0 // darker than this: no paper (shadowed paper stays above)

    /**
     * GrabCut start labels from the colours of page and background. Inside the frame, clearly
     * coloured or dark pixels (wood, a lap, the shadow below a book) start as probable background,
     * and so do small paper-coloured areas touching the photo border (the keys of a keyboard);
     * the rest starts as probable page as before. The background model then learns the table
     * from the whole image, not only from the thin border frame, and the page edge is cut where
     * the colour changes. null (plain rectangle start, as before) when no paper-coloured area of
     * page size lies inside the photo: dark or coloured covers, an empty table.
     */
    private fun colorSeed(small: Mat, rect: Rect): Mat? = matScope {
        val ycc = mat()
        Imgproc.cvtColor(small, ycc, Imgproc.COLOR_BGR2YCrCb)
        val paper = mat()
        Core.inRange(ycc, Scalar(PAPER_MIN_Y, 128.0 - PAPER_CR, 128.0 - PAPER_CB), Scalar(255.0, 128.0 + PAPER_CR, 128.0 + PAPER_CB), paper)
        val labels = mat()
        val stats = mat()
        val n = Imgproc.connectedComponentsWithStats(paper, labels, stats, mat(), 8, CvType.CV_32S)
        val margin = (SEED_BORDER * max(small.rows(), small.cols())).toInt().coerceAtLeast(1)
        fun touchesBorder(id: Int): Boolean {
            val x = stats.get(id, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y = stats.get(id, Imgproc.CC_STAT_TOP)[0].toInt()
            val w = stats.get(id, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val h = stats.get(id, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            return x <= margin || y <= margin || x + w >= small.cols() - margin || y + h >= small.rows() - margin
        }
        val area = (1 until n).associateWith { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
        val largest = area.values.maxOrNull() ?: return@matScope null
        val largestInside = area.filterKeys { !touchesBorder(it) }.values.maxOrNull() ?: 0.0
        if (largestInside < SEED_MIN_PAPER * small.total()) return@matScope null
        val notColoured = mat()
        Core.inRange(ycc, Scalar(SEED_DARK_Y, 128.0 - SEED_CHROMA * PAPER_CR, 128.0 - SEED_CHROMA * PAPER_CB),
            Scalar(255.0, 128.0 + SEED_CHROMA * PAPER_CR, 128.0 + SEED_CHROMA * PAPER_CB), notColoured)
        val background = mat()
        Core.bitwise_not(notColoured, background)
        for ((id, a) in area) if (touchesBorder(id) && a < PIECE_SHARE * largest) {
            val piece = mat()
            Core.compare(labels, Scalar(id.toDouble()), piece, Core.CMP_EQ)
            Core.bitwise_or(background, piece, background)
        }
        val label = Mat(small.size(), CvType.CV_8U, Scalar(Imgproc.GC_BGD.toDouble()))
        val inner = Mat(label, rect)
        inner.setTo(Scalar(Imgproc.GC_PR_FGD.toDouble()))
        inner.setTo(Scalar(Imgproc.GC_PR_BGD.toDouble()), Mat(background, rect))
        label
    }

    private fun largestContour(mask: Mat, method: Int): MatOfPoint? {
        val contours = ArrayList<MatOfPoint>()
        matScope { Imgproc.findContours(track(mask.clone()), contours, mat(), Imgproc.RETR_EXTERNAL, method) }
        val best = contours.maxByOrNull { Imgproc.contourArea(it) }
        contours.filter { it !== best }.forEach { it.release() }
        return best
    }

    fun largestComponent(mask: Mat): Mat? {
        val cnt = largestContour(mask, Imgproc.CHAIN_APPROX_NONE) ?: return null
        val out = Mat.zeros(mask.size(), CvType.CV_8U)
        Imgproc.drawContours(out, listOf(cnt), -1, Scalar(255.0), Imgproc.FILLED)
        cnt.release()
        return out
    }

    /**
     * The page as the largest foreground component, after removing skin- and wood-coloured
     * pixels: a hand touching the page or a lit wooden table otherwise merges with it and drags
     * a corner away (measured: paper Cr ~128 with <= 1 % 'skin', table/hand Cr 142-147 with
     * 52-81 %). If that removes too much (e.g. an orange cover), the unfiltered component is used.
     */
    fun pageComponent(small: Mat, seg: Mat): Mat? = matScope {
        val full = largestComponent(seg)?.let { track(it) } ?: return@matScope null
        val notSkin = mat()
        Core.bitwise_not(track(Fingers.skinMask(small)), notSkin)
        val fg = mat()
        Core.bitwise_and(seg, notSkin, fg)
        val k = max(3, odd(min(small.rows(), small.cols()) / 60))
        Imgproc.morphologyEx(fg, fg, Imgproc.MORPH_OPEN, kernel(k)) // cut thin bridges left between page and hand
        val filtered = pagePieces(fg, full)?.let { track(it) }
        val chosen = if (filtered != null && Core.countNonZero(filtered) >= 0.5 * Core.countNonZero(full)) filtered else full
        keep(chosen)
    }

    const val PIECE_SHARE = 0.5 // the other page of a spread is at least half as large as the largest piece ...
    const val PIECE_SIZE_RATIO = 1.6 // ... and about as wide and as high (a keyboard next to a book is not: 2.5-3.3x)

    /**
     * The large pieces of the filtered foreground that lie on the page. A brownish gutter shadow
     * looks like wood to the skin filter and cuts a spread in two; both halves are kept and the
     * gap between them is refilled from the unfiltered component [full] (only inside their hull,
     * so a hand at the outer edge stays removed). Bright neighbours such as a keyboard form a
     * separate piece of a different size and shape and are not joined (measured on the user's
     * series: keyboard 2.6-3.3x flatter than the page, the two pages of a spread within 1.2x).
     */
    private fun MatScope.pagePieces(fg: Mat, full: Mat): Mat? {
        val labels = mat()
        val stats = mat()
        val n = Imgproc.connectedComponentsWithStats(fg, labels, stats, mat(), 8, CvType.CV_32S)
        if (n <= 1) return null
        val areas = (1 until n).associateWith { stats.get(it, Imgproc.CC_STAT_AREA)[0] }
        val largestId = areas.maxBy { it.value }.key
        val largest = areas.getValue(largestId)
        fun dims(id: Int) = stats.get(id, Imgproc.CC_STAT_WIDTH)[0] to stats.get(id, Imgproc.CC_STAT_HEIGHT)[0]
        fun ratio(a: Double, b: Double) = maxOf(a, b) / maxOf(1.0, minOf(a, b))
        val (mainW, mainH) = dims(largestId)
        val union = track(Mat.zeros(fg.size(), CvType.CV_8U))
        var pieces = 0
        for ((id, area) in areas) {
            if (area < PIECE_SHARE * largest) continue
            if (id != largestId) {
                val (w, h) = dims(id)
                if (ratio(w, mainW) > PIECE_SIZE_RATIO || ratio(h, mainH) > PIECE_SIZE_RATIO) continue
            }
            val piece = mat()
            Core.compare(labels, Scalar(id.toDouble()), piece, Core.CMP_EQ)
            val onPage = mat()
            Core.bitwise_and(piece, full, onPage)
            if (Core.countNonZero(onPage) < 0.9 * area) continue
            Core.bitwise_or(union, piece, union)
            pieces++
        }
        if (pieces == 0) return null
        if (pieces > 1) {
            val pts = track(MatOfPoint())
            Core.findNonZero(union, pts)
            val hull = hullPoints(pts)
            val inHull = track(Mat.zeros(fg.size(), CvType.CV_8U))
            Imgproc.fillConvexPoly(inHull, MatOfPoint(*hull.toTypedArray()), Scalar(255.0))
            val gap = mat()
            Core.bitwise_and(full, inHull, gap)
            Core.bitwise_or(union, gap, union)
        }
        return largestComponent(union)
    }

    const val RANSAC_TOL = 0.006 // inlier distance as share of the working image size (~3.6 px at 600 px)

    /** A line (x0, y0, dx, dy) and how many contour points support it. */
    private class Line(val x0: Double, val y0: Double, val dx: Double, val dy: Double, val inliers: Int) {
        fun dist(p: Point) = kotlin.math.abs((p.x - x0) * dy - (p.y - y0) * dx)
        val angle get() = (Math.toDegrees(kotlin.math.atan2(dy, dx)) % 180 + 180) % 180
        fun asArray() = doubleArrayOf(x0, y0, dx, dy)
    }

    /** Sequential RANSAC: the [nLines] longest straight runs of contour points. */
    private fun ransacLines(points: List<Point>, tol: Double, nLines: Int = 6, iterations: Int = 400): List<Line> {
        val rng = java.util.Random(GRABCUT_SEED.toLong())
        var remaining = points
        val lines = ArrayList<Line>()
        repeat(nLines) {
            if (remaining.size < 20) return lines
            var best: Line? = null
            for (i in 0 until iterations) {
                val a = remaining[rng.nextInt(remaining.size)]
                val b = remaining[rng.nextInt(remaining.size)]
                val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
                if (len < 5 * tol) continue
                val cand = Line(a.x, a.y, (b.x - a.x) / len, (b.y - a.y) / len, 0)
                val count = remaining.count { cand.dist(it) < tol }
                if (count > (best?.inliers ?: 0)) best = Line(cand.x0, cand.y0, cand.dx, cand.dy, count)
            }
            val b = best ?: return lines
            if (b.inliers < 20) return lines
            val inl = remaining.filter { b.dist(it) < tol }
            val fitted = matScope {
                val out = mat()
                Imgproc.fitLine(track(MatOfPoint2f(*inl.toTypedArray())), out, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
                val v = FloatArray(4).also { out.get(0, 0, it) }
                Line(v[2].toDouble(), v[3].toDouble(), v[0].toDouble(), v[1].toDouble(), inl.size)
            }
            lines += fitted
            remaining = remaining.filter { fitted.dist(it) >= 2 * tol }
        }
        return lines
    }

    /**
     * Corners from the four dominant straight edges of the mask outline: page edges are the
     * longest straight runs, while attachments (hands, lit table patches) are short and
     * irregular. Picks two roughly parallel pairs of lines with the most inliers whose quad
     * covers the mask.
     */
    fun ransacQuad(mask: Mat): Quad? {
        val cnt = largestContour(mask, Imgproc.CHAIN_APPROX_NONE) ?: return null
        val pts = cnt.toList()
        cnt.release()
        val lines = ransacLines(pts, RANSAC_TOL * max(mask.rows(), mask.cols()))
        if (lines.size < 4) return null
        val area = Core.countNonZero(mask).toDouble()
        fun angleDiff(a: Double, b: Double): Double { val d = kotlin.math.abs(a - b) % 180; return min(d, 180 - d) }

        var best: Quad? = null
        var bestScore = 0
        val n = lines.size
        for (i0 in 0 until n) for (i1 in i0 + 1 until n) for (i2 in i1 + 1 until n) for (i3 in i2 + 1 until n) {
            val ls = listOf(lines[i0], lines[i1], lines[i2], lines[i3])
            for ((p, q) in listOf((0 to 1) to (2 to 3), (0 to 2) to (1 to 3), (0 to 3) to (1 to 2))) {
                val p1 = ls[p.first] to ls[p.second]
                val p2 = ls[q.first] to ls[q.second]
                if (angleDiff(p1.first.angle, p1.second.angle) > 25 || angleDiff(p2.first.angle, p2.second.angle) > 25) continue
                if (angleDiff(p1.first.angle, p2.first.angle) < 45) continue
                val corners = listOf(p1.first, p1.second).flatMap { a -> listOf(p2.first, p2.second).map { b -> intersect(a.asArray(), b.asArray()) } }
                if (corners.any { it == null }) continue
                val quad = orderQuad(corners.filterNotNull())
                val q2f = MatOfPoint2f(*quad)
                val convex = Imgproc.isContourConvex(MatOfPoint(*quad.map { Point(it.x, it.y) }.toTypedArray()))
                val qArea = Imgproc.contourArea(q2f)
                q2f.release()
                if (!convex || qArea < 0.6 * area || qArea > 1.6 * area) continue
                val score = ls.sumOf { it.inliers }
                if (score > bestScore) { best = quad; bestScore = score }
            }
        }
        return best
    }

    const val PROTRUSION_SIZE = 0.3 // opening kernel as share of sqrt(page area): narrower attachments are cut off
    const val CLIP_MARGIN = 0.04 // the mask is kept within the quad grown by this share (room for bulging book edges)

    /**
     * For corner finding only: an opening with a kernel of ~30 % of the page size removes
     * attachments narrower than that (lit table patches, fingers) while the page survives.
     * Its rounded corners do not matter because [refineQuad] refits the sides on the full mask.
     */
    fun withoutProtrusions(mask: Mat): Mat = matScope {
        val k = odd((PROTRUSION_SIZE * kotlin.math.sqrt(Core.countNonZero(mask).toDouble())).toInt())
        val opened = mat()
        Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, kernel(max(3, k)))
        val main = largestComponent(opened)
        if (main == null || Core.countNonZero(main) < 0.5 * Core.countNonZero(mask)) { main?.release(); mask.clone() } else main
    }

    /** Mask restricted to the quad grown by [CLIP_MARGIN] around its centre. */
    fun clipToQuad(mask: Mat, quad: Quad): Mat {
        val cx = quad.sumOf { it.x } / 4
        val cy = quad.sumOf { it.y } / 4
        val grown = quad.map { Point(cx + (it.x - cx) * (1 + 2 * CLIP_MARGIN), cy + (it.y - cy) * (1 + 2 * CLIP_MARGIN)) }
        val poly = Mat.zeros(mask.size(), CvType.CV_8U)
        Imgproc.fillConvexPoly(poly, MatOfPoint(*grown.map { Point(Math.round(it.x).toDouble(), Math.round(it.y).toDouble()) }.toTypedArray()), Scalar(255.0))
        val out = Mat()
        Core.bitwise_and(mask, poly, out)
        poly.release()
        return out
    }

    /**
     * Refit each side of the quad as a robust (Huber) line through the contour points near it,
     * ignoring the corner regions; protrusions further than the tolerance do not pull the edge.
     * Corners are the intersections of adjacent lines. Falls back to [quad] if a corner would
     * move implausibly far.
     */
    fun refineQuad(quad: Quad, mask: Mat): Quad {
        val cnt = largestContour(mask, Imgproc.CHAIN_APPROX_NONE) ?: return quad
        val pts = cnt.toArray()
        cnt.release()
        val perimeter = (0 until 4).sumOf { dist(quad[it], quad[(it + 1) % 4]) }
        val tol = 0.02 * perimeter
        // each line as (point, unit direction)
        val lines = (0 until 4).map { k ->
            val a = quad[k]
            val b = quad[(k + 1) % 4]
            val len = dist(a, b)
            val dx = (b.x - a.x) / len
            val dy = (b.y - a.y) / len
            val near = pts.filter { p ->
                val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / len
                val d = kotlin.math.abs((p.x - a.x) * dy - (p.y - a.y) * dx)
                t in 0.1..0.9 && d < tol
            }
            if (near.size < 10) doubleArrayOf(a.x, a.y, dx, dy)
            else matScope {
                val line = mat()
                Imgproc.fitLine(track(MatOfPoint2f(*near.toTypedArray())), line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
                val v = FloatArray(4).also { line.get(0, 0, it) }
                doubleArrayOf(v[2].toDouble(), v[3].toDouble(), v[0].toDouble(), v[1].toDouble())
            }
        }
        val refined = (0 until 4).map { k -> intersect(lines[(k + 3) % 4], lines[k]) ?: return quad }
        if ((0 until 4).any { dist(refined[it], quad[it]) > 0.1 * perimeter }) return quad
        return refined.toTypedArray()
    }

    private fun dist(a: Point, b: Point) = kotlin.math.hypot(a.x - b.x, a.y - b.y)

    /** Intersection of two lines given as (x0, y0, dx, dy); null if (nearly) parallel. */
    private fun intersect(l1: DoubleArray, l2: DoubleArray): Point? {
        val den = l1[2] * l2[3] - l1[3] * l2[2]
        if (kotlin.math.abs(den) < 1e-9) return null
        val t = ((l2[0] - l1[0]) * l2[3] - (l2[1] - l1[1]) * l2[2]) / den
        return Point(l1[0] + t * l1[2], l1[1] + t * l1[3])
    }

    private fun hullPoints(contour: MatOfPoint): List<Point> {
        val idx = MatOfInt()
        Imgproc.convexHull(contour, idx)
        val all = contour.toArray()
        return idx.toArray().map { all[it] }.also { idx.release() }
    }

    fun quadFromMask(mask: Mat): Quad? {
        val cnt = largestContour(mask, Imgproc.CHAIN_APPROX_SIMPLE) ?: return null
        val hull = hullPoints(cnt)
        cnt.release()
        val hull2f = MatOfPoint2f(*hull.toTypedArray())
        val peri = Imgproc.arcLength(hull2f, true)
        for (eps in doubleArrayOf(0.02, 0.03, 0.05)) {
            val approx = MatOfPoint2f()
            Imgproc.approxPolyDP(hull2f, approx, eps * peri, true)
            val pts = approx.toList()
            approx.release()
            if (pts.size == 4) return orderQuad(pts).also { hull2f.release() }
        }
        hull2f.release()
        // bulging edges (open books): take the points extremal along both diagonals
        return orderQuad(hull)
    }

    /** Fallback for plain backgrounds: Canny edges, biggest 4-corner contour. */
    private fun edgeQuad(gray: Mat): Quad? = matScope {
        val blur = mat()
        Imgproc.GaussianBlur(gray, blur, Size(5.0, 5.0), 0.0)
        val edges = mat()
        Imgproc.Canny(blur, edges, 50.0, 150.0)
        Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel(5), Point(-1.0, -1.0), 2)
        val minArea = MIN_AREA_RATIO * gray.total()
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(edges, contours, mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.forEach { track(it) }
        for (cnt in contours.sortedByDescending { Imgproc.contourArea(it) }.take(5)) {
            val hull = hullPoints(cnt)
            val hull2f = track(MatOfPoint2f(*hull.toTypedArray()))
            if (Imgproc.contourArea(hull2f) < minArea) break
            val approx = track(MatOfPoint2f())
            Imgproc.approxPolyDP(hull2f, approx, 0.02 * Imgproc.arcLength(hull2f, true), true)
            if (approx.rows() == 4) return@matScope orderQuad(approx.toList())
        }
        null
    }

    /**
     * Fast variant for the live camera overlay (~5x less work): coarser image, fewer GrabCut
     * iterations, no full-resolution mask.
     */
    fun previewQuad(frame: Mat): Quad? {
        // a guide only (the photo is cropped by the full detection): small and without the colour
        // second opinion, ~2x faster - the phone also processes the earlier photos meanwhile
        val quad = detectDocument(frame, workSize = PREVIEW_WORK_SIZE, iterations = 2, withMask = false, seedColors = false).quad ?: return null
        return quad.takeIf { looksLikePaper(frame, it) }
    }

    const val PREVIEW_WORK_SIZE = 256 // measured on 60 photos: 186 instead of 356 ms, corners within 2 % on 25 of 33
    const val MIN_PAPER_CONTRAST = 15.0 // paper must be this much brighter (0-255) than its surroundings
    const val MIN_PAPER_BRIGHTNESS = 70.0

    /**
     * GrabCut always finds "something"; for the live overlay a candidate only counts if the
     * area inside is clearly brighter than outside (paper on a table), so a dark or uniform
     * frame (lens covered, pointing at the floor) shows no outline.
     */
    fun looksLikePaper(image: Mat, quad: Quad): Boolean = matScope {
        val (small, scale) = downscale(image, 200)
        val gray = mat()
        if (small.channels() == 3) Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY) else small.copyTo(gray)
        val inside = track(Mat.zeros(gray.size(), CvType.CV_8U))
        Imgproc.fillConvexPoly(inside, MatOfPoint(*quad.map { Point(it.x * scale, it.y * scale) }.toTypedArray()), Scalar(255.0))
        val outside = mat()
        Core.bitwise_not(inside, outside)
        val inMean = Core.mean(gray, inside).`val`[0]
        val outMean = if (Core.countNonZero(outside) > 0) Core.mean(gray, outside).`val`[0] else 0.0
        if (inMean < MIN_PAPER_BRIGHTNESS || inMean - outMean < MIN_PAPER_CONTRAST) return@matScope false
        // a lit patch of a wooden table is brighter than its surroundings too: require paper-like
        // pixels (bright, nearly colourless; yellowed paper only shifts Cb) over most of the area
        if (small.channels() != 3) return@matScope true
        val ycc = mat()
        Imgproc.cvtColor(small, ycc, Imgproc.COLOR_BGR2YCrCb)
        val paperLike = mat()
        Core.inRange(ycc, Scalar(PAPER_MIN_Y, 128.0 - PAPER_CR, 128.0 - PAPER_CB), Scalar(255.0, 128.0 + PAPER_CR, 128.0 + PAPER_CB), paperLike)
        Core.bitwise_and(paperLike, inside, paperLike)
        Core.countNonZero(paperLike) >= MIN_PAPER_SHARE * Core.countNonZero(inside)
    }

    const val PAPER_MIN_Y = 90.0
    const val PAPER_CR = 10.0 // wood and skin: Cr 140-180
    const val PAPER_CB = 25.0
    const val MIN_PAPER_SHARE = 0.45 // text, images and the gutter take the rest

    const val MODEL_CONFIDENCE = 0.7 // DocAligner fastvit_t8: single sheets 0.71-0.93, spreads always 0 on the user's series
    const val MIN_CORNER_ANGLE = 45.0

    /**
     * Page corners in image coordinates plus the full-resolution foreground mask (if segmentation
     * worked). The segmentation handles book spreads; the learned corner [model] (if given) only
     * steps in where it fails: nothing found, or an outline that does not enclose paper (a receipt
     * on a wooden table yields the whole table). Its 256-px corners are then snapped to the edges.
     */
    fun detectDocument(image: Mat, workSize: Int = WORK_SIZE, iterations: Int = 4, withMask: Boolean = true,
                       model: CornerModel? = null, seedColors: Boolean = true): Result {
        val classical = segmentationDetect(image, workSize, iterations, withMask, seedColors)
        if (model == null || (classical.quad != null && looksLikePaper(image, classical.quad))) return classical
        val learned = model.detect(image)?.takeIf { it.minConfidence >= MODEL_CONFIDENCE }
            ?.let { orderQuad(it.quad.toList()) }?.takeIf { plausibleQuad(it, image) } ?: return classical
        classical.close()
        return Result(snapEdges(image, learned), null)
    }

    /** Convex, corner angles between 45 and 135 degrees, and an area within the usual page bounds. */
    fun plausibleQuad(q: Quad, image: Mat): Boolean {
        val area = kotlin.math.abs((0 until 4).sumOf { i -> q[i].x * q[(i + 1) % 4].y - q[(i + 1) % 4].x * q[i].y }) / 2
        val ratio = area / image.total()
        if (ratio < MIN_AREA_RATIO / 3 || ratio > MAX_AREA_RATIO) return false
        var sign = 0.0
        for (i in 0 until 4) {
            val a = q[(i + 3) % 4]; val b = q[i]; val c = q[(i + 1) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (sign != 0.0 && cross * sign <= 0) return false
            sign = cross
            val u = Point(a.x - b.x, a.y - b.y); val v = Point(c.x - b.x, c.y - b.y)
            val cos = (u.x * v.x + u.y * v.y) / (kotlin.math.hypot(u.x, u.y) * kotlin.math.hypot(v.x, v.y))
            val angle = Math.toDegrees(kotlin.math.acos(cos.coerceIn(-1.0, 1.0)))
            if (angle < MIN_CORNER_ANGLE || angle > 180 - MIN_CORNER_ANGLE) return false
        }
        return true
    }

    const val SNAP_SIZE = 1600 // working resolution for the edge snap (model corners: ~1/256 of the image)
    const val SNAP_RANGE = 0.015 // search this share of the image diagonal on both sides of each edge
    const val SNAP_SAMPLES = 60
    const val SNAP_MIN_GRADIENT = 12.0
    const val SNAP_MAX_TILT = 3.0 // degrees a snapped edge may turn against the model's edge

    /**
     * Moves every edge of [quad] onto the strongest paper/background step within [SNAP_RANGE]:
     * samples across the edge, the step of the dominant polarity per sample (bright paper on a dark
     * table or a dark cover on a light one), a robust line fit, corners = intersections. An edge
     * without enough clear steps, or one that tilts too far, stays as it was.
     */
    fun snapEdges(image: Mat, quad: Quad): Quad = matScope {
        val (small, scale) = downscale(image, SNAP_SIZE)
        val gray = mat()
        if (small.channels() == 3) Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY) else small.copyTo(gray)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        val gx = mat(); val gy = mat()
        Imgproc.Sobel(gray, gx, CvType.CV_32F, 1, 0, 3, 0.125)
        Imgproc.Sobel(gray, gy, CvType.CV_32F, 0, 1, 3, 0.125)
        val w = gray.cols(); val h = gray.rows()
        val range = SNAP_RANGE * kotlin.math.hypot(w.toDouble(), h.toDouble())
        val q = quad.map { Point(it.x * scale, it.y * scale) }
        val px = FloatArray(1)
        fun grad(x: Double, y: Double, nx: Double, ny: Double): Double? {
            val ix = x.toInt(); val iy = y.toInt()
            if (ix < 0 || iy < 0 || ix >= w || iy >= h) return null
            gx.get(iy, ix, px); val a = px[0]
            gy.get(iy, ix, px)
            return a * nx + px[0] * ny
        }
        val lines = (0 until 4).map { i ->
            val a = q[i]; val b = q[(i + 1) % 4]
            val len = kotlin.math.hypot(b.x - a.x, b.y - a.y)
            val dx = (b.x - a.x) / len; val dy = (b.y - a.y) / len
            val nx = dy; val ny = -dx // outward for the clockwise order tl, tr, br, bl
            // per sample the strongest falling (paper brighter than outside) and rising step
            val falling = ArrayList<Pair<Point, Double>>(); val rising = ArrayList<Pair<Point, Double>>()
            for (k in 0 until SNAP_SAMPLES) {
                val t = 0.1 + 0.8 * k / (SNAP_SAMPLES - 1) // corners are often bent or covered
                val cx = a.x + (b.x - a.x) * t; val cy = a.y + (b.y - a.y) * t
                val n = (2 * range).toInt() + 1
                val profile = DoubleArray(n) { j -> grad(cx + nx * (j - range), cy + ny * (j - range), nx, ny) ?: 0.0 }
                outermostStep(profile, -1.0)?.let { j -> falling += Point(cx + nx * (j - range), cy + ny * (j - range)) to -profile[j] }
                outermostStep(profile, 1.0)?.let { j -> rising += Point(cx + nx * (j - range), cy + ny * (j - range)) to profile[j] }
            }
            val steps = if (falling.sumOf { it.second } >= rising.sumOf { it.second }) falling else rising
            if (steps.size < SNAP_SAMPLES / 3) return@map doubleArrayOf(a.x, a.y, dx, dy)
            val pts = track(MatOfPoint2f(*steps.map { it.first }.toTypedArray()))
            val fit = mat()
            Imgproc.fitLine(pts, fit, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val f = DoubleArray(4) { fit.get(it, 0)[0] }
            val tilt = Math.toDegrees(kotlin.math.acos(kotlin.math.abs(f[0] * dx + f[1] * dy).coerceAtMost(1.0)))
            if (tilt > SNAP_MAX_TILT) doubleArrayOf(a.x, a.y, dx, dy) else doubleArrayOf(f[2], f[3], f[0], f[1])
        }
        val corners = (0 until 4).map { i -> intersect(lines[(i + 3) % 4], lines[i]) }
        if (corners.any { it == null } || corners.indices.any { dist(corners[it]!!, q[it]) > 2 * range }) return@matScope quad
        corners.map { Point(it!!.x / scale, it.y / scale) }.toTypedArray()
    }

    const val SNAP_OUTER_SHARE = 0.5 // an outer step this strong (vs. the strongest) wins: book spine, print near the edge

    /**
     * Index of the page edge in a gradient [profile] running outwards: the outermost local
     * maximum of sign [polarity] that reaches [SNAP_OUTER_SHARE] of the strongest one, because
     * beyond the page edge there is only background, while inside there may be a spine or print.
     */
    private fun outermostStep(profile: DoubleArray, polarity: Double): Int? {
        val v = DoubleArray(profile.size) { profile[it] * polarity }
        val best = v.max()
        if (best < SNAP_MIN_GRADIENT) return null
        for (j in v.indices.reversed()) {
            val peak = (j == 0 || v[j] >= v[j - 1]) && (j == v.lastIndex || v[j] >= v[j + 1])
            if (peak && v[j] >= SNAP_OUTER_SHARE * best) return j
        }
        return null
    }

    const val MIN_CONTRAST = 16.0 // grey levels between the 1st and 99th percentile: below, the photo shows nothing

    /**
     * A photo without contrast (lens covered, dark room). GrabCut never returned on it (measured: a
     * black photo kept the app in "Seite erkennen" for minutes, at 100 % of one core), and the
     * enhancement turned its sensor noise into a grey speckled page.
     */
    fun lacksContrast(image: Mat): Boolean = matScope {
        val hist = histogram(track(Enhance.toGray(downscale(image, 400).first)))
        histPercentile(hist, 99.0) - histPercentile(hist, 1.0) < MIN_CONTRAST
    }

    private fun segmentationDetect(image: Mat, workSize: Int, iterations: Int, withMask: Boolean, seedColors: Boolean): Result = matScope {
        val (small0, scale) = downscale(image, workSize)
        val small = mat()
        Imgproc.GaussianBlur(small0, small, Size(5.0, 5.0), 0.0)
        if (lacksContrast(small)) return@matScope Result(null, null)

        val plain = pageOutline(small, track(segmentDocument(small, iterations)))
        // second opinion from the colours of paper and background (see colorSeed); it may only cut
        // background away (lap, shadow, table edge below a book), never lose or add page area
        val seeded = if (plain == null || !COLOR_SEED || !seedColors) null else colorSeed(small, grabCutFrame(small))?.let { seed ->
            pageOutline(small, track(segmentDocument(small, iterations, track(seed))))?.takeIf { trims(plain.second, it.second) }
        }
        val chosen = seeded ?: plain
        if (chosen != null) {
            val (mask, quad) = chosen
            if (!withMask) return@matScope Result(scaleQuad(quad, 1 / scale), null)
            val up = mat()
            Imgproc.resize(track(clipToQuad(mask, quad)), up, image.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            val full = Mat()
            Imgproc.threshold(up, full, 127.0, 255.0, Imgproc.THRESH_BINARY)
            return@matScope Result(scaleQuad(quad, 1 / scale), full)
        }
        val gray = mat()
        Imgproc.cvtColor(small, gray, Imgproc.COLOR_BGR2GRAY)
        Result(edgeQuad(gray)?.let { scaleQuad(it, 1 / scale) }, null)
    }

    /** Page mask and corners from a GrabCut segmentation (in the scope's lifetime), or null. */
    private fun MatScope.pageOutline(small: Mat, seg: Mat): Pair<Mat, Quad>? {
        val mask = pageComponent(small, seg)?.let { track(it) } ?: return null
        val ratio = Core.countNonZero(mask).toDouble() / mask.total()
        if (ratio <= MIN_AREA_RATIO || ratio >= MAX_AREA_RATIO) return null
        val quad = (ransacQuad(mask) ?: quadFromMask(track(withoutProtrusions(mask))))?.let { refineQuad(it, mask) } ?: return null
        return mask to quad
    }

    /** [inner] lies inside [outer] (1 % tolerance) and keeps at least [SEED_MIN_KEEP] of its area. */
    private fun trims(outer: Quad, inner: Quad): Boolean = matScope {
        fun area(q: Quad) = kotlin.math.abs((0 until 4).sumOf { i -> q[i].x * q[(i + 1) % 4].y - q[(i + 1) % 4].x * q[i].y }) / 2
        val a = area(outer)
        if (area(inner) < SEED_MIN_KEEP * a || area(inner) > a) return@matScope false
        val poly = track(MatOfPoint2f(*outer))
        val tol = 0.01 * kotlin.math.sqrt(a)
        inner.all { Imgproc.pointPolygonTest(poly, it, true) >= -tol }
    }

    private fun scaleQuad(q: Quad, f: Double): Quad = q.map { Point(it.x * f, it.y * f) }.toTypedArray()
}
