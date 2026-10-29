package de.ultimatepdfscanner.core

import org.opencv.core.Mat
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.roundToLong

/** An image placed on a PDF page: a JPEG embedded as it is, or a black-and-white bitmap. */
sealed class PdfImage(val width: Int, val height: Int) {
    /** [components] 1 grey, 3 colour, 4 CMYK ([adobe]: Adobe-inverted CMYK). */
    class Jpeg internal constructor(val bytes: ByteArray, width: Int, height: Int, val components: Int, val adobe: Boolean) : PdfImage(width, height)

    /** Rows packed MSB first, each padded to whole bytes; bit 1 = white (PDF DeviceGray). */
    class Bilevel internal constructor(val bits: ByteArray, width: Int, height: Int) : PdfImage(width, height)

    companion object {
        /** Reads size and colour components from the JPEG's frame header; the bytes are embedded unchanged. */
        fun jpeg(bytes: ByteArray): Jpeg {
            fun u8(i: Int) = bytes[i].toInt() and 0xff
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            require(bytes.size > 4 && u8(0) == 0xFF && u8(1) == 0xD8) { "not a JPEG" }
            var i = 2
            var adobe = false
            while (i + 4 <= bytes.size) {
                if (u8(i) != 0xFF) { i++; continue }
                val marker = u8(i + 1)
                if (marker == 0xFF) { i++; continue } // fill byte
                if (marker == 0xD8 || marker == 0x01 || marker in 0xD0..0xD7) { i += 2; continue } // no length
                val len = u16(i + 2)
                if (marker == 0xEE && len >= 12 && String(bytes, i + 4, 5, Charsets.ISO_8859_1) == "Adobe") adobe = true
                val sof = marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC
                if (sof) {
                    require(i + 9 < bytes.size) { "truncated JPEG" }
                    val h = u16(i + 5)
                    val w = u16(i + 7)
                    val n = u8(i + 9)
                    require(w > 0 && h > 0 && n in setOf(1, 3, 4)) { "unsupported JPEG ($w x $h, $n components)" }
                    return Jpeg(bytes, w, h, n, adobe)
                }
                if (marker == 0xDA) break // scan data before any frame header
                i += 2 + len
            }
            throw IllegalArgumentException("JPEG without frame header")
        }

        /** Black-and-white page: grey values >= [threshold] become white. Much smaller and sharper than a JPEG of it. */
        fun bilevel(gray: Mat, threshold: Int = 128): Bilevel {
            require(gray.channels() == 1) { "bilevel needs a single-channel image" }
            val w = gray.cols()
            val h = gray.rows()
            val px = bytes(gray)
            val rowBytes = (w + 7) / 8
            val bits = ByteArray(rowBytes * h)
            for (y in 0 until h) {
                val src = y * w
                val dst = y * rowBytes
                for (x in 0 until w) {
                    if ((px[src + x].toInt() and 0xff) >= threshold) {
                        val k = dst + (x shr 3)
                        bits[k] = (bits[k].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
            return Bilevel(bits, w, h)
        }
    }
}

/**
 * One PDF page: [image] drawn into [imageBox] (x, y, width, height in points, origin bottom
 * left) on a page of [widthPt] x [heightPt]; [words] (boxes in image pixels) become the
 * invisible, searchable and selectable text layer. [rotation] (clockwise, multiple of 90) is
 * applied by the viewer to image and text together.
 */
class PdfPage(
    val image: PdfImage,
    val widthPt: Double,
    val heightPt: Double,
    val imageBox: DoubleArray = doubleArrayOf(0.0, 0.0, widthPt, heightPt),
    val words: List<Word> = emptyList(),
    val rotation: Int = 0,
) {
    init {
        require(widthPt > 0 && heightPt > 0 && imageBox.size == 4) { "bad page geometry" }
        require(rotation % 90 == 0) { "rotation must be a multiple of 90" }
    }

    companion object {
        const val POINTS_PER_INCH = 72.0

        /** Page exactly as large as the image printed at [dpi]. */
        fun natural(image: PdfImage, words: List<Word> = emptyList(), dpi: Double = 300.0, rotation: Int = 0): PdfPage {
            val w = image.width * POINTS_PER_INCH / dpi
            val h = image.height * POINTS_PER_INCH / dpi
            return PdfPage(image, w, h, doubleArrayOf(0.0, 0.0, w, h), words, rotation)
        }

        /**
         * Image centred on paper of [paperW] x [paperH] points (turned to landscape for a landscape
         * image), scaled to fit but never enlarged beyond [maxEnlarge] x its size at [dpi].
         */
        fun onPaper(image: PdfImage, paperW: Double, paperH: Double, words: List<Word> = emptyList(), dpi: Double = 300.0,
                    rotation: Int = 0, maxEnlarge: Double = 1.25): PdfPage {
            val landscape = image.width > image.height
            val pw = if (landscape) maxOf(paperW, paperH) else minOf(paperW, paperH)
            val ph = if (landscape) minOf(paperW, paperH) else maxOf(paperW, paperH)
            val natural = POINTS_PER_INCH / dpi
            val s = minOf(pw / image.width, ph / image.height, natural * maxEnlarge)
            val w = image.width * s
            val h = image.height * s
            return PdfPage(image, pw, ph, doubleArrayOf((pw - w) / 2, (ph - h) / 2, w, h), words, rotation)
        }

        val A4 = 595.2756 to 841.8898 // 210 x 297 mm
        val LETTER = 612.0 to 792.0
    }
}

/**
 * Document properties. [pdfa]: write PDF/A-2b (archival: sRGB output intent, XMP metadata,
 * embedded font); ignored when a [password] is set, because PDF/A forbids encryption.
 * [password]: AES-256 encryption (PDF 2.0 / Acrobat X "AESV3"), needed to open the file.
 */
data class PdfInfo(
    val title: String? = null,
    val author: String? = null,
    val subject: String? = null,
    val keywords: String? = null,
    val creator: String = "Ultimate PDF Scanner",
    val pdfa: Boolean = true,
    val password: String? = null,
    val created: ZonedDateTime = ZonedDateTime.now(),
)

/**
 * Streaming writer for scanned documents: page images plus an invisible text layer in a
 * "glyphless" font (the approach of Tesseract's and OCRmyPDF's PDF renderers). Every Unicode
 * character of the BMP is kept (Greek letters, formula symbols, ...), each word ends with a space
 * so copied text keeps its word gaps, and selecting a word highlights exactly its box. Pages go
 * to [output] as they are added, so memory use does not grow with the page count.
 *
 * Usage: `PdfWriter(out, info).use { w -> pages.forEach { w.addPage(it) } }`.
 */
class PdfWriter(output: OutputStream, private val info: PdfInfo = PdfInfo()) : AutoCloseable {
    private val out = CountingStream(output)
    private val offsets = HashMap<Int, Long>()
    private var nextId = FIRST_FREE
    private val pageIds = ArrayList<Int>()
    private val random = SecureRandom()
    private val crypt = info.password?.takeIf { it.isNotEmpty() }?.let { Aes256(it, random) }
    private val pdfa = info.pdfa && crypt == null
    private val fileId = ByteArray(16).also { random.nextBytes(it) }
    private var closed = false

    init {
        // binary comment: marks the file as binary for transfer programs (required by PDF/A)
        out.write("%PDF-1.7\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        writeFont()
    }

    private fun newId() = nextId++

    fun addPage(page: PdfPage) {
        check(!closed) { "writer closed" }
        val imageId = newId()
        val contentId = newId()
        val pageId = newId()
        writeImage(imageId, page.image)
        writeStream(contentId, "", deflate(content(page)), filter = "/FlateDecode")
        obj(pageId, "<< /Type /Page /Parent $PAGES 0 R /MediaBox [0 0 ${n(page.widthPt)} ${n(page.heightPt)}]" +
            (if (page.rotation % 360 != 0) " /Rotate ${((page.rotation % 360) + 360) % 360}" else "") +
            " /Resources << /XObject << /Im0 $imageId 0 R >> /Font << /F0 $FONT 0 R >> >> /Contents $contentId 0 R >>")
        pageIds += pageId
    }

    override fun close() {
        if (closed) return
        closed = true
        val infoId = newId()
        obj(infoId, infoDict())
        var catalogExtra = " /ViewerPreferences << /DisplayDocTitle true >>"
        if (pdfa) {
            val metaId = newId()
            writeStream(metaId, "/Type /Metadata /Subtype /XML", xmp().toByteArray(Charsets.UTF_8), compress = false)
            val iccId = newId()
            writeStream(iccId, "/N 3", deflate(Icc.srgb()), filter = "/FlateDecode")
            catalogExtra += " /Metadata $metaId 0 R /OutputIntents [<< /Type /OutputIntent /S /GTS_PDFA1" +
                " /OutputConditionIdentifier (sRGB IEC61966-2.1) /Info (sRGB IEC61966-2.1) /DestOutputProfile $iccId 0 R >>]"
        }
        obj(PAGES, "<< /Type /Pages /Count ${pageIds.size} /Kids [${pageIds.joinToString(" ") { "$it 0 R" }}] >>")
        obj(CATALOG, "<< /Type /Catalog /Pages $PAGES 0 R$catalogExtra >>")
        val encryptId = crypt?.let { c -> newId().also { id -> obj(id, c.dictionary()) } }
        val xref = out.count
        val sb = StringBuilder("xref\n0 $nextId\n0000000000 65535 f \n")
        for (id in 1 until nextId) {
            val off = offsets[id]
            sb.append(if (off != null) "%010d 00000 n \n".format(off) else "0000000000 65535 f \n")
        }
        val idHex = hex(fileId)
        sb.append("trailer\n<< /Size $nextId /Root $CATALOG 0 R /Info $infoId 0 R /ID [<$idHex> <$idHex>]")
        if (encryptId != null) sb.append(" /Encrypt $encryptId 0 R")
        sb.append(" >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    // --- page parts ----------------------------------------------------------------------

    private fun writeImage(id: Int, image: PdfImage) = when (image) {
        is PdfImage.Jpeg -> {
            val cs = when (image.components) { 1 -> "/DeviceGray"; 3 -> "/DeviceRGB"; else -> "/DeviceCMYK" }
            val decode = if (image.components == 4 && image.adobe) " /Decode [1 0 1 0 1 0 1 0]" else ""
            writeStream(id, "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height} /ColorSpace $cs" +
                " /BitsPerComponent 8$decode", image.bytes, filter = "/DCTDecode")
        }
        is PdfImage.Bilevel -> writeStream(id, "/Type /XObject /Subtype /Image /Width ${image.width} /Height ${image.height}" +
            " /ColorSpace /DeviceGray /BitsPerComponent 1", deflate(image.bits), filter = "/FlateDecode")
    }

    /**
     * Image, then the text layer line by line: one font size per line (the line's height), the
     * baseline at the line's bottom, so a selection covers the line box; each word is stretched
     * (Tz) to reach the start of the next word, its trailing space included.
     */
    private fun content(page: PdfPage): ByteArray {
        val (ix, iy, iw, ih) = page.imageBox.toList()
        val sx = iw / page.image.width
        val sy = ih / page.image.height
        val sb = StringBuilder()
        sb.append("q ${n(iw)} 0 0 ${n(ih)} ${n(ix)} ${n(iy)} cm /Im0 Do Q\n")
        val lines = page.words.filter { it.text.isNotBlank() && it.x1 > it.x0 && it.y1 > it.y0 }
            .groupBy { Triple(it.block, it.paragraph, it.line) }
        if (lines.isNotEmpty()) {
            sb.append("BT 3 Tr\n")
            val keys = lines.keys.sortedWith(compareBy({ it.first }, { it.second }, { it.third }))
            for (key in keys) {
                val words = lines.getValue(key).sortedBy { it.x0 }
                val top = words.minOf { it.y0 }
                val bottom = words.maxOf { it.y1 }
                val size = (bottom - top) * sy
                if (size <= 0.01) continue
                val baseline = iy + (page.image.height - bottom) * sy
                sb.append("/F0 ${n(size)} Tf\n")
                for ((k, w) in words.withIndex()) {
                    val next = words.getOrNull(k + 1)
                    val text = visualOrder(w.text.trim()) + if (next != null) " " else ""
                    val units = text.length
                    // to the next word (gap included); the last word of the line only to its own end
                    val target = (if (next != null && next.x0 > w.x0) next.x0 - w.x0 else w.x1 - w.x0) * sx
                    val natural = units * GLYPH_ADVANCE * size
                    val tz = if (natural > 0) 100 * target / natural else 100.0
                    sb.append("${n(tz)} Tz 1 0 0 1 ${n(ix + w.x0 * sx)} ${n(baseline)} Tm <")
                    for (ch in text) sb.append("%04X".format(ch.code))
                    sb.append("> Tj\n")
                }
            }
            sb.append("ET\n")
        }
        return sb.toString().toByteArray(Charsets.ISO_8859_1)
    }

    /**
     * Right-to-left words (Arabic, Hebrew) in visual order: PDF readers expect the characters laid out
     * from the left and restore the reading order themselves when text is copied or searched.
     */
    private fun visualOrder(word: String): String =
        if (word.none { Character.getDirectionality(it).let { d -> d == Character.DIRECTIONALITY_RIGHT_TO_LEFT || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC } }) word
        else PaddleOcr.logicalOrder(word) // the same reversal turns reading order into visual order

    // --- font ------------------------------------------------------------------------------

    /**
     * Type0 font with Identity-H encoding: a character code is its UTF-16 code unit, mapped back to
     * Unicode by the ToUnicode CMap; every code is drawn with the font's single empty glyph
     * (advance 500/1000 em, [GLYPH_ADVANCE]).
     */
    private fun writeFont() {
        val cid = newId(); val descriptor = newId(); val file = newId(); val cidToGid = newId(); val toUnicode = newId()
        obj(FONT, "<< /Type /Font /Subtype /Type0 /BaseFont /GlyphLessFont /Encoding /Identity-H" +
            " /DescendantFonts [$cid 0 R] /ToUnicode $toUnicode 0 R >>")
        obj(cid, "<< /Type /Font /Subtype /CIDFontType2 /BaseFont /GlyphLessFont" +
            " /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >>" +
            " /FontDescriptor $descriptor 0 R /CIDToGIDMap $cidToGid 0 R /DW 500 >>")
        obj(descriptor, "<< /Type /FontDescriptor /FontName /GlyphLessFont /Flags 5 /FontBBox [0 0 500 1000]" +
            " /ItalicAngle 0 /Ascent 1000 /Descent 0 /CapHeight 1000 /StemV 80 /FontFile2 $file 0 R >>")
        val font = GLYPHLESS_TTF
        writeStream(file, "/Length1 ${font.size}", deflate(font), filter = "/FlateDecode")
        // every CID -> glyph 1
        val map = ByteArray(2 * 65536) { if (it % 2 == 1) 1 else 0 }
        writeStream(cidToGid, "", deflate(map), filter = "/FlateDecode")
        val cmap = """
            /CIDInit /ProcSet findresource begin
            12 dict begin
            begincmap
            /CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def
            /CMapName /Adobe-Identity-UCS def
            /CMapType 2 def
            1 begincodespacerange
            <0000> <FFFF>
            endcodespacerange
            1 beginbfrange
            <0000> <FFFF> <0000>
            endbfrange
            endcmap
            CMapName currentdict /CMap defineresource pop
            end
            end
        """.trimIndent().toByteArray(Charsets.ISO_8859_1)
        writeStream(toUnicode, "", deflate(cmap), filter = "/FlateDecode")
    }

    // --- metadata --------------------------------------------------------------------------

    private fun infoDict(): String {
        val sb = StringBuilder("<<")
        info.title?.let { sb.append(" /Title ").append(text(it)) }
        info.author?.let { sb.append(" /Author ").append(text(it)) }
        info.subject?.let { sb.append(" /Subject ").append(text(it)) }
        info.keywords?.let { sb.append(" /Keywords ").append(text(it)) }
        sb.append(" /Creator ").append(text(info.creator))
        sb.append(" /Producer ").append(text(info.creator))
        val date = pdfDate(info.created)
        sb.append(" /CreationDate ").append(text(date)).append(" /ModDate ").append(text(date))
        return sb.append(" >>").toString()
    }

    private fun pdfDate(t: ZonedDateTime): String {
        val off = t.offset.totalSeconds / 60
        val sign = if (off < 0) "-" else "+"
        return "D:" + t.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss", Locale.ROOT)) +
            "%s%02d'%02d'".format(Locale.ROOT, sign, abs(off) / 60, abs(off) % 60)
    }

    /** XMP packet matching the Info dictionary (PDF/A requires both to agree). */
    private fun xmp(): String {
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        val date = info.created.withNano(0).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        val sb = StringBuilder()
        sb.append("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n")
        sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">\n<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
        sb.append("<rdf:Description rdf:about=\"\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\"")
        sb.append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\">\n")
        sb.append("<pdfaid:part>2</pdfaid:part>\n<pdfaid:conformance>B</pdfaid:conformance>\n")
        info.title?.let { sb.append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(it)}</rdf:li></rdf:Alt></dc:title>\n") }
        info.author?.let { sb.append("<dc:creator><rdf:Seq><rdf:li>${esc(it)}</rdf:li></rdf:Seq></dc:creator>\n") }
        info.subject?.let { sb.append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(it)}</rdf:li></rdf:Alt></dc:description>\n") }
        info.keywords?.let { sb.append("<pdf:Keywords>${esc(it)}</pdf:Keywords>\n") }
        sb.append("<dc:format>application/pdf</dc:format>\n")
        sb.append("<xmp:CreatorTool>${esc(info.creator)}</xmp:CreatorTool>\n<pdf:Producer>${esc(info.creator)}</pdf:Producer>\n")
        sb.append("<xmp:CreateDate>$date</xmp:CreateDate>\n<xmp:ModifyDate>$date</xmp:ModifyDate>\n<xmp:MetadataDate>$date</xmp:MetadataDate>\n")
        sb.append("</rdf:Description>\n</rdf:RDF>\n</x:xmpmeta>\n")
        repeat(20) { sb.append("                                                                                \n") } // room for in-place edits
        sb.append("<?xpacket end=\"w\"?>")
        return sb.toString()
    }

    // --- low level -------------------------------------------------------------------------

    /** A text string: plain ASCII as a literal, anything else as UTF-16BE with BOM; encrypted if needed. */
    private fun text(s: String): String {
        val ascii = s.all { it.code in 32..126 }
        val raw = if (ascii) s.toByteArray(Charsets.ISO_8859_1) else byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)
        val c = crypt
        if (c != null) return "<" + hex(c.encrypt(raw)) + ">"
        if (!ascii) return "<" + hex(raw) + ">"
        return "(" + s.replace("\\", "\\\\").replace("(", "\\(").replace(")", "\\)") + ")"
    }

    private fun obj(id: Int, body: String) {
        offsets[id] = out.count
        out.write("$id 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
    }

    private fun writeStream(id: Int, dict: String, data: ByteArray, filter: String? = null, compress: Boolean = true) {
        val payload = crypt?.encrypt(data) ?: data
        offsets[id] = out.count
        val f = if (filter != null && compress) " /Filter $filter" else ""
        out.write("$id 0 obj\n<< $dict$f /Length ${payload.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        out.write("\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
    }

    private class CountingStream(private val target: OutputStream) : OutputStream() {
        private val buffered = target.buffered(1 shl 16)
        var count = 0L
            private set
        override fun write(b: Int) { buffered.write(b); count++ }
        override fun write(b: ByteArray, off: Int, len: Int) { buffered.write(b, off, len); count += len }
        override fun flush() = buffered.flush()
        override fun close() = buffered.close()
    }

    companion object {
        private const val CATALOG = 1
        private const val PAGES = 2
        private const val FONT = 3
        private const val FIRST_FREE = 4
        const val GLYPH_ADVANCE = 0.5 // glyph width as share of the font size

        /** Number in PDF syntax: at most 4 decimals, no exponent, no trailing zeros. */
        internal fun n(v: Double): String {
            if (!v.isFinite()) return "0"
            val scaled = (v * 10000).roundToLong()
            val neg = scaled < 0
            val a = abs(scaled)
            val int = a / 10000
            val frac = (a % 10000).toString().padStart(4, '0').trimEnd('0')
            return (if (neg && (int != 0L || frac.isNotEmpty())) "-" else "") + int + (if (frac.isNotEmpty()) ".$frac" else "")
        }

        internal fun hex(b: ByteArray): String {
            val digits = "0123456789ABCDEF"
            val sb = StringBuilder(b.size * 2)
            for (x in b) { val v = x.toInt() and 0xff; sb.append(digits[v shr 4]).append(digits[v and 15]) }
            return sb.toString()
        }

        internal fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION)
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 4))
            val buf = ByteArray(1 shl 16)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            d.end()
            return out.toByteArray()
        }

        /**
         * Tesseract's GlyphLessFont (tessdata/pdf.ttf, Apache License 2.0, (c) Google): two glyphs,
         * .notdef and one empty glyph with an advance of 1024/2048 em.
         */
        private val GLYPHLESS_TTF: ByteArray = java.util.Base64.getDecoder().decode(
            "AAEAAAAKAIAAAwAgT1MvMlbeyJQAAAEoAAAAYGNtYXAACgA0AAABkAAAAB5nbHlmFSJBJAAAAbgAAAAYaGVhZAt48WUAAACsAAAANmhoZWEM" +
            "AgQCAAAA5AAAACRobXR4BAAAAAAAAYgAAAAIbG9jYQAMAAAAAAGwAAAABm1heHAABAAFAAABCAAAACBuYW1l8usW2gAAAdAAAABLcG9zdAAB" +
            "AAEAAAIcAAAAIAABAAAAAQAAsJRxEF8PPPUEBwgAAAAAAM+a/G4AAAAA1MOn8gAAAAAEAAgAAAAAEAACAAAAAAAAAAEAAAgA//8AAAQAAAAA" +
            "AAQAAAEAAAAAAAAAAAAAAAAAAAACAAEAAAACAAQAAQAAAAAAAQAAAAAAAAAAAAAAAAAAAAAAAwAAAZAABQAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "AAAAAAUAAQABAAAAAAAAAAAAAAAAAAAAAAAAAAAAR09PRwBAAAAAAAAB//8AAAABAAGAAAAAAAAAAAAAAAAAAAABAAAAAAAABAAAAAAAAAIA" +
            "AQAAAAAAFAADAAAAAAAUAAYACgAAAAAAAAAAAAAAAAAMAAAAAQAAAAAEAAgAAAMAADEhESEEAPwACAAAAAADACoAAAADAAAABQAWAAAAAQAA" +
            "AAAABQALABYAAwABBAkABQAWAAAAVgBlAHIAcwBpAG8AbgAgADEALgAwVmVyc2lvbiAxLjAAAAEAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAA" +
            "AAAAAAA="
        )
    }
}

/** AES-256 encryption, security handler revision 6 (ISO 32000-2, 7.6.4.3.3/7.6.4.4). */
internal class Aes256(password: String, private val random: SecureRandom) {
    private val key = ByteArray(32).also { random.nextBytes(it) }
    private val u: ByteArray
    private val ue: ByteArray
    private val o: ByteArray
    private val oe: ByteArray
    private val perms: ByteArray

    init {
        // the password is used as UTF-8, at most 127 bytes (SASLprep is the identity for ordinary characters)
        val pwd = password.toByteArray(Charsets.UTF_8).let { if (it.size > 127) it.copyOf(127) else it }
        val uValidation = bytes(8); val uKeySalt = bytes(8)
        u = hash(pwd, uValidation, ByteArray(0)) + uValidation + uKeySalt
        ue = aes("AES/CBC/NoPadding", hash(pwd, uKeySalt, ByteArray(0)), key, ByteArray(16))
        val oValidation = bytes(8); val oKeySalt = bytes(8)
        o = hash(pwd, oValidation, u) + oValidation + oKeySalt
        oe = aes("AES/CBC/NoPadding", hash(pwd, oKeySalt, u), key, ByteArray(16))
        val p = PERMISSIONS
        val block = byteArrayOf(p.toByte(), (p shr 8).toByte(), (p shr 16).toByte(), (p shr 24).toByte(),
            -1, -1, -1, -1, 'T'.code.toByte(), 'a'.code.toByte(), 'd'.code.toByte(), 'b'.code.toByte()) + bytes(4)
        perms = aes("AES/ECB/NoPadding", key, block, null)
    }

    private fun bytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    fun dictionary(): String = "<< /Filter /Standard /V 5 /R 6 /Length 256" +
        " /CF << /StdCF << /AuthEvent /DocOpen /CFM /AESV3 /Length 32 >> >> /StmF /StdCF /StrF /StdCF" +
        " /O <${PdfWriter.hex(o)}> /U <${PdfWriter.hex(u)}> /OE <${PdfWriter.hex(oe)}> /UE <${PdfWriter.hex(ue)}>" +
        " /P $PERMISSIONS /Perms <${PdfWriter.hex(perms)}> /EncryptMetadata true >>"

    /** Random IV followed by the AES-256-CBC ciphertext (PKCS#7 padding), as for every string and stream. */
    fun encrypt(data: ByteArray): ByteArray {
        val iv = bytes(16)
        return iv + aes("AES/CBC/PKCS5Padding", key, data, iv)
    }

    companion object {
        const val PERMISSIONS = -4 // everything allowed (printing, copying, ...): the password protects reading only

        private fun aes(transformation: String, key: ByteArray, data: ByteArray, iv: ByteArray?): ByteArray {
            val c = Cipher.getInstance(transformation)
            val k = SecretKeySpec(key, "AES")
            if (iv != null) c.init(Cipher.ENCRYPT_MODE, k, IvParameterSpec(iv)) else c.init(Cipher.ENCRYPT_MODE, k)
            return c.doFinal(data)
        }

        /** Hardened hash of revision 6 (ISO 32000-2, algorithm 2.B). */
        internal fun hash(pwd: ByteArray, salt: ByteArray, udata: ByteArray): ByteArray {
            var k = MessageDigest.getInstance("SHA-256").digest(pwd + salt + udata)
            var round = 0
            while (true) {
                val unit = pwd + k + udata
                val k1 = ByteArray(unit.size * 64)
                for (i in 0 until 64) System.arraycopy(unit, 0, k1, i * unit.size, unit.size)
                val e = aes("AES/CBC/NoPadding", k.copyOfRange(0, 16), k1, k.copyOfRange(16, 32))
                var sum = 0
                for (i in 0 until 16) sum += e[i].toInt() and 0xff
                k = MessageDigest.getInstance(when (sum % 3) { 0 -> "SHA-256"; 1 -> "SHA-384"; else -> "SHA-512" }).digest(e)
                round++
                if (round >= 64 && (e[e.size - 1].toInt() and 0xff) <= round - 32) break
            }
            return k.copyOf(32)
        }
    }
}

/** A compact sRGB ICC profile (v2, D50-adapted primaries, sampled sRGB tone curve) for the PDF/A output intent. */
internal object Icc {
    fun srgb(): ByteArray {
        val tags = ArrayList<Pair<String, ByteArray>>()
        fun xyz(x: Double, y: Double, z: Double) = buf { tag("XYZ "); i32(0); s15(x); s15(y); s15(z) }
        tags += "desc" to buf {
            val s = "sRGB IEC61966-2.1"
            tag("desc"); i32(0); i32(s.length + 1); bytes(s.toByteArray(Charsets.US_ASCII)); u8(0)
            i32(0); i32(0); u16(0); u8(0); bytes(ByteArray(67))
        }
        tags += "cprt" to buf { tag("text"); i32(0); bytes("No copyright, use freely".toByteArray(Charsets.US_ASCII)); u8(0) }
        tags += "wtpt" to xyz(0.9642, 1.0, 0.8249)
        tags += "rXYZ" to xyz(0.4361, 0.2225, 0.0139)
        tags += "gXYZ" to xyz(0.3851, 0.7169, 0.0971)
        tags += "bXYZ" to xyz(0.1431, 0.0606, 0.7141)
        val curve = buf {
            tag("curv"); i32(0); i32(1024)
            for (i in 0 until 1024) {
                val v = i / 1023.0
                val lin = if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
                u16((lin * 65535).roundToLong().toInt())
            }
        }
        // the three tone curves share one data block
        val entries = tags.map { it.first } + listOf("rTRC", "gTRC", "bTRC")
        val blocks = tags.map { it.second } + listOf(curve)
        val tableSize = 4 + 12 * entries.size
        var offset = 128 + tableSize
        val blockOffsets = blocks.map { b -> offset.also { offset += (b.size + 3) / 4 * 4 } }
        val total = offset
        return buf {
            i32(total); i32(0); i32(0x02100000); tag("mntr"); tag("RGB "); tag("XYZ ")
            u16(2026); u16(1); u16(1); u16(0); u16(0); u16(0)
            tag("acsp"); i32(0); i32(0); i32(0); i32(0); bytes(ByteArray(8)); i32(0)
            s15(0.9642); s15(1.0); s15(0.8249)
            i32(0); bytes(ByteArray(16)); bytes(ByteArray(28))
            i32(entries.size)
            entries.forEachIndexed { i, name ->
                val b = minOf(i, blocks.size - 1)
                tag(name); i32(blockOffsets[b]); i32(blocks[b].size)
            }
            for (b in blocks) { bytes(b); bytes(ByteArray((4 - b.size % 4) % 4)) }
        }
    }

    private class Buf {
        val out = ByteArrayOutputStream()
        fun u8(v: Int) = out.write(v)
        fun u16(v: Int) { out.write(v shr 8); out.write(v) }
        fun i32(v: Int) { out.write(v ushr 24); out.write(v shr 16); out.write(v shr 8); out.write(v) }
        fun s15(v: Double) = i32((v * 65536).roundToLong().toInt())
        fun tag(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun bytes(b: ByteArray) = out.write(b)
    }

    private fun buf(block: Buf.() -> Unit): ByteArray = Buf().apply(block).out.toByteArray()
}
