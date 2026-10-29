package de.ultimatepdfscanner.core

import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.math.BigInteger
import kotlin.math.max
import kotlin.math.min

/** A region to black out, in page-image pixels. */
data class Redaction(val kind: String, val x0: Int, val y0: Int, val x1: Int, val y1: Int)

/**
 * Finds sensitive data in the OCR words, fully offline and with checksums where formats have
 * them, so ordinary numbers are not blacked out:
 *  - payment card numbers (13-19 digits, Luhn),
 *  - IBANs (ISO 13616 mod-97),
 *  - German tax ID / Steuer-ID (11 digits, ISO 7064 MOD 11,10),
 *  - German tax numbers / Steuernummer (e.g. 12/345/67890, 123/456/78901, 13 digits after "Steuernummer"),
 *  - passwords and PINs following a label ("Passwort:", "Kennwort", "PIN", "Password").
 * Numbers may be split over several words (groups of 4); runs within one line are joined.
 */
object Redact {
    fun find(words: List<Word>): List<Redaction> {
        val out = ArrayList<Redaction>()
        val lines = words.groupBy { Triple(it.block, it.paragraph, it.line) }.values
        for (line in lines) {
            val ws = line.sortedBy { it.x0 }
            // numbers: try every run of consecutive words that only contain digits/spaces/separators
            var i = 0
            while (i < ws.size) {
                var matched = 0
                for (j in min(ws.size, i + 8) downTo i + 1) {
                    val run = ws.subList(i, j)
                    val kind = numberKind(run.joinToString(" ") { it.text }, labelBefore(ws, i))
                    if (kind != null) { out += box(kind, run); matched = j - i; break }
                }
                i += max(1, matched)
            }
            // passwords: the token(s) after a password label
            for ((k, w) in ws.withIndex()) {
                if (PASSWORD_LABEL.matches(w.text.lowercase().trimEnd(':', '.'))) {
                    val secret = ws.drop(k + 1).take(1).filter { it.text.isNotBlank() }
                    if (secret.isNotEmpty()) out += box("Passwort", secret)
                }
            }
        }
        return out
    }

    private val PASSWORD_LABEL = Regex("passwort|kennwort|password|passcode|pin|zugangscode|pw")

    private fun labelBefore(ws: List<Word>, i: Int) = ws.take(i).joinToString(" ") { it.text.lowercase() }

    private fun box(kind: String, run: List<Word>) =
        Redaction(kind, run.minOf { it.x0 }, run.minOf { it.y0 }, run.maxOf { it.x1 }, run.maxOf { it.y1 })

    internal fun numberKind(raw: String, label: String): String? {
        val compact = raw.replace(Regex("[\\s.-]"), "")
        // IBAN: 2 letters, 2 check digits, 11-30 alphanumerics
        if (Regex("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}").matches(compact.uppercase()) && ibanValid(compact.uppercase())) return "IBAN"
        if (Regex("\\d{13,19}").matches(compact) && luhn(compact) && !compact.all { it == compact[0] }) return "Kartennummer"
        if (Regex("\\d{11}").matches(compact) && steuerIdValid(compact)) return "Steuer-ID"
        val trimmed = raw.trim()
        if (Regex("\\d{2,3}/\\d{3,4}/\\d{4,5}").matches(trimmed)) return "Steuernummer"
        if (Regex("\\d{13}").matches(compact) && label.contains("steuernummer")) return "Steuernummer"
        return null
    }

    internal fun luhn(digits: String): Boolean {
        var sum = 0
        for ((i, c) in digits.reversed().withIndex()) {
            var d = c - '0'
            if (i % 2 == 1) { d *= 2; if (d > 9) d -= 9 }
            sum += d
        }
        return sum % 10 == 0
    }

    internal fun ibanValid(iban: String): Boolean {
        val rearranged = iban.substring(4) + iban.substring(0, 4)
        val numeric = rearranged.map { if (it.isDigit()) it.toString() else (it - 'A' + 10).toString() }.joinToString("")
        return BigInteger(numeric).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    /** Steuer-ID: first digit not 0, one digit appears 2-3 times in the first 10, ISO 7064 MOD 11,10 check digit. */
    internal fun steuerIdValid(id: String): Boolean {
        if (id[0] == '0') return false
        val counts = id.take(10).groupingBy { it }.eachCount().values
        if (counts.none { it == 2 || it == 3 } || counts.any { it > 3 }) return false
        var product = 10
        for (c in id.take(10)) {
            var sum = (c - '0' + product) % 10
            if (sum == 0) sum = 10
            product = (sum * 2) % 11
        }
        val check = (11 - product).let { if (it == 10) 0 else it }
        return check == id[10] - '0'
    }

    /** Black out (or pixelate) the regions, with a small margin, in place. */
    fun apply(image: Mat, regions: List<Redaction>, pixelate: Boolean = false, marginPx: Int = 4) {
        for (r in regions) {
            val x0 = (r.x0 - marginPx).coerceIn(0, image.cols() - 1)
            val y0 = (r.y0 - marginPx).coerceIn(0, image.rows() - 1)
            val x1 = (r.x1 + marginPx).coerceIn(x0 + 1, image.cols())
            val y1 = (r.y1 + marginPx).coerceIn(y0 + 1, image.rows())
            if (!pixelate) {
                Imgproc.rectangle(image, Point(x0.toDouble(), y0.toDouble()), Point(x1.toDouble(), y1.toDouble()), Scalar.all(0.0), Imgproc.FILLED)
                continue
            }
            // coarse mosaic: blocks of ~1/6 of the shorter side, so text and faces become unreadable
            val roi = image.submat(y0, y1, x0, x1)
            val block = max(6, min(x1 - x0, y1 - y0) / 6)
            val small = Mat()
            Imgproc.resize(roi, small, org.opencv.core.Size(max(1, (x1 - x0) / block).toDouble(), max(1, (y1 - y0) / block).toDouble()),
                0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(small, roi, roi.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
            small.release()
            roi.release()
        }
    }

    /** Words not covered by a redaction (they must also vanish from the PDF text layer). */
    fun remaining(words: List<Word>, regions: List<Redaction>) = words.filter { w ->
        regions.none { r -> w.x0 < r.x1 && w.x1 > r.x0 && w.y0 < r.y1 && w.y1 > r.y0 }
    }
}
