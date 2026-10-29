package de.ultimatepdfscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LeadersTest {
    init { TestSupport.init() }

    /** A table of contents: "3. Kondensatoren . . . . . . . . 127" per line. */
    private fun tocPage(): Mat {
        val w = 1240; val h = 1000
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.color = Color(245, 245, 245); g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font.createFont(Font.TRUETYPE_FONT, File("C:/Windows/Fonts/DejaVuSans.ttf")).deriveFont(30f)
        g.color = Color(20, 20, 20)
        val titles = listOf("Kondensatoren", "Widerstände", "Spulen und Drosseln", "Transformatoren", "Antennen", "Leitungen", "Filter")
        titles.forEachIndexed { i, t ->
            val y = 120 + i * 110
            val entry = "${i + 1}. $t "
            g.drawString(entry, 80, y)
            var x = 80 + g.fontMetrics.stringWidth(entry)
            while (x < w - 200) { g.drawString(".", x, y); x += 16 }
            g.drawString("${100 + 13 * i}", w - 160, y)
        }
        g.dispose()
        val gray = Mat(h, w, CvType.CV_8U)
        gray.put(0, 0, (img.raster.dataBuffer as java.awt.image.DataBufferByte).data)
        return gray
    }

    @Test
    fun leaderRowsAreFoundAndRemoved() {
        val page = tocPage()
        val letters = TextSize.charHeight(page)!!
        val r = Leaders.remove(page, letters)
        assertEquals(7, r.rows)
        val ocr = TesseractCli("deu")
        if (!ocr.available) return
        val text = pageText(TextSize.recognize(ocr, page))
        // each entry with its page number on one line, no dots read as junk
        assertTrue("1. Kondensatoren 100" in text, text)
        assertTrue("7. Filter 178" in text, text)
    }

    @Test
    fun normalTextHasNoLeaders() {
        val page = Enhance.toGray(TestSupport.makePage(fill = true))
        assertEquals(0, Leaders.remove(page, TextSize.charHeight(page)!!).rows)
    }
}
