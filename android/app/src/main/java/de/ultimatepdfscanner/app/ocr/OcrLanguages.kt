package de.ultimatepdfscanner.app.ocr

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * OCR languages: German and English ship with the app, the others are downloaded on request
 * (once, from the official tesseract-ocr/tessdata_best repository) and then work offline.
 * Chinese is a language pack for PaddleOCR instead (see [PADDLE_PACKS]).
 */
object OcrLanguages {
    data class Language(val code: String, val label: String)

    val BUNDLED = setOf("deu", "eng")

    val ALL = listOf(
        Language("deu", "Deutsch"), Language("eng", "Englisch"), Language("fra", "Französisch"), Language("ita", "Italienisch"),
        Language("spa", "Spanisch"), Language("por", "Portugiesisch"), Language("nld", "Niederländisch"), Language("pol", "Polnisch"),
        Language("ces", "Tschechisch"), Language("dan", "Dänisch"), Language("swe", "Schwedisch"), Language("nor", "Norwegisch"),
        Language("fin", "Finnisch"), Language("hun", "Ungarisch"), Language("ron", "Rumänisch"), Language("hrv", "Kroatisch"),
        Language("tur", "Türkisch"), Language("ell", "Griechisch"), Language("rus", "Russisch"), Language("ukr", "Ukrainisch"),
        Language("bel", "Belarussisch"), Language("bul", "Bulgarisch"), Language("srp", "Serbisch"), Language("mkd", "Mazedonisch"),
        Language("ara", "Arabisch"), Language("heb", "Hebräisch"), Language("fas", "Persisch"), Language("chi_sim", "Chinesisch (vereinfacht)"),
        Language("hin", "Hindi"), Language("ben", "Bengalisch"), Language("urd", "Urdu"), Language("ind", "Indonesisch"),
        Language("jpn", "Japanisch"), Language("kor", "Koreanisch"), Language("lat", "Latein"), Language("deu_latf", "Deutsch (Fraktur)"),
    )

    fun label(code: String) = ALL.firstOrNull { it.code == code }?.label ?: code

    /**
     * Languages in Latin script that the bundled PaddleOCR model (PP-OCRv5 latin) reads; it is used
     * whenever all selected languages are among them (measured: 95.5 % of the words right against
     * 91.0 % for Tesseract). Other scripts and Fraktur stay with Tesseract.
     */
    val PADDLE_LATIN = setOf("deu", "eng", "fra", "ita", "spa", "por", "nld", "pol", "ces", "dan", "swe", "nor", "fin",
        "hun", "ron", "hrv", "tur", "ind", "lat")

    fun usePaddle(language: String) = language.split('+').all { it in PADDLE_LATIN }

    /**
     * A PaddleOCR recogniser for another script: official PP-OCRv5 mobile ONNX export (PaddlePaddle on
     * Hugging Face, Apache-2.0) at a fixed revision, checked by SHA-256; its character list is in
     * inference.yml. Stored as <[name]>.onnx / .yml, shared by the languages of its script.
     */
    class PaddlePack(val name: String, val repo: String, val revision: String, val onnxSha256: String, val ymlSha256: String,
                     val rtl: Boolean = false, val trustLineClassifier: Boolean = false)

    private val CHINESE = PaddlePack("chinese", "PaddlePaddle/PP-OCRv5_mobile_rec_onnx", "ed152b8b495f84de93cda5709d768548a9127622",
        "da72dc72ca4dc220df0dfde68c1dedc31c58d3e76a25871122e5056227d50092", "5dfeb2777f6d0db8177d8128a8acfcf6e6276dc4ac73ea3bf0dc06d6a5e85d8e",
        trustLineClassifier = true)
    internal val ARABIC = PaddlePack("arabic", "PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx", "14aaedcd75825982689ecf5cd64ab33ee083215a",
        "799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110", "21368419e6c016c31db55d316d59e11c128e1913e6e6fe10287084710043d3a6",
        rtl = true)
    internal val DEVANAGARI = PaddlePack("devanagari", "PaddlePaddle/devanagari_PP-OCRv5_mobile_rec_onnx", "251aec19e36739540d35e2cc943f6aa7503b98e5",
        "cb789212ce96c69d3e74728ae4309d179281d68cb3945d0616b67cafab41c986", "9bd172dd26440c8ce94d1cde5d5baea6aefdc7cf3c5c8492e0beedef656d4e54")
    // the general Cyrillic model read Russian and Ukrainian without error, the East Slavic one with 0.8-1.4 %.
    // ARABIC, DEVANAGARI and CYRILLIC are not in use: Tesseract read the test pages as well or better (see PADDLE_PACKS)
    internal val CYRILLIC = PaddlePack("cyrillic", "PaddlePaddle/cyrillic_PP-OCRv5_mobile_rec_onnx", "2cef88145434beb8afa9dd82d77d799eb1ad7b29",
        "5371ee1ddaa7983cc62d0818d99e982b6804638c85e4f960d59a574094e172e5", "5c76cc91fa98410178a09f498db10050d0ec1634a660053d3005ab7be581f501",
        trustLineClassifier = true)

    /**
     * Languages read by a pack. Characters wrong on pages typeset by Word, clean / like a phone photo:
     * Chinese PaddleOCR 0 / 0 %, Tesseract 1.0 / 10.9 %. Tesseract was as good or better for Arabic
     * (0.3 / 0.3 % against 0.3 / 3.8 % with upscaled text), Hindi (0 / 0 % against 3.2 / 4.6 %) and
     * Cyrillic (0-0.4 % both, Tesseract 3x faster), so these stay with Tesseract; adding e.g.
     * "ara" to ARABIC here would switch them over (real photos may tell otherwise).
     */
    val PADDLE_PACKS = mapOf("chi_sim" to CHINESE)

    fun packModel(context: Context, pack: PaddlePack) = File(packDir(context), "${pack.name}.onnx")
    fun packCharacters(context: Context, pack: PaddlePack) = File(packDir(context), "${pack.name}.yml")
    private fun packInstalled(context: Context, pack: PaddlePack) = packModel(context, pack).isFile && packCharacters(context, pack).isFile

    /**
     * The language pack that reads [language] when its pack languages share one pack, if installed
     * (the packs read Latin letters too, so "Deutsch + Chinesisch" is read by the Chinese pack); null:
     * Tesseract or the bundled Latin model reads.
     */
    fun paddlePack(context: Context, language: String): PaddlePack? {
        val pack = language.split('+').mapNotNull { PADDLE_PACKS[it] }.distinct().singleOrNull() ?: return null
        return pack.takeIf { packInstalled(context, it) }
    }

    private fun dir(context: Context) = File(TesseractOcr.installModels(context), "tessdata")
    private fun packDir(context: Context) = File(TesseractOcr.installModels(context), "paddle").apply { mkdirs() }

    fun installed(context: Context): Set<String> =
        dir(context).listFiles { f -> f.name.endsWith(".traineddata") }?.map { it.name.removeSuffix(".traineddata") }?.toSet().orEmpty() + BUNDLED +
            PADDLE_PACKS.filterValues { packInstalled(context, it) }.keys

    fun size(context: Context, code: String): Long = File(dir(context), "$code.traineddata").length() +
        (PADDLE_PACKS[code]?.let { packModel(context, it).length() + packCharacters(context, it).length() } ?: 0L)

    /** Download one language (Tesseract model or PaddleOCR pack); [progress] gets 0..1. Throws on network errors. */
    fun download(context: Context, code: String, progress: (Float) -> Unit) {
        val pack = PADDLE_PACKS[code]
        if (pack == null) {
            fetch("https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/$code.traineddata", File(dir(context), "$code.traineddata"), 100_000, null, progress)
            return
        }
        if (packInstalled(context, pack)) return // another language of the same script loaded it
        val base = "https://huggingface.co/${pack.repo}/resolve/${pack.revision}"
        fetch("$base/inference.yml", packCharacters(context, pack), 1_000, pack.ymlSha256) { }
        fetch("$base/inference.onnx", packModel(context, pack), 1_000_000, pack.onnxSha256, progress)
    }

    private fun fetch(url: String, target: File, minSize: Long, sha256: String?, progress: (Float) -> Unit) {
        val tmp = File(target.path + ".part")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            check(conn.responseCode == 200) { tr("Server antwortet mit %d", conn.responseCode) }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) progress(done.toFloat() / total)
                    }
                }
            }
            check(tmp.length() > minSize) { tr("Datei unvollständig") }
            if (sha256 != null) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                tmp.inputStream().use { input -> val buf = ByteArray(1 shl 16); while (true) { val n = input.read(buf); if (n < 0) break; digest.update(buf, 0, n) } }
                check(digest.digest().joinToString("") { "%02x".format(it) } == sha256) { tr("Datei unvollständig") }
            }
            tmp.renameTo(target)
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    fun remove(context: Context, code: String) {
        if (code in BUNDLED) return
        File(dir(context), "$code.traineddata").delete()
        // a pack shared with other languages (Russian, Ukrainian, ...) goes for all of them
        PADDLE_PACKS[code]?.let { packModel(context, it).delete(); packCharacters(context, it).delete() }
    }
}
