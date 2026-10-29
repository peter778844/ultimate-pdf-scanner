package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.core.SplitMode
import org.opencv.core.Point
import kotlin.math.hypot

/** Manual page corners on the original photo; applying re-processes the photo with them. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropScreen(vm: ScanViewModel, docId: String, pageId: String) {
    DarkSystemBars()
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId } ?: return
    val page = doc.pages.firstOrNull { it.id == pageId }
    val photo = page?.let { p -> doc.photos.firstOrNull { it.id == p.photoId } }
    if (page == null || photo == null) { vm.goBack(); return }
    if (!vm.storage.original(doc, photo.id).exists()) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { vm.goBack() }, title = { Text(tr("Zuschnitt nicht möglich")) },
            text = { Text(tr("Das Originalfoto dieser Seite wurde entfernt, um Speicher zu sparen. Mit „Erneut scannen“ lässt sich die Seite neu aufnehmen.")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.goBack() }) { Text("OK") } })
        return
    }

    val bmp = rememberImage(vm.storage.original(doc, photo.id), 2000)
    // corners relative to the photo (0..1), order tl, tr, br, bl
    val corners = remember(photo.id) {
        val q = photo.quadPoints()
        mutableStateListOf(*(q?.map { Offset(it.x.toFloat(), it.y.toFloat()) }
            ?: listOf(Offset(0.1f, 0.1f), Offset(0.9f, 0.1f), Offset(0.9f, 0.9f), Offset(0.1f, 0.9f))).toTypedArray())
    }
    var split by remember(photo.id) { mutableStateOf(photo.split) }
    var active by remember { mutableIntStateOf(-1) }
    var confirmAuto by remember { mutableStateOf<Boolean?>(null) } // pending apply(auto) of an edited page

    fun apply(auto: Boolean, confirmed: Boolean = false) {
        // the page is made anew from the photo: redactions, a signature and notes would be gone without a word
        if (!confirmed && doc.pages.any { it.photoId == photo.id && it.edited }) { confirmAuto = auto; return }
        vm.recrop(docId, photo.id, if (auto) null else corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray(), split)
        vm.backToDocument(docId)
    }
    confirmAuto?.let { auto ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmAuto = null }, title = { Text(tr("Bearbeitungen gehen verloren")) },
            text = { Text(tr("Die Seite wird aus dem Foto neu erstellt. Schwärzungen, Unterschrift und Notizen müssen danach erneut eingefügt werden.")) },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { confirmAuto = null; apply(auto, confirmed = true) }) { Text(tr("Neu zuschneiden")) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirmAuto = null }) { Text(tr("Abbrechen")) } })
    }

    Scaffold(
        containerColor = Brand.CameraBg,
        topBar = {
            TopAppBar(title = { Text(tr("Zuschnitt anpassen")) },
                navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.Filled.Close, tr("Abbrechen")) } },
                actions = { HelpButton(HelpTopic.CROP, Color.White); IconButton(onClick = { apply(false) }) { Icon(Icons.Filled.Check, tr("Übernehmen")) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.CameraBg, titleContentColor = Color.White,
                    navigationIconContentColor = Color.White, actionIconContentColor = Brand.Overlay))
        },
        bottomBar = {
            Column(Modifier.background(Brand.Editor).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                    listOf(SplitMode.SINGLE to tr("Einzelseite"), SplitMode.DOUBLE to tr("Doppelseite"), SplitMode.AUTO to tr("Auto")).forEach { (m, label) ->
                        // for book-mode photos "Auto" is the book rule (split unless cover, receipt or single sheet)
                        val auto = if (photo.split == SplitMode.BOOK) SplitMode.BOOK else SplitMode.AUTO
                        FilterChip(selected = split == m || (m == SplitMode.AUTO && split == auto), onClick = { split = if (m == SplitMode.AUTO) auto else m },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Brand.Overlay,
                                selectedLabelColor = Color.Black))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { apply(true) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.AutoFixHigh, null, tint = Color.White); Text(tr("  Automatisch"), color = Color.White)
                    }
                    Button(onClick = { apply(false) }, modifier = Modifier.weight(1f)) { Text(tr("Übernehmen")) }
                }
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            val image = bmp ?: run { CircularProgressIndicator(); return@BoxWithConstraints }
            val img = remember(image) { image.asImageBitmap() }
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val scale = minOf(boxW / image.width, boxH / image.height)
            val dst = Rect(Offset((boxW - image.width * scale) / 2, (boxH - image.height * scale) / 2), Size(image.width * scale, image.height * scale))
            fun toScreen(p: Offset) = Offset(dst.left + p.x * dst.width, dst.top + p.y * dst.height)

            Canvas(Modifier.fillMaxSize().pointerInput(dst) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val touch = 48.dp.toPx()
                        active = corners.indices.minByOrNull { (toScreen(corners[it]) - pos).getDistance() }
                            ?.takeIf { (toScreen(corners[it]) - pos).getDistance() < touch * 1.5f } ?: -1
                    },
                    onDragEnd = { active = -1 }, onDragCancel = { active = -1 },
                    onDrag = { change, drag ->
                        if (active >= 0) {
                            change.consume()
                            val c = corners[active]
                            corners[active] = Offset((c.x + drag.x / dst.width).coerceIn(0f, 1f), (c.y + drag.y / dst.height).coerceIn(0f, 1f))
                        }
                    })
            }) {
                drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(image.width, image.height),
                    dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()), dstSize = IntSize(dst.width.toInt(), dst.height.toInt()))
                val pts = corners.map(::toScreen)
                val quad = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                // dim everything outside the page
                val outside = Path().apply { fillType = PathFillType.EvenOdd; addRect(dst); addPath(quad) }
                drawPath(outside, Color(0x99000000))
                drawPath(quad, Brand.Overlay, style = Stroke(2.5.dp.toPx()))
                pts.forEachIndexed { i, p ->
                    drawCircle(Color.White, if (i == active) 16.dp.toPx() else 12.dp.toPx(), p)
                    drawCircle(Brand.Overlay, if (i == active) 12.dp.toPx() else 8.dp.toPx(), p)
                }
                // magnifier while dragging: 3x view around the corner, placed away from the finger
                if (active >= 0) {
                    val p = pts[active]
                    val r = 64.dp.toPx()
                    val center = Offset(if (p.x < size.width / 2) size.width - r - 8.dp.toPx() else r + 8.dp.toPx(), r + 8.dp.toPx())
                    val zoom = 3f
                    val srcSide = (2 * r / (scale * zoom)).toInt().coerceAtLeast(8)
                    val sx = (corners[active].x * image.width - srcSide / 2).toInt().coerceIn(0, image.width - srcSide)
                    val sy = (corners[active].y * image.height - srcSide / 2).toInt().coerceIn(0, image.height - srcSide)
                    val circle = Path().apply { addOval(Rect(center, r)) }
                    clipPath(circle, ClipOp.Intersect) {
                        drawImage(img, IntOffset(sx, sy), IntSize(srcSide, srcSide),
                            IntOffset((center.x - r).toInt(), (center.y - r).toInt()), IntSize((2 * r).toInt(), (2 * r).toInt()))
                    }
                    drawCircle(Color.White, r, center, style = Stroke(3.dp.toPx()))
                    drawLine(Brand.Overlay, center - Offset(10.dp.toPx(), 0f), center + Offset(10.dp.toPx(), 0f), 2.dp.toPx())
                    drawLine(Brand.Overlay, center - Offset(0f, 10.dp.toPx()), center + Offset(0f, 10.dp.toPx()), 2.dp.toPx())
                }
            }
            Text(tr("Ecken auf die Seitenränder ziehen"), color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}
