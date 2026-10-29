package de.ultimatepdfscanner.core

import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.Test

/**
 * Evaluation of the real photo series in testdata/series (not a regular test: runs only with
 * -Deval=true). For every photo: detected outline drawn on the photo, the finished pages, and a
 * CSV line with page count, issues, timing and OCR confidence. Output in build/eval.
 */
class SeriesEval {
    init { TestSupport.init() }

    private val root = File(System.getProperty("evalRoot") ?: "../../testdata/series")
    private val out = File(System.getProperty("evalOut") ?: "build/eval")

    /** Fold strength (relative darkening at the gutter) of every photo, to tune the book-mode split. */
    @Test
    fun foldDips() {
        if (System.getProperty("eval") != "folds") return
        val sb = StringBuilder("photo;dip;axis;pos;forcedDip;paper\n")
        for (series in root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            for (f in File(series, "originals").listFiles { x -> x.extension == "jpg" }.orEmpty().sortedBy { it.name }) {
                val img = TestSupport.load(f)
                val det = Detect.detectDocument(img)
                val quad = det.quad ?: run { sb.append("${f.name};;;;\n"); return@run null } ?: continue
                val mask = det.mask ?: continue
                val (spread, spreadMask) = Warp.warpQuadWithMask(img, mask, quad, Pipeline.SPREAD_MARGIN, 0.54)
                val gray = Enhance.toGray(spread)
                val fold = Spread.findFold(gray, spreadMask)
                val forced = Spread.forcedFold(gray, spreadMask)
                val paper = histPercentile(histogram(gray, spreadMask), 90.0)
                sb.append("${series.name.take(13)}/${f.name};%.3f;${fold.axis};${fold.pos};%.3f;%.0f\n".format(fold.dip, forced.dip, paper))
                println("FOLD ${f.name} dip=%.3f forced=%.3f".format(fold.dip, forced.dip))
                listOf(img, spread, spreadMask, gray).forEach { it.release() }; det.close()
            }
        }
        File(out, "folds.csv").apply { parentFile.mkdirs() }.writeText(sb.toString())
    }

    /** Segmentation only (green) vs. DocAligner fastvit_t8 alone (blue) vs. the combination with edge snap (red). */
    @Test
    fun corners() {
        if (System.getProperty("eval") != "corners") return
        val model = CornerModel(File("../../models/docaligner/fastvit_t8_h_e_bifpn_256_fp32.onnx").readBytes())
        val sb = StringBuilder("photo;msSegmentation;msModel;msCombined;confModel;modelUsed;quad\n")
        for (series in root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            for (f in File(series, "originals").listFiles { x -> x.extension == "jpg" }.orEmpty().sortedBy { it.name }) {
                val img = TestSupport.load(f)
                var t = System.currentTimeMillis()
                val det = Detect.detectDocument(img)
                val msSeg = System.currentTimeMillis() - t
                t = System.currentTimeMillis(); val raw = model.detect(img); val msModel = System.currentTimeMillis() - t
                t = System.currentTimeMillis(); val combined = Detect.detectDocument(img, model = model); val msCombined = System.currentTimeMillis() - t
                val used = combined.quad != null && det.quad?.contentEquals(combined.quad) != true
                // three panels side by side: segmentation | fastvit_t8 | combined
                val panels = listOf(det.quad to Scalar(0.0, 200.0, 0.0), raw?.quad?.let { Detect.orderQuad(it.toList()) } to Scalar(255.0, 80.0, 0.0),
                    combined.quad to Scalar(0.0, 0.0, 255.0)).map { (q, color) ->
                    val p = img.clone()
                    q?.let { Imgproc.polylines(p, listOf(MatOfPoint(*it.map { c -> Point(c.x, c.y) }.toTypedArray())), true, color, 18) }
                    Mat().also { s -> Imgproc.resize(p, s, Size(p.cols() * 0.2, p.rows() * 0.2), 0.0, 0.0, Imgproc.INTER_AREA); p.release() }
                }
                val shrunk = Mat().also { org.opencv.core.Core.hconcat(panels, it) }
                panels.forEach { it.release() }
                TestSupport.write(File(out, "corners/${series.name.take(13)}_${f.nameWithoutExtension}.jpg"), shrunk)
                sb.append("${f.name};$msSeg;$msModel;$msCombined;%.2f;$used;${combined.quad?.joinToString(" ") { "%.0f,%.0f".format(it.x, it.y) }}\n".format(raw?.minConfidence ?: 0.0))
                listOf(img, shrunk).forEach { it.release() }; det.close(); combined.close()
            }
        }
        File(out, "corners.csv").writeText(sb.toString())
        model.close()
    }

    /** Ascender/descender ratio of the finished (upright) pages and of the same pages upside down. */
    @Test
    fun ascenders() {
        if (System.getProperty("eval") != "ascenders") return
        var right = 0; var wrong = 0; var undecided = 0
        val sb = StringBuilder("page;upright;flipped\n")
        for (f in out.walkTopDown().filter { it.extension == "jpg" && it.parentFile.name == "pages" }.sortedBy { it.name }) {
            val gray = Enhance.toGray(TestSupport.load(f))
            if (Orientation.textLineAxis(gray) != 0) continue // only pages with horizontal lines
            val up = Orientation.ascenderRatio(gray)
            val flipped = Orientation.rotate(gray, 180)
            val down = Orientation.ascenderRatio(flipped)
            for ((r, upright) in listOf(up to true, down to false)) when {
                r >= Orientation.UPRIGHT_RATIO -> if (upright) right++ else wrong++
                r <= 1 / Orientation.UPRIGHT_RATIO -> if (!upright) right++ else wrong++
                else -> undecided++
            }
            sb.append("${f.name};%.2f;%.2f\n".format(up, down))
            gray.release(); flipped.release()
        }
        println("ASCENDERS right=$right wrong=$wrong undecided=$undecided")
        File(out, "ascenders.csv").writeText(sb.toString())
    }

    /** Intermediate stages of one photo (-DevalPhoto=<name>): split parts with their mask outline, dewarped and cleaned pages. */
    @Test
    fun stages() {
        if (System.getProperty("eval") != "stages") return
        val name = System.getProperty("evalPhoto") ?: return
        Outline.DEBUG = true
        val f = root.walkTopDown().first { it.name == name }
        val dir = File(out, "stages/${f.nameWithoutExtension}").apply { deleteRecursively(); mkdirs() }
        val img = TestSupport.load(f)
        val det = Detect.detectDocument(img)
        val quad = det.quad ?: return
        val mask = det.mask ?: return
        val (spread, spreadMask) = Warp.warpQuadWithMask(img, mask, quad, Pipeline.SPREAD_MARGIN, 0.54)
        val fold = Pipeline.foldFor(Enhance.toGray(spread), spreadMask, SplitMode.DOUBLE)
        println("STAGES fold=$fold spread=${spread.cols()}x${spread.rows()}")
        val parts = if (fold != null) Spread.splitSpread(spread, spreadMask, fold) else listOf(spread to spreadMask)
        parts.forEachIndexed { i, (part, pm) ->
            val vis = part.clone()
            val contours = ArrayList<MatOfPoint>()
            Imgproc.findContours(pm!!.clone(), contours, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
            Imgproc.drawContours(vis, contours, -1, Scalar(255.0, 0.0, 255.0), 2)
            val skin = Fingers.fingerMask(part)
            Outline.refine(part, pm)?.let { r ->
                val rc = ArrayList<MatOfPoint>()
                Imgproc.findContours(r, rc, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
                Imgproc.drawContours(vis, rc, -1, Scalar(255.0, 128.0, 0.0), 3)
            }
            Outline.sides(part, pm, skin, if (i == 0) Outline.Side.BOTTOM else Outline.Side.TOP)?.let { s ->
                val (tl, tr, br, bl) = s.corners
                fun poly(pts: List<Point>) = Imgproc.polylines(vis, listOf(MatOfPoint(*pts.toTypedArray())), false, Scalar(0.0, 200.0, 0.0), 3)
                poly((0..100).map { k -> val y = tl.y + (bl.y - tl.y) * k / 100; Point(s.left(y), y) })
                poly((0..100).map { k -> val y = tr.y + (br.y - tr.y) * k / 100; Point(s.right(y), y) })
                poly((0..100).map { k -> val x = tl.x + (tr.x - tl.x) * k / 100; Point(x, s.top(x)) })
                poly((0..100).map { k -> val x = bl.x + (br.x - bl.x) * k / 100; Point(x, s.bottom(x)) })
            } ?: println("STAGES part $i: no outline")
            Imgproc.findContours(skin.clone(), contours.also { it.clear() }, Mat(), Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_NONE)
            Imgproc.drawContours(vis, contours, -1, Scalar(0.0, 255.0, 255.0), 2)
            TestSupport.write(File(dir, "part$i.png"), vis)
            val dew = Dewarp.dewarpPage(part, pm, if (i == 0) Outline.Side.BOTTOM else Outline.Side.TOP)
            TestSupport.write(File(dir, "dewarp$i.png"), dew)
            val straight = Dewarp.straightenTextLines(dew)
            val clean = Borders.clean(straight, if (i == 0) Outline.Side.BOTTOM else Outline.Side.TOP).first
            TestSupport.write(File(dir, "clean$i.png"), clean)
        }
    }

    /** OCR gate on every OCR input of the last evaluation: which pages would be skipped as texture. */
    @Test
    fun gate() {
        if (System.getProperty("eval") != "gate") return
        for (f in out.walkTopDown().filter { it.extension == "png" && it.parentFile.name == "ocrin" }.sortedBy { it.name }) {
            val g = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_GRAYSCALE)
            val t0 = System.currentTimeMillis()
            val v = OcrGate.assess(g)
            println("GATE ${f.name} text=${v.text} ink=%.3f spread=%.2f period=%.2f ${System.currentTimeMillis() - t0}ms".format(v.inkShare, v.heightSpread, v.periodicity))
            g.release()
        }
    }

    @Test
    fun evaluate() {
        if (System.getProperty("eval") != "true") return
        val ocr: OcrEngine = if (System.getProperty("ocr") == "paddle") PaddleOcr(
            File("../../models/paddle/ch_PP-OCRv5_det_mobile.onnx").readBytes(),
            File("../../models/paddle/latin_PP-OCRv5_rec_mobile.onnx").readBytes(), threads = 1,
            preferredTextHeight = System.getProperty("paddleHeight")?.toDouble() ?: PaddleOcr.TEXT_HEIGHT,
            clsModel = File("../../models/paddle/ch_ppocr_mobile_v2.0_cls_mobile.onnx").takeIf { it.exists() && System.getProperty("paddleCls") != "off" }?.readBytes())
        else TesseractCli(System.getProperty("lang") ?: "deu+eng")
        val modelFile = File("../../models/docaligner/fastvit_t8_h_e_bifpn_256_fp32.onnx")
        val model = if (modelFile.exists()) CornerModel(modelFile.readBytes()) else null // as in the app
        // photos in parallel (the PC has many cores; Tesseract runs single-threaded per process, see TesseractCli)
        val threads = System.getProperty("evalThreads")?.toInt() ?: maxOf(1, Runtime.getRuntime().availableProcessors() * 5 / 8)
        val jobs = ArrayList<Pair<File, String>>()
        for (series in root.listFiles().orEmpty().filter { it.isDirectory }.sortedBy { it.name }) {
            val docJson = File(series, "doc.json").takeIf { it.exists() }?.readText() ?: continue
            for (f in File(series, "originals").listFiles { x -> x.extension == "jpg" }.orEmpty().sortedBy { it.name }) {
                if (System.getProperty("evalPhoto")?.let { f.name !in it.split(",") } == true) continue
                jobs += f to (System.getProperty("evalSplit")
                    ?: Regex(""""id":"${f.nameWithoutExtension}","split":"(\w+)"""").find(docJson)?.groupValues?.get(1) ?: "AUTO")
            }
        }
        val pool = java.util.concurrent.Executors.newFixedThreadPool(threads)
        val t00 = System.currentTimeMillis()
        val lines = jobs.map { (f, split) -> pool.submit<String> { evaluatePhoto(f, split, ocr, model) } }.map { it.get() }
        pool.shutdown()
        println("EVAL ${jobs.size} photos with $threads threads in ${(System.currentTimeMillis() - t00) / 1000} s")
        val csv = StringBuilder("series;photo;split;pages;ms;issues;words;meanConf;share90\n")
        lines.forEach { csv.append(it) }
        File(out, "summary.csv").apply { parentFile.mkdirs() }.writeText(csv.toString())
        File(out, "pages.csv").writeText("photo;page;width;height;words;realWords;realConf;rotation;issues;notes\n" + pageLines.sorted().joinToString(""))
    }

    private val pageLines = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** One photo of the series: overlay, pages, texts, OCR inputs; returns its CSV line. */
    private fun evaluatePhoto(f: File, split: String, ocr: OcrEngine, model: CornerModel?): String {
        val series = f.parentFile.parentFile
        val img = TestSupport.load(f)
        val det = Detect.detectDocument(img, model = model)
        val overlay = img.clone()
        det.quad?.let { q ->
            Imgproc.polylines(overlay, listOf(MatOfPoint(*q.map { Point(it.x, it.y) }.toTypedArray())), true, Scalar(0.0, 220.0, 0.0), 12)
        }
        val small = Mat().also { Imgproc.resize(overlay, it, Size(overlay.cols() * 0.25, overlay.rows() * 0.25), 0.0, 0.0, Imgproc.INTER_AREA) }
        TestSupport.write(File(out, "${series.name}/overlay/${f.nameWithoutExtension}.jpg"), small)
        val t0 = System.currentTimeMillis()
        val pages = Pipeline.processPhoto(img, PipelineConfig(split = SplitMode.valueOf(split), mode = Mode.COLOR), ocr, 0.54, corners = model)
        val ms = System.currentTimeMillis() - t0
        pages.forEachIndexed { i, p ->
            TestSupport.write(File(out, "${series.name}/pages/${f.nameWithoutExtension}_$i.jpg"), p.image)
            File(out, "${series.name}/text/${f.nameWithoutExtension}_$i.txt").apply { parentFile.mkdirs() }.writeText(p.text)
            // exactly what the OCR engine was given (lossless), for offline OCR experiments
            matScope { TestSupport.write(File(out, "${series.name}/ocrin/${f.nameWithoutExtension}_$i.png"), track(Enhance.toGray(track(Enhance.readable(p.rectified))))) }
        }
        pages.forEachIndexed { i, p ->
            val real = p.words.filter(Quality::isRealWord)
            val conf = "%.1f".format(Quality.meanConfidence(real))
            pageLines += "${f.name};$i;${p.image.cols()};${p.image.rows()};${p.words.size};${real.size};$conf;${p.rotation};${p.issues.joinToString(",")};${p.notes.joinToString(",")}\n"
        }
        val words = pages.flatMap { it.words }
        val conf = if (words.isEmpty()) 0.0 else Quality.meanConfidence(words)
        val share90 = if (words.isEmpty()) 0.0 else words.count { it.conf >= 90 }.toDouble() / words.size
        println("EVAL ${f.name} split=$split pages=${pages.size} ${ms}ms words=${words.size} conf=%.1f".format(conf))
        val line = "${series.name};${f.name};$split;${pages.size};$ms;${pages.flatMap { it.issues }.joinToString(",")};${words.size};" +
            "%.1f;%.2f\n".format(conf, share90)
        pages.forEach { it.close() }
        overlay.release(); small.release(); img.release(); det.close()
        return line
    }
}
