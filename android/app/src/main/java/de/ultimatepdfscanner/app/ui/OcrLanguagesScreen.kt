package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
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
import androidx.compose.material3.Checkbox
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.ocr.OcrLanguages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Choose the OCR languages; missing ones are downloaded once and then work offline. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OcrLanguagesScreen(vm: ScanViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var installed by remember { mutableStateOf(OcrLanguages.installed(context)) }
    val loading = remember { mutableStateMapOf<String, Float>() }
    val active = settings.language.split('+').filter { it.isNotBlank() }.toSet()

    fun setActive(codes: Set<String>) {
        // keep the catalogue order; Tesseract tries the first language first
        val ordered = OcrLanguages.ALL.map { it.code }.filter { it in codes }
        if (ordered.isNotEmpty()) vm.updateSettings(settings.copy(language = ordered.joinToString("+")))
    }

    fun download(code: String) {
        loading[code] = 0f
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { OcrLanguages.download(context, code) { p -> loading[code] = p } }
            }
            loading.remove(code)
            ok.onSuccess { installed = OcrLanguages.installed(context); setActive(active + code) }
                .onFailure { snackbar.showSnackbar(tr("Download fehlgeschlagen: %s", it.message ?: tr("keine Verbindung"))) }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Sprachen der Texterkennung")) },
            navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
            actions = { HelpButton(HelpTopic.OCR_LANGUAGES) })
    }, snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text(tr("Gewählte Sprachen werden zusammen erkannt – am besten nur die nötigen wählen, jede weitere macht die Erkennung langsamer. ") +
                    tr("Fehlende Sprachen werden einmalig geladen (ca. 2–15 MB) und funktionieren danach offline."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            }
            items(OcrLanguages.ALL, key = { it.code }) { lang ->
                val have = lang.code in installed
                val progress = loading[lang.code]
                ListItem(
                    headlineContent = { Text(tr(lang.label)) },
                    supportingContent = {
                        Text(when {
                            progress != null -> tr("Wird geladen … %d %%", (progress * 100).toInt())
                            lang.code in OcrLanguages.BUNDLED -> tr("Enthalten")
                            have -> tr("Geladen · %s", formatBytes(OcrLanguages.size(context, lang.code)))
                            else -> tr("Nicht geladen")
                        })
                    },
                    leadingContent = {
                        Checkbox(checked = lang.code in active, enabled = have && progress == null,
                            onCheckedChange = { on -> setActive(if (on) active + lang.code else active - lang.code) })
                    },
                    trailingContent = {
                        Box(Modifier.size(48.dp), Alignment.Center) {
                            when {
                                progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp))
                                !have -> IconButton(onClick = { download(lang.code) }) { Icon(Icons.Filled.Download, tr("Laden"), tint = MaterialTheme.colorScheme.primary) }
                                lang.code !in OcrLanguages.BUNDLED -> IconButton(onClick = {
                                    OcrLanguages.remove(context, lang.code); installed = OcrLanguages.installed(context); setActive(active - lang.code)
                                }) { Icon(Icons.Filled.Delete, tr("Entfernen")) }
                            }
                        }
                    },
                )
            }
        }
    }
}
