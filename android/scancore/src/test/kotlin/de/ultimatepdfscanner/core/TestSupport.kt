package de.ultimatepdfscanner.core

import nu.pattern.OpenCV
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

object TestSupport {
    init { OpenCV.loadLocally() }

    fun init() = Unit

    val WORDS = ("The quick brown fox jumps over the lazy dog while seven wizards " +
        "quickly judge boxing matches near the river bank").split(" ")
    private val FONT_FILE = File("C:/Windows/Fonts/DejaVuSans.ttf")

    /** Same page as the Python prototype's make_page: 1240x1754, text block, shadow towards the right. */
    fun makePage(w: Int = 1240, h: Int = 1754, fill: Boolean = false): Mat {
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.color = Color(245, 245, 245)
        g.fillRect(0, 0, w, h)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font.createFont(Font.TRUETYPE_FONT, FONT_FILE).deriveFont(46f)
        g.color = Color(20, 20, 20)
        val fm = g.fontMetrics
        var y = 140 + fm.ascent
        var line = ArrayList<String>()
        val words = if (fill) List(12) { WORDS }.flatten() else WORDS + WORDS
        for (word in words) {
            if (fm.stringWidth((line + word).joinToString(" ")) > w - 240) {
                g.drawString(line.joinToString(" "), 120, y)
                y += 80
                line = ArrayList()
                if (y > h - 120) break
            }
            line += word
        }
        if (y <= h - 120) g.drawString(line.joinToString(" "), 120, y)
        g.dispose()
        val gray = Mat(h, w, CvType.CV_8U)
        gray.put(0, 0, (img.raster.dataBuffer as java.awt.image.DataBufferByte).data)
        // shadow: darker towards the right edge
        val shaded = Mat()
        gray.convertTo(shaded, CvType.CV_32F)
        val shadow = Mat(1, w, CvType.CV_32F)
        shadow.put(0, 0, FloatArray(w) { 1f - 0.45f * it / (w - 1) })
        val shadowFull = Mat()
        Core.repeat(shadow, h, 1, shadowFull)
        Core.multiply(shaded, shadowFull, shaded)
        val out8 = Mat()
        shaded.convertTo(out8, CvType.CV_8U)
        val bgr = Mat()
        Imgproc.cvtColor(out8, bgr, Imgproc.COLOR_GRAY2BGR)
        listOf(gray, shaded, shadow, shadowFull, out8).forEach { it.release() }
        return bgr
    }

    /** Corners of a w x h page seen by a tilted pinhole camera (principal point at image centre). */
    fun projectPage(w: Int, h: Int, sizeW: Int = 2000, sizeH: Int = 2400, f: Double = 2200.0): Quad {
        val rx = Math.toRadians(18.0); val ry = Math.toRadians(-14.0); val rz = Math.toRadians(4.0)
        val rvec = Mat(3, 1, CvType.CV_64F).also { it.put(0, 0, rx, ry, rz) }
        val rot = Mat()
        org.opencv.calib3d.Calib3d.Rodrigues(rvec, rot)
        val r = DoubleArray(9).also { rot.get(0, 0, it) }
        val corners = listOf(doubleArrayOf(-w / 2.0, -h / 2.0), doubleArrayOf(w / 2.0, -h / 2.0), doubleArrayOf(w / 2.0, h / 2.0), doubleArrayOf(-w / 2.0, h / 2.0))
        return corners.map { (x, y) ->
            val cx = r[0] * x + r[1] * y
            val cy = r[3] * x + r[4] * y
            val cz = r[6] * x + r[7] * y + 2600.0
            Point(cx / cz * f + sizeW / 2.0, cy / cz * f + sizeH / 2.0)
        }.toTypedArray()
    }

    /** The page photographed at an angle on a dark, noisy background. Returns (photo, true corners). */
    fun makePhoto(page: Mat): Pair<Mat, Quad> {
        val bg = Mat(2400, 2000, CvType.CV_8UC3)
        Core.randu(bg, 40.0, 80.0)
        Imgproc.GaussianBlur(bg, bg, Size(9.0, 9.0), 0.0)
        val w = page.cols().toDouble(); val h = page.rows().toDouble()
        val quad = projectPage(page.cols(), page.rows())
        val m = Imgproc.getPerspectiveTransform(MatOfPoint2f(Point(0.0, 0.0), Point(w, 0.0), Point(w, h), Point(0.0, h)), MatOfPoint2f(*quad))
        val warped = Mat()
        Imgproc.warpPerspective(page, warped, m, Size(2000.0, 2400.0))
        val mask = Mat()
        Imgproc.warpPerspective(Mat(page.size(), CvType.CV_8U, Scalar(255.0)), mask, m, Size(2000.0, 2400.0))
        warped.copyTo(bg, mask)
        return bg to quad
    }

    fun load(path: File): Mat = Imgcodecs.imdecode(MatOfByte(*path.readBytes()), Imgcodecs.IMREAD_COLOR)

    fun write(path: File, m: Mat) { path.parentFile.mkdirs(); Imgcodecs.imwrite(path.path, m) }

    fun rotate(m: Mat, cw: Int) = Orientation.rotate(m, cw)
}

/** OCR engine for PC tests: the Tesseract command line, with the prototype's tessdata_best models. */
class TesseractCli(private val lang: String = "eng", private val timeoutSeconds: Long = 120) : OcrEngine {
    private val exe = "C:/Program Files/Tesseract-OCR/tesseract.exe"
    private val tessdata = File("../../prototype/tessdata")

    val available get() = File(exe).exists()

    private fun tsv(image: Mat, psm: Int = 3): List<Word> {
        val png = MatOfByte()
        Imgcodecs.imencode(".png", image, png)
        val cmd = mutableListOf(exe, "stdin", "stdout")
        if (tessdata.isDirectory) cmd += listOf("--tessdata-dir", tessdata.path)
        cmd += listOf("-l", lang, "--psm", "$psm", "--dpi", "300", "-c", "tessedit_create_tsv=1")
        // one thread per process: tests and the series evaluation run many Tesseracts side by side
        val p = ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).also { it.environment()["OMP_THREAD_LIMIT"] = "1" }.start()
        p.outputStream.use { it.write(png.toArray()) }
        // like the app's engine: a page that takes too long is abandoned (no text) instead of blocking
        val reader = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.bufferedReader(Charsets.UTF_8).readText() }
        if (!p.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly()
            println("OCR TIMEOUT after $timeoutSeconds s")
            return emptyList()
        }
        val out = reader.get()
        return out.lines().drop(1).mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 12 || f[0] != "5" || f[11].isBlank()) return@mapNotNull null
            val conf = f[10].toFloat()
            if (conf < 0) return@mapNotNull null
            val x = f[6].toInt(); val y = f[7].toInt()
            Word(f[11].trim(), conf, x, y, x + f[8].toInt(), y + f[9].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt())
        }
    }

    override fun recognize(gray: Mat) = tsv(gray)
    override fun recognize(gray: Mat, singleBlock: Boolean) = tsv(gray, if (singleBlock) 6 else 3)

    /** Same strategy as the Android engine (no OSD there): line axis + OCR confidence of the two candidates. */
    override fun orientation(gray: Mat, hint: Int?): Int = Orientation.detect(gray, hint) { candidate ->
        val words = tsv(candidate)
        if (words.isEmpty()) 0.0 else words.sumOf { it.conf.toDouble() * it.text.length } / words.sumOf { it.text.length }
    }
}
