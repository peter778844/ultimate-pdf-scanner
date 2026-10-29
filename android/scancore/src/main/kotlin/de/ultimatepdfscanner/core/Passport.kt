package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.roundToInt

/**
 * Biometric passport photo (35 x 45 mm, German/ICAO layout): head (chin to crown) ~34 mm, a
 * little space above the head, face centred. The face box comes from a face detector, which
 * covers roughly forehead to chin.
 */
object Passport {
    const val WIDTH_MM = 35.0
    const val HEIGHT_MM = 45.0
    const val HEAD_SHARE = 34.0 / 45.0 // chin to crown
    const val TOP_SHARE = 0.09 // space above the crown
    const val HEAD_PER_FACE_BOX = 1.35 // chin-to-crown height / detector face box height
    const val OUT_DPI = 600

    private fun px(mm: Double, dpi: Int) = (mm / 25.4 * dpi).roundToInt()

    /** Crop rectangle (may reach outside the image) for a face box. */
    fun cropRect(faceX: Double, faceY: Double, faceW: Double, faceH: Double): Rect {
        val chin = faceY + faceH
        val head = HEAD_PER_FACE_BOX * faceH
        val h = head / HEAD_SHARE
        val w = h * WIDTH_MM / HEIGHT_MM
        val top = chin - head - TOP_SHARE * h
        val left = faceX + faceW / 2 - w / 2
        return Rect(left.roundToInt(), top.roundToInt(), w.roundToInt(), h.roundToInt())
    }

    /** The finished photo at [OUT_DPI]; areas beyond the photo are filled with its border colours. */
    fun crop(image: Mat, faceX: Double, faceY: Double, faceW: Double, faceH: Double): Mat = matScope {
        val r = cropRect(faceX, faceY, faceW, faceH)
        val pad = maxOf(0, -r.x, -r.y, r.x + r.width - image.cols(), r.y + r.height - image.rows())
        val src = if (pad > 0) mat().also { Core.copyMakeBorder(image, it, pad, pad, pad, pad, Core.BORDER_REPLICATE) } else image
        val roi = Mat(src, Rect(r.x + pad, r.y + pad, r.width, r.height))
        val out = Mat()
        Imgproc.resize(roi, out, Size(px(WIDTH_MM, OUT_DPI).toDouble(), px(HEIGHT_MM, OUT_DPI).toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        roi.release()
        out
    }

    /** 10 x 15 cm print (300 dpi, portrait) with 2 x 3 photos and light cut marks. */
    fun printSheet(photo: Mat, dpi: Int = 300): Mat {
        val sheet = Mat(px(150.0, dpi), px(100.0, dpi), CvType.CV_8UC3, Scalar(255.0, 255.0, 255.0))
        val w = px(WIDTH_MM, dpi)
        val h = px(HEIGHT_MM, dpi)
        val gapX = (sheet.cols() - 2 * w) / 3
        val gapY = (sheet.rows() - 3 * h) / 4
        val small = Mat()
        Imgproc.resize(photo, small, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val mark = Scalar(190.0, 190.0, 190.0)
        for (row in 0 until 3) for (col in 0 until 2) {
            val x = gapX + col * (w + gapX)
            val y = gapY + row * (h + gapY)
            small.copyTo(Mat(sheet, Rect(x, y, w, h)))
            Imgproc.rectangle(sheet, Point(x - 1.0, y - 1.0), Point((x + w).toDouble(), (y + h).toDouble()), mark, 1)
        }
        small.release()
        return sheet
    }
}
