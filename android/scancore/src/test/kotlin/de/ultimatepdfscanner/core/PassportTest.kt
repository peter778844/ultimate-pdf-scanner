package de.ultimatepdfscanner.core

import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PassportTest {
    init { TestSupport.init() }

    @Test
    fun headFillsAboutThreeQuartersAndFaceIsCentred() {
        val r = Passport.cropRect(400.0, 300.0, 200.0, 240.0)
        assertEquals(35.0 / 45.0, r.width.toDouble() / r.height, 0.01)
        val head = 1.35 * 240
        assertEquals(34.0 / 45.0, head / r.height, 0.01)
        assertEquals(500.0, r.x + r.width / 2.0, 1.0) // face centre
        assertTrue(r.y < 300 - 0.35 * 240, "space above the head")
    }

    @Test
    fun cropPadsAtImageBordersAndSheetHasSixPhotos() {
        val img = Mat(800, 600, CvType.CV_8UC3, Scalar(200.0, 180.0, 160.0))
        val photo = Passport.crop(img, 200.0, 20.0, 200.0, 240.0) // head close to the top: needs padding
        assertEquals(827, photo.cols())
        assertEquals(1063, photo.rows())
        val sheet = Passport.printSheet(photo)
        assertEquals(1181, sheet.cols())
        assertEquals(1772, sheet.rows())
    }
}
