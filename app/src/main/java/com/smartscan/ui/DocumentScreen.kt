package com.smartscan.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartscan.container
import com.smartscan.data.DocStatus
import com.smartscan.data.Repository
import com.smartscan.data.UploadStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DocumentViewModel(app: Application, val docId: Long) : AndroidViewModel(app) {
    private val c = app.container
    val doc = c.repo.observeDoc(docId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pages = c.repo.observePages(docId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val nodes = c.repo.observeNodes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val nodeIds = c.repo.observeDocNodeIds(docId).map { it.toSet() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val uploads = c.repo.observeUploads(docId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val endpoints = c.repo.observeEndpoints().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val busy = MutableStateFlow(false)
    val shares = MutableSharedFlow<Intent>(extraBufferCapacity = 2)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    private fun job(block: suspend () -> Unit) = viewModelScope.launch {
        busy.value = true
        try { block() } catch (e: Exception) { messages.emit("Errore: ${e.message}") } finally { busy.value = false }
    }

    fun sharePdf(ocr: Boolean) = job {
        val f = c.exporter.exportPdf(docId, ocr)
        shares.emit(c.exporter.shareIntent(listOf(f), "application/pdf"))
    }

    fun shareImages() = job {
        val files = c.exporter.exportImages(docId)
        shares.emit(c.exporter.shareIntent(files, "image/*"))
    }

    fun shareText() = job {
        val f = c.exporter.exportText(docId)
        shares.emit(c.exporter.shareIntent(listOf(f), "text/plain"))
    }

    fun savePdfTo(uri: Uri) = job {
        val f = c.exporter.exportPdf(docId, true)
        withContext(Dispatchers.IO) {
            getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
        }
        messages.emit("PDF salvato (${f.length() / 1024} KB)")
    }

    fun rename(title: String) = viewModelScope.launch {
        val t = title.trim()
        if (t.isNotEmpty()) c.repo.getDoc(docId)?.let { c.repo.updateDoc(it.copy(title = t)) }
    }

    fun upload(endpointId: Long) = viewModelScope.launch { c.sync.enqueueUpload(docId, endpointId) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { c.repo.deleteDoc(docId); onDone() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocumentScreen(docId: Long, onEdit: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: DocumentViewModel = viewModel(key = "doc_$docId") { DocumentViewModel(app, docId) }
    val doc by vm.doc.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val nodeIds by vm.nodeIds.collectAsStateWithLifecycle()
    val uploads by vm.uploads.collectAsStateWithLifecycle()
    val endpoints by vm.endpoints.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var showOcr by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val saveLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        uri?.let { vm.savePdfTo(it) }
    }
    LaunchedEffect(Unit) { vm.shares.collect { context.startActivity(it) } }
    LaunchedEffect(Unit) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Text(
                    doc?.title ?: "", maxLines = 1,
                    modifier = Modifier.clickable(enabled = doc != null) { renaming = true },
                )
            },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            actions = {
                IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, "Modifica") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Elimina") }
            },
        )
    }) { pad ->
        val d = doc ?: return@Scaffold
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
            item {
                if (busy || d.status == DocStatus.PROCESSING) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    "${formatDate(d.createdAt)} · ${pages.size} pagine" +
                        (d.docDate?.let { " · data doc. $it" } ?: "") +
                        if (d.status == DocStatus.PROCESSING) " · OCR in corso" else "",
                    style = MaterialTheme.typography.bodyMedium,
                )
                val byId = nodes.associateBy { it.id }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    nodeIds.mapNotNull { byId[it] }.forEach { n ->
                        AssistChip(onClick = {}, label = { Text(Repository.nodePath(n, byId)) })
                    }
                }
                if (d.notes.isNotBlank()) Text(d.notes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(pages, key = { it.id }) { p -> Thumb(p.processedPath, Modifier.size(96.dp, 128.dp)) }
                }
                Spacer(Modifier.height(16.dp))
            }
            item {
                Text("Esporta e condividi", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                    ActionButton(Icons.Filled.PictureAsPdf, "PDF ricercabile", !busy) { vm.sharePdf(true) }
                    ActionButton(Icons.Filled.PictureAsPdf, "PDF", !busy) { vm.sharePdf(false) }
                    ActionButton(Icons.Filled.Image, "Immagini", !busy) { vm.shareImages() }
                    ActionButton(Icons.Filled.Description, "Testo OCR", !busy) { vm.shareText() }
                    ActionButton(Icons.Filled.Save, "Salva PDF…", !busy) {
                        saveLauncher.launch(com.smartscan.export.Exporter.fileBase(d) + ".pdf")
                    }
                }
            }
            item {
                Text("Invio a storage", style = MaterialTheme.typography.titleMedium)
                if (endpoints.isEmpty()) Text("Nessun endpoint: aggiungine uno via QR dalla schermata Endpoint.", style = MaterialTheme.typography.bodySmall)
            }
            items(endpoints, key = { it.id }) { ep ->
                val u = uploads.firstOrNull { it.endpointId == ep.id }
                ListItem(
                    headlineContent = { Text(ep.name) },
                    supportingContent = { Text(u?.message?.ifBlank { null } ?: (if (ep.autoUpload) "Invio automatico" else "Manuale")) },
                    leadingContent = {
                        when (u?.status) {
                            UploadStatus.DONE -> Icon(Icons.Filled.CloudDone, null, tint = MaterialTheme.colorScheme.primary)
                            UploadStatus.FAILED -> Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error)
                            UploadStatus.RUNNING -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            UploadStatus.QUEUED -> Icon(Icons.Filled.Schedule, null)
                            null -> Icon(Icons.Filled.CloudUpload, null)
                        }
                    },
                    trailingContent = {
                        TextButton(onClick = { vm.upload(ep.id) }) { Text(if (u == null) "Invia" else "Reinvia") }
                    },
                )
            }
            item {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Testo riconosciuto (OCR)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = { showOcr = !showOcr }) { Text(if (showOcr) "Nascondi" else "Mostra") }
                }
                if (showOcr) Card(Modifier.fillMaxWidth()) {
                    SelectionContainer {
                        Text(d.ocrText.ifBlank { "—" }, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (renaming) {
        var name by remember { mutableStateOf(doc?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Nome documento") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.rename(name); renaming = false }, enabled = name.isNotBlank()) { Text("Salva") }
            },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Annulla") } },
        )
    }

    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Eliminare il documento?") },
        text = { Text("Viene rimosso dall'archivio locale. Le copie già inviate agli endpoint restano.") },
        confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onClose) }) { Text("Elimina") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annulla") } },
    )
}

@Composable
private fun ActionButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, enabled = enabled) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label)
    }
}
