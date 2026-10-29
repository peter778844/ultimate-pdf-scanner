package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import java.io.File
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively

/** A photo with nothing on it (lens covered, dark room): the app hung for minutes in "Seite erkennen" (GrabCut). */
class BlackPhotoTest {
    init { TestSupport.init() }

    private fun blackPhoto(): Mat {
        val m = Mat(4000, 3000, CvType.CV_8UC3, Scalar(6.0, 6.0, 8.0))
        val noise = Mat(m.size(), m.type())
        Core.setRNGSeed(1)
        Core.randn(noise, 0.0, 3.0)
        Core.add(m, noise, m)
        return m
    }

    @Test
    fun blackPhotoFinishesQuickly() {
        val modelFile = File("../../models/docaligner/fastvit_t8_h_e_bifpn_256_fp32.onnx")
        val model = if (modelFile.exists()) CornerModel(modelFile.readBytes()) else null
        val photo = blackPhoto()
        assertTimeoutPreemptively(Duration.ofSeconds(60)) {
            val t0 = System.currentTimeMillis()
            val pages = Pipeline.processPhoto(photo, PipelineConfig(ocr = false), null, 0.54, corners = model) { println("  $it ${System.currentTimeMillis() - t0} ms") }
            println("black photo: ${pages.size} page(s) in ${System.currentTimeMillis() - t0} ms")
            // the photo as it is (the enhancement made a grey speckled page of the sensor noise), marked for checking
            assertEquals(1, pages.size)
            assertTrue(histPercentile(histogram(Enhance.toGray(pages[0].image)), 50.0) < 30)
            assertTrue(pages[0].issues.isNotEmpty())
            pages.forEach { it.close() }
        }
        model?.close()
    }
}
