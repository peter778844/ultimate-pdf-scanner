package de.ultimatepdfscanner.core

import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Book mode: shoot automatically after each page turn. Motion between preview frames is the
 * mean absolute difference of tiny blurred greyscale thumbnails (0-255). After a shot it waits
 * for clear motion (the page is being turned), then for the picture to be steady again.
 */
class PageTurn(
    private val motionHigh: Double = 9.0,
    private val motionLow: Double = 2.5,
    private val steadyMs: Long = 700,
) {
    enum class Phase { READY, TURN_PAGE, HOLD_STILL }

    var phase = Phase.READY
        private set
    private var steadySince = -1L
    private var previous: Mat? = null

    /** Motion of this frame against the previous one (frame: grey or BGR, any size). */
    fun motion(frame: Mat): Double = matScope {
        val gray = if (frame.channels() == 1) frame else mat().also { Imgproc.cvtColor(frame, it, Imgproc.COLOR_BGR2GRAY) }
        val small = Mat()
        Imgproc.resize(gray, small, Size(64.0, 48.0), 0.0, 0.0, Imgproc.INTER_AREA)
        Imgproc.GaussianBlur(small, small, Size(5.0, 5.0), 0.0)
        val prev = previous
        previous = small
        if (prev == null) return@matScope 0.0
        val diff = mat()
        Core.absdiff(small, prev, diff)
        prev.release()
        Core.mean(diff).`val`[0]
    }

    /** Feed one frame's [motion]; returns true when the camera should shoot now. */
    fun update(motion: Double, pageVisible: Boolean, now: Long): Boolean {
        when (phase) {
            Phase.TURN_PAGE -> if (motion >= motionHigh) { phase = Phase.HOLD_STILL; steadySince = -1L }
            Phase.READY, Phase.HOLD_STILL -> {
                if (motion > motionLow || !pageVisible) { steadySince = -1L; return false }
                if (steadySince < 0) steadySince = now
                if (now - steadySince >= steadyMs) return true
            }
        }
        return false
    }

    /** 0..1: how far the countdown to the next automatic shot has run (0 while waiting for a page turn). */
    fun progress(now: Long): Float =
        if (phase == Phase.TURN_PAGE || steadySince < 0) 0f else ((now - steadySince).toFloat() / steadyMs).coerceIn(0f, 1f)

    /** Call after every shot (also manual ones): the next automatic shot needs a page turn first. */
    fun shot() { phase = Phase.TURN_PAGE; steadySince = -1L }

    fun reset() { phase = Phase.READY; steadySince = -1L; previous?.release(); previous = null }
}
