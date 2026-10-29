package de.ultimatepdfscanner.core

import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.Test

/**
 * Diagnostic run (no assertions): `gradlew :scancore:test --tests *DebugRunTest -Ddebugdir=<folder>`
 * writes detection overlays, fold, orientation decisions and final pages to build/debugrun.
 */
class DebugRunTest {
    init { TestSupport.init() }

    @Test
    fun run() {
        val dir = System.getProperty("debugdir")?.let(::File) ?: return
        val out = File("build/debugrun").apply { deleteRecursively(); mkdirs() }
        val ocr = TesseractCli("deu")
        for (photo in dir.listFiles { f -> f.extension.lowercase() == "jpg" }!!.sortedBy { it.name }) {
            val image = TestSupport.load(photo)
            val det = Detect.detectDocument(image)
            val overlay = image.clone()
            det.quad?.let { q -> Imgproc.polylines(overlay, listOf(MatOfPoint(*q.map { Point(it.x, it.y) }.toTypedArray())), true, Scalar(0.0, 220.0, 0.0), 12) }
            det.mask?.let { m ->
                val contours = ArrayList<MatOfPoint>()
                Imgproc.findContours(m.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
                Imgproc.drawContours(overlay, contours, -1, Scalar(255.0, 0.0, 255.0), 6)
            }
            TestSupport.write(File(out, "${photo.nameWithoutExtension}_detect.jpg"), overlay)
            println("${photo.name}: ${image.cols()}x${image.rows()} quad=${det.quad?.joinToString { "(%.0f,%.0f)".format(it.x, it.y) }} mask=${det.mask != null}")
            val pages = Pipeline.processPhoto(image, PipelineConfig(mode = Mode.AUTO), object : OcrEngine by ocr {
                override fun orientation(gray: Mat, hint: Int?): Int {
                    val axis = Orientation.textLineAxis(gray)
                    return ocr.orientation(gray).also { println("   orientation: axis=$axis -> rotate $it (page ${gray.cols()}x${gray.rows()})") }
                }
            }) { println("   $it") }
            pages.forEachIndexed { i, p ->
                println("   page ${i + 1}: ${p.image.cols()}x${p.image.rows()} ch=${p.image.channels()} words=${p.words.size} notes=${p.notes}")
                TestSupport.write(File(out, "${photo.nameWithoutExtension}_p${i + 1}.jpg"), p.image)
            }
        }
    }
}
