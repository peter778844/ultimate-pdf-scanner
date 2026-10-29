package de.ultimatepdfscanner.app

import android.app.Application
import android.content.Context
import androidx.exifinterface.media.ExifInterface
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.ultimatepdfscanner.app.data.DocInfo
import de.ultimatepdfscanner.app.data.FolderInfo
import de.ultimatepdfscanner.app.data.PageInfo
import de.ultimatepdfscanner.app.data.PhotoInfo
import de.ultimatepdfscanner.app.data.Storage
import de.ultimatepdfscanner.app.export.ExportFormat
import de.ultimatepdfscanner.app.export.ExportOptions
import de.ultimatepdfscanner.app.export.Exporter
import de.ultimatepdfscanner.app.export.Compression
import de.ultimatepdfscanner.app.export.Resolution
import de.ultimatepdfscanner.app.export.PageSize
import de.ultimatepdfscanner.app.ocr.TesseractOcr
import de.ultimatepdfscanner.app.translate.OfflineTranslator
import de.ultimatepdfscanner.app.translate.PageTranslator
import de.ultimatepdfscanner.core.Classify
import de.ultimatepdfscanner.core.CornerModel
import de.ultimatepdfscanner.core.Formulas
import de.ultimatepdfscanner.core.HandwritingReader
import de.ultimatepdfscanner.core.OcrEngine
import de.ultimatepdfscanner.core.PaddleOcr
import de.ultimatepdfscanner.core.Mode
import de.ultimatepdfscanner.core.pageText
import de.ultimatepdfscanner.core.Pipeline
import de.ultimatepdfscanner.core.PipelineConfig
import de.ultimatepdfscanner.core.Quad
import de.ultimatepdfscanner.core.SplitMode
import de.ultimatepdfscanner.core.TextSize
import de.ultimatepdfscanner.core.Warp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
    data object Trash : Screen
    data object SendToPc : Screen
    data object OcrLanguages : Screen
    data object Glossaries : Screen
    data object StorageOverview : Screen
    data class Folder(val folderId: String) : Screen
    /** [retakePhotoId]: one shot replaces this photo, then the camera returns to the document. */
    data class Camera(val docId: String?, val retakePhotoId: String? = null, val folderId: String? = null) : Screen
    data class Document(val docId: String) : Screen
    data class Page(val docId: String, val pageId: String) : Screen
    data class Crop(val docId: String, val pageId: String) : Screen
    data class Redact(val docId: String, val pageId: String) : Screen
    data class SignaturePad(val docId: String?, val pageId: String?) : Screen // draw; then place on the page if given
    data class Sign(val docId: String, val pageId: String) : Screen
    data class TextEdit(val docId: String, val pageId: String) : Screen
    data class Annotate(val docId: String, val pageId: String) : Screen
}

/** What the camera is set up for; each mode has its own guide and processing. */
enum class CaptureMode(val label: String) {
    QR("QR-CODE"), ID_CARD("AUSWEIS"), DOCUMENT("DOKUMENT"), BOOK("BUCH"), PICTURE("BILD"), CARD("VISITENKARTE"), PASSPORT("PASSFOTO");

    /** Document type preset by the mode (null = classify from the text). */
    val docType: de.ultimatepdfscanner.core.DocType? get() = when (this) {
        ID_CARD -> de.ultimatepdfscanner.core.DocType.ID_CARD
        CARD -> de.ultimatepdfscanner.core.DocType.BUSINESS_CARD
        BOOK -> de.ultimatepdfscanner.core.DocType.BOOK
        PICTURE -> de.ultimatepdfscanner.core.DocType.PICTURE
        PASSPORT -> de.ultimatepdfscanner.core.DocType.PHOTO
        else -> null
    }
}

/**
 * Scan quality: camera resolution and the letter size the text recognition works at. Large clear
 * print reads fine from a small photo; small print, old books and formulas need more pixels.
 * [megapixels] is what the processing plan budgets memory for (Genau: the sensor's maximum, up to 50 MP).
 */
enum class ScanQuality(val captureWidth: Int?, val captureHeight: Int?, val megapixels: Int, val textHeight: Double, val upscale: Boolean) {
    FAST(2560, 1920, 5, TextSize.TARGET, false),
    STANDARD(4000, 3000, 12, TextSize.TARGET, true),
    ACCURATE(null, null, 50, TextSize.ACCURATE_TARGET, true),
}

/**
 * How the camera shoots: on the shutter only, automatically (page steady for 0.7 s; book mode:
 * after each page turn), or by a Bluetooth remote. Volume keys and remotes shoot in every setting.
 */
enum class Trigger { MANUAL, AUTO, REMOTE }

const val AUTO_DELAY_DEFAULT_MS = 700
const val AUTO_DELAY_MIN_MS = 200
const val AUTO_DELAY_MAX_MS = 1500

/** Seconds with one decimal in the user's notation, e.g. "0,7". */
fun seconds(ms: Int): String = String.format(java.util.Locale.getDefault(), "%.1f", ms / 1000.0)

data class Settings(
    val quality: ScanQuality = ScanQuality.STANDARD,
    val language: String = "deu",
    val mode: Mode = Mode.COLOR, // filter of new documents: COLOR, GRAY or BW
    val enhance: Boolean = true, // automatic correction of new documents (shadows, artefacts, text contrast)
    val split: SplitMode = SplitMode.AUTO,
    val handwriting: Boolean = false,
    val trigger: Trigger = Trigger.MANUAL,
    val autoDelayMs: Int = AUTO_DELAY_DEFAULT_MS, // automatic shot: how long the page must lie still (200-1500 ms)
    val appLock: Boolean = false, // biometric lock when opening the app
    val cameraFocal: Double = Warp.DEFAULT_FOCAL, // focal length / sensor diagonal of the back camera, read from Camera2
    val export: ExportOptions = ExportOptions(), // last used export format and options (never the password)
    val captureMode: CaptureMode = CaptureMode.DOCUMENT,
    val timer: Int = 0, // seconds between automatic shots (0 = off)
    val rtl: Boolean = false, // book mode: right-to-left reading order
    val uiLanguage: I18n.Choice = I18n.Choice.SYSTEM,
    val ocr: Boolean = true, // text recognition (searchable PDF); off: faster, image-only PDF
    val exportFolder: String? = null, // document-tree URI where "Speichern" puts exports; null = ask every time
    val cameraMegapixels: Int = 0, // largest photo the back camera gives this app (0 = camera not opened yet)
)

/** A full-text search hit: [snippet] around the match, [page] 1-based (null = matched the name). */
data class SearchHit(val docId: String, val snippet: String?, val page: Int?)

/** Background processing state of one document. */
data class Work(val done: Int, val total: Int, val step: String)

class ScanViewModel(app: Application) : AndroidViewModel(app) {
    val storage = Storage(app.filesDir)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()
    private val back = ArrayDeque<Screen>()

    private val _docs = MutableStateFlow(storage.list())
    val docs: StateFlow<List<DocInfo>> = _docs.asStateFlow()
    private val _folders = MutableStateFlow(storage.folders())
    val folders: StateFlow<List<FolderInfo>> = _folders.asStateFlow()
    private val _settings = MutableStateFlow(loadSettings().also { I18n.choice = it.uiLanguage })
    val settings: StateFlow<Settings> = _settings.asStateFlow()
    private val _work = MutableStateFlow<Map<String, Work>>(emptyMap())
    val work: StateFlow<Map<String, Work>> = _work.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null) // short blocking operations (export, filter)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    /** Bumped when page images change so the UI reloads them. */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    /** App lock: locked until the user authenticates; [lockedSince] tracks time in the background. */
    private val _locked = MutableStateFlow(_settings.value.appLock)
    val locked: StateFlow<Boolean> = _locked.asStateFlow()
    private var backgroundSince = 0L
    fun unlock() { _locked.value = false }
    fun onAppStopped() { backgroundSince = System.currentTimeMillis() }
    fun onAppStarted() {
        if (_settings.value.appLock && backgroundSince > 0 && System.currentTimeMillis() - backgroundSince > AppLock.RELOCK_MS) _locked.value = true
    }

    /** Shutter triggers from hardware keys / Bluetooth remotes while the camera is open. */
    private val _shutter = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val shutter: SharedFlow<Unit> = _shutter
    fun triggerShutter() { _shutter.tryEmit(Unit) }

    private val docLock = Mutex() // doc.json updates come from the UI and the processing workers

    // processing state; declared before init {} because init already starts the workers
    /** Photos currently being processed by a worker (so two workers never take the same photo). */
    private val claimed = HashSet<String>()
    private val activeWorkers = java.util.concurrent.atomic.AtomicInteger(0)
    /** Parallelism for this phone; lowered for the rest of the session if memory runs out. */
    private var plan: Performance.Plan? = null
    private var lastPlan: Performance.Plan? = null
    private var lowered = false // ran out of memory: one photo at a time for the rest of the session

    init {
        Log.i(TAG, "device: " + Performance.describe(app))
        ensureProcessing() // resume photos left unprocessed when the app was closed
        purgeTrash()
    }

    // --- navigation ---------------------------------------------------------------------

    /** Jump straight into the camera (from a shortcut), on top of the library. */
    fun startCamera(mode: CaptureMode) {
        updateSettings(_settings.value.copy(captureMode = mode))
        if (_screen.value is Screen.Camera) return
        back.clear()
        _screen.value = Screen.Home
        navigate(Screen.Camera(null))
    }

    fun navigate(to: Screen) { back.addLast(_screen.value); _screen.value = to }

    /** Replace the current screen (no back entry), e.g. camera -> document. */
    fun replace(to: Screen) { _screen.value = to }

    fun goBack(): Boolean {
        val prev = back.removeLastOrNull() ?: return false
        _screen.value = prev
        return true
    }

    /** Pop screens until the document is on top (e.g. after re-cropping, whose pages get new ids). */
    fun backToDocument(docId: String) {
        while (back.isNotEmpty() && _screen.value != Screen.Document(docId)) _screen.value = back.removeLast()
        if (_screen.value != Screen.Document(docId)) _screen.value = Screen.Document(docId)
    }

    fun doc(id: String): DocInfo? = _docs.value.firstOrNull { it.id == id }

    fun consumeMessage() { _message.value = null }

    fun toast(text: String) { _message.value = text }

    private fun publish(doc: DocInfo) {
        _docs.update { list -> if (list.any { it.id == doc.id }) list.map { if (it.id == doc.id) doc else it } else listOf(doc) + list }
        _version.update { it + 1 }
    }

    private suspend fun edit(docId: String, change: (DocInfo) -> DocInfo): DocInfo? = docLock.withLock {
        val current = storage.load(docId) ?: return@withLock null
        change(current).also { publish(it) }
    }

    // --- capture & import -----------------------------------------------------------------

    fun newDocument(folder: String? = null): DocInfo {
        val name = "Scan " + SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY).format(Date()) // see DEFAULT_NAME
        val doc = storage.create(name).let { if (folder != null) storage.setFolder(it, folder) else it }
        return doc.also { publish(it) }
    }

    /**
     * The document of the open camera session. Created with the first shot and stored in the
     * screen state (not in the composition), so every shot of the session lands in the same
     * document even if the camera UI is rebuilt in between.
     */
    fun cameraDocument(type: de.ultimatepdfscanner.core.DocType? = null): DocInfo {
        val current = (_screen.value as? Screen.Camera)?.docId
        current?.let { id -> doc(id) ?: storage.load(id)?.also { publish(it) } }?.let { return it }
        val folder = (_screen.value as? Screen.Camera)?.folderId
        val created = newDocument(folder).let { d -> if (type != null) storage.setType(d, type.name).also { publish(it) } else d }
        return created.also { d ->
            (_screen.value as? Screen.Camera)?.let { _screen.value = it.copy(docId = d.id) }
            Log.i(TAG, "camera session: new document ${d.id} (was $current)")
        }
    }

    /** Where the camera writes the next photo; register it with [photoTaken] afterwards. */
    fun nextPhotoFile(doc: DocInfo): Pair<String, File> {
        val id = storage.newPhotoId()
        return id to storage.original(doc, id)
    }

    /** [split] how the photo is divided (book mode: always two pages), [rtl] right page first. */
    fun photoTaken(docId: String, photoId: String, split: SplitMode = _settings.value.split, rtl: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = _settings.value
            doc(docId)?.let { normalizeOriginal(storage.original(it, photoId)); rememberPhotoSize(storage.original(it, photoId)) }
            edit(docId) { storage.addPhoto(it, photoId, split, s.cameraFocal, rtl) }
            ensureProcessing()
        }
    }

    /** A retake was written to [nextPhotoFile]: it replaces [oldPhotoId] (and its pages) in place. */
    fun retakeTaken(docId: String, oldPhotoId: String, newPhotoId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val s = _settings.value
            doc(docId)?.let { normalizeOriginal(storage.original(it, newPhotoId)) }
            edit(docId) { storage.retake(it, oldPhotoId, newPhotoId, s.split, s.cameraFocal) } // the mode chosen in the camera
            ensureProcessing()
        }
    }

    fun importUris(docId: String?, uris: List<Uri>, folder: String? = null) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val doc = docId?.let { doc(it) } ?: newDocument(folder)
            if (docId == null) navigate(Screen.Document(doc.id))
            _busy.value = tr("Fotos importieren")
            try {
                withContext(Dispatchers.IO) {
                    val app = getApplication<Application>()
                    for (uri in uris) {
                        val tmp = File.createTempFile("import", null, app.cacheDir)
                        try {
                            app.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } } ?: continue
                            importFile(doc.id, tmp)
                        } finally { tmp.delete() }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "import failed", e)
                _message.value = tr("Import fehlgeschlagen: %s", e.message)
            } finally { _busy.value = null }
            // nothing could be read: no empty new document is left behind
            if (docId == null && doc(doc.id)?.photos.isNullOrEmpty()) {
                if (!goBack()) replace(Screen.Home)
                deleteForever(listOf(doc.id))
            }
            ensureProcessing()
        }
    }

    /** Download a PDF or picture from [url] and import it like a file (new document unless [docId]). */
    fun importLink(docId: String?, url: String, folder: String? = null) {
        viewModelScope.launch {
            _busy.value = tr("Link laden")
            val app = getApplication<Application>()
            val tmp = File.createTempFile("link", null, app.cacheDir)
            try {
                withContext(Dispatchers.IO) {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000; conn.readTimeout = 30000; conn.instanceFollowRedirects = true
                    try {
                        check(conn.responseCode == 200) { tr("Server antwortet mit %d", conn.responseCode) }
                        conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    } finally { conn.disconnect() }
                }
                if (!importable(tmp)) { _message.value = tr("Unter dem Link liegt keine PDF- oder Bilddatei."); return@launch }
                val doc = docId?.let { doc(it) } ?: newDocument(folder)
                if (docId == null) navigate(Screen.Document(doc.id))
                withContext(Dispatchers.IO) { importFile(doc.id, tmp) }
                ensureProcessing()
            } catch (e: Exception) {
                Log.w(TAG, "link import failed", e)
                _message.value = tr("Import fehlgeschlagen: %s", e.message)
            } finally { tmp.delete(); _busy.value = null }
        }
    }

    private fun isPdf(file: File) = ByteArray(5).also { b -> file.inputStream().use { it.read(b) } }.toString(Charsets.ISO_8859_1) == "%PDF-"

    private fun isImage(file: File) = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { android.graphics.BitmapFactory.decodeFile(file.path, it) }.outWidth > 0

    private fun importable(file: File) = isPdf(file) || isImage(file)

    /**
     * One imported file: a PDF gives a digital page per PDF page; a picture is a camera photo (cropped and
     * flattened like one) if its EXIF data names a camera, else a digital page (screenshot, picture from the web).
     */
    private suspend fun importFile(docId: String, file: File) {
        if (isPdf(file)) { importPdf(docId, file); return }
        check(isImage(file)) { tr("Nur PDF-Dateien und Bilder lassen sich importieren.") }
        val doc = doc(docId) ?: return
        val id = storage.newPhotoId()
        val target = storage.original(doc, id)
        file.copyTo(target, overwrite = true)
        val exif = runCatching { ExifInterface(target.path) }.getOrNull()
        val camera = exif != null && (exif.getAttribute(ExifInterface.TAG_MAKE) != null || exif.getAttribute(ExifInterface.TAG_MODEL) != null ||
            exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0) > 0)
        val focal = Warp.focalFrom35mm(exif?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0)?.toDouble())
        normalizeOriginal(target)
        edit(docId) { if (camera) storage.addPhoto(it, id, _settings.value.split, focal) else storage.addDigitalPage(it, id) }
    }

    /** Every page of a PDF rendered at [PDF_DPI] (white background) as a digital page. */
    private suspend fun importPdf(docId: String, file: File) {
        val fd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        val renderer = try { android.graphics.pdf.PdfRenderer(fd) } catch (e: SecurityException) {
            fd.close(); error(tr("Geschützte PDF-Dateien lassen sich nicht importieren."))
        }
        renderer.use { r ->
            for (i in 0 until r.pageCount) {
                _busy.value = tr("PDF-Seite %d/%d importieren", i + 1, r.pageCount)
                r.openPage(i).use { page ->
                    // a quarter of the Java heap at most for the page bitmap (4 bytes per pixel): phones with a small heap
                    val heapPixels = getApplication<Application>().getSystemService(android.app.ActivityManager::class.java).largeMemoryClass * 1024.0 * 1024 / 4 / 4
                    val f = minOf(PDF_DPI / 72.0, MAX_SIDE.toDouble() / max(page.width, page.height), kotlin.math.sqrt(heapPixels / (page.width.toDouble() * page.height)))
                    val bmp = android.graphics.Bitmap.createBitmap(max(1, (page.width * f).toInt()), max(1, (page.height * f).toInt()), android.graphics.Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(android.graphics.Color.WHITE)
                    page.render(bmp, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    val doc = doc(docId) ?: return
                    val id = storage.newPhotoId()
                    storage.original(doc, id).outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, it) }
                    bmp.recycle()
                    edit(docId) { storage.addDigitalPage(it, id) }
                }
            }
        }
    }

    /** Import every JPEG of a folder as a new document (debug builds: adb test hook). */
    fun importFiles(dir: File) {
        val files = dir.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg") }?.sortedBy { it.name } ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val doc = newDocument()
            for (f in files) {
                val id = storage.newPhotoId()
                f.copyTo(storage.original(doc, id))
                normalizeOriginal(storage.original(doc, id))
                edit(doc.id) { storage.addPhoto(it, id, _settings.value.split, Warp.DEFAULT_FOCAL) }
            }
            ensureProcessing()
        }
    }

    /**
     * Bake the EXIF rotation into the pixels and cap the size, so that page detection, the crop
     * editor and the processing all see the same image.
     */
    private fun normalizeOriginal(file: File) {
        val image = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_COLOR) // applies the EXIF orientation
        if (image.empty()) return
        val longest = max(image.cols(), image.rows())
        // "Maximal" keeps what the sensor delivers (up to 50 MP); otherwise 12 MP are plenty and much faster
        val cap = if (_settings.value.quality == ScanQuality.ACCURATE) MAX_SIDE_ACCURATE else MAX_SIDE
        val out = if (longest > cap) org.opencv.core.Mat().also {
            val f = cap.toDouble() / longest
            Imgproc.resize(image, it, Size(image.cols() * f, image.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA)
        } else image
        Imgcodecs.imwrite(file.path, out, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 95))
        if (out !== image) out.release()
        image.release()
    }

    // --- background processing --------------------------------------------------------------


    /**
     * Start processing if photos are waiting: as many workers as this phone allows (see
     * [Performance]), each with its own OCR engine(s). Workers end when nothing is left.
     */
    fun ensureProcessing() {
        // memory per photo follows its size: the scan quality, or the sensor's maximum for pictures
        val pictures = storage.list().any { d -> d.type == de.ultimatepdfscanner.core.DocType.PICTURE.name && d.pending > 0 }
        val wanted = if (pictures) ScanQuality.ACCURATE.megapixels else _settings.value.quality.megapixels
        // phones often give apps less than the sensor's maximum (Xiaomi 17T Pro: 12.6 of 50 MP): plan with what arrives
        val megapixels = prefs.getInt("photoMegapixels", 0).takeIf { it > 0 }?.let { minOf(it, wanted) } ?: wanted
        // planned afresh whenever nothing runs: photo size, free memory and heat change; out-of-memory lowers it for the session
        if (activeWorkers.get() == 0) plan = null
        val p = plan ?: Performance.plan(getApplication(), megapixels)
            .let { if (lowered) it.copy(photoWorkers = 1, parallelPages = false) else it }.also {
                if (it != lastPlan) Log.i(TAG, "processing plan: $it")
                lastPlan = it
                plan = it
            }
        while (true) {
            val n = activeWorkers.get()
            if (n >= p.photoWorkers) return
            if (activeWorkers.compareAndSet(n, n + 1)) viewModelScope.launch(Dispatchers.Default) { workerLoop() }
        }
    }

    /** The next unprocessed photo of any document, reserved for the caller. */
    private fun claimNext(): Pair<DocInfo, PhotoInfo>? = synchronized(claimed) {
        for (doc in storage.list()) for (photo in doc.photos) {
            if (!photo.processed && photo.id !in claimed) { claimed += photo.id; return doc to photo }
        }
        null
    }

    private suspend fun workerLoop() {
        var ocr: OcrEngine? = null
        var second: OcrEngine? = null // second engine: the two pages of a spread in parallel
        var ocrLanguage = ""
        var hint: Int? = null // orientation of the previous page: tried first
        try {
            while (true) {
                if (activeWorkers.get() > (plan?.photoWorkers ?: 1)) break // parallelism was lowered
                val (doc, photo) = claimNext() ?: break
                // a photo that took the app down (native crash, a hang the user ended) is not tried for ever:
                // counted before each try (written at once), cleared when the try ends
                val attemptsKey = "attempts_${photo.id}"
                try {
                    val attempts = prefs.getInt(attemptsKey, 0) + 1
                    prefs.edit().putInt(attemptsKey, attempts).commit()
                    if (attempts > MAX_ATTEMPTS) error(tr("Verarbeitung mehrfach abgebrochen"))
                    val s = _settings.value
                    if (ocr == null || ocrLanguage != s.language) {
                        (ocr as? TesseractOcr)?.close(); (second as? TesseractOcr)?.close(); second = null
                        setWork(doc.id, tr("Texterkennung vorbereiten"))
                        val paddleEngine = if (de.ultimatepdfscanner.app.ocr.OcrLanguages.usePaddle(s.language)) paddle
                            else de.ultimatepdfscanner.app.ocr.OcrLanguages.paddlePack(getApplication(), s.language)?.let(::paddlePack)
                        if (paddleEngine != null) {
                            // one shared PaddleOCR instance: ONNX Runtime serves several threads
                            ocr = paddleEngine
                            if (plan?.parallelPages == true) second = paddleEngine
                        } else {
                            val installed = de.ultimatepdfscanner.app.ocr.OcrLanguages.installed(getApplication())
                            val language = s.language.split('+').filter { it in installed }.joinToString("+").ifEmpty { "deu" }
                            ocr = TesseractOcr(getApplication(), language)
                            if (plan?.parallelPages == true) second = TesseractOcr(getApplication(), language)
                        }
                        ocrLanguage = s.language
                    }
                    hint = processPhoto(doc, photo, ocr, second, s, hint)
                } catch (e: Throwable) {
                    if (isOutOfMemory(e)) {
                        // not the photo's fault: retry it with less parallelism
                        Log.w(TAG, "photo ${photo.id}: out of memory, lowering parallelism", e)
                        lowered = true
                        plan = plan?.copy(photoWorkers = 1, parallelPages = false)
                        (second as? TesseractOcr)?.close(); second = null
                        System.gc()
                    } else {
                        Log.e(TAG, "photo ${photo.id} failed", e)
                        _message.value = tr("Ein Foto konnte nicht verarbeitet werden: %s", e.message)
                        edit(doc.id) { d -> storage.markFailed(d, photo.id) }
                    }
                } finally {
                    prefs.edit().remove(attemptsKey).apply()
                    synchronized(claimed) { claimed -= photo.id }
                }
            }
        } finally {
            (ocr as? TesseractOcr)?.close(); (second as? TesseractOcr)?.close() // the shared PaddleOCR stays open
            if (activeWorkers.decrementAndGet() == 0) _work.value = emptyMap()
        }
        // photos may have been added (or retried) while this worker was finishing
        if (synchronized(claimed) { storage.list().any { d -> d.photos.any { !it.processed && it.id !in claimed } } }) ensureProcessing()
    }

    /**
     * Learned page-corner detector (DocAligner, ~13 MB, bundled), loaded on first use and shared
     * by all workers. Without it (load failed) the segmentation alone finds the pages.
     */
    private val cornerModel: CornerModel? by lazy {
        try {
            CornerModel(getApplication<Application>().assets.open(CORNER_MODEL).use { it.readBytes() })
        } catch (e: Throwable) {
            Log.w(TAG, "corner model not available", e)
            null
        }
    }

    /**
     * PaddleOCR (PP-OCRv5 mobile detector + Latin recogniser, ~13 MB, bundled, Apache-2.0) with the
     * handwriting reader for unsure lines (DE·HTR, ~10 MB), loaded on first use and shared by all
     * workers; null if loading failed (then Tesseract reads).
     */
    private val paddle: PaddleOcr? by lazy {
        try {
            val assets = getApplication<Application>().assets
            val handwriting = try {
                HandwritingReader(assets.open(HANDWRITING_MODEL).use { it.readBytes() }, assets.open(HANDWRITING_ALPHABET).bufferedReader().readText(),
                    leanMemory = Performance.leanMemory(getApplication()))
            } catch (e: Throwable) {
                Log.w(TAG, "handwriting model not available", e)
                null
            }
            PaddleOcr(assets.open(PADDLE_DET).use { it.readBytes() }, assets.open(PADDLE_REC).use { it.readBytes() }, threads = 2,
                clsModel = assets.open(PADDLE_CLS).use { it.readBytes() }, handwriting = handwriting, leanMemory = Performance.leanMemory(getApplication()))
        } catch (e: Throwable) {
            Log.w(TAG, "PaddleOCR not available", e)
            null
        }
    }

    /** PaddleOCR with a downloaded language pack (Chinese), one per pack, loaded on first use. */
    private val paddlePacks = HashMap<String, PaddleOcr>()

    private fun paddlePack(pack: de.ultimatepdfscanner.app.ocr.OcrLanguages.PaddlePack): PaddleOcr? = synchronized(paddlePacks) {
        paddlePacks[pack.name] ?: try {
            val app = getApplication<Application>()
            PaddleOcr(app.assets.open(PADDLE_DET).use { it.readBytes() }, de.ultimatepdfscanner.app.ocr.OcrLanguages.packModel(app, pack).readBytes(),
                threads = 2, clsModel = app.assets.open(PADDLE_CLS).use { it.readBytes() },
                characters = PaddleOcr.charactersFromYaml(de.ultimatepdfscanner.app.ocr.OcrLanguages.packCharacters(app, pack).readText()),
                rtl = pack.rtl, trustLineClassifier = pack.trustLineClassifier, leanMemory = Performance.leanMemory(app)).also { paddlePacks[pack.name] = it }
        } catch (e: Throwable) {
            Log.w(TAG, "language pack ${pack.name} not available", e)
            null
        }
    }

    /**
     * Formula detection for Word export (PP-DocLayout-S, ~5 MB, bundled): the formulas go in as pictures;
     * loaded on the first Word export; null if loading failed (then plain text).
     */
    private val formulas: Formulas? by lazy {
        try {
            val assets = getApplication<Application>().assets
            Formulas(assets.open(FORMULA_LAYOUT).use { it.readBytes() })
        } catch (e: Throwable) {
            Log.w(TAG, "formula models not available", e)
            null
        }
    }

    /** The size of the largest photos this camera delivers, for the processing plan. */
    private fun rememberPhotoSize(file: File) {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(file.path, o)
        val mp = ((o.outWidth.toLong() * o.outHeight + 999_999) / 1_000_000).toInt()
        if (mp > prefs.getInt("photoMegapixels", 0)) prefs.edit().putInt("photoMegapixels", mp).apply()
    }

    private fun isOutOfMemory(e: Throwable): Boolean =
        e is OutOfMemoryError || (e.message?.contains("Insufficient memory", ignoreCase = true) == true) ||
            (e.message?.contains("bad_alloc") == true)

    private fun setWork(docId: String, step: String) {
        val doc = storage.load(docId) ?: return
        val total = doc.photos.size
        _work.update { it + (docId to Work(total - doc.pending, total, step)) }
    }

    /** Returns the orientation of the last page (hint for the next photo of the same book). */
    private suspend fun processPhoto(doc: DocInfo, photo: PhotoInfo, ocr: OcrEngine, second: OcrEngine?, s: Settings, hint: Int?): Int? {
        val file = storage.original(doc, photo.id)
        setWork(doc.id, tr("Foto laden"))
        val t0 = System.currentTimeMillis()
        val image = Imgcodecs.imread(file.path, Imgcodecs.IMREAD_COLOR)
        require(!image.empty()) { tr("Foto nicht lesbar") }
        val manual: Quad? = if (photo.manual) photo.quadPoints()?.map { Point(it.x * image.cols(), it.y * image.rows()) }?.toTypedArray() else null
        val picture = doc.type == de.ultimatepdfscanner.core.DocType.PICTURE.name // colour-true, no OCR (see Picture)
        // a document keeps its own filter: new photos follow its pages, the settings only seed new documents
        val mode = doc.pages.map { it.mode }.distinct().singleOrNull() ?: s.mode
        val enhance = doc.pages.map { it.enhance }.distinct().singleOrNull() ?: s.enhance
        val cfg = PipelineConfig(split = photo.split, handwriting = s.handwriting, mode = mode, enhance = enhance, ocr = s.ocr && !picture, orientationHint = hint,
            ocrTextHeight = s.quality.textHeight, ocrUpscale = s.quality.upscale, picture = picture,
            // screenshots and PDF pages: upright, nothing to remove or sharpen (coloured content is no finger)
            fingers = !photo.digital, deblur = !photo.digital, orient = !photo.digital)
        // time per processing step for profiling (logcat tag UltimatePDFScanner); with parallel pages the
        // callback comes from two threads, so the current step is kept per thread
        val steps = LinkedHashMap<String, Long>()
        val current = HashMap<Thread, Pair<String, Long>>()
        fun mark(step: String?) = synchronized(steps) {
            val now = System.currentTimeMillis()
            val (name, since) = current[Thread.currentThread()] ?: ("Laden" to t0)
            steps[name] = (steps[name] ?: 0L) + now - since
            if (step != null) current[Thread.currentThread()] = step to now else current.remove(Thread.currentThread())
        }
        val pages = Pipeline.processPhoto(image, cfg, ocr, photo.focal, manual, second, cornerModel) { step ->
            mark(step)
            setWork(doc.id, tr(step))
        }
        mark(null)
        val used = (manual ?: pages.firstOrNull()?.quad)?.map { Point(it.x / image.cols(), it.y / image.rows()) }?.toTypedArray()
        Log.i(TAG, "photo ${photo.id}: ${pages.size} page(s), ${image.cols()}x${image.rows()}, ${System.currentTimeMillis() - t0} ms, " +
            "words=${pages.map { it.words.size }}, rotation=${pages.map { it.rotation }}, steps=${steps.entries.joinToString { "${it.key}=${it.value}" }}")
        image.release()
        // pictures are stored as rendered (colour, no automatic correction): the text filters would bleach them
        edit(doc.id) { storage.putPages(it, photo, pages, if (picture) Mode.COLOR else mode, enhance && !picture, used, photo.manual) }
        val rotation = pages.lastOrNull()?.rotation
        pages.forEach { it.close() }
        setWork(doc.id, "")
        autoName(doc.id)
        return rotation
    }

    /**
     * Once all photos are processed, a document that still has its default name is classified
     * (receipt, invoice, business card, ...) and renamed like "Quittung_2026-10-23_12-30".
     */
    private suspend fun autoName(docId: String) {
        val d = storage.load(docId) ?: return
        if (d.pending > 0 || d.pages.isEmpty() || !DEFAULT_NAME.matches(d.name)) return
        d.type?.let { preset -> // type chosen by the camera mode: name it, no guessing
            val t = runCatching { de.ultimatepdfscanner.core.DocType.valueOf(preset) }.getOrNull() ?: return
            edit(docId) { it.copy(name = autoFileName(t, it.created)).also(storage::save) }
            return
        }
        val text = d.pages.take(3).joinToString("\n") { pageText(storage.words(d, it)) }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(storage.pageFile(d, d.pages.first()).path, bounds)
        val aspect = max(bounds.outWidth, bounds.outHeight).toDouble() / kotlin.math.max(1, kotlin.math.min(bounds.outWidth, bounds.outHeight))
        val type = Classify.classify(text, d.pages.size, aspect)
        edit(docId) { it.copy(name = autoFileName(type, it.created), type = type.name).also(storage::save) }
    }

    /** "Quittung_2026-10-23_12-30" in the UI language ("Receipt_..." in English). */
    private fun autoFileName(t: de.ultimatepdfscanner.core.DocType, created: Long) =
        tr(t.label) + Classify.suggestName(t, created).removePrefix(t.label)

    /** Re-run a photo with corners set by the user (null = automatic detection again). */
    fun recrop(docId: String, photoId: String, relativeQuad: Quad?, split: SplitMode) {
        viewModelScope.launch {
            edit(docId) { d ->
                d.copy(photos = d.photos.map {
                    if (it.id != photoId) it
                    else it.copy(processed = false, split = split, manual = relativeQuad != null,
                        quad = relativeQuad?.flatMap { p -> listOf(p.x, p.y) } ?: it.quad)
                }).also(storage::save)
            }
            ensureProcessing()
        }
    }

    // --- page & document edits --------------------------------------------------------------

    fun setPageMode(docId: String, page: PageInfo, mode: Mode, enhance: Boolean = page.enhance) = blocking(tr("Filter anwenden")) {
        edit(docId) { storage.setMode(it, page, mode, enhance) }
    }

    /** Filter and automatic correction for all pages; null keeps each page's own value. */
    fun setAllModes(docId: String, mode: Mode? = null, enhance: Boolean? = null) = blocking(tr("Filter anwenden")) {
        doc(docId)?.pages?.forEach { p ->
            edit(docId) { d -> d.pages.firstOrNull { it.id == p.id }?.let { storage.setMode(d, it, mode ?: it.mode, enhance ?: it.enhance) } ?: d }
        }
    }

    fun applyRedactions(docId: String, page: PageInfo, regions: List<de.ultimatepdfscanner.core.Redaction>, pixelate: Boolean) = blocking(tr("Schwärzen")) {
        edit(docId) { storage.redact(it, page, regions, pixelate) }
        _message.value = trn(regions.size, "1 Bereich unkenntlich gemacht", "%d Bereiche unkenntlich gemacht")
    }

    /** The user's signature as transparent PNG (drawn once, reused). */
    val signatureFile: File get() = File(getApplication<Application>().filesDir, "signature.png")

    fun saveSignature(png: android.graphics.Bitmap) {
        signatureFile.outputStream().use { png.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        _version.update { it + 1 }
    }

    /** [rect] = x0,y0,x1,y1 relative to the page image as stored (unrotated). */
    fun applySignature(docId: String, page: PageInfo, rect: List<Double>) = blocking(tr("Unterschrift einfügen")) {
        edit(docId) { storage.sign(it, page, signatureFile, rect, page.rotation) }
        _message.value = tr("Unterschrift eingefügt")
    }

    fun applyMarks(docId: String, page: PageInfo, marks: List<de.ultimatepdfscanner.app.data.Mark>) = blocking(tr("Änderungen einfügen")) {
        edit(docId) { storage.annotate(it, page, marks) }
        _message.value = tr("Seite bearbeitet")
    }

    fun rotatePage(docId: String, page: PageInfo, by: Int) { viewModelScope.launch { edit(docId) { storage.rotate(it, page, by) } } }

    fun deletePage(docId: String, page: PageInfo) { viewModelScope.launch { edit(docId) { storage.removePage(it, page) } } }

    private val _hiddenPages = MutableStateFlow(setOf<String>())
    /** Pages deleted with [deletePageUndoable] that can still be brought back; the UI hides them. */
    val hiddenPages: StateFlow<Set<String>> = _hiddenPages.asStateFlow()

    /**
     * One-tap delete: the page disappears at once but its files go only after [UNDO_MS], unless
     * [undoDelete] brings it back first. Runs in the ViewModel, so leaving the screen still deletes.
     */
    fun deletePageUndoable(docId: String, page: PageInfo) {
        _hiddenPages.update { it + page.id }
        viewModelScope.launch {
            delay(UNDO_MS)
            if (page.id !in _hiddenPages.value) return@launch
            edit(docId) { d -> d.pages.firstOrNull { it.id == page.id }?.let { storage.removePage(d, it) } ?: d }
            _hiddenPages.update { it - page.id }
        }
    }

    fun undoDelete(pageId: String) = _hiddenPages.update { it - pageId }

    fun deletePhoto(docId: String, photoId: String) { viewModelScope.launch { edit(docId) { storage.removePhoto(it, photoId) } } }

    fun movePage(docId: String, page: PageInfo, to: Int) { viewModelScope.launch { edit(docId) { storage.movePage(it, page, to) } } }

    /** Store the user's corrected page text (mapped onto the OCR word boxes). */
    fun saveText(docId: String, pageId: String, text: String) {
        viewModelScope.launch(Dispatchers.IO) {
            edit(docId) { d ->
                val page = d.pages.firstOrNull { it.id == pageId } ?: return@edit d
                storage.setWords(d, page, de.ultimatepdfscanner.core.TextEdit.apply(storage.words(d, page), text))
            }
            _message.value = tr("Text gesichert")
        }
    }

    fun flipReadingOrder(docId: String) { viewModelScope.launch { edit(docId) { storage.flipReadingOrder(it) } } }

    /** A finished passport photo: one page with the photo, one with a 10x15 print sheet. */
    fun addPassportPhoto(photo: org.opencv.core.Mat, onDone: (String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val doc = cameraDocument(de.ultimatepdfscanner.core.DocType.PHOTO)
            val sheet = de.ultimatepdfscanner.core.Passport.printSheet(photo)
            edit(doc.id) { d -> storage.addImagePage(storage.addImagePage(d, photo), sheet) }
            photo.release(); sheet.release()
            autoName(doc.id)
            withContext(Dispatchers.Main) { onDone(doc.id) }
        }
    }

    fun reorder(docId: String, ids: List<String>) { viewModelScope.launch { edit(docId) { storage.reorder(it, ids) } } }

    fun rotatePages(docId: String, ids: Collection<String>, by: Int) {
        viewModelScope.launch { edit(docId) { d -> d.pages.filter { it.id in ids }.fold(d) { acc, p -> storage.rotate(acc, acc.pages.first { it.id == p.id }, by) } } }
    }

    fun deletePages(docId: String, ids: Collection<String>) {
        viewModelScope.launch { edit(docId) { d -> d.pages.filter { it.id in ids }.fold(d) { acc, p -> storage.removePage(acc, p) } } }
    }

    fun rename(docId: String, name: String) { viewModelScope.launch { edit(docId) { storage.rename(it, name.ifBlank { it.name }) } } }

    // --- trash -----------------------------------------------------------------------------

    /** Move to the trash; it is deleted for good after [Storage.TRASH_DAYS] days. */
    fun trash(docIds: Collection<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            docIds.forEach { id -> edit(id) { storage.setTrashed(it, now) } }
            lastTrashed = docIds.toList()
        }
    }
    private var lastTrashed: List<String> = emptyList()

    /** Undo the last [trash] (snackbar action). */
    fun undoTrash() = restore(lastTrashed)

    fun restore(docIds: Collection<String>) {
        viewModelScope.launch(Dispatchers.IO) { docIds.forEach { id -> edit(id) { storage.setTrashed(it, null) } } }
    }

    fun deleteForever(docIds: Collection<String>) {
        viewModelScope.launch(Dispatchers.IO) {
            docLock.withLock { docIds.forEach { id -> storage.load(id)?.let { storage.delete(it) } } }
            // only drop these: reloading the whole list raced with documents created meanwhile (at app start the
            // trash purge wiped a document a "Senden an" import had just created)
            _docs.update { list -> list.filter { it.id !in docIds } }
        }
    }

    fun emptyTrash() = deleteForever(_docs.value.filter { it.inTrash }.map { it.id })

    private fun purgeTrash() {
        val limit = System.currentTimeMillis() - Storage.TRASH_DAYS * 24L * 3600 * 1000
        deleteForever(_docs.value.filter { (it.deletedAt ?: Long.MAX_VALUE) < limit }.map { it.id })
    }

    fun deleteDocument(docId: String) = trash(listOf(docId))

    // --- folders & categories --------------------------------------------------------------

    fun createFolder(name: String): FolderInfo {
        val f = FolderInfo(java.util.UUID.randomUUID().toString().take(8), name.trim())
        _folders.value = (_folders.value + f).sortedBy { it.name.lowercase() }
        storage.saveFolders(_folders.value)
        return f
    }

    fun renameFolder(id: String, name: String) {
        _folders.value = _folders.value.map { if (it.id == id) it.copy(name = name.trim()) else it }.sortedBy { it.name.lowercase() }
        storage.saveFolders(_folders.value)
    }

    /** Remove the folder; its scans move back to the top level. */
    fun deleteFolder(id: String) {
        _folders.value = _folders.value.filter { it.id != id }
        storage.saveFolders(_folders.value)
        moveToFolder(_docs.value.filter { it.folder == id }.map { it.id }, null)
    }

    fun moveToFolder(docIds: Collection<String>, folder: String?) {
        viewModelScope.launch(Dispatchers.IO) { docIds.forEach { id -> edit(id) { storage.setFolder(it, folder) } } }
    }

    /**
     * Change a scan's category. Between "Bild" and a text category the photos are processed again
     * (colour-true picture vs. split, text recognition): a book shot in picture mode by mistake
     * becomes a proper book scan.
     */
    fun setType(docId: String, type: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val picture = de.ultimatepdfscanner.core.DocType.PICTURE.name
            var again = false
            edit(docId) { d ->
                val typed = storage.setType(d, type)
                if ((d.type == picture) == (type == picture)) return@edit typed
                again = true
                val split = when (type) {
                    picture -> SplitMode.SINGLE
                    de.ultimatepdfscanner.core.DocType.BOOK.name -> SplitMode.BOOK
                    else -> _settings.value.split
                }
                typed.copy(photos = typed.photos.map { it.copy(processed = false, split = split) }).also(storage::save)
            }
            if (again) ensureProcessing()
        }
    }

    // --- search ------------------------------------------------------------------------------

    /** Name and full-text search over all scans (not in the trash); OCR text is cached per document. */
    suspend fun search(query: String): List<SearchHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext emptyList()
        _docs.value.filter { !it.inTrash && it.photos.isNotEmpty() }.mapNotNull { d ->
            if (d.name.contains(q, ignoreCase = true)) return@mapNotNull SearchHit(d.id, null, null)
            val text = storage.fullText(d)
            // Fraktur read with the long s ("Geſchichte") is found as "Geschichte" (same length: the positions stay)
            val at = text.replace('ſ', 's').indexOf(q.replace('ſ', 's'), ignoreCase = true)
            if (at < 0) return@mapNotNull null
            val page = text.substring(0, at).count { it == Storage.PAGE_BREAK[0] } + 1
            val from = maxOf(0, at - 40)
            val to = minOf(text.length, at + q.length + 60)
            val snippet = (if (from > 0) "…" else "") + text.substring(from, to).replace(WHITESPACE, " ").trim() + (if (to < text.length) "…" else "")
            SearchHit(d.id, snippet, page)
        }
    }

    // --- merge, split, move pages -------------------------------------------------------------

    /** Append the pages of all other documents to the first one, in the given order; the others are removed. */
    fun merge(docIds: List<String>) = blocking(tr("Zusammenführen")) {
        val target = docIds.firstOrNull() ?: return@blocking
        docLock.withLock {
            var into = storage.load(target) ?: return@withLock
            for (id in docIds.drop(1)) {
                val from = storage.load(id) ?: continue
                into = storage.copyPages(from, into, from.pages)
                storage.delete(from)
            }
            publish(into)
        }
        _docs.value = storage.list()
        _message.value = tr("%d Scans zusammengeführt", docIds.size)
    }

    /**
     * Copy or move pages to another document ([targetId] null = a new document named [newName]).
     * Returns the target id.
     */
    suspend fun transferPages(docId: String, pageIds: List<String>, targetId: String?, move: Boolean, newName: String? = null): String? =
        withContext(Dispatchers.IO) {
            _busy.value = if (move) tr("Seiten verschieben") else tr("Seiten kopieren")
            try {
                docLock.withLock {
                    val from = storage.load(docId) ?: return@withLock null
                    val pages = from.pages.filter { it.id in pageIds }
                    val target = targetId?.let { storage.load(it) } ?: storage.create(newName ?: (from.name + tr(" (Teil)"))).copy(folder = from.folder).also { storage.save(it) }
                    val updated = storage.copyPages(from, target, pages)
                    publish(updated)
                    if (move) {
                        var rest = from
                        pages.forEach { p -> rest = storage.removePage(rest, p) }
                        publish(rest)
                    }
                    updated.id
                }
            } finally { _busy.value = null }
        }

    /** Remove documents left without any photo (camera opened and closed without a shot). */
    fun dropEmpty(docId: String) {
        val d = storage.load(docId) ?: return
        if (d.photos.isEmpty()) { storage.delete(d); _docs.value = storage.list() }
    }

    // --- storage & backup ---------------------------------------------------------------------

    /** [languages]: text recognition languages and glossaries the user loaded (plus the bundled ones, unpacked). */
    data class StorageStats(val total: Long, val pages: Long, val originals: Long, val trash: Long, val cache: Long,
                            val largest: List<Pair<DocInfo, Long>>, val free: Long, val languages: Long)

    suspend fun storageStats(): StorageStats = withContext(Dispatchers.IO) {
        val docs = storage.list()
        val sizes = docs.associateWith { storage.size(it) }
        val originals = docs.sumOf { d -> storage.originalsDir(d).listFiles()?.sumOf { it.length() } ?: 0L }
        val cache = getApplication<Application>().cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val total = sizes.values.sum()
        val files = getApplication<Application>().filesDir
        val languages = listOf("ocr", "glossaries").sumOf { File(files, it).walkTopDown().filter { f -> f.isFile }.sumOf { f -> f.length() } }
        StorageStats(total, total - originals, originals, sizes.filterKeys { it.inTrash }.values.sum(), cache,
            sizes.filterKeys { !it.inTrash }.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            files.usableSpace, languages)
    }

    fun clearCache() = blocking(tr("Zwischenspeicher leeren")) {
        getApplication<Application>().cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        _message.value = tr("Zwischenspeicher geleert")
    }

    /**
     * Delete the original photos of all finished scans to save space. Pages stay as they are,
     * but their crop can no longer be changed.
     */
    fun removeOriginals() = blocking(tr("Originalfotos entfernen")) {
        var freed = 0L
        docLock.withLock {
            storage.list().filter { it.pending == 0 }.forEach { d ->
                storage.originalsDir(d).listFiles()?.forEach { freed += it.length(); it.delete() }
            }
        }
        _version.update { it + 1 }
        _message.value = "%.0f MB freigegeben".format(freed / 1e6)
    }

    fun backup(target: Uri, includeTrash: Boolean) = blocking(tr("Sicherung erstellen")) {
        getApplication<Application>().contentResolver.openOutputStream(target)?.use { out ->
            de.ultimatepdfscanner.app.data.Backup.write(getApplication<Application>().filesDir, out, includeTrash) { i, n -> _busy.value = tr("Sicherung erstellen (%d/%d)", i, n) }
        }
        _message.value = tr("Sicherung gespeichert")
    }

    fun restore(source: Uri) = blocking(tr("Sicherung wiederherstellen")) {
        try {
            val r = getApplication<Application>().contentResolver.openInputStream(source)?.use {
                de.ultimatepdfscanner.app.data.Backup.restore(getApplication<Application>().filesDir, it)
            } ?: return@blocking
            _docs.value = storage.list()
            _folders.value = storage.folders()
            _message.value = trn(r.added, "1 Scan wiederhergestellt", "%d Scans wiederhergestellt") + if (r.skipped > 0) tr(" (%d schon vorhanden)", r.skipped) else ""
        } catch (e: Exception) {
            _message.value = tr("Wiederherstellen fehlgeschlagen: %s", e.message)
        }
    }

    // --- export -----------------------------------------------------------------------------

    private fun exportDir() = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }

    private fun pagesOf(doc: DocInfo, pageIds: Collection<String>?) = if (pageIds == null) doc.pages else doc.pages.filter { it.id in pageIds }

    /** Write one export file (all formats except several JPGs) to [out]. */
    private fun writeExport(doc: DocInfo, opts: ExportOptions, pages: List<PageInfo>, out: java.io.OutputStream) {
        val base = safeName(doc.name)
        when (opts.format) {
            ExportFormat.PDF -> if (doc.type == de.ultimatepdfscanner.core.DocType.ID_CARD.name && opts.pageSize != PageSize.ORIGINAL) Exporter.writeIdCardPdf(storage, doc, out, opts, pages)
                else Exporter.writePdf(storage, doc, out, opts, pages) { i, n -> _busy.value = tr("PDF erstellen (%d/%d)", i, n) }
            ExportFormat.DOCX -> Exporter.writeDocx(storage, doc, out, opts, pages, formulas) { i, n -> _busy.value = tr("Word erstellen, Formeln erkennen (%d/%d)", i, n) }
            ExportFormat.ZIP -> Exporter.writeZip(storage, doc, out, base, opts, pages)
            ExportFormat.TXT -> out.write(Exporter.text(storage, doc, pages).toByteArray())
            ExportFormat.XLSX -> Exporter.writeXlsx(storage, doc, out, pages)
            ExportFormat.CSV -> out.write(Exporter.csv(storage, doc, pages).toByteArray())
            ExportFormat.JPG -> out.write(Exporter.pageJpeg(storage, doc, pages.first(), opts.resolution, opts.compression, rotate = true).first)
            ExportFormat.VCF -> out.write(de.ultimatepdfscanner.core.Contact.parse(Exporter.text(storage, doc, pages)).toVCard().toByteArray())
        }
    }

    /** Export into the cache for sharing / printing; several pages as JPG give one file per page. */
    suspend fun exportFiles(docId: String, opts: ExportOptions, pageIds: Collection<String>? = null): List<File> = withContext(Dispatchers.IO) {
        val doc = doc(docId) ?: return@withContext emptyList()
        _busy.value = tr("%s erstellen", tr(opts.format.label))
        try {
            val dir = exportDir()
            dir.listFiles()?.forEach { it.deleteRecursively() }
            val pages = pagesOf(doc, pageIds)
            if (opts.format == ExportFormat.JPG && pages.size > 1) return@withContext Exporter.writeJpegs(storage, doc, dir, safeName(doc.name), opts, pages)
            listOf(File(dir, safeName(doc.name) + "." + opts.format.ext).also { f -> f.outputStream().use { writeExport(doc, opts, pages, it) } })
        } catch (e: Exception) {
            Log.e(TAG, "export failed", e)
            _message.value = tr("Export fehlgeschlagen: %s", e.message)
            emptyList()
        } finally { _busy.value = null }
    }

    /** Save one export file at a place the user picked. */
    fun saveExport(docId: String, opts: ExportOptions, target: Uri, pageIds: Collection<String>? = null) = blocking(tr("%s speichern", tr(opts.format.label))) {
        val doc = doc(docId) ?: return@blocking
        getApplication<Application>().contentResolver.openOutputStream(target)?.use { writeExport(doc, opts, pagesOf(doc, pageIds), it) }
        _message.value = when {
            opts.format == ExportFormat.PDF && !opts.password.isNullOrEmpty() -> tr("PDF gespeichert (passwortgeschützt, AES-256)")
            else -> tr("%s gespeichert", tr(opts.format.label))
        }
    }

    /** Display name of a chosen export folder (null if it is no longer accessible). */
    fun folderName(tree: String?): String? = tree?.let { t ->
        runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(getApplication(), Uri.parse(t)) }.getOrNull()
            ?.takeIf { it.canWrite() }?.name
    }

    /** Keep access to a folder the user picked across restarts. */
    fun setExportFolder(tree: Uri?) {
        val app = getApplication<Application>()
        _settings.value.exportFolder?.let { old ->
            runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(old), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        }
        tree?.let { app.contentResolver.takePersistableUriPermission(it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        updateSettings(_settings.value.copy(exportFolder = tree?.toString()))
    }

    /**
     * Save an export straight into the configured folder (no file dialog). A name that already
     * exists gets a number appended. Returns false if the folder is gone (then the caller asks).
     */
    fun saveToExportFolder(docId: String, opts: ExportOptions, pageIds: Collection<String>? = null): Boolean {
        val tree = _settings.value.exportFolder ?: return false
        val app = getApplication<Application>()
        val folder = runCatching { androidx.documentfile.provider.DocumentFile.fromTreeUri(app, Uri.parse(tree)) }.getOrNull()
        if (folder == null || !folder.canWrite()) {
            _message.value = tr("Der Speicherort ist nicht erreichbar. Bitte neu wählen.")
            updateSettings(_settings.value.copy(exportFolder = null))
            return false
        }
        val doc = doc(docId) ?: return true
        if (opts.format == ExportFormat.JPG && pagesOf(doc, pageIds).size > 1) { saveJpegsToFolder(docId, opts, Uri.parse(tree), pageIds); return true }
        blocking(tr("%s speichern", tr(opts.format.label))) {
            val name = uniqueName(folder, safeName(doc.name), opts.format.ext)
            val target = folder.createFile(opts.format.mime, name) ?: run { _message.value = tr("Export fehlgeschlagen: %s", name); return@blocking }
            app.contentResolver.openOutputStream(target.uri)?.use { writeExport(doc, opts, pagesOf(doc, pageIds), it) }
            _message.value = tr("Gespeichert in „%s“", (folder.name ?: "") + "/" + name + "." + opts.format.ext)
        }
        return true
    }

    /** "Name", or "Name (2)", "Name (3)" ... if a file with that name and extension exists. */
    private fun uniqueName(folder: androidx.documentfile.provider.DocumentFile, base: String, ext: String): String {
        val existing = folder.listFiles().mapNotNull { it.name }.toSet()
        if ("$base.$ext" !in existing) return base
        var i = 2
        while ("$base ($i).$ext" in existing) i++
        return "$base ($i)"
    }

    /** Several JPGs into a folder the user picked (document tree). */
    fun saveJpegsToFolder(docId: String, opts: ExportOptions, tree: Uri, pageIds: Collection<String>? = null) = blocking(tr("Bilder speichern")) {
        val doc = doc(docId) ?: return@blocking
        val app = getApplication<Application>()
        val folder = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, tree) ?: return@blocking
        val pages = pagesOf(doc, pageIds)
        pages.forEachIndexed { i, p ->
            _busy.value = tr("Bilder speichern (%d/%d)", i + 1, pages.size)
            val name = "%s_%02d".format(safeName(doc.name), i + 1)
            folder.createFile("image/jpeg", name)?.uri?.let { u ->
                app.contentResolver.openOutputStream(u)?.use { it.write(Exporter.pageJpeg(storage, doc, p, opts.resolution, opts.compression, rotate = true).first) }
            }
        }
        _message.value = tr("%d Bilder gespeichert", pages.size)
    }

    /** Rough file size of an image export: the first pages are encoded and extrapolated. */
    suspend fun estimateSize(docId: String, opts: ExportOptions, pageIds: Collection<String>? = null): Long? = withContext(Dispatchers.IO) {
        val doc = doc(docId) ?: return@withContext null
        val pages = pagesOf(doc, pageIds)
        if (pages.isEmpty()) return@withContext 0L
        when (opts.format) {
            ExportFormat.PDF, ExportFormat.JPG, ExportFormat.ZIP -> {
                val sample = pages.take(3)
                val bytes = sample.sumOf { p ->
                    if (opts.resolution == Resolution.ORIGINAL && opts.compression == Compression.LOW) storage.pageFile(doc, p).length() else Exporter.pageJpeg(storage, doc, p, opts.resolution, opts.compression).first.size.toLong()
                }
                bytes * pages.size / sample.size + 2_000L * pages.size
            }
            ExportFormat.DOCX -> if (opts.docxImages) pages.size * 350_000L else null
            else -> null
        }
    }

    /**
     * Translate the document on the device into a new document "<name> (<language>)" whose pages
     * look like the originals, with the translation set over the text (see PageTranslator); it
     * exports like any scan. Every paragraph is translated from its own language, so a few lines
     * already in [target] do not stop the rest. Returns the new document's id (null on failure;
     * the reason is shown as a message).
     * [fields]: special fields (IATE) whose terms come from the glossaries; empty: none.
     */
    suspend fun translateDocument(docId: String, target: String, fields: Set<String> = emptySet()): String? = withContext(Dispatchers.IO) {
        val doc = doc(docId) ?: return@withContext null
        if (doc.pending > 0) { _message.value = tr("Bitte warten, bis alle Seiten erkannt sind"); return@withContext null }
        _busy.value = tr("Sprache erkennen")
        try {
            val pages = doc.pages.map { PageTranslator.paragraphs(storage.words(doc, it)) }
            // the long s of Fraktur ("Geſchichte") is an s for the translator
            val all = pages.flatten().map { if (PageTranslator.readable(it)) PageTranslator.text(it).replace('ſ', 's') else "" }
            if (all.none { it.isNotBlank() }) { _message.value = tr("Kein erkannter Text zum Übersetzen"); return@withContext null }
            // the OCR languages the user chose when the text alone does not tell
            val languages = OfflineTranslator.detectEach(all, OfflineTranslator.fromOcrLanguage(_settings.value.language))
            val todo = all.indices.filter { i -> all[i].isNotBlank() && languages[i].let { it != null && it != target && OfflineTranslator.supported(it) } }
            if (todo.isEmpty()) {
                val other = languages.firstOrNull { it != null && it != target }
                _message.value = if (other != null) tr("Die Sprache „%s“ kann nicht übersetzt werden", other)
                    else if (languages.all { it == null }) tr("Sprache nicht erkannt") else tr("Der Text ist bereits auf %s", tr(OfflineTranslator.label(target)))
                return@withContext null
            }
            val translations = arrayOfNulls<String>(all.size)
            var done = 0
            for ((source, indices) in todo.groupBy { languages[it]!! }) {
                val glossary = if (fields.isEmpty()) null else {
                    _busy.value = tr("Fachwörterbuch laden")
                    de.ultimatepdfscanner.app.translate.Glossaries.load(getApplication(), source, target, fields, indices.joinToString(" ") { all[it] })
                        ?: run { _message.value = tr("Fachwörterbuch für %s oder %s fehlt – übersetzt ohne Fachbegriffe", tr(OfflineTranslator.label(source)), tr(OfflineTranslator.label(target))); null }
                }
                _busy.value = tr("Sprachmodell laden (beim ersten Mal ~30 MB)")
                val out = OfflineTranslator.translate(indices.map { all[it] }, source, target, glossary) { _, _ -> _busy.value = tr("Übersetzen (%d/%d)", ++done, todo.size) }
                indices.forEachIndexed { k, i -> translations[i] = out[k] }
            }
            _busy.value = tr("Übersetzung in die Seiten setzen")
            docLock.withLock {
                var copy = storage.create(doc.name + " (" + tr(OfflineTranslator.label(target)) + ")").copy(folder = doc.folder, type = doc.type)
                copy = storage.copyPages(doc, copy, doc.pages)
                var first = 0
                copy.pages.forEachIndexed { i, page ->
                    val pars = pages[i]
                    val texts = translations.copyOfRange(first, first + pars.size).toList()
                    first += pars.size
                    if (texts.all { it == null }) return@forEachIndexed
                    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    android.graphics.BitmapFactory.decodeFile(storage.pageFile(copy, page).path, bounds)
                    val rtl = target in setOf("ar", "he", "fa", "ur")
                    val noSpaces = target in setOf("zh", "ja")
                    val words = PageTranslator.render(storage.pageFile(copy, page), 90, bounds.outWidth, pars, texts, rtl, noSpaces)
                    PageTranslator.render(storage.rectFile(copy, page), 92, bounds.outWidth, pars, texts, rtl, noSpaces) // filter changes keep the translation
                    storage.setWords(copy, page, words)
                    copy = storage.markEdited(copy, page)
                }
                publish(copy)
                copy.id
            }
        } catch (e: Exception) {
            _message.value = tr("Übersetzung fehlgeschlagen: %s", e.message)
            null
        } finally { _busy.value = null }
    }

    fun contact(docId: String): de.ultimatepdfscanner.core.Contact = de.ultimatepdfscanner.core.Contact.parse(text(docId))

    fun text(docId: String): String = doc(docId)?.let { Exporter.text(storage, it) } ?: ""

    // --- settings ---------------------------------------------------------------------------

    fun updateSettings(s: Settings) {
        _settings.value = s
        I18n.choice = s.uiLanguage
        prefs.edit().putString("quality", s.quality.name).putString("uiLanguage", s.uiLanguage.name).putString("language", s.language).putString("mode", s.mode.name).putBoolean("enhance", s.enhance).putString("split", s.split.name)
            .putBoolean("handwriting", s.handwriting).putString("trigger", s.trigger.name).putInt("autoDelayMs", s.autoDelayMs).putBoolean("appLock", s.appLock)
            .putFloat("cameraFocal", s.cameraFocal.toFloat())
            .putString("exportFormat", s.export.format.name).putString("exportPageSize", s.export.pageSize.name)
            .putString("exportResolution", s.export.resolution.name).putString("exportCompression", s.export.compression.name).putBoolean("exportDocxImages", s.export.docxImages)
            .putString("captureMode", s.captureMode.name).putInt("timer", s.timer).putBoolean("rtl", s.rtl).putBoolean("ocr", s.ocr).putString("exportFolder", s.exportFolder).putInt("cameraMegapixels", s.cameraMegapixels).apply()
    }

    private fun loadSettings() = Settings(
        // first start on a weak phone: fast quality (5 MP), a page would otherwise take a minute there
        quality = enumPref("quality", if (Performance.weakDevice(getApplication())) ScanQuality.FAST else ScanQuality.STANDARD),
        language = prefs.getString("language", "deu") ?: "deu",
        // the former "Original" filter is colour without automatic correction
        mode = prefs.getString("mode", null)?.let { runCatching { Mode.valueOf(it) }.getOrNull() }?.takeIf { it != Mode.NONE && it != Mode.AUTO } ?: Mode.COLOR,
        enhance = prefs.getBoolean("enhance", true) && prefs.getString("mode", null) != Mode.NONE.name,
        split = prefs.getString("split", null)?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() } ?: SplitMode.AUTO,
        handwriting = prefs.getBoolean("handwriting", false),
        // earlier versions had two switches (documents: steady page, books: page turn)
        trigger = prefs.getString("trigger", null)?.let { runCatching { Trigger.valueOf(it) }.getOrNull() }
            ?: if (prefs.getBoolean("autoCapture", false) || prefs.getBoolean("pageTurn", false)) Trigger.AUTO else Trigger.MANUAL,
        autoDelayMs = prefs.getInt("autoDelayMs", AUTO_DELAY_DEFAULT_MS).coerceIn(AUTO_DELAY_MIN_MS, AUTO_DELAY_MAX_MS),
        appLock = prefs.getBoolean("appLock", false),
        cameraFocal = prefs.getFloat("cameraFocal", Warp.DEFAULT_FOCAL.toFloat()).toDouble(),
        export = ExportOptions(
            format = enumPref("exportFormat", ExportFormat.PDF).let { if (it == ExportFormat.VCF) ExportFormat.PDF else it },
            pageSize = enumPref("exportPageSize", de.ultimatepdfscanner.app.export.PageSize.ORIGINAL),
            resolution = enumPref("exportResolution", Resolution.ORIGINAL),
            compression = enumPref("exportCompression", Compression.LOW),
            docxImages = prefs.getBoolean("exportDocxImages", false),
        ),
        // special modes are chosen for the occasion and not kept: the camera starts in DOCUMENT again
        captureMode = enumPref("captureMode", CaptureMode.DOCUMENT).let {
            if (it == CaptureMode.QR || it == CaptureMode.PASSPORT || it == CaptureMode.PICTURE) CaptureMode.DOCUMENT else it
        },
        timer = prefs.getInt("timer", 0),
        rtl = prefs.getBoolean("rtl", false),
        uiLanguage = enumPref("uiLanguage", I18n.Choice.SYSTEM),
        ocr = prefs.getBoolean("ocr", true),
        exportFolder = prefs.getString("exportFolder", null),
        cameraMegapixels = prefs.getInt("cameraMegapixels", 0),
    )

    private inline fun <reified E : Enum<E>> enumPref(key: String, default: E): E =
        prefs.getString(key, null)?.let { v -> enumValues<E>().firstOrNull { it.name == v } } ?: default

    private fun blocking(label: String, block: suspend () -> Unit) {
        viewModelScope.launch(Dispatchers.Default) {
            _busy.value = label
            try { block() } finally { _busy.value = null }
        }
    }

    companion object {
        private const val TAG = "UltimatePDFScanner"
        private const val UNDO_MS = 11_000L // longer than the snackbar offering "undo" (SnackbarDuration.Long: 10 s)
        private const val CORNER_MODEL = "models/docaligner_fastvit_t8.onnx"
        private const val PADDLE_DET = "models/ch_PP-OCRv5_det_mobile.onnx"
        private const val PADDLE_REC = "models/latin_PP-OCRv5_rec_mobile.onnx"
        private const val PADDLE_CLS = "models/ch_ppocr_mobile_v2.0_cls_mobile.onnx"
        private const val HANDWRITING_MODEL = "models/dehtr2_uint8.onnx"
        private const val HANDWRITING_ALPHABET = "models/dehtr2_alphabet.txt"
        private const val FORMULA_LAYOUT = "models/pp_doclayout_s.onnx"
        private const val MAX_ATTEMPTS = 2 // tries of a photo that ended with the app (crash, hang); the next one marks it failed
        const val MAX_SIDE = 4096
        const val PDF_DPI = 200.0 // imported PDF pages: sharp enough for OCR, small enough to process quickly
        const val MAX_SIDE_ACCURATE = 8192 // 50 MP sensors: 8160 x 6120
        const val SLOW_MEGAPIXELS = 16 // above this "Maximal" warns: processing gets much slower and needs a lot of memory
        private val WHITESPACE = Regex("\\s+")
        private val DEFAULT_NAME = Regex("""Scan \d{2}\.\d{2}\.\d{4} \d{2}:\d{2}""")
        fun safeName(name: String) = name.replace(Regex("[^A-Za-z0-9äöüÄÖÜß _.-]"), "_").trim().ifEmpty { "scan" }
    }
}
