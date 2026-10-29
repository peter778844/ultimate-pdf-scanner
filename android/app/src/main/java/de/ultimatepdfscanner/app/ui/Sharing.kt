package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.export.ExportFormat
import de.ultimatepdfscanner.app.export.ExportOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

private fun Context.uriFor(file: File): Uri = FileProvider.getUriForFile(this, packageName + ".files", file)

/** Android share sheet for one or several exported files. */
fun shareFiles(context: Context, files: List<File>, mime: String, title: String) {
    if (files.isEmpty()) return
    val intent = if (files.size == 1) Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, context.uriFor(files[0])) }
    else Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(files.map { context.uriFor(it) })) }
    intent.type = mime
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, title))
}

/** Export and open the share sheet. */
fun CoroutineScope.launchShare(vm: ScanViewModel, context: Context, docId: String, opts: ExportOptions = ExportOptions(), pageIds: Collection<String>? = null) = launch {
    val files = vm.exportFiles(docId, opts, pageIds)
    shareFiles(context, files, opts.format.mime, tr("%s teilen", tr(opts.format.label)))
}

/** Export as PDF and hand it to the Android print dialog (printers, "Als PDF speichern"). */
fun CoroutineScope.launchPrint(vm: ScanViewModel, context: Context, docId: String, opts: ExportOptions, pageIds: Collection<String>? = null) = launch {
    val pdf = vm.exportFiles(docId, opts.copy(format = ExportFormat.PDF, password = null), pageIds).firstOrNull() ?: return@launch
    val name = vm.doc(docId)?.name ?: tr("Scan")
    (context.getSystemService(Context.PRINT_SERVICE) as PrintManager).print(name, PdfPrintAdapter(pdf, name), PrintAttributes.Builder().build())
}

private class PdfPrintAdapter(private val file: File, private val name: String) : PrintDocumentAdapter() {
    override fun onLayout(old: PrintAttributes?, new: PrintAttributes, cancel: CancellationSignal, callback: LayoutResultCallback, extras: Bundle?) {
        if (cancel.isCanceled) { callback.onLayoutCancelled(); return }
        callback.onLayoutFinished(PrintDocumentInfo.Builder("$name.pdf").setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), old != new)
    }

    override fun onWrite(pages: Array<out PageRange>, destination: ParcelFileDescriptor, cancel: CancellationSignal, callback: WriteResultCallback) {
        try {
            file.inputStream().use { input -> ParcelFileDescriptor.AutoCloseOutputStream(destination).use { input.copyTo(it) } }
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (e: Exception) {
            callback.onWriteFailed(e.message)
        }
    }
}

fun shareText(context: Context, subject: String, text: String) {
    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }, tr("Text teilen")))
}

fun sharePdf(context: Context, file: File, title: String = tr("PDF teilen")) = shareFiles(context, listOf(file), "application/pdf", title)
