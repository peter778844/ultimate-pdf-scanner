package de.ultimatepdfscanner.app.export

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.state.RenderingMode
import com.tom_roush.pdfbox.util.Matrix as PdfMatrix
import de.ultimatepdfscanner.app.data.DocInfo
import de.ultimatepdfscanner.app.data.PageInfo
import de.ultimatepdfscanner.app.data.Storage
import de.ultimatepdfscanner.core.Formulas
import de.ultimatepdfscanner.core.Office
import de.ultimatepdfscanner.core.PdfImage
import de.ultimatepdfscanner.core.PdfInfo
import de.ultimatepdfscanner.core.PdfPage
import de.ultimatepdfscanner.core.PdfWriter
import de.ultimatepdfscanner.core.Tables
import de.ultimatepdfscanner.core.Word
import de.ultimatepdfscanner.core.pageText
import de.ultimatepdfscanner.core.Orientation
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class ExportFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    DOCX("Word", "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    JPG("Bilder", "jpg", "image/jpeg"),
    ZIP("ZIP", "zip", "application/zip"),
    TXT("Text", "txt", "text/plain"),
    XLSX("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("CSV", "csv", "text/csv"),
    VCF("Kontakt", "vcf", "text/x-vcard"),
}

enum class PageSize(val label: String, val rect: PDRectangle?) {
    ORIGINAL("Wie gescannt", null), A4("A4", PDRectangle.A4), LETTER("Letter", PDRectangle.LETTER),
}

/** Image resolution in the export; [dpi] 0 = as scanned (no downscaling). */
enum class Resolution(val label: String, val dpi: Int) {
    ORIGINAL("Original", 0), DPI300("300 dpi", 300), DPI200("200 dpi", 200), DPI150("150 dpi", 150),
}

/** JPEG compression of the page images; LOW keeps the stored page as it is when nothing else changes. */
enum class Compression(val label: String, val jpeg: Int) {
    LOW("Gering", 92), MEDIUM("Mittel", 80), HIGH("Stark", 60),
}

data class ExportOptions(
    val format: ExportFormat = ExportFormat.PDF,
    val pageSize: PageSize = PageSize.ORIGINAL,
    val resolution: Resolution = Resolution.ORIGINAL,
    val compression: Compression = Compression.LOW,
    val password: String? = null,
    val docxImages: Boolean = false, // Word: page images above the text
)

/** Searchable PDF (page image + invisible OCR text layer) and the other export formats. */
object Exporter {
    private const val DPI = 300f
    private val FONT = PDType1Font.HELVETICA

    /** Helvetica/WinAnsi cannot encode every character; replace those it cannot show. */
    private fun encodable(text: String): String = buildString {
        for (ch in text) {
            val s = ch.toString()
            append(try { FONT.encode(s); s } catch (e: Exception) { "?" })
        }
    }

    /** Pixel size of a stored page image (unrotated). */
    fun pageSize(storage: Storage, doc: DocInfo, page: PageInfo): Pair<Int, Int> {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(storage.pageFile(doc, page).path, bounds)
        return bounds.outWidth to bounds.outHeight
    }

    /**
     * The page JPEG for an export. [ptPerPx] is how large one page pixel is printed (points), so
     * [Resolution] can be met; the image is only re-encoded if it has to shrink, be compressed
     * harder or be turned ([rotate]). Grey and black-and-white pages stay single-channel JPEGs.
     */
    fun pageJpeg(storage: Storage, doc: DocInfo, page: PageInfo, resolution: Resolution, compression: Compression,
                 rotate: Boolean = false, ptPerPx: Float = 72f / DPI): Pair<ByteArray, Pair<Int, Int>> {
        val file = storage.pageFile(doc, page)
        val f = if (resolution.dpi == 0) 1.0 else minOf(1.0, ptPerPx * resolution.dpi / 72.0)
        val turn = rotate && page.rotation != 0
        if (f >= 0.999 && compression == Compression.LOW && !turn) return file.readBytes() to pageSize(storage, doc, page)
        var img = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_UNCHANGED)
        if (f < 0.999) {
            val small = Mat()
            Imgproc.resize(img, small, Size(Math.round(img.cols() * f).toDouble(), Math.round(img.rows() * f).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
            img.release(); img = small
        }
        if (turn) { val r = Orientation.rotate(img, page.rotation); img.release(); img = r }
        val buf = MatOfByte()
        Imgcodecs.imencode(".jpg", img, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, compression.jpeg, Imgcodecs.IMWRITE_JPEG_OPTIMIZE, 1))
        val size = img.cols() to img.rows()
        img.release()
        return buf.toArray().also { buf.release() } to size
    }

    /**
     * [password]: protect the file with AES-256 (needed to open it); null = unprotected. With a
     * fixed [ExportOptions.pageSize] the page image is centred on it at its true scale or smaller.
     */
    fun writePdf(storage: Storage, doc: DocInfo, out: OutputStream, opts: ExportOptions = ExportOptions(), pages: List<PageInfo> = doc.pages,
                 progress: (Int, Int) -> Unit = { _, _ -> }) {
        // scancore's PdfWriter: full Unicode text layer, PDF/A-2b (without password), AES-256 with
        // one; pages are streamed, so a thick book does not have to fit into memory
        PdfWriter(out, PdfInfo(title = doc.name, password = opts.password?.takeIf { it.isNotEmpty() })).use { pdf ->
            pages.forEachIndexed { index, page ->
                progress(index + 1, pages.size)
                val (pw, ph) = pageSize(storage, doc, page) // the word boxes are in these pixels
                val natural = 72.0 / DPI
                val paper = when (opts.pageSize) { PageSize.A4 -> PdfPage.A4; PageSize.LETTER -> PdfPage.LETTER; else -> null }
                // points per stored pixel, as the page will be laid out (for the resolution limit)
                val scale = paper?.let { (w, h) ->
                    val (pw2, ph2) = if (pw > ph) maxOf(w, h) to minOf(w, h) else minOf(w, h) to maxOf(w, h)
                    minOf(pw2 / pw, ph2 / ph, natural * 1.25)
                } ?: natural
                val image = pageImage(storage, doc, page, opts, scale.toFloat())
                // words are in stored pixels; the embedded image may be smaller (resolution limit)
                val f = image.width.toDouble() / pw
                val words = storage.words(doc, page).let { ws ->
                    if (f > 0.999) ws else ws.map { w ->
                        w.copy(x0 = (w.x0 * f).toInt(), y0 = (w.y0 * f).toInt(), x1 = (w.x1 * f).toInt(), y1 = (w.y1 * f).toInt())
                    }
                }
                val dpi = 72.0 / scale * f // the image at its size fills the same area
                pdf.addPage(if (paper != null) PdfPage.onPaper(image, paper.first, paper.second, words, dpi = 72.0 / natural * f, rotation = page.rotation)
                            else PdfPage.natural(image, words, dpi = dpi, rotation = page.rotation))
            }
        }
    }

    /** The page as PDF image: black-and-white pages as 1-bit bitmap (sharp and small), others as JPEG. */
    private fun pageImage(storage: Storage, doc: DocInfo, page: PageInfo, opts: ExportOptions, ptPerPx: Float): PdfImage {
        if (page.mode == de.ultimatepdfscanner.core.Mode.BW) {
            val gray = Imgcodecs.imread(storage.pageFile(doc, page).path, Imgcodecs.IMREAD_GRAYSCALE)
            val f = if (opts.resolution.dpi == 0) 1.0 else minOf(1.0, ptPerPx * opts.resolution.dpi / 72.0)
            val img = if (f < 0.999) Mat().also { Imgproc.resize(gray, it, Size(Math.round(gray.cols() * f).toDouble(), Math.round(gray.rows() * f).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA); gray.release() } else gray
            return PdfImage.bilevel(img).also { img.release() }
        }
        val (bytes, _) = pageJpeg(storage, doc, page, opts.resolution, opts.compression, ptPerPx = ptPerPx)
        return PdfImage.jpeg(bytes) // embedded as is, no re-compression
    }

    private const val MM = 72f / 25.4f
    const val ID_W_MM = 85.6f // ID-1 format (identity cards, driving licences, bank cards)
    const val ID_H_MM = 53.98f

    /**
     * Identity card copy: front and back (consecutive pages) at their true size on one A4 page,
     * one below the other, ready to print. The text layer is kept for unrotated pages.
     */
    fun writeIdCardPdf(storage: Storage, doc: DocInfo, out: OutputStream, opts: ExportOptions = ExportOptions(), pages: List<PageInfo> = doc.pages) {
        PDDocument().use { pdf ->
            pdf.documentInformation.title = doc.name
            pdf.documentInformation.creator = "Ultimate PDF Scanner"
            val a4 = PDRectangle.A4
            for (pair in pages.chunked(2)) {
                val sheet = PDPage(a4)
                pdf.addPage(sheet)
                PDPageContentStream(pdf, sheet).use { cs ->
                    pair.forEachIndexed { k, page ->
                        val (ow, oh) = pageSize(storage, doc, page).let { (a, b) -> if (page.rotation % 180 == 0) a to b else b to a }
                        val size = ow to oh // the text boxes are in these (stored) pixels
                        val portrait = size.second > size.first
                        val w = (if (portrait) ID_H_MM else ID_W_MM) * MM
                        val h = (if (portrait) ID_W_MM else ID_H_MM) * MM
                        val x = (a4.width - w) / 2
                        val y = a4.height - 40 * MM - h - k * (h + 20 * MM)
                        val (bytes, _) = pageJpeg(storage, doc, page, opts.resolution, opts.compression, rotate = true, ptPerPx = w / size.first)
                        cs.drawImage(JPEGFactory.createFromStream(pdf, ByteArrayInputStream(bytes)), x, y, w, h)
                        if (page.rotation != 0) return@forEachIndexed
                        val sx = w / size.first
                        val sy = h / size.second
                        cs.beginText()
                        cs.setRenderingMode(RenderingMode.NEITHER)
                        for (word in storage.words(doc, page)) {
                            val text = encodable(word.text)
                            val fs = maxOf(1f, (word.y1 - word.y0) * sy)
                            val tw = FONT.getStringWidth(text) / 1000f * fs
                            cs.setFont(FONT, fs)
                            cs.setHorizontalScaling(if (tw > 0) 100f * (word.x1 - word.x0) * sx / tw else 100f)
                            cs.setTextMatrix(PdfMatrix.getTranslateInstance(x + word.x0 * sx, y + (size.second - word.y1) * sy + 0.2f * fs))
                            cs.showText(text)
                        }
                        cs.endText()
                    }
                }
            }
            if (!opts.password.isNullOrEmpty()) {
                pdf.protect(StandardProtectionPolicy(opts.password, opts.password, AccessPermission()).apply { encryptionKeyLength = 256 })
            }
            pdf.save(out)
        }
    }

    fun text(storage: Storage, doc: DocInfo, pages: List<PageInfo> = doc.pages): String =
        pages.joinToString("\n\n\u000C\n") { pageText(storage.words(doc, it)) }

    /** Word file; with [formulas] the display formulas (unreadable for the OCR) become pictures cut from the page. */
    fun writeDocx(storage: Storage, doc: DocInfo, out: OutputStream, opts: ExportOptions, pages: List<PageInfo> = doc.pages,
                  formulas: Formulas? = null, progress: (Int, Int) -> Unit = { _, _ -> }) {
        val parts = pages.mapIndexed { i, p ->
            progress(i + 1, pages.size)
            val words = storage.words(doc, p)
            val paragraphs = if (formulas != null && p.rotation == 0) formulaParagraphs(storage, doc, p, words, formulas)
                else pageText(words).split("\n\n").filter { it.isNotBlank() }.map { Office.Block.Text(it) }
            if (opts.docxImages) {
                val (jpeg, size) = pageJpeg(storage, doc, p, Resolution.DPI200, Compression.MEDIUM, rotate = true)
                Office.DocxPage(paragraphs, jpeg, size.first, size.second)
            } else Office.DocxPage(paragraphs)
        }
        Office.writeDocx(doc.name, parts, out)
    }

    private fun formulaParagraphs(storage: Storage, doc: DocInfo, page: PageInfo, words: List<Word>, formulas: Formulas): List<Office.Block> {
        val img = Imgcodecs.imread(storage.pageFile(doc, page).path)
        try {
            val found = formulas.detect(img)
            return Formulas.layoutText(words, found).map { part ->
                when (part) {
                    is Formulas.Part.Text -> Office.Block.Text(part.text)
                    is Formulas.Part.Formula -> {
                        val box = found[part.index]
                        val crop = img.submat(box)
                        val buf = MatOfByte()
                        Imgcodecs.imencode(".jpg", crop, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 90))
                        crop.release()
                        Office.Block.Picture(buf.toArray().also { buf.release() }, box.width, box.height, box.width.toDouble() / img.cols(), part.number)
                    }
                }
            }
        } finally {
            img.release()
        }
    }

    /** Tables per page; pages without a recognisable table give a single text column. */
    fun tables(storage: Storage, doc: DocInfo, pages: List<PageInfo> = doc.pages): List<List<List<String>>> =
        pages.map { Tables.extract(storage.words(doc, it)) }

    fun writeXlsx(storage: Storage, doc: DocInfo, out: OutputStream, pages: List<PageInfo> = doc.pages) =
        Office.writeXlsx(tables(storage, doc, pages).mapIndexed { i, rows -> "Seite ${i + 1}" to rows }, out)

    fun csv(storage: Storage, doc: DocInfo, pages: List<PageInfo> = doc.pages): String {
        val all = tables(storage, doc, pages).flatMapIndexed { i, rows -> (if (i > 0) listOf(emptyList<String>()) else emptyList()) + rows }
        return Tables.toCsv(all)
    }

    /** Page images with the rotation applied, named Name_01.jpg ... */
    fun writeJpegs(storage: Storage, doc: DocInfo, dir: File, base: String, opts: ExportOptions, pages: List<PageInfo> = doc.pages): List<File> =
        pages.mapIndexed { i, p ->
            File(dir, if (pages.size == 1) "$base.jpg" else "%s_%02d.jpg".format(base, i + 1))
                .also { it.writeBytes(pageJpeg(storage, doc, p, opts.resolution, opts.compression, rotate = true).first) }
        }

    fun writeZip(storage: Storage, doc: DocInfo, out: OutputStream, base: String, opts: ExportOptions, pages: List<PageInfo> = doc.pages) {
        ZipOutputStream(out).use { zip ->
            pages.forEachIndexed { i, p ->
                zip.putNextEntry(ZipEntry("%s_%02d.jpg".format(base, i + 1)))
                zip.write(pageJpeg(storage, doc, p, opts.resolution, opts.compression, rotate = true).first)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry("$base.txt"))
            zip.write(text(storage, doc, pages).toByteArray())
            zip.closeEntry()
        }
    }
}
