package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OpenCV Mats hold native memory that the JVM garbage collector does not see; on a phone
 * a few leaked 12 MP images exhaust memory. Every temporary Mat is created through a
 * [MatScope] and released when the scope ends.
 */
class MatScope : AutoCloseable {
    private val mats = ArrayList<Mat>()

    fun <T : Mat> track(mat: T): T = mat.also { mats += it }

    fun mat(): Mat = track(Mat())

    /** Keep [mat] alive beyond this scope (the caller becomes responsible for releasing it). */
    fun <T : Mat> keep(mat: T): T = mat.also { mats.remove(it) }

    override fun close() {
        mats.forEach { it.release() }
        mats.clear()
    }
}

inline fun <R> matScope(block: MatScope.() -> R): R = MatScope().use { it.block() }

/** Python's `n | 1`: the next odd number >= n. */
internal fun odd(n: Int): Int = n or 1

internal fun ones(k: Int): Mat = Mat.ones(k, k, CvType.CV_8U)

internal fun MatScope.kernel(k: Int): Mat = track(ones(k))

/** 256-bin histogram of an 8-bit single-channel image (optionally restricted by a mask). */
internal fun histogram(gray: Mat, mask: Mat? = null): LongArray = matScope {
    val hist = mat()
    Imgproc.calcHist(listOf(gray), MatOfInt(0), mask ?: Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
    val values = FloatArray(256)
    hist.get(0, 0, values)
    LongArray(256) { values[it].toLong() }
}

/** numpy-style percentile (linear interpolation) of the values from..to of a histogram. */
internal fun histPercentile(hist: LongArray, p: Double, from: Int = 0, to: Int = 255): Double {
    val n = (from..to).sumOf { hist[it] }
    if (n == 0L) return Double.NaN
    // value of the k-th smallest sample (0-based)
    fun valueAt(k: Long): Int {
        var seen = 0L
        for (v in from..to) {
            seen += hist[v]
            if (k < seen) return v
        }
        return to
    }
    val rank = p / 100.0 * (n - 1)
    val i = rank.toLong()
    val lo = valueAt(i)
    val hi = if (i + 1 < n) valueAt(i + 1) else lo
    return lo + (rank - i) * (hi - lo)
}

internal fun bytes(m: Mat): ByteArray = ByteArray((m.total() * m.channels()).toInt()).also { m.get(0, 0, it) }

internal fun floats(m: Mat): FloatArray = FloatArray((m.total() * m.channels()).toInt()).also { m.get(0, 0, it) }

internal fun median(values: DoubleArray): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sortedArray()
    val m = s.size / 2
    return if (s.size % 2 == 1) s[m] else 0.5 * (s[m - 1] + s[m])
}

internal fun median(values: FloatArray): Double = median(DoubleArray(values.size) { values[it].toDouble() })

/**
 * Imgproc.resize (INTER_LINEAR) with a spare row below the source. OpenCV's ARM build reads up to
 * 32 bytes past the end of the source when shrinking 3-channel images (seen on the phone: PaddleOCR's
 * 1547x2369 -> 1536x2368), a SIGSEGV whenever the buffer ends just before an unmapped memory page.
 */
internal fun resizeLinear(src: Mat, dst: Mat, size: Size) = matScope {
    val padded = mat()
    Core.copyMakeBorder(src, padded, 0, 1, 0, 0, Core.BORDER_REPLICATE)
    Imgproc.resize(track(padded.submat(0, src.rows(), 0, src.cols())), dst, size)
}

/** Resize so that the longer side is at most [maxSide] (never upscales). Returns the scale used. */
internal fun MatScope.downscale(image: Mat, maxSide: Int): Pair<Mat, Double> {
    val scale = min(1.0, maxSide.toDouble() / max(image.rows(), image.cols()))
    val out = mat()
    Imgproc.resize(image, out, Size((image.cols() * scale).roundToInt().toDouble(), (image.rows() * scale).roundToInt().toDouble()),
        0.0, 0.0, Imgproc.INTER_AREA)
    return out to scale
}
