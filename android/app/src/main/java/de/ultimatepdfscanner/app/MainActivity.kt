package de.ultimatepdfscanner.app

import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.fragment.app.FragmentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.ui.CameraScreen
import de.ultimatepdfscanner.app.ui.CropScreen
import de.ultimatepdfscanner.app.ui.DocumentScreen
import de.ultimatepdfscanner.app.ui.HomeScreen
import de.ultimatepdfscanner.app.ui.LockScreen
import de.ultimatepdfscanner.app.ui.PageScreen
import de.ultimatepdfscanner.app.ui.RedactScreen
import de.ultimatepdfscanner.app.ui.SignScreen
import de.ultimatepdfscanner.app.ui.SignaturePadScreen
import de.ultimatepdfscanner.app.ui.ScanTheme
import de.ultimatepdfscanner.app.ui.SettingsScreen
import de.ultimatepdfscanner.app.ui.StorageScreen
import de.ultimatepdfscanner.app.ui.TrashScreen
import java.io.File

class MainActivity : FragmentActivity() {
    private val vm: ScanViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // test hook, debug builds only: adb shell am start -n de.ultimatepdfscanner.app/.MainActivity --es import_dir <folder>
        if (savedInstanceState == null && applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            intent.getStringExtra("import_dir")?.let { vm.importFiles(File(it)) }
            // adb shell am start -n de.ultimatepdfscanner.app/.MainActivity --es export_pdf <docId> [--es password <pw>]: files/export_test.pdf
            intent.getStringExtra("export_pdf")?.let { id ->
                Thread {
                    val doc = vm.storage.load(id) ?: return@Thread
                    val opts = de.ultimatepdfscanner.app.export.ExportOptions(password = intent.getStringExtra("password"))
                    val t0 = System.currentTimeMillis()
                    File(filesDir, "export_test.pdf").outputStream().use { de.ultimatepdfscanner.app.export.Exporter.writePdf(vm.storage, doc, it, opts) }
                    android.util.Log.i("UltimatePDFScanner", "test export ${doc.pages.size} pages in ${System.currentTimeMillis() - t0} ms")
                }.start()
            }
            // adb shell am start -n de.ultimatepdfscanner.app/.MainActivity --es export_docx <docId>: files/export_test.docx
            intent.getStringExtra("export_docx")?.let { id ->
                Thread {
                    val t0 = System.currentTimeMillis()
                    val files = kotlinx.coroutines.runBlocking { vm.exportFiles(id, de.ultimatepdfscanner.app.export.ExportOptions(format = de.ultimatepdfscanner.app.export.ExportFormat.DOCX)) }
                    files.firstOrNull()?.copyTo(File(filesDir, "export_test.docx"), overwrite = true)
                    android.util.Log.i("UltimatePDFScanner", "test docx export in ${System.currentTimeMillis() - t0} ms: ${files.size} file(s)")
                }.start()
            }
        }
        if (savedInstanceState == null) handleStart(intent)
        setContent {
            // Arabic and Urdu: the whole layout mirrors (reading direction right to left)
            val direction = if (I18n.rtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
                ScanTheme { de.ultimatepdfscanner.app.ui.ExplainHost { App(vm) } }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleStart(intent)
    }

    /** Tile, widget and launcher shortcuts: open the camera in the requested mode. */
    private fun handleStart(intent: android.content.Intent?) {
        if (intent == null) return
        // Android re-delivers the task's original intent when it recreates the app from the recents list
        if (intent.flags and android.content.Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        QuickAccess.mode(intent.getStringExtra(QuickAccess.EXTRA))?.let { vm.startCamera(it) }
        intent.removeExtra(QuickAccess.EXTRA)
        // "Senden an" from another app: files become a new document, a shared link is downloaded
        when (intent.action) {
            android.content.Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val stream = intent.getParcelableExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)
                if (stream != null) vm.importUris(null, listOf(stream))
                else intent.getStringExtra(android.content.Intent.EXTRA_TEXT)?.let { text ->
                    Regex("""https?://\S+""").find(text)?.let { vm.importLink(null, it.value) } ?: vm.toast(tr("Kein Link im geteilten Text"))
                }
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                intent.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM)?.let { vm.importUris(null, it) }
            }
        }
        intent.action = null // handled: a recreated activity must not import again
    }

    override fun onStart() { super.onStart(); vm.onAppStarted() }

    override fun onStop() { super.onStop(); vm.onAppStopped() }

    /** In the camera, volume keys and Bluetooth remotes take a photo instead of changing the volume. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (vm.screen.value is Screen.Camera && RemoteShutter.isShutterKey(event.keyCode)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.triggerShutter()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    @Composable
    private fun App(vm: ScanViewModel) {
        val screen by vm.screen.collectAsStateWithLifecycle()
        val work by vm.work.collectAsStateWithLifecycle()
        val locked by vm.locked.collectAsStateWithLifecycle()
        val settings by vm.settings.collectAsStateWithLifecycle()
        // with the lock enabled, hide the content in the recent-apps preview and from screenshots
        DisposableEffect(settings.appLock) {
            if (settings.appLock) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
        if (locked) { LockScreen { AppLock.prompt(this@MainActivity) { vm.unlock() } }; return }
        BackHandler(enabled = screen != Screen.Home) {
            (screen as? Screen.Camera)?.let { cam -> if (cam.retakePhotoId == null) cam.docId?.let { vm.dropEmpty(it) } }
            if (!vm.goBack()) finish()
        }
        // processing and shooting take minutes: keep the display on so the phone does not suspend the work
        val keepOn = work.isNotEmpty() || screen is Screen.Camera
        DisposableEffect(keepOn) {
            if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        when (val s = screen) {
            Screen.Home -> HomeScreen(vm)
            Screen.Settings -> SettingsScreen(vm)
            Screen.Trash -> TrashScreen(vm)
            Screen.SendToPc -> de.ultimatepdfscanner.app.ui.SendToPcScreen(vm)
            Screen.OcrLanguages -> de.ultimatepdfscanner.app.ui.OcrLanguagesScreen(vm)
            Screen.Glossaries -> de.ultimatepdfscanner.app.ui.GlossariesScreen(vm)
            Screen.StorageOverview -> StorageScreen(vm)
            is Screen.Folder -> HomeScreen(vm, s.folderId)
            is Screen.Camera -> CameraScreen(vm, s.docId, s.retakePhotoId)
            is Screen.Document -> DocumentScreen(vm, s.docId)
            is Screen.Page -> PageScreen(vm, s.docId, s.pageId)
            is Screen.Crop -> CropScreen(vm, s.docId, s.pageId)
            is Screen.Redact -> RedactScreen(vm, s.docId, s.pageId)
            is Screen.SignaturePad -> SignaturePadScreen(vm, s.docId, s.pageId)
            is Screen.Sign -> SignScreen(vm, s.docId, s.pageId)
            is Screen.TextEdit -> de.ultimatepdfscanner.app.ui.TextEditScreen(vm, s.docId, s.pageId)
            is Screen.Annotate -> de.ultimatepdfscanner.app.ui.AnnotateScreen(vm, s.docId, s.pageId)
        }
    }
}
