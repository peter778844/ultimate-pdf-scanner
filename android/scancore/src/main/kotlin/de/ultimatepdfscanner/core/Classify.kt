package de.ultimatepdfscanner.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Document types for automatic naming. */
enum class DocType(val label: String, val plural: String) {
    RECEIPT("Quittung", "Quittungen"),
    INVOICE("Rechnung", "Rechnungen"),
    BUSINESS_CARD("Visitenkarte", "Visitenkarten"),
    CONTRACT("Vertrag", "Verträge"),
    LETTER("Brief", "Briefe"),
    ID_CARD("Ausweis", "Ausweise"), // set by the ID card camera mode, never guessed from text
    BOOK("Buch", "Bücher"), // set by the book camera mode
    PHOTO("Passfoto", "Passfotos"), // set by the passport photo camera mode
    PICTURE("Bild", "Bilder"), // pictures and magazine pages: set by the picture camera mode, no OCR
    DOCUMENT("Dokument", "Dokumente"),
}

/**
 * Rule-based, fully offline classifier on the OCR text and the page shape. Keyword weights
 * are tuned for German documents (with common English terms); a type needs a clear lead,
 * otherwise the neutral DOCUMENT is used.
 */
object Classify {
    private val KEYWORDS: Map<DocType, Map<String, Double>> = mapOf(
        DocType.RECEIPT to mapOf(
            "kassenbon" to 3.0, "quittung" to 3.0, "kassenbeleg" to 3.0, "bon-nr" to 2.0, "beleg-nr" to 2.0, "rückgeld" to 2.5,
            "gegeben" to 1.5, "bar" to 1.0, "ec-karte" to 2.0, "girocard" to 2.0, "kartenzahlung" to 2.0, "summe" to 1.0,
            "zu zahlen" to 1.5, "terminal" to 1.5, "tse" to 1.5, "kasse" to 1.5, "vielen dank für ihren einkauf" to 3.0, "receipt" to 3.0,
        ),
        DocType.INVOICE to mapOf(
            "rechnung" to 2.5, "rechnungsnummer" to 3.0, "rechnungsnr" to 3.0, "rechnungsdatum" to 3.0, "kundennummer" to 1.5,
            "zahlbar" to 1.5, "zahlungsziel" to 2.0, "fällig" to 1.0, "iban" to 1.0, "bic" to 1.0, "ust-idnr" to 2.0, "ust-id" to 2.0,
            "steuernummer" to 1.0, "netto" to 1.0, "brutto" to 1.0, "nettobetrag" to 1.5, "invoice" to 3.0, "leistungszeitraum" to 2.0,
        ),
        DocType.CONTRACT to mapOf(
            "vertrag" to 2.5, "vereinbarung" to 2.0, "vertragspartner" to 3.0, "laufzeit" to 1.5, "kündigung" to 1.5,
            "kündigungsfrist" to 2.0, "§" to 0.8, "unterschrift" to 1.0, "zwischen" to 0.5, "mietvertrag" to 3.0, "arbeitsvertrag" to 3.0,
        ),
        DocType.LETTER to mapOf(
            "sehr geehrte" to 2.5, "sehr geehrter" to 2.5, "mit freundlichen grüßen" to 3.0, "betreff" to 1.5, "ihr zeichen" to 2.0,
            "liebe" to 0.8, "hallo" to 0.5,
        ),
    )

    private val PHONE = Regex("""(\+\d{2}|\b0)\s?\d{2,5}[\s/-]?\d{3,}""")
    private val EMAIL = Regex("""[\w.+-]+@[\w-]+\.[\w.]+""")
    private val WEB = Regex("""\b(www\.|https?://)\S+""", RegexOption.IGNORE_CASE)

    /**
     * [aspect] = long side / short side of the first page (receipts are long strips, business
     * cards ~1.6 with little text).
     */
    fun classify(text: String, pageCount: Int = 1, aspect: Double = 1.414): DocType {
        val t = text.lowercase(Locale.GERMANY)
        val words = t.split(Regex("\\s+")).count { it.isNotBlank() }
        val scores = KEYWORDS.mapValues { (_, kw) -> kw.entries.sumOf { (k, w) -> if (containsWord(t, k)) w else 0.0 } }.toMutableMap()
        if (aspect > 2.0) scores[DocType.RECEIPT] = (scores[DocType.RECEIPT] ?: 0.0) + 2.5 // narrow till receipt
        val cardLike = pageCount == 1 && aspect in 1.45..1.95 && words in 3..80
        val contacts = listOf(PHONE, EMAIL, WEB).count { it.containsMatchIn(text) }
        val cardScore = if (cardLike) 2.0 + 1.5 * contacts else 0.0

        val all = scores + (DocType.BUSINESS_CARD to cardScore)
        val best = all.maxBy { it.value }
        val second = all.filter { it.key != best.key }.maxOf { it.value }
        return if (best.value >= 3.0 && best.value >= second + 1.0) best.key else DocType.DOCUMENT
    }

    private fun containsWord(text: String, key: String): Boolean {
        if (!key.first().isLetterOrDigit()) return text.contains(key)
        return Regex("(?<![\\p{L}\\d])" + Regex.escape(key) + "(?![\\p{L}\\d])").containsMatchIn(text)
    }

    /** File name like "Quittung_2026-10-23_12-30" (type and scan time). */
    fun suggestName(type: DocType, time: Long): String =
        type.label + "_" + SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date(time))
}
