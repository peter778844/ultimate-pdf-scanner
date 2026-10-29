package de.ultimatepdfscanner.core

import nu.pattern.OpenCV
import org.opencv.core.Core
import kotlin.test.Test
import kotlin.test.assertTrue

class OpenCvSmokeTest {
    @Test
    fun loadsNativeOpenCv() {
        OpenCV.loadLocally()
        assertTrue(Core.VERSION.startsWith("4."), Core.VERSION)
    }
}
