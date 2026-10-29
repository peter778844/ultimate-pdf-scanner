package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/** Image enhancement: shadow/illumination removal, white/black point and output colour modes. */
enum class Mode { AUTO, COLOR, GRAY, BW, NONE }

object Enhance {
    const val PAPER_WHITE_PERCENTILE = 30.0 // this share of the paper pixels (incl. the grey halo next to text) still ends up pure white
    const val COLORFUL_MIN_SHARE = 0.01 // measured: O'Reilly cover 1.7 %, text pages with coloured figures and yellowed book pages 0 %; pages with at least this share of coloured areas get the global colour correction
    const val COLOR_BORDER = 0.08 // ignored margin on each side when looking for colour
    const val COLOR_AREA = 0.02 // coloured areas at least this wide (share of the short side) make a colour page
    const val MIN_CHROMA = 30.0 // yellowed paper stays below ~25 (the page detection's paper limit), print colours, wood and covers above

    /**
     * Estimate the paper illumination: a closing removes text strokes (dark features narrower
     * than k) but, unlike a plain dilation, keeps the edge of large dark areas such as the
     * phone's shadow in place; the median then smooths without blurring that edge.
     * (Measured: a dilation left a grey band of ~224 along hard shadow edges.)
     */
    fun background(plane: Mat): Mat = matScope {
        val k = max(7, odd(min(plane.rows(), plane.cols()) / 100))
        val closed = mat()
        Imgproc.morphologyEx(plane, closed, Imgproc.MORPH_CLOSE, kernel(k))
        val bg = Mat()
        Imgproc.medianBlur(closed, bg, min(255, odd(k * 3)))
        bg
    }

    /** The estimated paper (illumination) of each channel, without the print: same size and type as [image]. */
    fun flattenBackground(image: Mat): Mat = matScope {
        val planes = ArrayList<Mat>()
        Core.split(image, planes)
        planes.forEach { track(it) }
        val bgs = planes.map { track(background(it)) }
        Mat().also { if (bgs.size == 1) bgs[0].copyTo(it) else Core.merge(bgs, it) }
    }

    const val MIN_BACKGROUND = 0.3 // share of the paper level below which the background estimate is not followed
    const val MIN_BACKGROUND_LEVEL = 24.0 // ... and an absolute floor (an all-black photo has no paper level)

    /** Divide each channel by its estimated background so the paper becomes uniformly white. */
    fun flatten(image: Mat): Mat = matScope {
        val planes = ArrayList<Mat>()
        Core.split(image, planes)
        planes.forEach { track(it) }
        // where the background is far below the paper level (a strip of table, a corner of the photo)
        // the division blows the sensor noise of each channel up differently: colour speckle. The
        // division itself stays (it also turns such strips white, and text in deep shadow must stay),
        // but those pixels lose their colour.
        val noPaper = track(Mat.zeros(image.size(), CvType.CV_8U))
        val out = planes.map { p ->
            val bg = track(background(p))
            val floor = max(MIN_BACKGROUND_LEVEL, MIN_BACKGROUND * histPercentile(histogram(bg), 95.0))
            val low = mat(); Imgproc.threshold(bg, low, floor, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_or(noPaper, low, noPaper)
            track(Mat()).also { Core.divide(p, bg, it, 255.0) }
        }
        val result = Mat()
        if (out.size == 1) out[0].copyTo(result) else Core.merge(out, result)
        if (out.size == 3 && Core.countNonZero(noPaper) > 0) {
            val gray = mat(); Imgproc.cvtColor(result, gray, Imgproc.COLOR_BGR2GRAY)
            val grey3 = mat(); Imgproc.cvtColor(gray, grey3, Imgproc.COLOR_GRAY2BGR)
            grey3.copyTo(result, noPaper)
        }
        result
    }

    const val DARK_PAGE_MAX_MEDIAN = 128.0
    const val DARK_PAGE_SKEW = 1.5 // light outliers must be this much stronger than dark ones
    const val DARK_PAGE_STROKE = 0.007 // opening on dark pages: finer than title strokes, coarser than cloth texture
    const val DARK_PAGE_TEXTURE = 250 // median size as a share of the short side (cover cloth: ~8 px weave at 2000 px)

    /**
     * Light print on a dark ground (dark book covers): the page is dark and the strongest
     * deviations from the local surroundings are bright (print), the reverse of paper, where
     * they are dark (ink). Cloth texture deviates both ways. Measured: Meinke/Gundlach cover
     * 61 vs 29, paper pages 1-26 vs 5-190. Flattening assumes dark print and would turn such a
     * page into noise.
     */
    fun isDarkPage(image: Mat): Boolean = matScope {
        val (small, _) = downscale(image, 400)
        val gray = track(toGray(small))
        if (histPercentile(histogram(gray), 50.0) >= DARK_PAGE_MAX_MEDIAN) return@matScope false
        val local = mat()
        Imgproc.medianBlur(gray, local, 21)
        val light = mat(); val dark = mat()
        Core.subtract(gray, local, light) // saturates: only brighter-than-surroundings remains
        Core.subtract(local, gray, dark)
        histPercentile(histogram(light), 99.0) > DARK_PAGE_SKEW * histPercentile(histogram(dark), 99.0)
    }

    /** Flattened page with dark print on white, also for dark pages (flattened as their negative): input for OCR and orientation. */
    fun readable(image: Mat): Mat {
        if (!isDarkPage(image)) return flatten(image)
        // cover print is large while cloth texture is fine: a median finer than the strokes removes
        // most of the texture; binarised, an opening finer than the title's strokes removes the rest
        // (Meinke/Gundlach cover: Tesseract then reads "Taschenbuch der Hochfrequenz- technik Dritte Auflage")
        return matScope {
            val flat = track(flatten(mat().also { Core.bitwise_not(image, it) }))
            val smooth = mat()
            Imgproc.medianBlur(flat, smooth, max(3, odd(min(image.rows(), image.cols()) / DARK_PAGE_TEXTURE)))
            val ink = mat()
            Imgproc.threshold(track(toGray(smooth)), ink, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
            val k = max(3, odd((min(image.rows(), image.cols()) * DARK_PAGE_STROKE).toInt()))
            Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(k.toDouble(), k.toDouble())))
            Core.bitwise_not(ink, ink)
            Mat().also { if (image.channels() == 3) Imgproc.cvtColor(ink, it, Imgproc.COLOR_GRAY2BGR) else ink.copyTo(it) }
        }
    }

    fun toGray(image: Mat): Mat {
        val out = Mat()
        if (image.channels() == 3) Imgproc.cvtColor(image, out, Imgproc.COLOR_BGR2GRAY) else image.copyTo(out)
        return out
    }

    /**
     * Black and white point for a flattened page. Paper next to text stays slightly grey after
     * flattening (ink spread, blur, paper grain); the white point is put below most paper
     * pixels so the background becomes clean white.
     */
    fun levels(gray: Mat): Pair<Double, Double> = matScope {
        val t = Imgproc.threshold(gray, mat(), 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU).toInt()
        val hist = histogram(gray)
        if ((t + 1..255).sumOf { hist[it] } == 0L || (0..t).sumOf { hist[it] } == 0L) return@matScope 0.0 to 255.0
        val white = histPercentile(hist, PAPER_WHITE_PERCENTILE, t + 1, 255)
        val black = histPercentile(hist, 5.0, 0, t)
        if (white - black < 30) 0.0 to 255.0 else black to white
    }

    fun applyLevels(image: Mat, black: Double, white: Double): Mat {
        val lut = Mat(1, 256, CvType.CV_8U)
        val values = ByteArray(256) { v -> ((v - black) / (white - black) * 255).coerceIn(0.0, 255.0).toInt().toByte() }
        lut.put(0, 0, values)
        val out = Mat()
        Core.LUT(image, lut, out)
        lut.release()
        return out
    }

    /**
     * True if the page has larger coloured areas, e.g. a cover: flattening would bleach them, so
     * such pages get the global correction of [colorPage]. Coloured lines and lettering survive
     * flattening and do not count. Only the inner part is looked at: a strip of wooden table, book
     * edge or neighbouring page left at the border used to turn plain yellowed book pages into
     * "colour pages", as did HSV saturation, which exaggerates yellowed paper in the gutter shadow.
     */
    fun isColorful(image: Mat): Boolean = colorShare(image) >= COLORFUL_MIN_SHARE

    /** Share of clearly coloured pixels in the page without its outer [COLOR_BORDER] on each side. */
    fun colorShare(image: Mat): Double = matScope {
        if (image.channels() != 3) return@matScope 0.0
        val (small, _) = downscale(image, 800)
        val inner = small.submat((small.rows() * COLOR_BORDER).toInt(), (small.rows() * (1 - COLOR_BORDER)).toInt(),
            (small.cols() * COLOR_BORDER).toInt(), (small.cols() * (1 - COLOR_BORDER)).toInt())
        // chroma = distance from grey in YCrCb; HSV saturation would exaggerate yellowed paper in the gutter shadow
        val ycc = mat()
        Imgproc.cvtColor(inner, ycc, Imgproc.COLOR_BGR2YCrCb)
        inner.release()
        val ch = ArrayList<Mat>()
        Core.split(ycc, ch)
        ch.forEach { track(it) }
        val cr = mat().also { ch[1].convertTo(it, CvType.CV_32F, 1.0, -128.0) }
        val cb = mat().also { ch[2].convertTo(it, CvType.CV_32F, 1.0, -128.0) }
        val chroma = mat()
        Core.magnitude(cr, cb, chroma)
        val colored = mat()
        Imgproc.threshold(chroma, colored, MIN_CHROMA, 255.0, Imgproc.THRESH_BINARY)
        val mask = mat().also { colored.convertTo(it, CvType.CV_8U) }
        // only areas: coloured lines and lettering stay intact when the page is flattened anyway
        Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, kernel(odd((min(mask.rows(), mask.cols()) * COLOR_AREA).toInt())))
        Core.countNonZero(mask).toDouble() / mask.total()
    }

    const val FIELD_SIZE = 400 // working size of the illumination field
    const val FIELD_SIGMA = 0.06 // interpolation reach over non-paper areas, as share of the field's longer side
    const val PAPER_CHROMA = 32.0 // paper (also yellowed) is at most this far from grey in YCrCb
    const val PAPER_LEVEL = 0.55 // ... and at least this bright relative to the brightest paper

    /**
     * The light falling on the page, per channel and at full size: measured only where there is
     * paper (bright, nearly colourless after removing the print), interpolated across pictures and
     * coloured areas by normalised convolution. Dividing the photo by it turns shadowed, yellowed
     * paper white while pictures keep their colours, because they are corrected by the same light.
     */
    fun illumination(image: Mat): Mat = matScope {
        val (small, _) = downscale(image, FIELD_SIZE)
        val bg = track(flattenBackground(small)) // text removed: paper, or the colour of large areas
        val ycc = mat()
        Imgproc.cvtColor(bg, ycc, Imgproc.COLOR_BGR2YCrCb)
        val ch = ArrayList<Mat>(); Core.split(ycc, ch); ch.forEach { track(it) }
        val cr = mat().also { ch[1].convertTo(it, CvType.CV_32F, 1.0, -128.0) }
        val cb = mat().also { ch[2].convertTo(it, CvType.CV_32F, 1.0, -128.0) }
        val chroma = mat(); Core.magnitude(cr, cb, chroma)
        val neutral = mat(); Imgproc.threshold(chroma, neutral, PAPER_CHROMA, 1.0, Imgproc.THRESH_BINARY_INV)
        val bright = mat()
        Imgproc.threshold(track(Mat().also { ch[0].convertTo(it, CvType.CV_32F) }), bright,
            PAPER_LEVEL * histPercentile(histogram(ch[0]), 98.0), 1.0, Imgproc.THRESH_BINARY)
        val m = mat(); Core.multiply(neutral, bright, m)
        if (Core.countNonZero(m) < 0.05 * m.total()) return@matScope Mat() // hardly any paper: no field
        val sigma = FIELD_SIGMA * max(small.rows(), small.cols())
        val wsum = mat(); Imgproc.GaussianBlur(m, wsum, Size(0.0, 0.0), sigma)
        Core.max(wsum, Scalar(1e-4), wsum)
        val planes = ArrayList<Mat>(); Core.split(bg, planes); planes.forEach { track(it) }
        val field = planes.map { p ->
            val f = mat().also { p.convertTo(it, CvType.CV_32F) }
            val num = mat(); Core.multiply(f, m, num)
            Imgproc.GaussianBlur(num, num, Size(0.0, 0.0), sigma)
            Core.divide(num, wsum, num)
            // measured paper stays as measured (sharp shadow edges); in between, the light is the
            // harmonic interpolation of its surroundings (continues a light gradient exactly, where the
            // weighted average above leans towards the nearer or larger paper areas), solved coarse to fine
            val a = membrane(num, f, m)
            val full = mat()
            Imgproc.resize(a, full, image.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
            Core.max(full, Scalar(1.0), full)
            full
        }
        Mat().also { Core.merge(field, it) }
    }

    const val MEMBRANE_ITERATIONS = 120 // Jacobi sweeps per pyramid level

    /**
     * Solves the Laplace equation for the unknown pixels (where [known] is 0) with the [values] of
     * the known ones as boundary, starting from [guess]: Jacobi sweeps (3x3 mean, known pixels
     * reset) on a pyramid of 1/4, 1/2 and full size, each level started from the one below.
     */
    private fun MatScope.membrane(guess: Mat, values: Mat, known: Mat): Mat {
        val known8 = mat().also { known.convertTo(it, CvType.CV_8U, 255.0) }
        var f = guess
        for (div in intArrayOf(4, 2, 1)) {
            val size = Size(maxOf(1, values.cols() / div).toDouble(), maxOf(1, values.rows() / div).toDouble())
            val kw = mat(); Imgproc.resize(known, kw, size, 0.0, 0.0, Imgproc.INTER_AREA)
            val kv = mat(); Imgproc.resize(track(Mat().also { Core.multiply(values, known, it) }), kv, size, 0.0, 0.0, Imgproc.INTER_AREA)
            val kMask = mat(); Imgproc.threshold(kw, kMask, 0.5, 255.0, Imgproc.THRESH_BINARY)
            kMask.convertTo(kMask, CvType.CV_8U)
            Core.divide(kv, track(Mat().also { Core.max(kw, Scalar(1e-6), it) }), kv) // mean of the known values per cell
            val cur = mat(); Imgproc.resize(f, cur, size, 0.0, 0.0, if (div == 4) Imgproc.INTER_AREA else Imgproc.INTER_LINEAR)
            kv.copyTo(cur, kMask)
            val tmp = mat()
            repeat(MEMBRANE_ITERATIONS) {
                Imgproc.blur(cur, tmp, Size(3.0, 3.0), org.opencv.core.Point(-1.0, -1.0), Core.BORDER_REPLICATE)
                kv.copyTo(tmp, kMask)
                tmp.copyTo(cur)
            }
            f = cur
        }
        values.copyTo(f, known8)
        return f
    }

    /**
     * Colour pages: the photo divided by its [illumination], then one black/white point for all
     * channels (the paper's white balance comes from the division, the colours stay in proportion).
     * Falls back to [colorPage] when the page has too little paper to measure the light.
     */
    fun colorFlatten(image: Mat): Mat = matScope {
        val field = track(illumination(image))
        if (field.empty()) return@matScope colorPage(image)
        val img = mat().also { image.convertTo(it, CvType.CV_32FC3) }
        val flat = mat(); Core.divide(img, field, flat, 255.0)
        val out8 = mat(); flat.convertTo(out8, CvType.CV_8UC3)
        val gray = track(toGray(out8))
        val hist = histogram(gray)
        val black = histPercentile(hist, 0.5)
        val white = max(black + 30, min(255.0, histPercentile(hist, 99.0)))
        applyLevels(out8, black, white)
    }

    /**
     * Colour pages such as covers: global white balance + contrast stretch per channel.
     * Local flattening would turn large coloured areas white (they are their own
     * 'background'), so colourful pages only get this global correction.
     */
    fun colorPage(image: Mat): Mat = matScope {
        val planes = ArrayList<Mat>()
        Core.split(image, planes)
        planes.forEach { track(it) }
        val out = planes.map { p ->
            val hist = histogram(p)
            val lo = histPercentile(hist, 0.5)
            val hi = histPercentile(hist, 99.5)
            track(applyLevels(p, lo, max(hi, lo + 30)))
        }
        Mat().also { Core.merge(out, it) }
    }

    /**
     * What kind of sheet this is, from shape and ink:
     *  - RECEIPT: long narrow strip (till receipts; faint thermal print gets extra contrast),
     *  - WHITEBOARD: landscape, little ink, mostly coloured marker strokes (bright background, vivid colours),
     *  - PAPER: everything else.
     */
    enum class Profile { PAPER, RECEIPT, WHITEBOARD }

    const val RECEIPT_ASPECT = 2.2
    const val WHITEBOARD_MAX_INK = 0.08 // share of the page covered by strokes
    const val WHITEBOARD_MIN_COLORED_INK = 0.3 // share of the strokes that are clearly coloured

    fun profile(original: Mat): Profile = matScope {
        val long = max(original.rows(), original.cols()).toDouble()
        val short = min(original.rows(), original.cols()).toDouble()
        if (long / short >= RECEIPT_ASPECT) return@matScope Profile.RECEIPT
        if (original.channels() != 3 || original.cols() <= original.rows()) return@matScope Profile.PAPER
        val flat = track(flatten(original))
        val gray = track(toGray(flat))
        val ink = mat()
        Imgproc.threshold(gray, ink, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val inkShare = Core.countNonZero(ink).toDouble() / ink.total()
        if (inkShare == 0.0 || inkShare > WHITEBOARD_MAX_INK) return@matScope Profile.PAPER
        val hsv = mat()
        Imgproc.cvtColor(flat, hsv, Imgproc.COLOR_BGR2HSV)
        val colored = mat()
        Core.inRange(hsv, Scalar(0.0, 90.0, 40.0), Scalar(180.0, 255.0, 255.0), colored)
        Core.bitwise_and(colored, ink, colored)
        if (Core.countNonZero(colored).toDouble() / Core.countNonZero(ink) >= WHITEBOARD_MIN_COLORED_INK) Profile.WHITEBOARD else Profile.PAPER
    }

    /** Receipts: thermal print fades to grey; a higher black point and a dark gamma restore deep black text. */
    private fun receiptLevels(gray: Mat): Mat = matScope {
        val (black, white) = levels(gray)
        val t = Imgproc.threshold(gray, mat(), 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        val inkMid = histPercentile(histogram(gray), 50.0, 0, t.toInt()) // typical stroke grey
        val bp = max(black, inkMid - 10)
        val lut = Mat(1, 256, CvType.CV_8U)
        lut.put(0, 0, ByteArray(256) { v ->
            val x = ((v - bp) / max(white - bp, 1.0)).coerceIn(0.0, 1.0)
            (Math.pow(x, 1.6) * 255).toInt().toByte()
        })
        track(lut)
        Mat().also { Core.LUT(gray, lut, it) }
    }

    /** Whiteboards: pure white background and saturated marker colours. */
    private fun whiteboard(flat: Mat): Mat = matScope {
        val gray = track(toGray(flat))
        val (black, white) = levels(gray)
        val leveled = track(applyLevels(flat, black, white - 0.25 * (white - black)))
        val hsv = mat()
        Imgproc.cvtColor(leveled, hsv, Imgproc.COLOR_BGR2HSV)
        val ch = ArrayList<Mat>()
        Core.split(hsv, ch)
        ch.forEach { track(it) }
        Core.multiply(ch[1], Scalar(1.5), ch[1]) // saturating
        Core.merge(ch, hsv)
        Mat().also { Imgproc.cvtColor(hsv, it, Imgproc.COLOR_HSV2BGR) }
    }

    fun enhance(image: Mat, mode: Mode = Mode.COLOR): Mat {
        if (mode == Mode.NONE) return image.clone()
        return matScope { finish(track(readable(image)), mode, image) }
    }

    /** Automatic correction switched off: [mode] only converts the colours (BW: one global Otsu threshold). */
    fun plain(image: Mat, mode: Mode): Mat = when (mode) {
        Mode.GRAY -> toGray(image)
        Mode.BW -> matScope {
            val gray = track(toGray(image))
            Mat().also { Imgproc.threshold(gray, it, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU) }
        }
        else -> image.clone()
    }

    /**
     * Output mode for an already flattened page (lets cleaning steps run in between).
     * [original] is the unflattened page; colourful pages are rendered from it.
     */
    fun finish(flat: Mat, mode: Mode, original: Mat? = null): Mat = matScope {
        val gray = track(toGray(flat))
        if (mode == Mode.BW) {
            return@matScope Mat().also { Imgproc.threshold(gray, it, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU) }
        }
        if (original != null && isDarkPage(original)) { // keep a dark cover dark: global correction only
            val c = track(colorPage(original))
            return@matScope if (mode == Mode.GRAY) toGray(c) else c.clone()
        }
        when (original?.let { profile(it) }) {
            Profile.RECEIPT -> return@matScope receiptLevels(gray) // receipts: deep black text on white, no colour needed
            Profile.WHITEBOARD -> if (mode == Mode.COLOR || mode == Mode.AUTO) return@matScope whiteboard(flat)
            else -> Unit
        }
        if ((mode == Mode.COLOR || mode == Mode.AUTO) && original != null && original.channels() == 3 && isColorful(original)) {
            return@matScope colorFlatten(original)
        }
        val (black, white) = levels(gray)
        if (mode == Mode.COLOR) applyLevels(flat, black, white) else applyLevels(gray, black, white)
    }
}
