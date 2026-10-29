package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import org.opencv.photo.Photo
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Finger removal: skin-coloured regions that reach into the page from its border are
 * replaced by the surrounding paper (fingers holding a magazine flat).
 */
object Fingers {
    const val MIN_AREA = 0.0005 // share of the page; smaller skin-coloured specks are ignored
    const val MAX_AREA = 0.15 // larger regions are more likely a coloured page than a finger
    // Shape: a finger reaches into the page and is compact. Table edges and the brownish gutter
    // shadow are thin strips along the border (measured length/depth 10-63) or sparse specks.
    const val MIN_DEPTH = 0.03 // reach into the page, as share of the page size perpendicular to the border
    const val MAX_ELONGATION = 3.0 // extent along the border / depth into the page
    const val MIN_FILL = 0.35 // area / bounding-box area

    /** Skin colour in YCrCb (robust to brightness). Aged, yellowish paper has Cr <= ~135. */
    fun skinMask(image: Mat): Mat = matScope {
        val ycc = mat()
        Imgproc.cvtColor(image, ycc, Imgproc.COLOR_BGR2YCrCb)
        val mask = mat()
        Core.inRange(ycc, Scalar(51.0, 140.0, 77.0), Scalar(255.0, 180.0, 127.0), mask)
        val k = max(3, odd(min(image.rows(), image.cols()) / 200))
        val opened = mat()
        Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, kernel(k))
        Mat().also { Imgproc.morphologyEx(opened, it, Imgproc.MORPH_CLOSE, kernel(3 * k)) }
    }

    /** Number of lines along a border strip that contain component pixels. */
    private fun contact(region: Mat, rows: IntRange, cols: IntRange, alongRows: Boolean): Int = matScope {
        val strip = region.submat(rows.first, rows.last + 1, cols.first, cols.last + 1)
        val reduced = mat()
        Core.reduce(strip, reduced, if (alongRows) 1 else 0, Core.REDUCE_MAX)
        strip.release()
        Core.countNonZero(reduced)
    }

    private fun looksLikeFinger(region: Mat, x: Int, y: Int, bw: Int, bh: Int, area: Int, border: Int): Boolean {
        val h = region.rows()
        val w = region.cols()
        if (area < MIN_AREA * h * w || area > MAX_AREA * h * w || area < MIN_FILL * bw * bh) return false
        // (contact length, depth into the page, extent along the border, page size across); judged from the border it rests on most
        val sides = listOf(
            arrayOf(contact(region, 0 until h, 0 until border, true), bw, bh, w),
            arrayOf(contact(region, 0 until h, w - border until w, true), bw, bh, w),
            arrayOf(contact(region, 0 until border, 0 until w, false), bh, bw, h),
            arrayOf(contact(region, h - border until h, 0 until w, false), bh, bw, h),
        )
        val (c, depth, along, size) = sides.maxBy { it[0] }
        return c > 0 && depth >= MIN_DEPTH * size && along <= MAX_ELONGATION * depth
    }

    /** Skin regions of plausible size and shape that reach in from the page border. */
    fun fingerMask(image: Mat): Mat = matScope {
        val h = image.rows()
        val w = image.cols()
        val labels = mat()
        val stats = mat()
        val n = Imgproc.connectedComponentsWithStats(track(skinMask(image)), labels, stats, mat())
        val out = Mat.zeros(h, w, CvType.CV_8U)
        val border = max(2, min(h, w) / 100)
        for (i in 1 until n) {
            val area = stats.get(i, Imgproc.CC_STAT_AREA)[0].toInt()
            if (area < MIN_AREA * h * w) continue // cheap reject before building the component mask
            val region = mat()
            Core.compare(labels, Scalar(i.toDouble()), region, Core.CMP_EQ)
            val x = stats.get(i, Imgproc.CC_STAT_LEFT)[0].toInt()
            val y = stats.get(i, Imgproc.CC_STAT_TOP)[0].toInt()
            val bw = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            val bh = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            if (looksLikeFinger(region, x, y, bw, bh, area, border)) out.setTo(Scalar(255.0), region)
        }
        out
    }

    /**
     * Returns (cleaned image, removed share of the page); the input is returned unchanged if
     * nothing was found. The finger (with its shadow) is replaced by the paper as it would look
     * there: the page's illumination field, estimated without the finger, so the patch carries
     * on any shading of the surroundings. Inpainting the photo itself drew the neighbouring text
     * as streaks into the patch; whatever the finger covered cannot be recovered anyway.
     */
    fun removeFingers(image: Mat): Pair<Mat, Double> = matScope {
        if (image.channels() != 3) return@matScope image to 0.0
        val mask = track(fingerMask(image))
        val share = Core.countNonZero(mask).toDouble() / mask.total()
        if (share == 0.0) return@matScope image to 0.0
        val k = max(5, odd(min(image.rows(), image.cols()) / 150))
        val grown = mat()
        Imgproc.dilate(mask, grown, kernel(k)) // the finger's shadow and outline
        PaperFill.fill(image, grown, max(3.0, k / 2.0)) to share
    }
}

/**
 * Replaces regions of a page (fingers, strips at the border) by the paper as it would look there:
 * the page's illumination field, estimated at a reduced size without the region (filled in
 * roughly first, then the print removed by the background estimate), so the patch carries on the
 * shading of its surroundings and shows no seams. Inpainting the photo itself drew neighbouring
 * text as streaks into the patch, and a constant colour showed as grey blocks.
 */
object PaperFill {
    const val PAPER_SIZE = 800 // working size of the paper field (it is smooth)

    /** A new image: [mask] (8-bit, != 0) replaced by paper, with a soft edge of [feather] px just outside it. */
    fun fill(image: Mat, mask: Mat, feather: Double): Mat = matScope {
        val (small, _) = downscale(image, PAPER_SIZE)
        val smallMask = mat()
        Imgproc.resize(mask, smallMask, small.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
        Imgproc.dilate(smallMask, smallMask, kernel(3))
        val rough = mat()
        Photo.inpaint(small, smallMask, rough, 5.0, Photo.INPAINT_TELEA)
        val paper = track(Enhance.flattenBackground(rough))
        val paperFull = mat()
        Imgproc.resize(paper, paperFull, image.size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
        val hard = mat(); mask.convertTo(hard, CvType.CV_32F, 1.0 / 255)
        Core.min(hard, Scalar(1.0), hard)
        val alpha = mat()
        Imgproc.GaussianBlur(hard, alpha, Size(0.0, 0.0), feather)
        Core.max(alpha, hard, alpha)
        val a = mat(); if (image.channels() == 3) Core.merge(listOf(alpha, alpha, alpha), a) else alpha.copyTo(a)
        val img32 = mat(); image.convertTo(img32, CvType.CV_32F)
        val pap32 = mat(); paperFull.convertTo(pap32, CvType.CV_32F)
        // out = img + alpha * (paper - img)
        val diff = mat(); Core.subtract(pap32, img32, diff)
        Core.multiply(diff, a, diff)
        Core.add(img32, diff, img32)
        Mat().also { img32.convertTo(it, image.type()) }
    }
}

/**
 * Deblurring of slightly out-of-focus pages. The blur is estimated from the text edges: a step
 * edge of contrast A blurred by a Gaussian of width sigma has a maximum gradient of
 * A / (sigma * sqrt(2 pi)). If the page is blurry, Richardson-Lucy deconvolution with that
 * Gaussian sharpens it again. Motion blur is not modelled; such photos should be retaken.
 */
object Deblur {
    const val BLUR_SIGMA = 1.4 // pages with an estimated blur above this (in pixels) are deconvolved
    const val ITERATIONS = 15

    /** Estimated Gaussian blur sigma in pixels (sharp phone photos: ~0.7-1.2). */
    fun estimateBlur(image: Mat): Double = matScope {
        val gray = track(Enhance.toGray(image))
        val flat = track(Enhance.flatten(gray))
        val (black, white) = Enhance.levels(flat)
        val contrast = white - black
        val f = mat().also { flat.convertTo(it, CvType.CV_32F) }
        val gx = mat()
        val gy = mat()
        Imgproc.Sobel(f, gx, CvType.CV_32F, 1, 0, 3, 1.0 / 8.0)
        Imgproc.Sobel(f, gy, CvType.CV_32F, 0, 1, 3, 1.0 / 8.0)
        val grad = mat()
        Core.magnitude(gx, gy, grad)
        val g = floatPercentile(grad, 99.5)
        if (g <= 0 || contrast <= 0) 0.0 else contrast / (g * sqrt(2 * PI))
    }

    /** Percentile of a float image via a fine histogram (exact enough for a blur estimate). */
    private fun floatPercentile(m: Mat, p: Double): Double {
        val v = floats(m)
        var maxV = 0f
        for (x in v) if (x > maxV) maxV = x
        if (maxV <= 0f) return 0.0
        val bins = 8192
        val hist = LongArray(bins)
        for (x in v) hist[min(bins - 1, (x / maxV * (bins - 1)).toInt())]++
        val target = p / 100.0 * (v.size - 1)
        var seen = 0L
        for (b in 0 until bins) {
            seen += hist[b]
            if (seen > target) return (b + 0.5) / (bins - 1) * maxV
        }
        return maxV.toDouble()
    }

    /** Richardson-Lucy deconvolution for a Gaussian PSF (symmetric, so no flipped kernel). */
    fun richardsonLucy(plane: Mat, sigma: Double, iterations: Int = ITERATIONS): Mat = matScope {
        val img = mat()
        plane.convertTo(img, CvType.CV_32F, 1.0, 1.0)
        val est = img.clone().also { track(it) }
        val conv = mat()
        val ratio = mat()
        val back = mat()
        repeat(iterations) {
            Imgproc.GaussianBlur(est, conv, Size(0.0, 0.0), sigma)
            Core.max(conv, Scalar(1e-3), conv)
            Core.divide(img, conv, ratio)
            Imgproc.GaussianBlur(ratio, back, Size(0.0, 0.0), sigma)
            Core.multiply(est, back, est)
        }
        Core.subtract(est, Scalar(1.0), est)
        Mat().also { est.convertTo(it, CvType.CV_8U) } // saturating: clips to 0..255
    }

    /** Returns (image, estimated sigma); the input instance is returned if it is not blurry. */
    fun deblur(image: Mat, threshold: Double = BLUR_SIGMA): Pair<Mat, Double> {
        val sigma = estimateBlur(image)
        if (sigma <= threshold) return image to sigma
        val psf = 0.8 * sigma // slightly under-estimate: an over-sized PSF causes ringing
        return matScope {
            val planes = ArrayList<Mat>()
            Core.split(image, planes)
            planes.forEach { track(it) }
            val out = planes.map { track(richardsonLucy(it, psf)) }
            val merged = Mat()
            if (out.size == 1) out[0].copyTo(merged) else Core.merge(out, merged)
            merged to sigma
        }
    }
}

/**
 * Handwriting removal on a flattened page (paper ~white). Removes what can be told apart from
 * black print without a learned model: blue/violet/red pen strokes (by hue) and pencil (grey
 * strokes not attached to dark print). Black ballpoint needs a segmentation model and is not
 * handled. Off by default, because coloured print in these hues is removed too.
 */
object Handwriting {
    val PEN_HUES = listOf(100.0 to 160.0, 160.0 to 180.0, 0.0 to 8.0) // OpenCV hue (0-180): blue/violet, red (wraps)
    const val MIN_SATURATION = 70.0
    const val PENCIL_CORE = 0.45 // pixels darker than this (0 = ink, 1 = paper) count as print

    fun penMask(flat: Mat): Mat = matScope {
        if (flat.channels() != 3) return@matScope Mat.zeros(flat.size(), CvType.CV_8U)
        val hsv = mat()
        Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_BGR2HSV)
        val out = Mat.zeros(flat.size(), CvType.CV_8U)
        val part = mat()
        // no brightness limit: paper is unsaturated anyway, and light ink must still count
        for ((lo, hi) in PEN_HUES) {
            Core.inRange(hsv, Scalar(lo, MIN_SATURATION, 0.0), Scalar(hi, 255.0, 255.0), part)
            Core.bitwise_or(out, part, out)
        }
        out
    }

    /**
     * Mid-grey pixels that are not the anti-aliased rim of dark print. Works per pixel, so a
     * pencil line crossing printed text is removed between the letters.
     */
    fun pencilMask(flat: Mat): Mat = matScope {
        val gray = track(Enhance.toGray(flat))
        val (black, white) = Enhance.levels(gray)
        val span = max(white - black, 1.0)
        val core = mat()
        Core.compare(gray, Scalar(black + PENCIL_CORE * span), core, Core.CMP_LE)
        val k = max(3, odd(min(gray.rows(), gray.cols()) / 400)) // rim width of printed glyphs
        val printZone = mat()
        Imgproc.dilate(core, printZone, kernel(2 * k + 1))
        val mid = mat()
        Core.inRange(gray, Scalar(black + PENCIL_CORE * span + 1e-6), Scalar(black + 0.85 * span - 1e-6), mid)
        val notPrint = mat()
        Core.bitwise_not(printZone, notPrint)
        Core.bitwise_and(mid, notPrint, mid)
        // drop paper-grain specks smaller than 15 px
        keepComponents(mid, minArea = 15)
    }

    /** Mask of the 8-connected components of [mask] with at least [minArea] pixels (one pass over the labels). */
    private fun keepComponents(mask: Mat, minArea: Int): Mat = matScope {
        val labels = mat()
        val stats = mat()
        val n = Imgproc.connectedComponentsWithStats(mask, labels, stats, mat(), 8, CvType.CV_32S)
        val keep = BooleanArray(n) { it > 0 && stats.get(it, Imgproc.CC_STAT_AREA)[0] >= minArea }
        val lab = IntArray(labels.total().toInt()).also { labels.get(0, 0, it) }
        val px = ByteArray(lab.size) { if (keep[lab[it]]) 255.toByte() else 0 }
        Mat(mask.size(), CvType.CV_8U).also { it.put(0, 0, px) }
    }

    /** Returns (cleaned flattened page, removed share of the page). */
    fun removeHandwriting(flat: Mat): Pair<Mat, Double> = matScope {
        val mask = mat()
        Core.bitwise_or(track(penMask(flat)), track(pencilMask(flat)), mask)
        Imgproc.dilate(mask, mask, kernel(3))
        val share = Core.countNonZero(mask).toDouble() / mask.total()
        val out = flat.clone()
        out.setTo(Scalar.all(255.0), mask)
        out to share
    }
}
