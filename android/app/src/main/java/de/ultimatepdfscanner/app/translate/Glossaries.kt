package de.ultimatepdfscanner.app.translate

import android.content.Context
import android.net.Uri
import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.core.Glossary
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Special-term glossaries for the translator, one file per language (IATE, EU languages; built by
 * tools/build_glossaries.py). Loaded once on request or imported from a file, then offline.
 */
object Glossaries {
    /** Language tag -> German name; the 24 official EU languages. */
    val LANGUAGES = linkedMapOf("bg" to "Bulgarisch", "cs" to "Tschechisch", "da" to "Dänisch", "de" to "Deutsch", "el" to "Griechisch",
        "en" to "Englisch", "es" to "Spanisch", "et" to "Estnisch", "fi" to "Finnisch", "fr" to "Französisch", "ga" to "Irisch",
        "hr" to "Kroatisch", "hu" to "Ungarisch", "it" to "Italienisch", "lt" to "Litauisch", "lv" to "Lettisch", "mt" to "Maltesisch",
        "nl" to "Niederländisch", "pl" to "Polnisch", "pt" to "Portugiesisch", "ro" to "Rumänisch", "sk" to "Slowakisch",
        "sl" to "Slowenisch", "sv" to "Schwedisch")

    /** Download address of the files (glossary-<lang>.tsv.gz): the GitHub release of the project; null: only the import works. */
    private val BASE_URL: String? = "https://github.com/peter778844/ultimate-pdf-scanner/releases/download/glossaries-v1"

    val canDownload get() = BASE_URL != null

    private fun dir(context: Context) = File(context.filesDir, "glossaries").apply { mkdirs() }
    fun file(context: Context, lang: String) = File(dir(context), "glossary-$lang.tsv.gz")
    fun installed(context: Context): Set<String> = LANGUAGES.keys.filter { file(context, it).isFile }.toSet()

    /** Language of a glossary file name ("glossary-de.tsv.gz"), null for other files. */
    fun languageOf(name: String?): String? = name?.let { Regex("glossary-([a-z]{2})\\.tsv\\.gz").find(it) }?.groupValues?.get(1)?.takeIf { it in LANGUAGES }

    /** Copy a glossary file chosen by the user; returns its language. Throws if it is not one. */
    fun import(context: Context, uri: Uri, name: String?): String {
        val lang = languageOf(name) ?: error(tr("Keine Fachwörterbuch-Datei (glossary-xx.tsv.gz)"))
        val tmp = File(dir(context), "import.part")
        try {
            context.contentResolver.openInputStream(uri)!!.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            check(valid(tmp)) { tr("Keine Fachwörterbuch-Datei (glossary-xx.tsv.gz)") }
            check(tmp.renameTo(file(context, lang).also { it.delete() })) { tr("Datei unvollständig") }
        } finally { tmp.delete() }
        return lang
    }

    fun download(context: Context, lang: String, progress: (Float) -> Unit) {
        val base = BASE_URL ?: error(tr("Download nicht verfügbar"))
        val tmp = File(dir(context), "$lang.part")
        val conn = URL("$base/glossary-$lang.tsv.gz").openConnection() as HttpURLConnection
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
            check(valid(tmp) && tmp.renameTo(file(context, lang))) { tr("Datei unvollständig") }
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    /** A gzip file whose first line is "id <TAB> fields <TAB> term". */
    private fun valid(f: File) = runCatching {
        GZIPInputStream(f.inputStream()).bufferedReader().use { it.readLine()?.split('\t')?.size == 3 }
    }.getOrDefault(false)

    fun remove(context: Context, lang: String) { file(context, lang).delete() }

    /**
     * Glossary for translating [source] -> [target] in [fields], with only the terms made of the words
     * of [text] (lean in memory); null if one of the two files is missing.
     */
    fun load(context: Context, source: String, target: String, fields: Set<String>, text: String): Glossary? {
        val s = file(context, source).takeIf { it.isFile } ?: return null
        val t = file(context, target).takeIf { it.isFile } ?: return null
        val vocabulary = Regex("[\\p{L}\\p{N}]+").findAll(text).map { it.value.lowercase() }.toSet()
        return s.inputStream().use { si -> t.inputStream().use { ti -> Glossary.load(si, ti, fields, vocabulary) } }
    }
}
