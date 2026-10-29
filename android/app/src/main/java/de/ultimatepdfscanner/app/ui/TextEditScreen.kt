package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.core.pageText

/** Correct the recognised text of a page while looking at the page (zoomable) above it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditScreen(vm: ScanViewModel, docId: String, pageId: String) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId } ?: return
    val page = doc.pages.firstOrNull { it.id == pageId } ?: run { vm.goBack(); return }
    val context = LocalContext.current
    val original = remember(pageId) { pageText(vm.storage.words(doc, page)) }
    var text by remember(pageId) { mutableStateOf(original) }
    var confirmLeave by remember { mutableStateOf(false) }
    val changed = text != original
    BackHandler(enabled = changed) { confirmLeave = true }

    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Text bearbeiten · Seite %d", doc.pages.indexOf(page) + 1)) },
            navigationIcon = { IconButton(onClick = { if (changed) confirmLeave = true else vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
            actions = {
                HelpButton(HelpTopic.TEXT)
                IconButton(onClick = {
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(tr("Seite"), text))
                }) { Icon(Icons.Filled.ContentCopy, tr("Kopieren")) }
                IconButton(onClick = { shareText(context, doc.name, text) }) { Icon(Icons.Filled.Share, tr("Teilen")) }
                TextButton(onClick = { vm.saveText(docId, pageId, text); vm.goBack() }, enabled = changed) {
                    Icon(Icons.Filled.Check, null); Text(tr("Sichern"), modifier = Modifier.padding(start = 4.dp))
                }
            })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            var scale by remember { mutableFloatStateOf(1f) }
            var offset by remember { mutableStateOf(Offset.Zero) }
            val zoom = rememberTransformableState { _, z, pan, _ ->
                scale = (scale * z).coerceIn(1f, 6f)
                offset = if (scale > 1f) offset + pan else Offset.Zero
            }
            Box(Modifier.fillMaxWidth().weight(0.42f).background(Brand.Editor).transformable(zoom)) {
                rememberImage(vm.storage.pageFile(doc, page), 2000, page.rotation)?.let {
                    Image(it.asImageBitmap(), tr("Seite"), Modifier.fillMaxSize().padding(8.dp)
                        .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y), contentScale = ContentScale.Fit)
                }
            }
            Text(tr("Korrekturen gehen in die Suche und in die Textebene des PDFs ein."), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            TextField(text, { text = it }, modifier = Modifier.fillMaxWidth().weight(0.58f),
                placeholder = { Text(tr("Kein Text erkannt – hier eintippen")) }, textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
        }
    }
    if (confirmLeave) {
        AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(tr("Änderungen sichern?")) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.saveText(docId, pageId, text); vm.goBack() }) { Text(tr("Sichern")) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; vm.goBack() }) { Text(tr("Verwerfen")) } })
    }
}
