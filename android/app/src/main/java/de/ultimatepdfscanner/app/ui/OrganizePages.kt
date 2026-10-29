package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.data.DocInfo
import kotlinx.coroutines.delay

/**
 * Organise mode: tap selects pages, holding and dragging a page moves it. The order is kept
 * locally while dragging and saved on drop.
 */
@Composable
fun OrganizeGrid(vm: ScanViewModel, doc: DocInfo, version: Int, selected: Set<String>, onToggle: (String) -> Unit, modifier: Modifier) {
    val state = rememberLazyGridState()
    var order by remember(doc.pages) { mutableStateOf(doc.pages.map { it.id }) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var pointer by remember { mutableStateOf(Offset.Zero) }
    var grab by remember { mutableStateOf(Offset.Zero) } // pointer position inside the dragged tile
    val edge = with(LocalDensity.current) { 72.dp.toPx() }
    val byId = doc.pages.associateBy { it.id }

    fun itemAt(p: Offset) = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
        p.x >= info.offset.x && p.x < info.offset.x + info.size.width && p.y >= info.offset.y && p.y < info.offset.y + info.size.height
    }

    // scroll while a page is held near the top or bottom edge
    LaunchedEffect(dragKey) {
        while (dragKey != null) {
            val h = state.layoutInfo.viewportSize.height
            val dy = when { pointer.y < edge -> -18f; pointer.y > h - edge -> 18f; else -> 0f }
            if (dy != 0f) state.scrollBy(dy)
            delay(16)
        }
    }

    LazyVerticalGrid(GridCells.Adaptive(120.dp), modifier.pointerInput(doc.id) {
        detectDragGesturesAfterLongPress(
            onDragStart = { p ->
                itemAt(p)?.takeIf { (it.key as? String) in byId }?.let { info ->
                    dragKey = info.key as String
                    pointer = p
                    grab = p - Offset(info.offset.x.toFloat(), info.offset.y.toFloat())
                }
            },
            onDrag = { change, amount ->
                val key = dragKey ?: return@detectDragGesturesAfterLongPress
                change.consume()
                pointer += amount
                val target = itemAt(pointer)?.key as? String
                if (target != null && target != key && target in byId) {
                    order = order.toMutableList().apply { remove(key); add(order.indexOf(target), key) }
                }
            },
            onDragEnd = { if (dragKey != null) vm.reorder(doc.id, order); dragKey = null },
            onDragCancel = { dragKey = null; order = doc.pages.map { it.id } },
        )
    }, state = state, contentPadding = PaddingValues(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(tr("Tippen zum Auswählen · gedrückt halten und ziehen zum Verschieben"), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
        }
        items(order.mapNotNull { byId[it] }, key = { it.id }) { page ->
            val dragging = page.id == dragKey
            val number = order.indexOf(page.id) + 1
            val isSel = page.id in selected
            val offset = if (dragging) state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == page.id }?.let { info ->
                pointer - grab - Offset(info.offset.x.toFloat(), info.offset.y.toFloat())
            } ?: Offset.Zero else Offset.Zero
            Column(Modifier.zIndex(if (dragging) 1f else 0f).graphicsLayer {
                translationX = offset.x; translationY = offset.y
                scaleX = if (dragging) 1.06f else 1f; scaleY = if (dragging) 1.06f else 1f
            }.then(if (dragging) Modifier else Modifier.animateItem())) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.71f).shadow(if (dragging) 12.dp else 0.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp)).background(Color.White)
                    .border(if (isSel) 3.dp else 1.dp, if (isSel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                    .clickable { onToggle(page.id) }) {
                    rememberImage(vm.storage.pageFile(doc, page), 360, page.rotation, version)?.let {
                        Image(it.asImageBitmap(), tr("Seite %d", number), Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                    }
                    Icon(if (isSel) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked, null,
                        tint = if (isSel) MaterialTheme.colorScheme.primary else Color(0xFF9AA0AA),
                        modifier = Modifier.align(Alignment.TopStart).padding(6.dp).size(24.dp).clip(RoundedCornerShape(50)).background(Color.White))
                    Box(Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)).padding(horizontal = 7.dp, vertical = 2.dp)) {
                        Text("$number", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
    }
}

/** Bottom bar actions of the organise mode (act on the selection). */
@Composable
fun RowScope.OrganizeActions(count: Int, onRotate: () -> Unit, onMove: () -> Unit, onCopy: () -> Unit, onExtract: () -> Unit, onDelete: () -> Unit) {
    val enabled = count > 0
    OrgAction(Icons.Filled.RotateRight, tr("Drehen"), enabled, onRotate)
    OrgAction(Icons.AutoMirrored.Filled.DriveFileMove, tr("Verschieben"), enabled, onMove)
    OrgAction(Icons.Filled.ContentCopy, tr("Kopieren"), enabled, onCopy)
    OrgAction(Icons.Filled.NoteAdd, tr("Neuer Scan"), enabled, onExtract)
    OrgAction(Icons.Filled.Delete, tr("Löschen"), enabled, onDelete)
}

@Composable
private fun RowScope.OrgAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = tint)
        Text(label, style = MaterialTheme.typography.labelSmall, color = tint, maxLines = 1)
    }
}

/** Pick the document that receives moved/copied pages. */
@Composable
fun TargetDocPicker(vm: ScanViewModel, docs: List<DocInfo>, exclude: String, move: Boolean, onPick: (DocInfo?) -> Unit, onDismiss: () -> Unit) {
    val candidates = docs.filter { !it.inTrash && it.id != exclude && it.photos.isNotEmpty() }
    AlertDialog(onDismissRequest = onDismiss, icon = { Icon(if (move) Icons.AutoMirrored.Filled.DriveFileMove else Icons.Filled.ContentCopy, null) },
        title = { Text(if (move) tr("Verschieben nach …") else tr("Kopieren nach …")) },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth()) {
                item {
                    ListItem(headlineContent = { Text(tr("Neuer Scan")) }, leadingContent = { Icon(Icons.Filled.NoteAdd, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(null) })
                }
                items(candidates.size) { i ->
                    val d = candidates[i]
                    ListItem(headlineContent = { Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(trn(d.pages.size, "1 Seite", "%d Seiten")) },
                        leadingContent = {
                            Box(Modifier.size(32.dp, 42.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                                d.pages.firstOrNull()?.let { p -> rememberImage(vm.storage.pageFile(d, p), 120, p.rotation) }?.let {
                                    Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                }
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent), modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onPick(d) })
                }
            }
        },
        confirmButton = {}, dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } })
}

