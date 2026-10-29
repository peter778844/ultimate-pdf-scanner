package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.Screen
import de.ultimatepdfscanner.app.data.DocInfo
import de.ultimatepdfscanner.app.data.PageInfo
import de.ultimatepdfscanner.app.data.PhotoInfo
import de.ultimatepdfscanner.core.Mode
import kotlinx.coroutines.launch

/** One tile of the overview: a finished page, or a photo that could not be processed. */
private sealed interface Tile {
    val key: String
    data class Page(val page: PageInfo, val number: Int, val processing: Boolean) : Tile { override val key get() = page.id }
    data class Failed(val photo: PhotoInfo) : Tile { override val key get() = "failed-" + photo.id }
}

/** Overview tiles in document order; failed photos sit where their pages would be. [hidden]: deleted, undo still possible. */
private fun tiles(doc: DocInfo, hidden: Set<String>): List<Tile> {
    val pending = doc.photos.filter { !it.processed }.map { it.id }.toSet()
    val out = ArrayList<Tile>()
    doc.pages.filter { it.id !in hidden }.forEachIndexed { i, p -> out += Tile.Page(p, i + 1, p.photoId in pending) }
    for (photo in doc.photos.filter { it.failed && doc.pages.none { p -> p.photoId == it.id } }) {
        val earlier = doc.photos.takeWhile { it.id != photo.id }.map { it.id }.toSet()
        val at = out.indexOfLast { it is Tile.Page && it.page.photoId in earlier } + 1
        out.add(at, Tile.Failed(photo))
    }
    return out
}

/** Pages that need a look: processing failed or quality problems found. */
private fun Tile.needsReview() = this is Tile.Failed || (this is Tile.Page && page.issues.isNotEmpty() && !processing)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DocumentScreen(vm: ScanViewModel, docId: String) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val work by vm.work.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val hidden by vm.hiddenPages.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId } ?: return
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var translating by remember { mutableStateOf(false) }
    var glossaryField by remember { mutableStateOf<String?>(null) } // null: none, "*": all fields
    var onlyProblems by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<String?>(null) } // key of the tile whose action menu is open
    var sheetFor by remember { mutableStateOf<Tile?>(null) } // "Weitere Optionen …": the full list of page actions
    var organizing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var transfer by remember { mutableStateOf<Boolean?>(null) } // true = move, false = copy
    var confirmDeletePages by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf<Collection<String>?>(null) } // page ids; empty = whole document
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.importUris(docId, it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importUris(docId, it) }
    var importing by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    if (importing) ImportChoiceDialog(onGallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onFiles = { pickFiles.launch(arrayOf("application/pdf", "image/*")) }, onLink = { linking = true }, onDismiss = { importing = false })
    if (linking) LinkDialog(onLoad = { vm.importLink(docId, it) }, onDismiss = { linking = false })
    MessageEffect(vm, snackbar)

    val all = tiles(doc, hidden)
    val problems = all.count { it.needsReview() }
    if (problems == 0) onlyProblems = false
    val shown = if (onlyProblems) all.filter { it.needsReview() } else all
    fun retake(photoId: String) = vm.navigate(Screen.Camera(docId, retakePhotoId = photoId))
    fun delete(tile: Tile) = when (tile) {
        is Tile.Failed -> vm.deletePhoto(docId, tile.photo.id)
        is Tile.Page -> {
            vm.deletePageUndoable(docId, tile.page)
            scope.launch {
                val r = snackbar.showSnackbar(tr("Seite gelöscht"), actionLabel = tr("Rückgängig"), duration = androidx.compose.material3.SnackbarDuration.Long)
                if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.undoDelete(tile.page.id)
            }
            Unit
        }
    }
    /** Long-press menu: retake (green), rotate left (blue), delete (red). */
    fun actionsFor(tile: Tile): List<MenuAction> {
        val photoId = when (tile) { is Tile.Page -> tile.page.photoId; is Tile.Failed -> tile.photo.id }
        val spread = doc.pages.count { it.photoId == photoId } > 1
        return listOf(
            MenuAction(tr("Neu aufnehmen"), Icons.Filled.CameraAlt, MenuGreen, subtitle = if (spread) tr("Ersetzt beide Seiten") else null) {
                menuFor = null; retake(photoId)
            },
            MenuAction(tr("Um 90° links drehen"), Icons.AutoMirrored.Filled.RotateLeft, MenuBlue, enabled = tile is Tile.Page) {
                menuFor = null; (tile as? Tile.Page)?.let { vm.rotatePage(docId, it.page, 270) }
            },
            MenuAction(tr("Löschen"), Icons.Filled.Delete, MenuRed) { menuFor = null; delete(tile) },
        )
    }
    androidx.activity.compose.BackHandler(enabled = organizing) { organizing = false; picked = emptySet() }
    fun transferTo(target: DocInfo?, move: Boolean) {
        val ids = doc.pages.filter { it.id in picked }.map { it.id }
        scope.launch {
            val id = vm.transferPages(docId, ids, target?.id, move) ?: return@launch
            picked = emptySet()
            val r = snackbar.showSnackbar(if (move) trn(ids.size, "1 Seite verschoben", "%d Seiten verschoben") else trn(ids.size, "1 Seite kopiert", "%d Seiten kopiert"),
                actionLabel = tr("Öffnen"), duration = androidx.compose.material3.SnackbarDuration.Long)
            if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) vm.navigate(Screen.Document(id))
        }
    }

    Scaffold(
        topBar = {
            if (organizing) TopAppBar(
                title = { Text(if (picked.isEmpty()) tr("Seiten ordnen") else tr("%d ausgewählt", picked.size)) },
                navigationIcon = { IconButton(onClick = { organizing = false; picked = emptySet() }) { Icon(Icons.Filled.Close, tr("Fertig")) } },
                actions = {
                    TextButton(onClick = { picked = if (picked.size == doc.pages.size) emptySet() else doc.pages.map { it.id }.toSet() }) {
                        Text(if (picked.size == doc.pages.size) tr("Keine") else tr("Alle"))
                    }
                    TextButton(onClick = { organizing = false; picked = emptySet() }) { Text(tr("Fertig")) }
                },
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) else TopAppBar(
                title = {
                    Column(Modifier.clickable { renaming = true }) {
                        Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(trn(doc.pages.count { it.id !in hidden }, "1 Seite", "%d Seiten"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
                actions = {
                    HelpButton(HelpTopic.DOCUMENT)
                    IconButton(onClick = { renaming = true }, modifier = Modifier.explain("Umbenennen", "Gibt dem Dokument einen eigenen Namen; er wird auch als Dateiname beim Export verwendet.")) {
                        Icon(Icons.Filled.Edit, tr("Umbenennen"))
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.explain("Mehr", "Leserichtung von Doppelseiten umkehren, auf dem Gerät übersetzen oder den Scan löschen.")) {
                            Icon(Icons.Filled.MoreVert, tr("Mehr"))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            if (doc.photos.any { ph -> doc.pages.count { it.photoId == ph.id } == 2 }) {
                                DropdownMenuItem(text = { Text(tr("Leserichtung umkehren")) }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) },
                                    onClick = { menu = false; vm.flipReadingOrder(docId) })
                            }
                            DropdownMenuItem(text = { Text(tr("Übersetzen…")) }, leadingIcon = { Icon(Icons.Filled.Translate, null) },
                                onClick = { menu = false; translating = true })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(tr("Scan löschen")) }, leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                onClick = { menu = false; confirmDelete = true })
                        }
                    }
                })
        },
        bottomBar = {
            if (organizing) BottomAppBar {
                OrganizeActions(picked.size, onRotate = { vm.rotatePages(docId, picked, 90) }, onMove = { transfer = true }, onCopy = { transfer = false },
                    onExtract = { transferTo(null, move = true) }, onDelete = { confirmDeletePages = true })
            } else BottomAppBar {
                BarAction(Icons.Filled.AddAPhoto, tr("Scannen"), Explanation("Scannen", "Öffnet die Kamera und hängt weitere Seiten an dieses Dokument an.")) { vm.navigate(Screen.Camera(docId)) }
                BarAction(Icons.Filled.PhotoLibrary, tr("Import"), Explanation("Import", "Übernimmt Fotos, Screenshots, PDF-Dateien oder einen Link als weitere Seiten; Kamerafotos werden zugeschnitten, alles wird erkannt.")) {
                    importing = true
                }
                BarAction(Icons.Filled.ViewModule, tr("Ordnen"), Explanation("Ordnen", "Reihenfolge per Ziehen ändern; Seiten auswählen, um sie zu drehen, zu verschieben, zu kopieren, herauszulösen oder zu löschen.")) { organizing = true }
                BarAction(Icons.Filled.IosShare, tr("Exportieren"), Explanation("Exportieren", "Als PDF, Word, Bilder, ZIP, Text oder Tabelle teilen, speichern oder drucken."), primary = true) { exporting = emptyList() }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        if (organizing) {
            OrganizeGrid(vm, doc, version, picked, onToggle = { id -> picked = if (id in picked) picked - id else picked + id }, Modifier.padding(pad).fillMaxSize())
            return@Scaffold
        }
        Column(Modifier.padding(pad).fillMaxSize()) {
        if (doc.pages.isNotEmpty()) ImageControls(
            mode = doc.pages.map { it.mode }.distinct().singleOrNull(),
            enhance = doc.pages.all { it.enhance },
            onMode = { vm.setAllModes(docId, mode = it) },
            onEnhance = { vm.setAllModes(docId, enhance = it) })
        LazyVerticalGrid(GridCells.Adaptive(140.dp), Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val w = work[docId]
            if (w != null || doc.pending > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    val done = w?.done ?: (doc.photos.size - doc.pending)
                    val total = w?.total ?: doc.photos.size
                    ProgressBanner(done, total, w?.step)
                }
            }
            if (doc.type == de.ultimatepdfscanner.core.DocType.BUSINESS_CARD.name && doc.pending == 0 && doc.pages.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { ContactBanner(vm, docId) }
            }
            if (problems > 0) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    ReviewBanner(problems, onlyProblems) { onlyProblems = it }
                }
            }
            items(shown, key = { it.key }) { tile ->
                val menu = if (menuFor == tile.key) actionsFor(tile) else null
                SlowLongPress {
                    when (tile) {
                        is Tile.Page -> PageTile(vm, doc, tile, version, menu, onOpen = { vm.navigate(Screen.Page(docId, tile.page.id)) },
                            onMenu = { menuFor = tile.key }, onDismissMenu = { menuFor = null }, onMore = { menuFor = null; sheetFor = tile })
                        is Tile.Failed -> FailedTile(vm, doc, tile.photo, menu, onRetake = { retake(tile.photo.id) },
                            onMenu = { menuFor = tile.key }, onDismissMenu = { menuFor = null }, onMore = { menuFor = null; sheetFor = tile })
                    }
                }
            }
            items(doc.photos.count { !it.processed && doc.pages.none { p -> p.photoId == it.id } }) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.71f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        }
        }
    }

    transfer?.let { move ->
        TargetDocPicker(vm, docs, exclude = docId, move = move, onPick = { transfer = null; transferTo(it, move) }, onDismiss = { transfer = null })
    }
    if (confirmDeletePages) {
        AlertDialog(onDismissRequest = { confirmDeletePages = false }, title = { Text(trn(picked.size, "1 Seite löschen?", "%d Seiten löschen?")) },
            confirmButton = { TextButton(onClick = { vm.deletePages(docId, picked); picked = emptySet(); confirmDeletePages = false }) { Text(tr("Löschen")) } },
            dismissButton = { TextButton(onClick = { confirmDeletePages = false }) { Text(tr("Abbrechen")) } })
    }
    sheetFor?.let { tile ->
        TileActions(vm, doc, tile, version, onDismiss = { sheetFor = null }, onRetake = { id -> sheetFor = null; retake(id) },
            onDelete = { sheetFor = null; delete(tile) })
    }
    exporting?.let { ids -> ExportSheet(vm, docId, ids.ifEmpty { null }, scope, onDismiss = { exporting = null }) }
    if (translating) {
        AlertDialog(onDismissRequest = { translating = false }, icon = { Icon(Icons.Filled.Translate, null) },
            title = { Text(tr("Übersetzen in …")) },
            text = {
                Column {
                    Text(tr("Die Übersetzung läuft auf dem Gerät. Beim ersten Mal wird das Sprachmodell einmalig geladen."),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 8.dp))
                    GlossaryFieldChoice(glossaryField, onPick = { glossaryField = it }, onManage = { translating = false; vm.navigate(Screen.Glossaries) })
                    Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState())) { // all OCR languages ML Kit translates
                        de.ultimatepdfscanner.app.translate.OfflineTranslator.TARGETS.forEach { (code, label) ->
                            TextButton(onClick = {
                                translating = false
                                val fields = when (glossaryField) { null -> emptySet(); "*" -> de.ultimatepdfscanner.core.Glossary.FIELDS.keys; else -> setOf(glossaryField!!) }
                                scope.launch { vm.translateDocument(docId, code, fields)?.let { vm.navigate(Screen.Document(it)) } }
                            }, modifier = Modifier.fillMaxWidth()) { Text(tr(label)) }
                        }
                    }
                }
            },
            confirmButton = {}, dismissButton = { TextButton(onClick = { translating = false }) { Text(tr("Abbrechen")) } })
    }
    if (renaming) NameDialog(doc.name, onDone = { vm.rename(docId, it); renaming = false }, onCancel = { renaming = false })
    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(tr("Scan löschen?")) },
            text = { Text(tr("„%s“ wird endgültig gelöscht.", doc.name)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deleteDocument(docId); vm.goBack() }) { Text(tr("Löschen")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Abbrechen")) } })
    }
    BusyDialog(vm)
}

/** Special field of the translation: its terms come from the glossaries (IATE). */
@Composable
private fun GlossaryFieldChoice(field: String?, onPick: (String?) -> Unit, onManage: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    fun label(f: String?) = when (f) { null -> tr("Ohne Fachwörterbuch"); "*" -> tr("Alle Fachgebiete"); else -> tr(de.ultimatepdfscanner.core.Glossary.FIELDS.getValue(f)) }
    Box(Modifier.padding(bottom = 8.dp)) {
        TextButton(onClick = { open = true }) { Text(tr("Fachgebiet: %s", label(field)) + " ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            (listOf(null, "*") + de.ultimatepdfscanner.core.Glossary.FIELDS.keys).forEach { f ->
                DropdownMenuItem(text = { Text(label(f)) }, onClick = { open = false; onPick(f) })
            }
            DropdownMenuItem(text = { Text(tr("Fachwörterbücher verwalten …")) }, onClick = { open = false; onManage() })
        }
    }
}

@Composable
private fun RowScope.BarAction(icon: ImageVector, label: String, explanation: Explanation, primary: Boolean = false, onClick: () -> Unit) {
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).explain(explanation.title, explanation.text).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        if (primary) Box(Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 18.dp, vertical = 4.dp)) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimary)
        } else Icon(icon, null, Modifier.padding(vertical = 4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, fontWeight = if (primary) FontWeight.SemiBold else null)
    }
}

/**
 * Filter and automatic correction for the whole document. [mode] null: the pages have
 * different filters (set one by one in the page view), so no segment is selected.
 */
@Composable
private fun ImageControls(mode: Mode?, enhance: Boolean, onMode: (Mode) -> Unit, onEnhance: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest) { Column {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
            Box(Modifier.explain("Farbe, Graustufen, Schwarz-Weiß", "Stellt alle Seiten dieses Dokuments um. Schwarz-Weiß ergibt die kleinsten Dateien, Farbe gibt Abbildungen und Stempel originalgetreu wieder.")) {
                Segmented(IMAGE_MODES, mode, ::modeLabel, onMode)
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .explain("Automatische Verbesserung", "An: Schatten, Flecken und Artefakte werden entfernt, die Schrift wird kontrastreicher. Aus: Die Seiten werden nur zugeschnitten und entzerrt – für Fotos, Zeichnungen oder wenn die Verbesserung etwas verfälscht.")
                .clickable { onEnhance(!enhance) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoFixHigh, null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(tr("Automatische Verbesserung"), style = MaterialTheme.typography.bodyLarge)
                    Text(tr("Schatten und Artefakte entfernen, Schrift schärfen"), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(enhance, onEnhance)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    } }
}

/** Business cards: the recognised contact with a one-tap "add to contacts". */
@Composable
private fun ContactBanner(vm: ScanViewModel, docId: String) {
    val context = LocalContext.current
    val version by vm.version.collectAsStateWithLifecycle()
    val c = remember(docId, version) { vm.contact(docId) }
    if (c.isEmpty) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Person, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(c.name ?: c.company ?: tr("Kontakt erkannt"), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(c.name?.let { c.company }, c.phones.firstOrNull()?.second, c.emails.firstOrNull()).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FilledTonalButton(onClick = { addToContacts(context, c) }) { Text(tr("Zu Kontakten")) }
        }
    }
}

@Composable
private fun ProgressBanner(done: Int, total: Int, step: String?) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Text(tr("Seiten werden verarbeitet (%d/%d)", done, total), style = MaterialTheme.typography.titleSmall)
            if (!step.isNullOrBlank()) Text(step, style = MaterialTheme.typography.bodySmall)
            LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

/** Summary of pages that should be checked, with a filter to show only those. */
@Composable
private fun ReviewBanner(count: Int, onlyProblems: Boolean, onFilter: (Boolean) -> Unit) {
    Surface(color = Status.WarnContainer, contentColor = Status.OnWarnContainer, shape = MaterialTheme.shapes.medium) {
        Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.WarningAmber, null, tint = Status.Warn)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(trn(count, "1 Seite prüfen", "%d Seiten prüfen"), style = MaterialTheme.typography.titleSmall)
                Text(tr("Lange drücken: neu aufnehmen, drehen oder löschen"), style = MaterialTheme.typography.bodySmall)
            }
            FilterChip(selected = onlyProblems, onClick = { onFilter(!onlyProblems) }, label = { Text(tr("Nur diese")) })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PageTile(vm: ScanViewModel, doc: DocInfo, tile: Tile.Page, version: Int, menu: List<MenuAction>?,
                     onOpen: () -> Unit, onMenu: () -> Unit, onDismissMenu: () -> Unit, onMore: () -> Unit) {
    val page = tile.page
    val flagged = page.issues.isNotEmpty() && !tile.processing
    var size by remember { mutableStateOf(IntSize.Zero) }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(0.71f).onSizeChanged { size = it }.clip(RoundedCornerShape(10.dp)).background(Color.White)
            .border(if (flagged) 2.dp else 1.dp, if (flagged) Status.Warn else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onOpen, onLongClick = onMenu)) {
            menu?.let { PageActionMenu(size, it, onDismissMenu, onMore) }
            val bmp = rememberImage(vm.storage.pageFile(doc, page), 420, page.rotation, version)
            bmp?.let { Image(it.asImageBitmap(), tr("Seite %d", tile.number), Modifier.fillMaxSize().padding(2.dp).alpha(if (tile.processing) 0.35f else 1f), contentScale = ContentScale.Fit) }
            if (tile.processing) CircularProgressIndicator(Modifier.size(28.dp).align(Alignment.Center))
            NumberBadge(tile.number, Modifier.align(Alignment.BottomStart))
            if (flagged) StatusDot(Icons.Filled.WarningAmber, Status.Warn, Modifier.align(Alignment.TopEnd))
        }
        Text(if (flagged) tr(page.issues.first().label) else if (tile.processing) tr("Wird neu verarbeitet") else modeLabel(page.mode, page.enhance),
            style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = if (flagged) Status.Warn else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (flagged) FontWeight.SemiBold else null, modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

/** A photo whose processing failed: original dimmed, red marker, direct retake. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FailedTile(vm: ScanViewModel, doc: DocInfo, photo: PhotoInfo, menu: List<MenuAction>?,
                       onRetake: () -> Unit, onMenu: () -> Unit, onDismissMenu: () -> Unit, onMore: () -> Unit) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(0.71f).onSizeChanged { size = it }.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, Status.Error, RoundedCornerShape(10.dp)).combinedClickable(onClick = onRetake, onLongClick = onMenu)) {
            menu?.let { PageActionMenu(size, it, onDismissMenu, onMore) }
            rememberImage(vm.storage.original(doc, photo.id), 420)?.let {
                Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(0.3f), contentScale = ContentScale.Crop)
            }
            Column(Modifier.align(Alignment.Center).padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.ErrorOutline, null, tint = Status.Error, modifier = Modifier.size(32.dp))
                FilledTonalButton(onClick = onRetake, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
                    Icon(Icons.Filled.CameraAlt, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("Erneut"), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        Text(tr("Fehlgeschlagen"), style = MaterialTheme.typography.labelSmall, color = Status.Error, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 4.dp, start = 2.dp))
    }
}

@Composable
private fun NumberBadge(n: Int, modifier: Modifier) {
    Box(modifier.padding(6.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f))
        .padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text("$n", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun StatusDot(icon: ImageVector, color: Color, modifier: Modifier) {
    Box(modifier.padding(6.dp).size(26.dp).clip(CircleShape).background(color), Alignment.Center) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** "Weitere Optionen …": what is wrong with the page and everything that can be done with it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TileActions(vm: ScanViewModel, doc: DocInfo, tile: Tile, version: Int, onDismiss: () -> Unit, onRetake: (String) -> Unit, onDelete: () -> Unit) {
    val photoId = when (tile) { is Tile.Page -> tile.page.photoId; is Tile.Failed -> tile.photo.id }
    val siblings = doc.pages.count { it.photoId == photoId }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val file = when (tile) { is Tile.Page -> vm.storage.pageFile(doc, tile.page); is Tile.Failed -> vm.storage.original(doc, tile.photo.id) }
                val rotation = (tile as? Tile.Page)?.page?.rotation ?: 0
                Box(Modifier.width(54.dp).height(72.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    rememberImage(file, 200, rotation, version)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Column(Modifier.padding(start = 16.dp)) {
                    Text(if (tile is Tile.Page) tr("Seite %d", tile.number) else tr("Foto nicht verarbeitet"), style = MaterialTheme.typography.titleMedium)
                    val issues = (tile as? Tile.Page)?.page?.issues.orEmpty()
                    when {
                        tile is Tile.Failed -> Text(tr("Bei der Bildverarbeitung ist ein Fehler aufgetreten."), style = MaterialTheme.typography.bodySmall, color = Status.Error)
                        issues.isEmpty() -> Text(tr("Keine Auffälligkeiten"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> issues.forEach { Text("• " + tr(it.hint), style = MaterialTheme.typography.bodySmall, color = Status.Warn) }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = 12.dp))
            SheetAction(Icons.Filled.CameraAlt, tr("Erneut scannen"),
                if (siblings > 1) tr("Ersetzt beide Seiten dieser Doppelseite") else tr("Kamera öffnen und diese Seite ersetzen"), highlight = true) { onRetake(photoId) }
            if (tile is Tile.Page) {
                val page = tile.page
                val index = doc.pages.indexOfFirst { it.id == page.id }
                SheetAction(Icons.Filled.Crop, tr("Zuschnitt anpassen")) { onDismiss(); vm.navigate(Screen.Crop(doc.id, page.id)) }
                SheetAction(Icons.AutoMirrored.Filled.RotateLeft, tr("Um 90° links drehen")) { vm.rotatePage(doc.id, page, 270) }
                if (index > 0) SheetAction(Icons.AutoMirrored.Filled.ArrowBack, tr("Nach vorn")) { onDismiss(); vm.movePage(doc.id, page, index - 1) }
                if (index in 0 until doc.pages.size - 1) SheetAction(Icons.AutoMirrored.Filled.ArrowForward, tr("Nach hinten")) { onDismiss(); vm.movePage(doc.id, page, index + 1) }
                SheetAction(Icons.Filled.Delete, tr("Seite löschen")) { onDelete() }
            } else {
                SheetAction(Icons.Filled.Delete, tr("Foto entfernen")) { onDelete() }
            }
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, title: String, subtitle: String? = null, highlight: Boolean = false, onClick: () -> Unit) {
    androidx.compose.material3.ListItem(
        headlineContent = { Text(title, fontWeight = if (highlight) FontWeight.SemiBold else null) },
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, null, tint = if (highlight) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 4.dp),
    )
}
