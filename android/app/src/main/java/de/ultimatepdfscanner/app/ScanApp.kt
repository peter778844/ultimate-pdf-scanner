package de.ultimatepdfscanner.app

import android.app.Application
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.opencv.android.OpenCVLoader

class ScanApp : Application() {
    override fun onCreate() {
        super.onCreate()
        check(OpenCVLoader.initLocal()) { "OpenCV native library could not be loaded" }
        PDFBoxResourceLoader.init(applicationContext)
        I18n.init(this)
    }
}
