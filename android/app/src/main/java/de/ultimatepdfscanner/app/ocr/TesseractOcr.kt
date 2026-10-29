package de.ultimatepdfscanner.app.ocr

import android.content.Context
import com.googlecode.tesseract.android.TessBaseAPI
import de.ultimatepdfscanner.core.OcrEngine
import de.ultimatepdfscanner.core.Orientation
import de.ultimatepdfscanner.core.Word
import org.opencv.core.Mat
import java.io.File

/**
 * Tesseract4Android engine with the bundled tessdata_best models. Not thread-safe: use one
 * instance per processing thread and [close] it when done.
 */
class TesseractOcr(context: Context, language: String, private val timeoutMs: Long = TIMEOUT_MS) : OcrEngine, AutoCloseable {
    private val api = TessBaseAPI()
    private val watchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "ocr-watchdog").apply { isDaemon = true } }

    init {
        val dataPath = installModels(context)
        check(api.init(dataPath.path, language, TessBaseAPI.OEM_LSTM_ONLY)) { "Tesseract init failed for $language" }
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        api.setVariable("user_defined_dpi", "300")
    }

    private fun setGray(gray: Mat) {
        val data = ByteArray((gray.total()).toInt())
        gray.get(0, 0, data)
        api.setImage(data, gray.cols(), gray.rows(), 1, gray.cols())
    }

    override fun recognize(gray: Mat): List<Word> = recognize(gray, singleBlock = false)

    override fun recognize(gray: Mat, singleBlock: Boolean): List<Word> {
        api.pageSegMode = if (singleBlock) TessBaseAPI.PageSegMode.PSM_SINGLE_BLOCK else TessBaseAPI.PageSegMode.PSM_AUTO
        setGray(gray)
        val words = ArrayList<Word>()
        if (!run()) { api.clear(); return words } // took too long: rather no text than a blocked queue
        val it = api.resultIterator ?: return words
        val word = TessBaseAPI.PageIteratorLevel.RIL_WORD
        var block = 0
        var para = 0
        var line = 0
        it.begin()
        do {
            if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_BLOCK)) { block++; para = 0; line = 0 }
            if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_PARA)) { para++; line = 0 }
            if (it.isAtBeginningOf(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE)) line++
            val text = it.getUTF8Text(word)?.trim().orEmpty()
            if (text.isEmpty()) continue
            val r = it.getBoundingRect(word)
            words += Word(text, it.confidence(word), r.left, r.top, r.right, r.bottom, block, para, line)
        } while (it.next(word))
        it.delete()
        api.clear()
        return words
    }

    /** No OSD in Tesseract4Android: line axis from the ink pattern, then OCR confidence decides 0 vs 180. */
    override fun orientation(gray: Mat, hint: Int?): Int = Orientation.detect(gray, hint) { candidate ->
        api.pageSegMode = TessBaseAPI.PageSegMode.PSM_AUTO
        setGray(candidate)
        (if (run()) api.meanConfidence().toDouble() else 0.0).also { api.clear() }
    }

    /**
     * Runs the recognition, cancelled after [timeoutMs]; false if it was cancelled. Recognition goes
     * through getHOCRText because only that call passes Tesseract a monitor that [TessBaseAPI.stop]
     * can cancel (getUTF8Text cannot be interrupted). Textures without text took minutes on the PC.
     */
    private fun run(): Boolean {
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = watchdog.schedule({ cancelled.set(true); api.stop() }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        try { api.getHOCRText(0) } finally { task.cancel(false) }
        if (cancelled.get()) android.util.Log.w("UltimatePDFScanner", "OCR cancelled after $timeoutMs ms")
        return !cancelled.get()
    }

    override fun close() { watchdog.shutdownNow(); api.recycle() }

    companion object {
        const val TIMEOUT_MS = 90_000L // a dense page at the accurate letter size takes ~10-20 s on the phone
        /** Copy the models from the APK assets to internal storage once; returns the dir containing tessdata/. */
        fun installModels(context: Context): File {
            val base = File(context.filesDir, "ocr")
            val dir = File(base, "tessdata").apply { mkdirs() }
            for (name in context.assets.list("tessdata").orEmpty()) {
                val target = File(dir, name)
                if (target.exists() && target.length() > 0) continue
                val tmp = File(dir, "$name.part")
                context.assets.open("tessdata/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
                tmp.renameTo(target)
            }
            return base
        }
    }
}
