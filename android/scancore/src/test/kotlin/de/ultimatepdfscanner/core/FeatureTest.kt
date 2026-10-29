package de.ultimatepdfscanner.core

import java.io.File
import java.util.Calendar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Classification/naming, sensitive-data detection and the two-column reading order. */
class FeatureTest {
    init { TestSupport.init() }

    @Test
    fun classifiesTypicalDocuments() {
        assertEquals(DocType.RECEIPT, Classify.classify("REWE Markt\nBanane 1,29\nSUMME EUR 12,40\nGegeben bar 20,00\nRückgeld 7,60\nVielen Dank für Ihren Einkauf", aspect = 2.6))
        assertEquals(DocType.INVOICE, Classify.classify("Rechnung\nRechnungsnummer: 2026-0815\nRechnungsdatum 23.10.2026\nNettobetrag 100,00\nUSt 19 %\nZahlbar innerhalb 14 Tagen\nIBAN DE89 3704 0044 0532 0130 00"))
        assertEquals(DocType.BUSINESS_CARD, Classify.classify("Dr. Erika Muster\nVertrieb\n+49 170 1234567\nerika@example.com\nwww.example.com", aspect = 1.6))
        assertEquals(DocType.CONTRACT, Classify.classify("Mietvertrag\nzwischen den Vertragspartnern\n§ 1 Mietsache\n§ 2 Laufzeit und Kündigung"))
        assertEquals(DocType.LETTER, Classify.classify("Betreff: Ihre Anfrage\nSehr geehrte Frau Muster,\nvielen Dank ...\nMit freundlichen Grüßen"))
        assertEquals(DocType.DOCUMENT, Classify.classify("Die Energielage realistisch gesehen. Die meisten Industrienationen drehen seit einigen Jahren mit dem Anwachsen ihrer Wirtschaft", pageCount = 2))
    }

    @Test
    fun suggestsNameWithTypeAndTime() {
        val t = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 23, 12, 30) }.timeInMillis
        assertEquals("Quittung_2026-10-23_12-30", Classify.suggestName(DocType.RECEIPT, t))
    }

    private fun line(vararg texts: String, y: Int = 100): List<Word> {
        var x = 10
        return texts.map { t -> Word(t, 95f, x, y, x + 20 * t.length, y + 30, 1, 1, y).also { x += 20 * t.length + 15 } }
    }

    @Test
    fun findsSensitiveNumbersWithChecksums() {
        val words = line("Karte:", "4111", "1111", "1111", "1111", y = 100) +
            line("IBAN", "DE89", "3704", "0044", "0532", "0130", "00", y = 200) +
            line("Steuer-ID", "86095742719", y = 300) +
            line("Steuernummer", "21/815/08150", y = 400) +
            line("Passwort:", "Geheim123!", y = 500) +
            line("Bestellnummer", "1234567890123", y = 600) + // fails Luhn -> must stay
            line("Telefon", "0170", "1234567", y = 700)
        val found = Redact.find(words)
        assertEquals(listOf("Kartennummer", "IBAN", "Steuer-ID", "Steuernummer", "Passwort"), found.map { it.kind }, "$found")
        val remaining = Redact.remaining(words, found).joinToString(" ") { it.text }
        assertTrue("4111" !in remaining && "Geheim123!" !in remaining && "86095742719" !in remaining)
        assertTrue("1234567890123" in remaining && "Telefon" in remaining)
    }

    @Test
    fun checksums() {
        assertTrue(Redact.luhn("4111111111111111"))
        assertTrue(!Redact.luhn("4111111111111112"))
        assertTrue(Redact.ibanValid("DE89370400440532013000"))
        assertTrue(!Redact.ibanValid("DE89370400440532013001"))
        assertTrue(Redact.steuerIdValid("86095742719"))
        assertTrue(!Redact.steuerIdValid("86095742718"))
        assertNull(Redact.numberKind("1234 5678", ""))
    }

    @Test
    fun redactionBlacksOutOrPixelates() {
        val page = TestSupport.makePage()
        val r = Redaction("Kartennummer", 120, 140, 700, 200)
        val black = page.clone().also { Redact.apply(it, listOf(r)) }
        val roi = org.opencv.core.Rect(130, 150, 500, 40)
        assertTrue(org.opencv.core.Core.mean(org.opencv.core.Mat(black, roi)).`val`[0] < 1.0)
        val mosaic = page.clone().also { Redact.apply(it, listOf(r), pixelate = true) }
        val m = org.opencv.core.Mat(mosaic, roi)
        // a mosaic has no fine detail left: neighbouring pixels are (almost) always equal
        val dx = org.opencv.core.Mat()
        org.opencv.core.Core.absdiff(m.colRange(1, m.cols()), m.colRange(0, m.cols() - 1), dx)
        val changes = org.opencv.core.Core.countNonZero(Enhance.toGray(dx)).toDouble() / dx.total()
        assertTrue(changes < 0.1, "pixelated area still detailed: $changes")
    }

    @Test
    fun signatureBlendsOnlyTheStrokes() {
        val page = org.opencv.core.Mat(400, 400, org.opencv.core.CvType.CV_8UC3, org.opencv.core.Scalar(250.0, 250.0, 250.0))
        val sig = org.opencv.core.Mat(100, 200, org.opencv.core.CvType.CV_8UC4, org.opencv.core.Scalar(0.0, 0.0, 0.0, 0.0)) // transparent
        org.opencv.imgproc.Imgproc.line(sig, org.opencv.core.Point(10.0, 50.0), org.opencv.core.Point(190.0, 50.0),
            org.opencv.core.Scalar(120.0, 40.0, 10.0, 255.0), 8) // opaque blue stroke
        Signature.blend(page, sig, listOf(0.25, 0.5, 0.75, 0.75))
        val stroke = page.get(250, 200) // centre of the stroke (rect 100..300 x 200..300)
        val paper = page.get(210, 200) // inside the rect but off the stroke
        assertTrue(stroke[0] < 160 && stroke[2] < 60, "stroke should be blue ink: ${stroke.toList()}")
        paper.forEach { assertEquals(250.0, it, 1.0, "transparent parts must leave the paper untouched: ${paper.toList()}") }
    }

    @Test
    fun previewIgnoresDarkFramesButFindsPages() {
        val dark = org.opencv.core.Mat(480, 360, org.opencv.core.CvType.CV_8UC3)
        org.opencv.core.Core.randu(dark, 0.0, 25.0) // covered lens / dark room
        assertNull(Detect.previewQuad(dark))
        val (photo, _) = TestSupport.makePhoto(TestSupport.makePage())
        val small = org.opencv.core.Mat().also { org.opencv.imgproc.Imgproc.resize(photo, it, org.opencv.core.Size(400.0, 480.0)) }
        assertTrue(Detect.previewQuad(small) != null, "page on a dark table must still be found")
    }

    /** Phone photos of a bare wooden table (no page) must not show an outline; book pages on wood must. */
    @Test
    fun previewIgnoresBareWoodenTable() {
        fun preview(f: java.io.File): Quad? {
            val img = TestSupport.load(f)
            val small = org.opencv.core.Mat().also { org.opencv.imgproc.Imgproc.resize(img, it, org.opencv.core.Size(480.0, 640.0), 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA) }
            return Detect.previewQuad(small)
        }
        java.io.File("../../testdata/phone_table").listFiles { f -> f.extension == "jpg" }.orEmpty()
            .forEach { assertNull(preview(it), "${it.name}: bare table taken for a page") }
        java.io.File("../../testdata/phone_spread").listFiles { f -> f.extension == "jpg" }.orEmpty()
            .forEach { assertTrue(preview(it) != null, "${it.name}: book on the table not found") }
    }

    /** Tesseract's layout analysis must read a two-column page column by column. */
    @Test
    fun twoColumnPageReadsColumnByColumn() {
        val ocr = TesseractCli("deu")
        val page = File("build/realphoto-pages/page09.jpg")
        if (!ocr.available || !page.exists()) return
        val text = pageText(ocr.recognize(Enhance.toGray(TestSupport.load(page))))
        val endOfColumn1 = text.indexOf("verklungenes")
        val heading = text.indexOf("Wo endet der Fortschritt")
        val column2 = text.indexOf("Lübbe kommt zu der Auffassung")
        assertTrue(endOfColumn1 in 0 until heading && heading < column2, "order: $endOfColumn1 / $heading / $column2")
    }
}
