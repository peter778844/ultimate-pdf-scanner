package de.ultimatepdfscanner.core

import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.Test

/**
 * A/B of the colour-seeded page segmentation (Detect.COLOR_SEED) on the user's real photos.
 * Writes an overlay (red = without seed, green = with seed) for every photo whose corners move
 * by more than 1.5 % of the image diagonal; judged by eye. Run: -Deval=colorseed.
 */
class ColorSeedEval {
    init { TestSupport.init() }

    private val roots = listOf("series", "series2", "phone", "phone_spread", "phone_table", "phone_all").map { File("../../testdata/$it") } +
        File("C:/Users/Peter/Desktop/testfotos")

    @Test
    fun compare() {
        if (System.getProperty("eval") != "colorseed") return
        val out = File("build/eval/colorseed").apply { deleteRecursively(); mkdirs() }
        val photos = roots.flatMap { r -> r.walkTopDown().filter { it.isFile && it.extension.lowercase() == "jpg" && "pages" !in it.path }.toList() }.distinct()
            .filter { f -> System.getProperty("evalOnly")?.let { it in f.path } ?: true }
        var changed = 0
        var tOld = 0L
        var tNew = 0L
        for (f in photos) {
            val img = TestSupport.load(f)
            Detect.COLOR_SEED = false
            var t = System.currentTimeMillis()
            val old = Detect.detectDocument(img, withMask = false).quad
            tOld += System.currentTimeMillis() - t
            Detect.COLOR_SEED = true
            t = System.currentTimeMillis()
            val new = Detect.detectDocument(img, withMask = false).quad
            tNew += System.currentTimeMillis() - t
            val diag = kotlin.math.hypot(img.cols().toDouble(), img.rows().toDouble())
            val move = if (old == null || new == null) (if (old == null && new == null) 0.0 else 1.0)
                else (0 until 4).maxOf { kotlin.math.hypot(old[it].x - new[it].x, old[it].y - new[it].y) } / diag
            if (move > 0.015) {
                changed++
                old?.let { Imgproc.polylines(img, listOf(MatOfPoint(*it.map { p -> Point(p.x, p.y) }.toTypedArray())), true, Scalar(0.0, 0.0, 255.0), 12) }
                new?.let { Imgproc.polylines(img, listOf(MatOfPoint(*it.map { p -> Point(p.x, p.y) }.toTypedArray())), true, Scalar(0.0, 200.0, 0.0), 8) }
                val small = org.opencv.core.Mat()
                Imgproc.resize(img, small, org.opencv.core.Size(), 0.25, 0.25, Imgproc.INTER_AREA)
                Imgcodecs.imwrite(File(out, "%.3f_%s".format(move, f.name)).path, small)
                println("changed %.3f %s".format(move, f.path))
            }
            img.release()
        }
        println("colour seed: ${photos.size} photos, $changed changed; time old ${tOld} ms, new ${tNew} ms")
        Detect.COLOR_SEED = true
    }
}
