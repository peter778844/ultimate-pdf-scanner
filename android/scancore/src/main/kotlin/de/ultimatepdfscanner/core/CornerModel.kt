package de.ultimatepdfscanner.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer

/**
 * Learned page-corner detector (DocAligner heatmap models, DocsaidLab, Apache-2.0 incl. the
 * weights): the photo is squeezed to 256 x 256, the network returns one heatmap per corner
 * (tl, tr, br, bl), and each corner is the centroid of the largest blob above [THRESHOLD].
 * ONNX Runtime sessions may be used from several threads, so one instance can be shared.
 */
class CornerModel(modelBytes: ByteArray, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelBytes, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
    })
    private val inputName = session.inputNames.first()

    /** Corners in image pixels plus a confidence per corner (peak heat 0..1); null if a corner is missing. */
    class Result(val quad: Quad, val confidence: DoubleArray) {
        val minConfidence get() = confidence.min()
    }

    fun detect(image: Mat): Result? = matScope {
        val size = INPUT
        val small = mat()
        Imgproc.resize(image, small, Size(size.toDouble(), size.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        val bgr = if (small.channels() == 3) small else mat().also { Imgproc.cvtColor(small, it, Imgproc.COLOR_GRAY2BGR) }
        // HWC uint8 (BGR, as DocAligner is fed) -> CHW float 0..1
        val f = mat().also { bgr.convertTo(it, CvType.CV_32FC3, 1.0 / 255) }
        val planes = ArrayList<Mat>()
        Core.split(f, planes)
        planes.forEach { track(it) }
        val data = FloatArray(3 * size * size)
        val plane = FloatArray(size * size)
        planes.forEachIndexed { c, p -> p.get(0, 0, plane); System.arraycopy(plane, 0, data, c * size * size, plane.size) }
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, size.toLong(), size.toLong()))
        input.use {
            session.run(mapOf(inputName to input)).use { out ->
                val heat = out.get("heatmap").orElse(out.get(0)).value
                @Suppress("UNCHECKED_CAST")
                val maps = (heat as Array<Array<Array<FloatArray>>>)[0] // [4][H][W]
                val corners = ArrayList<Point>()
                val conf = DoubleArray(4)
                for (k in 0 until 4) {
                    val m = maps[k]
                    val h = m.size
                    val w = m[0].size
                    val hm = track(Mat(h, w, CvType.CV_32F))
                    for (y in 0 until h) hm.put(y, 0, m[y])
                    conf[k] = Core.minMaxLoc(hm).maxVal
                    val bin = mat()
                    Imgproc.threshold(hm, bin, THRESHOLD, 255.0, Imgproc.THRESH_BINARY)
                    val bin8 = mat().also { bin.convertTo(it, CvType.CV_8U) }
                    val contours = ArrayList<MatOfPoint>()
                    Imgproc.findContours(bin8, contours, mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
                    contours.forEach { track(it) }
                    val best = contours.maxByOrNull { Imgproc.contourArea(it) } ?: return@matScope null
                    val mom = Imgproc.moments(best)
                    val (cx, cy) = if (mom.m00 > 0) mom.m10 / mom.m00 to mom.m01 / mom.m00 else best.toArray()[0].let { it.x to it.y }
                    // heatmap pixel centres -> original image pixels
                    corners += Point((cx + 0.5) * image.cols() / w, (cy + 0.5) * image.rows() / h)
                }
                Result(corners.toTypedArray(), conf)
            }
        }
    }

    override fun close() { session.close() }

    companion object {
        const val INPUT = 256
        const val THRESHOLD = 0.3
    }
}
