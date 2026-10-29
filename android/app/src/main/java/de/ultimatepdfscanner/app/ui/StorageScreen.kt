package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoSizeSelectLarge
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.Screen
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Categorical slots 1-4 of the validated reference palette (blue, orange, aqua, yellow), stepped per theme. */
private val SEGMENTS_LIGHT = listOf(Color(0xFF2A78D6), Color(0xFFEB6834), Color(0xFF1BAF7A), Color(0xFFEDA100), Color(0xFF8E5BD0))
private val SEGMENTS_DARK = listOf(Color(0xFF3987E5), Color(0xFFD95926), Color(0xFF199E70), Color(0xFFC98500), Color(0xFF9D6CE0))

fun formatBytes(b: Long): String = when {
    b >= 1_000_000_000 -> "%.1f GB".format(b / 1e9)
    b >= 1_000_000 -> "%.0f MB".format(b / 1e6)
    b >= 1_000 -> "%.0f kB".format(b / 1e3)
    else -> "$b B"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StorageScreen(vm: ScanViewModel) {
    val version by vm.version.collectAsStateWithLifecycle()
    val docs by vm.docs.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val stats by produceState<ScanViewModel.StorageStats?>(null, version, docs, busy) { value = vm.storageStats() }
    val snackbar = remember { SnackbarHostState() }
    var includeTrash by remember { mutableStateOf(false) }
    var confirmOriginals by remember { mutableStateOf(false) }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let { vm.backup(it, includeTrash) } }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.restore(it) } }
    MessageEffect(vm, snackbar)

    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Speicher & Sicherung")) },
            navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
            actions = { HelpButton(HelpTopic.STORAGE) })
    }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            val s = stats
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Column(Modifier.padding(20.dp)) {
                    Text(tr("Belegt von Ultimate PDF Scanner"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(s?.let { formatBytes(it.total + it.cache + it.languages) } ?: "…", style = MaterialTheme.typography.displaySmall)
                    s?.let { Text(tr("%s frei auf dem Gerät", formatBytes(it.free)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (s != null) {
                        val colors = if (isSystemInDarkTheme()) SEGMENTS_DARK else SEGMENTS_LIGHT
                        val parts = listOf(tr("Seiten") to s.pages - s.trash.coerceAtMost(s.pages), tr("Originalfotos") to s.originals, tr("Papierkorb") to s.trash, tr("Zwischenspeicher") to s.cache, tr("Sprachdaten") to s.languages)
                        Spacer(Modifier.height(16.dp))
                        UsageBar(parts.map { it.second }, colors)
                        Spacer(Modifier.height(12.dp))
                        parts.forEachIndexed { i, (label, bytes) ->
                            Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(colors[i]))
                                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp).weight(1f))
                                Text(formatBytes(bytes), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            Header(tr("Platz schaffen"))
            ListItem(headlineContent = { Text(tr("Zwischenspeicher leeren")) }, supportingContent = { Text(tr("Exportierte Dateien zum Teilen")) },
                leadingContent = { Icon(Icons.Filled.CleaningServices, null) },
                trailingContent = { TextButton(onClick = { vm.clearCache() }, enabled = (s?.cache ?: 0) > 0) { Text(tr("Leeren")) } })
            ListItem(headlineContent = { Text(tr("Papierkorb")) }, supportingContent = { Text(tr("Gelöschte Scans der letzten 30 Tage")) },
                leadingContent = { Icon(Icons.Filled.Delete, null) },
                trailingContent = { TextButton(onClick = { vm.navigate(Screen.Trash) }) { Text(tr("Öffnen")) } })
            ListItem(headlineContent = { Text(tr("Originalfotos entfernen")) },
                supportingContent = { Text(tr("Die fertigen Seiten bleiben erhalten, der Zuschnitt lässt sich danach nicht mehr ändern.")) },
                leadingContent = { Icon(Icons.Filled.PhotoSizeSelectLarge, null) },
                trailingContent = { TextButton(onClick = { confirmOriginals = true }, enabled = (s?.originals ?: 0) > 0) { Text(tr("Entfernen")) } })

            if (!s?.largest.isNullOrEmpty()) {
                Header(tr("Größte Scans"))
                s!!.largest.forEach { (doc, size) ->
                    ListItem(headlineContent = { Text(doc.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(trn(doc.pages.size, "1 Seite", "%d Seiten")) },
                        trailingContent = { Text(formatBytes(size), style = MaterialTheme.typography.bodyMedium) },
                        modifier = Modifier.padding(start = 40.dp))
                }
            }

            HorizontalDivider(Modifier.padding(top = 8.dp))
            Header(tr("Sicherung"))
            Text(tr("Alle Scans als eine ZIP-Datei sichern – z. B. auf eine SD-Karte, einen USB-Stick oder den PC. Nichts wird hochgeladen."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
            ListItem(headlineContent = { Text(tr("Papierkorb mitsichern")) }, trailingContent = { Switch(includeTrash, { includeTrash = it }) })
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                androidx.compose.material3.Button(onClick = {
                    backup.launch(tr("UltimatePDFScanner-Sicherung_") + SimpleDateFormat("yyyy-MM-dd", Locale.GERMANY).format(Date()) + ".zip")
                }, enabled = busy == null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Backup, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Sichern"))
                }
                androidx.compose.material3.OutlinedButton(onClick = { restore.launch(arrayOf("application/zip", "application/octet-stream")) },
                    enabled = busy == null, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Restore, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(tr("Wiederherstellen"))
                }
            }
            Text(tr("Beim Wiederherstellen werden nur Scans ergänzt, die noch fehlen."),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
    if (confirmOriginals) {
        AlertDialog(onDismissRequest = { confirmOriginals = false }, icon = { Icon(Icons.Filled.PhotoSizeSelectLarge, null) },
            title = { Text(tr("Originalfotos entfernen?")) },
            text = { Text(tr("Spart %s. Seiten lassen sich danach nicht mehr neu zuschneiden, „Erneut scannen“ bleibt möglich.", stats?.originals?.let(::formatBytes) ?: "")) },
            confirmButton = { TextButton(onClick = { vm.removeOriginals(); confirmOriginals = false }) { Text(tr("Entfernen")) } },
            dismissButton = { TextButton(onClick = { confirmOriginals = false }) { Text(tr("Abbrechen")) } })
    }
    BusyDialog(vm)
}

@Composable
private fun Header(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp))
}

/** Stacked usage bar: rounded ends, 2 dp surface gaps between segments. */
@Composable
private fun UsageBar(values: List<Long>, colors: List<Color>) {
    val total = values.sum().coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(4.dp)), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEachIndexed { i, v ->
            if (v > 0) Box(Modifier.weight(maxOf(v.toFloat() / total, 0.01f)).fillMaxHeight().background(colors[i]))
        }
    }
}
