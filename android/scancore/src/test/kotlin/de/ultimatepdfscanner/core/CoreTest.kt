package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Kotlin port of the Python prototype's synthetic tests (same images, same thresholds). */
class CoreTest {
    init { TestSupport.init() }

    private val ocr = TesseractCli("eng")

    private fun recall(words: List<Word>): Double {
        val found = words.map { it.text.trim('.', ',').lowercase() }.toSet()
        val unique = TestSupport.WORDS.toSet()
        return unique.count { it.lowercase() in found }.toDouble() / unique.size
    }

    @Test
    fun detectsCornersAndTrueAspect() {
        val (photo, truth) = TestSupport.makePhoto(TestSupport.makePage())
        val det = Detect.detectDocument(photo)
        val quad = assertNotNull(det.quad)
        val err = quad.indices.maxOf { hypot(quad[it].x - truth[it].x, quad[it].y - truth[it].y) }
        assertTrue(err < 15, "corner error $err px")
        val page = Warp.warpQuad(photo, quad)
        assertEquals(1754.0 / 1240.0, page.rows().toDouble() / page.cols(), 0.08)
    }

    @Test
    fun flattensShadowAndWhitensPaperNextToText() {
        val page = TestSupport.makePage()
        val out = Enhance.enhance(page, Mode.GRAY)
        val w = out.cols()
        val left = histPercentile(histogram(Mat(out, Rect(0, 0, w / 10, out.rows()))), 90.0)
        val right = histPercentile(histogram(Mat(out, Rect(w - w / 10, 0, w / 10, out.rows()))), 90.0)
        assertTrue(abs(left - right) < 15, "left $left right $right")

        // grey halo test from the prototype: soft edges, paper next to glyphs must end up white
        val small = Mat().also { Imgproc.resize(page, it, Size(), 0.5, 0.5, Imgproc.INTER_AREA) }
        Imgproc.GaussianBlur(small, small, Size(0.0, 0.0), 1.2)
        val g = Enhance.enhance(small, Mode.GRAY)
        val ink = Mat().also { Core.compare(g, Scalar(128.0), it, Core.CMP_LT) }
        val rim = Mat().also { Imgproc.dilate(ink, it, ones(5)) }
        val near = Mat().also { Imgproc.dilate(ink, it, ones(15)) }
        val ring = Mat().also { Core.bitwise_and(near, Mat().also { n -> Core.bitwise_not(rim, n) }, it) }
        assertTrue(histPercentile(histogram(g, ring), 50.0) >= 250)
    }

    @Test
    fun renderWithoutEnhancementOnlyConvertsColours() {
        val page = TestSupport.makePage()
        val color = Pipeline.render(page, Mode.COLOR, enhance = false)
        assertEquals(0.0, Core.norm(page, color), "colour without correction is the page itself")
        val gray = Pipeline.render(page, Mode.GRAY, enhance = false)
        assertEquals(1, gray.channels())
        assertEquals(0.0, Core.norm(Enhance.toGray(page), gray))
        val bw = Pipeline.render(page, Mode.BW, enhance = false)
        assertEquals(1, bw.channels())
        val values = histogram(bw)
        assertEquals(bw.total(), values[0] + values[255], "only black and white")
        // the shadow survives without correction: the enhanced page is lighter on the shaded side
        assertTrue(Core.mean(Pipeline.render(page, Mode.GRAY)).`val`[0] > Core.mean(gray).`val`[0])
    }

    @Test
    fun ocrReadsSyntheticPhoto() {
        if (!ocr.available) return
        val (photo, _) = TestSupport.makePhoto(TestSupport.makePage())
        val pages = Pipeline.processPhoto(photo, PipelineConfig(mode = Mode.GRAY), ocr)
        assertEquals(1, pages.size)
        assertTrue(recall(pages[0].words) >= 0.9, pages[0].text)
        pages.forEach { it.close() }
    }

    @Test
    fun orientationFindsAllFourRotations() {
        if (!ocr.available) return
        val gray = Enhance.toGray(TestSupport.makePage(fill = true))
        for (rot in intArrayOf(0, 90, 180, 270)) {
            val turned = TestSupport.rotate(gray, rot)
            val fix = ocr.orientation(turned)
            assertEquals(0, (rot + fix) % 360, "page turned $rot cw, engine says rotate $fix")
        }
    }

    // --- open books -------------------------------------------------------------------

    private fun textPage(full: Boolean = false): Mat =
        TestSupport.rotate(Enhance.toGray(TestSupport.makePage(fill = full)), 90) // text lines vertical, as next to a horizontal fold

    @Test
    fun findsGutterButNotOnSinglePage() {
        val top = textPage(); val bottom = textPage()
        val spread = Mat().also { Core.vconcat(listOf(top, bottom), it) }
        val shaded = Mat().also { spread.convertTo(it, CvType.CV_32F) }
        val shade = FloatArray(spread.rows()) { y -> (1 - 0.45 * Math.exp(-Math.pow((y - top.rows()) / 60.0, 2.0))).toFloat() }
        val col = Mat(spread.rows(), 1, CvType.CV_32F).also { it.put(0, 0, shade) }
        val full = Mat().also { Core.repeat(col, 1, spread.cols(), it) }
        Core.multiply(shaded, full, shaded)
        val gutter = Mat().also { shaded.convertTo(it, CvType.CV_8U) }

        val fold = Spread.findFold(gutter)
        assertTrue(fold.confident, "$fold")
        assertEquals(0, fold.axis)
        assertTrue(abs(fold.pos - gutter.rows() / 2) < gutter.rows() * 0.02, "$fold")
        val parts = Spread.splitSpread(gutter, null, fold)
        assertEquals(gutter.rows(), parts.sumOf { it.first.rows() })

        assertFalse(Spread.findFold(top).confident)
    }

    private fun bandShifts(gray: Mat): DoubleArray {
        val flat = Enhance.flatten(gray)
        val ink = Mat().also { Core.compare(flat, Scalar(128.0), it, Core.CMP_LT) }
        val data = bytes(ink)
        val h = ink.rows(); val w = ink.cols()
        val rowsWithInk = (0 until h).filter { r -> (0 until w).any { data[r * w + it].toInt() != 0 } }
        val y0 = rowsWithInk.first(); val y1 = rowsWithInk.last() + 1
        val bands = (0 until 10).map { b ->
            val r0 = y0 + (y1 - y0) * b / 10; val r1 = y0 + (y1 - y0) * (b + 1) / 10
            DoubleArray(w) { c -> (r0 until r1).count { data[it * w + c].toInt() != 0 }.toDouble() }
        }
        val ref = bands[5]
        return bands.filter { b -> b.any { it > 0 } }.map { Dewarp.shift(ref, it, 35).first }.toDoubleArray()
    }

    @Test
    fun straightensBentTextLines() {
        val page = textPage(full = true)
        val h = page.rows(); val w = page.cols()
        val mapX = FloatArray(h * w); val mapY = FloatArray(h * w)
        for (r in 0 until h) {
            val t = max(0.0, (r.toDouble() / h - 0.5) / 0.5)
            val bend = 40 * t * t // lines curl near the fold (bottom)
            for (c in 0 until w) { mapX[r * w + c] = (c - bend).toFloat(); mapY[r * w + c] = r.toFloat() }
        }
        val bent = Dewarp.remap(page, mapX, mapY, w, h)
        val before = bandShifts(bent).let { it.max() - it.min() }
        val after = bandShifts(Dewarp.straightenTextLines(bent)).let { it.max() - it.min() }
        assertTrue(before > 10, "before $before")
        assertTrue(after < before * 0.4, "bend $before px -> $after px")
    }

    // --- cleaning ---------------------------------------------------------------------

    private fun yellowPage(): Mat {
        val p = TestSupport.makePage()
        Core.add(p, Scalar(-25.0, -5.0, 0.0), p) // aged, yellowish paper must not count as skin
        return p
    }

    @Test
    fun removesFingerAtBorderOnly() {
        val page = yellowPage()
        val (same, none) = Fingers.removeFingers(page)
        assertEquals(0.0, none)
        assertSame(page, same)

        val img = page.clone()
        val h = img.rows(); val w = img.cols()
        val finger = Mat.zeros(h, w, CvType.CV_8U)
        Imgproc.ellipse(finger, Point(w - 40.0, h / 2.0), Size(160.0, 55.0), 0.0, 0.0, 360.0, Scalar(255.0), -1)
        val skin = Mat(h, w, CvType.CV_8UC3, Scalar(115.0, 145.0, 205.0))
        val noise = Mat(h, w, CvType.CV_8UC3).also { Core.randn(it, 0.0, 8.0) }
        Core.add(skin, noise, skin)
        skin.copyTo(img, finger)

        val (out, share) = Fingers.removeFingers(img)
        assertTrue(share > 0.005, "share $share")
        val diff = Mat().also { Core.absdiff(out, page, it) }
        val meanDiff = Core.mean(diff, finger).`val`.take(3).average()
        assertTrue(meanDiff < 25, "finger area should look like paper again ($meanDiff)")
    }

    @Test
    fun deblursOnlyBlurryPages() {
        val small = Mat().also { Imgproc.resize(TestSupport.makePage(), it, Size(), 0.5, 0.5, Imgproc.INTER_AREA) }
        assertTrue(Deblur.estimateBlur(small) < Deblur.BLUR_SIGMA)
        assertSame(small, Deblur.deblur(small).first)

        val blurred = Mat().also { Imgproc.GaussianBlur(small, it, Size(0.0, 0.0), 3.0) }
        val (out, sigma) = Deblur.deblur(blurred)
        assertTrue(sigma > Deblur.BLUR_SIGMA + 0.2, "sigma $sigma") // clearly classified as blurry
        assertTrue(Deblur.estimateBlur(out) < sigma)
        if (ocr.available) {
            val before = recall(ocr.recognize(Enhance.toGray(Enhance.flatten(blurred))))
            val after = recall(ocr.recognize(Enhance.toGray(Enhance.flatten(out))))
            assertTrue(after > before + 0.2, "recall $before -> $after")
        }
    }

    @Test
    fun removesPenAndPencilKeepsPrint() {
        val page = TestSupport.makePage()
        val marked = page.clone()
        Imgproc.line(marked, Point(100.0, 1000.0), Point(1100.0, 1100.0), Scalar(200.0, 60.0, 20.0), 5) // blue ballpoint
        Imgproc.putText(marked, "ok!", Point(900.0, 1300.0), Imgproc.FONT_HERSHEY_SCRIPT_SIMPLEX, 3.0, Scalar(30.0, 30.0, 210.0), 6) // red pen
        Imgproc.line(marked, Point(150.0, 1500.0), Point(1000.0, 1450.0), Scalar(165.0, 165.0, 165.0), 4) // pencil
        Imgproc.line(marked, Point(120.0, 300.0), Point(1100.0, 330.0), Scalar(170.0, 170.0, 170.0), 3) // pencil through the text
        val (clean, share) = Handwriting.removeHandwriting(Enhance.flatten(marked))
        assertTrue(share > 0.002, "share $share")

        val diff = Mat().also { Core.absdiff(marked, page, it) }
        val marks = Mat().also { Imgproc.cvtColor(diff, it, Imgproc.COLOR_BGR2GRAY); Core.compare(it, Scalar(0.0), it, Core.CMP_GT) }
        val cleanGray = Enhance.toGray(clean)
        assertTrue(Core.mean(cleanGray, marks).`val`[0] > 235, "marks should be gone")
        val printInk = Mat().also { Core.compare(Enhance.toGray(Enhance.flatten(page)), Scalar(100.0), it, Core.CMP_LT) }
        val keptDark = Mat().also { Core.compare(cleanGray, Scalar(128.0), it, Core.CMP_LT); Core.bitwise_and(it, printInk, it) }
        assertTrue(Core.countNonZero(keptDark).toDouble() / Core.countNonZero(printInk) > 0.97, "black print must survive")
    }
}
