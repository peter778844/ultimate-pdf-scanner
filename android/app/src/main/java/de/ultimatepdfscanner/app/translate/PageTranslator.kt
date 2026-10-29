package de.ultimatepdfscanner.app.translate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextPaint
import de.ultimatepdfscanner.core.Word
import de.ultimatepdfscanner.core.pageText
import java.io.File

/**
 * Lays a translation over a page image, so the translated page looks like the original: the
 * lines of each paragraph are painted in the paper colour and the translated paragraph is set
 * into the paragraph's box, as large as fits, in the colour of the print. Pictures stay as they are.
 */
object PageTranslator {
    private const val MIN_TEXT_PX = 6f
    private const val SAME_PRINT = 1.2f // line heights within 20 %: the same type size

    private const val MIN_CONFIDENCE = 60f // below, mostly OCR noise from pictures and logos: left as it is

    /**
     * The page's paragraphs in reading order: lines of one OCR paragraph that follow each other
     * closely and overlap horizontally. The OCR sometimes joins lines far apart (a menu bar, a
     * quote beside a photo and the caption below it); their common box would cover the photo.
     */
    fun paragraphs(words: List<Word>): List<List<Word>> {
        val lines = words.groupBy { Triple(it.block, it.paragraph, it.line) }
            .toSortedMap(compareBy({ it.first }, { it.second }, { it.third })).values
        val out = mutableListOf<MutableList<Word>>()
        var prev: List<Word>? = null
        for (line in lines) {
            val p = prev
            if (p != null && p[0].block == line[0].block && p[0].paragraph == line[0].paragraph && follows(p, line)) out.last() += line
            else out += line.toMutableList()
            prev = line
        }
        return out
    }

    private fun follows(a: List<Word>, b: List<Word>): Boolean {
        val (ax0, ax1) = a.minOf { it.x0 } to a.maxOf { it.x1 }
        val (bx0, bx1) = b.minOf { it.x0 } to b.maxOf { it.x1 }
        val ha = a.maxOf { it.y1 } - a.minOf { it.y0 }
        val hb = b.maxOf { it.y1 } - b.minOf { it.y0 }
        val gap = b.minOf { it.y0 } - a.maxOf { it.y1 }
        val overlap = minOf(ax1, bx1) - maxOf(ax0, bx0)
        return gap < 0.8 * maxOf(ha, hb) && overlap > 0.5 * minOf(ax1 - ax0, bx1 - bx0) && maxOf(ha, hb) < 1.6 * minOf(ha, hb).coerceAtLeast(1)
    }

    /** Worth translating: read with confidence (not noise from a picture) and has letters. */
    fun readable(paragraph: List<Word>): Boolean =
        paragraph.sumOf { it.conf.toDouble() * it.text.length } / paragraph.sumOf { it.text.length }.coerceAtLeast(1) >= MIN_CONFIDENCE &&
            paragraph.sumOf { w -> w.text.count { it.isLetter() } } >= 2

    /** Text of a paragraph as one line, end-of-line hyphens undone. */
    fun text(paragraph: List<Word>): String = OfflineTranslator.paragraphs(pageText(paragraph)).joinToString(" ")

    /**
     * Paint [translations] (one per paragraph; null = keep the paragraph as it is) into the JPEG
     * [file], whose words are given in an image [wordsWidth] pixels wide. The translation is poured
     * into the paragraph's own lines, so it never covers a picture next to the text; paragraphs of the
     * same type size get the same size, the largest at which all of them fit. [rtl]: the target language is written right to left;
     * [noSpaces]: it breaks between any two characters (Chinese, Japanese). Returns the page's words
     * afterwards: the set translation plus the paragraphs kept, numbered by [paragraphs] (one OCR
     * paragraph may have been split, its line numbers would mix).
     */
    fun render(file: File, quality: Int, wordsWidth: Int, paragraphs: List<List<Word>>, translations: List<String?>,
               rtl: Boolean = false, noSpaces: Boolean = false): List<Word> {
        val bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inMutable = true }) ?: return paragraphs.flatten()
        val scale = bmp.width.toFloat() / wordsWidth
        val canvas = Canvas(bmp)
        val bounds = Rect(0, 0, bmp.width, bmp.height)
        val out = mutableListOf<Word>()
        // first pass: each paragraph's lines, words and the largest size at which it fits
        class Setting(val i: Int, val lines: List<Rect>, val tokens: List<String>, val lineHeight: Int, val fits: Float)
        val gap = if (noSpaces) "" else " "
        val measure = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val settings = paragraphs.mapIndexedNotNull { i, par ->
            val translation = translations[i]
            if (translation.isNullOrBlank()) { out += par.map { it.copy(paragraph = i + 1) }; return@mapIndexedNotNull null }
            val lines = par.groupBy { it.line }.values
                .map { l -> Rect(l.minOf { it.x0 }, l.minOf { it.y0 }, l.maxOf { it.x1 }, l.maxOf { it.y1 }).scaled(scale) }
                .filter { it.intersect(bounds) && it.width() > 1 && it.height() > 1 }.sortedBy { it.top }
            if (lines.isEmpty()) { out += par.map { it.copy(paragraph = i + 1) }; return@mapIndexedNotNull null }
            val tokens = if (noSpaces) translation.filter { !it.isWhitespace() }.map { it.toString() } else translation.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val lineHeight = lines.map { it.height() }.sorted()[lines.size / 2]
            var size = lineHeight * 0.95f
            while (size > MIN_TEXT_PX) { // largest size up to the original letter height at which every word finds a line
                measure.textSize = size
                if (fill(tokens, lines, gap, measure).sumOf { it.size } == tokens.size) break
                size = (size * 0.92f).coerceAtLeast(MIN_TEXT_PX)
            }
            Setting(i, lines, tokens, lineHeight, size)
        }
        // paragraphs printed alike (body text, headings of one level) get one size: the smallest that fits them all
        val sizes = mutableMapOf<Int, Float>()
        var group = mutableListOf<Setting>()
        fun close() { val s = group.minOf { it.fits }; group.forEach { sizes[it.i] = s }; group = mutableListOf() }
        for (st in settings.sortedBy { it.lineHeight }) {
            if (group.isNotEmpty() && st.lineHeight > group.first().lineHeight * SAME_PRINT) close()
            group += st
        }
        if (group.isNotEmpty()) close()

        for (st in settings) {
            val i = st.i
            val lines = st.lines
            val (paper, ink) = colours(bmp, lines)
            // the lines plus a margin for accents and descenders the OCR box leaves out
            val pad = (st.lineHeight * 0.2f).toInt()
            val paperPaint = Paint().apply { color = paper }
            lines.forEach { canvas.drawRect(Rect(it).apply { inset(-pad / 2, -pad) }, paperPaint) }

            val tokens = st.tokens
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = sizes.getValue(i) }
            var rows = fill(tokens, lines, gap, paint)
            // what still does not fit at the smallest size goes onto the last line
            val placed = rows.sumOf { it.size }
            if (placed < tokens.size) rows = rows.dropLast(1) + listOf(rows.last() + tokens.drop(placed))
            val fm = paint.fontMetrics
            val like = paragraphs[i].first()
            rows.forEachIndexed { r, row ->
                if (row.isEmpty()) return@forEachIndexed
                val line = lines[r]
                val text = row.joinToString(gap)
                val x = if (rtl) line.right - paint.measureText(text) else line.left.toFloat()
                val baseline = line.exactCenterY() - (fm.ascent + fm.descent) / 2
                canvas.drawText(text, x, baseline, paint)
                var wx = x
                row.forEach { t -> // one word per set word, in the words' coordinates (PDF text layer, search)
                    val w = paint.measureText(t)
                    out += Word(t, 100f, (wx / scale).toInt(), (line.top / scale).toInt(), ((wx + w) / scale).toInt(), (line.bottom / scale).toInt(),
                        like.block, i + 1, r + 1)
                    wx += w + paint.measureText(gap)
                }
            }
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, quality, it) }
        bmp.recycle()
        return out
    }

    private fun Rect.scaled(s: Float) = Rect((left * s).toInt(), (top * s).toInt(), (right * s).toInt(), (bottom * s).toInt())

    /** Words of each line, filled in order as long as they fit its width; may leave words over. */
    private fun fill(tokens: List<String>, lines: List<Rect>, gap: String, paint: TextPaint): List<List<String>> {
        var next = 0
        return lines.map { line ->
            val row = mutableListOf<String>()
            while (next < tokens.size && paint.measureText((row + tokens[next]).joinToString(gap)) <= line.width()) row += tokens[next++]
            row
        }
    }

    /**
     * Paper and print colour inside [rects]: the paper is what most pixels show (the middle of the
     * brightness order), the print the few pixels furthest from it. Too little contrast: black or white.
     */
    private fun colours(bmp: Bitmap, rects: List<Rect>): Pair<Int, Int> {
        val px = rects.flatMap { r -> IntArray(r.width() * r.height()).also { bmp.getPixels(it, 0, r.width(), r.left, r.top, r.width(), r.height()) }.asList() }
        fun lum(c: Int) = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000
        val sorted = px.sortedBy { lum(it) }
        fun mean(from: Int, to: Int): Int {
            val part = sorted.subList(from.coerceIn(0, sorted.size - 1), to.coerceIn(from + 1, sorted.size))
            return Color.rgb(part.sumOf { Color.red(it) } / part.size, part.sumOf { Color.green(it) } / part.size, part.sumOf { Color.blue(it) } / part.size)
        }
        val n = sorted.size
        val paper = mean(n * 35 / 100, n * 65 / 100)
        val light = lum(paper) >= 128
        val ink = if (light) mean(0, n * 3 / 100 + 1) else mean(n - n * 3 / 100 - 1, n)
        val contrastOk = kotlin.math.abs(lum(ink) - lum(paper)) >= 80
        return paper to if (contrastOk) ink else if (light) Color.BLACK else Color.WHITE
    }
}
