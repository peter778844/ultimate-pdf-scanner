package de.ultimatepdfscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * Text size normalisation for OCR. Tesseract reads best at a certain letter size; book pages from
 * a phone photo have a median letter height of only ~16 px (series of 129 pages: 12-19 px), large
 * cover titles 100 px and more. Scaling every page so its median letter is [TARGET] px high
 * read 6 % more dictionary words on that series (19519 vs 18391; 26-40 px only up to +7.4 % at much
 * higher cost); binarising (Sauvola) or sharpening read fewer.
 */
object TextSize {
    const val TARGET = 20.0 // median letter height (px) of the OCR input
    const val TOLERANCE = 0.18 // closer than this to the target: leave the page as it is
    const val MIN_LETTERS = 50 // fewer letter-sized marks: no reliable measurement
    const val MAX_UPSCALE = 2.5 // memory: a 2000 px page stays below 5000 px

    /** Median height (px) of letter-sized ink marks, or null if the page has too few of them. */
    fun charHeight(gray: Mat): Double? = matScope {
        val ink = mat()
        Imgproc.threshold(gray, ink, 0.0, 255.0, Imgproc.THRESH_BINARY_INV + Imgproc.THRESH_OTSU)
        val stats = mat()
        val n = Imgproc.connectedComponentsWithStats(ink, mat(), stats, mat(), 8, CvType.CV_32S)
        val heights = ArrayList<Int>()
        for (i in 1 until n) {
            val h = stats.get(i, Imgproc.CC_STAT_HEIGHT)[0].toInt()
            val w = stats.get(i, Imgproc.CC_STAT_WIDTH)[0].toInt()
            if (h in 6..150 && w in 2..3 * h) heights += h
        }
        if (heights.size <= MIN_LETTERS) return@matScope null
        heights.sort()
        heights[heights.size / 2].toDouble()
    }

    const val ACCURATE_TARGET = 26.0 // for small print: +0.6 % more dictionary words than 20 px on the series, ~same time

    /**
     * Factor by which [gray] should be scaled for OCR to letters of [target] px (1.0: leave it).
     * Without [upscale] small print is left as it is (fast mode: large print is still reduced).
     */
    fun scaleFor(gray: Mat, target: Double = TARGET, upscale: Boolean = true): Double {
        val h = charHeight(gray) ?: return 1.0
        val s = (target / h).coerceAtMost(if (upscale) MAX_UPSCALE else 1.0)
        return if (s > 1 - TOLERANCE && s < 1 + TOLERANCE) 1.0 else s
    }

    /** OCR on the page scaled to letters of [target] px; word boxes come back in page pixels. */
    fun recognize(engine: OcrEngine, gray: Mat, target0: Double = TARGET, upscale: Boolean = true): List<Word> {
        val target = engine.preferredTextHeight ?: target0
        val letters = charHeight(gray)
        val s = if (target <= 0) 1.0 else scaleFor(gray, target, upscale)
        val scaled = if (s == 1.0) gray else Mat().also {
            Imgproc.resize(gray, it, Size(gray.cols() * s, gray.rows() * s), 0.0, 0.0, if (s < 1) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
        }
        // tables of contents: dot leaders removed, read as one block (entries keep their page numbers)
        val leaders = letters?.let { Leaders.remove(scaled, it * s) }
        val toc = leaders != null && leaders.rows >= Leaders.MIN_ROWS
        try {
            fun back(v: Int) = (v / s).roundToInt()
            val words = if (toc) engine.recognize(leaders!!.image, singleBlock = true) else engine.recognize(scaled)
            return if (s == 1.0) words else words.map { it.copy(x0 = back(it.x0), y0 = back(it.y0), x1 = back(it.x1), y1 = back(it.y1)) }
        } finally {
            if (scaled !== gray) scaled.release()
            leaders?.image?.release()
        }
    }
}
