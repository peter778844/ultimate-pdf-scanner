package de.ultimatepdfscanner.core

import kotlin.math.max
import kotlin.math.min

/**
 * Table recognition from OCR word boxes (no ruling lines needed): words are grouped into rows
 * by vertical overlap, split into cells at wide gaps, and the cells are assigned to columns
 * found from the horizontal ranges no multi-cell row covers. Tesseract's own lines are not
 * used because its layout analysis often reads table columns as separate blocks.
 */
object Tables {
    const val CELL_GAP = 1.1 // a gap wider than this x the median word height separates cells
    const val ROW_OVERLAP = 0.5 // share of the smaller height two words must overlap to share a row

    private class Cell(val x0: Int, val x1: Int, val text: String)

    /** Rows of cells, every row as long as the widest; empty pages give an empty list. */
    fun extract(words: List<Word>): List<List<String>> {
        val ws = words.filter { it.text.isNotBlank() }
        if (ws.isEmpty()) return emptyList()
        val heights = ws.map { it.y1 - it.y0 }.sorted()
        val h = max(1, heights[heights.size / 2])

        // rows: by vertical centre, joined while they overlap enough
        val rows = ArrayList<MutableList<Word>>()
        for (w in ws.sortedBy { (it.y0 + it.y1) / 2 }) {
            val row = rows.lastOrNull()
            if (row != null) {
                val top = row.minOf { it.y0 }
                val bottom = row.maxOf { it.y1 }
                val overlap = min(bottom, w.y1) - max(top, w.y0)
                if (overlap >= ROW_OVERLAP * min(bottom - top, w.y1 - w.y0)) { row += w; continue }
            }
            rows += mutableListOf(w)
        }

        // cells: split each row at wide gaps
        val cellRows = rows.map { row ->
            val sorted = row.sortedBy { it.x0 }
            val cells = ArrayList<Cell>()
            var start = sorted[0]
            var end = sorted[0].x1
            val text = StringBuilder(sorted[0].text)
            for (w in sorted.drop(1)) {
                if (w.x0 - end > CELL_GAP * h) {
                    cells += Cell(start.x0, end, text.toString())
                    start = w; text.clear()
                } else text.append(' ')
                text.append(w.text)
                end = max(end, w.x1)
            }
            cells += Cell(start.x0, end, text.toString())
            cells
        }

        // columns: x ranges covered by cells of rows that have at least two cells
        val multi = cellRows.filter { it.size >= 2 }.flatten()
        if (multi.isEmpty()) return cellRows.map { r -> listOf(r.joinToString(" ") { it.text }) }
        val spans = multi.map { it.x0 to it.x1 }.sortedBy { it.first }
        val columns = ArrayList<IntArray>()
        for ((a, b) in spans) {
            val last = columns.lastOrNull()
            if (last != null && a <= last[1]) last[1] = max(last[1], b) else columns += intArrayOf(a, b)
        }

        return cellRows.map { cells ->
            val out = Array(columns.size) { "" }
            for (c in cells) {
                // the column with the largest overlap; cells between columns go to the nearest
                val col = columns.indices.maxBy { i -> min(c.x1, columns[i][1]) - max(c.x0, columns[i][0]) }
                out[col] = if (out[col].isEmpty()) c.text else out[col] + " " + c.text
            }
            out.toList()
        }
    }

    /** RFC 4180 CSV with semicolons (what German Excel expects) and a BOM for the umlauts. */
    fun toCsv(rows: List<List<String>>, separator: Char = ';'): String = "﻿" + rows.joinToString("\r\n") { row ->
        row.joinToString(separator.toString()) { v ->
            if (v.any { it == separator || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        }
    }

    /** German or English number ("1.234,56", "-12,5", "1,234.56", "12 %") or null. */
    fun number(s: String): Double? {
        val t = s.trim().removeSuffix("€").removeSuffix("%").removePrefix("€").trim().replace(" ", "")
        if (t.isEmpty() || !t.matches(Regex("""[-+]?[\d.,]+"""))) return null
        val de = Regex("""[-+]?\d{1,3}(\.\d{3})*(,\d+)?|[-+]?\d+(,\d+)?""")
        val en = Regex("""[-+]?\d{1,3}(,\d{3})*(\.\d+)?|[-+]?\d+(\.\d+)?""")
        return when {
            de.matches(t) && (t.contains(',') || !t.contains('.') || t.substringAfterLast('.').length == 3) ->
                t.replace(".", "").replace(',', '.').toDoubleOrNull()
            en.matches(t) -> t.replace(",", "").toDoubleOrNull()
            else -> null
        }
    }
}
