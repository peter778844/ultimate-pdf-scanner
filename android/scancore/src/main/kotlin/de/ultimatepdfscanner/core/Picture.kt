package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Pictures and glossy magazine pages: colour-true rendering instead of the text treatment
 * (which flattens the paper to white and would bleach photos). Removes the printing screen
 * (halftone dots cause moiré), white-balances on the paper and stretches the contrast gently,
 * equally in all channels so hues stay as printed.
 */
object Picture {
    const val SCREEN_PROBE = 512 // FFT window (px) for finding the printing screen
    const val MIN_PERIOD = 2.5 // screen period (px) range that is looked for
    const val MAX_PERIOD = 14.0
    const val SCREEN_PEAK = 20.0 // spectral peak vs the picture's own (low-frequency) level: printed screen ~350, smooth photo ~2
    const val BLUR_PER_PERIOD = 0.5 // Gaussian sigma per period: the screen frequency drops below 1 %
    const val RESHARPEN = 0.5 // unsharp amount after descreening, at twice the blur radius
    const val WHITE_BALANCE_LIMIT = 0.15 // at most +-15 % per channel
    const val CONTRAST_CLIP = 0.3 // percent clipped at both ends of the luminance

    /** Colour-true page for pictures: descreened, white-balanced, contrast gently stretched. */
    fun render(page: Mat): Mat = matScope {
        val clean = screenPeriod(page)?.let { track(descreen(page, it)) } ?: page
        val balanced = track(whiteBalance(clean))
        stretch(balanced)
    }

    /**
     * Period (px) of a halftone screen in the middle of the page, or null if there is none: the
     * strongest peak of the magnitude spectrum at the frequencies of a printing screen.
     */
    fun screenPeriod(page: Mat): Double? = screenPeak(page)?.takeIf { it.second >= SCREEN_PEAK }?.first

    /** Period (px) and strength (peak / typical spectral level) of the strongest screen-like peak. */
    fun screenPeak(page: Mat): Pair<Double, Double>? = matScope {
        val gray = track(Enhance.toGray(page))
        val n = min(SCREEN_PROBE, min(gray.rows(), gray.cols()))
        if (n < 128) return@matScope null
        val crop = gray.submat(Rect((gray.cols() - n) / 2, (gray.rows() - n) / 2, n, n))
        val f = mat().also { crop.convertTo(it, CvType.CV_32F) }
        crop.release()
        Core.subtract(f, Core.mean(f), f)
        val window = mat().also { Imgproc.createHanningWindow(it, Size(n.toDouble(), n.toDouble()), CvType.CV_32F) }
        Core.multiply(f, window, f)
        val spec = mat()
        Core.dft(f, spec, Core.DFT_COMPLEX_OUTPUT)
        val planes = ArrayList<Mat>()
        Core.split(spec, planes)
        planes.forEach { track(it) }
        val mag = mat()
        Core.magnitude(planes[0], planes[1], mag)
        val values = FloatArray(n * n).also { mag.get(0, 0, it) }
        // frequencies of a screen: radius n/MAX_PERIOD .. n/MIN_PERIOD (spectrum not shifted: wrap around)
        val rMin = n / MAX_PERIOD; val rMax = n / MIN_PERIOD
        // the picture's own content lives at low frequencies (below the screen band); blurring away a
        // screen hardly touches it, so it is the reference for how strong a peak is
        val content = ArrayList<Float>()
        var best = 0f; var bestR = 0.0
        for (y in 0 until n) for (x in 0 until n) {
            val fy = if (y > n / 2) y - n else y
            val fx = if (x > n / 2) x - n else x
            val r = hypot(fx.toDouble(), fy.toDouble())
            val v = values[y * n + x]
            if (r >= 2 && r < rMin) content += v
            if (r < rMin || r > rMax) continue
            if (v > best) { best = v; bestR = r }
        }
        if (content.isEmpty()) return@matScope null
        content.sort()
        val typical = content[content.size / 2]
        if (typical <= 0f) null else n / bestR to (best / typical).toDouble()
    }

    /** Removes a screen of the given period and gives back some of the lost crispness. */
    fun descreen(page: Mat, period: Double): Mat = matScope {
        val sigma = BLUR_PER_PERIOD * period
        val smooth = mat()
        Imgproc.GaussianBlur(page, smooth, Size(0.0, 0.0), sigma)
        val wide = mat()
        Imgproc.GaussianBlur(smooth, wide, Size(0.0, 0.0), 2 * sigma)
        Mat().also { Core.addWeighted(smooth, 1 + RESHARPEN, wide, -RESHARPEN, 0.0, it) }
    }

    /**
     * White balance on the paper: the brightest nearly colourless pixels (page margins) should be
     * grey. A full-bleed photo without paper keeps its colours.
     */
    fun whiteBalance(page: Mat): Mat = matScope {
        if (page.channels() != 3) return@matScope page.clone()
        val (small, _) = downscale(page, 600)
        val gray = track(Enhance.toGray(small))
        val bright = histPercentile(histogram(gray), 95.0)
        val mask = mat()
        Imgproc.threshold(gray, mask, bright - 1, 255.0, Imgproc.THRESH_BINARY)
        mask.convertTo(mask, CvType.CV_8U)
        if (Core.countNonZero(mask) < 0.01 * mask.total()) return@matScope page.clone()
        val mean = Core.mean(small, mask).`val`
        val grey = (mean[0] + mean[1] + mean[2]) / 3
        // paper-like only: a strongly tinted bright area is part of the picture
        if ((0 until 3).any { kotlin.math.abs(mean[it] - grey) > 0.2 * grey }) return@matScope page.clone()
        val gains = DoubleArray(3) { (grey / max(1.0, mean[it])).coerceIn(1 - WHITE_BALANCE_LIMIT, 1 + WHITE_BALANCE_LIMIT) }
        Mat().also { Core.multiply(page, Scalar(gains[0], gains[1], gains[2]), it) }
    }

    /** Contrast stretch of the luminance range, the same factor for all channels (hues unchanged). */
    fun stretch(page: Mat): Mat = matScope {
        val (small, _) = downscale(page, 600)
        val hist = histogram(track(Enhance.toGray(small)))
        val lo = histPercentile(hist, CONTRAST_CLIP)
        val hi = histPercentile(hist, 100 - CONTRAST_CLIP)
        if (hi - lo < 40) return@matScope page.clone() // flat picture: leave it
        val scale = 255.0 / (hi - lo)
        Mat().also { page.convertTo(it, -1, scale, -lo * scale) }
    }
}
