package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.Mat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Light print on a dark ground (the black Meinke/Gundlach cover) and the learned corner fallback. */
class DarkPageTest {
    init { TestSupport.init() }

    @Test
    fun darkPageIsRecognised() {
        val page = TestSupport.makePage()
        val negative = Mat().also { Core.bitwise_not(page, it) }
        assertFalse(Enhance.isDarkPage(page))
        assertTrue(Enhance.isDarkPage(negative))
    }

    @Test
    fun blackCoverIsCutReadAndUpright() {
        val photo = File("../../testdata/series").walkTopDown().firstOrNull { it.name == "Pmvkv50m51346.jpg" } ?: return
        val modelFile = File("../../models/docaligner/fastvit_t8_h_e_bifpn_256_fp32.onnx")
        val model = if (modelFile.exists()) CornerModel(modelFile.readBytes()) else null
        val pages = Pipeline.processPhoto(TestSupport.load(photo), PipelineConfig(), TesseractCli("deu"), 0.54, corners = model)
        assertEquals(1, pages.size)
        val text = pages[0].text
        println("cover: rotation=${pages[0].rotation} words=${pages[0].words.size} text=${text.replace('\n', ' ').take(120)}")
        assertEquals(0, pages[0].rotation)
        // the cover stays a dark cover (flattening used to turn it into white noise)
        assertTrue(histPercentile(histogram(Enhance.toGray(pages[0].image)), 50.0) < Enhance.DARK_PAGE_MAX_MEDIAN)
        // most of the title is read (texture removed, letters at the OCR's size); before: noise only
        val title = listOf("Meinke", "Gundlach", "Taschenbuch", "Hochfrequenz", "technik", "Auflage")
        assertTrue(title.count { it in text } >= 3, text)
        pages.forEach { it.close() }; model?.close()
    }
}
