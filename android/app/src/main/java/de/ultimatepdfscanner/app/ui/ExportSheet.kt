package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.ContactPage
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.export.ExportFormat
import de.ultimatepdfscanner.app.export.ExportOptions
import de.ultimatepdfscanner.app.export.Compression
import de.ultimatepdfscanner.app.export.Resolution
import de.ultimatepdfscanner.app.export.PageSize
import de.ultimatepdfscanner.core.DocType
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material3.TextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** CREATE_DOCUMENT with the MIME type chosen at launch time (one launcher for every format). */
private class CreateTypedDocument : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>) =
        Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)
    override fun parseResult(resultCode: Int, intent: Intent?): Uri? = intent?.data
}

private fun icon(f: ExportFormat): ImageVector = when (f) {
    ExportFormat.PDF -> Icons.Filled.PictureAsPdf
    ExportFormat.DOCX -> Icons.Filled.Description
    ExportFormat.JPG -> Icons.Filled.Image
    ExportFormat.ZIP -> Icons.Filled.FolderZip
    ExportFormat.TXT -> Icons.AutoMirrored.Filled.TextSnippet
    ExportFormat.XLSX -> Icons.Filled.TableChart
    ExportFormat.CSV -> Icons.Filled.GridOn
    ExportFormat.VCF -> Icons.Filled.ContactPage
}

private fun describe(f: ExportFormat, pages: Int): String = when (f) {
    ExportFormat.PDF -> tr("Durchsuchbares PDF mit erkanntem Text")
    ExportFormat.DOCX -> tr("Bearbeitbarer Text für Word, LibreOffice und Google Docs")
    ExportFormat.JPG -> if (pages == 1) tr("Die Seite als JPG-Bild") else tr("Ein JPG-Bild pro Seite (%d Dateien)", pages)
    ExportFormat.ZIP -> tr("Alle Seiten als JPG und der Text in einem Archiv")
    ExportFormat.TXT -> tr("Nur der erkannte Text")
    ExportFormat.XLSX -> tr("Erkannte Tabellen, ein Tabellenblatt pro Seite")
    ExportFormat.CSV -> tr("Erkannte Tabellen als CSV (Semikolon, für Excel)")
    ExportFormat.VCF -> tr("Visitenkarte als Kontakt (vCard)")
}

/**
 * Export sheet: pick a format, adjust its few options, then share, save or print.
 * [pageIds] null = the whole document.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(vm: ScanViewModel, docId: String, pageIds: Collection<String>? = null, scope: kotlinx.coroutines.CoroutineScope, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // [scope] belongs to the screen below: the sheet closes before the share sheet / print dialog opens
    val settings by vm.settings.collectAsStateWithLifecycle()
    val doc = vm.doc(docId) ?: return
    val pages = pageIds?.size ?: doc.pages.size
    val isCard = doc.type == DocType.BUSINESS_CARD.name
    val isId = doc.type == DocType.ID_CARD.name
    val formats = ExportFormat.entries.filter { it != ExportFormat.VCF || isCard }
    var opts by remember {
        mutableStateOf(settings.export.copy(format = if (isCard) ExportFormat.VCF else if (isId) ExportFormat.PDF else settings.export.format, password = null,
            pageSize = if (isId) PageSize.A4 else settings.export.pageSize))
    }
    var protect by remember { mutableStateOf(false) }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    val pwValid = !protect || (pw.length >= 4 && pw == pw2)
    val final = opts.copy(password = if (protect && opts.format == ExportFormat.PDF) pw else null)
    val estimate by produceState<Long?>(null, opts.format, opts.resolution, opts.compression, opts.pageSize, opts.docxImages, pageIds) { value = vm.estimateSize(docId, opts, pageIds) }

    val save = rememberLauncherForActivityResult(CreateTypedDocument()) { uri -> uri?.let { vm.saveExport(docId, final, it, pageIds); onDismiss() } }
    val saveFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let { vm.saveJpegsToFolder(docId, final, it, pageIds); onDismiss() } }
    fun remember() { if (!isId && !isCard) vm.updateSettings(settings.copy(export = opts.copy(password = null))) }
    var choosingFolder by remember { mutableStateOf(false) }
    if (choosingFolder) StorageLocationDialog(vm, settings.exportFolder) { choosingFolder = false }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 16.dp)) {
            // the options scroll, the buttons stay in sight: below the fold they were taken for missing
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("Exportieren"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                HelpButton(HelpTopic.EXPORT)
            }
            Text(if (pageIds != null) trn(pages, "1 ausgewählte Seite", "%d ausgewählte Seiten") else "${doc.name} · " + trn(pages, "1 Seite", "%d Seiten"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(16.dp))

            formats.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { f -> FormatTile(f, opts.format == f, Modifier.weight(1f)) { opts = opts.copy(format = f) } }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Text(if (isId && opts.format == ExportFormat.PDF && opts.pageSize != PageSize.ORIGINAL) tr("Vorder- und Rückseite in Originalgröße auf einer Seite – fertig zum Ausdrucken")
                else describe(opts.format, pages), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.padding(vertical = 14.dp))

            // fixed minimum height: the format tiles above must not jump when the options change
            Column(Modifier.heightIn(min = 250.dp)) { when (opts.format) {
                ExportFormat.PDF -> {
                    OptionLabel(tr("Seitenformat"))
                    Segmented(PageSize.entries, opts.pageSize, { tr(it.label) }) { opts = opts.copy(pageSize = it) }
                    ImageOptions(opts, estimate) { opts = it }
                    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Lock, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(tr("Mit Passwort schützen"), style = MaterialTheme.typography.bodyLarge)
                            Text(tr("AES-256-Verschlüsselung"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(protect, { protect = it })
                    }
                    if (protect) {
                        val kb = KeyboardOptions(keyboardType = KeyboardType.Password)
                        OutlinedTextField(pw, { pw = it }, label = { Text(tr("Passwort")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = kb, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            supportingText = { if (pw.isNotEmpty() && pw.length < 4) Text(tr("Mindestens 4 Zeichen")) })
                        OutlinedTextField(pw2, { pw2 = it }, label = { Text(tr("Passwort wiederholen")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = kb, isError = pw2.isNotEmpty() && pw != pw2, modifier = Modifier.fillMaxWidth(),
                            supportingText = { if (pw2.isNotEmpty() && pw != pw2) Text(tr("Passwörter stimmen nicht überein")) })
                    }
                }
                ExportFormat.JPG, ExportFormat.ZIP -> ImageOptions(opts, estimate) { opts = it }
                ExportFormat.DOCX -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Seitenbilder einfügen"), style = MaterialTheme.typography.bodyLarge)
                        Text(tr("Jede Seite als Bild über ihrem Text") + (estimate?.let { " · ≈ ${formatBytes(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(opts.docxImages, { opts = opts.copy(docxImages = it) })
                }
                ExportFormat.XLSX, ExportFormat.CSV -> Text(tr("Spalten werden an den Abständen im Text erkannt – am besten klappt das mit klar getrennten Spalten. Zahlen wie „1.234,50“ werden als Zahlen übernommen."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ExportFormat.VCF -> ContactPreview(vm, docId)
                ExportFormat.TXT -> {}
            } }

            Spacer(Modifier.height(12.dp))
            StorageRow(vm, settings.exportFolder) { choosingFolder = true }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { remember(); onDismiss(); scope.launchShare(vm, context, docId, final, pageIds) }, enabled = pwValid,
                    modifier = Modifier.weight(1f).explain("Teilen", "Erstellt die Datei und schickt sie an eine App, z. B. WhatsApp, E-Mail, Telegram oder einen Cloud-Speicher.")) {
                    Icon(Icons.Filled.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Teilen"))
                }
                OutlinedButton(onClick = {
                    remember()
                    if (settings.exportFolder != null && vm.saveToExportFolder(docId, final, pageIds)) onDismiss()
                    else if (opts.format == ExportFormat.JPG && pages > 1) saveFolder.launch(null)
                    else save.launch(ScanViewModel.safeName(doc.name) + "." + opts.format.ext to opts.format.mime)
                }, enabled = pwValid, modifier = Modifier.weight(1f).explain("Speichern", "Legt die Datei am Speicherort ab – im festen Ordner oder, bei „Jedes Mal fragen“, dort, wo du sie hinlegst.")) {
                    Icon(Icons.Filled.Save, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Speichern"))
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = { remember(); onDismiss(); scope.launchPrint(vm, context, docId, opts, pageIds) },
                    modifier = Modifier.weight(1f).explain("Drucken", "Druckt über den Android-Druckdienst; dort lässt sich auch „Als PDF speichern“ wählen.")) {
                    Icon(Icons.Filled.Print, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Drucken"))
                }
                when (opts.format) {
                    ExportFormat.TXT -> OutlinedButton(onClick = {
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(doc.name, vm.text(docId)))
                        onDismiss()
                    }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.ContentCopy, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Kopieren")) }
                    ExportFormat.VCF -> OutlinedButton(onClick = { addToContacts(context, vm.contact(docId)); onDismiss() }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.PersonAdd, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Zu Kontakten"))
                    }
                    else -> Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun FormatTile(f: ExportFormat, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Surface(color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer, shape = shape,
        modifier = modifier.border(if (selected) 2.dp else 0.dp, if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .clip(shape).clickable(onClick = onClick)) {
        Column(Modifier.padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon(f), null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(tr(f.label), style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else null,
                textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

/** Where "Speichern" puts the file; tapping opens [StorageLocationDialog]. */
@Composable
private fun StorageRow(vm: ScanViewModel, tree: String?, onClick: () -> Unit) {
    // the folder name needs a storage query: read it off the UI thread
    val name by produceState<String?>(null, tree) { value = withContext(Dispatchers.IO) { vm.folderName(tree) } }
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium,
        modifier = Modifier.explain("Speicherort", "Wohin „Speichern“ die Dateien legt: jedes Mal fragen oder immer in einen festen Ordner, auch auf SD-Karte oder in einen Cloud-Ordner.")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(tr("Speicherort"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(name ?: tr("Jedes Mal fragen"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
            Text(tr("Ändern"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** Storage location for "Speichern": ask every time, the chosen folder, or pick another one (also SD card or cloud folders). */
@Composable
fun StorageLocationDialog(vm: ScanViewModel, tree: String?, onDismiss: () -> Unit) {
    val name by produceState<String?>(null, tree) { value = withContext(Dispatchers.IO) { vm.folderName(tree) } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let { runCatching { vm.setExportFolder(it) }.onFailure { vm.toast(tr("Auf diesen Ordner ist kein dauerhafter Zugriff möglich.")) } }
        onDismiss()
    }
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Filled.Folder, null) }, title = { Text(tr("Speicherort")) },
        text = {
            Column {
                Text(tr("Wohin „Speichern“ deine Exporte legt. „Teilen“ ist davon unabhängig."), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                LocationOption(tr("Jedes Mal fragen"), tr("Ordner und Dateiname beim Speichern wählen"), selected = tree == null) {
                    vm.setExportFolder(null); onDismiss()
                }
                if (tree != null) LocationOption(name ?: "…", tr("Exporte landen direkt hier"), selected = true) { onDismiss() }
                // start in the chosen folder or in "Documents": the storage root, where the picker opens by
                // default, cannot be chosen, so "ask every time" seemed impossible to switch off
                val start = tree?.let { android.net.Uri.parse(it) }
                    ?: android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")
                androidx.compose.material3.OutlinedButton(onClick = { picker.launch(start) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Anderen Ordner wählen …"))
                }
                Text(tr("Einen Unterordner wählen, z. B. „Documents“ – den obersten Ordner des Speichers lässt Android nicht zu.") + " " +
                    tr("Auch SD-Karte und Cloud-Ordner, sofern auf dem Gerät eingerichtet."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Schließen")) } })
}

@Composable
private fun LocationOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(start = 4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp, top = 4.dp))
}

/** One-of choice; [value] null (e.g. pages with different filters) selects nothing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Segmented(options: List<T>, value: T?, label: (T) -> String, onChange: (T) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, o ->
            SegmentedButton(selected = o == value, onClick = { onChange(o) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) { Text(label(o)) }
        }
    }
}

@Composable
private fun ImageOptions(opts: ExportOptions, estimate: Long?, onChange: (ExportOptions) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.Bottom) {
        OptionLabel(tr("Auflösung"))
        Spacer(Modifier.weight(1f))
        Text(estimate?.let { "≈ ${formatBytes(it)}" } ?: "…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 6.dp))
    }
    Segmented(Resolution.entries, opts.resolution, { tr(it.label) }) { onChange(opts.copy(resolution = it)) }
    OptionLabel(tr("Kompression"))
    Segmented(Compression.entries, opts.compression, { tr(it.label) }) { onChange(opts.copy(compression = it)) }
    Text(tr("300 dpi und geringe Kompression für Druck und Archiv, 150 dpi und starke Kompression für E-Mail."),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ContactPreview(vm: ScanViewModel, docId: String) {
    val c = remember(docId) { vm.contact(docId) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (c.isEmpty) Text(tr("Auf dieser Karte wurden keine Kontaktdaten erkannt."), style = MaterialTheme.typography.bodyMedium)
        c.name?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        listOfNotNull(c.title, c.company).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        c.phones.forEach { (t, n) -> Text(tr(mapOf("CELL" to "Mobil", "FAX" to "Fax").getOrDefault(t, "Telefon")) + ": $n", style = MaterialTheme.typography.bodySmall) }
        c.emails.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        c.web?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        listOfNotNull(c.street, listOfNotNull(c.postalCode, c.city).joinToString(" ").ifBlank { null }).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Opens the system "new contact" form, pre-filled; needs no contacts permission. */
fun addToContacts(context: Context, c: de.ultimatepdfscanner.core.Contact) {
    val i = Intent(android.provider.ContactsContract.Intents.Insert.ACTION).setType(android.provider.ContactsContract.RawContacts.CONTENT_TYPE)
    c.name?.let { i.putExtra(android.provider.ContactsContract.Intents.Insert.NAME, it) }
    c.company?.let { i.putExtra(android.provider.ContactsContract.Intents.Insert.COMPANY, it) }
    c.title?.let { i.putExtra(android.provider.ContactsContract.Intents.Insert.JOB_TITLE, it) }
    val phoneKeys = listOf(android.provider.ContactsContract.Intents.Insert.PHONE, android.provider.ContactsContract.Intents.Insert.SECONDARY_PHONE,
        android.provider.ContactsContract.Intents.Insert.TERTIARY_PHONE)
    val phoneTypes = listOf(android.provider.ContactsContract.Intents.Insert.PHONE_TYPE, android.provider.ContactsContract.Intents.Insert.SECONDARY_PHONE_TYPE,
        android.provider.ContactsContract.Intents.Insert.TERTIARY_PHONE_TYPE)
    c.phones.take(3).forEachIndexed { k, (type, num) ->
        i.putExtra(phoneKeys[k], num)
        i.putExtra(phoneTypes[k], when (type) {
            "CELL" -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            "FAX" -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
            else -> android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_WORK
        })
    }
    c.emails.firstOrNull()?.let { i.putExtra(android.provider.ContactsContract.Intents.Insert.EMAIL, it) }
    listOfNotNull(c.street, listOfNotNull(c.postalCode, c.city).joinToString(" ").ifBlank { null }).joinToString("\n").ifBlank { null }
        ?.let { i.putExtra(android.provider.ContactsContract.Intents.Insert.POSTAL, it) }
    c.web?.let { url ->
        val row = android.content.ContentValues().apply {
            put(android.provider.ContactsContract.Data.MIMETYPE, android.provider.ContactsContract.CommonDataKinds.Website.CONTENT_ITEM_TYPE)
            put(android.provider.ContactsContract.CommonDataKinds.Website.URL, url)
        }
        i.putParcelableArrayListExtra(android.provider.ContactsContract.Intents.Insert.DATA, arrayListOf(row))
    }
    context.startActivity(i)
}
