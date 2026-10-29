package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Draw
import androidx.compose.material.icons.filled.HideSource
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.Screen
import de.ultimatepdfscanner.app.data.DocInfo
import de.ultimatepdfscanner.app.data.PageInfo
import de.ultimatepdfscanner.core.Mode
import de.ultimatepdfscanner.core.Pipeline
import de.ultimatepdfscanner.core.pageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PageScreen(vm: ScanViewModel, docId: String, pageId: String) {
    DarkSystemBars()
    val docs by vm.docs.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId } ?: return
    if (doc.pages.isEmpty()) { LaunchedEffect(Unit) { vm.goBack() }; return }
    val start = doc.pages.indexOfFirst { it.id == pageId }.coerceAtLeast(0)
    val pager = rememberPagerState(initialPage = start) { doc.pages.size }
    val page = doc.pages[pager.currentPage.coerceIn(0, doc.pages.size - 1)]
    var showFilters by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Brand.Editor,
        topBar = {
            TopAppBar(
                title = { Text(tr("Seite %d von %d", pager.currentPage + 1, doc.pages.size)) },
                navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
                actions = {
                    HelpButton(HelpTopic.PAGE)
                    IconButton(onClick = { confirmDelete = true }, modifier = Modifier.explain("Seite löschen", "Löscht diese Seite aus dem Dokument (mit Rückfrage).")) {
                        Icon(Icons.Filled.Delete, tr("Seite löschen"))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.Editor, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Color.White),
            )
        },
        bottomBar = {
            Surface(color = Brand.EditorBar) {
                Column(Modifier.navigationBarsPadding()) {
                    AnimatedVisibility(showFilters) { FilterStrip(vm, doc, page, version) }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp, horizontal = 4.dp)) {
                        Tool(Icons.Filled.Palette, tr("Filter"), Explanation("Filter", "Farbe, Graustufen oder Schwarz-Weiß und die automatische Verbesserung nur für diese Seite."), selected = showFilters) { showFilters = !showFilters }
                        Tool(Icons.Filled.RotateRight, tr("Drehen"), Explanation("Drehen", "Dreht die Seite um 90° im Uhrzeigersinn.")) { vm.rotatePage(docId, page, 90) }
                        Tool(Icons.Filled.Crop, tr("Zuschnitt"), Explanation("Zuschnitt", "Die Ecken der Seite im Originalfoto neu setzen, wenn Rand fehlt oder Hintergrund mit drauf ist.")) { vm.navigate(Screen.Crop(docId, page.id)) }
                        Tool(Icons.Filled.Edit, tr("Bearbeiten"), Explanation("Bearbeiten", "Mit Stift, Marker oder Text auf die Seite schreiben.")) { vm.navigate(Screen.Annotate(docId, page.id)) }
                        Tool(Icons.Filled.HideSource, tr("Schwärzen"), Explanation("Schwärzen", "Sensible Daten mit Balken oder Pixeln unkenntlich machen; sie verschwinden auch aus dem Text des PDFs.")) { vm.navigate(Screen.Redact(docId, page.id)) }
                        Tool(Icons.Filled.Draw, tr("Signieren"), Explanation("Signieren", "Setzt deine gespeicherte Unterschrift auf die Seite; beim ersten Mal zeichnest du sie.")) {
                            vm.navigate(if (vm.signatureFile.exists()) Screen.Sign(docId, page.id) else Screen.SignaturePad(docId, page.id))
                        }
                        Tool(Icons.AutoMirrored.Filled.TextSnippet, tr("Text"), Explanation("Text", "Zeigt den erkannten Text der Seite zum Korrigieren und Kopieren.")) { vm.navigate(Screen.TextEdit(docId, page.id)) }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (page.issues.isNotEmpty()) {
                Surface(color = Color(0xFF3A2A12), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.WarningAmber, null, tint = Color(0xFFFFB95C))
                        Text(page.issues.joinToString(" · ") { tr(it.label) }, color = Color(0xFFFFE2BC), style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                        TextButton(onClick = { vm.navigate(Screen.Camera(docId, retakePhotoId = page.photoId)) }) {
                            Text(tr("Erneut scannen"), color = Color(0xFFFFB95C))
                        }
                    }
                }
            }
            HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), beyondViewportPageCount = 1) { index ->
                ZoomablePage(vm, doc, doc.pages[index], version)
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(tr("Seite löschen?")) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.deletePage(docId, page) }) { Text(tr("Löschen")) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(tr("Abbrechen")) } })
    }
    BusyDialog(vm)
}

@Composable
private fun Tool(icon: ImageVector, label: String, explanation: Explanation, selected: Boolean = false, onClick: () -> Unit) {
    val tint = if (selected) Brand.Overlay else Color.White
    Column(Modifier.width(72.dp).clip(RoundedCornerShape(12.dp)).explain(explanation.title, explanation.text).clickable(onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun ZoomablePage(vm: ScanViewModel, doc: DocInfo, page: PageInfo, version: Int) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val state = rememberTransformableState { _, zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale > 1f) offset + pan else Offset.Zero
    }
    val bmp = rememberImage(vm.storage.pageFile(doc, page), 2400, page.rotation, version)
    Box(Modifier.fillMaxSize().transformable(state, canPan = { scale > 1f })
        .pointerInput(Unit) { detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero }) },
        contentAlignment = Alignment.Center) {
        if (bmp == null) CircularProgressIndicator()
        else Image(bmp.asImageBitmap(), tr("Seite"), Modifier.fillMaxSize().padding(12.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y), contentScale = ContentScale.Fit)
    }
}

/** The filters with live previews of this page (rendered from its rectified image) and the automatic correction switch. */
@Composable
private fun FilterStrip(vm: ScanViewModel, doc: DocInfo, page: PageInfo, version: Int) {
    val previews by produceState<Map<Mode, Bitmap>>(emptyMap(), page.id, page.enhance, version) {
        value = withContext(Dispatchers.Default) {
            val rect = Imgcodecs.imread(vm.storage.rectFile(doc, page).path, Imgcodecs.IMREAD_COLOR)
            if (rect.empty()) return@withContext emptyMap()
            val f = 360.0 / maxOf(rect.cols(), rect.rows())
            val small = org.opencv.core.Mat()
            Imgproc.resize(rect, small, Size(rect.cols() * f, rect.rows() * f), 0.0, 0.0, Imgproc.INTER_AREA)
            rect.release()
            IMAGE_MODES.associateWith { m ->
                val out = Pipeline.render(small, m, page.enhance)
                val rgba = org.opencv.core.Mat()
                Imgproc.cvtColor(out, rgba, if (out.channels() == 1) Imgproc.COLOR_GRAY2RGBA else Imgproc.COLOR_BGR2RGBA)
                val bmp = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
                Utils.matToBitmap(rgba, bmp)
                out.release(); rgba.release()
                bmp
            }.also { small.release() }
        }
    }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IMAGE_MODES.forEach { m ->
                val selected = page.mode == m
                Column(Modifier.weight(1f).clickable { if (!selected) vm.setPageMode(doc.id, page, m) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF2A3442))
                        .border(if (selected) 2.dp else 0.dp, if (selected) Brand.Overlay else Color.Transparent, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center) {
                        previews[m]?.let { Image(it.asImageBitmap(), modeLabel(m), Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit) }
                            ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                    Text(modeLabel(m), color = if (selected) Brand.Overlay else Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth().clickable { vm.setPageMode(doc.id, page, page.mode, !page.enhance) }.padding(start = 16.dp, end = 12.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.AutoFixHigh, null, tint = Brand.Overlay)
            Text(tr("Automatische Verbesserung"), color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Switch(page.enhance, { vm.setPageMode(doc.id, page, page.mode, it) })
        }
    }
}
