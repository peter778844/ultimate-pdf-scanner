package de.ultimatepdfscanner.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.RotatedRect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * OCR with PaddleOCR PP-OCRv5 models (Apache-2.0) in ONNX Runtime: a DB text-line detector and a
 * CTC line recogniser (the Latin model reads German, English, French ... incl. umlauts and ß).
 * Measured on six hand-transcribed pages of the user's series: 94.6 % of the words read correctly
 * (mobile detector) against 91.0 % for Tesseract, with fewer junk words.
 *
 * Pre- and post-processing follow PaddleOCR / RapidOCR: detector input min side >= 736 px,
 * multiple of 32, (x/255 - 0.5)/0.5; probability > 0.3, boxes scored >= 0.5, grown by
 * area * 1.6 / perimeter; recogniser input 48 px high, batches of similar width; CTC greedy decoding
 * with the character list stored in the model. Word boxes come from the CTC time steps. Lines are
 * grouped into blocks and put in reading order (columns) here, since the models give lines only.
 *
 * Other scripts: [characters] (the recogniser's character list, when the model does not carry it) and
 * [rtl] for right-to-left scripts (Arabic): the recogniser reads the line image left to right, so its
 * text is turned into reading order, and words, lines and columns are ordered from the right.
 * [trustLineClassifier] false: the 0/180-degree line classifier (trained on Chinese and Latin lines)
 * does not decide alone; measured on Devanagari: an upright page voted upside down.
 *
 * With [handwriting], lines the printed-text recogniser reads unsurely (score < [HANDWRITING_BELOW])
 * are read again as handwriting; that reading wins if it is clearly surer (see [HANDWRITING_MIN]).
 *
 * Thread-safe in the sense of ONNX Runtime: one instance may serve several threads.
 */
class PaddleOcr(detModel: ByteArray, recModel: ByteArray, threads: Int = 2,
                override val preferredTextHeight: Double? = TEXT_HEIGHT, clsModel: ByteArray? = null,
                private val handwriting: HandwritingReader? = null, characters: List<String>? = null,
                private val rtl: Boolean = false, private val trustLineClassifier: Boolean = true,
                leanMemory: Boolean = false) : OcrEngine, AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // phones with little memory: no arena (it keeps ~650 MB per page of different line widths, measured; ~20 % slower)
        if (leanMemory) { setMemoryPatternOptimization(false); setCPUArenaAllocator(false) }
    }
    private val det: OrtSession = env.createSession(detModel, options)
    private val rec: OrtSession = env.createSession(recModel, options)
    private val cls: OrtSession? = clsModel?.let { env.createSession(it, options) }

    /** With the line classifier the page orientation is decided by the models, not by reading confidence. */
    override val orientationReliable: Boolean get() = cls != null
    internal val chars: List<String> = run {
        val list = characters ?: rec.metadata.customMetadata["character"]?.split('\n')?.map { it.trimEnd('\r') }?.filter { it.isNotEmpty() }
            ?: error("recognition model without character list")
        // index 0 = CTC blank, the last class is the space (appended as in PaddleOCR). The Java API
        // decodes a few characters outside the BMP wrongly (one went missing), so the list is fitted
        // to the model's class count: the space must stay the last class.
        val classes = (rec.outputInfo.values.first().info as ai.onnxruntime.TensorInfo).shape.last().toInt()
        val body = if (classes > 2) (list + List(maxOf(0, classes - 2 - list.size)) { "" }).take(classes - 2) else list
        listOf("") + body + " "
    }

    /** One detected text line: its corner points in page pixels (tl, tr, br, bl). */
    class Line(val box: Array<Point>, val text: String, val score: Double, val words: List<Word>)

    override fun recognize(gray: Mat): List<Word> = recognize(gray, singleBlock = false)

    override fun recognize(gray: Mat, singleBlock: Boolean): List<Word> {
        val lines = readLines(gray)
        return order(lines, singleBlock)
    }

    /** Text lines with their words, unordered (detector order). */
    fun readLines(image: Mat): List<Line> = matScope {
        val bgr = if (image.channels() == 3) image else mat().also { Imgproc.cvtColor(image, it, Imgproc.COLOR_GRAY2BGR) }
        val boxes = detect(bgr)
        val crops = boxes.map { track(crop(bgr, it)) }
        val results = recognizeCrops(crops)
        boxes.indices.mapNotNull { i ->
            var (text, score, cols, steps) = results[i]
            if (handwriting != null && score < HANDWRITING_BELOW) {
                val hw = handwriting.read(crops[i])
                if (hw.score >= HANDWRITING_MIN && hw.score > score + HANDWRITING_MARGIN && hw.text.isNotBlank()) { text = hw.text; score = hw.score; cols = hw.cols; steps = hw.stepsPerCrop }
            }
            if (score < TEXT_SCORE || text.isBlank()) return@mapNotNull null
            if (rtl) fillGaps(crops[i], text, cols, steps).let { (t, c) -> text = t; cols = c }
            val words = words(boxes[i], crops[i], text, cols, steps)
            if (rtl) Line(boxes[i], logicalOrder(text), score, words.reversed().map { it.copy(text = logicalOrder(it.text)) })
            else Line(boxes[i], text, score, words)
        }
    }

    /**
     * The Arabic recogniser skips Western numbers inside a line ("حوالي  كيلومترا": two spaces where
     * 6650 stands) but reads them alone: each double space is read again from its piece of the line.
     */
    private fun fillGaps(crop: Mat, text: String, cols: IntArray, stepsPerCrop: Double): Pair<String, IntArray> = matScope {
        var t = text; var c = cols
        var k = t.indexOf("  ")
        while (k >= 0) {
            val px = crop.cols() / max(1e-6, stepsPerCrop) // crop pixels per time step
            val x0 = ((c[k] + 1) * px).toInt().coerceIn(0, crop.cols() - 1)
            val x1 = (c[k + 1] * px).toInt().coerceIn(x0 + 1, crop.cols())
            val piece = if (x1 - x0 >= crop.rows() / 2) recognizeCrops(listOf(track(crop.submat(0, crop.rows(), x0, x1)))).first() else null
            val found = piece?.takeIf { it.score >= TEXT_SCORE }?.text?.trim().orEmpty()
            if (found.isNotEmpty()) {
                // characters spread over the gap's time steps, in the visual order the line has
                val steps = IntArray(found.length) { c[k] + 1 + (c[k + 1] - c[k] - 1) * it / found.length }
                t = t.substring(0, k + 1) + found + t.substring(k + 1)
                c = c.copyOfRange(0, k + 1) + steps + c.copyOfRange(k + 1, c.size)
            }
            k = t.indexOf("  ", k + 1 + found.length)
        }
        t to c
    }

    // --- detection -------------------------------------------------------------------------

    private fun detect(bgr: Mat): List<Array<Point>> = matScope {
        val h = bgr.rows(); val w = bgr.cols()
        var ratio = if (min(h, w) < DET_MIN_SIDE) DET_MIN_SIDE.toDouble() / min(h, w) else 1.0
        if (max(h, w) * ratio > DET_MAX_SIDE) ratio = DET_MAX_SIDE.toDouble() / max(h, w) // bounded work on the phone
        val rh = max(32, ((h * ratio).toInt() / 32.0).roundToInt() * 32)
        val rw = max(32, ((w * ratio).toInt() / 32.0).roundToInt() * 32)
        val resized = mat()
        resizeLinear(bgr, resized, Size(rw.toDouble(), rh.toDouble()))
        val input = chw(resized)
        val prob = OnnxTensor.createTensor(env, FloatBuffer.wrap(input), longArrayOf(1, 3, rh.toLong(), rw.toLong())).use { t ->
            det.run(mapOf(det.inputNames.first() to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val map = (out.get(0).value as Array<Array<Array<FloatArray>>>)[0][0] // [H][W]
                val m = Mat(rh, rw, CvType.CV_32F)
                for (y in 0 until rh) m.put(y, 0, map[y])
                track(m)
            }
        }
        val bitmap = mat()
        Imgproc.threshold(prob, bitmap, DET_THRESH, 255.0, Imgproc.THRESH_BINARY)
        val bin8 = mat().also { bitmap.convertTo(it, CvType.CV_8U) }
        Imgproc.dilate(bin8, bin8, Mat.ones(2, 2, CvType.CV_8U).also { track(it) })
        val contours = ArrayList<MatOfPoint>()
        Imgproc.findContours(bin8, contours, mat(), Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        contours.forEach { track(it) }
        val sx = w.toDouble() / rw; val sy = h.toDouble() / rh
        val out = ArrayList<Array<Point>>()
        for (c in contours.take(MAX_CANDIDATES)) {
            val c2f = track(MatOfPoint2f(*c.toArray()))
            val rect = Imgproc.minAreaRect(c2f)
            if (min(rect.size.width, rect.size.height) < MIN_SIZE) continue
            if (boxScore(prob, rect) < BOX_THRESH) continue
            // unclip: a rectangle grown by area * ratio / perimeter on every side
            val rw0 = rect.size.width; val rh0 = rect.size.height
            val d = rw0 * rh0 * UNCLIP_RATIO / (2 * (rw0 + rh0))
            val grown = RotatedRect(rect.center, Size(rw0 + 2 * d, rh0 + 2 * d), rect.angle)
            if (min(grown.size.width, grown.size.height) < MIN_SIZE + 2) continue
            val pts = arrayOfNulls<Point>(4).also { grown.points(it) }.map { p ->
                Point((p!!.x * sx).roundToInt().toDouble().coerceIn(0.0, w - 1.0), (p.y * sy).roundToInt().toDouble().coerceIn(0.0, h - 1.0))
            }
            val ordered = clockwise(pts)
            if (hypot(ordered[0].x - ordered[1].x, ordered[0].y - ordered[1].y) <= 3 ||
                hypot(ordered[0].x - ordered[3].x, ordered[0].y - ordered[3].y) <= 3) continue
            out += ordered
        }
        out
    }

    /** Mean probability inside the box ("fast" score of PaddleOCR). */
    private fun boxScore(prob: Mat, rect: RotatedRect): Double = matScope {
        val pts = arrayOfNulls<Point>(4).also { rect.points(it) }.map { it!! }
        val x0 = pts.minOf { it.x }.toInt().coerceIn(0, prob.cols() - 1); val x1 = ceil(pts.maxOf { it.x }).toInt().coerceIn(0, prob.cols() - 1)
        val y0 = pts.minOf { it.y }.toInt().coerceIn(0, prob.rows() - 1); val y1 = ceil(pts.maxOf { it.y }).toInt().coerceIn(0, prob.rows() - 1)
        val mask = track(Mat.zeros(y1 - y0 + 1, x1 - x0 + 1, CvType.CV_8U))
        Imgproc.fillPoly(mask, listOf(MatOfPoint(*pts.map { Point((it.x - x0).roundToInt().toDouble(), (it.y - y0).roundToInt().toDouble()) }.toTypedArray())), Scalar(1.0))
        Core.mean(track(prob.submat(y0, y1 + 1, x0, x1 + 1)), mask).`val`[0]
    }

    /** tl, tr, br, bl as in PaddleOCR (left-most pair sorted by y, right-most pair sorted by y). */
    private fun clockwise(p: List<Point>): Array<Point> {
        val xs = p.sortedBy { it.x }
        val left = xs.take(2).sortedBy { it.y }
        val right = xs.drop(2).sortedBy { it.y }
        return arrayOf(left[0], right[0], right[1], left[1])
    }

    // --- recognition -----------------------------------------------------------------------

    private fun crop(bgr: Mat, box: Array<Point>): Mat = matScope {
        val cw = max(hypot(box[0].x - box[1].x, box[0].y - box[1].y), hypot(box[2].x - box[3].x, box[2].y - box[3].y)).toInt()
        val ch = max(hypot(box[0].x - box[3].x, box[0].y - box[3].y), hypot(box[1].x - box[2].x, box[1].y - box[2].y)).toInt()
        val m = Imgproc.getPerspectiveTransform(track(MatOfPoint2f(*box)),
            track(MatOfPoint2f(Point(0.0, 0.0), Point(cw.toDouble(), 0.0), Point(cw.toDouble(), ch.toDouble()), Point(0.0, ch.toDouble()))))
        track(m)
        val out = Mat()
        Imgproc.warpPerspective(bgr, out, m, Size(max(1, cw).toDouble(), max(1, ch).toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE)
        if (out.rows() >= 1.5 * out.cols()) { val r = Mat(); Core.rotate(out, r, Core.ROTATE_90_COUNTERCLOCKWISE); out.release(); r } else out
    }

    /** (text, mean probability, time step of every character, number of time steps scaled to the crop width). */
    private data class RecResult(val text: String, val score: Double, val cols: IntArray, val stepsPerCrop: Double)

    private fun recognizeCrops(crops: List<Mat>): List<RecResult> {
        val results = arrayOfNulls<RecResult>(crops.size)
        val order = crops.indices.sortedBy { crops[it].cols().toDouble() / max(1, crops[it].rows()) }
        for (batch in order.chunked(REC_BATCH)) matScope {
            var maxRatio = REC_WIDTH.toDouble() / REC_HEIGHT
            for (i in batch) maxRatio = max(maxRatio, crops[i].cols().toDouble() / max(1, crops[i].rows()))
            val width = (REC_HEIGHT * maxRatio).toInt()
            val data = FloatArray(batch.size * 3 * REC_HEIGHT * width)
            val resizedW = IntArray(batch.size)
            batch.forEachIndexed { b, i ->
                val c = crops[i]
                val rwid = min(width, ceil(REC_HEIGHT * c.cols().toDouble() / max(1, c.rows())).toInt()).coerceAtLeast(1)
                resizedW[b] = rwid
                val r = mat()
                resizeLinear(c, r, Size(rwid.toDouble(), REC_HEIGHT.toDouble()))
                val px = ByteArray(rwid * REC_HEIGHT * 3).also { r.get(0, 0, it) }
                val base = b * 3 * REC_HEIGHT * width
                for (y in 0 until REC_HEIGHT) for (x in 0 until rwid) for (ch in 0 until 3) {
                    data[base + ch * REC_HEIGHT * width + y * width + x] = ((px[(y * rwid + x) * 3 + ch].toInt() and 0xff) / 255f - 0.5f) / 0.5f
                }
            }
            OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(batch.size.toLong(), 3, REC_HEIGHT.toLong(), width.toLong())).use { t ->
                rec.run(mapOf(rec.inputNames.first() to t)).use { out ->
                    @Suppress("UNCHECKED_CAST")
                    val probs = out.get(0).value as Array<Array<FloatArray>> // [B][T][C]
                    batch.forEachIndexed { b, i ->
                        val steps = probs[b]
                        val sb = StringBuilder(); val cols = ArrayList<Int>(); var sum = 0.0; var n = 0
                        var prev = -1
                        for (t in steps.indices) {
                            var best = 0; var bp = steps[t][0]
                            for (k in 1 until steps[t].size) if (steps[t][k] > bp) { bp = steps[t][k]; best = k }
                            if (best != 0 && best != prev && best < chars.size) {
                                sb.append(chars[best]); cols += t; sum += bp; n++
                            }
                            prev = best
                        }
                        // time steps cover the padded width; the crop occupies resizedW of it
                        val stepsPerCrop = steps.size.toDouble() * resizedW[b] / width
                        results[i] = RecResult(sb.toString(), if (n > 0) sum / n else 0.0, cols.toIntArray(), stepsPerCrop)
                    }
                }
            }
        }
        return results.map { it!! }
    }

    /** Words of a line with boxes in page pixels, from the time steps of their characters. */
    private fun words(box: Array<Point>, crop: Mat, text: String, cols: IntArray, stepsPerCrop: Double): List<Word> {
        val out = ArrayList<Word>()
        var start = -1
        fun emit(from: Int, to: Int) { // characters [from, to)
            val step = 1.0 / max(1e-6, stepsPerCrop) // share of the line per time step
            val u0 = ((cols[from] - 0.5) * step).coerceIn(0.0, 1.0)
            val u1 = ((cols[to - 1] + 1.5) * step).coerceIn(0.0, 1.0)
            fun at(u: Double, top: Boolean): Point {
                val a = if (top) box[0] else box[3]; val b = if (top) box[1] else box[2]
                return Point(a.x + (b.x - a.x) * u, a.y + (b.y - a.y) * u)
            }
            val pts = listOf(at(u0, true), at(u1, true), at(u1, false), at(u0, false))
            val word = text.substring(from, to)
            out += Word(word, 0f, pts.minOf { it.x }.roundToInt(), pts.minOf { it.y }.roundToInt(),
                pts.maxOf { it.x }.roundToInt(), pts.maxOf { it.y }.roundToInt(), 0, 0, 0)
        }
        for (i in text.indices) {
            if (text[i].isWhitespace()) { if (start >= 0) emit(start, i); start = -1 }
            else if (start < 0) start = i
        }
        if (start >= 0) emit(start, text.length)
        return out
    }

    // --- reading order ---------------------------------------------------------------------

    /**
     * Words with block/paragraph/line numbers in reading order. First the detector's boxes that sit
     * on one text line close together are joined (it cuts lines at wide gaps, e.g. a last word);
     * these lines are grouped into blocks (below each other, overlapping horizontally, close
     * together), and the blocks are ordered by a recursive XY cut: a horizontal gap across all blocks
     * splits top from bottom, else a vertical gap splits the left column from the right one.
     * [singleBlock]: one block, lines top to bottom. Heights and centres come from the rotated boxes,
     * because on a slightly skewed page the axis-aligned boxes of neighbouring lines overlap.
     */
    private fun order(lines: List<Line>, singleBlock: Boolean): List<Word> {
        if (lines.isEmpty()) return emptyList()
        class L(val line: Line) {
            val x0 = line.box.minOf { it.x }; val x1 = line.box.maxOf { it.x }
            val y0 = line.box.minOf { it.y }; val y1 = line.box.maxOf { it.y }
            val lh = hypot(line.box[3].x - line.box[0].x, line.box[3].y - line.box[0].y)
            val cy = line.box.sumOf { it.y } / 4
        }
        class Row(first: L) {
            val parts = mutableListOf(first)
            val x0 get() = parts.minOf { it.x0 }
            val x1 get() = parts.maxOf { it.x1 }
            val y0 get() = parts.minOf { it.y0 }
            val y1 get() = parts.maxOf { it.y1 }
            val cy get() = parts.sumOf { it.cy } / parts.size
            val lh get() = parts.maxOf { it.lh }
        }
        // 1. text lines: boxes at the same height with a gap of less than JOIN_GAP line heights; in a
        // table of contents also across the gap the detector leaves in the dot leader ("Exzitonen ....  ..312")
        val all = lines.map { L(it) }
        // a gap that stays free in the lines above and below is the gutter between two columns, however
        // narrow (magazines: about one line height); text is on both sides of it, hardly any box crosses it
        fun gutter(x: Double, cy: Double, lh: Double): Boolean {
            val near = all.filter { abs(it.cy - cy) < GUTTER_REACH * lh }
            return near.count { it.x1 <= x } >= 3 && near.count { it.x0 >= x } >= 3 && near.count { it.x0 < x && it.x1 > x } <= GUTTER_CROSSING * near.size
        }
        val rowsAll = ArrayList<Row>()
        for (l in all.sortedBy { it.x0 }) {
            val row = rowsAll.firstOrNull { r ->
                abs(r.cy - l.cy) < 0.5 * min(r.lh, l.lh) && ((l.x0 - r.x1 < JOIN_GAP * max(r.lh, l.lh) && !gutter((r.x1 + l.x0) / 2, l.cy, max(r.lh, l.lh))) ||
                    (PAGE_NUMBER.matches(l.line.text) && (LEADER_END.containsMatchIn(r.parts.last().line.text) || l.line.text.count { it == '.' } >= 2)))
            }
            if (row != null) row.parts += l else rowsAll += Row(l)
        }
        rowsAll.sortBy { it.cy }
        // 2. blocks of lines
        val blocks = ArrayList<MutableList<Row>>()
        if (singleBlock) blocks += rowsAll.toMutableList() else for (r in rowsAll) {
            val target = blocks.lastOrNull { b ->
                val last = b.last()
                val overlap = min(last.x1, r.x1) - max(last.x0, r.x0)
                val dy = r.cy - last.cy
                // a much larger line is a heading: its own block, or it would join the column below to the text above
                overlap > 0.5 * min(last.x1 - last.x0, r.x1 - r.x0) && dy > 0.5 * min(last.lh, r.lh) && dy < 2.4 * max(last.lh, r.lh) &&
                    max(last.lh, r.lh) < HEADING_RATIO * min(last.lh, r.lh)
            }
            if (target != null) target += r else blocks += mutableListOf(r)
        }
        // 3. reading order of the blocks
        fun bx0(b: List<Row>) = b.minOf { it.x0 }
        fun bx1(b: List<Row>) = b.maxOf { it.x1 }
        fun by0(b: List<Row>) = b.minOf { it.y0 }
        fun by1(b: List<Row>) = b.maxOf { it.y1 }
        fun cut(bs: List<List<Row>>): List<List<Row>> {
            if (bs.size <= 1) return bs
            // horizontal cut: the first gap (top down) that no block spans
            val byY = bs.sortedBy { by0(it) }
            var bottom = by1(byY[0])
            for (k in 1 until byY.size) {
                if (by0(byY[k]) >= bottom) return cut(byY.subList(0, k)) + cut(byY.subList(k, byY.size))
                bottom = max(bottom, by1(byY[k]))
            }
            // vertical cut: left column first (right column first in right-to-left scripts)
            val byX = bs.sortedBy { bx0(it) }
            var right = bx1(byX[0])
            for (k in 1 until byX.size) {
                if (bx0(byX[k]) >= right) {
                    val left = cut(byX.subList(0, k)); val rest = cut(byX.subList(k, byX.size))
                    return if (rtl) rest + left else left + rest
                }
                right = max(right, bx1(byX[k]))
            }
            return byY
        }
        val words = ArrayList<Word>()
        cut(blocks).forEachIndexed { bi, block ->
            block.sortedBy { it.cy }.forEachIndexed { li, row ->
                for (l in row.parts.sortedBy { if (rtl) -it.x1 else it.x0 }) for (w in l.line.words) {
                    words += w.copy(conf = (l.line.score * 100).toFloat(), block = bi + 1, paragraph = 1, line = li + 1)
                }
            }
        }
        return words
    }

        // --- orientation -----------------------------------------------------------------------

    /**
     * Clockwise turn that makes the page upright. The recogniser reads upside-down text with high
     * confidence too, so confidence cannot decide (measured: 3 of 10 disputed pages turned wrongly).
     * Instead: the line axis from the shape of the detected text boxes (wide = horizontal lines),
     * then PaddleOCR's text-line orientation classifier (0 / 180 degrees) votes over the longest lines.
     * Without the classifier the reading confidence is used as with Tesseract.
     */
    override fun orientation(gray: Mat, hint: Int?): Int? {
        val classifier = cls ?: return Orientation.detect(gray, hint) { candidate ->
            val lines = readLines(candidate)
            val n = lines.sumOf { it.text.length }
            if (n == 0) 0.0 else lines.sumOf { it.score * it.text.length } / n * 100
        }
        return matScope {
            val (small, _) = downscale(gray, ORIENT_SIZE)
            val bgr = if (small.channels() == 3) small else mat().also { Imgproc.cvtColor(small, it, Imgproc.COLOR_GRAY2BGR) }
            var horizontal = 0.0; var vertical = 0.0
            for (b in detect(bgr)) {
                val w = hypot(b[1].x - b[0].x, b[1].y - b[0].y); val h = hypot(b[3].x - b[0].x, b[3].y - b[0].y)
                if (w > 1.5 * h) horizontal += w else if (h > 1.5 * w) vertical += h
            }
            if (horizontal + vertical == 0.0) return@matScope null
            val level = if (vertical > horizontal) 90 else 0
            val turned = if (level == 0) bgr else track(Orientation.rotate(bgr, level))
            // the longest lines (at least 3 times as wide as high) vote with their 180-degree probability
            val boxes = detect(turned).map { it to hypot(it[1].x - it[0].x, it[1].y - it[0].y) }
                .filter { (b, w) -> w >= 3 * hypot(b[3].x - b[0].x, b[3].y - b[0].y) }
                .sortedByDescending { it.second }.take(ORIENT_LINES)
            if (boxes.size < 2) return@matScope (hint?.takeIf { (it - level) % 180 == 0 }) ?: level
            val crops = boxes.map { track(crop(turned, it.first)) }
            var flip = 0.0; var weight = 0.0
            for (batch in crops.indices.chunked(REC_BATCH)) {
                val data = FloatArray(batch.size * 3 * CLS_HEIGHT * CLS_WIDTH)
                batch.forEachIndexed { k, i ->
                    val c = crops[i]
                    val rw = min(CLS_WIDTH, ceil(CLS_HEIGHT * c.cols().toDouble() / max(1, c.rows())).toInt()).coerceAtLeast(1)
                    val r = mat(); resizeLinear(c, r, Size(rw.toDouble(), CLS_HEIGHT.toDouble()))
                    val px = ByteArray(rw * CLS_HEIGHT * 3).also { r.get(0, 0, it) }
                    val base = k * 3 * CLS_HEIGHT * CLS_WIDTH
                    for (y in 0 until CLS_HEIGHT) for (x in 0 until rw) for (ch in 0 until 3)
                        data[base + ch * CLS_HEIGHT * CLS_WIDTH + y * CLS_WIDTH + x] = ((px[(y * rw + x) * 3 + ch].toInt() and 0xff) / 255f - 0.5f) / 0.5f
                }
                OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(batch.size.toLong(), 3, CLS_HEIGHT.toLong(), CLS_WIDTH.toLong())).use { t ->
                    classifier.run(mapOf(classifier.inputNames.first() to t)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val p = out.get(0).value as Array<FloatArray> // [N][0 deg, 180 deg]
                        batch.forEachIndexed { k, i -> val w = boxes[i].second; flip += w * p[k][1]; weight += w }
                    }
                }
            }
            val vote = flip / weight
            // a close vote (bold or italic print confuses the small classifier, measured 0.43-0.64 on
            // typewritten pages): ascenders vs. descenders of the turned page decide if they are clear
            // few lines (a page of figures) make the vote itself unsure
            val unsure = !trustLineClassifier || vote in UNSURE_VOTE || (boxes.size < FEW_LINES && vote in 0.2..0.8)
            val ratio = if (unsure) Orientation.ascenderRatio(track(Enhance.toGray(turned))) else 1.0
            // then the page is read both ways: the right way up reads with clearly higher confidence
            // (handwritten formulas: vote 0.24-0.39 and ratio 0.80, near the turning threshold)
            val readGain = if (unsure) readConfidence(turned) - readConfidence(track(Orientation.rotate(turned, 180))) else 0.0
            if (Pipeline.DEBUG) println("PADDLE ORIENT level=$level horizontal=%.0f vertical=%.0f flip=%.2f lines=${boxes.size} ratio=%.2f readGain=%.3f".format(horizontal, vertical, vote, ratio, readGain))
            val flipped = when {
                !unsure -> vote > 0.5
                readGain >= READ_GAIN -> false
                readGain <= -READ_GAIN -> true
                // the ink ratio only where the reading does not lean the other way (math and script mislead it)
                ratio >= Orientation.UPRIGHT_RATIO && readGain >= 0 -> false
                ratio <= 1 / Orientation.UPRIGHT_RATIO && readGain <= 0 -> true
                // still undecided: the previous page of the same document, else as photographed
                hint != null && (hint - level + 360) % 180 == 0 -> hint != level
                else -> false
            }
            if (flipped) (level + 180) % 360 else level
        }
    }

    /** Mean character probability of the page read as it is (printed-text recogniser only: the handwriting one reads upside-down script surely too). */
    private fun readConfidence(bgr: Mat): Double = matScope {
        val boxes = detect(bgr)
        val results = recognizeCrops(boxes.map { track(crop(bgr, it)) })
        val n = results.sumOf { it.text.length }
        if (n == 0) 0.0 else results.sumOf { it.score * it.text.length } / n
    }

    private fun chw(bgr: Mat): FloatArray {
        val h = bgr.rows(); val w = bgr.cols()
        val px = ByteArray(h * w * 3).also { bgr.get(0, 0, it) }
        val out = FloatArray(3 * h * w)
        for (i in 0 until h * w) for (c in 0 until 3) out[c * h * w + i] = ((px[i * 3 + c].toInt() and 0xff) / 255f - 0.5f) / 0.5f
        return out
    }

    override fun close() { det.close(); rec.close(); cls?.close() }

    companion object {
        const val DET_MIN_SIDE = 736
        const val DET_MAX_SIDE = 3200
        const val DET_THRESH = 0.3
        const val BOX_THRESH = 0.5
        const val UNCLIP_RATIO = 1.6
        const val MIN_SIZE = 3.0
        const val MAX_CANDIDATES = 1000
        const val REC_HEIGHT = 48
        const val REC_WIDTH = 320
        const val REC_BATCH = 6
        const val TEXT_SCORE = 0.5
        private val LEADER_END = Regex("""(?:[.·…]\s*){2,}$""") // a line part ending in leader dots
        private val PAGE_NUMBER = Regex("""^[\s.·…]*\d{1,4}[a-z]?$""") // leader dots and a page number

        /**
         * The character list of PaddleOCR's inference.yml (PostProcess.character_dict): one entry per
         * line ("  - x"), plain or in single quotes ('' stands for one quote). Official ONNX exports
         * carry the list only there.
         */
        fun charactersFromYaml(yaml: String): List<String> {
            val lines = yaml.lines()
            val start = lines.indexOfFirst { it.trim() == "character_dict:" }
            require(start >= 0) { "no character_dict" }
            val out = ArrayList<String>()
            for (line in lines.drop(start + 1)) {
                if (!line.startsWith("  - ")) break
                val v = line.removePrefix("  - ").trimEnd('\r')
                out += if (v.length >= 2 && v.startsWith("'") && v.endsWith("'")) v.substring(1, v.length - 1).replace("''", "'")
                    else if (v.length >= 2 && v.startsWith("\"") && v.endsWith("\"")) v.substring(1, v.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
                    else v
            }
            return out
        }

        /**
         * Reading order of a right-to-left line read left to right (as PaddleOCR's pred_reverse): the
         * characters are reversed, but runs of digits, Latin letters and number punctuation keep their order.
         */
        internal fun logicalOrder(visual: String): String {
            val parts = ArrayList<String>()
            val run = StringBuilder()
            for (c in visual) {
                if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in " :*./%+-") run.append(c)
                else { if (run.isNotEmpty()) { parts += run.toString(); run.clear() }; parts += c.toString() }
            }
            if (run.isNotEmpty()) parts += run.toString()
            return parts.asReversed().joinToString("")
        }
        const val HANDWRITING_BELOW = 0.9 // printed lines score higher; handwriting read as print rarely does
        const val HANDWRITING_MIN = 0.8 // below, the handwriting reading of figure labels and formula remains is mostly junk
        const val HANDWRITING_MARGIN = 0.1
        const val TEXT_HEIGHT = 0.0 // see SeriesEval measurements: the detector separates close lines best unscaled
        const val ORIENT_SIZE = 1600 // working size of the orientation decision
        const val ORIENT_LINES = 24 // longest lines voting on 0 / 180 degrees
        const val FEW_LINES = 8
        val UNSURE_VOTE = 0.3..0.7 // share of 180-degree votes the classifier is not trusted with alone
        const val READ_GAIN = 0.05 // mean character probability one way up must exceed the other by this to decide
        const val CLS_HEIGHT = 48
        const val CLS_WIDTH = 192
        const val JOIN_GAP = 2.0 // boxes on one line this close (in line heights) belong together, unless a column gutter lies between
        const val HEADING_RATIO = 1.5 // measured: body lines 38-56 px, the heading above two columns 76 px
        const val GUTTER_REACH = 4.0 // lines within this many line heights above and below show whether a gap is a gutter
        const val GUTTER_CROSSING = 0.2 // share of those lines that may cross it (a heading over both columns)
    }
}
