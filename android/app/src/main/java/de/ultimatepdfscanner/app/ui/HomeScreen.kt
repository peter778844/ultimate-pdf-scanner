package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.Screen
import de.ultimatepdfscanner.app.SearchHit
import de.ultimatepdfscanner.app.data.DocInfo
import de.ultimatepdfscanner.app.data.FolderInfo
import de.ultimatepdfscanner.core.DocType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Library: all scans (or one folder's) with full-text search, category filter, folders and a
 * multi-selection for merging, moving and deleting.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(vm: ScanViewModel, folderId: String? = null) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val folders by vm.folders.collectAsStateWithLifecycle()
    val work by vm.work.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf(listOf<String>()) } // in tap order: the first one is the merge target
    var renaming by remember { mutableStateOf<DocInfo?>(null) }
    var moving by remember { mutableStateOf<List<String>?>(null) }
    var typing by remember { mutableStateOf<DocInfo?>(null) }
    var newFolder by remember { mutableStateOf(false) }
    var folderMenu by remember { mutableStateOf(false) }
    var renamingFolder by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var confirmMerge by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.importUris(null, it, folderId) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.importUris(null, it, folderId) }
    var importing by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    if (importing) ImportChoiceDialog(onGallery = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onFiles = { pickFiles.launch(arrayOf("application/pdf", "image/*")) }, onLink = { linking = true }, onDismiss = { importing = false })
    if (linking) LinkDialog(onLoad = { vm.importLink(null, it, folderId) }, onDismiss = { linking = false })
    val scroll = TopAppBarDefaults.pinnedScrollBehavior()
    MessageEffect(vm, snackbar)

    val folder = folderId?.let { id -> folders.firstOrNull { it.id == id } }
    val library = docs.filter { !it.inTrash && it.photos.isNotEmpty() }
    val here = library.filter { folderId == null || it.folder == folderId }
    val searching = query.isNotBlank()
    // debounce: the full-text search reads every document's text
    val hits by produceState<List<SearchHit>?>(null, query, version) {
        value = null
        if (query.isBlank()) return@produceState
        delay(200)
        value = vm.search(query)
    }
    val types = here.mapNotNull { it.type }.distinct().mapNotNull { t -> runCatching { DocType.valueOf(t) }.getOrNull() }.sortedBy { it.ordinal }
    val list: List<Pair<DocInfo, SearchHit?>> = when {
        searching -> hits.orEmpty().mapNotNull { h -> library.firstOrNull { it.id == h.docId }?.let { it to h } }
        else -> here.filter { category == null || it.type == category }.map { it to null }
    }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting || searching) { if (selecting) selected = emptyList() else query = "" }

    fun trash(ids: List<String>) {
        vm.trash(ids)
        selected = emptyList()
        scope.launch {
            val r = snackbar.showSnackbar(if (ids.size == 1) tr("In den Papierkorb verschoben") else tr("%d Scans in den Papierkorb verschoben", ids.size),
                actionLabel = tr("Rückgängig"), duration = SnackbarDuration.Long)
            if (r == SnackbarResult.ActionPerformed) vm.undoTrash()
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(tr("%d ausgewählt", selected.size)) },
                    navigationIcon = { IconButton(onClick = { selected = emptyList() }) { Icon(Icons.Filled.Close, tr("Auswahl beenden")) } },
                    actions = {
                        IconButton(onClick = { selected = list.map { it.first.id } }) { Icon(Icons.Filled.SelectAll, tr("Alle auswählen")) }
                        if (selected.size >= 2) IconButton(onClick = { confirmMerge = true }) { Icon(Icons.Filled.CallMerge, tr("Zusammenführen")) }
                        IconButton(onClick = { moving = selected }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, tr("In Ordner verschieben")) }
                        IconButton(onClick = { trash(selected) }) { Icon(Icons.Filled.Delete, tr("Löschen")) }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                )
            } else TopAppBar(
                title = { Text(folder?.name ?: "Ultimate PDF Scanner", fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (folder == null) MaterialTheme.colorScheme.primary else Color.Unspecified) },
                navigationIcon = { if (folderId != null) IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
                actions = {
                    if (folder != null) Box {
                        IconButton(onClick = { folderMenu = true }) { Icon(Icons.Filled.MoreVert, tr("Ordner")) }
                        DropdownMenu(folderMenu, { folderMenu = false }) {
                            DropdownMenuItem(text = { Text(tr("Ordner umbenennen")) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                                onClick = { folderMenu = false; renamingFolder = true })
                            DropdownMenuItem(text = { Text(tr("Ordner auflösen")) }, leadingIcon = { Icon(Icons.Filled.FolderOff, null) },
                                onClick = { folderMenu = false; vm.deleteFolder(folder.id); vm.goBack() })
                        }
                    } else Box {
                        Row {
                            HelpButton(HelpTopic.HOME)
                            IconButton(onClick = { vm.navigate(Screen.Settings) }, modifier = Modifier.explain("Einstellungen", "Sprache, Bildqualität, Auslösung, Texterkennung, Speicherort, App-Sperre und Sicherung.")) {
                                Icon(Icons.Filled.Settings, tr("Einstellungen"))
                            }
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Mehr")) }
                        }
                        DropdownMenu(menu, { menu = false }) {
                            val trashed = docs.count { it.inTrash }
                            DropdownMenuItem(text = { Text(if (trashed > 0) tr("Papierkorb (%d)", trashed) else tr("Papierkorb")) },
                                leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; vm.navigate(Screen.Trash) })
                            DropdownMenuItem(text = { Text(tr("An PC senden")) }, leadingIcon = { Icon(Icons.Filled.Computer, null) },
                                onClick = { menu = false; vm.navigate(Screen.SendToPc) })
                            DropdownMenuItem(text = { Text(tr("Speicher & Sicherung")) }, leadingIcon = { Icon(Icons.Filled.Storage, null) },
                                onClick = { menu = false; vm.navigate(Screen.StorageOverview) })
                        }
                    }
                },
                scrollBehavior = scroll,
            )
        },
        floatingActionButton = {
            if (!selecting) Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                SmallFloatingActionButton(onClick = { importing = true },
                    modifier = Modifier.explain("Importieren", "Übernimmt Fotos, Screenshots, PDF-Dateien oder einen Link als neues Dokument; Kamerafotos werden zugeschnitten, alles wird erkannt."),
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLowest, contentColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.Filled.PhotoLibrary, tr("Importieren"))
                }
                ExtendedFloatingActionButton(onClick = { vm.navigate(Screen.Camera(null, folderId = folderId)) },
                    modifier = Modifier.explain("Scannen", "Öffnet die Kamera. Alle Fotos einer Aufnahme kommen in ein gemeinsames Dokument."),
                    icon = { Icon(Icons.Filled.DocumentScanner, null) }, text = { Text(tr("Scannen")) },
                    containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 160.dp)) {
            if (library.isNotEmpty() || folderId != null) item(key = "search") {
                TextField(query, { query = it }, placeholder = { Text(if (folderId == null) tr("Scans und Text durchsuchen") else tr("Alle Scans durchsuchen")) },
                    singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (searching) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, tr("Suche löschen")) } },
                    shape = RoundedCornerShape(28.dp),
                    colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest, unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp)))
            }
            if (!searching && types.size > 1) item(key = "categories") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 6.dp)) {
                    item { FilterChip(category == null, { category = null }, label = { Text(tr("Alle")) }) }
                    items(types) { t ->
                        FilterChip(category == t.name, { category = if (category == t.name) null else t.name },
                            label = { Text("${tr(t.plural)} ${here.count { it.type == t.name }}") })
                    }
                }
            }
            if (!searching && folderId == null && category == null && library.isNotEmpty()) item(key = "folders") {
                Row(Modifier.fillMaxWidth().padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { SectionLabel(tr("Ordner")) }
                    TextButton(onClick = { newFolder = true }, modifier = Modifier.padding(top = 8.dp)) {
                        Icon(Icons.Filled.CreateNewFolder, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("Neuer Ordner"))
                    }
                }
                if (folders.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(folders, key = { it.id }) { f ->
                        FolderCard(f.name, library.count { it.folder == f.id }) { vm.navigate(Screen.Folder(f.id)) }
                    }
                }
            }
            if (list.isNotEmpty()) item(key = "label") {
                SectionLabel(when {
                    searching -> tr("%d Treffer", list.size)
                    folderId == null && category == null -> tr("Alle Scans")
                    category != null -> tr(DocType.valueOf(category!!).plural)
                    else -> trn(list.size, "1 Scan", "%d Scans")
                })
            }
            items(list, key = { it.first.id }) { (doc, hit) ->
                DocRow(vm, doc, hit, query, folders, work[doc.id]?.let { it.done to it.total }, version,
                    selected = doc.id in selected, selecting = selecting,
                    onOpen = {
                        if (selecting) selected = if (doc.id in selected) selected - doc.id else selected + doc.id
                        else vm.navigate(Screen.Document(doc.id))
                    },
                    onSelect = { selected = if (doc.id in selected) selected - doc.id else selected + doc.id },
                    onRename = { renaming = doc }, onMove = { moving = listOf(doc.id) }, onType = { typing = doc },
                    onDelete = { trash(listOf(doc.id)) }, onShare = { scope.launchShare(vm, context, doc.id) })
            }
            if (list.isEmpty()) item(key = "empty") {
                EmptyState(when {
                    searching && hits == null -> null
                    searching -> tr("Nichts gefunden") to tr("Weder ein Name noch ein erkannter Text passt zu „%s“.", query)
                    folderId != null -> tr("Ordner ist leer") to tr("Verschiebe Scans hierher: lange auf einen Scan tippen und „In Ordner“ wählen.")
                    else -> tr("Noch keine Scans") to tr("Tippe auf „Scannen“, um Seiten zu fotografieren,\noder importiere Fotos aus der Galerie.")
                })
            }
        }
    }

    renaming?.let { doc -> NameDialog(doc.name, onDone = { vm.rename(doc.id, it); renaming = null }, onCancel = { renaming = null }) }
    if (renamingFolder && folder != null) {
        NameDialog(folder.name, title = tr("Ordnername"), onDone = { vm.renameFolder(folder.id, it); renamingFolder = false }, onCancel = { renamingFolder = false })
    }
    if (newFolder) NameDialog("", title = tr("Neuer Ordner"), onDone = { if (it.isNotBlank()) vm.createFolder(it); newFolder = false }, onCancel = { newFolder = false })
    moving?.let { ids ->
        FolderPicker(vm, folders, current = docs.firstOrNull { it.id == ids.first() }?.folder,
            onPick = { vm.moveToFolder(ids, it); moving = null; selected = emptyList() }, onDismiss = { moving = null })
    }
    typing?.let { doc -> TypePicker(doc.type, onPick = { vm.setType(doc.id, it); typing = null }, onDismiss = { typing = null }) }
    if (confirmMerge) {
        val first = docs.firstOrNull { it.id == selected.first() }
        AlertDialog(onDismissRequest = { confirmMerge = false }, icon = { Icon(Icons.Filled.CallMerge, null) },
            title = { Text(tr("%d Scans zusammenführen?", selected.size)) },
            text = { Text(tr("Alle Seiten werden in der Auswahlreihenfolge an „%s“ angehängt. Die übrigen Scans entfallen danach.", first?.name)) },
            confirmButton = { TextButton(onClick = { vm.merge(selected); confirmMerge = false; selected = emptyList() }) { Text(tr("Zusammenführen")) } },
            dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text(tr("Abbrechen")) } })
    }
    BusyDialog(vm)
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp))
}

@Composable
private fun FolderCard(name: String, count: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLowest,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.width(160.dp)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            Column(Modifier.padding(start = 10.dp)) {
                Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(trn(count, "1 Scan", "%d Scans"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The query in bold inside the snippet. */
private fun highlight(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    val q = query.trim()
    var i = 0
    while (q.isNotEmpty()) {
        val at = text.indexOf(q, i, ignoreCase = true)
        if (at < 0) break
        append(text.substring(i, at))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = color)) { append(text.substring(at, at + q.length)) }
        i = at + q.length
    }
    append(text.substring(i))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocRow(vm: ScanViewModel, doc: DocInfo, hit: SearchHit?, query: String, folders: List<FolderInfo>, progress: Pair<Int, Int>?, version: Int,
                   selected: Boolean, selecting: Boolean, onOpen: () -> Unit, onSelect: () -> Unit,
                   onRename: () -> Unit, onMove: () -> Unit, onType: () -> Unit, onDelete: () -> Unit, onShare: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val border = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    Surface(shape = MaterialTheme.shapes.medium, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp)
            .border(if (selected) 2.dp else 1.dp, if (selected) border else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .clip(MaterialTheme.shapes.medium).combinedClickable(onClick = onOpen, onLongClick = onSelect)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val first = doc.pages.firstOrNull()
            Box(Modifier.width(60.dp).height(80.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)), contentAlignment = Alignment.Center) {
                val bmp = first?.let { rememberImage(vm.storage.pageFile(doc, it), 240, it.rotation, version) }
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(Icons.AutoMirrored.Filled.InsertDriveFile, null, tint = MaterialTheme.colorScheme.outline)
                if (selecting) Icon(if (selected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                    tint = if (selected) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(4.dp).size(22.dp).clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White else Color(0x66000000)))
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(doc.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(trn(doc.pages.size, "1 Seite", "%d Seiten") + " · " + de.ultimatepdfscanner.app.I18n.dateTime().format(Date(doc.created)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val folderName = doc.folder?.let { id -> folders.firstOrNull { it.id == id }?.name }
                val type = doc.type?.let { t -> runCatching { DocType.valueOf(t) }.getOrNull() }?.takeIf { it != DocType.DOCUMENT }
                val issues = doc.pages.count { it.issues.isNotEmpty() } + doc.photos.count { it.failed }
                if (type != null || folderName != null || issues > 0) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    type?.let { Tag(tr(it.label), MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer) }
                    folderName?.let { Tag(it, MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant, Icons.Filled.Folder) }
                    if (issues > 0 && doc.pending == 0) Tag(tr("%d prüfen", issues), Status.WarnContainer, Status.OnWarnContainer, Icons.Filled.WarningAmber)
                }
                hit?.snippet?.let {
                    Text(highlight(it, query, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                    Text(tr("Seite %d", hit.page), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                if (progress != null || doc.pending > 0) {
                    Spacer(Modifier.height(8.dp))
                    val (done, total) = progress ?: ((doc.photos.size - doc.pending) to doc.photos.size)
                    LinearProgressIndicator(progress = { if (total == 0) 0f else done / total.toFloat() }, modifier = Modifier.fillMaxWidth())
                    Text(tr("Wird verarbeitet (%d/%d)", done, total), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (!selecting) Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, tr("Mehr")) }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text(tr("Als PDF teilen")) }, leadingIcon = { Icon(Icons.Filled.Share, null) }, onClick = { menu = false; onShare() })
                    DropdownMenuItem(text = { Text(tr("Umbenennen")) }, leadingIcon = { Icon(Icons.Filled.Edit, null) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(tr("In Ordner verschieben")) }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text(tr("Kategorie ändern")) }, leadingIcon = { Icon(Icons.Filled.Label, null) }, onClick = { menu = false; onType() })
                    HorizontalDivider()
                    DropdownMenuItem(text = { Text(tr("Löschen")) }, leadingIcon = { Icon(Icons.Filled.Delete, null) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun Tag(text: String, bg: Color, fg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector? = null) {
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(bg).padding(horizontal = 7.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(12.dp).padding(end = 2.dp)) }
        Text(text, color = fg, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

/** [content] = title to text; null while a search is still running. */
@Composable
private fun EmptyState(content: Pair<String, String>?) {
    Box(Modifier.fillMaxWidth().padding(32.dp).padding(top = 48.dp), Alignment.Center) {
        if (content == null) return@Box
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(112.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                Icon(Icons.Filled.DocumentScanner, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text(content.first, style = MaterialTheme.typography.titleLarge)
            Text(content.second, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

/** Choose a folder (or none); a new folder can be created on the spot. */
@Composable
fun FolderPicker(vm: ScanViewModel, folders: List<FolderInfo>, current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        NameDialog("", title = tr("Neuer Ordner"), onDone = { if (it.isNotBlank()) onPick(vm.createFolder(it).id); creating = false }, onCancel = { creating = false })
        return
    }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.AutoMirrored.Filled.DriveFileMove, null) }, title = { Text(tr("In Ordner verschieben")) },
        text = {
            Column {
                PickerRow(Icons.Filled.FolderOff, tr("Kein Ordner"), current == null) { onPick(null) }
                folders.forEach { f -> PickerRow(Icons.Filled.Folder, f.name, current == f.id) { onPick(f.id) } }
                PickerRow(Icons.Filled.CreateNewFolder, tr("Neuer Ordner …"), false) { creating = true }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } })
}

@Composable
private fun TypePicker(current: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Filled.Label, null) }, title = { Text(tr("Kategorie")) },
        text = { Column { DocType.entries.forEach { t -> PickerRow(Icons.Filled.Label, tr(t.label), current == t.name) { onPick(t.name) } } } },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } })
}

@Composable
private fun PickerRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(label, fontWeight = if (selected) FontWeight.SemiBold else null) },
        leadingContent = { Icon(icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingContent = { if (selected) Icon(Icons.Filled.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
        colors = androidx.compose.material3.ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick))
}

@Composable
fun NameDialog(current: String, title: String = tr("Name"), onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(onDismissRequest = onCancel, title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text(tr("Abbrechen")) } })
}
