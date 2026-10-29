package de.ultimatepdfscanner.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.ultimatepdfscanner.app.tr

/** What to import: pictures from the gallery, files (PDF, pictures) or a link to a PDF or picture. */
@Composable
fun ImportChoiceDialog(onGallery: () -> Unit, onFiles: () -> Unit, onLink: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Filled.FileOpen, null) }, title = { Text(tr("Importieren")) },
        text = {
            Column {
                ImportOption(Icons.Filled.PhotoLibrary, tr("Bilder aus der Galerie"), tr("Fotos und Screenshots")) { onDismiss(); onGallery() }
                ImportOption(Icons.Filled.Description, tr("Dateien"), tr("PDF-Dateien und Bilder, z. B. aus Downloads oder der Cloud")) { onDismiss(); onFiles() }
                ImportOption(Icons.Filled.Link, tr("Link"), tr("Eine PDF- oder Bilddatei aus dem Internet")) { onDismiss(); onLink() }
                Text(tr("Danach wie gescannte Seiten: Texterkennung, Übersetzung und Export in jedes Format."), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } })
}

@Composable
private fun ImportOption(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.padding(start = 14.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Address of a PDF or picture to download and import. */
@Composable
fun LinkDialog(onLoad: (String) -> Unit, onDismiss: () -> Unit) {
    var url by remember { mutableStateOf("") }
    val valid = url.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(Icons.Filled.Link, null) }, title = { Text(tr("Link importieren")) },
        text = {
            OutlinedTextField(url, { url = it }, label = { Text(tr("Internetadresse")) }, placeholder = { Text("https://…") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onLoad(url.trim()) }, enabled = valid) { Text(tr("Laden")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } })
}
