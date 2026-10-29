package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.core.Mode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Decoded, downsampled page images; keyed by file, modification time, size and rotation. */
object ImageCache {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 6).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    fun load(file: File, maxSide: Int, rotation: Int = 0): Bitmap? {
        if (!file.exists()) return null
        val key = "${file.path}|${file.lastModified()}|$maxSide|$rotation"
        cache.get(key)?.let { return it }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        if (rotation != 0) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
        cache.put(key, bmp)
        return bmp
    }
}

@Composable
fun rememberImage(file: File, maxSide: Int, rotation: Int = 0, version: Int = 0): Bitmap? =
    produceState<Bitmap?>(null, file.path, maxSide, rotation, version) {
        value = withContext(Dispatchers.IO) { ImageCache.load(file, maxSide, rotation) }
    }.value

/** The filters offered for pages; "Original" is colour with the automatic correction switched off. */
val IMAGE_MODES = listOf(Mode.COLOR, Mode.GRAY, Mode.BW)

fun modeLabel(m: Mode) = when (m) {
    Mode.COLOR -> tr("Farbe")
    Mode.GRAY -> tr("Graustufen")
    Mode.BW -> tr("Schwarz-Weiß")
    Mode.NONE -> tr("Original")
    Mode.AUTO -> tr("Automatisch")
}

fun modeLabel(m: Mode, enhance: Boolean) = if (enhance) modeLabel(m) else modeLabel(m) + " · " + tr("unkorrigiert")

/** Light status and navigation bar icons while a dark screen (camera, editors) is shown; the theme's again afterwards. */
@Composable
fun DarkSystemBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    val lightTheme = !androidx.compose.foundation.isSystemInDarkTheme()
    androidx.compose.runtime.DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
        val bars = window?.let { androidx.core.view.WindowCompat.getInsetsController(it, view) }
        bars?.isAppearanceLightStatusBars = false
        bars?.isAppearanceLightNavigationBars = false
        onDispose {
            bars?.isAppearanceLightStatusBars = lightTheme
            bars?.isAppearanceLightNavigationBars = lightTheme
        }
    }
}

/** Modal progress for short operations (PDF export, filter changes). */
@Composable
fun BusyDialog(vm: ScanViewModel) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    busy?.let {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(end = 8.dp))
            }
        })
    }
}

/** Shows view-model messages (errors, "PDF gespeichert") as snackbars. */
@Composable
fun MessageEffect(vm: ScanViewModel, host: SnackbarHostState) {
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(message) {
        message?.let { host.showSnackbar(it); vm.consumeMessage() }
    }
}
