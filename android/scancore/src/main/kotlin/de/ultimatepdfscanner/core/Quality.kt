package de.ultimatepdfscanner.core

import org.opencv.core.Mat

/** Problems of a processed page that make a retake worthwhile. */
enum class Issue(val label: String, val hint: String) {
    NO_PAGE("Keine Seite erkannt", "Der Seitenrand wurde nicht gefunden – das ganze Foto wurde übernommen."),
    BLURRY("Unscharf", "Das Foto ist verwackelt oder unscharf."),
    DARK("Zu dunkel", "Die Seite ist schlecht ausgeleuchtet."),
    POOR_TEXT("Text schlecht lesbar", "Die Texterkennung war unsicher."),
    NO_TEXT("Kein Text erkannt", "Auf der Seite wurde kein Text gefunden – eine leere Seite oder gar keine Seite im Bild?"),
}

/** Rates a processed page; the thresholds are deliberately loose so only clearly poor scans are flagged. */
object Quality {
    const val STRONG_BLUR = 1.9 // estimated sigma (sharp phone photos ~0.7-1.2; the estimate saturates: true 3 px -> 1.97, 6 px -> 2.34)
    const val MIN_PAPER = 90.0 // 90th percentile of the grey page before enhancement
    const val MIN_WORDS = 15 // judge OCR confidence only on pages with real text
    const val MIN_CONFIDENCE = 55.0
    const val SURE_CONFIDENCE = 80.0
    const val MIN_SURE_SHARE = 0.5 // a page with at least half of its words read surely is legible

    const val MIN_ANY_WORDS = 3 // fewer recognised words: probably no page at all (or a blank one)

    /** [words] null = no OCR ran (then text is not judged). */
    fun assess(pageFound: Boolean, blurSigma: Double, rectified: Mat, words: List<Word>?): List<Issue> {
        val issues = ArrayList<Issue>()
        if (!pageFound) issues += Issue.NO_PAGE
        // the blur estimate needs letters: on a blank page it measures paper grain (false alarms)
        if (blurSigma > STRONG_BLUR && hasLetters(rectified)) issues += Issue.BLURRY
        if (paperBrightness(rectified) < MIN_PAPER) issues += Issue.DARK
        val text = words?.filter(::isRealWord)
        // low mean AND few sure words: tables of contents read their dot leaders as junk words (low
        // mean) while the entries themselves are read well
        if (text != null && text.size >= MIN_WORDS && meanConfidence(text) < MIN_CONFIDENCE &&
            text.count { it.conf >= SURE_CONFIDENCE } < MIN_SURE_SHARE * text.size) issues += Issue.POOR_TEXT
        if (words != null && words.count { w -> w.text.count(Char::isLetterOrDigit) >= 2 } < MIN_ANY_WORDS && issues.isEmpty()) issues += Issue.NO_TEXT
        return issues
    }

    /**
     * A word with at least three letters. Fragments of formulas, diagram labels and page edges
     * (read since pages are scaled to the OCR's letter size) say nothing about how well the text
     * itself was read, but pulled the mean down: 6 of 129 good pages were flagged "poorly legible".
     */
    fun isRealWord(w: Word): Boolean = w.text.count(Char::isLetter) >= 3

    /** Mean confidence of the real words ([isRealWord]); for judging and comparing readings. */
    fun textConfidence(words: List<Word>): Double = meanConfidence(words.filter(::isRealWord))

    /** Mean OCR confidence weighted by word length (short fragments are often noise). */
    fun meanConfidence(words: List<Word>): Double {
        val chars = words.sumOf { it.text.length }
        return if (chars == 0) 0.0 else words.sumOf { it.conf.toDouble() * it.text.length } / chars
    }

    private fun hasLetters(image: Mat): Boolean = matScope {
        val (small, _) = downscale(image, 2000)
        TextSize.charHeight(track(Enhance.toGray(track(Enhance.flatten(small))))) != null
    }

    private fun paperBrightness(image: Mat): Double = matScope {
        val gray = track(Enhance.toGray(image))
        histPercentile(histogram(gray), 90.0)
    }
}
