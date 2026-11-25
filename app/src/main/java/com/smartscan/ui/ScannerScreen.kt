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
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Badge
import androidx.compose.material3.Button
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
import com.smartscan.data.DocOutput
import com.smartscan.data.PageEntity
import com.smartscan.data.Repository
import com.smartscan.scan.AutoCaptureController
import com.smartscan.scan.AutoCaptureController.Phase
import com.smartscan.scan.DocumentDetector
import com.smartscan.scan.LocationTracker
import com.smartscan.scan.QuadTracker
import com.smartscan.scan.toUprightGray
import com.smartscan.voice.VoiceCommand
import com.smartscan.voice.VoiceCommands
import com.smartscan.voice.VoiceController
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
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
import kotlin.math.min

enum class Dictation { PRE, POST, NOTE }

/** A voice note of the document being scanned and the page it was attached to. */
data class VoiceNote(val text: String, val pageId: Long?)

data class ScannerUi(
    val quad: FloatArray? = null,
    val frameW: Int = 3,
    val frameH: Int = 4,
    val phase: Phase = Phase.SEARCHING,
    val progress: Float = 0f,
    val auto: Boolean = true,
    val torch: Boolean = false,
    /** Quick toggles, persisted in AppSettings. */
    val hq: Boolean = false,
    val edges: Boolean = true,
    val deskew: Boolean = true,
    val geo: Boolean = false,
    /** Preview fills the screen (crops the sides) or shows the whole captured frame. */
    val fullPreview: Boolean = true,
    /** Voice notes added to the current (not yet saved) document; each one can be deleted. */
    val notes: List<VoiceNote> = emptyList(),
    val showNotes: Boolean = false,
    val capturing: Boolean = false,
    val processing: Int = 0,
    val voiceOn: Boolean = false,
    val voiceAvailable: Boolean = false,
    val dictation: Dictation? = null,
    val lastHeard: String = "",
    val metaSummary: String? = null,
    val flashTick: Int = 0,
    /** Output of the document being scanned, chosen before «Fine». Kept for the next documents. */
    val output: DocOutput = DocOutput.PDF,
    val savedCount: Int = 0,
    val notice: String? = null,
    val showPages: Boolean = false,
    val saving: Boolean = false,
)

sealed interface ScanEvent {
    data object Closed : ScanEvent
    data class Message(val text: String) : ScanEvent
    data object Shutter : ScanEvent
    data object NeedAudio : ScanEvent
    data object NeedLocation : ScanEvent
}

/**
 * Continuous scanning: pages go into the current draft; «Fine» saves it (OCR + uploads in background)
 * and scanning goes on into a new draft right away. When [existingDocId] > 0 the scanner only adds
 * pages to that document and «Fine» returns to it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScannerViewModel(app: Application, private val existingDocId: Long) : AndroidViewModel(app) {
    private val c = app.container
    private val settings = c.settings
    private val docIdFlow = MutableStateFlow<Long?>(null)
    private val _ui = MutableStateFlow(
        ScannerUi(
            auto = settings.autoCapture, hq = settings.maxQuality,
            edges = settings.edgeDetect, deskew = settings.edgeDetect && settings.autoDeskew,
            geo = settings.geoTag,
            fullPreview = settings.fullPreview,
        )
    )
    val ui: StateFlow<ScannerUi> = _ui.asStateFlow()
    val pages: StateFlow<List<PageEntity>> = docIdFlow.filterNotNull()
        .flatMapLatest { c.repo.observePages(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val events = MutableSharedFlow<ScanEvent>(extraBufferCapacity = 8)
    val addingToExisting = existingDocId > 0

    private val controller = AutoCaptureController()
    private val tracker = QuadTracker()
    private val location = LocationTracker(app)
    private val executor = Executors.newSingleThreadExecutor()
    private var imageCapture: ImageCapture? = null
    private var camera: Camera? = null
    private var grayBuf: ByteArray? = null
    @Volatile private var lastSig = ByteArray(0)
    @Volatile private var smoothed: FloatArray? = null
    private val voice = VoiceController(app, settings.voiceLanguage)
    val voicePartial = voice.partial
    private var audioOk = false
    private var dictationCont: CancellableContinuation<String?>? = null
    private var noticeJob: Job? = null
    private var closing = false

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
        binding = Triple(provider, owner, view)
        bindUseCases()
    }

    private var binding: Triple<ProcessCameraProvider, LifecycleOwner, PreviewView>? = null
    private var torchRetries = 0

    /** (Re)binds preview, capture and analysis. Called again when «Qualità max» changes the capture mode. */
    private fun bindUseCases() {
        val (provider, owner, view) = binding ?: return
        val ratio = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        val preview = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(ratio).build())
            .build()
        preview.setSurfaceProvider(view.surfaceProvider)
        val capture = ImageCapture.Builder()
            .setCaptureMode(
                if (_ui.value.hq) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
            )
            // The light is a torch (always on while enabled), never a flash fired at capture time.
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
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
                val q = if (_ui.value.edges) DocumentDetector.detect(gray) else null
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
        val cam = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        camera = cam
        imageCapture = capture
        // Some devices (Samsung) drop a torch request sent before the session streams, or switch the
        // torch off after a capture: apply it again whenever the camera opens or the torch goes off.
        cam.cameraInfo.cameraState.removeObservers(owner)
        cam.cameraInfo.torchState.removeObservers(owner)
        cam.cameraInfo.cameraState.observe(owner) { st ->
            if (st.type == CameraState.Type.OPEN && _ui.value.torch) applyTorch()
        }
        cam.cameraInfo.torchState.observe(owner) { st ->
            if (st == TorchState.ON) torchRetries = 0
            else if (_ui.value.torch && !closing && torchRetries < 3) {
                torchRetries++
                main.postDelayed({ if (_ui.value.torch) applyTorch() }, 300)
            }
        }
        applyTorch()
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    private fun applyTorch() {
        val cam = camera ?: return
        val on = _ui.value.torch
        if (on && !cam.cameraInfo.hasFlashUnit()) {
            _ui.update { it.copy(torch = false) }
            notify("Questa fotocamera non ha la luce")
            return
        }
        val f = cam.cameraControl.enableTorch(on)
        f.addListener({
            runCatching { f.get() }.onFailure { e ->
                // A newer enableTorch call cancels this one: nothing to retry.
                if (e.cause is androidx.camera.core.CameraControl.OperationCanceledException) return@addListener
                Log.w("Scanner", "torch", e)
                if (_ui.value.torch && torchRetries < 3) {
                    torchRetries++
                    main.postDelayed({ applyTorch() }, 400)
                }
            }
        }, ContextCompat.getMainExecutor(getApplication()))
    }

    fun focusAt(view: PreviewView, x: Float, y: Float) {
        val point = view.meteringPointFactory.createPoint(x, y)
        camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(point).build())
    }

    private fun onAnalysis(q: FloatArray?, sharp: Double, sig: ByteArray, w: Int, h: Int) {
        lastSig = sig
        val s = tracker.update(q)
        smoothed = s
        val d = controller.onFrame(s, sharp, sig, SystemClock.elapsedRealtime())
        _ui.update { it.copy(quad = s, frameW = w, frameH = h, phase = d.phase, progress = if (it.auto) d.progress else 0f) }
        if (d.capture) {
            val u = _ui.value
            if (u.auto && u.dictation == null && !u.saving) viewModelScope.launch { capture(auto = true) }
            else controller.reset()
        }
    }

    fun capture(auto: Boolean = false) {
        val ic = imageCapture ?: return
        val id = docIdFlow.value ?: return
        if (_ui.value.capturing || _ui.value.saving || closing) { if (auto) controller.reset(); return }
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
                    if (_ui.value.torch) { torchRetries = 0; applyTorch() }
                    controller.onCaptured(sig, SystemClock.elapsedRealtime())
                    events.tryEmit(ScanEvent.Shutter)
                    _ui.update { it.copy(capturing = false, processing = it.processing + 1, flashTick = it.flashTick + 1) }
                    val u = _ui.value
                    val loc = if (u.geo) location.current() else null
                    viewModelScope.launch {
                        try {
                            c.repo.addCapturedPage(
                                id, raw, hint?.takeIf { u.edges }, settings.defaultFilter, u.deskew,
                                detect = u.edges, hq = u.hq, lat = loc?.latitude, lon = loc?.longitude,
                            )
                        } catch (e: Exception) {
                            Log.e("Scanner", "process", e)
                            events.tryEmit(ScanEvent.Message("Errore elaborazione: ${e.message}"))
                        } finally {
                            _ui.update { it.copy(processing = it.processing - 1) }
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    controller.reset()
                    _ui.update { it.copy(capturing = false) }
                    events.tryEmit(ScanEvent.Message("Scatto non riuscito: ${exception.message}"))
                }
            }
        )
    }

    // ---------- documents ----------

    fun toggleOutput() {
        _ui.update { it.copy(output = if (it.output == DocOutput.PDF) DocOutput.IMAGES else DocOutput.PDF) }
    }

    /** «Fine»: save the current document and keep scanning into a new one. */
    fun finishDocument() {
        if (addingToExisting) { close(); return }
        if (_ui.value.saving || closing) return
        _ui.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                ui.first { it.processing == 0 && !it.capturing }
                val id = docIdFlow.value ?: return@launch
                if (c.repo.pageCount(id) == 0) return@launch
                if (settings.voiceMetaPost && voiceReady()) {
                    _ui.update { it.copy(dictation = Dictation.POST) }
                    val text = dictate()
                    _ui.update { it.copy(dictation = null) }
                    if (!text.isNullOrBlank()) c.repo.applyVoiceMetadata(id, text, settings.createUnknownTags)
                }
                val n = save(id)
                docIdFlow.value = c.repo.createDraft()
                controller.reset()
                tracker.reset()
                _ui.update { it.copy(metaSummary = null, showPages = false, notes = emptyList(), showNotes = false, savedCount = it.savedCount + 1) }
                notify("Documento salvato · $n ${if (n == 1) "pagina" else "pagine"} · ${_ui.value.output.label}")
                // Straight back to the camera: no dictation prompt in between (pre-dictation only when the scanner opens).
                if (!closing) resumeCommands()
            } finally {
                _ui.update { it.copy(saving = false) }
            }
        }
    }

    /** Leaving the scanner: pending pages are saved, never thrown away. */
    fun close() {
        if (closing) return
        closing = true
        skipDictation()
        voice.stop()
        viewModelScope.launch {
            ui.first { it.processing == 0 && !it.capturing && !it.saving }
            val id = docIdFlow.value
            if (id != null && !addingToExisting) {
                if (c.repo.pageCount(id) == 0) c.repo.deleteDoc(id)
                else {
                    save(id)
                    events.emit(ScanEvent.Message("Documento salvato"))
                }
            }
            events.emit(ScanEvent.Closed)
        }
    }

    private suspend fun save(id: Long): Int {
        val n = c.repo.pageCount(id)
        c.repo.markProcessing(id, null, null, _ui.value.output)
        c.sync.enqueueFinalize(id)
        return n
    }

    fun togglePages() = _ui.update { it.copy(showPages = !it.showPages && pages.value.isNotEmpty(), showNotes = false) }

    fun deletePage(p: PageEntity) {
        viewModelScope.launch {
            c.repo.deletePage(p)
            if (pages.value.size <= 1) _ui.update { it.copy(showPages = false) }
        }
    }

    private fun deleteLast() {
        viewModelScope.launch {
            pages.value.lastOrNull()?.let { c.repo.deletePage(it); notify("Ultima pagina eliminata") }
        }
    }

    private fun notify(text: String) {
        noticeJob?.cancel()
        _ui.update { it.copy(notice = text) }
        noticeJob = viewModelScope.launch { delay(2500); _ui.update { it.copy(notice = null) } }
    }

    fun toggleAuto() {
        val v = !_ui.value.auto
        settings.autoCapture = v
        controller.reset()
        _ui.update { it.copy(auto = v, progress = 0f) }
        notify(
            when {
                !_ui.value.edges -> "Lo scatto automatico richiede i bordi attivi"
                v -> "Scatto automatico attivo"
                else -> "Scatto automatico spento"
            }
        )
    }

    fun toggleTorch() {
        _ui.update { it.copy(torch = !it.torch) }
        torchRetries = 0
        applyTorch()
        if (_ui.value.torch) {
            // Samsung (and other) phones block the LED when the battery is low, without any error to the app.
            val bm = getApplication<Application>().getSystemService(android.os.BatteryManager::class.java)
            val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            if (level in 0..15) notify("Batteria al $level%: il telefono può bloccare la luce")
        }
    }

    fun togglePreview() {
        val v = !_ui.value.fullPreview
        settings.fullPreview = v
        _ui.update { it.copy(fullPreview = v) }
        notify(if (v) "Anteprima a schermo pieno (i lati sono tagliati)" else "Anteprima del fotogramma intero")
    }

    fun toggleHq() {
        val v = !_ui.value.hq
        settings.maxQuality = v
        _ui.update { it.copy(hq = v) }
        notify(if (v) "Qualità massima: file più grandi" else "Qualità normale")
        if (!_ui.value.capturing) bindUseCases()
    }

    /**
     * One button, three states: Auto (edges + automatic shutter) -> Bordi (edges + deskew, manual shutter)
     * -> Spento (whole frame as it is, manual shutter) -> Auto.
     */
    fun cycleDetect() {
        val u = _ui.value
        val (edges, auto) = when {
            u.edges && u.auto -> true to false
            u.edges -> false to false
            else -> true to true
        }
        settings.edgeDetect = edges
        settings.autoDeskew = edges
        settings.autoCapture = auto
        controller.reset()
        tracker.reset()
        _ui.update { it.copy(edges = edges, deskew = edges, auto = auto, quad = null, progress = 0f) }
        notify(
            when {
                auto -> "Auto: bordi e scatto automatico"
                edges -> "Bordi: raddrizza, scatti tu"
                else -> "Spento: pagina intera così com'è, scatti tu"
            }
        )
    }

    /** «GPS»: the position is saved with every page while on. Asks for the permission the first time. */
    fun toggleGeo() {
        if (_ui.value.geo) {
            settings.geoTag = false
            location.stop()
            _ui.update { it.copy(geo = false) }
            notify("Posizione non salvata")
        } else if (!hasLocationPermission()) {
            events.tryEmit(ScanEvent.NeedLocation)
        } else enableGeo()
    }

    fun onLocationPermission(granted: Boolean) {
        if (granted) enableGeo() else notify("Serve il permesso posizione")
    }

    /** Called when the scanner opens: resumes tracking if «GPS» was left on. */
    fun resumeGeo() {
        if (_ui.value.geo) { if (hasLocationPermission()) location.start() else _ui.update { it.copy(geo = false) } }
    }

    private fun enableGeo() {
        settings.geoTag = true
        location.start()
        _ui.update { it.copy(geo = true) }
        notify(if (location.anyProviderEnabled) "Posizione salvata con ogni pagina" else "Attiva la localizzazione del telefono")
    }

    private fun hasLocationPermission(): Boolean {
        val ctx = getApplication<Application>()
        return listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    }

    // ---------- voice notes ----------

    /** Dictates a note for the current document; can be repeated, each note is appended. */
    fun recordNote() {
        if (_ui.value.dictation == Dictation.NOTE) { skipDictation(); return }
        if (_ui.value.dictation != null || _ui.value.saving || closing) return
        if (!voice.available) { notify("Riconoscimento vocale non disponibile"); return }
        if (!audioOk) { events.tryEmit(ScanEvent.NeedAudio); return }
        val wasOn = _ui.value.voiceOn
        viewModelScope.launch {
            _ui.update { it.copy(dictation = Dictation.NOTE) }
            val text = dictate()
            _ui.update { it.copy(dictation = null) }
            if (!text.isNullOrBlank()) {
                val id = docIdFlow.first { it != null }!!
                val pageId = pages.value.lastOrNull()?.id
                val doc = c.repo.appendNote(id, text, pageId)
                val note = VoiceNote(Repository.normalizeNote(text), pageId)
                _ui.update { it.copy(notes = it.notes + note) }
                notify("Nota aggiunta" + (doc?.title?.takeIf { it.isNotBlank() }?.let { " · «$it»" } ?: ""))
            }
            if (closing) return@launch
            if (wasOn) startCommands() else _ui.update { it.copy(voiceOn = false) }
        }
    }

    fun hideNotes() = _ui.update { it.copy(showNotes = false) }

    fun toggleNotes() = _ui.update { it.copy(showNotes = !it.showNotes && it.notes.isNotEmpty(), showPages = false) }

    fun deleteNote(n: VoiceNote) {
        viewModelScope.launch {
            val id = docIdFlow.value ?: return@launch
            c.repo.removeNote(id, n.text, n.pageId)
            _ui.update { u -> (u.notes - n).let { rest -> u.copy(notes = rest, showNotes = u.showNotes && rest.isNotEmpty()) } }
            notify("Nota eliminata")
        }
    }

    fun onAudioForNote(granted: Boolean) {
        audioOk = audioOk || granted
        if (granted) recordNote() else notify("Serve il permesso microfono per le note vocali")
    }

    // ---------- voice ----------

    private fun voiceReady() = audioOk && voice.available

    fun onPermissions(audioGranted: Boolean) {
        audioOk = audioGranted
        if (!voiceReady()) return
        if (settings.voiceMetaPre && !addingToExisting) preDictation() else resumeCommands()
    }

    private suspend fun dictate(): String? = suspendCancellableCoroutine { cont ->
        dictationCont = cont
        voice.listenOnce { text ->
            dictationCont = null
            if (cont.isActive) cont.resume(text)
        }
    }

    private fun preDictation() {
        viewModelScope.launch {
            _ui.update { it.copy(dictation = Dictation.PRE) }
            val text = dictate()
            _ui.update { it.copy(dictation = null) }
            if (!text.isNullOrBlank()) applyMeta(text)
            if (!closing) resumeCommands()
        }
    }

    fun skipDictation() {
        val cont = dictationCont ?: return
        dictationCont = null
        voice.stop()
        if (cont.isActive) cont.resume(null)
    }

    fun toggleVoice() {
        if (_ui.value.voiceOn) {
            voice.stop(); _ui.update { it.copy(voiceOn = false) }
        } else startCommands()
    }

    private fun resumeCommands() {
        if (settings.voiceCommands && voiceReady()) startCommands() else _ui.update { it.copy(voiceOn = false) }
    }

    private fun startCommands() {
        voice.startContinuous { onVoice(it) }
        _ui.update { it.copy(voiceOn = true) }
    }

    private fun onVoice(text: String) {
        _ui.update { it.copy(lastHeard = text) }
        when (val cmd = VoiceCommands.parse(text)) {
            VoiceCommand.Capture -> capture()
            VoiceCommand.Finish, VoiceCommand.NewDocument -> finishDocument()
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
        location.stop()
        main.removeCallbacksAndMessages(null)
        voice.destroy()
        executor.shutdown()
        super.onCleared()
    }
}

@Composable
fun ScannerScreen(docId: Long, onClose: () -> Unit) {
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
    val locationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        vm.onLocationPermission(res.values.any { it })
    }
    LaunchedEffect(Unit) { vm.resumeGeo() }
    // The notes list closes by itself; deleting a note restarts the timer.
    LaunchedEffect(ui.showNotes, ui.notes.size) { if (ui.showNotes) { delay(5000); vm.hideNotes() } }
    val audioLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        vm.onAudioForNote(ok)
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
                ScanEvent.Closed -> onClose()
                ScanEvent.NeedAudio -> audioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                ScanEvent.NeedLocation -> locationLauncher.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                )
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
    BackHandler {
        when {
            ui.showNotes -> vm.toggleNotes()
            ui.showPages -> vm.togglePages()
            else -> vm.close()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCamera) {
            val previewView = remember {
                PreviewView(context).apply {
                    scaleType = if (ui.fullPreview) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                }
            }
            LaunchedEffect(previewView) { vm.bindCamera(context, owner, previewView) }
            // Full screen (nicer, but a tall screen hides ~40% of the 4:3 width) or the whole frame, centered.
            AndroidView({ previewView }, Modifier.fillMaxSize()) {
                it.scaleType = if (ui.fullPreview) PreviewView.ScaleType.FILL_CENTER else PreviewView.ScaleType.FIT_CENTER
            }
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
            IconButton(onClick = { vm.close() }) {
                Icon(Icons.Filled.Folder, "Elenco documenti", tint = Color.White)
            }
            if (ui.savedCount > 0) Box(Modifier.clip(RoundedCornerShape(50)).clickable { vm.close() }) {
                Pill("✓ ${ui.savedCount}")
            }
            Spacer(Modifier.weight(1f))
            // Quick toggles as icons (a notice explains each one when tapped); remembered for next sessions.
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                ToggleIcon(
                    if (ui.fullPreview) Icons.Filled.Fullscreen else Icons.Filled.FitScreen,
                    if (ui.fullPreview) "Anteprima: schermo pieno" else "Anteprima: fotogramma intero",
                    on = !ui.fullPreview,
                ) { vm.togglePreview() }
                ToggleIcon(Icons.Filled.HighQuality, "Qualità massima", ui.hq) { vm.toggleHq() }
                // Tristate: Auto -> Bordi -> Spento
                ToggleIcon(
                    when {
                        ui.edges && ui.auto -> Icons.Filled.AutoMode
                        ui.edges -> Icons.Filled.Crop
                        else -> Icons.Filled.CropFree
                    },
                    when {
                        ui.edges && ui.auto -> "Rilevamento: Auto"
                        ui.edges -> "Rilevamento: Bordi"
                        else -> "Rilevamento: Spento"
                    },
                    on = ui.edges,
                    activeColor = if (ui.auto) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                ) { vm.cycleDetect() }
                ToggleIcon(
                    if (ui.geo) Icons.Filled.LocationOn else Icons.Filled.LocationOff, "Posizione GPS", ui.geo,
                ) { vm.toggleGeo() }
                ToggleIcon(
                    if (ui.torch) Icons.Filled.FlashlightOn else Icons.Filled.FlashlightOff, "Luce", ui.torch,
                    activeColor = Color(0xFFFFB300),
                ) { vm.toggleTorch() }
            }
        }

        // Hints
        Column(
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp, start = 24.dp, end = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Only short-lived messages: notices (2.5 s), what is happening right now, and the "next page" hint
            // for 3 s. Nothing stays on screen permanently.
            val autoOn = ui.auto && ui.edges
            val live = when {
                ui.notice != null -> ui.notice
                ui.saving -> "Salvo il documento…"
                autoOn && ui.phase == Phase.STEADYING -> "Tieni fermo…"
                autoOn && ui.phase == Phase.CAPTURING -> "Scatto…"
                else -> null
            }
            val nextHint = if (autoOn && ui.phase == Phase.WAIT_NEXT) "Pagina acquisita · metti la prossima" else null
            var showNext by remember { mutableStateOf(false) }
            LaunchedEffect(nextHint) { showNext = nextHint != null; if (nextHint != null) { delay(3000); showNext = false } }
            val line = listOfNotNull(
                live ?: nextHint?.takeIf { showNext },
                ui.processing.takeIf { it > 0 }?.let { "elaboro $it" },
            ).joinToString("  ·  ")
            if (line.isNotEmpty()) Pill(line)
            val heard = partial.ifBlank { ui.lastHeard }
            if (ui.voiceOn && heard.isNotBlank()) { Spacer(Modifier.size(6.dp)); Pill("🎙 $heard") }
            var showMeta by remember { mutableStateOf(false) }
            LaunchedEffect(ui.metaSummary) { showMeta = ui.metaSummary != null; if (showMeta) { delay(4000); showMeta = false } }
            if (showMeta) ui.metaSummary?.let { Spacer(Modifier.size(6.dp)); Pill(it) }
        }

        ui.dictation?.let { kind ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(24.dp))
                    .background(Color(0xCC000000)).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Mic, null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(48.dp))
                Text(
                    when (kind) {
                        Dictation.PRE -> "Nuovo documento: detta i metadati"
                        Dictation.POST -> "Documento finito: detta i metadati"
                        Dictation.NOTE -> "Detta la nota" + if (pages.isNotEmpty()) " (pagina ${pages.size})" else ""
                    },
                    color = Color.White, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center,
                )
                Text(partial.ifBlank { if (kind == Dictation.NOTE) "Parla, mi fermo quando smetti" else "«titolo … categoria … tag …»" }, color = Color.White, textAlign = TextAlign.Center)
                Spacer(Modifier.size(12.dp))
                Button(onClick = { vm.skipDictation() }) { Text(if (kind == Dictation.NOTE) "Annulla" else "Salta") }
            }
        }

        // Bottom: page strip (optional) + controls in the thumb zone
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()) {
            if (ui.showPages && pages.isNotEmpty()) PageStrip(pages) { vm.deletePage(it) }
            if (ui.showNotes && ui.notes.isNotEmpty()) NoteList(ui.notes, pages) { vm.deleteNote(it) }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Left: current document pages
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NoteButton(
                            ui.notes.size, active = ui.dictation == Dictation.NOTE,
                            onList = { vm.toggleNotes() },
                        ) { vm.recordNote() }
                        Spacer(Modifier.height(10.dp))
                        PagesButton(pages, ui.processing) { vm.togglePages() }
                    }
                }
                ShutterButton(progress = ui.progress, busy = ui.capturing || ui.saving) { vm.capture() }
                // Right: output format + Fine
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Column(horizontalAlignment = Alignment.End) {
                        if (!vm.addingToExisting) {
                            OutputToggle(ui.output) { vm.toggleOutput() }
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(
                            onClick = { vm.finishDocument() },
                            enabled = (pages.isNotEmpty() || ui.processing > 0) && !ui.saving,
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Filled.Check, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Fine")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tap: dictate a note for the current document (repeatable). The badge counts the notes;
 * tapping the badge or a long press shows them, each with a delete button.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteButton(count: Int, active: Boolean, onList: () -> Unit, onClick: () -> Unit) {
    Box(Modifier.size(width = 60.dp, height = 52.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(48.dp).clip(CircleShape)
                .background(if (active) MaterialTheme.colorScheme.tertiary else Color(0x99000000))
                .combinedClickable(onClick = onClick, onLongClick = { if (count > 0) onList() }),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Filled.EditNote, "Nota vocale", tint = Color.White) }
        if (count > 0) Badge(Modifier.align(Alignment.TopEnd).clickable(onClick = onList)) {
            Text("$count", modifier = Modifier.padding(horizontal = 2.dp))
        }
    }
}

/** Notes of the document being scanned, newest last; X deletes one. */
@Composable
private fun NoteList(notes: List<VoiceNote>, pages: List<PageEntity>, onDelete: (VoiceNote) -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Color(0xB3000000)).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (n in notes) {
            val page = pages.indexOfFirst { it.id == n.pageId }.takeIf { it >= 0 }?.let { "p.${it + 1} · " } ?: ""
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    page + n.text, color = Color.White, style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2, modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { onDelete(n) }) {
                    Icon(Icons.Filled.Close, "Elimina nota", tint = Color.White)
                }
            }
        }
    }
}

/** Last page thumbnail with the page count; tap to show all pages of the current document. */
@Composable
private fun PagesButton(pages: List<PageEntity>, processing: Int, onClick: () -> Unit) {
    val last = pages.lastOrNull()
    Box(Modifier.size(width = 60.dp, height = 76.dp).clickable(enabled = last != null, onClick = onClick)) {
        if (last != null) {
            if (pages.size > 1) Box(
                Modifier.size(width = 50.dp, height = 66.dp).align(Alignment.BottomEnd)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0x88FFFFFF))
            )
            AsyncImage(
                model = File(last.processedPath), contentDescription = "Pagine del documento",
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 50.dp, height = 66.dp).align(Alignment.TopStart)
                    .clip(RoundedCornerShape(6.dp)).border(2.dp, Color.White, RoundedCornerShape(6.dp))
            )
            Badge(Modifier.align(Alignment.TopEnd)) { Text("${pages.size}") }
        } else if (processing > 0) {
            Box(
                Modifier.size(width = 50.dp, height = 66.dp).clip(RoundedCornerShape(6.dp))
                    .border(2.dp, Color.White.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            )
        }
    }
}

@Composable
private fun PageStrip(pages: List<PageEntity>, onDelete: (PageEntity) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().background(Color(0x99000000)).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        itemsIndexed(pages, key = { _, p -> p.id }) { i, p ->
            Box(Modifier.size(width = 66.dp, height = 88.dp)) {
                AsyncImage(
                    model = File(p.processedPath), contentDescription = "Pagina ${i + 1}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(6.dp))
                )
                Text(
                    "${i + 1}", color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)).padding(horizontal = 4.dp)
                )
                Box(
                    Modifier.align(Alignment.TopEnd).padding(2.dp).size(24.dp).clip(CircleShape)
                        .background(Color(0xCC000000)).clickable { onDelete(p) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Close, "Elimina pagina", tint = Color.White, modifier = Modifier.size(16.dp)) }
            }
        }
    }
}

/** One tap switches the output of the current document: PDF <-> images. */
@Composable
private fun OutputToggle(output: DocOutput, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color(0x99000000)).clickable(onClick = onClick)
            .padding(3.dp),
    ) {
        for (o in DocOutput.entries) {
            val sel = o == output
            Text(
                if (o == DocOutput.PDF) "PDF" else "IMG",
                color = if (sel) MaterialTheme.colorScheme.onPrimary else Color.White,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(50))
                    .background(if (sel) MaterialTheme.colorScheme.primary else Color.Transparent)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}


/** Compact round toggle for the scanner top bar: filled when on. Long press shows its name. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ToggleIcon(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    onClick: () -> Unit,
) {
    val ctx = LocalContext.current
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(if (on) activeColor else Color(0x66000000))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { Toast.makeText(ctx, label + if (on) ": attivo" else ": spento", Toast.LENGTH_SHORT).show() },
            ),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = Color.White, modifier = Modifier.size(22.dp)) }
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
        // Same mapping as PreviewView: FILL_CENTER (full screen) or FIT_CENTER (whole frame).
        val s = if (ui.fullPreview) max(size.width / iw, size.height / ih) else min(size.width / iw, size.height / ih)
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
