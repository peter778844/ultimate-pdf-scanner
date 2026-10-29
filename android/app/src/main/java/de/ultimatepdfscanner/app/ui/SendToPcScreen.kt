package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.share.LocalServer
import kotlinx.coroutines.delay

private fun qrBitmap(text: String, size: Int = 640): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1))
    val px = IntArray(size * size) { if (m.get(it % size, it / size)) 0xFF111318.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** Serves the scans to a PC in the same Wi-Fi while this screen is open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendToPcScreen(vm: ScanViewModel) {
    var address by remember { mutableStateOf(LocalServer.localAddress()) }
    val downloads = remember { mutableStateListOf<String>() }
    val server = remember { LocalServer(vm.storage, { vm.docs.value }) { name -> downloads.add(0, name) } }
    var running by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        running = server.start()
        onDispose { server.stop() }
    }
    // the Wi-Fi may connect while the screen is open
    LaunchedEffect(Unit) { while (true) { address = LocalServer.localAddress(); delay(3000) } }
    // read after the start (reading [running] here recomposes this scope): before it, the port was still 0
    val url = address?.takeIf { running }?.let { "http://$it:${server.port}/${server.token}/" }

    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("An PC senden")) },
            navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
            actions = { HelpButton(HelpTopic.SEND_TO_PC) })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (url == null || !running) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.surfaceVariant), Alignment.Center) {
                    Icon(Icons.Filled.WifiOff, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(tr("Kein WLAN"), style = MaterialTheme.typography.titleLarge)
                Text(tr("Verbinde Handy und PC mit demselben WLAN (oder den PC mit dem Hotspot des Handys). Die Adresse erscheint dann hier."),
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(tr("Am PC im Browser öffnen"), style = MaterialTheme.typography.titleLarge)
            Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 2.dp) {
                Image(remember(url) { qrBitmap(url) }.asImageBitmap(), tr("QR-Code der Adresse"), Modifier.padding(14.dp).size(220.dp))
            }
            SelectionContainer {
                Text(url, style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.Center)
            }
            Text(tr("Adresse eintippen oder den QR-Code mit der PC-Kamera scannen. Dort lassen sich alle Scans als PDF oder Text herunterladen."),
                textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Computer, null, tint = MaterialTheme.colorScheme.primary)
                    Text(tr("Nur im lokalen Netz und nur solange dieser Bildschirm offen ist. Nichts wird ins Internet hochgeladen."),
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 12.dp))
                }
            }
            if (downloads.isNotEmpty()) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(tr("Heruntergeladen"), style = MaterialTheme.typography.titleSmall)
                    downloads.take(8).forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = Status.Ok, modifier = Modifier.size(18.dp))
                            Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
            Button(onClick = { vm.goBack() }) { Text(tr("Fertig")) }
        }
    }
}
