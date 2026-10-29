package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** Upright detection for engines without Tesseract OSD (e.g. Tesseract4Android). */
object Orientation {
    /**
     * How strongly a profile alternates like text lines: the largest rise of the normalised
     * autocorrelation after its dip (lines: dip to about -0.3 at half a period, peak at the
     * period). A plain "first peak" is fooled by noise wiggles at tiny lags.
     */
    internal fun periodicity(profile: DoubleArray): Double {
        val n = profile.size
        val mean = profile.average()
        val p = DoubleArray(n) { profile[it] - mean }
        val maxLag = n / 8
        val ac = DoubleArray(maxLag + 1) { lag -> var s = 0.0; for (i in 0 until n - lag) s += p[i] * p[i + lag]; s }
        if (ac[0] <= 0) return 0.0
        var lowest = 1.0
        var best = 0.0
        for (lag in 1..maxLag) {
            val v = ac[lag] / ac[0]
            lowest = kotlin.math.min(lowest, v)
            best = max(best, v - lowest)
        }
        return best
    }

    /** 0 if text lines run horizontally, 90 if vertically. */
    fun textLineAxis(gray: Mat): Int = matScope {
        val (small, _) = downscale(gray, 1500)
        val flat = track(Enhance.flatten(small))
        val ink = mat()
        Imgproc.threshold(flat, ink, 0.0, 1.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val f = mat().also { ink.convertTo(it, CvType.CV_32F) }
        val rows = mat()
        val cols = mat()
        Core.reduce(f, rows, 1, Core.REDUCE_SUM) // one value per row: horizontal lines alternate here
        Core.reduce(f, cols, 0, Core.REDUCE_SUM)
        val r = floats(rows).map { it.toDouble() }.toDoubleArray()
        val c = floats(cols).map { it.toDouble() }.toDoubleArray()
        if (periodicity(r) >= periodicity(c)) 0 else 90
    }

    const val ACCEPT_CONFIDENCE = 80.0 // upside-down text never scores this high: no need to try the other candidate
    const val UPRIGHT_RATIO = 1.35 // ascender/descender ink ratio that counts as a clear decision

    /**
     * Ascenders (b, d, f, h, k, l, t, capitals) are far more common than descenders (g, j, p, q, y)
     * in Latin, Cyrillic and Greek text: ink above the x-height band of each line divided by ink
     * below the baseline. > 1 upright, < 1 upside down, ~1 undecided (little text, formulas,
     * other scripts). Expects horizontal text lines; takes milliseconds.
     */
    fun ascenderRatio(gray: Mat): Double = matScope {
        val (small, _) = downscale(gray, 1600)
        val flat = track(Enhance.flatten(small))
        val ink = mat()
        Imgproc.threshold(flat, ink, 0.0, 1.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val f = mat().also { ink.convertTo(it, CvType.CV_32F) }
        // narrow vertical strips: curved or slightly tilted lines are nearly straight within a strip
        var up = 0.0
        var down = 0.0
        var total = 0.0
        val strips = STRIPS
        for (s in 0 until strips) {
            val x0 = f.cols() * s / strips
            val x1 = f.cols() * (s + 1) / strips
            val rowsMat = mat()
            Core.reduce(track(f.colRange(x0, x1)), rowsMat, 1, Core.REDUCE_SUM)
            val rows = floats(rowsMat)
            val n = rows.size
            val peak = rows.maxOrNull() ?: 0f
            if (n < 20 || peak <= 0f) continue
            // text lines: runs of rows with ink; inside each line the x-height band is where the
            // row profile is high, ascenders/descenders form the weak tails above and below
            var r = 0
            val minInk = peak * 0.05f
            while (r < n) {
                if (rows[r] < minInk) { r++; continue }
                val start = r
                while (r < n && rows[r] >= minInk) r++
                val end = r // exclusive
                if (end - start < 4) continue
                val linePeak = (start until end).maxOf { rows[it] }
                val core = (start until end).filter { rows[it] >= linePeak * 0.5f }
                for (i in start until core.first()) up += rows[i]
                for (i in core.last() + 1 until end) down += rows[i]
                for (i in start until end) total += rows[i]
            }
        }
        // too little text for a judgement
        if (up + down < total * 0.02) 1.0 else (up + 1.0) / (down + 1.0)
    }

    const val STRIPS = 6

    /**
     * Clockwise rotation (0/90/180/270) that makes the page upright. [confidence] scores a
     * candidate image (e.g. mean OCR confidence); only the two rotations matching the text
     * line axis are tried, on a downscaled central crop. The [hint] (e.g. the previous page of
     * the same book) is tried first, and a clearly readable first candidate is accepted
     * without trying the second, which halves the cost for most pages.
     */
    fun detect(gray: Mat, hint: Int? = null, confidence: (Mat) -> Double): Int = matScope {
        val axis = textLineAxis(gray)
        val (small, _) = downscale(gray, 1600)
        val crop = track(Mat(small, Rect(small.cols() / 6, small.rows() / 3, small.cols() * 2 / 3, small.rows() / 3)))
        val candidates = (if (axis == 0) listOf(0, 180) else listOf(90, 270)).sortedBy { if (it == hint) 0 else 1 }
        val first = confidence(track(rotate(crop, candidates[0])))
        if (first >= ACCEPT_CONFIDENCE) return@matScope candidates[0]
        val second = confidence(track(rotate(crop, candidates[1])))
        if (second > first) candidates[1] else candidates[0]
    }

    fun rotate(image: Mat, clockwise: Int): Mat {
        val out = Mat()
        when (clockwise) {
            90 -> Core.rotate(image, out, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(image, out, Core.ROTATE_180)
            270 -> Core.rotate(image, out, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> image.copyTo(out)
        }
        return out
    }
}

/** One finished page. The caller owns the Mats and must [close] the result. */
class PageResult(
    val image: Mat, // final output (colour or grey)
    val rectified: Mat, // cleaned, upright page before enhancement (for later colour/grey changes)
    val words: List<Word>,
    val notes: List<String>,
    val quad: Quad?,
    val issues: List<Issue> = emptyList(),
    val rotation: Int = 0, // clockwise turn applied to make the page upright (hint for the next page)
) : AutoCloseable {
    val text: String get() = pageText(words)
    override fun close() { image.release(); rectified.release() }
}

/** How a photo is divided into pages. */
enum class SplitMode {
    AUTO, // split when a gutter is detected
    SINGLE, // always one page
    DOUBLE, // always two pages (open magazine/book), even without visible gutter shading
    BOOK, // book mode: two pages unless the photo is clearly not an open book (cover, receipt, single sheet)
}

data class PipelineConfig(
    val detect: Boolean = true,
    val split: SplitMode = SplitMode.AUTO,
    val dewarp: Boolean = true,
    val fingers: Boolean = true, // remove fingers reaching in from the page border
    val deblur: Boolean = true, // deconvolve pages whose estimated blur exceeds Deblur.BLUR_SIGMA
    val handwriting: Boolean = false, // remove coloured pen and pencil marks (also removes coloured print)
    val orient: Boolean = true,
    val orientationHint: Int? = null, // likely rotation (previous page of the same book), tried first
    val mode: Mode = Mode.GRAY,
    val enhance: Boolean = true, // false: the output is only converted to [mode] (Enhance.plain); OCR still reads the flattened page
    val ocr: Boolean = true,
    val ocrTextHeight: Double = TextSize.TARGET, // letter size (px) the page is scaled to for OCR
    val ocrUpscale: Boolean = true, // false: small print is not enlarged (faster, for clear large print)
    val picture: Boolean = false, // pictures / magazines: colour-true rendering, no text steps (see Picture)
)

/** Orchestrates the stages: detect -> warp -> split -> dewarp -> clean -> orient -> enhance -> OCR. */
object Pipeline {
    @JvmStatic var DEBUG = System.getProperty("ultimatepdfscanner.debug") != null
    const val SPREAD_MARGIN = 0.04 // keep bulging page edges inside the rectified spread

    /**
     * [manualQuad] (corners the user adjusted) replaces the automatic page detection; the
     * mask then is the quad itself. With a [secondEngine] the two pages of a spread are finished
     * in parallel (each OCR engine is used by one thread only); [progress] may then be called
     * from two threads. The learned [corners] model helps where the segmentation fails (see
     * [Detect.detectDocument]); it may be shared between threads.
     */
    fun processPhoto(image: Mat, cfg: PipelineConfig, engine: OcrEngine?, focal: Double = Warp.DEFAULT_FOCAL,
                     manualQuad: Quad? = null, secondEngine: OcrEngine? = null, corners: CornerModel? = null,
                     progress: (String) -> Unit = {}): List<PageResult> = matScope {
        progress("Seite erkennen")
        if (Detect.lacksContrast(image)) { // nothing on the photo: kept as it is and marked for checking
            val out = image.clone()
            return@matScope listOf(PageResult(out, out.clone(), emptyList(), emptyList(), null, Quality.assess(false, Deblur.estimateBlur(out), out, null), 0))
        }
        val det = when {
            manualQuad != null -> Detect.Result(manualQuad, null)
            cfg.detect -> Detect.detectDocument(image, model = corners)
            else -> Detect.Result(null, null)
        }
        det.mask?.let { track(it) }
        val quad = det.quad
        if (cfg.picture) {
            // cut and flatten the page, then only the colour-true picture treatment: no finger removal
            // (skin in photos), no deconvolution, no text orientation, no OCR
            progress("Seite entzerren")
            val page = track(if (quad != null) Warp.warpQuad(image, quad, focal = focal) else image.clone())
            progress("Bild verbessern")
            val out = Picture.render(page)
            val issues = Quality.assess(quad != null, Deblur.estimateBlur(page), page, null)
            return@matScope listOf(PageResult(out, out.clone(), emptyList(), emptyList(), quad, issues, 0))
        }
        if (quad == null) return@matScope listOf(finishPage(image.clone(), cfg, engine, null, progress))
        if (cfg.split == SplitMode.SINGLE) return@matScope listOf(finishPage(Warp.warpQuad(image, quad, focal = focal), cfg, engine, quad, progress))

        val mask = det.mask ?: quadMask(image, quad).also { track(it) }
        val (spread, spreadMask) = Warp.warpQuadWithMask(image, mask, quad, SPREAD_MARGIN, focal)
        track(spread); track(spreadMask)
        val gray = track(Enhance.toGray(spread))
        val fold = foldFor(gray, spreadMask, cfg.split)
        if (fold == null) {
            return@matScope listOf(finishPage(Warp.warpQuad(image, quad, focal = focal), cfg, engine, quad, progress))
        }

        val parts = Spread.splitSpread(spread, spreadMask, fold)
        fun page(i: Int, pageCfg: PipelineConfig, pageEngine: OcrEngine?): PageResult {
            val (part, partMask) = parts[i]
            progress("Seite entzerren")
            var page = part
            if (cfg.dewarp) { // edge-based dewarp, then text-line refinement (measured: 88.9% -> 95.6% words with conf >= 90)
                // splitSpread returns the part before the fold first: its fold is at the bottom
                val edged = Dewarp.dewarpPage(part, partMask, if (i == 0) Outline.Side.BOTTOM else Outline.Side.TOP)
                part.release()
                page = Dewarp.straightenTextLines(edged)
                edged.release()
            }
            // strips of the neighbouring page and the book block's edges at the borders
            progress("Ränder bereinigen")
            val clean = Borders.clean(page, if (i == 0) Outline.Side.BOTTOM else Outline.Side.TOP, fold.sharp).first
            page.release()
            page = clean
            partMask?.release()
            // splitSpread turned the pages of a side-by-side spread by 90 degrees: turning back is
            // the camera's upright, used where the text orientation cannot be determined
            return finishPage(page, pageCfg, pageEngine, quad, progress, cameraUpright = if (fold.axis == 1) 270 else 0)
        }
        if (secondEngine != null && parts.size == 2) {
            // both pages at once; the second runs on its own thread with its own OCR engine
            var second: PageResult? = null
            var failure: Throwable? = null
            val worker = Thread { try { second = page(1, cfg, secondEngine) } catch (t: Throwable) { failure = t } }
            worker.start()
            val first = try { page(0, cfg, engine) } finally { worker.join() }
            failure?.let { first.close(); throw it }
            listOf(first, second!!)
        } else {
            // one after the other: the first page's orientation is the best guess for the second
            var hint = cfg.orientationHint
            parts.indices.map { i -> page(i, cfg.copy(orientationHint = hint), engine).also { hint = it.rotation } }
        }
    }

    /**
     * Where the rectified photo is cut into two pages, or null if it is one page. AUTO splits at a
     * clear gutter, BOOK already at a weak one; both keep dark or coloured covers whole.
     */
    fun foldFor(gray: Mat, mask: Mat?, split: SplitMode): Spread.Fold? {
        if (split == SplitMode.SINGLE) return null
        val fold = if (split == SplitMode.AUTO) Spread.findFold(gray, mask) else Spread.forcedFold(gray, mask)
        return when (split) {
            SplitMode.AUTO -> fold.takeIf { it.confident && Spread.looksLikePages(gray, mask) }
            SplitMode.BOOK -> fold.takeIf { it.dip >= Spread.BOOK_MIN_DIP && Spread.looksLikePages(gray, mask) }
            else -> fold
        }
    }

    /**
     * Cleaning, orientation, enhancement and OCR for an already rectified page; takes ownership of
     * [page]. [cameraUpright] is the clockwise turn back to the photo's own upright (pages of a
     * split spread lie on their side), applied when the text orientation is not determined.
     */
    private fun finishPage(page: Mat, cfg: PipelineConfig, engine: OcrEngine?, quad: Quad?, progress: (String) -> Unit,
                           cameraUpright: Int = 0): PageResult {
        var img = page
        val notes = ArrayList<String>()
        var blur = 0.0
        fun replace(next: Mat) { if (next !== img) { img.release(); img = next } }

        if (cfg.fingers) {
            progress("Finger entfernen")
            val (out, share) = Fingers.removeFingers(img)
            replace(out)
            if (share > 0) notes += "Finger entfernt (%.1f %%)".format(share * 100)
        }
        if (cfg.deblur) {
            progress("Schärfe prüfen")
            val (out, sigma) = Deblur.deblur(img)
            replace(out)
            blur = sigma
            if (sigma > Deblur.BLUR_SIGMA) notes += "geschärft (Unschärfe %.1f px)".format(sigma)
        }
        // Orientation: a cheap prediction (the previous page of the same book, else ascenders vs
        // descenders), checked for free by the page's own OCR below. The OCR probe of both
        // candidates (seconds) only runs when the prediction is unsure.
        var rotation = 0
        var predicted = false
        // dark covers: a few large title lines on cloth defeat the line-based estimate (measured on
        // the Meinke/Gundlach cover: 90 and 270 degrees); the camera's own upright is more reliable
        val rot = if (!cfg.orient || Enhance.isDarkPage(img)) cameraUpright else {
            progress("Ausrichtung")
            matScope {
                val gray = track(Enhance.toGray(track(Enhance.readable(img))))
                if (engine != null && engine.orientationReliable) return@matScope engine.orientation(gray, cfg.orientationHint) ?: cameraUpright
                val level = if (Orientation.textLineAxis(gray) == 0) 0 else 90
                val hint = cfg.orientationHint?.takeIf { (it - level + 360) % 180 == 0 } // same line direction as this page
                val ratio = Orientation.ascenderRatio(track(Orientation.rotate(gray, level)))
                when {
                    hint != null -> { predicted = true; hint }
                    ratio >= Orientation.UPRIGHT_RATIO -> { predicted = true; level }
                    ratio <= 1 / Orientation.UPRIGHT_RATIO -> { predicted = true; (level + 180) % 360 }
                    // the OCR probe looks at a central crop only (a figure there makes it guess): checked below too
                    engine != null -> { predicted = true; engine.orientation(gray, cfg.orientationHint) ?: cameraUpright }
                    // no OCR to ask: of the two turns that make the lines horizontal, the one nearer the camera's upright
                    (cameraUpright - level + 360) % 180 == 0 -> cameraUpright
                    else -> level
                }
            }
        }
        if (rot != 0) { replace(Orientation.rotate(img, rot)); rotation = rot }

        var (flat, output, words) = enhanceAndRead(img, cfg, engine, notes, progress)
        if (DEBUG) println("ORIENT rot=$rotation predicted=$predicted conf=%.1f words=${words.size}".format(Quality.textConfidence(words)))
        // the check: an upside-down page reads badly; then try it the other way round, keep the better
        if (predicted && cfg.ocr && engine != null && words.size >= Quality.MIN_WORDS && Quality.textConfidence(words) < VERIFY_CONFIDENCE) {
            progress("Ausrichtung")
            val turned = Orientation.rotate(img, 180)
            val (flat2, output2, words2) = enhanceAndRead(turned, cfg, engine, ArrayList(), progress)
            if (DEBUG) println("ORIENT turned conf=%.1f words=${words2.size}".format(Quality.textConfidence(words2)))
            if (Quality.textConfidence(words2) > Quality.textConfidence(words) + 5) {
                listOf(flat, output).forEach { it.release() }
                replace(turned)
                flat = flat2; output = output2; words = words2
                rotation = (rotation + 180) % 360
            } else {
                listOf(turned, flat2, output2).forEach { it.release() }
            }
        }
        flat.release()
        val issues = Quality.assess(quad != null, blur, img, if (cfg.ocr && engine != null) words else null)
        return PageResult(output, img, words, notes, quad, issues, rotation)
    }

    const val VERIFY_CONFIDENCE = 65.0 // below this mean OCR confidence a predicted orientation is double-checked

    /** Flattening, optional handwriting removal, output rendering and OCR of an upright page. */
    private fun enhanceAndRead(img: Mat, cfg: PipelineConfig, engine: OcrEngine?, notes: MutableList<String>,
                               progress: (String) -> Unit): Triple<Mat, Mat, List<Word>> {
        progress("Bild verbessern")
        var flat = Enhance.readable(img)
        if (cfg.handwriting) {
            val (clean, share) = Handwriting.removeHandwriting(flat)
            flat.release()
            flat = clean
            notes += "Handschrift entfernt (%.2f %%)".format(share * 100)
        }
        val output = if (cfg.mode == Mode.NONE || !cfg.enhance) Enhance.plain(img, cfg.mode) else Enhance.finish(flat, cfg.mode, img)
        var words = emptyList<Word>()
        if (cfg.ocr && engine != null) {
            progress("Texterkennung")
            // OCR always sees the flattened (and cleaned) grayscale, independent of the output mode
            words = matScope {
                val gray = track(Enhance.toGray(flat))
                // texture without text (a bare table, cloth): Tesseract would take minutes for junk
                if (!OcrGate.assess(gray).text) { notes += "kein Text (Struktur)"; emptyList() }
                else TextSize.recognize(engine, gray, cfg.ocrTextHeight, cfg.ocrUpscale) // at the letter size Tesseract reads best
            }
        }
        return Triple(flat, output, words)
    }

    private fun quadMask(image: Mat, quad: Quad): Mat {
        val mask = Mat.zeros(image.size(), CvType.CV_8U)
        Imgproc.fillConvexPoly(mask, MatOfPoint(*quad.map { org.opencv.core.Point(it.x, it.y) }.toTypedArray()), Scalar(255.0))
        return mask
    }

    /** Re-render a page with another filter; same result as processing it in that mode. */
    fun render(rectified: Mat, mode: Mode, enhance: Boolean = true): Mat =
        if (enhance) Enhance.enhance(rectified, mode) else Enhance.plain(rectified, mode)
}
