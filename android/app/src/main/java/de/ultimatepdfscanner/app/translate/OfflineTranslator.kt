package de.ultimatepdfscanner.app.translate

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await

/**
 * On-device translation (Google ML Kit): the text never leaves the phone; only the language
 * model (~30 MB per language) is downloaded once on first use.
 */
object OfflineTranslator {
    /** OCR language (Tesseract code) -> language tag of the translation; Serbian and Latin have no ML Kit model. */
    private val OCR_TO_TAG = mapOf("deu" to "de", "eng" to "en", "fra" to "fr", "ita" to "it", "spa" to "es", "por" to "pt", "nld" to "nl",
        "pol" to "pl", "ces" to "cs", "dan" to "da", "swe" to "sv", "nor" to "no", "fin" to "fi", "hun" to "hu", "ron" to "ro", "hrv" to "hr",
        "tur" to "tr", "ell" to "el", "rus" to "ru", "ukr" to "uk", "bel" to "be", "bul" to "bg", "mkd" to "mk", "ara" to "ar", "heb" to "he",
        "fas" to "fa", "chi_sim" to "zh", "hin" to "hi", "ben" to "bn", "urd" to "ur", "ind" to "id", "jpn" to "ja", "kor" to "ko", "deu_latf" to "de")

    /** Target languages: every OCR language ML Kit can translate, in the order of the OCR language list. */
    val TARGETS: List<Pair<String, String>> = de.ultimatepdfscanner.app.ocr.OcrLanguages.ALL.mapNotNull { l ->
        OCR_TO_TAG[l.code]?.takeIf { TranslateLanguage.fromLanguageTag(it) != null }?.let { it to l.label }
    }.distinctBy { it.first }

    /** The translation language of the selected OCR languages (the first one ML Kit knows), as a fallback for detection. */
    fun fromOcrLanguage(language: String): String? =
        language.split('+').firstNotNullOfOrNull { OCR_TO_TAG[it]?.takeIf { tag -> TranslateLanguage.fromLanguageTag(tag) != null } }

    fun label(code: String) = TARGETS.firstOrNull { it.first == code }?.second ?: code

    /**
     * Language of every paragraph on its own, so a document with a few lines in the target language
     * is still translated as a whole. Paragraphs too short to tell (headings, captions) and the ones
     * that cannot be told take the most frequent language of the others, else [fallback].
     */
    suspend fun detectEach(paragraphs: List<String>, fallback: String?): List<String?> {
        val client = LanguageIdentification.getClient()
        try {
            val found = paragraphs.map { p ->
                if (p.count { it.isLetter() } < MIN_DETECT_LETTERS) null
                else client.identifyLanguage(p.take(3000)).await().takeIf { it != "und" }
            }
            val main = found.filterNotNull().groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: fallback
            return found.map { it ?: main }
        } finally { client.close() }
    }

    private const val MIN_DETECT_LETTERS = 20

    fun supported(language: String) = TranslateLanguage.fromLanguageTag(language) != null

    /** [glossary]: special terms kept from the translator and put in as given; a paragraph whose placeholders get lost is translated without. */
    suspend fun translate(paragraphs: List<String>, source: String, target: String, glossary: de.ultimatepdfscanner.core.Glossary? = null,
                          progress: (Int, Int) -> Unit): List<String> {
        val src = TranslateLanguage.fromLanguageTag(source) ?: error(de.ultimatepdfscanner.app.tr("Ausgangssprache „%s“ wird nicht unterstützt", source))
        val tgt = TranslateLanguage.fromLanguageTag(target) ?: error(de.ultimatepdfscanner.app.tr("Zielsprache „%s“ wird nicht unterstützt", target))
        val client = Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        try {
            client.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            return paragraphs.mapIndexed { i, p ->
                progress(i + 1, paragraphs.size)
                if (p.isBlank()) return@mapIndexed p
                val plain = client.translate(p).await()
                // placeholders cost fluency: only for the terms the translator did not get right by itself
                val (protected, terms) = glossary?.protect(p) { !plain.contains(it, ignoreCase = true) } ?: (p to emptyList())
                if (terms.isEmpty()) plain else glossary?.restore(client.translate(protected).await(), terms) ?: plain
            }
        } finally {
            client.close()
        }
    }

    /** OCR lines -> paragraphs: join lines, undo end-of-line hyphenation ("Wirt-\nschaft" -> "Wirtschaft"). */
    fun paragraphs(pageText: String): List<String> = pageText.split(Regex("\n\\s*\n")).map { para ->
        para.lines().map { it.trim() }.filter { it.isNotEmpty() }.fold("") { acc, line ->
            when {
                acc.isEmpty() -> line
                acc.endsWith("-") && line.firstOrNull()?.isLowerCase() == true -> acc.dropLast(1) + line
                else -> "$acc $line"
            }
        }
    }.filter { it.isNotBlank() }
}
