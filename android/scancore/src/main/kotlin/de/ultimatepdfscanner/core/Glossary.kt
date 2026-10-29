package de.ultimatepdfscanner.core

import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Special terms of one language pair for the translator (from IATE, see tools/build_glossaries.py):
 * [protect] swaps every known source term for a placeholder the translator leaves alone,
 * [restore] puts the target term in its place.
 */
class Glossary private constructor(private val terms: Map<String, String>, private val maxWords: Int) {
    val size get() = terms.size

    /**
     * [text] with the terms replaced by ⟦1⟧, ⟦2⟧ … and the target term for each placeholder. Terms
     * whose target fails [keep] stay as they are (with the words inside them).
     */
    fun protect(text: String, keep: (target: String) -> Boolean = { true }): Pair<String, List<String>> {
        val words = WORD.findAll(text).toList()
        val out = StringBuilder()
        val targets = mutableListOf<String>()
        var copied = 0
        var i = 0
        while (i < words.size) {
            // longest term starting at this word
            val hit = (minOf(maxWords, words.size - i) downTo 1).firstNotNullOfOrNull { n ->
                val span = text.substring(words[i].range.first, words[i + n - 1].range.last + 1)
                (terms[key(span)] ?: terms[normalize(span)] ?: terms[normalize(span).lowercase()])?.let { n to it } // also "BLUTDRUCK" in a heading
            }
            if (hit == null) { i++; continue }
            if (!keep(hit.second)) { i += hit.first; continue }
            out.append(text, copied, words[i].range.first)
            targets += hit.second
            out.append("⟦").append(targets.size).append('⟧')
            copied = words[i + hit.first - 1].range.last + 1
            i += hit.first
        }
        return out.append(text, copied, text.length).toString() to targets
    }

    /** The translation with the target terms put back; null if the translator lost a placeholder. */
    fun restore(translated: String, targets: List<String>): String? {
        val seen = mutableSetOf<Int>()
        val result = PLACEHOLDER.replace(translated) { m ->
            val n = m.groupValues[1].toInt()
            seen += n
            val t = targets.getOrNull(n - 1) ?: return@replace m.value
            // "the defendant" at the start of a sentence
            if (SENTENCE_START.containsMatchIn(translated.substring(0, m.range.first))) t.replaceFirstChar { it.uppercase() } else t
        }
        return result.takeIf { seen.size == targets.size && (1..targets.size).all { it in seen } }
    }

    companion object {
        private val WORD = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?")
        private val PLACEHOLDER = Regex("⟦\\s*(\\d+)\\s*⟧")
        private val SENTENCE_START = Regex("(^|[.!?:]\\s+)$")

        /** EuroVoc fields of the IATE entries: code -> German name. */
        val FIELDS = linkedMapOf("04" to "Politik", "08" to "Internationale Beziehungen", "10" to "Europäische Union", "12" to "Recht",
            "16" to "Wirtschaft", "20" to "Handel", "24" to "Finanzwesen", "28" to "Soziales und Gesundheit", "32" to "Bildung und Kommunikation",
            "36" to "Wissenschaft", "40" to "Unternehmen und Wettbewerb", "44" to "Beschäftigung und Arbeitsbedingungen", "48" to "Verkehr",
            "52" to "Umwelt", "56" to "Land-, Forst- und Fischereiwirtschaft", "60" to "Agrarerzeugnisse und Lebensmittel",
            "64" to "Produktion, Technologie und Forschung", "66" to "Energie", "68" to "Industrie", "72" to "Geografie",
            "76" to "Internationale Organisationen")

        /** Words collapsed to single spaces, lower case unless the term is an abbreviation like "EU" or "CO2". */
        private fun normalize(s: String) = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        private fun key(s: String) = normalize(s).let { n -> if (n.count { it.isUpperCase() } >= 2) n else n.lowercase() }

        /**
         * Glossary from the files of the source and the target language, limited to the entries of
         * [fields] (empty: all fields). A source term with different translations in the chosen fields
         * is ambiguous and left to the translator, as are terms under three letters. [vocabulary]: the
         * lower-case words of the text to translate; only terms made of them are kept (measured: German ->
         * English in all fields held 409,000 terms in 76 MB of heap, too much for small phones).
         */
        fun load(source: InputStream, target: InputStream, fields: Set<String>, vocabulary: Set<String>? = null): Glossary {
            fun lines(input: InputStream, each: (id: String, term: String) -> Unit) =
                GZIPInputStream(input).bufferedReader().useLines { seq ->
                    seq.forEach { line ->
                        val parts = line.split('\t')
                        if (parts.size == 3 && (fields.isEmpty() || parts[1].split(',').any { it in fields })) each(parts[0], parts[2])
                    }
                }
            // the source side first, so that only the entries needed are kept of the target side
            val wanted = HashMap<String, MutableList<String>>()
            lines(source) { id, term ->
                if (term.count { it.isLetter() } < 3) return@lines
                if (vocabulary != null && WORD.findAll(term).any { it.value.lowercase() !in vocabulary }) return@lines
                wanted.getOrPut(id) { ArrayList(1) } += term
            }
            val terms = HashMap<String, String?>()
            lines(target) { id, t ->
                // the first term of an entry is its main one
                for (term in wanted.remove(id) ?: return@lines) {
                    val k = key(term)
                    terms[k] = if (k in terms && terms[k] != t) null else t
                }
            }
            @Suppress("UNCHECKED_CAST")
            val clean = terms.filterValues { it != null } as Map<String, String>
            return Glossary(clean, clean.keys.maxOfOrNull { k -> WORD.findAll(k).count() }?.coerceAtMost(8) ?: 1)
        }
    }
}
