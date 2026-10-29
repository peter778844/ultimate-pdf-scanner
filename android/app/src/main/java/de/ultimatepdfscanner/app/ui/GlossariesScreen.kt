package de.ultimatepdfscanner.app.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.translate.Glossaries
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Special-term glossaries for the translation: load or import one file per language, remove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlossariesScreen(vm: ScanViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var installed by remember { mutableStateOf(Glossaries.installed(context)) }
    val loading = remember { mutableStateMapOf<String, Float>() }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                        ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    runCatching { Glossaries.import(context, uri, name) }
                }
            }
            installed = Glossaries.installed(context)
            results.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { snackbar.showSnackbar(it.message ?: tr("Datei unvollständig")) }
        }
    }

    fun download(lang: String) {
        loading[lang] = 0f
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { Glossaries.download(context, lang) { p -> loading[lang] = p } } }
            loading.remove(lang)
            installed = Glossaries.installed(context)
            ok.onFailure { snackbar.showSnackbar(tr("Download fehlgeschlagen: %s", it.message ?: tr("keine Verbindung"))) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Fachwörterbücher")) },
            navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
            actions = {
                IconButton(onClick = { pick.launch(arrayOf("*/*")) }) { Icon(Icons.Filled.FolderOpen, tr("Aus Datei importieren")) }
                HelpButton(HelpTopic.GLOSSARIES)
            })
    }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(tr("Fachbegriffe aus Recht, Medizin, Technik, Wirtschaft und 17 weiteren Fachgebieten (IATE, Terminologiedatenbank der EU) werden beim Übersetzen genau so übernommen. Nötig ist das Wörterbuch der Ausgangs- und der Zielsprache, je 0,3–9 MB.") +
                    " " + if (Glossaries.canDownload) tr("Einmal laden, danach offline.") else tr("Dateien glossary-xx.tsv.gz über das Ordner-Symbol oben importieren."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            items(Glossaries.LANGUAGES.entries.toList(), key = { it.key }) { (lang, label) ->
                val have = lang in installed
                val progress = loading[lang]
                ListItem(
                    headlineContent = { Text(tr(label)) },
                    supportingContent = {
                        Text(when {
                            progress != null -> tr("Wird geladen … %d %%", (progress * 100).toInt())
                            have -> tr("Geladen · %s", formatBytes(Glossaries.file(context, lang).length()))
                            else -> tr("Nicht geladen")
                        })
                    },
                    trailingContent = {
                        Box(Modifier.size(48.dp), Alignment.Center) {
                            when {
                                progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp))
                                have -> IconButton(onClick = { Glossaries.remove(context, lang); installed = Glossaries.installed(context) }) {
                                    Icon(Icons.Filled.Delete, tr("Entfernen"))
                                }
                                Glossaries.canDownload -> IconButton(onClick = { download(lang) }) {
                                    Icon(Icons.Filled.Download, tr("Laden"), tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}
