package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.data.Mark


private enum class Tool(val label: String, val icon: ImageVector) { PEN("Stift", Icons.Filled.Edit), MARKER("Marker", Icons.Filled.BorderColor), TEXT("Text", Icons.Filled.TextFields) }

private val PEN_COLORS = listOf(Color(0xFF111111), Color(0xFF1A47B8), Color(0xFFC62828), Color(0xFF2E7D32))
private val MARKER_COLORS = listOf(Color(0xFFFFEB3B), Color(0xFF76FF03), Color(0xFF40C4FF), Color(0xFFFF80AB))

/** Draw, highlight or write on a page (e.g. fill in a form); burned into the page on saving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotateScreen(vm: ScanViewModel, docId: String, pageId: String) {
    DarkSystemBars()
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId } ?: return
    val page = doc.pages.firstOrNull { it.id == pageId } ?: run { vm.goBack(); return }
    val marks = remember { mutableStateListOf<Mark>() }
    var tool by remember { mutableStateOf(Tool.PEN) }
    var penColor by remember { mutableStateOf(PEN_COLORS[0]) }
    var markerColor by remember { mutableStateOf(MARKER_COLORS[0]) }
    var size by remember { mutableFloatStateOf(0.4f) } // 0..1 slider
    var current by remember { mutableStateOf<List<Offset>?>(null) }
    var textAt by remember { mutableStateOf<Offset?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    val bmp = rememberImage(vm.storage.pageFile(doc, page), 2000, page.rotation)
    BackHandler(enabled = marks.isNotEmpty()) { confirmLeave = true }

    fun penWidth() = if (tool == Tool.MARKER) 0.012f + size * 0.03f else 0.0015f + size * 0.006f
    fun textSize() = 0.018f + size * 0.04f
    val color = if (tool == Tool.MARKER) markerColor else penColor

    Scaffold(containerColor = Brand.Editor, topBar = {
        TopAppBar(title = { Text(tr("Bearbeiten")) },
            navigationIcon = { IconButton(onClick = { if (marks.isNotEmpty()) confirmLeave = true else vm.goBack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, tr("Zurück")) } },
            actions = {
                HelpButton(HelpTopic.ANNOTATE)
                IconButton(onClick = { marks.removeLastOrNull() }, enabled = marks.isNotEmpty()) { Icon(Icons.AutoMirrored.Filled.Undo, tr("Rückgängig")) }
                TextButton(onClick = { vm.applyMarks(docId, page, marks.toList()); vm.goBack() }, enabled = marks.isNotEmpty()) {
                    Icon(Icons.Filled.Check, null, tint = if (marks.isNotEmpty()) Brand.Overlay else Color.Gray)
                    Text(tr("Sichern"), color = if (marks.isNotEmpty()) Brand.Overlay else Color.Gray, modifier = Modifier.padding(start = 4.dp))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.Editor, titleContentColor = Color.White,
                navigationIconContentColor = Color.White, actionIconContentColor = Color.White))
    }, bottomBar = {
        Surface(color = Brand.EditorBar) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    (if (tool == Tool.MARKER) MARKER_COLORS else PEN_COLORS).forEach { c ->
                        val on = c == color
                        Box(Modifier.size(30.dp).clip(CircleShape).background(c).border(if (on) 3.dp else 1.dp, if (on) Color.White else Color(0x66FFFFFF), CircleShape)
                            .clickable { if (tool == Tool.MARKER) markerColor = c else penColor = c })
                    }
                    Slider(size, { size = it }, modifier = Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Tool.entries.forEach { t -> ToolButton(t.icon, t.label, tool == t) { tool = t } }
                }
            }
        }
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
            val image = bmp ?: return@BoxWithConstraints
            val aspect = image.width.toFloat() / image.height
            val w = minOf(constraints.maxWidth.toFloat(), constraints.maxHeight * aspect)
            val h = w / aspect
            val density = LocalDensity.current
            Box(Modifier.size(with(density) { w.toDp() }, with(density) { h.toDp() })) {
                Image(image.asImageBitmap(), tr("Seite"), Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                Canvas(Modifier.fillMaxSize()
                    .pointerInput(tool) {
                        if (tool == Tool.TEXT) detectTapGestures { p -> textAt = Offset(p.x / w, p.y / h) }
                        else detectDragGestures(
                            onDragStart = { p -> current = listOf(Offset(p.x / w, p.y / h)) },
                            onDrag = { change, _ -> change.consume(); current = current.orEmpty() + Offset(change.position.x / w, change.position.y / h) },
                            onDragEnd = {
                                current?.takeIf { it.size > 1 }?.let { marks += Mark.Ink(it, color.toArgb(), penWidth(), tool == Tool.MARKER) }
                                current = null
                            })
                    }) {
                    val all = marks.toList() + listOfNotNull(current?.let { Mark.Ink(it, color.toArgb(), penWidth(), tool == Tool.MARKER) })
                    for (m in all) when (m) {
                        is Mark.Ink -> {
                            val path = Path().apply { m.points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x * w, p.y * h) else lineTo(p.x * w, p.y * h) } }
                            drawPath(path, Color(m.color).copy(alpha = if (m.highlighter) 0.45f else 1f),
                                style = Stroke(width = m.width * w, cap = if (m.highlighter) StrokeCap.Butt else StrokeCap.Round, join = StrokeJoin.Round),
                                blendMode = if (m.highlighter) BlendMode.Multiply else BlendMode.SrcOver)
                        }
                        is Mark.Note -> drawContext.canvas.nativeCanvas.drawText(m.text, m.at.x * w, m.at.y * h,
                            android.graphics.Paint().apply { this.color = m.color; textSize = m.size * w; isAntiAlias = true })
                    }
                }
            }
        }
    }

    textAt?.let { at ->
        var text by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { textAt = null }, title = { Text(tr("Text einfügen")) },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text(tr("z. B. Name, Datum …")) }) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) marks += Mark.Note(at, text.trim(), penColor.toArgb(), textSize()); textAt = null }) { Text(tr("Einfügen")) } },
            dismissButton = { TextButton(onClick = { textAt = null }) { Text(tr("Abbrechen")) } })
    }
    if (confirmLeave) {
        AlertDialog(onDismissRequest = { confirmLeave = false }, title = { Text(tr("Änderungen sichern?")) },
            confirmButton = { TextButton(onClick = { confirmLeave = false; vm.applyMarks(docId, page, marks.toList()); vm.goBack() }) { Text(tr("Sichern")) } },
            dismissButton = { TextButton(onClick = { confirmLeave = false; vm.goBack() }) { Text(tr("Verwerfen")) } })
    }
}

@Composable
private fun RowScope.ToolButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = if (selected) Brand.Overlay else Color.White
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, label, tint = tint)
        Text(label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}
