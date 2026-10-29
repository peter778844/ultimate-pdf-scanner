package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.data.DocInfo
import de.ultimatepdfscanner.app.data.Storage
import kotlin.math.ceil

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(vm: ScanViewModel) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val trashed = docs.filter { it.inTrash }.sortedByDescending { it.deletedAt }
    var confirmEmpty by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<DocInfo?>(null) }
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Papierkorb")) },
            navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
            actions = { HelpButton(HelpTopic.TRASH); if (trashed.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text(tr("Leeren")) } })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text(tr("Gelöschte Scans werden nach %d Tagen endgültig entfernt.", Storage.TRASH_DAYS),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (trashed.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                        Icon(Icons.Filled.DeleteSweep, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(tr("Papierkorb ist leer"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                }
            }
            items(trashed, key = { it.id }) { doc ->
                Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(48.dp).height(64.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            doc.pages.firstOrNull()?.let { p -> rememberImage(vm.storage.pageFile(doc, p), 200, p.rotation, version) }?.let {
                                Image(it.asImageBitmap(), null, Modifier.fillMaxSize().alpha(0.6f), contentScale = ContentScale.Crop)
                            }
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text(doc.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val left = ceil(Storage.TRASH_DAYS - (System.currentTimeMillis() - (doc.deletedAt ?: 0)) / 86_400_000.0).toInt().coerceAtLeast(0)
                            Text(trn(doc.pages.size, "1 Seite", "%d Seiten") + " · " + trn(left, "noch 1 Tag", "noch %d Tage"), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.restore(listOf(doc.id)) }) { Icon(Icons.Filled.RestoreFromTrash, tr("Wiederherstellen"), tint = MaterialTheme.colorScheme.primary) }
                        IconButton(onClick = { deleting = doc }) { Icon(Icons.Filled.DeleteForever, tr("Endgültig löschen")) }
                    }
                }
            }
        }
    }
    if (confirmEmpty) {
        AlertDialog(onDismissRequest = { confirmEmpty = false }, icon = { Icon(Icons.Filled.DeleteSweep, null) },
            title = { Text(tr("Papierkorb leeren?")) }, text = { Text(tr("%d Scans werden endgültig gelöscht. Das lässt sich nicht rückgängig machen.", trashed.size)) },
            confirmButton = { TextButton(onClick = { vm.emptyTrash(); confirmEmpty = false }) { Text(tr("Endgültig löschen")) } },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(tr("Abbrechen")) } })
    }
    deleting?.let { doc ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(tr("Endgültig löschen?")) },
            text = { Text(tr("„%s“ wird sofort gelöscht.", doc.name)) },
            confirmButton = { TextButton(onClick = { vm.deleteForever(listOf(doc.id)); deleting = null }) { Text(tr("Löschen")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("Abbrechen")) } })
    }
}
