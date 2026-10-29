package de.ultimatepdfscanner.core

import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TextSizeTest {
    init { TestSupport.init() }

    @Test
    fun smallPrintIsEnlargedAndBoxesMapBack() {
        val page = Enhance.toGray(TestSupport.makePage(fill = true))
        val small = Mat().also { Imgproc.resize(page, it, Size(page.cols() * 0.4, page.rows() * 0.4), 0.0, 0.0, Imgproc.INTER_AREA) }
        val h = assertNotNull(TextSize.charHeight(small))
        assertTrue(h < TextSize.TARGET, "letter height $h")
        assertTrue(TextSize.scaleFor(small) > 1.2)
        val ocr = TesseractCli("eng")
        if (!ocr.available) return
        val words = TextSize.recognize(ocr, small)
        assertTrue(words.size > 50, "words ${words.size}")
        // boxes in the coordinates of the page that was passed in
        assertTrue(words.all { it.x1 <= small.cols() + 2 && it.y1 <= small.rows() + 2 }, "boxes outside the page")
        val quick = words.firstOrNull { it.text == "quick" }
        assertNotNull(quick)
    }

    @Test
    fun pageAtTheTargetSizeIsLeftAlone() {
        val page = Enhance.toGray(TestSupport.makePage(fill = true))
        val h = assertNotNull(TextSize.charHeight(page))
        val atTarget = Mat().also { val f = TextSize.TARGET / h; Imgproc.resize(page, it, Size(page.cols() * f, page.rows() * f)) }
        assertEquals(1.0, TextSize.scaleFor(atTarget))
    }
}
