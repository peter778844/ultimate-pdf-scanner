package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Border cleanup for book pages: strips of the neighbouring page beyond the fold and the stacked
 * page edges of the book block. Along each border the paper brightness (a high percentile per
 * line, so print does not count) is followed inwards; a clear shadow trough followed by normal
 * paper marks the real page edge, and everything outside it is painted in the paper colour.
 * Works strip by strip along the border, because a fold or a book edge is rarely straight.
 */
object Borders {
    const val WORK_SIZE = 800 // analysis resolution (longer side)
    const val BAND = 0.16 // searched depth from each border, as share of the page size across it (gutter of a thick book: ~12 %)
    const val STRIPS = 12 // pieces along each border
    const val PAPER_PERCENTILE = 80.0 // per line: the paper, not the print
    const val MIN_DEPTH = 0.15 // trough at least this much darker than the paper on its inner side ...
    const val MIN_INNER_PAPER = 0.6 // ... where there is paper (vs. the whole page), not a dark cover

    /**
     * The cleaned page (a new Mat) and the share of border strips that were painted. Only beyond the
     * [fold] (the side where a spread was cut) may a painted strip hold text (the neighbouring page);
     * elsewhere strips with letters stay: a running footer in the shade at the bottom of a page was
     * painted over. [sharpFold]: the cut lies on the gutter's shadow line, so beyond it is nothing of
     * the other page; only a thin dark strip right at the cut is painted there, no trough further in
     * (measured: the curled, shaded gutter side of a Meinke page lost "derart", "Komple-" and an
     * equation number, whose faint letters the letter check does not see).
     */
    fun clean(page: Mat, fold: Outline.Side? = null, sharpFold: Boolean = false): Pair<Mat, Double> = matScope {
        val (small, scale) = downscale(page, WORK_SIZE)
        val gray = track(Enhance.toGray(small))
        val paperRef = histPercentile(histogram(gray), 70.0)
        if (paperRef < 60) return@matScope page.clone() to 0.0 // dark page or cover: nothing to measure
        val paint = track(Mat.zeros(page.size(), org.opencv.core.CvType.CV_8U))
        val h = gray.rows(); val w = gray.cols()
        val data = ByteArray(h * w).also { gray.get(0, 0, it) }
        // letters: dark marks of letter size (median of the page) in a locally thresholded copy
        val ink = mat()
        Imgproc.adaptiveThreshold(gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV, 25, 15.0)
        val letter = TextSize.charHeight(gray)
        fun hasLetters(r: Rect): Boolean {
            val lh = letter ?: return false
            val stats = mat()
            val n = Imgproc.connectedComponentsWithStats(track(ink.submat(r)), mat(), stats, mat(), 8, org.opencv.core.CvType.CV_32S)
            var count = 0
            for (i in 1 until n) {
                val ch = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0]; val cw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0]
                if (ch in 0.5 * lh..2.0 * lh && cw <= 3 * ch && cw >= 1) count++
            }
            return count >= MIN_LETTERS
        }
        fun px(x: Int, y: Int) = data[y * w + x].toInt() and 0xff
        var painted = 0
        // side 0: top, 1: bottom, 2: left, 3: right
        for (side in 0 until 4) {
            val alongRows = side >= 2 // left/right: the border runs along the rows
            val length = if (alongRows) h else w
            val across = if (alongRows) w else h
            val band = max(4, (across * BAND).roundToInt())
            for (s in 0 until STRIPS) {
                val from = s * length / STRIPS
                val to = (s + 1) * length / STRIPS
                // paper brightness of each line parallel to the border, from the edge inwards
                val profile = DoubleArray(band) { d ->
                    val line = IntArray(to - from) { i ->
                        val a = from + i
                        when (side) {
                            0 -> px(a, d); 1 -> px(a, h - 1 - d); 2 -> px(d, a); else -> px(w - 1 - d, a)
                        }
                    }
                    line.sort()
                    line[min(line.size - 1, (line.size * PAPER_PERCENTILE / 100).toInt())].toDouble()
                }
                val smooth = boxSmooth(profile, max(1, band / 12))
                val edge = troughEdge(smooth, paperRef) ?: continue
                val isFold = fold != null && fold.ordinal == side // Side order: TOP, BOTTOM, LEFT, RIGHT = sides 0..3
                if (!isFold) {
                    val d = edge + 1
                    val small = when (side) {
                        0 -> Rect(from, 0, to - from, d); 1 -> Rect(from, h - d, to - from, d)
                        2 -> Rect(0, from, d, to - from); else -> Rect(w - d, from, d, to - from)
                    }
                    if (hasLetters(small)) continue
                } else if (sharpFold && smooth.indices.minBy { smooth[it] } > 0) continue // a trough, not the strip at the cut
                painted++
                // paint [0, edge] of this strip at full resolution
                val depth = ((edge + 1) / scale).roundToInt()
                val a0 = (from / scale).roundToInt(); val a1 = min(if (alongRows) page.rows() else page.cols(), (to / scale).roundToInt())
                val r = when (side) {
                    0 -> Rect(a0, 0, a1 - a0, depth)
                    1 -> Rect(a0, page.rows() - depth, a1 - a0, depth)
                    2 -> Rect(0, a0, depth, a1 - a0)
                    else -> Rect(page.cols() - depth, a0, depth, a1 - a0)
                }
                paint.submat(r).setTo(Scalar(255.0))
            }
            // the face of a thick book block: many thin lines parallel to this border, from the edge on
            val isFold = fold != null && fold.ordinal == side
            if (!isFold) blockFace(gray, side, length, across)?.let { d ->
                val r = when (side) {
                    0 -> Rect(0, 0, w, d); 1 -> Rect(0, h - d, w, d); 2 -> Rect(0, 0, d, h); else -> Rect(w - d, 0, d, h)
                }
                // (no letter check: the fine stripes break up into letter-sized marks; the direction
                // test already excludes text, which has steps along the lines too)
                run {
                    painted += STRIPS
                    val depth = min((d / scale).roundToInt(), if (alongRows) page.cols() else page.rows())
                    val full = when (side) {
                        0 -> Rect(0, 0, page.cols(), depth); 1 -> Rect(0, page.rows() - depth, page.cols(), depth)
                        2 -> Rect(0, 0, depth, page.rows()); else -> Rect(page.cols() - depth, 0, depth, page.rows())
                    }
                    paint.submat(full).setTo(Scalar(255.0))
                }
            }
        }
        // painted with the paper as it would look there (no grey blocks where the light varies)
        val out = if (painted == 0) page.clone() else PaperFill.fill(page, paint, max(2.0, 0.004 * min(page.rows(), page.cols())))
        out to painted.toDouble() / (4 * STRIPS)
    }

    const val FACE_BAND = 0.35 // the block face of a thick book is searched this deep (share of the page across)
    const val FACE_MIN_GRADIENT = 1.5 // mean grey step across the border direction on a face line (faint stripes: 2-3)
    const val FACE_ANISOTROPY = 2.2 // ... at least this many times the step along it (plain paper and text: ~1)
    const val FACE_START = 0.03 // the face begins this close to the border ...
    const val FACE_MIN_DEPTH = 0.03 // ... and is at least this deep (share of the page across)
    const val FACE_GAP = 0.03 // interruptions of the stripe pattern are bridged

    /**
     * Depth (in the analysis image) of the face of a thick book block along [side], or null. The
     * camera above a thick book sees the stacked page edges next to the page: a dense pattern of
     * thin stripes parallel to the border (the user's Hochfrequenz book: about 30 % of the page).
     * The stripes are too fine and faint to be single lines after thresholding, so the measure is
     * the direction of the grey steps: on a face line they run across the border (one stripe after
     * the other) and hardly along it; text has steps in both directions, plain paper in neither.
     */
    private fun MatScope.blockFace(gray: Mat, side: Int, length: Int, across: Int): Int? {
        val h = gray.rows(); val w = gray.cols()
        val gx = mat(); val gy = mat()
        Imgproc.Sobel(gray, gx, org.opencv.core.CvType.CV_32F, 1, 0, 3, 0.25)
        Imgproc.Sobel(gray, gy, org.opencv.core.CvType.CV_32F, 0, 1, 3, 0.25)
        Core.absdiff(gx, Scalar(0.0), gx); Core.absdiff(gy, Scalar(0.0), gy)
        val fx = floats(gx); val fy = floats(gy)
        val band = max(4, (across * FACE_BAND).roundToInt())
        val a0 = length / 10; val a1 = length - length / 10 // the middle 80 % (corners are bent or covered)
        fun isFace(d: Int): Boolean {
            var perp = 0.0; var along = 0.0
            for (a in a0 until a1) {
                val (x, y) = when (side) { 0 -> a to d; 1 -> a to (h - 1 - d); 2 -> d to a; else -> (w - 1 - d) to a }
                val i = y * w + x
                // left/right border: the stripes are vertical, their steps run along x
                if (side >= 2) { perp += fx[i]; along += fy[i] } else { perp += fy[i]; along += fx[i] }
            }
            val n = (a1 - a0).coerceAtLeast(1)
            return perp / n >= FACE_MIN_GRADIENT && perp >= FACE_ANISOTROPY * along
        }
        val raw = BooleanArray(band) { isFace(it) }
        // majority over 5 lines: single lines of the pattern may fall just short
        val face = BooleanArray(band) { d -> (max(0, d - 2)..min(band - 1, d + 2)).count { raw[it] } >= 3 }
        val first = face.indexOfFirst { it }
        if (first < 0 || first > FACE_START * across) return null
        val gap = max(2, (FACE_GAP * across).roundToInt())
        var end = first; var miss = 0
        for (d in first until band) {
            if (face[d]) { end = d; miss = 0 } else if (++miss > gap) break
        }
        if (Pipeline.DEBUG) println("FACE side=$side first=$first end=$end band=$band")
        if (end - first < FACE_MIN_DEPTH * across) return null
        return min(band, end + 2)
    }

    /**
     * Index of the outermost line to paint: the darkest point of a trough that is clearly darker
     * than the paper further in. Null if the border is clean.
     */
    internal fun troughEdge(profile: DoubleArray, paperRef: Double): Int? {
        val v = profile.indices.minBy { profile[it] }
        if (v >= profile.size - 2) return null // no room for paper inside
        // compared with the paper right inside it: the gutter side of a page is often in shadow as a whole
        val inner = profile.copyOfRange(v + 1, profile.size).max()
        if (inner < MIN_INNER_PAPER * paperRef) return null
        if ((inner - profile[v]) / inner < MIN_DEPTH) return null
        if (v > 0) return v
        // darkest at the very border: a thin strip of table or book edge along the cut is painted up to
        // where the paper is back; a broad shadow (the gutter side in shade, maybe with text in it) is not
        var e = 0
        while (e + 1 < profile.size - 2 && (inner - profile[e + 1]) / inner >= MIN_DEPTH) e++
        return e.takeIf { it < profile.size * EDGE_STRIP }
    }

    const val MIN_LETTERS = 3 // this many letter-sized marks make a strip text, which is kept

    const val EDGE_STRIP = 0.125 // a dark strip at the very border is at most this share of the searched band (2 % of the page)

    private fun boxSmooth(v: DoubleArray, r: Int) = DoubleArray(v.size) { i ->
        val a = max(0, i - r); val b = min(v.size - 1, i + r)
        (a..b).sumOf { v[it] } / (b - a + 1)
    }

}
