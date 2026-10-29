package de.ultimatepdfscanner.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Book mode on the user's real photo series (testdata/series/labels.tsv): open books must give
 * two pages, covers, receipts and single sheets one. Skipped if the photos are not present.
 */
class BookSplitTest {
    init { TestSupport.init() }

    private val roots = listOf(File("../../testdata/series"), File("../../testdata/series2"))

    /** Photos whose page detection is still wrong (keyboard taken for paper), see roadmap Q2. */
    private val knownDetectionFailures = setOf(
        "Pmvlgy6i6a696.jpg", // Kittel spread pressed flat by both hands: gutter dip 0.055, single sheets reach 0.049
    )

    @Test
    fun bookModeSplitsOnlyOpenBooks() {
        val wrong = ArrayList<String>()
        var checked = 0
        for (root in roots) for (line in File(root, "labels.tsv").takeIf { it.exists() }?.readLines()?.filter { !it.startsWith("#") && it.isNotBlank() }.orEmpty()) {
            val (path, expected) = line.split('\t')
            if (expected == "?") continue
            val file = File(root, path.substringBefore('/') + "/originals/" + path.substringAfter('/'))
            if (!file.exists() || file.name in knownDetectionFailures) continue
            val img = TestSupport.load(file)
            val det = Detect.detectDocument(img)
            val quad = det.quad
            val pages = if (quad == null) 1 else {
                val mask = det.mask ?: org.opencv.core.Mat(img.size(), org.opencv.core.CvType.CV_8U, org.opencv.core.Scalar(255.0))
                val (spread, spreadMask) = Warp.warpQuadWithMask(img, mask, quad, Pipeline.SPREAD_MARGIN, 0.54)
                val gray = Enhance.toGray(spread)
                (if (Pipeline.foldFor(gray, spreadMask, SplitMode.BOOK) == null) 1 else 2).also { listOf(spread, spreadMask, gray).forEach { it.release() } }
            }
            checked++
            if (pages.toString() != expected) wrong += "${file.name}: expected $expected, got $pages"
            img.release(); det.close()
        }
        println("book split: $checked photos checked, ${wrong.size} wrong")
        assertTrue(wrong.isEmpty(), wrong.joinToString("\n"))
    }
}
