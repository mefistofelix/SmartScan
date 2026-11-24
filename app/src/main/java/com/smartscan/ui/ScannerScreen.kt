package com.smartscan.ui

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaActionSound
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.smartscan.HardwareKeys
import com.smartscan.container
import com.smartscan.data.PageEntity
import com.smartscan.scan.AutoCaptureController
import com.smartscan.scan.AutoCaptureController.Phase
import com.smartscan.scan.Corners
import com.smartscan.scan.DocumentDetector
import com.smartscan.scan.toUprightGray
import com.smartscan.voice.VoiceCommand
import com.smartscan.voice.VoiceCommands
import com.smartscan.voice.VoiceController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.max

data class ScannerUi(
    val quad: FloatArray? = null,
    val frameW: Int = 3,
    val frameH: Int = 4,
    val phase: Phase = Phase.SEARCHING,
    val progress: Float = 0f,
    val auto: Boolean = true,
    val multi: Boolean = true,
    val torch: Boolean = false,
    val capturing: Boolean = false,
    val processing: Int = 0,
    val voiceOn: Boolean = false,
    val voiceAvailable: Boolean = false,
    val preDictation: Boolean = false,
    val lastHeard: String = "",
    val metaSummary: String? = null,
    val flashTick: Int = 0,
)

sealed interface ScanEvent {
    data class Done(val docId: Long) : ScanEvent
    data object Cancelled : ScanEvent
    data class Message(val text: String) : ScanEvent
    data object Shutter : ScanEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModel(app: Application, private val existingDocId: Long) : AndroidViewModel(app) {
    private val c = app.container
    private val settings = c.settings
    private val docIdFlow = MutableStateFlow<Long?>(null)
    private val _ui = MutableStateFlow(
        ScannerUi(auto = settings.autoCapture, multi = settings.multiPage || existingDocId > 0)
    )
    val ui: StateFlow<ScannerUi> = _ui.asStateFlow()
    val pages: StateFlow<List<PageEntity>> = docIdFlow.filterNotNull()
        .flatMapLatest { c.repo.observePages(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 8)

    private val controller = AutoCaptureController()
    private val executor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var grayBuf: ByteArray? = null
    @Volatile private var lastSig = ByteArray(0)
    @Volatile private var smoothed: FloatArray? = null
    private val voice = VoiceController(app, settings.voiceLanguage)
    val voicePartial = voice.partial
    private var preDone = false
    private var finishing = false

    init {
        viewModelScope.launch {
            docIdFlow.value = if (existingDocId > 0) existingDocId else c.repo.createDraft()
        }
        _ui.update { it.copy(voiceAvailable = voice.available) }
    }

    // ---------- camera ----------

    suspend fun bindCamera(ctx: Context, owner: LifecycleOwner, view: PreviewView) {
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val f = ProcessCameraProvider.getInstance(ctx)
            f.addListener({ cont.resume(f.get()) }, ContextCompat.getMainExecutor(ctx))
        }
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
        preview.setSurfaceProvider(view.surfaceProvider)
        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
            )
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setAspectRatioStrategy(ratio)
                    .setResolutionStrategy(
                        ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                    ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            try {
                val gray = proxy.toUprightGray(grayBuf)
                val q = DocumentDetector.detect(gray)
                val sharp = DocumentDetector.sharpness(gray)
                val sig = DocumentDetector.signature(gray)
                val w = gray.cols(); val h = gray.rows()
                gray.release()
                val norm = q?.let { pts ->
                    FloatArray(8) { i -> if (i % 2 == 0) (pts[i / 2].x / w).toFloat() else (pts[i / 2].y / h).toFloat() }
                }
                onAnalysis(norm, sharp, sig, w, h)
            } catch (t: Throwable) {
                Log.w("Scanner", "analysis", t)
            } finally {
                proxy.close()
            }
        }
        provider.unbindAll()
        camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        imageCapture = capture
        camera?.cameraControl?.enableTorch(_ui.value.torch)
    }

    fun focusAt(view: PreviewView, x: Float, y: Float) {
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    private fun onAnalysis(q: FloatArray?, sharp: Double, sig: ByteArray, w: Int, h: Int) {
        lastSig = sig
        val prev = smoothed
        val s = if (q == null) null else if (prev == null || Corners.maxDelta(prev, q) > 0.08f) q else Corners.lerp(prev, q, 0.5f)
        smoothed = s
        val d = controller.onFrame(q, sharp, sig, SystemClock.elapsedRealtime())
        _ui.update { it.copy(quad = s, frameW = w, frameH = h, phase = d.phase, progress = if (it.auto) d.progress else 0f) }
        if (d.capture) {
            if (_ui.value.auto && !_ui.value.preDictation) viewModelScope.launch { capture(auto = true) }
            else controller.reset()
        }
    }

    fun capture(auto: Boolean = false) {
        val ic = imageCapture ?: return
        val id = docIdFlow.value ?: return
        if (_ui.value.capturing || finishing) { if (auto) controller.reset(); return }
        _ui.update { it.copy(capturing = true) }
        val dir = c.repo.docDir(id)
        val raw = File(dir, "raw_${System.nanoTime()}.jpg")
        val hint = smoothed?.copyOf()
        val sig = lastSig
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(raw).build(),
            ContextCompat.getMainExecutor(getApplication()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    controller.onCaptured(sig, SystemClock.elapsedRealtime())
                    events.tryEmit(ScanEvent.Shutter)
                    _ui.update { it.copy(capturing = false, processing = it.processing + 1, flashTick = it.flashTick + 1) }
                    viewModelScope.launch {
                        try {
                            c.repo.addCapturedPage(id, raw, hint, settings.defaultFilter, settings.autoDeskew)
                        } catch (e: Exception) {
                            Log.e("Scanner", "process", e)
                            events.tryEmit(ScanEvent.Message("Errore elaborazione: ${e.message}"))
                        } finally {
                            _ui.update { it.copy(processing = it.processing - 1) }
                        }
                    }
                    if (!_ui.value.multi) finish()
                }

                override fun onError(exception: ImageCaptureException) {
                    controller.reset()
                    _ui.update { it.copy(capturing = false) }
                    events.tryEmit(ScanEvent.Message("Scatto non riuscito: ${exception.message}"))
                }
            }
        )
    }

    fun finish() {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            ui.first { it.processing == 0 && !it.capturing }
            val id = docIdFlow.value ?: run { events.emit(ScanEvent.Cancelled); return@launch }
            voice.stop()
            if (c.repo.pageCount(id) == 0) {
                if (existingDocId <= 0) c.repo.deleteDoc(id)
                events.emit(ScanEvent.Cancelled)
            } else {
                events.emit(ScanEvent.Done(id))
            }
        }
    }

    /** Voice "nuovo documento": save the current one and keep scanning into a fresh draft. */
    private fun newDocument() {
        viewModelScope.launch {
            ui.first { it.processing == 0 && !it.capturing }
            val id = docIdFlow.value ?: return@launch
            if (c.repo.pageCount(id) > 0) {
                c.repo.markProcessing(id, null, null)
                c.sync.enqueueFinalize(id)
                docIdFlow.value = c.repo.createDraft()
                controller.reset()
                _ui.update { it.copy(metaSummary = null) }
                events.emit(ScanEvent.Message("Documento salvato · nuovo documento"))
            }
        }
    }

    private fun deleteLast() {
        viewModelScope.launch {
            pages.value.lastOrNull()?.let { c.repo.deletePage(it); events.emit(ScanEvent.Message("Ultima pagina eliminata")) }
        }
    }

    fun toggleAuto() {
        val v = !_ui.value.auto
        settings.autoCapture = v
        controller.reset()
        _ui.update { it.copy(auto = v, progress = 0f) }
    }

    fun toggleMulti() {
        val v = !_ui.value.multi
        settings.multiPage = v
        _ui.update { it.copy(multi = v) }
    }

    fun toggleTorch() {
        val v = !_ui.value.torch
        camera?.cameraControl?.enableTorch(v)
        _ui.update { it.copy(torch = v) }
    }

    // ---------- voice ----------

    fun onPermissions(audioGranted: Boolean) {
        if (!audioGranted || !voice.available) return
        if (settings.voiceMetaPre && !preDone) startPreDictation()
        else if (settings.voiceCommands) startCommands()
    }

    private fun startPreDictation() {
        _ui.update { it.copy(preDictation = true) }
        voice.listenOnce { text ->
            preDone = true
            _ui.update { it.copy(preDictation = false) }
            if (!text.isNullOrBlank()) applyMeta(text)
            if (settings.voiceCommands) startCommands()
        }
    }

    fun skipPreDictation() {
        preDone = true
        voice.stop()
        _ui.update { it.copy(preDictation = false) }
        if (settings.voiceCommands) startCommands()
    }

    fun toggleVoice() {
        if (_ui.value.voiceOn) {
            voice.stop(); _ui.update { it.copy(voiceOn = false) }
        } else startCommands()
    }

    private fun startCommands() {
        voice.startContinuous { onVoice(it) }
        _ui.update { it.copy(voiceOn = true) }
    }

    private fun onVoice(text: String) {
        _ui.update { it.copy(lastHeard = text) }
        when (val cmd = VoiceCommands.parse(text)) {
            VoiceCommand.Capture -> capture()
            VoiceCommand.Finish -> finish()
            VoiceCommand.NewDocument -> newDocument()
            VoiceCommand.DeleteLast -> deleteLast()
            VoiceCommand.ToggleAuto -> toggleAuto()
            VoiceCommand.ToggleFlash -> toggleTorch()
            is VoiceCommand.Metadata -> applyMeta(cmd.text)
            VoiceCommand.Unknown -> Unit
        }
    }

    private fun applyMeta(text: String) {
        viewModelScope.launch {
            val id = docIdFlow.first { it != null }!!
            val p = c.repo.applyVoiceMetadata(id, text, settings.createUnknownTags)
            _ui.update { it.copy(metaSummary = p.summary()) }
        }
    }

    override fun onCleared() {
        voice.destroy()
        executor.shutdown()
        super.onCleared()
    }
}

@Composable
fun ScannerScreen(docId: Long, onDone: (Long) -> Unit, onCancel: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ScannerViewModel = viewModel(key = "scanner_$docId") { ScannerViewModel(app, docId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val partial by vm.voicePartial.collectAsStateWithLifecycle()
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val settings = remember { context.container.settings }
    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }

    fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    var hasCamera by remember { mutableStateOf(granted(Manifest.permission.CAMERA)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        hasCamera = hasCamera || res[Manifest.permission.CAMERA] == true
        vm.onPermissions(res[Manifest.permission.RECORD_AUDIO] == true || granted(Manifest.permission.RECORD_AUDIO))
    }
    LaunchedEffect(Unit) {
        val needed = buildList {
            if (!hasCamera) add(Manifest.permission.CAMERA)
            if (settings.wantsVoice && !granted(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
        }
        if (needed.isNotEmpty()) launcher.launch(needed.toTypedArray())
        else vm.onPermissions(granted(Manifest.permission.RECORD_AUDIO) && settings.wantsVoice)
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ScanEvent.Done -> onDone(e.docId)
                ScanEvent.Cancelled -> onCancel()
                is ScanEvent.Message -> Toast.makeText(context, e.text, Toast.LENGTH_SHORT).show()
                ScanEvent.Shutter -> {
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    if (settings.shutterSound) sound.play(MediaActionSound.SHUTTER_CLICK)
                }
            }
        }
    }
    DisposableEffect(Unit) {
        HardwareKeys.shutterEnabled = true
        onDispose { HardwareKeys.shutterEnabled = false; sound.release() }
    }
    LaunchedEffect(Unit) { HardwareKeys.events.collect { vm.capture() } }
    BackHandler { vm.finish() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            LaunchedEffect(previewView) { vm.bindCamera(context, owner, previewView) }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            QuadOverlay(ui, Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures { p -> vm.focusAt(previewView, p.x, p.y) }
            })
        } else {
            Text(
                "Serve il permesso fotocamera", color = Color.White,
                modifier = Modifier.align(Alignment.Center).clickable { launcher.launch(arrayOf(Manifest.permission.CAMERA)) }
            )
        }

        // Capture flash
        var flashTarget by remember { mutableStateOf(0f) }
        LaunchedEffect(ui.flashTick) { if (ui.flashTick > 0) { flashTarget = 0.7f; delay(90); flashTarget = 0f } }
        val flashAlpha by animateFloatAsState(flashTarget, tween(120), label = "flash")
        if (flashAlpha > 0f) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = flashAlpha)))

        // Top bar
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = { vm.finish() }) { Icon(Icons.Filled.Close, "Chiudi", tint = Color.White) }
            Spacer(Modifier.weight(1f))
            ScanChip("Auto", ui.auto) { vm.toggleAuto() }
            Spacer(Modifier.width(6.dp))
            ScanChip(if (ui.multi) "Multipagina" else "Singola", ui.multi) { vm.toggleMulti() }
            IconButton(onClick = { vm.toggleTorch() }) {
                Icon(if (ui.torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff, "Flash", tint = Color.White)
            }
            if (ui.voiceAvailable) IconButton(onClick = { vm.toggleVoice() }) {
                Icon(
                    if (ui.voiceOn) Icons.Filled.Mic else Icons.Filled.MicOff, "Comandi vocali",
                    tint = if (ui.voiceOn) MaterialTheme.colorScheme.tertiary else Color.White
                )
            }
        }

        // Hints
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val hint = when {
                ui.preDictation -> "Detta i metadati: «titolo … categoria … tag …»"
                !ui.auto -> "Premi il pulsante o un tasto volume"
                ui.phase == Phase.WAIT_NEXT -> "Pagina acquisita · metti la prossima"
                ui.phase == Phase.STEADYING -> "Tieni fermo…"
                ui.phase == Phase.CAPTURING -> "Scatto…"
                else -> "Inquadra il documento"
            }
            Pill(hint + if (ui.processing > 0) "  ·  elaboro ${ui.processing}" else "")
            val heard = partial.ifBlank { ui.lastHeard }
            if (ui.voiceOn && heard.isNotBlank()) { Spacer(Modifier.size(6.dp)); Pill("🎙 $heard") }
            ui.metaSummary?.let { Spacer(Modifier.size(6.dp)); Pill(it) }
        }

        if (ui.preDictation) {
            Column(
                Modifier.align(Alignment.Center).clip(RoundedCornerShape(24.dp)).background(Color(0xCC000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(48.dp))
                Text(partial.ifBlank { "In ascolto…" }, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.size(12.dp))
                Button(onClick = { vm.skipPreDictation() }) { Text("Salta") }
            }
        }

        // Bottom controls (thumb zone)
        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(64.dp).clickable(enabled = pages.isNotEmpty()) { vm.finish() }) {
                pages.lastOrNull()?.let { last ->
                    BadgedBox(badge = { Badge { Text("${pages.size}") } }) {
                        AsyncImage(
                            model = File(last.processedPath), contentDescription = "Ultima pagina",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, Color.White, RoundedCornerShape(8.dp))
                        )
                    }
                }
            }
            ShutterButton(progress = ui.progress, busy = ui.capturing) { vm.capture() }
            Button(onClick = { vm.finish() }, enabled = pages.isNotEmpty() || ui.processing > 0) {
                Icon(Icons.Filled.Check, null)
                Spacer(Modifier.width(4.dp))
                Text("Fine")
            }
        }
    }
}

@Composable
private fun ScanChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick, label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = Color.White,
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
private fun Pill(text: String) {
    Text(
        text, color = Color.White, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

@Composable
private fun ShutterButton(progress: Float, busy: Boolean, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.tertiary
    Box(
        Modifier.size(84.dp).clip(CircleShape).clickable(enabled = !busy, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = 5.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.5f), style = Stroke(sw))
            if (progress > 0f) drawArc(
                ring, -90f, 360f * progress, false, style = Stroke(sw),
                topLeft = Offset(sw / 2, sw / 2),
                size = androidx.compose.ui.geometry.Size(size.width - sw, size.height - sw)
            )
            drawCircle(if (busy) Color.LightGray else Color.White, radius = size.minDimension / 2 - 10.dp.toPx())
        }
    }
}

@Composable
private fun QuadOverlay(ui: ScannerUi, modifier: Modifier) {
    val color = when (ui.phase) {
        Phase.STEADYING, Phase.CAPTURING -> Color(0xFF4CAF50)
        Phase.WAIT_NEXT -> Color(0xFFFFC107)
        else -> MaterialTheme.colorScheme.primary
    }
    Canvas(modifier) {
        val q = ui.quad ?: return@Canvas
        val iw = ui.frameW.toFloat()
        val ih = ui.frameH.toFloat()
        val s = max(size.width / iw, size.height / ih)
        val dx = (size.width - iw * s) / 2
        val dy = (size.height - ih * s) / 2
        fun p(i: Int) = Offset(q[i * 2] * iw * s + dx, q[i * 2 + 1] * ih * s + dy)
        val path = Path().apply {
            moveTo(p(0).x, p(0).y); lineTo(p(1).x, p(1).y); lineTo(p(2).x, p(2).y); lineTo(p(3).x, p(3).y); close()
        }
        drawPath(path, color.copy(alpha = 0.22f))
        drawPath(path, color, style = Stroke(3.dp.toPx()))
        for (i in 0 until 4) drawCircle(color, 6.dp.toPx(), p(i))
    }
}
