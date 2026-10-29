package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.AppLock
import de.ultimatepdfscanner.app.BuildConfig
import de.ultimatepdfscanner.app.ScanQuality
import de.ultimatepdfscanner.app.export.Compression
import de.ultimatepdfscanner.app.export.PageSize
import de.ultimatepdfscanner.app.export.Resolution
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.Screen
import de.ultimatepdfscanner.app.ocr.OcrLanguages
import de.ultimatepdfscanner.core.Mode
import de.ultimatepdfscanner.core.SplitMode
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import de.ultimatepdfscanner.app.Trigger

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: ScanViewModel) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var lockError by remember { mutableStateOf<String?>(null) }
    var pickLanguage by remember { mutableStateOf(false) }
    var pickFolder by remember { mutableStateOf(false) }
    var confirmSlow by remember { mutableStateOf(false) }
    if (confirmSlow) SlowQualityDialog(s.cameraMegapixels, onConfirm = { confirmSlow = false; vm.updateSettings(s.copy(quality = ScanQuality.ACCURATE)) }) { confirmSlow = false }
    if (pickFolder) StorageLocationDialog(vm, s.exportFolder) { pickFolder = false }
    if (pickLanguage) LanguageDialog(s.uiLanguage, onPick = { vm.updateSettings(s.copy(uiLanguage = it)); pickLanguage = false }) { pickLanguage = false }
    lockError?.let { msg ->
        AlertDialog(onDismissRequest = { lockError = null }, title = { Text(tr("App-Sperre nicht möglich")) }, text = { Text(msg) },
            confirmButton = { TextButton(onClick = { lockError = null }) { Text("OK") } })
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Einstellungen")) },
            navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
            actions = { HelpButton(HelpTopic.SETTINGS) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Group(tr("Sprache")) {
                Link(tr("Sprache der App"), languageName(s.uiLanguage)) { pickLanguage = true }
            }

            Group(tr("Aufnahme")) {
                Choice(tr("Scan-Qualität"), ScanQuality.entries.map { it to qualityLabel(it, s.cameraMegapixels.takeIf { mp -> mp > 0 }) }, s.quality) {
                    if (it == ScanQuality.ACCURATE && slowMaximum(s.cameraMegapixels)) confirmSlow = true else vm.updateSettings(s.copy(quality = it))
                }
                Text(when (s.quality) {
                    ScanQuality.FAST -> tr("Für große, klare Schrift wie Briefe und Ausdrucke: kleinere Fotos, schnellste Verarbeitung.")
                    ScanQuality.STANDARD -> tr("Für Bücher und die meisten Dokumente: gute Erkennung bei normaler Geschwindigkeit.")
                    ScanQuality.ACCURATE -> tr("Für Kleingedrucktes, alte Bücher, Fußnoten und Formeln: volle Kameraauflösung, langsamer, mehr Speicher.")
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                Choice(tr("Doppelseiten im Dokument-Modus"), listOf(SplitMode.AUTO to tr("Automatisch teilen"), SplitMode.SINGLE to tr("Nie teilen")),
                    if (s.split == SplitMode.DOUBLE) SplitMode.AUTO else s.split) { vm.updateSettings(s.copy(split = it)) }
                Choice(tr("Auslösung"), listOf(Trigger.MANUAL to tr("Manuell"), Trigger.AUTO to tr("Automatisch"), Trigger.REMOTE to tr("Bluetooth")), s.trigger) {
                    vm.updateSettings(s.copy(trigger = it))
                }
                Text(when (s.trigger) {
                    Trigger.MANUAL -> tr("Fotografiert beim Tippen auf den Auslöser.")
                    Trigger.AUTO -> tr("Fotografiert, sobald die Seite %s s ruhig im Bild liegt (im Scan-Fenster einstellbar); im Buch-Modus nach jedem Umblättern.",
                        de.ultimatepdfscanner.app.seconds(s.autoDelayMs))
                    Trigger.REMOTE -> tr("Fotografiert mit einem gekoppelten Bluetooth-Fernauslöser.")
                } + " " + tr("Lautstärketasten lösen immer aus."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
                Choice(tr("Intervall-Timer"), listOf(0 to tr("Aus"), 3 to "3 s", 5 to "5 s", 10 to "10 s"), s.timer) { vm.updateSettings(s.copy(timer = it)) }
            }

            Group(tr("Buch-Modus")) {
                Choice(tr("Leserichtung"), listOf(false to tr("Links → rechts"), true to tr("Rechts → links")), s.rtl) { vm.updateSettings(s.copy(rtl = it)) }
                Text(tr("Rechts → links für Arabisch, Hebräisch, Persisch und Japanisch: die rechte Seite kommt zuerst."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp))
                ListItem(headlineContent = { Text(tr("Fernauslöser")) }, supportingContent = {
                    Text(tr("Bluetooth-Kamera-Fernauslöser (z. B. „AB Shutter 3“) in den Android-Bluetooth-Einstellungen koppeln. ") +
                        tr("In der Kamera lösen dann der Fernauslöser und die Lautstärketasten aus."))
                })
            }

            Group(tr("Bild")) {
                Choice(tr("Standardfilter"), listOf(Mode.COLOR to tr("Farbe"), Mode.GRAY to tr("Graustufen"), Mode.BW to "S/W"),
                    s.mode) { vm.updateSettings(s.copy(mode = it)) }
                Toggle(tr("Automatische Verbesserung"), tr("Schatten und Artefakte entfernen, Schrift schärfen. In jedem Dokument einzeln abschaltbar."),
                    s.enhance) { vm.updateSettings(s.copy(enhance = it)) }
                Toggle(tr("Handschrift entfernen"), tr("Entfernt blaue/rote Stiftnotizen und Bleistift. Entfernt auch Druck in diesen Farben."),
                    s.handwriting) { vm.updateSettings(s.copy(handwriting = it)) }
            }

            Group(tr("Texterkennung")) {
                Toggle(tr("Texterkennung"), tr("Macht PDFs durchsuchbar und den Text kopierbar. Aus: schnellere Verarbeitung, PDF nur als Bild."), s.ocr) {
                    vm.updateSettings(s.copy(ocr = it))
                }
                if (s.ocr) Link(tr("Sprachen"), s.language.split('+').joinToString(", ") { tr(OcrLanguages.label(it)) }) { vm.navigate(Screen.OcrLanguages) }
                Link(tr("Fachwörterbücher für die Übersetzung"), tr("Recht, Medizin, Technik und mehr – 24 EU-Sprachen")) { vm.navigate(Screen.Glossaries) }
            }

            Group(tr("PDF & Export")) {
                // the folder name needs a storage query: read it off the UI thread
                val folder by androidx.compose.runtime.produceState<String?>(null, s.exportFolder) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { vm.folderName(s.exportFolder) }
                }
                Link(tr("Speicherort für PDFs und Bilder"), folder?.let { "„$it“" } ?: tr("Jedes Mal fragen")) { pickFolder = true }
                // defaults of the export sheet (it can still change them for a single export)
                Choice(tr("Auflösung"), Resolution.entries.map { it to tr(it.label) }, s.export.resolution) {
                    vm.updateSettings(s.copy(export = s.export.copy(resolution = it)))
                }
                Choice(tr("Kompression"), Compression.entries.map { it to tr(it.label) }, s.export.compression) {
                    vm.updateSettings(s.copy(export = s.export.copy(compression = it)))
                }
                Choice(tr("Seitenformat"), PageSize.entries.map { it to tr(it.label) }, s.export.pageSize) {
                    vm.updateSettings(s.copy(export = s.export.copy(pageSize = it)))
                }
                Text(tr("300 dpi und geringe Kompression für Druck und Archiv, 150 dpi und starke Kompression für E-Mail.") + " " +
                    tr("Beim Exportieren lässt sich das für einzelne Dateien ändern."),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp))
            }

            Group(tr("Sicherheit & Speicher")) {
                Toggle(tr("App-Sperre"), tr("Beim Öffnen mit Fingerabdruck, Gesicht oder Displaysperre entsperren. Inhalte erscheinen nicht in der App-Übersicht."),
                    s.appLock) { on ->
                    val reason = if (on) (context as? FragmentActivity)?.let { AppLock.unavailableReason(it) } else null
                    if (reason == null) vm.updateSettings(s.copy(appLock = on)) else lockError = reason
                }
                Link(tr("Speicher & Sicherung"), tr("Belegung, Backup als ZIP, Wiederherstellen")) { vm.navigate(Screen.StorageOverview) }
                Link(tr("Papierkorb"), tr("Gelöschte Scans werden nach 30 Tagen entfernt")) { vm.navigate(Screen.Trash) }
                ListItem(headlineContent = { Text(tr("Offline")) }, supportingContent = {
                    Text(tr("Alle Fotos und Texte bleiben auf diesem Gerät. Internet wird nur für das einmalige Laden von Sprachen, Übersetzungsmodellen und Fachwörterbüchern genutzt, wenn du es anstößt."))
                })
            }

            Group(tr("Über")) {
                ListItem(headlineContent = { Text("Ultimate PDF Scanner ${BuildConfig.VERSION_NAME}") },
                    supportingContent = { Text(tr("Open-Source-Komponenten: OpenCV, Tesseract OCR, PdfBox-Android, ZXing (jeweils Apache-Lizenz 2.0); ML Kit (Übersetzung, Gesichtserkennung, auf dem Gerät).")) })
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** A titled card of settings; list items inside take the card colour. */
@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 28.dp, top = 20.dp, bottom = 8.dp))
    val card = MaterialTheme.colorScheme.surfaceContainerLowest
    androidx.compose.material3.Surface(color = card, shape = MaterialTheme.shapes.large,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(surface = card)) {
            Column(Modifier.padding(vertical = 4.dp)) { content() }
        }
    }
}

@Composable
private fun <T> Choice(title: String, options: List<Pair<T, String>>, value: T, onChange: (T) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (v, label) -> FilterChip(selected = v == value, onClick = { onChange(v) }, label = { Text(label) }) }
        }
    }
}

@Composable
private fun Toggle(title: String, subtitle: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        trailingContent = { Switch(checked = value, onCheckedChange = onChange) }, modifier = Modifier.clickable { onChange(!value) })
}

@Composable
private fun Link(title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) },
        trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) }, modifier = Modifier.clickable(onClick = onClick))
}

/** Native name; "System language" shows which language it currently resolves to. */
private fun languageName(c: de.ultimatepdfscanner.app.I18n.Choice): String =
    if (c == de.ultimatepdfscanner.app.I18n.Choice.SYSTEM) tr("Systemsprache") + " (" + de.ultimatepdfscanner.app.I18n.active.label + ")" else c.label

@Composable
private fun LanguageDialog(current: de.ultimatepdfscanner.app.I18n.Choice, onPick: (de.ultimatepdfscanner.app.I18n.Choice) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(tr("Sprache der App")) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn {
                items(de.ultimatepdfscanner.app.I18n.Choice.entries.size) { i ->
                    val c = de.ultimatepdfscanner.app.I18n.Choice.entries[i]
                    Row(Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 10.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = c == current, onClick = { onPick(c) })
                        Text(if (c == de.ultimatepdfscanner.app.I18n.Choice.SYSTEM) tr("Systemsprache") else c.label, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } })
}
