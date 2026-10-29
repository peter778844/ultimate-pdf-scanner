package de.ultimatepdfscanner.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.nio.FloatBuffer
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Handwritten German text lines (DE·HTR v2, naeyn, Apache-2.0, int8, CTC over 111 characters).
 * Reads one line crop at a time; ONNX Runtime sessions may be used from several threads.
 */
class HandwritingReader(model: ByteArray, private val alphabet: String, threads: Int = 2, leanMemory: Boolean = false) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        // phones with little memory: no arena (it keeps ~650 MB per page of different line widths, measured; ~20 % slower)
        if (leanMemory) { setMemoryPatternOptimization(false); setCPUArenaAllocator(false) }
    }
    private val session: OrtSession = env.createSession(model, options)

    /** Text, mean probability of its characters, time step of every character, time steps covering the line. */
    class Reading(val text: String, val score: Double, val cols: IntArray, val stepsPerCrop: Double)

    fun read(line: Mat): Reading = matScope {
        val gray = if (line.channels() == 1) line else mat().also { Imgproc.cvtColor(line, it, Imgproc.COLOR_BGR2GRAY) }
        val w = min(MAX_WIDTH, max(4, (gray.cols() * HEIGHT.toDouble() / max(1, gray.rows())).roundToInt()))
        val r = mat()
        Imgproc.resize(gray, r, Size(w.toDouble(), HEIGHT.toDouble()), 0.0, 0.0, if (gray.rows() > HEIGHT) Imgproc.INTER_AREA else Imgproc.INTER_CUBIC)
        val padded = (w + 3) / 4 * 4
        val px = bytes(r)
        val data = FloatArray(HEIGHT * padded) { 1f } // white padding on the right
        for (y in 0 until HEIGHT) for (x in 0 until w) data[y * padded + x] = (px[y * w + x].toInt() and 0xFF) / 255f
        OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 1, HEIGHT.toLong(), padded.toLong())).use { t ->
            session.run(mapOf("image" to t)).use { out ->
                @Suppress("UNCHECKED_CAST")
                val steps = (out.get(0).value as Array<Array<FloatArray>>)[0] // [T][C], raw logits
                val sb = StringBuilder(); val cols = ArrayList<Int>(); var sum = 0.0
                var prev = -1
                for (t in steps.indices) {
                    val l = steps[t]
                    var best = 0
                    for (k in 1 until l.size) if (l[k] > l[best]) best = k
                    if (best != 0 && best != prev && best - 1 < alphabet.length) {
                        var z = 0.0
                        for (v in l) z += exp((v - l[best]).toDouble())
                        sb.append(alphabet[best - 1]); cols += t; sum += 1 / z // softmax probability of the chosen character
                    }
                    prev = best
                }
                Reading(sb.toString(),if (cols.isEmpty()) 0.0 else sum / cols.size, cols.toIntArray(), steps.size.toDouble() * w / padded)
            }
        }
    }

    override fun close() { session.close(); options.close() }

    companion object {
        private const val HEIGHT = 64
        private const val MAX_WIDTH = 1024
    }
}
