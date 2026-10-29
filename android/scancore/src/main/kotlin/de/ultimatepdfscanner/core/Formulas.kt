package de.ultimatepdfscanner.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Rect
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Display formulas of a page, found by PP-DocLayout-S (PaddlePaddle, Apache-2.0): the OCR cannot read
 * them, so exports show these regions as pictures. ONNX Runtime sessions may be used from several
 * threads, so one instance can be shared.
 */
class Formulas(layoutModel: ByteArray, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    }
    private val layout: OrtSession = env.createSession(layoutModel, options)

    /** Regions of display formulas, top to bottom, with a margin (the detector tends to clip the ends). */
    fun detect(page: Mat): List<Rect> = matScope {
        val bgr = if (page.channels() == 3) page else mat().also { Imgproc.cvtColor(page, it, Imgproc.COLOR_GRAY2BGR) }
        val small = mat()
        Imgproc.resize(bgr, small, Size(LAYOUT_SIZE.toDouble(), LAYOUT_SIZE.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val rgb = mat().also { Imgproc.cvtColor(small, it, Imgproc.COLOR_BGR2RGB) }
        val f = mat().also { rgb.convertTo(it, CvType.CV_32FC3, 1.0 / 255) }
        val planes = ArrayList<Mat>()
        Core.split(f, planes)
        planes.forEach { track(it) }
        val n = LAYOUT_SIZE * LAYOUT_SIZE
        val data = FloatArray(3 * n)
        val plane = FloatArray(n)
        planes.forEachIndexed { c, p ->
            p.get(0, 0, plane)
            for (i in 0 until n) data[c * n + i] = ((plane[i] - MEAN[c]) / STD[c]).toFloat()
        }
        val w = page.cols(); val h = page.rows()
        val image = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, LAYOUT_SIZE.toLong(), LAYOUT_SIZE.toLong()))
        val scale = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(LAYOUT_SIZE.toFloat() / h, LAYOUT_SIZE.toFloat() / w)), longArrayOf(1, 2))
        image.use { scale.use {
            layout.run(mapOf("image" to image, "scale_factor" to scale)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val boxes = out.get(0).value as Array<FloatArray> // class, score, x0, y0, x1, y1
                val found = boxes.filter { it[0].toInt() == FORMULA_CLASS && it[1] >= MIN_SCORE }.map { b ->
                    val pad = min((b[5] - b[3]) * PAD, w / 200f) // tall formulas: no neighbouring lines
                    val x0 = max(0, (b[2] - 4 * pad).toInt()); val y0 = max(0, (b[3] - pad).toInt())
                    val x1 = min(w, (b[4] + 4 * pad).roundToInt()); val y1 = min(h, (b[5] + pad).roundToInt())
                    Rect(x0, y0, x1 - x0, y1 - y0)
                }.filter { it.width > 4 && it.height > 4 }
                merged(found).sortedBy { it.y }
            }
        } }
    }

    override fun close() { layout.close(); options.close() }

    /** A paragraph of a page with formulas: text, or the formula with this index and its equation number, e.g. "(12)". */
    sealed interface Part {
        class Text(val text: String) : Part
        class Formula(val index: Int, val number: String? = null) : Part
    }

    companion object {
        private const val LAYOUT_SIZE = 480
        private val MEAN = doubleArrayOf(0.485, 0.456, 0.406)
        private val STD = doubleArrayOf(0.229, 0.224, 0.225)
        private const val FORMULA_CLASS = 7 // "formula" in PP-DocLayout's 23 classes
        private const val MIN_SCORE = 0.5f
        private const val PAD = 0.15f // of the box height, four times that left and right

        /** Boxes overlapping by more than a third of the smaller one are one formula seen twice: their union. */
        internal fun merged(boxes: List<Rect>): List<Rect> {
            val out = boxes.toMutableList()
            var i = 0
            while (i < out.size) {
                val j = (i + 1 until out.size).firstOrNull { k ->
                    val a = out[i]; val b = out[k]
                    val iw = min(a.x + a.width, b.x + b.width) - max(a.x, b.x)
                    val ih = min(a.y + a.height, b.y + b.height) - max(a.y, b.y)
                    iw > 0 && ih > 0 && iw.toLong() * ih > min(a.area(), b.area()) / 3
                }
                if (j == null) { i++; continue }
                val a = out[i]; val b = out.removeAt(j)
                val x0 = min(a.x, b.x); val y0 = min(a.y, b.y)
                out[i] = Rect(x0, y0, max(a.x + a.width, b.x + b.width) - x0, max(a.y + a.height, b.y + b.height) - y0)
            }
            return out
        }

        /** Equation numbers as printed beside a formula: (12), (4.3), (7a), also with a lost bracket. */
        private val EQUATION_NUMBER = Regex("""\(?\d{1,4}(?:[.,]\d{1,3})?[a-z]?\)?""")

        /**
         * Paragraphs of a page in reading order with each formula after the nearest line above it (or
         * before it on the same row) in the same column. Words inside a formula region are dropped (the
         * OCR cannot read formulas); a lone equation number beside a formula goes with the formula.
         */
        fun layoutText(words: List<Word>, formulas: List<Rect>): List<Part> {
            fun inside(w: Word, r: Rect): Boolean {
                val cx = (w.x0 + w.x1) / 2; val cy = (w.y0 + w.y1) / 2
                return cx >= r.x && cx < r.x + r.width && cy >= r.y && cy < r.y + r.height
            }
            val outside = words.filter { w -> formulas.none { inside(w, it) } }
            val numbers = arrayOfNulls<String>(formulas.size)
            val numberLines = HashSet<Triple<Int, Int, Int>>()
            for ((key, ws) in outside.groupBy { Triple(it.block, it.paragraph, it.line) }) {
                val text = ws.joinToString("") { it.text }
                if (!EQUATION_NUMBER.matches(text)) continue
                val cy = ws.sumOf { (it.y0 + it.y1) / 2 } / ws.size
                val i = formulas.indices.firstOrNull { val f = formulas[it]; numbers[it] == null && cy >= f.y && cy < f.y + f.height } ?: continue
                numbers[i] = text.trim('(', ')').let { "($it)" }
                numberLines += key
            }
            val kept = outside.filter { Triple(it.block, it.paragraph, it.line) !in numberLines }
            val lines = kept.groupBy { Triple(it.block, it.paragraph, it.line) }
            val keys = lines.keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            // anchor: index of the line the formula follows (-1: before all text); the column is that of the
            // line's text block, as a short line above an indented formula need not overlap it
            val blockX = kept.groupBy { it.block }.mapValues { (_, ws) -> ws.minOf { it.x0 } to ws.maxOf { it.x1 } }
            fun anchor(f: Rect, sameColumn: Boolean): Int {
                var best = -1
                var bestBottom = Int.MIN_VALUE
                keys.forEachIndexed { k, key ->
                    val ws = lines.getValue(key)
                    val (x0, x1) = blockX.getValue(key.first)
                    val y0 = ws.minOf { it.y0 }; val y1 = ws.maxOf { it.y1 }
                    val before = (y0 + y1) / 2 < f.y + f.height / 2 || (y0 < f.y + f.height && y1 > f.y && ws.maxOf { it.x1 } <= f.x + f.height)
                    if ((!sameColumn || (x1 > f.x && x0 < f.x + f.width)) && before && y1 > bestBottom) { best = k; bestBottom = y1 }
                }
                return best
            }
            val anchors = formulas.map { f -> anchor(f, true).takeIf { it >= 0 } ?: anchor(f, false) }
            val parts = ArrayList<Part>()
            fun formulasAfter(k: Int) = formulas.indices.filter { anchors[it] == k }.sortedBy { formulas[it].y }.forEach { parts += Part.Formula(it, numbers[it]) }
            formulasAfter(-1)
            var from = 0
            for (k in keys.indices) {
                if (anchors.none { it == k } && k < keys.lastIndex) continue
                val segment = keys.subList(from, k + 1).flatMap { lines.getValue(it) }
                pageText(segment).split("\n\n").filter { it.isNotBlank() }.forEach { parts += Part.Text(it) }
                formulasAfter(k)
                from = k + 1
            }
            return parts
        }
    }
}
