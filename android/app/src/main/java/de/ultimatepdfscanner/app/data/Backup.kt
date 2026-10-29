package de.ultimatepdfscanner.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Local backup as one ZIP file: every document folder (doc.json, page images, OCR words,
 * original photos), the folder list and the signature. Restoring adds documents that are not
 * on the device yet; existing ones are left alone, so restoring twice is harmless.
 */
object Backup {
    private const val MANIFEST = "scanpipe-backup.json"
    private const val VERSION = 1

    fun write(filesDir: File, out: OutputStream, includeTrash: Boolean, progress: (Int, Int) -> Unit) {
        val docsDir = File(filesDir, "docs")
        val storage = Storage(filesDir)
        val docs = storage.list().filter { includeTrash || !it.inTrash }
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(MANIFEST))
            zip.write(JSONObject().put("version", VERSION).put("created", System.currentTimeMillis()).put("documents", docs.size).toString().toByteArray())
            zip.closeEntry()
            for (name in listOf("folders.json", "signature.png")) {
                val f = File(filesDir, name)
                if (f.exists()) add(zip, f, name)
            }
            docs.forEachIndexed { i, d ->
                progress(i + 1, docs.size)
                File(docsDir, d.id).walkTopDown().filter { it.isFile && it.name != "text.txt" }.forEach { f ->
                    add(zip, f, "docs/" + f.relativeTo(docsDir).invariantSeparatorsPath)
                }
            }
        }
    }

    private fun add(zip: ZipOutputStream, f: File, path: String) {
        val entry = ZipEntry(path)
        // JPEGs do not shrink; storing them saves a lot of time on the phone
        if (f.extension == "jpg" || f.extension == "png") {
            entry.method = ZipEntry.STORED
            entry.size = f.length()
            entry.compressedSize = f.length()
            entry.crc = java.util.zip.CRC32().also { crc -> f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; crc.update(b, 0, n) } } }.value
        }
        zip.putNextEntry(entry)
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    data class Result(val added: Int, val skipped: Int)

    /** Throws if the file is not a Ultimate PDF Scanner backup. */
    fun restore(filesDir: File, input: InputStream): Result {
        val docsDir = File(filesDir, "docs").apply { mkdirs() }
        val existing = docsDir.list()?.toSet() ?: emptySet()
        val staging = File(filesDir, "restore-tmp").apply { deleteRecursively(); mkdirs() }
        var manifest = false
        try {
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val target = File(staging, e.name).canonicalFile
                    require(target.path.startsWith(staging.canonicalPath + File.separator)) { "Ungültiger Eintrag ${e.name}" } // zip slip
                    if (e.name == MANIFEST) manifest = true
                    if (e.isDirectory) target.mkdirs() else { target.parentFile?.mkdirs(); target.outputStream().use { zip.copyTo(it) } }
                }
            }
            require(manifest) { "Keine Ultimate-PDF-Scanner-Sicherung" }
            var added = 0
            var skipped = 0
            File(staging, "docs").listFiles()?.forEach { d ->
                if (d.name in existing || !File(d, "doc.json").exists()) skipped++
                else { d.copyRecursively(File(docsDir, d.name)); added++ }
            }
            // folders: union by id
            File(staging, "folders.json").takeIf { it.exists() }?.let { f ->
                val mine = File(filesDir, "folders.json").takeIf { it.exists() }?.let { JSONArray(it.readText()) } ?: JSONArray()
                val ids = (0 until mine.length()).map { mine.getJSONObject(it).getString("id") }.toSet()
                val theirs = JSONArray(f.readText())
                for (i in 0 until theirs.length()) theirs.getJSONObject(i).let { if (it.getString("id") !in ids) mine.put(it) }
                File(filesDir, "folders.json").writeText(mine.toString())
            }
            File(staging, "signature.png").takeIf { it.exists() && !File(filesDir, "signature.png").exists() }?.copyTo(File(filesDir, "signature.png"))
            return Result(added, skipped)
        } finally {
            staging.deleteRecursively()
        }
    }
}
