package de.ultimatepdfscanner.core

import kotlin.math.max
import kotlin.math.min

/**
 * Applies a user's corrections of a page text to its OCR words, so that search and the PDF
 * text layer show the corrected text at (about) the right place. Unchanged lines keep their
 * word boxes; changed lines are matched to the most similar original line (in order) and their
 * words are spread over that line's box by character count; new lines go below their predecessor.
 */
object TextEdit {
    private class Line(val words: List<Word>) {
        val text = words.joinToString(" ") { it.text }
        val x0 = words.minOf { it.x0 }; val y0 = words.minOf { it.y0 }
        val x1 = words.maxOf { it.x1 }; val y1 = words.maxOf { it.y1 }
    }

    /** Original lines in the reading order of [pageText]. */
    private fun lines(words: List<Word>): List<Line> =
        words.groupBy { Triple(it.block, it.paragraph, it.line) }.toSortedMap(compareBy({ it.first }, { it.second }, { it.third })).values.map { Line(it) }

    /** 0..1 similarity (1 - normalised Levenshtein distance). */
    internal fun similarity(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return 1.0 - prev[b.length].toDouble() / max(a.length, b.length)
    }

    const val MIN_SIMILARITY = 0.3

    fun apply(words: List<Word>, edited: String): List<Word> {
        val old = lines(words)
        // edited lines with their paragraph index (blank lines separate paragraphs)
        val newLines = ArrayList<Pair<String, Int>>()
        var par = 0
        var blank = false
        for (raw in edited.lines()) {
            val l = raw.trim()
            if (l.isEmpty()) { blank = true; continue }
            if (blank && newLines.isNotEmpty()) par++
            blank = false
            newLines += l to par
        }
        if (newLines.isEmpty()) return emptyList()

        // order-preserving alignment maximising total similarity (Needleman-Wunsch, free gaps)
        val n = newLines.size
        val m = old.size
        val score = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            val s = similarity(newLines[i - 1].first, old[j - 1].text).let { if (it >= MIN_SIMILARITY) it else Double.NEGATIVE_INFINITY }
            score[i][j] = maxOf(score[i - 1][j], score[i][j - 1], score[i - 1][j - 1] + s)
        }
        val match = arrayOfNulls<Line>(n)
        var i = n; var j = m
        while (i > 0 && j > 0) {
            val s = similarity(newLines[i - 1].first, old[j - 1].text)
            when {
                s >= MIN_SIMILARITY && score[i][j] == score[i - 1][j - 1] + s -> { match[i - 1] = old[j - 1]; i--; j-- }
                score[i][j] == score[i - 1][j] -> i--
                else -> j--
            }
        }

        // boxes for unmatched lines: below the previous line (or above the next one)
        val out = ArrayList<Word>()
        var prevBox: IntArray? = null
        for (k in 0 until n) {
            val (text, p) = newLines[k]
            val line = match[k]
            val box = when {
                line != null -> intArrayOf(line.x0, line.y0, line.x1, line.y1)
                prevBox != null -> (prevBox[3] - prevBox[1]).let { h -> intArrayOf(prevBox[0], prevBox[3] + h / 3, prevBox[2], prevBox[3] + h / 3 + h) }
                else -> (match.drop(k).firstOrNull { it != null })?.let { nx -> val h = nx.y1 - nx.y0; intArrayOf(nx.x0, nx.y0 - h - h / 3, nx.x1, nx.y0 - h / 3) }
                    ?: intArrayOf(0, 0, 100, 30)
            }
            prevBox = box
            if (line != null && line.text == text) { // unchanged: keep the exact boxes
                line.words.forEach { out += it.copy(block = 0, paragraph = p, line = k) }
                continue
            }
            val tokens = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val total = text.length.coerceAtLeast(1)
            var pos = 0
            for (t in tokens) {
                val start = text.indexOf(t, pos)
                pos = start + t.length
                val wx0 = box[0] + (box[2] - box[0]) * start / total
                val wx1 = box[0] + (box[2] - box[0]) * pos / total
                out += Word(t, 100f, wx0, box[1], max(wx0 + 1, min(box[2], wx1)), box[3], 0, p, k)
            }
        }
        return out
    }
}
