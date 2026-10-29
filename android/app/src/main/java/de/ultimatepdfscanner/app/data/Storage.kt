package de.ultimatepdfscanner.app.data

import de.ultimatepdfscanner.core.Issue
import de.ultimatepdfscanner.core.Mode
import de.ultimatepdfscanner.core.PageResult
import de.ultimatepdfscanner.core.Pipeline
import de.ultimatepdfscanner.core.Quad
import de.ultimatepdfscanner.core.Redact
import de.ultimatepdfscanner.core.Redaction
import de.ultimatepdfscanner.core.Signature
import de.ultimatepdfscanner.core.SplitMode
import de.ultimatepdfscanner.core.Word
import org.json.JSONArray
import org.json.JSONObject
import org.opencv.core.MatOfInt
import org.opencv.core.Point
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.util.UUID

/** A photo as taken; its pages are derived from it and can be re-derived (e.g. after a manual crop). */
data class PhotoInfo(
    val id: String,
    val split: SplitMode,
    val focal: Double,
    val quad: List<Double>? = null, // corners tl,tr,br,bl as x0,y0,...; null until processed or if no page was found
    val manual: Boolean = false, // quad set by the user
    val processed: Boolean = false,
    val failed: Boolean = false, // processing threw; the photo has no pages and should be retaken
    val rtl: Boolean = false, // spread read right to left: the right page comes first
    val digital: Boolean = false, // screenshot, PDF page or other image not taken by a camera: no crop, dewarp or finger removal
) {
    fun quadPoints(): Quad? = quad?.chunked(2)?.map { Point(it[0], it[1]) }?.toTypedArray()
}

data class PageInfo(
    val id: String,
    val photoId: String,
    val mode: Mode,
    val rotation: Int = 0,
    val issues: List<Issue> = emptyList(), // quality problems found during processing
    val enhance: Boolean = true, // automatic correction (shadows, artefacts, text contrast); off: [mode] only converts colours
    val edited: Boolean = false, // redacted, signed or annotated: burnt into the page, gone if its photo is processed again
)

data class DocInfo(
    val id: String,
    val name: String,
    val created: Long,
    val pages: List<PageInfo>,
    val photos: List<PhotoInfo>,
    val type: String? = null, // DocType name once classified
    val folder: String? = null, // FolderInfo id
    val deletedAt: Long? = null, // in the trash since then
) {
    val pending get() = photos.count { !it.processed }
    val inTrash get() = deletedAt != null
}

data class FolderInfo(val id: String, val name: String)

/**
 * Documents on internal storage: docs/<docId>/doc.json; originals/<photoId>.jpg (kept for
 * re-cropping); per page <pageId>.jpg (rendered output), <pageId>_rect.jpg (cleaned colour
 * page for filter changes) and <pageId>.words.json (OCR result).
 */
class Storage(private val filesDir: File) {
    private val root = File(filesDir, "docs").apply { mkdirs() }

    fun dir(doc: DocInfo) = File(root, doc.id)
    fun originalsDir(doc: DocInfo) = File(dir(doc), "originals").apply { mkdirs() }
    fun original(doc: DocInfo, photoId: String) = File(originalsDir(doc), "$photoId.jpg")
    fun pageFile(doc: DocInfo, page: PageInfo) = File(dir(doc), "${page.id}.jpg")
    fun rectFile(doc: DocInfo, page: PageInfo) = File(dir(doc), "${page.id}_rect.jpg")
    private fun wordsFile(doc: DocInfo, page: PageInfo) = File(dir(doc), "${page.id}.words.json")

    fun list(): List<DocInfo> = root.listFiles()?.mapNotNull { load(it.name) }?.sortedByDescending { it.created } ?: emptyList()

    fun load(id: String): DocInfo? {
        val f = File(File(root, id), "doc.json")
        if (!f.exists()) return null
        return runCatching {
            val j = JSONObject(f.readText())
            val pages = j.getJSONArray("pages").objects().map {
                // the former "Original" filter is colour without automatic correction
                val mode = Mode.valueOf(it.getString("mode"))
                PageInfo(it.getString("id"), it.optString("photo"), if (mode == Mode.NONE) Mode.COLOR else mode, it.optInt("rotation"),
                    it.optJSONArray("issues")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { Issue.valueOf(a.getString(i)) }.getOrNull() } } ?: emptyList(),
                    enhance = mode != Mode.NONE && it.optBoolean("enhance", true), edited = it.optBoolean("edited"))
            }
            val photos = j.optJSONArray("photos")?.objects()?.map {
                PhotoInfo(
                    id = it.getString("id"),
                    split = SplitMode.valueOf(it.optString("split", "AUTO")),
                    focal = it.optDouble("focal", de.ultimatepdfscanner.core.Warp.DEFAULT_FOCAL),
                    quad = it.optJSONArray("quad")?.let { a -> (0 until a.length()).map { i -> a.getDouble(i) } },
                    manual = it.optBoolean("manual"),
                    processed = it.optBoolean("processed"),
                    failed = it.optBoolean("failed"),
                    rtl = it.optBoolean("rtl"),
                    digital = it.optBoolean("digital"),
                )
            } ?: emptyList()
            DocInfo(id, j.getString("name"), j.getLong("created"), pages, photos, j.optString("type").ifEmpty { null },
                j.optString("folder").ifEmpty { null }, if (j.has("deletedAt")) j.getLong("deletedAt") else null)
        }.getOrNull()
    }

    fun save(doc: DocInfo) {
        val pages = JSONArray().apply {
            doc.pages.forEach { p ->
                put(JSONObject().put("id", p.id).put("photo", p.photoId).put("mode", p.mode.name).put("rotation", p.rotation).put("enhance", p.enhance)
                    .apply { if (p.issues.isNotEmpty()) put("issues", JSONArray(p.issues.map { it.name })); if (p.edited) put("edited", true) })
            }
        }
        val photos = JSONArray().apply {
            doc.photos.forEach { p ->
                put(JSONObject().put("id", p.id).put("split", p.split.name).put("focal", p.focal).put("manual", p.manual)
                    .put("processed", p.processed).put("failed", p.failed).put("rtl", p.rtl).put("digital", p.digital).apply { p.quad?.let { q -> put("quad", JSONArray(q)) } })
            }
        }
        val j = JSONObject().put("name", doc.name).put("created", doc.created).put("pages", pages).put("photos", photos)
            .apply { doc.type?.let { put("type", it) }; doc.folder?.let { put("folder", it) }; doc.deletedAt?.let { put("deletedAt", it) } }
        dir(doc).mkdirs()
        val tmp = File(dir(doc), "doc.json.tmp")
        tmp.writeText(j.toString())
        tmp.renameTo(File(dir(doc), "doc.json")) // atomic replace: a crash never leaves a half-written index
    }

    fun create(name: String): DocInfo = DocInfo(UUID.randomUUID().toString(), name, System.currentTimeMillis(), emptyList(), emptyList()).also { save(it) }

    fun delete(doc: DocInfo) { dir(doc).deleteRecursively() }

    /** Register a new original photo (already written to [original]). */
    fun addPhoto(doc: DocInfo, photoId: String, split: SplitMode, focal: Double, rtl: Boolean = false): DocInfo =
        doc.copy(photos = doc.photos + PhotoInfo(photoId, split, focal, rtl = rtl)).also { save(it) }

    /** A digital page (screenshot, PDF page): the whole image is the page, as if the user had set the corners. */
    fun addDigitalPage(doc: DocInfo, photoId: String): DocInfo =
        doc.copy(photos = doc.photos + PhotoInfo(photoId, SplitMode.SINGLE, de.ultimatepdfscanner.core.Warp.DEFAULT_FOCAL,
            quad = listOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0, 0.0, 1.0), manual = true, digital = true)).also { save(it) }

    /**
     * Add a finished image as a page without running the document pipeline (e.g. passport
     * photos); the image is kept as its own "original".
     */
    fun addImagePage(doc: DocInfo, image: org.opencv.core.Mat): DocInfo {
        val photoId = newPhotoId()
        writeJpeg(original(doc, photoId), image, 95)
        val page = PageInfo(UUID.randomUUID().toString().take(8), photoId, Mode.COLOR, enhance = false)
        writeJpeg(rectFile(doc, page), image, 95)
        writeJpeg(pageFile(doc, page), image, 95)
        wordsFile(doc, page).writeText("[]")
        invalidateText(doc)
        return doc.copy(pages = doc.pages + page, photos = doc.photos + PhotoInfo(photoId, SplitMode.SINGLE, de.ultimatepdfscanner.core.Warp.DEFAULT_FOCAL, processed = true))
            .also { save(it) }
    }

    /** Swap the two pages of every spread photo (reading direction changed after the fact). */
    fun flipReadingOrder(doc: DocInfo): DocInfo {
        val pages = doc.pages.toMutableList()
        for (photo in doc.photos) {
            val idx = pages.indices.filter { pages[it].photoId == photo.id }
            if (idx.size == 2) { val a = pages[idx[0]]; pages[idx[0]] = pages[idx[1]]; pages[idx[1]] = a }
        }
        invalidateText(doc)
        return doc.copy(pages = pages, photos = doc.photos.map { it.copy(rtl = !it.rtl) }).also { save(it) }
    }

    fun newPhotoId(): String = "P" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(4)

    /**
     * Store the pages derived from a photo. Pages of the same photo from an earlier run are
     * replaced in place (keeping the document order); otherwise they are appended.
     */
    fun putPages(doc: DocInfo, photo: PhotoInfo, results: List<PageResult>, mode: Mode, enhance: Boolean, quad: Quad?, manual: Boolean): DocInfo {
        val old = doc.pages.filter { it.photoId == photo.id }
        old.forEach { deletePageFiles(doc, it) }
        // the pipeline already rendered r.image in `mode`; AUTO resolves to what it chose
        val ordered = if (photo.rtl && results.size == 2) results.reversed() else results
        val fresh = ordered.map { r ->
            val page = PageInfo(UUID.randomUUID().toString().take(8), photo.id, if (mode == Mode.AUTO) autoMode(r) else mode, issues = r.issues, enhance = enhance)
            writeJpeg(rectFile(doc, page), r.rectified, 92)
            writeJpeg(pageFile(doc, page), r.image, 90)
            wordsFile(doc, page).writeText(wordsToJson(r.words).toString())
            page
        }
        invalidateText(doc)
        val insertAt = doc.pages.indexOfFirst { it.photoId == photo.id }.let { if (it < 0) positionOf(doc, photo) else it }
        val remaining = doc.pages.filter { it.photoId != photo.id }.toMutableList()
        remaining.addAll(insertAt.coerceAtMost(remaining.size), fresh)
        val updated = photo.copy(processed = true, failed = false, manual = manual,
            quad = quad?.flatMap { listOf(it.x, it.y) })
        return doc.copy(pages = remaining, photos = doc.photos.map { if (it.id == photo.id) updated else it }).also { save(it) }
    }

    /** Where the pages of a photo without pages go: after the pages of the photos taken before it. */
    private fun positionOf(doc: DocInfo, photo: PhotoInfo): Int {
        val earlier = doc.photos.takeWhile { it.id != photo.id }.map { it.id }.toSet()
        return doc.pages.indexOfLast { it.photoId in earlier } + 1
    }

    /** Mark a photo whose processing failed; it stays in the document so it can be retaken. */
    fun markFailed(doc: DocInfo, photoId: String): DocInfo =
        doc.copy(photos = doc.photos.map { if (it.id == photoId) it.copy(processed = true, failed = true) else it }).also { save(it) }

    /**
     * Replace a photo by a retake (already written to [original] under [newId]). The old pages
     * stay as placeholders, re-assigned to the new photo, until its pages replace them in place.
     */
    fun retake(doc: DocInfo, oldId: String, newId: String, split: SplitMode, focal: Double): DocInfo {
        original(doc, oldId).delete()
        val photos = doc.photos.map { if (it.id == oldId) PhotoInfo(newId, split, focal) else it }
        val pages = doc.pages.map { if (it.photoId == oldId) it.copy(photoId = newId) else it }
        return doc.copy(photos = photos, pages = pages).also { save(it) }
    }

    private fun autoMode(r: PageResult) = if (r.image.channels() == 3) Mode.COLOR else Mode.GRAY

    fun words(doc: DocInfo, page: PageInfo): List<Word> {
        val f = wordsFile(doc, page)
        return if (f.exists()) runCatching { wordsFromJson(JSONArray(f.readText())) }.getOrDefault(emptyList()) else emptyList()
    }

    /** Re-render a page with another filter / automatic correction from its stored rectified image. */
    fun setMode(doc: DocInfo, page: PageInfo, mode: Mode, enhance: Boolean): DocInfo {
        val rect = Imgcodecs.imread(rectFile(doc, page).path, Imgcodecs.IMREAD_COLOR)
        val out = Pipeline.render(rect, mode, enhance)
        writeJpeg(pageFile(doc, page), out, 90)
        rect.release(); out.release()
        return doc.copy(pages = doc.pages.map { if (it.id == page.id) it.copy(mode = mode, enhance = enhance) else it }).also { save(it) }
    }

    /**
     * Burn redactions into the page and its rectified image (so later filter changes keep them)
     * and drop the covered words from the OCR result (so they vanish from the PDF text layer).
     * Regions are in page-image pixels.
     */
    fun redact(doc: DocInfo, page: PageInfo, regions: List<Redaction>, pixelate: Boolean): DocInfo {
        for (f in listOf(pageFile(doc, page), rectFile(doc, page))) {
            val img = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_UNCHANGED)
            if (img.empty()) continue
            Redact.apply(img, regions, pixelate)
            writeJpeg(f, img, if (f == pageFile(doc, page)) 90 else 92)
            img.release()
        }
        wordsFile(doc, page).writeText(wordsToJson(Redact.remaining(words(doc, page), regions)).toString())
        invalidateText(doc)
        return markEdited(doc, page)
    }

    fun markEdited(doc: DocInfo, page: PageInfo): DocInfo =
        doc.copy(pages = doc.pages.map { if (it.id == page.id) it.copy(edited = true) else it }).also { save(it) }

    /**
     * Blend a transparent signature (BGRA PNG) into the page and its rectified image at the
     * rectangle x0,y0,x1,y1 given relative to the image (0..1).
     */
    fun sign(doc: DocInfo, page: PageInfo, signature: File, rect: List<Double>, rotation: Int = 0): DocInfo {
        val loaded = Imgcodecs.imread(signature.path, Imgcodecs.IMREAD_UNCHANGED)
        if (loaded.empty() || loaded.channels() != 4) return doc
        // the page is shown turned by `rotation`; the stored image is not, so turn the signature back
        val sig = de.ultimatepdfscanner.core.Orientation.rotate(loaded, (360 - rotation) % 360)
        loaded.release()
        for (f in listOf(pageFile(doc, page), rectFile(doc, page))) {
            val img = Imgcodecs.imread(f.path, Imgcodecs.IMREAD_UNCHANGED)
            if (img.empty()) continue
            Signature.blend(img, sig, rect)
            writeJpeg(f, img, if (f == pageFile(doc, page)) 90 else 92)
            img.release()
        }
        sig.release()
        return markEdited(doc, page)
    }

    /** Burn annotations into the page (and its rectified image, so filter changes keep them). */
    fun annotate(doc: DocInfo, page: PageInfo, marks: List<Mark>): DocInfo {
        Marks.burn(pageFile(doc, page), marks, page.rotation, 90)
        Marks.burn(rectFile(doc, page), marks, page.rotation, 92)
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(pageFile(doc, page).path, bounds)
        val notes = Marks.noteWords(marks, page.rotation, bounds.outWidth, bounds.outHeight)
        if (notes.isNotEmpty()) setWords(doc, page, words(doc, page) + notes)
        return markEdited(doc, page)
    }

    fun rotate(doc: DocInfo, page: PageInfo, by: Int): DocInfo =
        doc.copy(pages = doc.pages.map { if (it.id == page.id) it.copy(rotation = ((it.rotation + by) % 360 + 360) % 360) else it }).also { save(it) }

    fun removePage(doc: DocInfo, page: PageInfo): DocInfo {
        deletePageFiles(doc, page)
        val pages = doc.pages.filter { it.id != page.id }
        // drop the original once none of its pages is left
        val photos = doc.photos.filter { ph -> !ph.processed || ph.failed || pages.any { it.photoId == ph.id } }
        doc.photos.filter { it !in photos }.forEach { original(doc, it.id).delete() }
        return doc.copy(pages = pages, photos = photos).also { save(it) }
    }

    /** Remove a photo with all its pages (e.g. a failed shot the user does not want to retake). */
    fun removePhoto(doc: DocInfo, photoId: String): DocInfo {
        doc.pages.filter { it.photoId == photoId }.forEach { deletePageFiles(doc, it) }
        original(doc, photoId).delete()
        return doc.copy(pages = doc.pages.filter { it.photoId != photoId }, photos = doc.photos.filter { it.id != photoId }).also { save(it) }
    }

    fun movePage(doc: DocInfo, page: PageInfo, to: Int): DocInfo {
        val pages = doc.pages.toMutableList()
        val i = pages.indexOfFirst { it.id == page.id }
        val j = to.coerceIn(0, pages.size - 1)
        if (i < 0 || i == j) return doc
        pages.add(j, pages.removeAt(i))
        return doc.copy(pages = pages).also { save(it) }
    }

    /** New page order (ids); pages missing from [ids] keep their relative order at the end. */
    fun reorder(doc: DocInfo, ids: List<String>): DocInfo {
        val byId = doc.pages.associateBy { it.id }
        val ordered = ids.mapNotNull { byId[it] } + doc.pages.filter { it.id !in ids }
        invalidateText(doc)
        return doc.copy(pages = ordered).also { save(it) }
    }

    fun rename(doc: DocInfo, name: String): DocInfo = doc.copy(name = name).also { save(it) }

    fun setFolder(doc: DocInfo, folder: String?): DocInfo = doc.copy(folder = folder).also { save(it) }

    fun setType(doc: DocInfo, type: String?): DocInfo = doc.copy(type = type).also { save(it) }

    fun setTrashed(doc: DocInfo, at: Long?): DocInfo = doc.copy(deletedAt = at).also { save(it) }

    /** Bytes used by a document on internal storage. */
    fun size(doc: DocInfo): Long = dir(doc).walkTopDown().filter { it.isFile }.sumOf { it.length() }

    private fun deletePageFiles(doc: DocInfo, page: PageInfo) {
        listOf(pageFile(doc, page), rectFile(doc, page), wordsFile(doc, page)).forEach { it.delete() }
        invalidateText(doc)
    }

    // --- full-text search -------------------------------------------------------------------

    private fun textFile(doc: DocInfo) = File(dir(doc), "text.txt")
    private fun invalidateText(doc: DocInfo) { textFile(doc).delete() }

    /** OCR text of all pages, pages separated by form feeds; cached in text.txt until the words change. */
    fun fullText(doc: DocInfo): String {
        val f = textFile(doc)
        if (f.exists()) return f.readText()
        return doc.pages.joinToString(PAGE_BREAK) { de.ultimatepdfscanner.core.pageText(words(doc, it)) }.also { if (doc.pending == 0) f.writeText(it) }
    }

    /** Replace a page's OCR words (text edits); keeps the PDF text layer and search in sync. */
    fun setWords(doc: DocInfo, page: PageInfo, words: List<Word>): DocInfo {
        wordsFile(doc, page).writeText(wordsToJson(words).toString())
        invalidateText(doc)
        return doc
    }

    // --- folders ----------------------------------------------------------------------------

    private val foldersFile = File(filesDir, "folders.json")

    fun folders(): List<FolderInfo> = runCatching {
        JSONArray(foldersFile.readText()).objects().map { FolderInfo(it.getString("id"), it.getString("name")) }
    }.getOrDefault(emptyList())

    fun saveFolders(list: List<FolderInfo>) {
        foldersFile.writeText(JSONArray().apply { list.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) } }.toString())
    }

    // --- moving pages between documents ------------------------------------------------------

    /**
     * Copy pages (with their files and original photos) from [from] into [to], appended in the
     * given order. New page ids, so copying within the same storage never collides.
     */
    fun copyPages(from: DocInfo, to: DocInfo, pages: List<PageInfo>): DocInfo {
        dir(to).mkdirs()
        val photos = to.photos.toMutableList()
        val added = pages.map { p ->
            val np = p.copy(id = UUID.randomUUID().toString().take(8))
            listOf(pageFile(from, p) to pageFile(to, np), rectFile(from, p) to rectFile(to, np), wordsFile(from, p) to wordsFile(to, np))
                .forEach { (a, b) -> if (a.exists()) a.copyTo(b, overwrite = true) }
            if (photos.none { it.id == p.photoId }) from.photos.firstOrNull { it.id == p.photoId }?.let { ph ->
                original(from, ph.id).takeIf { it.exists() }?.copyTo(original(to, ph.id), overwrite = true)
                photos += ph
            }
            np
        }
        invalidateText(to)
        return to.copy(pages = to.pages + added, photos = photos).also { save(it) }
    }

    private fun writeJpeg(f: File, m: org.opencv.core.Mat, quality: Int) {
        Imgcodecs.imwrite(f.path, m, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality))
    }

    companion object {
        const val PAGE_BREAK = "\u000C"
        const val TRASH_DAYS = 30

        private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

        fun wordsToJson(words: List<Word>) = JSONArray().apply {
            words.forEach { w ->
                put(JSONArray().put(w.text).put(w.conf.toDouble()).put(w.x0).put(w.y0).put(w.x1).put(w.y1)
                    .put(w.block).put(w.paragraph).put(w.line))
            }
        }

        fun wordsFromJson(a: JSONArray) = (0 until a.length()).map { i ->
            val w = a.getJSONArray(i)
            Word(w.getString(0), w.getDouble(1).toFloat(), w.getInt(2), w.getInt(3), w.getInt(4), w.getInt(5),
                w.getInt(6), w.getInt(7), w.getInt(8))
        }
    }
}
