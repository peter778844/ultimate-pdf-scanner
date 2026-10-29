package de.ultimatepdfscanner.core

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Minimal Office Open XML writers (Word .docx, Excel .xlsx) without a library: just the parts
 * Word, Excel, LibreOffice and Google Docs need.
 */
object Office {
    /** One page of a Word export: its paragraphs and optionally the page image (JPEG bytes + pixel size). */
    class DocxPage(val paragraphs: List<Block>, val jpeg: ByteArray? = null, val width: Int = 0, val height: Int = 0)

    /**
     * A paragraph of a Word page: text, or a picture (of a formula) [widthShare] of the text width wide,
     * with its [number] at the right margin.
     */
    sealed interface Block {
        class Text(val text: String) : Block
        class Picture(val jpeg: ByteArray, val width: Int, val height: Int, val widthShare: Double, val number: String? = null) : Block
    }

    /** Centred formula, its number at the right margin of the text (16 cm = 9072 twips). */
    private const val NUMBERED = """<w:pPr><w:tabs><w:tab w:val="center" w:pos="4536"/><w:tab w:val="right" w:pos="9072"/></w:tabs></w:pPr>"""
    private fun numberRuns(number: String) = """<w:r><w:tab/><w:t xml:space="preserve">${esc(number)}</w:t></w:r>"""

    private fun esc(s: String) = buildString {
        for (c in s) when {
            c == '&' -> append("&amp;")
            c == '<' -> append("&lt;")
            c == '>' -> append("&gt;")
            c == '"' -> append("&quot;")
            c.code < 0x20 && c != '\t' && c != '\n' && c != '\r' -> {} // not allowed in XML 1.0
            else -> append(c)
        }
    }

    /** A table-of-contents line: title, at least two leader dots, page number ("Exzitonen . . . . 312"). */
    private val TOC_LINE = Regex("""(.*?\S)\s*(?:[.·…]\s*){2,}(\d{1,4}[a-z]?)\s*""")

    /** The first strongly directed character is right-to-left. */
    internal fun rightToLeft(text: String): Boolean = text.firstNotNullOfOrNull { c ->
        when (Character.getDirectionality(c)) {
            Character.DIRECTIONALITY_RIGHT_TO_LEFT, Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> true
            Character.DIRECTIONALITY_LEFT_TO_RIGHT -> false
            else -> null
        }
    } ?: false

    private fun ZipOutputStream.part(name: String, content: String) { putNextEntry(ZipEntry(name)); write(content.toByteArray()); closeEntry() }

    private const val CONTENT_TYPES_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>"""

    private fun rootRels(target: String, type: String = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument") =
        """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="$type" Target="$target"/></Relationships>"""

    private const val EMU_PER_INCH = 914400L
    private const val TEXT_WIDTH_EMU = 6_000_000L // A4 minus 2.5 cm margins, ~16.5 cm

    fun writeDocx(title: String, pages: List<DocxPage>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val hasImages = pages.any { p -> p.jpeg != null || p.paragraphs.any { it is Block.Picture } }
            zip.part("[Content_Types].xml", CONTENT_TYPES_HEAD + (if (hasImages) """<Default Extension="jpeg" ContentType="image/jpeg"/>""" else "") +
                """<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>""" +
                """<Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/></Types>""")
            zip.part("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/></Relationships>""")
            zip.part("docProps/core.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>${esc(title)}</dc:title><dc:creator>Ultimate PDF Scanner</dc:creator></cp:coreProperties>""")

            val rels = StringBuilder()
            val body = StringBuilder()
            var img = 0
            fun picture(bytes: ByteArray, cx: Long, cy: Long, name: String, number: String? = null) {
                img++
                zip.putNextEntry(ZipEntry("word/media/image$img.jpeg")); zip.write(bytes); zip.closeEntry()
                rels.append("""<Relationship Id="rIdImg$img" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/image$img.jpeg"/>""")
                body.append(if (number == null) """<w:p><w:pPr><w:jc w:val="center"/></w:pPr>""" else "<w:p>$NUMBERED<w:r><w:tab/></w:r>")
                body.append("""<w:r><w:drawing><wp:inline distT="0" distB="0" distL="0" distR="0"><wp:extent cx="$cx" cy="$cy"/><wp:docPr id="$img" name="${esc(name)}"/>""" +
                    """<a:graphic xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/picture">""" +
                    """<pic:pic xmlns:pic="http://schemas.openxmlformats.org/drawingml/2006/picture"><pic:nvPicPr><pic:cNvPr id="$img" name="image$img.jpeg"/><pic:cNvPicPr/></pic:nvPicPr>""" +
                    """<pic:blipFill><a:blip r:embed="rIdImg$img"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>""" +
                    """<pic:spPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="$cx" cy="$cy"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>""" +
                    """</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>""")
                body.append((number?.let(::numberRuns) ?: "") + "</w:p>")
            }
            pages.forEachIndexed { i, page ->
                if (i > 0) body.append("""<w:p><w:r><w:br w:type="page"/></w:r></w:p>""")
                page.jpeg?.let { bytes ->
                    val cx = TEXT_WIDTH_EMU
                    picture(bytes, cx, if (page.width > 0) cx * page.height / page.width else cx, "Seite ${i + 1}")
                }
                for (p in page.paragraphs) when (p) {
                    is Block.Text -> {
                        // right-to-left paragraphs (Arabic, Hebrew): Word places punctuation and numbers by the paragraph direction
                        val rtl = rightToLeft(p.text)
                        val lines = p.text.split('\n')
                        // table of contents: "Title ....... 312" as title, tab with dot leader, page number at the right margin
                        val toc = lines.any { TOC_LINE.matches(it) }
                        val props = (if (rtl) "<w:bidi/>" else "") + (if (toc) """<w:tabs><w:tab w:val="right" w:leader="dot" w:pos="9072"/></w:tabs>""" else "")
                        body.append(if (props.isEmpty()) "<w:p>" else "<w:p><w:pPr>$props</w:pPr>")
                        val rPr = if (rtl) "<w:rPr><w:rtl/></w:rPr>" else ""
                        lines.forEachIndexed { j, line ->
                            if (j > 0) body.append("<w:r><w:br/></w:r>")
                            val entry = TOC_LINE.matchEntire(line)
                            if (entry != null) body.append("""<w:r>$rPr<w:t xml:space="preserve">${esc(entry.groupValues[1])}</w:t></w:r><w:r>$rPr<w:tab/><w:t>${esc(entry.groupValues[2])}</w:t></w:r>""")
                            else body.append("""<w:r>$rPr<w:t xml:space="preserve">${esc(line)}</w:t></w:r>""")
                        }
                        body.append("</w:p>")
                    }
                    is Block.Picture -> {
                        val cx = (TEXT_WIDTH_EMU * p.widthShare.coerceIn(0.05, 1.0)).toLong()
                        picture(p.jpeg, cx, if (p.width > 0) cx * p.height / p.width else cx, "Formel", p.number)
                    }
                }
            }
            zip.part("word/_rels/document.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$rels</Relationships>""")
            zip.part("word/document.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" xmlns:wp="http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"><w:body>$body<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1417" w:right="1417" w:bottom="1134" w:left="1417" w:header="708" w:footer="708" w:gutter="0"/></w:sectPr></w:body></w:document>""")
        }
    }

    /** Excel column name: 0 -> A, 25 -> Z, 26 -> AA. */
    internal fun column(i: Int): String { var n = i; val sb = StringBuilder(); do { sb.insert(0, 'A' + n % 26); n = n / 26 - 1 } while (n >= 0); return sb.toString() }

    /** One sheet per entry (name to rows); cells that read as numbers become numeric. */
    fun writeXlsx(sheets: List<Pair<String, List<List<String>>>>, out: OutputStream) {
        val names = sheets.mapIndexed { i, (n, _) ->
            n.replace(Regex("""[\[\]:*?/\\]"""), " ").take(31).ifBlank { "Tabelle ${i + 1}" }
        }
        ZipOutputStream(out).use { zip ->
            zip.part("[Content_Types].xml", CONTENT_TYPES_HEAD +
                """<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>""" +
                sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } +
                "</Types>")
            zip.part("_rels/.rels", rootRels("xl/workbook.xml"))
            zip.part("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                names.mapIndexed { i, n -> """<sheet name="${esc(n)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
            zip.part("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                "</Relationships>")
            sheets.forEachIndexed { s, (_, rows) ->
                val data = StringBuilder()
                rows.forEachIndexed { r, row ->
                    data.append("""<row r="${r + 1}">""")
                    row.forEachIndexed { c, v ->
                        if (v.isEmpty()) return@forEachIndexed
                        val ref = column(c) + (r + 1)
                        val num = Tables.number(v)
                        if (num != null) data.append("""<c r="$ref"><v>$num</v></c>""")
                        else data.append("""<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${esc(v)}</t></is></c>""")
                    }
                    data.append("</row>")
                }
                zip.part("xl/worksheets/sheet${s + 1}.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$data</sheetData></worksheet>""")
            }
        }
    }
}
