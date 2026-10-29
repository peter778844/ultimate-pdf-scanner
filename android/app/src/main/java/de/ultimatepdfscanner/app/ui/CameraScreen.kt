package de.ultimatepdfscanner.app.ui

import de.ultimatepdfscanner.app.tr
import de.ultimatepdfscanner.app.trn
import android.Manifest
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.media.MediaActionSound
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import kotlin.math.roundToInt
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material.icons.filled.BluetoothConnected
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FormatTextdirectionLToR
import androidx.compose.material.icons.filled.FormatTextdirectionRToL
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TimerOff
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.snapshotFlow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.ultimatepdfscanner.app.CaptureMode
import de.ultimatepdfscanner.app.RemoteShutter
import de.ultimatepdfscanner.app.ScanQuality
import de.ultimatepdfscanner.app.ScanViewModel
import de.ultimatepdfscanner.app.Screen
import de.ultimatepdfscanner.core.Detect
import de.ultimatepdfscanner.core.DocType
import de.ultimatepdfscanner.core.PageTurn
import de.ultimatepdfscanner.core.SplitMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.hypot
import android.util.Size as AndroidSize
import de.ultimatepdfscanner.app.Trigger
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.WarningAmber

private const val STABLE_TOLERANCE = 0.015f // max corner movement (share of the frame) to count as steady
private const val AUTO_PAUSE_MS = 2500L // pause after a shot so the user can turn the page
private const val CARD_ASPECT = 85.6f / 54f // ID-1: identity and business cards

/** Corners tl,tr,br,bl as x0,y0,... normalised to the (upright) camera frame. */
private class FrameQuad(val pts: FloatArray, val time: Long)

/** What one analysed preview frame showed. */
/** [brightness]: mean of the frame, 0..255 (the auto exposure already did what it could). */
private class FrameInfo(val quad: FrameQuad?, val motion: Double, val code: com.google.zxing.Result?, val brightness: Double)

/** Modes available when pages are added to an existing document / a session already started. */
private val PAGE_MODES = listOf(CaptureMode.DOCUMENT, CaptureMode.BOOK)

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraScreen(vm: ScanViewModel, docId: String?, retakePhotoId: String? = null) {
    DarkSystemBars()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    // an existing document only takes more pages; a fresh session offers every mode until the first shot
    val existing = remember { docId != null }
    val existingType = remember { docId?.let { vm.doc(it)?.type } }

    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var light by remember { mutableStateOf(128.0) } // smoothed frame brightness
    var dark by remember { mutableStateOf(false) }
    var count by remember { mutableIntStateOf(0) }
    var lastPhoto by remember { mutableStateOf<File?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var quad by remember { mutableStateOf<FrameQuad?>(null) }
    var stableSince by remember { mutableLongStateOf(0L) }
    var lastShot by remember { mutableLongStateOf(0L) }
    var remote by remember { mutableStateOf(false) }
    var front by remember { mutableStateOf(false) } // passport photos: selfie camera
    var idSide by remember { mutableIntStateOf(0) } // ID card: 0 front, 1 back
    var code by remember { mutableStateOf<com.google.zxing.Result?>(null) }
    var intervalOn by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var processingPhoto by remember { mutableStateOf(false) }
    val pageTurn = remember { PageTurn() }
    var turnPhase by remember { mutableStateOf(PageTurn.Phase.READY) }
    val flash = remember { Animatable(0f) }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { sound.release(); pageTurn.reset() } }

    val mode = when {
        retakePhotoId != null || existing -> when (existingType) {
            DocType.BOOK.name -> CaptureMode.BOOK
            DocType.ID_CARD.name -> CaptureMode.ID_CARD
            DocType.BUSINESS_CARD.name -> CaptureMode.CARD
            DocType.PICTURE.name -> CaptureMode.PICTURE
            else -> if (settings.captureMode in PAGE_MODES) settings.captureMode else CaptureMode.DOCUMENT
        }
        else -> settings.captureMode
    }
    val modes = when {
        retakePhotoId != null -> emptyList()
        existing || count > 0 -> if (mode in PAGE_MODES) PAGE_MODES else emptyList()
        else -> CaptureMode.entries
    }
    val modeRef = remember { AtomicReference(mode) }
    modeRef.set(mode)
    fun setMode(m: CaptureMode) {
        if (m == mode) return
        vm.updateSettings(settings.copy(captureMode = m))
        quad = null; code = null; intervalOn = false; idSide = 0; pageTurn.reset(); turnPhase = PageTurn.Phase.READY
        front = false
    }

    val pictureMode = mode == CaptureMode.PICTURE
    val maxMegapixels = settings.cameraMegapixels.takeIf { it > 0 } // largest photo the back camera gives this app
    val imageCapture = remember(pictureMode, settings.quality) {
        // resolution from the scan quality: 5 MP (fast), 12 MP (standard, plenty for most text), the sensor's
        // maximum (small print); pictures and magazines always at the maximum
        val q = if (pictureMode) ScanQuality.ACCURATE else settings.quality
        val strategy = if (q.captureWidth == null || q.captureHeight == null) ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY
            else ResolutionStrategy(AndroidSize(q.captureWidth, q.captureHeight), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(95)
            .setResolutionSelector(ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(strategy)
                // "Maximal": also the sensor's high-resolution modes (e.g. 50 MP), which phones offer only on request
                .setAllowedResolutionMode(if (q == ScanQuality.ACCURATE) ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    else ResolutionSelector.PREFER_CAPTURE_RATE_OVER_HIGHER_RESOLUTION)
                .build())
            .build()
    }

    fun finishSession(id: String) { vm.dropEmpty(id); vm.replace(Screen.Document(id)) }

    fun setTrigger(t: Trigger) {
        if (t == settings.trigger) return
        vm.updateSettings(settings.copy(trigger = t))
        pageTurn.reset(); turnPhase = PageTurn.Phase.READY; stableSince = 0L
    }

    fun capture() {
        if (capturing || !granted || mode == CaptureMode.QR) return
        capturing = true
        sound.play(MediaActionSound.SHUTTER_CLICK)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        scope.launch { flash.snapTo(0.8f); flash.animateTo(0f, tween(250)) }
        if (mode == CaptureMode.PASSPORT) { // processed right here, not in the document pipeline
            val tmp = File(context.cacheDir, "passport.jpg")
            imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(tmp).build(), ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        processingPhoto = true
                        scope.launch {
                            val photo = PassportCapture.process(context, tmp)
                            capturing = false
                            if (photo == null) { processingPhoto = false; vm.toast(tr("Kein Gesicht erkannt – bitte noch einmal")); return@launch }
                            vm.addPassportPhoto(photo) { id -> processingPhoto = false; vm.replace(Screen.Document(id)) }
                        }
                    }
                    override fun onError(e: ImageCaptureException) { capturing = false }
                })
            return
        }
        val doc = vm.cameraDocument(mode.docType)
        val (photoId, file) = vm.nextPhotoFile(doc)
        imageCapture.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturing = false
                    if (retakePhotoId != null) { // one shot replaces the page, then back to the overview
                        vm.retakeTaken(doc.id, retakePhotoId, photoId)
                        vm.goBack()
                        return
                    }
                    val split = when (mode) {
                        CaptureMode.BOOK -> SplitMode.BOOK // two pages unless it is a cover, receipt or single sheet
                        CaptureMode.ID_CARD, CaptureMode.CARD, CaptureMode.PICTURE -> SplitMode.SINGLE
                        else -> settings.split
                    }
                    vm.photoTaken(doc.id, photoId, split, rtl = mode == CaptureMode.BOOK && settings.rtl)
                    count++; lastPhoto = file
                    lastShot = System.currentTimeMillis(); stableSince = 0L
                    pageTurn.shot(); turnPhase = pageTurn.phase
                    when (mode) {
                        CaptureMode.ID_CARD -> if (idSide == 0) idSide = 1 else finishSession(doc.id)
                        CaptureMode.CARD -> finishSession(doc.id)
                        else -> {}
                    }
                }
                override fun onError(e: ImageCaptureException) { capturing = false }
            })
    }
    val currentCapture by rememberUpdatedState(::capture)

    // hardware keys and Bluetooth remotes (forwarded by MainActivity)
    LaunchedEffect(Unit) { vm.shutter.collect { currentCapture() } }
    // show when a remote is paired
    LaunchedEffect(Unit) { while (true) { remote = RemoteShutter.remoteConnected(context); delay(2000) } }

    // auto-capture: shoot when the outline has been steady for the chosen time (Settings.autoDelayMs)
    val stableMs = settings.autoDelayMs.toLong()
    val autoCapture = settings.trigger == Trigger.AUTO && mode in setOf(CaptureMode.DOCUMENT, CaptureMode.ID_CARD, CaptureMode.CARD)
    val pageTurnOn = settings.trigger == Trigger.AUTO && mode == CaptureMode.BOOK
    LaunchedEffect(quad, autoCapture, stableMs) {
        if (quad == null || !autoCapture) return@LaunchedEffect
        val now = System.currentTimeMillis()
        if (stableSince == 0L) stableSince = now
        if (now - stableSince >= stableMs && now - lastShot >= AUTO_PAUSE_MS && !capturing) currentCapture()
    }
    // countdown of the next automatic shot (drawn around the page outline)
    var shotProgress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(mode, autoCapture, pageTurnOn, stableMs) {
        while (true) {
            withFrameMillis { }
            val now = System.currentTimeMillis()
            shotProgress = when {
                quad == null || capturing -> 0f
                mode == CaptureMode.BOOK -> if (pageTurnOn) pageTurn.progress(now) else 0f
                autoCapture && stableSince > 0L && now - lastShot >= AUTO_PAUSE_MS -> ((now - stableSince).toFloat() / stableMs).coerceIn(0f, 1f)
                else -> 0f
            }
        }
    }

    // interval timer: a shot every N seconds while running (started with the shutter)
    val timerMode = settings.timer > 0 && mode in PAGE_MODES
    LaunchedEffect(intervalOn, settings.timer) {
        while (intervalOn && settings.timer > 0) {
            for (s in settings.timer downTo 1) { countdown = s; delay(1000); if (!intervalOn) break }
            countdown = 0
            if (intervalOn) currentCapture()
        }
        countdown = 0
    }

    fun close() {
        if (retakePhotoId != null) { vm.goBack(); return }
        val id = (vm.screen.value as? Screen.Camera)?.docId
        if (id == null) vm.goBack() else finishSession(id)
    }

    Box(Modifier.fillMaxSize().background(Brand.CameraBg)) {
        Column(Modifier.fillMaxSize()) {
            // top bar: close, title/step, mode options
            Row(Modifier.statusBarsPadding().fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = ::close, modifier = Modifier.explain("Schließen", "Beendet die Aufnahme. Bereits fotografierte Seiten bleiben erhalten und werden verarbeitet.")) {
                    Icon(Icons.Filled.Close, tr("Schließen"), tint = Color.White)
                }
                if (retakePhotoId != null) Text(tr("Seite erneut scannen"), color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
                else if (mode in QUALITY_MODES) QualityChip(settings.quality, maxMegapixels) { vm.updateSettings(settings.copy(quality = it)) }
                Spacer(Modifier.weight(1f))
                if (remote) Icon(Icons.Filled.BluetoothConnected, tr("Fernauslöser verbunden"), tint = Brand.Overlay, modifier = Modifier.padding(8.dp))
                when (mode) {
                    CaptureMode.BOOK -> {
                        TimerButton(settings.timer) { vm.updateSettings(settings.copy(timer = nextTimer(settings.timer))); intervalOn = false }
                        TopToggle(if (settings.rtl) Icons.Filled.FormatTextdirectionRToL else Icons.Filled.FormatTextdirectionLToR,
                            if (settings.rtl) tr("Leserichtung rechts nach links") else tr("Leserichtung links nach rechts"), settings.rtl) {
                            vm.updateSettings(settings.copy(rtl = !settings.rtl))
                        }
                    }
                    CaptureMode.DOCUMENT -> TimerButton(settings.timer) { vm.updateSettings(settings.copy(timer = nextTimer(settings.timer))); intervalOn = false }
                    CaptureMode.ID_CARD, CaptureMode.CARD -> {}
                    CaptureMode.PASSPORT -> IconButton(onClick = { front = !front; torch = false }) { Icon(Icons.Filled.Cameraswitch, tr("Kamera wechseln"), tint = Color.White) }
                    CaptureMode.QR, CaptureMode.PICTURE -> {}
                }
                HelpButton(HelpTopic.CAMERA, Color.White)
                if (!front) IconButton(onClick = { torch = !torch; camera?.cameraControl?.enableTorch(torch) },
                    modifier = Modifier.explain("Licht", "Schaltet die Taschenlampe ein oder aus. Bei glänzendem Papier besser ohne Licht und schräg zum Fenster fotografieren.")) {
                    Icon(if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, tr("Licht"), tint = if (torch) Brand.Overlay else Color.White)
                }
            }

            // preview (4:3, what you see is what is captured) with the mode's guide
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                // full width if there is room, else full height: never taller than the space between the bars
                Box(Modifier.aspectRatio(3f / 4f)) {
                    if (granted) {
                        CameraPreview(imageCapture, front, modeRef, onCamera = { cam ->
                            camera = cam
                            if (!front) megapixelsOf(cam)?.let { mp -> if (mp != settings.cameraMegapixels) vm.updateSettings(settings.copy(cameraMegapixels = mp)) }
                            if (!front) focalFromCamera(cam)?.let { f -> if (kotlin.math.abs(f - settings.cameraFocal) > 1e-3) vm.updateSettings(settings.copy(cameraFocal = f)) }
                        }) { info ->
                            val prev = quad
                            quad = info.quad
                            light = 0.9 * light + 0.1 * info.brightness
                            dark = if (dark) light < 60 else light < 40 // a gap, so the hint does not flicker
                            if (info.quad == null || prev == null || maxMove(prev.pts, info.quad.pts) > STABLE_TOLERANCE) stableSince = 0L
                            if (info.code != null && code == null && modeRef.get() == CaptureMode.QR) {
                                code = info.code
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            if (modeRef.get() == CaptureMode.BOOK && settings.trigger == Trigger.AUTO) {
                                val shoot = pageTurn.update(info.motion, info.quad != null, System.currentTimeMillis())
                                turnPhase = pageTurn.phase
                                if (shoot && !capturing) currentCapture()
                            }
                        }
                        when (mode) {
                            CaptureMode.QR -> QrFinder()
                            CaptureMode.PASSPORT -> PassportGuide()
                            CaptureMode.ID_CARD, CaptureMode.CARD -> { CardGuide(); PageOutline(quad, shotProgress) }
                            else -> PageOutline(quad, shotProgress)
                        }
                    } else {
                        Text(tr("Bitte Kamerazugriff erlauben."), color = Color.White, modifier = Modifier.align(Alignment.Center))
                    }
                    Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))
                    if (dark && !torch && !front && mode != CaptureMode.PASSPORT) {
                        TextButton(onClick = { torch = true; camera?.cameraControl?.enableTorch(true) },
                            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp).background(Color(0x99000000), RoundedCornerShape(50))) {
                            Icon(Icons.Filled.FlashOn, null, tint = Brand.Overlay)
                            Text(" " + tr("Wenig Licht – Lampe einschalten"), color = Color.White)
                        }
                    }
                    Hint(hintFor(mode, quad != null, autoCapture, pageTurnOn, turnPhase, idSide, settings.trigger == Trigger.REMOTE, remote), Modifier.align(Alignment.TopCenter).padding(top = 12.dp))
                    if (countdown > 0) Text("$countdown", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center).alpha(0.85f))
                    if (processingPhoto) Text(tr("Passfoto wird zugeschnitten …"), color = Color.White, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.Center).background(Color(0x99000000), RoundedCornerShape(12.dp)).padding(16.dp))
                    if (mode == CaptureMode.ID_CARD && idSide == 1) {
                        TextButton(onClick = { (vm.screen.value as? Screen.Camera)?.docId?.let(::finishSession) },
                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)) { Text(tr("Ohne Rückseite fertig"), color = Color.White) }
                    }
                }
            }

            if (mode in QUALITY_MODES && retakePhotoId == null) TriggerSwitch(settings.trigger, book = mode == CaptureMode.BOOK, settings.autoDelayMs, ::setTrigger)
            if (autoCapture && retakePhotoId == null) AutoDelaySlider(settings.autoDelayMs) { vm.updateSettings(settings.copy(autoDelayMs = it)) }
            // mode carousel
            if (modes.size > 1) ModeCarousel(modes, mode, ::setMode)
            else Spacer(Modifier.height(12.dp))

            // controls
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 28.dp, end = 28.dp, top = 12.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris ->
                    if (uris.isEmpty()) return@rememberLauncherForActivityResult
                    if (mode == CaptureMode.QR) { scope.launch { code = QrCodes.decode(context, uris.first()) ?: run { vm.toast(tr("Kein Code im Bild gefunden")); null } }; return@rememberLauncherForActivityResult }
                    val id = vm.cameraDocument(mode.docType).id
                    vm.importUris(id, uris)
                    vm.replace(Screen.Document(id))
                }
                val galleryAllowed = retakePhotoId == null && mode != CaptureMode.PASSPORT
                if (!galleryAllowed) Spacer(Modifier.size(56.dp))
                else IconButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.explain("Aus Galerie", "Übernimmt vorhandene Fotos statt neuer Aufnahmen.").size(56.dp).background(Color(0x33FFFFFF), CircleShape)) {
                    Icon(Icons.Filled.PhotoLibrary, tr("Aus Galerie"), tint = Color.White)
                }
                when {
                    mode == CaptureMode.QR -> Box(Modifier.size(78.dp))
                    timerMode -> Box(Modifier.explain("Serie", "Startet oder stoppt die Serie: Der Timer macht dann automatisch alle paar Sekunden ein Foto.")) {
                        Shutter(enabled = granted, running = intervalOn) { intervalOn = !intervalOn }
                    }
                    else -> Box(Modifier.explain("Auslöser", "Macht ein Foto. Die Lautstärketasten und ein Bluetooth-Fernauslöser lösen ebenfalls aus.")) {
                        Shutter(enabled = granted && !capturing, onClick = ::capture)
                    }
                }
                if (retakePhotoId != null || mode == CaptureMode.QR || mode == CaptureMode.PASSPORT) Spacer(Modifier.size(56.dp))
                else Box(Modifier.explain("Fertig", "Beendet die Aufnahme und öffnet die Übersicht; die Zahl zeigt die Fotos dieser Aufnahme.")) {
                    Thumbnail(lastPhoto, count, onClick = ::close)
                }
            }
        }
    }

    code?.let { c -> QrResultSheet(c, onDismiss = { code = null }) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    MessageEffect(vm, snackbar)
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 120.dp), contentAlignment = Alignment.BottomCenter) {
        androidx.compose.material3.SnackbarHost(snackbar)
    }
}

/** Modes whose photos go through the document pipeline at the chosen scan quality (pictures always use the maximum). */
private val QUALITY_MODES = setOf(CaptureMode.DOCUMENT, CaptureMode.BOOK, CaptureMode.ID_CARD, CaptureMode.CARD)

fun qualityLabel(q: ScanQuality, maxMegapixels: Int? = null) = when (q) {
    ScanQuality.FAST -> tr("Schnell")
    ScanQuality.STANDARD -> tr("Standard")
    ScanQuality.ACCURATE -> tr("Maximal") + (maxMegapixels?.let { " ($it MP)" } ?: "")
}

/** Photos above [ScanViewModel.SLOW_MEGAPIXELS] take much longer to process: "Maximal" asks first. */
fun slowMaximum(maxMegapixels: Int?) = (maxMegapixels ?: 0) > ScanViewModel.SLOW_MEGAPIXELS

@Composable
fun SlowQualityDialog(maxMegapixels: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.WarningAmber, null, tint = Status.Warn) },
        title = { Text(tr("Sehr hohe Auflösung")) },
        text = { Text(tr("Mit %d MP dauert die Verarbeitung jeder Seite deutlich länger und braucht viel Speicher. Für die meisten Dokumente reicht „Standard“. „Maximal“ lohnt sich nur für sehr kleine Schrift.", maxMegapixels)) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(tr("Trotzdem verwenden")) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("Abbrechen")) } })
}

/** Photo size of a quality step in megapixels; [max] = what the camera gives (null: not known yet). */
fun qualityMegapixels(q: ScanQuality, max: Int?): Int? = when (q) {
    ScanQuality.ACCURATE -> max
    else -> max?.let { minOf(q.megapixels, it) } ?: q.megapixels
}

/** Scan quality right in the camera: the current step with its photo size, the steps in a menu. */
@Composable
private fun QualityChip(quality: ScanQuality, maxMegapixels: Int?, onPick: (ScanQuality) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var confirmSlow by remember { mutableStateOf(false) }
    if (confirmSlow && maxMegapixels != null) SlowQualityDialog(maxMegapixels, onConfirm = { confirmSlow = false; onPick(ScanQuality.ACCURATE) }) { confirmSlow = false }
    fun size(q: ScanQuality) = qualityMegapixels(q, maxMegapixels)?.let { "$it MP" } ?: tr("volle Auflösung")
    Box(Modifier.padding(start = 4.dp)) {
        Row(Modifier.clip(RoundedCornerShape(50)).background(Brand.Blue)
            .explain("Bildqualität", "Wie viele Pixel jedes Foto hat. Schnell (5 MP) für große Schrift, Standard (12 MP) für fast alles, Maximal für Kleingedrucktes und Formeln – braucht länger.")
            .clickable(onClickLabel = tr("Bildqualität")) { open = true }
            .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.HighQuality, null, tint = Color.White, modifier = Modifier.size(18.dp))
            // the step's name is in the menu; the photo size alone keeps the bar narrow enough for small phones
            Text(size(quality), color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).semantics { contentDescription = tr("Bildqualität") + ": " + qualityLabel(quality) })
            Icon(Icons.Filled.ArrowDropDown, null, tint = Color.White)
        }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            Text(tr("Bildqualität"), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            ScanQuality.entries.forEach { q ->
                androidx.compose.material3.DropdownMenuItem(
                    text = {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(if (q == ScanQuality.ACCURATE) qualityLabel(q, maxMegapixels) else qualityLabel(q) + " · " + size(q),
                                    fontWeight = if (q == quality) FontWeight.SemiBold else null)
                                if (q == ScanQuality.ACCURATE && slowMaximum(maxMegapixels))
                                    Icon(Icons.Filled.WarningAmber, tr("langsam"), tint = Status.Warn, modifier = Modifier.padding(start = 6.dp).size(18.dp))
                            }
                            Text(when (q) {
                                ScanQuality.FAST -> tr("Briefe, Ausdrucke, große Schrift")
                                ScanQuality.STANDARD -> tr("Bücher und die meisten Dokumente (empfohlen)")
                                ScanQuality.ACCURATE -> tr("Kleingedrucktes, Formeln, alte Bücher – langsamer")
                            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { androidx.compose.material3.RadioButton(selected = q == quality, onClick = null) },
                    onClick = { open = false; if (q == ScanQuality.ACCURATE && q != quality && slowMaximum(maxMegapixels)) confirmSlow = true else onPick(q) })
            }
            Text(tr("Gilt auch für alle weiteren Scans."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** Largest JPEG the back camera delivers to this app, in megapixels (some phones keep their 50 MP mode to themselves). */
@OptIn(ExperimentalCamera2Interop::class)
private fun megapixelsOf(camera: Camera): Int? = runCatching {
    val map = Camera2CameraInfo.from(camera.cameraInfo).getCameraCharacteristic(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
    // high-resolution sizes (50 MP sensor modes) are listed separately from the normal ones
    val sizes: List<AndroidSize> = map?.getOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty() +
        map?.getHighResolutionOutputSizes(android.graphics.ImageFormat.JPEG)?.toList().orEmpty()
    sizes.maxOfOrNull { it.width.toLong() * it.height }?.let { ((it + 500_000) / 1_000_000).toInt() }
}.getOrNull()

private fun nextTimer(t: Int) = when (t) { 0 -> 3; 3 -> 5; 5 -> 10; else -> 0 }

private fun hintFor(mode: CaptureMode, found: Boolean, auto: Boolean, pageTurn: Boolean, phase: PageTurn.Phase, idSide: Int,
                    remoteMode: Boolean, remote: Boolean): String = when {
    remoteMode && mode in QUALITY_MODES && !remote -> tr("Kein Fernauslöser verbunden – in den Bluetooth-Einstellungen koppeln")
    remoteMode && mode in QUALITY_MODES && found -> tr("Mit dem Fernauslöser auslösen")
    else -> hintForMode(mode, found, auto, pageTurn, phase, idSide)
}

private fun hintForMode(mode: CaptureMode, found: Boolean, auto: Boolean, pageTurn: Boolean, phase: PageTurn.Phase, idSide: Int): String = when (mode) {
    CaptureMode.QR -> tr("QR-Code oder Barcode in den Rahmen halten")
    CaptureMode.PASSPORT -> tr("Gesicht ins Oval · gerade schauen · heller Hintergrund")
    CaptureMode.ID_CARD -> if (idSide == 0) tr("Vorderseite des Ausweises in den Rahmen") else tr("Jetzt die Rückseite")
    CaptureMode.CARD -> tr("Visitenkarte in den Rahmen legen")
    CaptureMode.PICTURE -> tr("Bild flach hinlegen · kein Blitz · Spiegelungen vermeiden")
    CaptureMode.BOOK -> when {
        !found -> tr("Aufgeschlagenes Buch ins Bild halten")
        !pageTurn -> tr("Doppelseite erkannt")
        phase == PageTurn.Phase.TURN_PAGE -> tr("Umblättern – löst danach automatisch aus")
        phase == PageTurn.Phase.HOLD_STILL -> tr("Ruhig halten …")
        else -> tr("Ruhig halten – wird automatisch ausgelöst")
    }
    CaptureMode.DOCUMENT -> when {
        !found -> tr("Seite ins Bild halten")
        auto -> tr("Ruhig halten – wird automatisch ausgelöst")
        else -> tr("Seite erkannt")
    }
}

@Composable
private fun TopToggle(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label, tint = if (on) Brand.Overlay else Color.White) }
}

/** The three ways to shoot, side by side: on the shutter, automatically, by a Bluetooth remote. */
@Composable
private fun TriggerSwitch(trigger: Trigger, book: Boolean, autoDelayMs: Int, onChange: (Trigger) -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 10.dp).height(40.dp).clip(shape)
        .border(1.dp, Color.White.copy(alpha = 0.35f), shape)) {
        listOf(
            Triple(Trigger.MANUAL, Icons.Filled.TouchApp, tr("Manuell")),
            Triple(Trigger.AUTO, Icons.Filled.AutoMode, if (book) tr("Umblättern") else tr("Auto %s s", de.ultimatepdfscanner.app.seconds(autoDelayMs))),
            Triple(Trigger.REMOTE, Icons.Filled.Bluetooth, tr("Bluetooth")),
        ).forEach { (t, icon, label) ->
            val on = t == trigger
            Row(Modifier.weight(1f).fillMaxHeight().background(if (on) Brand.Blue else Color.Transparent)
                .explain(when (t) {
                    Trigger.MANUAL -> "Manuell"
                    Trigger.AUTO -> "Automatisch"
                    Trigger.REMOTE -> "Bluetooth"
                }, when (t) {
                    Trigger.MANUAL -> "Fotografiert nur, wenn du auf den Auslöser tippst."
                    Trigger.AUTO -> "Fotografiert von selbst, sobald die Seite ruhig im Bild liegt; die Wartezeit (0,2–1,5 s) stellst du darunter ein. Im Buch-Modus: nach jedem Umblättern, sobald das Buch wieder still liegt."
                    Trigger.REMOTE -> "Fotografiert mit einem Bluetooth-Fernauslöser (z. B. „AB Shutter 3“). Den Auslöser vorher in den Bluetooth-Einstellungen des Handys koppeln."
                })
                .clickable { onChange(t) }.semantics { stateDescription = if (on) tr("an") else tr("aus") },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = if (on) Color.White else Color(0xCCFFFFFF), modifier = Modifier.size(18.dp))
                Text(label, color = if (on) Color.White else Color(0xCCFFFFFF), style = MaterialTheme.typography.labelLarge,
                    maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** How long the page must lie still before the automatic shot: 0.2-1.5 s in steps of 0.1 s. */
@Composable
private fun AutoDelaySlider(ms: Int, onChange: (Int) -> Unit) {
    var value by remember(ms) { mutableFloatStateOf(ms.toFloat()) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 4.dp)
        .explain("Wartezeit", "Wie lange die Seite ruhig im Bild liegen muss, bevor automatisch fotografiert wird. Kürzer geht schneller, länger gibt weniger verwackelte Fotos."),
        verticalAlignment = Alignment.CenterVertically) {
        Text(tr("Auslösen nach %s s", de.ultimatepdfscanner.app.seconds(value.toInt())), color = Color.White,
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(150.dp))
        androidx.compose.material3.Slider(value = value, onValueChange = { value = it },
            onValueChangeFinished = { onChange((value / 100).roundToInt() * 100) },
            valueRange = de.ultimatepdfscanner.app.AUTO_DELAY_MIN_MS.toFloat()..de.ultimatepdfscanner.app.AUTO_DELAY_MAX_MS.toFloat(),
            steps = (de.ultimatepdfscanner.app.AUTO_DELAY_MAX_MS - de.ultimatepdfscanner.app.AUTO_DELAY_MIN_MS) / 100 - 1,
            colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Brand.Overlay, inactiveTrackColor = Color(0x55FFFFFF)),
            modifier = Modifier.weight(1f).semantics { contentDescription = tr("Wartezeit") })
    }
}

@Composable
private fun TimerButton(seconds: Int, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50))
        .explain("Intervall-Timer", "Tippen schaltet um: aus, 3, 5 oder 10 Sekunden. Danach startet der Auslöser eine Serie mit einem Foto in diesem Abstand – praktisch für Bücher.")
        .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (seconds > 0) Icons.Filled.Timer else Icons.Filled.TimerOff, tr("Intervall-Timer"), tint = if (seconds > 0) Brand.Overlay else Color.White)
        if (seconds > 0) Text("${seconds}s", color = Brand.Overlay, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 2.dp))
    }
}

@Composable
/**
 * Mode bar like a camera app: the selected mode sits in the centre, the others line up beside
 * it; tapping a mode or swiping the bar (it snaps to the centre) switches.
 */
private fun ModeCarousel(modes: List<CaptureMode>, selected: CaptureMode, onSelect: (CaptureMode) -> Unit) {
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    val fling = androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior(state, androidx.compose.foundation.gestures.snapping.SnapPosition.Center)
    val current by rememberUpdatedState(selected)
    val currentModes by rememberUpdatedState(modes)
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val side = maxWidth / 2 - 50.dp // lets the first and last mode reach the centre
        /** Index of the item closest to the centre of the bar. */
        fun centred(): Int? {
            val info = state.layoutInfo
            val mid = (info.viewportStartOffset + info.viewportEndOffset) / 2
            return info.visibleItemsInfo.minByOrNull { kotlin.math.abs(it.offset + it.size / 2 - mid) }?.index
        }
        // keep the selected mode centred (also after a tap)
        LaunchedEffect(selected, modes) {
            val i = modes.indexOf(selected).coerceAtLeast(0)
            val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i }
            if (info == null) state.scrollToItem(i)
            val item = state.layoutInfo.visibleItemsInfo.firstOrNull { it.index == i } ?: return@LaunchedEffect
            val mid = (state.layoutInfo.viewportStartOffset + state.layoutInfo.viewportEndOffset) / 2
            state.animateScrollBy((item.offset + item.size / 2 - mid).toFloat())
        }
        // a swipe selects whatever stops in the centre. Only a swipe by the user: the bar also scrolls by
        // itself (re-centring, or the list shrinking to Dokument/Buch after the first shot), and that
        // must never switch the mode - it used to jump to another mode after the first photo.
        var swiped by remember { mutableStateOf(false) }
        LaunchedEffect(state) {
            state.interactionSource.interactions.collect { if (it is androidx.compose.foundation.interaction.DragInteraction.Start) swiped = true }
        }
        LaunchedEffect(state) {
            snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
                if (!scrolling && swiped) {
                    swiped = false
                    centred()?.let { currentModes.getOrNull(it) }?.let { if (it != current) onSelect(it) }
                }
            }
        }
        androidx.compose.foundation.lazy.LazyRow(state = state, flingBehavior = fling,
            contentPadding = PaddingValues(horizontal = side.coerceAtLeast(0.dp)), verticalAlignment = Alignment.CenterVertically) {
            items(modes.size) { k ->
                val m = modes[k]
                val on = m == selected
                Box(Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(50)).background(if (on) Brand.Blue else Color.Transparent)
                    .clickable { onSelect(m) }.padding(horizontal = 12.dp, vertical = 7.dp)) {
                    Text(tr(m.label), color = if (on) Color.White else Color(0xCCFFFFFF), fontSize = 13.sp,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Medium, letterSpacing = 1.sp)
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, modifier: Modifier) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center,
        modifier = modifier.padding(horizontal = 16.dp).background(Color(0x99000000), RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp))
}

/** Shutter; with the interval timer it starts/stops the series (red square while running). */
@Composable
private fun Shutter(enabled: Boolean, running: Boolean? = null, onClick: () -> Unit) {
    Box(Modifier.size(78.dp).border(4.dp, Color.White, CircleShape).padding(7.dp).clip(CircleShape)
        .background(if (!enabled) Color(0xFF9E9E9E) else if (running != null) Color(0xFFE53935) else Color.White)
        .clickable(enabled = enabled, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center) {
        when (running) {
            true -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(Color.White))
            false -> Icon(Icons.Filled.Timer, tr("Serie starten"), tint = Color.White)
            null -> {}
        }
    }
}

@Composable
private fun Thumbnail(file: File?, count: Int, onClick: () -> Unit) {
    Box(Modifier.size(56.dp)) {
        val bmp = file?.let { rememberImage(it, 160, version = count) }
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).background(Color(0x33FFFFFF)).border(2.dp, Color.White, RoundedCornerShape(10.dp))
            .clickable(enabled = count > 0, onClick = onClick)) {
            bmp?.let { Image(it.asImageBitmap(), tr("Fertig"), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        if (count > 0) Badge(Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp), containerColor = Brand.Blue) {
            Text("$count", color = Color.White)
        }
    }
}

/** Live outline of the detected page over the preview. */
@Composable
/**
 * Live outline of the detected page. While an automatic shot is counting down ([countdown]
 * 0..1), a yellow line runs once around the outline and the area fills up; when it closes the
 * photo is taken.
 */
private fun PageOutline(quad: FrameQuad?, countdown: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val q = quad?.pts ?: return@Canvas
        val path = Path().apply {
            moveTo(q[0] * size.width, q[1] * size.height)
            for (i in 1 until 4) lineTo(q[2 * i] * size.width, q[2 * i + 1] * size.height)
            close()
        }
        val green = Brand.Overlay
        val yellow = Color(0xFFFFD54F)
        drawPath(path, green.copy(alpha = 0.18f))
        drawPath(path, green, style = Stroke(width = 3.dp.toPx(), join = StrokeJoin.Round))
        if (countdown > 0f) {
            drawPath(path, yellow.copy(alpha = 0.35f * countdown))
            val measure = PathMeasure().apply { setPath(path, false) }
            val run = Path()
            measure.getSegment(0f, measure.length * countdown, run, true)
            drawPath(run, yellow, style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        for (i in 0 until 4) drawCircle(if (countdown > 0f) yellow else green, 6.dp.toPx(), Offset(q[2 * i] * size.width, q[2 * i + 1] * size.height))
    }
}

/** Dims everything outside [cutout] (relative rect) and draws its border. */
@Composable
private fun GuideMask(cutout: (Size) -> Path, border: Color = Color.White) {
    Canvas(Modifier.fillMaxSize()) {
        val path = cutout(size)
        clipPath(path, clipOp = ClipOp.Difference) { drawRect(Color(0x99000000)) }
        drawPath(path, border, style = Stroke(width = 2.dp.toPx()))
    }
}

@Composable
private fun CardGuide() = GuideMask({ s ->
    val w = s.width * 0.84f
    val h = w / CARD_ASPECT
    Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset((s.width - w) / 2, (s.height - h) / 2), Size(w, h)), CornerRadius(w * 0.045f))) }
}, border = Color(0xCCFFFFFF))

@Composable
private fun PassportGuide() {
    GuideMask({ s ->
        val h = s.height * 0.8f
        val w = h * 35f / 45f
        Path().apply { addRect(Rect(Offset((s.width - w) / 2, (s.height - h) / 2), Size(w, h))) }
    }, border = Color(0x66FFFFFF))
    Canvas(Modifier.fillMaxSize()) {
        val h = size.height * 0.8f
        val headH = h * 34f / 45f * 0.78f // face oval: forehead to chin
        val headW = headH * 0.72f
        val top = (size.height - h) / 2 + h * 0.09f + h * 34f / 45f * 0.18f
        drawOval(Brand.Overlay, Offset((size.width - headW) / 2, top), Size(headW, headH), style = Stroke(width = 3.dp.toPx()))
    }
}

@Composable
private fun QrFinder() {
    val t = rememberInfiniteTransition(label = "scan")
    val y by t.animateFloat(0.1f, 0.9f, infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse), label = "line")
    GuideMask({ s ->
        val side = s.width * 0.66f
        Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(Offset((s.width - side) / 2, (s.height - side) / 2), Size(side, side)), CornerRadius(24f))) }
    }, border = Color.Transparent)
    Canvas(Modifier.fillMaxSize()) {
        val side = size.width * 0.66f
        val l = (size.width - side) / 2
        val tp = (size.height - side) / 2
        val arm = side * 0.14f
        val sw = 5.dp.toPx()
        for ((cx, cy, dx, dy) in listOf(listOf(l, tp, 1f, 1f), listOf(l + side, tp, -1f, 1f), listOf(l, tp + side, 1f, -1f), listOf(l + side, tp + side, -1f, -1f))) {
            drawLine(Brand.Overlay, Offset(cx, cy), Offset(cx + dx * arm, cy), sw, StrokeCap.Round)
            drawLine(Brand.Overlay, Offset(cx, cy), Offset(cx, cy + dy * arm), sw, StrokeCap.Round)
        }
        drawLine(Brand.Overlay.copy(alpha = 0.7f), Offset(l + 16f, tp + side * y), Offset(l + side - 16f, tp + side * y), 2.dp.toPx())
    }
}

private fun maxMove(a: FloatArray, b: FloatArray) = (0 until 4).maxOf { hypot(a[2 * it] - b[2 * it], a[2 * it + 1] - b[2 * it + 1]) }

/** Focal length / sensor diagonal of the bound camera (for the page aspect ratio), via Camera2. */
@OptIn(ExperimentalCamera2Interop::class)
private fun focalFromCamera(camera: Camera): Double? = runCatching {
    val info = Camera2CameraInfo.from(camera.cameraInfo)
    val f = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
    val s = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    f / hypot(s.width, s.height).toDouble()
}.getOrNull()

@Composable
private fun CameraPreview(imageCapture: ImageCapture, front: Boolean, mode: AtomicReference<CaptureMode>, onCamera: (Camera) -> Unit, onFrame: (FrameInfo) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    // ahead of the processing of the photos already taken, which otherwise slowed the outline to ~1 s per frame
    val analysisExecutor = remember {
        Executors.newSingleThreadExecutor { r -> Thread { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY); r.run() } }
    }
    val frameCallback by rememberUpdatedState(onFrame)
    val cameraCallback by rememberUpdatedState(onCamera)
    val motion = remember { PageTurn() } // only its motion measure
    val view = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER } }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }
    // (re)bind when the lens or the capture resolution changes
    DisposableEffect(front, imageCapture) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build())
                .build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy(AndroidSize(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                    .build())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor) { proxy -> analyze(proxy, mode.get(), motion)?.let { info -> view.post { frameCallback(info) } } }
            provider.unbindAll()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching { provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture, analysis) }.getOrNull()?.let { cameraCallback(it) }
        }, ContextCompat.getMainExecutor(context))
        onDispose { }
    }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { view })
}

/** One preview frame: page outline (+ motion for the book mode) or a QR/barcode. */
private fun analyze(proxy: ImageProxy, mode: CaptureMode, motion: PageTurn): FrameInfo? = try {
    val plane = proxy.planes[0]
    val w = proxy.width
    val h = proxy.height
    val rowStride = plane.rowStride
    val buf = plane.buffer.apply { rewind() }
    val bytes = ByteArray(w * h * 4)
    if (rowStride == w * 4) buf.get(bytes) else for (r in 0 until h) { buf.position(r * rowStride); buf.get(bytes, r * w * 4, w * 4) }
    val rgba = Mat(h, w, CvType.CV_8UC4).also { it.put(0, 0, bytes) }
    val bgr = Mat()
    Imgproc.cvtColor(rgba, bgr, Imgproc.COLOR_RGBA2BGR)
    rgba.release()
    val upright = Mat()
    when (proxy.imageInfo.rotationDegrees) {
        90 -> Core.rotate(bgr, upright, Core.ROTATE_90_CLOCKWISE)
        180 -> Core.rotate(bgr, upright, Core.ROTATE_180)
        270 -> Core.rotate(bgr, upright, Core.ROTATE_90_COUNTERCLOCKWISE)
        else -> bgr.copyTo(upright)
    }
    bgr.release()
    val brightness = Core.mean(upright).`val`.take(3).average()
    val info = when (mode) {
        CaptureMode.QR -> FrameInfo(null, 0.0, QrCodes.decode(upright), brightness)
        CaptureMode.PASSPORT -> FrameInfo(null, 0.0, null, brightness)
        else -> {
            val m = if (mode == CaptureMode.BOOK) motion.motion(upright) else 0.0
            val tq = System.nanoTime()
            val q = Detect.previewQuad(upright)
            android.util.Log.d("UPSDEBUG", "previewQuad ${(System.nanoTime() - tq) / 1_000_000} ms")
            val fq = q?.let { FrameQuad(FloatArray(8) { i -> (if (i % 2 == 0) q[i / 2].x / upright.cols() else q[i / 2].y / upright.rows()).toFloat() }, System.currentTimeMillis()) }
            FrameInfo(fq, m, null, brightness)
        }
    }
    upright.release()
    android.util.Log.d("UPSDEBUG", "frame ${w}x$h mode=$mode quad=${info.quad != null} bright=${info.brightness.toInt()}")
    info
} catch (e: Throwable) {
    android.util.Log.e("UPSDEBUG", "analyze failed", e)
    null
} finally {
    proxy.close()
}
