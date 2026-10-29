package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.FaceDetector
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.core.Redact
import de.ultimatepdfscanner.core.Redaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

private class Candidate(val r: Redaction, enabled: Boolean) { var enabled by mutableStateOf(enabled) }

/**
 * Redaction: sensitive data found in the OCR text (card numbers, IBAN, tax IDs, passwords) and
 * faces (passport photos) are proposed; the user can toggle them and draw extra boxes, then
 * choose black bars or pixelation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RedactScreen(vm: ScanViewModel, docId: String, pageId: String) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId } ?: return
    val page = doc.pages.firstOrNull { it.id == pageId } ?: run { vm.goBack(); return }
    val file = vm.storage.pageFile(doc, page)
    val bmp = rememberImage(file, 2000)
    val candidates = remember(pageId) { mutableStateListOf<Candidate>() }
    var scanning by remember { mutableStateOf(true) }
    var pixelate by remember { mutableStateOf(false) }
    var drawing by remember { mutableStateOf<Rect?>(null) }
    // page image size in pixels (OCR/redaction coordinates); the bitmap may be downsampled
    val pageSize = remember(file.path) {
        BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(file.path, it) }.let { it.outWidth to it.outHeight }
    }

    LaunchedEffect(pageId, bmp) {
        val b = bmp ?: return@LaunchedEffect
        val found = withContext(Dispatchers.Default) {
            Redact.find(vm.storage.words(doc, page)) + faces(b, pageSize.first.toFloat() / b.width)
        }
        candidates.clear()
        candidates.addAll(found.map { Candidate(it, true) })
        scanning = false
    }

    Scaffold(
        containerColor = Brand.CameraBg,
        topBar = {
            TopAppBar(title = { Text(tr("Schwärzen")) },
                navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.Filled.Close, tr("Abbrechen")) } },
                actions = { HelpButton(HelpTopic.REDACT, Color.White) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.CameraBg, titleContentColor = Color.White, navigationIconContentColor = Color.White))
        },
        bottomBar = {
            Column(Modifier.navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(when {
                    scanning -> tr("Suche sensible Daten…")
                    candidates.isEmpty() -> tr("Nichts Sensibles gefunden. Bereiche zum Schwärzen mit dem Finger aufziehen.")
                    else -> tr("Gefunden – antippen zum An-/Abwählen, weitere Bereiche aufziehen:")
                }, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    candidates.forEach { c ->
                        FilterChip(selected = c.enabled, onClick = { c.enabled = !c.enabled }, label = { Text(c.r.kind) },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Color(0xFFE53935), selectedLabelColor = Color.White))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(selected = !pixelate, onClick = { pixelate = false }, label = { Text(tr("Schwarzer Balken")) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Brand.Overlay))
                    FilterChip(selected = pixelate, onClick = { pixelate = true }, label = { Text(tr("Verpixeln")) },
                        colors = FilterChipDefaults.filterChipColors(labelColor = Color.White, selectedContainerColor = Brand.Overlay))
                }
                val chosen = candidates.filter { it.enabled }.map { it.r }
                Button(onClick = { vm.applyRedactions(docId, page, chosen, pixelate); vm.goBack() }, enabled = chosen.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()) { Text(tr("Unkenntlich machen (%d)", chosen.size)) }
            }
        },
    ) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val b = bmp ?: run { CircularProgressIndicator(); return@BoxWithConstraints }
            val img = remember(b) { b.asImageBitmap() }
            val boxW = constraints.maxWidth.toFloat()
            val boxH = constraints.maxHeight.toFloat()
            val s = min(boxW / b.width, boxH / b.height)
            val dst = Rect(Offset((boxW - b.width * s) / 2, (boxH - b.height * s) / 2), Size(b.width * s, b.height * s))
            val toScreen = dst.width / pageSize.first // page px -> screen px
            fun screenRect(r: Redaction) = Rect(dst.left + r.x0 * toScreen, dst.top + r.y0 * toScreen, dst.left + r.x1 * toScreen, dst.top + r.y1 * toScreen)

            Canvas(Modifier.fillMaxSize()
                .pointerInput(dst) {
                    detectTapGestures { pos -> candidates.firstOrNull { screenRect(it.r).contains(pos) }?.let { it.enabled = !it.enabled } }
                }
                .pointerInput(dst) {
                    var start = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start = it; drawing = Rect(it, it) },
                        onDrag = { change, _ -> change.consume(); drawing = Rect(start, change.position).normalized() },
                        onDragEnd = {
                            drawing?.let { r ->
                                if (r.width > 12 && r.height > 12) {
                                    val x0 = ((r.left - dst.left) / toScreen).toInt(); val y0 = ((r.top - dst.top) / toScreen).toInt()
                                    val x1 = ((r.right - dst.left) / toScreen).toInt(); val y1 = ((r.bottom - dst.top) / toScreen).toInt()
                                    candidates += Candidate(Redaction(tr("Manuell"), max(0, x0), max(0, y0), min(pageSize.first, x1), min(pageSize.second, y1)), true)
                                }
                            }
                            drawing = null
                        })
                }) {
                drawImage(img, IntOffset.Zero, IntSize(b.width, b.height), IntOffset(dst.left.toInt(), dst.top.toInt()), IntSize(dst.width.toInt(), dst.height.toInt()))
                candidates.forEach { c ->
                    val r = screenRect(c.r)
                    if (c.enabled) {
                        drawRect(Color(0xCC000000), r.topLeft, r.size)
                        drawRect(Color(0xFFE53935), r.topLeft, r.size, style = Stroke(2.dp.toPx()))
                    } else {
                        drawRect(Color(0xFFE53935), r.topLeft, r.size, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f))))
                    }
                }
                drawing?.let { drawRect(Brand.Overlay, it.topLeft, it.size, style = Stroke(2.dp.toPx())) }
            }
        }
    }
}

private fun Rect.normalized() = Rect(min(left, right), min(top, bottom), max(left, right), max(top, bottom))

/** Faces (e.g. passport photos) via Android's built-in, offline face detector; boxes in page pixels. */
private fun faces(bitmap: Bitmap, toPage: Float): List<Redaction> {
    val w = bitmap.width and 1.inv() // FaceDetector needs an even width
    val rgb565 = Bitmap.createBitmap(bitmap, 0, 0, w, bitmap.height).copy(Bitmap.Config.RGB_565, false)
    val found = arrayOfNulls<FaceDetector.Face>(8)
    val n = FaceDetector(w, rgb565.height, found.size).findFaces(rgb565, found)
    val mid = android.graphics.PointF()
    return (0 until n).mapNotNull { i ->
        val f = found[i] ?: return@mapNotNull null
        if (f.confidence() < 0.4f) return@mapNotNull null
        f.getMidPoint(mid)
        val e = f.eyesDistance()
        // a passport photo shows head and shoulders: roughly 3.2 x 4.4 eye distances around the eyes
        Redaction(tr("Gesicht"), ((mid.x - 1.6f * e) * toPage).toInt(), ((mid.y - 2.0f * e) * toPage).toInt(),
            ((mid.x + 1.6f * e) * toPage).toInt(), ((mid.y + 2.4f * e) * toPage).toInt())
    }
}
