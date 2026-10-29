package de.ultimatepdfscanner.core

import org.opencv.core.Mat
import org.opencv.core.Point

/** One recognised word; [box] is x0, y0, x1, y1 in page-image pixels. */
data class Word(
    val text: String,
    val conf: Float,
    val x0: Int, val y0: Int, val x1: Int, val y1: Int,
    val block: Int, val paragraph: Int, val line: Int,
)

/** Page corners in image coordinates, ordered top-left, top-right, bottom-right, bottom-left. */
typealias Quad = Array<Point>

interface OcrEngine {
    fun recognize(gray: Mat): List<Word>

    /** [singleBlock]: read the page as one block of lines (no column analysis), e.g. a table of contents. */
    fun recognize(gray: Mat, singleBlock: Boolean): List<Word> = recognize(gray)

    /** Clockwise rotation (0/90/180/270) that makes the text upright, or null if unsure. */
    fun orientation(gray: Mat, hint: Int? = null): Int?

    /** Letter height (px) this engine reads best at; null = the pipeline's setting, 0 = leave the page as it is. */
    val preferredTextHeight: Double? get() = null

    /** True if [orientation] is decided by a model of its own: then it is asked directly and not double-checked by reading confidence. */
    val orientationReliable: Boolean get() = false
}

/** Text of a page in reading order: words joined per line, blank line between paragraphs. */
fun pageText(words: List<Word>): String {
    val lines = words.groupBy { Triple(it.block, it.paragraph, it.line) }
    val keys = lines.keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
    val texts = keys.map { k -> lines.getValue(k).map { it.text }.toMutableList() }
    // words hyphenated at the line end are joined: "Konden-" + "satoren" -> "Kondensatoren"
    for (i in 0 until texts.size - 1) {
        val last = texts[i].lastOrNull() ?: continue
        val next = texts[i + 1].firstOrNull() ?: continue
        if (isLineBreakHyphen(last, next)) {
            texts[i][texts[i].lastIndex] = last.dropLast(1) + next
            texts[i + 1].removeAt(0)
        }
    }
    val out = StringBuilder()
    var prevPar: Pair<Int, Int>? = null
    keys.forEachIndexed { i, key ->
        val par = key.first to key.second
        if (texts[i].isEmpty()) return@forEachIndexed // its only word went up to the previous line
        if (prevPar != null) out.append(if (par != prevPar) "\n\n" else "\n")
        out.append(texts[i].joinToString(" "))
        prevPar = par
    }
    return out.toString()
}

/** Conjunctions after a suspended hyphen ("Gleich- und Wechselstrom"): that hyphen stays. */
private val SUSPENDED_HYPHEN_NEXT = setOf("und", "oder", "bzw", "bzw.", "sowie", "bis", "als", "wie", "and", "or")

/**
 * A word part at the end of a line ([last] ends with a hyphen after a letter) continued in lower
 * case on the next line is one hyphenated word; a capital (a new noun) or a conjunction is not.
 */
internal fun isLineBreakHyphen(last: String, next: String): Boolean {
    if (last.length < 3 || !(last.endsWith('-') || last.endsWith('¬'))) return false
    if (!last[last.length - 2].isLetter()) return false
    val first = next.firstOrNull() ?: return false
    if (!first.isLetter() || !first.isLowerCase()) return false
    return next.lowercase().trimEnd(',', '.', ';', ':') !in SUSPENDED_HYPHEN_NEXT
}
