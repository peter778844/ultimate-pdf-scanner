package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Perspective correction: map the detected quad onto an upright rectangle. */
object Warp {
    private fun cross(a: DoubleArray, b: DoubleArray) =
        doubleArrayOf(a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]

    const val DEFAULT_FOCAL = 0.55 // focal length / image diagonal of a typical phone main camera (23-26 mm equivalent)

    /** 35 mm-equivalent focal length (EXIF FocalLengthIn35mmFilm) -> focal length / image diagonal. */
    fun focalFrom35mm(f35: Double?): Double = if (f35 != null && f35 > 0) f35 / 43.27 else DEFAULT_FOCAL

    /**
     * True width/height ratio of the photographed rectangle. Zhang & He, "Whiteboard scanning
     * and image enhancement" (2007), square pixels, principal point at the image centre. The
     * focal length is given ([focal] = f / image diagonal), not estimated: estimating it from
     * the four corners is ill-conditioned for the usual near top-down shots (measured: f came
     * out 3-7x the diagonal or negative, ratios off by 20 %), while with weak perspective an
     * approximate focal length barely affects the result.
     */
    fun pageAspectRatio(quad: Quad, width: Int, height: Int, focal: Double = DEFAULT_FOCAL): Double? {
        val (tl, tr, br, bl) = quad
        val m1 = doubleArrayOf(tl.x, tl.y, 1.0)
        val m2 = doubleArrayOf(tr.x, tr.y, 1.0)
        val m3 = doubleArrayOf(bl.x, bl.y, 1.0)
        val m4 = doubleArrayOf(br.x, br.y, 1.0)
        val k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3)
        val k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2)
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }
        val u0 = width / 2.0
        val v0 = height / 2.0

        if (abs(n2[2]) < 1e-9 || abs(n3[2]) < 1e-9) { // fronto-parallel: no perspective to undo
            return hypot(n2[0], n2[1]) / hypot(n3[0], n3[1])
        }
        val f = focal * hypot(width.toDouble(), height.toDouble())
        // n^T (A^-T A^-1) n with A = [[f,0,u0],[0,f,v0],[0,0,1]]
        fun norm(n: DoubleArray): Double {
            val x = (n[0] - u0 * n[2]) / f
            val y = (n[1] - v0 * n[2]) / f
            return x * x + y * y + n[2] * n[2]
        }
        val ratio2 = norm(n2) / norm(n3)
        return if (ratio2 > 0) sqrt(ratio2) else null
    }

    /**
     * Homography from the quad to an upright rectangle, and the output size. [margin]
     * (fraction of each side) pads the output so that edges bulging out of the quad –
     * curved book pages – stay inside the image.
     */
    fun perspectiveTransform(quad: Quad, width: Int, height: Int, margin: Double = 0.0, focal: Double = DEFAULT_FOCAL): Pair<Mat, Size> {
        val (tl, tr, br, bl) = quad
        fun dist(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)
        val w0 = max(dist(tr, tl), dist(br, bl))
        var h0 = max(dist(bl, tl), dist(br, tr))
        val ratio = pageAspectRatio(quad, width, height, focal)
        if (ratio != null && ratio > 0.2 && ratio < 5) h0 = w0 / ratio // keep the wider edge's resolution, fix the proportions
        val w = w0.roundToInt()
        val h = h0.roundToInt()
        val mx = (w * margin).roundToInt().toDouble()
        val my = (h * margin).roundToInt().toDouble()
        val dst = MatOfPoint2f(Point(mx, my), Point(w - 1 + mx, my), Point(w - 1 + mx, h - 1 + my), Point(mx, h - 1 + my))
        val src = MatOfPoint2f(*quad)
        val m = Imgproc.getPerspectiveTransform(src, dst)
        src.release()
        dst.release()
        return m to Size(w + 2 * mx, h + 2 * my)
    }

    fun warpQuad(image: Mat, quad: Quad, margin: Double = 0.0, focal: Double = DEFAULT_FOCAL): Mat {
        val (m, size) = perspectiveTransform(quad, image.cols(), image.rows(), margin, focal)
        val out = Mat()
        Imgproc.warpPerspective(image, out, m, size, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        m.release()
        return out
    }

    /** Warp image and mask with the same homography. */
    fun warpQuadWithMask(image: Mat, mask: Mat, quad: Quad, margin: Double = 0.0, focal: Double = DEFAULT_FOCAL): Pair<Mat, Mat> {
        val (m, size) = perspectiveTransform(quad, image.cols(), image.rows(), margin, focal)
        val out = Mat()
        Imgproc.warpPerspective(image, out, m, size, Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        val outMask = Mat()
        Imgproc.warpPerspective(mask, outMask, m, size, Imgproc.INTER_NEAREST, Core.BORDER_CONSTANT, Scalar(0.0))
        m.release()
        return out to outMask
    }
}
