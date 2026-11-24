package com.smartscan.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.smartscan.DeepLinks
import com.smartscan.container
import com.smartscan.data.EndpointEntity
import com.smartscan.data.EndpointType
import com.smartscan.data.UploadFormat
import com.smartscan.sync.EndpointConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import java.util.concurrent.Executors

class EndpointsViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val endpoints = c.repo.observeEndpoints().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val pendingAdd = MutableStateFlow<EndpointEntity?>(null)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val syncing = MutableStateFlow(setOf<Long>())

    fun parse(raw: String) {
        try {
            pendingAdd.value = EndpointConfig.parse(raw)
        } catch (e: Exception) {
            messages.tryEmit(e.message ?: "Configurazione non valida")
        }
    }

    fun confirmAdd(e: EndpointEntity) = viewModelScope.launch {
        pendingAdd.value = null
        val id = c.repo.addEndpoint(e)
        messages.emit("Endpoint «${e.name}» aggiunto")
        if (e.taxonomyUrl != null) sync(id)
    }

    fun sync(id: Long) = viewModelScope.launch {
        syncing.value = syncing.value + id
        val r = c.sync.syncTaxonomy(id)
        syncing.value = syncing.value - id
        messages.emit(r.fold({ "Tassonomia sincronizzata ($it voci)" }, { "Sync fallita: ${it.message}" }))
    }

    fun setAuto(e: EndpointEntity, v: Boolean) = viewModelScope.launch { c.repo.updateEndpoint(e.copy(autoUpload = v)) }
    fun setFormat(e: EndpointEntity, f: UploadFormat) = viewModelScope.launch { c.repo.updateEndpoint(e.copy(format = f)) }
    fun delete(e: EndpointEntity) = viewModelScope.launch { c.repo.deleteEndpoint(e) }
}

@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EndpointsScreen(onScanQr: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: EndpointsViewModel = viewModel { EndpointsViewModel(app) }
    val endpoints by vm.endpoints.collectAsStateWithLifecycle()
    val pendingAdd by vm.pendingAdd.collectAsStateWithLifecycle()
    val syncing by vm.syncing.collectAsStateWithLifecycle()
    val link by DeepLinks.pending.collectAsState()
    var manual by remember { mutableStateOf(false) }
    var toDelete by remember { mutableStateOf<EndpointEntity?>(null) }

    LaunchedEffect(link) { link?.let { DeepLinks.pending.value = null; vm.parse(it) } }
    LaunchedEffect(Unit) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Endpoint di storage") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = { IconButton(onClick = { manual = true }) { Icon(Icons.Filled.Add, "Aggiungi manualmente") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onScanQr, icon = { Icon(Icons.Filled.QrCodeScanner, null) }, text = { Text("Aggiungi da QR") })
        },
    ) { pad ->
        if (endpoints.isEmpty()) Box(Modifier.padding(pad).fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "Nessun endpoint.\nInquadra il QR di configurazione fornito dal tuo server per abilitare l'invio automatico e la tassonomia remota.",
                style = MaterialTheme.typography.bodyLarge,
            )
        } else LazyColumn(Modifier.padding(pad), contentPadding = PaddingValues(12.dp, 8.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(endpoints, key = { it.id }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(e.name, style = MaterialTheme.typography.titleMedium)
                                Text("${e.type.name} · ${Uri.parse(e.url).host}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (e.taxonomyUrl != null) IconButton(onClick = { vm.sync(e.id) }, enabled = e.id !in syncing) {
                                Icon(Icons.Filled.Sync, "Sincronizza tassonomia")
                            }
                            IconButton(onClick = { toDelete = e }) { Icon(Icons.Filled.Delete, "Rimuovi") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Invio automatico", Modifier.weight(1f))
                            Switch(checked = e.autoUpload, onCheckedChange = { vm.setAuto(e, it) })
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            UploadFormat.entries.forEach { f ->
                                FilterChip(selected = e.format == f, onClick = { vm.setFormat(e, f) }, label = { Text(f.label) })
                            }
                        }
                        val syncText = when {
                            e.taxonomyUrl == null -> "Nessuna tassonomia remota"
                            e.lastSyncError != null -> "Ultima sync fallita: ${e.lastSyncError}"
                            e.lastSync != null -> "Tassonomia aggiornata ${formatDate(e.lastSync)}"
                            else -> "Tassonomia non ancora sincronizzata"
                        }
                        Text(syncText, style = MaterialTheme.typography.bodySmall)
                        if (e.url.startsWith("http://")) Text(
                            "Attenzione: connessione non cifrata (http)", color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    pendingAdd?.let { e -> ConfirmEndpointDialog(e, onConfirm = { vm.confirmAdd(e) }, onDismiss = { vm.pendingAdd.value = null }) }
    if (manual) ManualEndpointDialog(onDismiss = { manual = false }, onSave = { manual = false; vm.pendingAdd.value = it })
    toDelete?.let { e ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Rimuovere «${e.name}»?") },
            text = { Text("Verranno rimossi anche le categorie/tag sincronizzati da questo endpoint e le loro assegnazioni.") },
            confirmButton = { TextButton(onClick = { vm.delete(e); toDelete = null }) { Text("Rimuovi") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }
}

@Composable
private fun ConfirmEndpointDialog(e: EndpointEntity, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val headers = EndpointConfig.headers(e)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungere questo endpoint?") },
        text = {
            Column {
                Text("Verifica che sia un server di cui ti fidi: i documenti verranno inviati qui.")
                Spacer(Modifier.height(8.dp))
                Text("Nome: ${e.name}", fontWeight = FontWeight.Bold)
                Text("Tipo: ${e.type.name}")
                Text("Host: ${Uri.parse(e.url).host}")
                Text("URL: ${e.url}", style = MaterialTheme.typography.bodySmall)
                e.taxonomyUrl?.let { Text("Tassonomia: ${Uri.parse(it).host}", style = MaterialTheme.typography.bodySmall) }
                if (headers.isNotEmpty()) Text("Intestazioni: ${headers.keys.joinToString()}", style = MaterialTheme.typography.bodySmall)
                Text("Invio automatico: ${if (e.autoUpload) "sì" else "no"} · Formato: ${e.format.label}")
                if (e.url.startsWith("http://")) Text("Connessione non cifrata!", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Aggiungi") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

@Composable
private fun ManualEndpointDialog(onDismiss: () -> Unit, onSave: (EndpointEntity) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var webdav by remember { mutableStateOf(false) }
    var auth by remember { mutableStateOf("") }
    var taxonomy by remember { mutableStateOf("") }
    val valid = url.startsWith("http://") || url.startsWith("https://")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuovo endpoint") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(name, { name = it }, label = { Text("Nome") }, singleLine = true)
                OutlinedTextField(url, { url = it }, label = { Text("URL upload") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !webdav, onClick = { webdav = false }, label = { Text("HTTP POST") })
                    FilterChip(selected = webdav, onClick = { webdav = true }, label = { Text("WebDAV") })
                }
                OutlinedTextField(auth, { auth = it }, label = { Text("Authorization (opzionale)") }, singleLine = true)
                OutlinedTextField(taxonomy, { taxonomy = it }, label = { Text("URL tassonomia (opzionale)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                val h = if (auth.isBlank()) emptyMap() else mapOf("Authorization" to auth.trim())
                onSave(
                    EndpointEntity(
                        name = name.ifBlank { Uri.parse(url).host ?: "Endpoint" },
                        type = if (webdav) EndpointType.WEBDAV else EndpointType.HTTP,
                        url = url.trim(),
                        headers = EndpointConfig.json.encodeToString(h),
                        taxonomyUrl = taxonomy.trim().takeIf { it.startsWith("http") },
                    )
                )
            }) { Text("Continua") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } },
    )
}

/** Full-screen QR scanner (ML Kit) returning the raw QR payload. */
@OptIn(ExperimentalGetImage::class)
@Composable
fun QrScanScreen(onResult: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    DisposableEffect(Unit) { onDispose { executor.shutdown(); scanner.close() } }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (granted) {
            val previewView = remember { PreviewView(context) }
            LaunchedEffect(previewView) {
                val providerFuture = ProcessCameraProvider.getInstance(context)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                    var done = false
                    val main = Handler(Looper.getMainLooper())
                    val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                    analysis.setAnalyzer(executor) { proxy ->
                        val img = proxy.image
                        if (img == null || done) { proxy.close(); return@setAnalyzer }
                        scanner.process(InputImage.fromMediaImage(img, proxy.imageInfo.rotationDegrees))
                            .addOnSuccessListener { codes ->
                                val raw = codes.firstNotNullOfOrNull { it.rawValue }
                                if (raw != null && !done) { done = true; main.post { onResult(raw) } }
                            }
                            .addOnCompleteListener { proxy.close() }
                    }
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }, ContextCompat.getMainExecutor(context))
            }
            AndroidView({ previewView }, Modifier.fillMaxSize())
            Box(Modifier.align(Alignment.Center).size(260.dp).border(3.dp, Color.White, RoundedCornerShape(24.dp)))
        }
        IconButton(onClick = onClose, modifier = Modifier.statusBarsPadding().padding(8.dp)) {
            Icon(Icons.Filled.Close, "Chiudi", tint = Color.White)
        }
        Text(
            "Inquadra il QR di configurazione dell'endpoint", color = Color.White,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(32.dp)
        )
    }
}
