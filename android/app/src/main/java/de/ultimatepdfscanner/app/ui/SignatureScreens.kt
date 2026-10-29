package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.Screen
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private val INK = Color(0xFF0D2B6B) // dark blue, like a ballpoint pen

/** Draw the signature once; it is stored as a transparent PNG and reused on any page. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignaturePadScreen(vm: ScanViewModel, docId: String?, pageId: String?) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val strokePx = with(LocalDensity.current) { 3.dp.toPx() }

    fun save() {
        val all = strokes.flatten()
        if (all.isEmpty()) return
        val pad = strokePx * 3
        val x0 = max(0f, all.minOf { it.x } - pad); val y0 = max(0f, all.minOf { it.y } - pad)
        val x1 = min(padSize.width, all.maxOf { it.x } + pad); val y1 = min(padSize.height, all.maxOf { it.y } + pad)
        // render at 2x for a crisp result on paper
        val scale = 2f
        val bmp = Bitmap.createBitmap(((x1 - x0) * scale).roundToInt().coerceAtLeast(1), ((y1 - y0) * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply {
            color = android.graphics.Color.rgb(13, 43, 107); style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokePx * scale; strokeCap = android.graphics.Paint.Cap.ROUND; strokeJoin = android.graphics.Paint.Join.ROUND; isAntiAlias = true
        }
        for (s in strokes) {
            val path = android.graphics.Path()
            s.forEachIndexed { i, p ->
                val x = (p.x - x0) * scale; val y = (p.y - y0) * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
        vm.saveSignature(bmp)
        if (docId != null && pageId != null) vm.replace(Screen.Sign(docId, pageId)) else vm.goBack()
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text(tr("Unterschrift zeichnen")) },
            navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.Filled.Close, tr("Abbrechen")) } },
            actions = { HelpButton(HelpTopic.SIGNATURE) })
    }, bottomBar = {
        Row(Modifier.navigationBarsPadding().padding(16.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { strokes.clear() }, modifier = Modifier.weight(1f)) { Text(tr("Löschen")) }
            Button(onClick = ::save, enabled = strokes.isNotEmpty(), modifier = Modifier.weight(1f)) { Text(tr("Speichern")) }
        }
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.Center) {
            Text(tr("Mit dem Finger unterschreiben"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(bottom = 8.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(2.2f).clip(RoundedCornerShape(16.dp)).background(Color.White)
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))) {
                Canvas(Modifier.fillMaxSize().pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { current = listOf(it) },
                        onDrag = { change, _ -> change.consume(); current = current + change.position },
                        onDragEnd = { if (current.size > 1) strokes += current; current = emptyList() })
                }) {
                    padSize = size
                    drawLine(Color(0xFFB0BEC5), Offset(24f, size.height * 0.75f), Offset(size.width - 24f, size.height * 0.75f), 2f)
                    (strokes + listOf(current)).filter { it.size > 1 }.forEach { s ->
                        val path = Path().apply { moveTo(s[0].x, s[0].y); s.drop(1).forEach { lineTo(it.x, it.y) } }
                        drawPath(path, INK, style = Stroke(strokePx, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
    }
}

/** Place the saved signature on a page: drag to move, pinch to resize. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignScreen(vm: ScanViewModel, docId: String, pageId: String) {
    val docs by vm.docs.collectAsStateWithLifecycle()
    val version by vm.version.collectAsStateWithLifecycle()
    val doc = docs.firstOrNull { it.id == docId } ?: return
    val page = doc.pages.firstOrNull { it.id == pageId } ?: run { vm.goBack(); return }
    val pageBmp = rememberImage(vm.storage.pageFile(doc, page), 2000, page.rotation, version)
    val sigBmp = remember(version) { vm.signatureFile.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) } }
    // signature centre and width relative to the displayed page
    var cx by remember { mutableFloatStateOf(0.7f) }
    var cy by remember { mutableFloatStateOf(0.85f) }
    var w by remember { mutableFloatStateOf(0.35f) }

    Scaffold(containerColor = Brand.CameraBg, topBar = {
        TopAppBar(title = { Text(tr("Unterschrift platzieren")) },
            navigationIcon = { IconButton(onClick = { vm.goBack() }) { Icon(Icons.Filled.Close, tr("Abbrechen")) } },
            actions = { HelpButton(HelpTopic.SIGNATURE, Color.White); IconButton(onClick = { vm.replace(Screen.SignaturePad(docId, pageId)) }) { Icon(Icons.Filled.Edit, tr("Neu zeichnen")) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Brand.CameraBg, titleContentColor = Color.White,
                navigationIconContentColor = Color.White, actionIconContentColor = Color.White))
    }, bottomBar = {
        Column(Modifier.navigationBarsPadding().padding(16.dp)) {
            Text(tr("Verschieben mit einem Finger, Größe mit zwei Fingern ändern."), color = Color.White, style = MaterialTheme.typography.bodyMedium)
            Button(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), enabled = sigBmp != null && pageBmp != null, onClick = {
                val s = sigBmp!!; val p = pageBmp!!
                val hRel = w * s.height / s.width * p.width / p.height
                vm.applySignature(docId, page, toStored(listOf(cx - w / 2, cy - hRel / 2, cx + w / 2, cy + hRel / 2).map { it.toDouble() }, page.rotation))
                vm.goBack()
            }) { Text(tr("Unterschrift einfügen")) }
        }
    }) { pad ->
        BoxWithConstraints(Modifier.padding(pad).fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
            val p = pageBmp ?: run { CircularProgressIndicator(); return@BoxWithConstraints }
            val s = sigBmp ?: return@BoxWithConstraints
            val boxW = constraints.maxWidth.toFloat(); val boxH = constraints.maxHeight.toFloat()
            val scale = min(boxW / p.width, boxH / p.height)
            val dw = p.width * scale; val dh = p.height * scale
            val left = (boxW - dw) / 2; val top = (boxH - dh) / 2
            val density = LocalDensity.current
            Box(Modifier.fillMaxSize().pointerInput(dw, dh) {
                detectTransformGestures { _, pan, zoom, _ ->
                    w = (w * zoom).coerceIn(0.08f, 0.9f)
                    cx = (cx + pan.x / dw).coerceIn(0f, 1f)
                    cy = (cy + pan.y / dh).coerceIn(0f, 1f)
                }
            }) {
                Image(p.asImageBitmap(), tr("Seite"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                val sw = w * dw
                val sh = sw * s.height / s.width
                with(density) {
                    Image(s.asImageBitmap(), tr("Unterschrift"),
                        Modifier.offset { IntOffset((left + cx * dw - sw / 2).roundToInt(), (top + cy * dh - sh / 2).roundToInt()) }
                            .size(sw.toDp(), sh.toDp()).border(1.dp, Brand.Overlay))
                }
            }
        }
    }
}

/** A rectangle relative to the page as displayed (turned by [rotation]) -> relative to the stored image. */
private fun toStored(r: List<Double>, rotation: Int): List<Double> {
    fun map(u: Double, v: Double): Pair<Double, Double> = when (rotation) {
        90 -> v to 1 - u
        180 -> 1 - u to 1 - v
        270 -> 1 - v to u
        else -> u to v
    }
    val (ax, ay) = map(r[0], r[1]); val (bx, by) = map(r[2], r[3])
    return listOf(min(ax, bx), min(ay, by), max(ax, bx), max(ay, by))
}
