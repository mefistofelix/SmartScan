package com.smartscan.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddAPhoto
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.smartscan.container
import com.smartscan.data.DocStatus
import com.smartscan.data.NodeKind
import com.smartscan.data.PageEntity
import com.smartscan.data.PageFilter
import com.smartscan.data.Repository
import com.smartscan.voice.VoiceController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class ReviewViewModel(app: Application, val docId: Long) : AndroidViewModel(app) {
    private val c = app.container
    val settings = c.settings
    val doc = c.repo.observeDoc(docId).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val pages = c.repo.observePages(docId).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val nodes = c.repo.observeNodes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val endpoints = c.repo.observeEndpoints().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val nodeIds = c.repo.observeDocNodeIds(docId).map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())
    val title = MutableStateFlow("")
    val notes = MutableStateFlow("")
    val busyPages = MutableStateFlow(setOf<Long>())
    val voiceSummary = MutableStateFlow<String?>(null)
    private val voice = VoiceController(app, settings.voiceLanguage)
    val listening = voice.listening
    val partial = voice.partial
    val voiceAvailable = voice.available

    init {
        viewModelScope.launch {
            val d = doc.filterNotNull().first()
            title.value = d.title
            notes.value = d.notes
        }
    }

    private fun rerender(p: PageEntity) {
        viewModelScope.launch {
            busyPages.value = busyPages.value + p.id
            runCatching { c.repo.rerender(p, settings.autoDeskew) }
            busyPages.value = busyPages.value - p.id
        }
    }

    fun setFilter(p: PageEntity, f: PageFilter) { if (p.filter != f) rerender(p.copy(filter = f)) }
    fun rotate(p: PageEntity) = rerender(p.copy(rotation = (p.rotation + 90) % 360))
    fun delete(p: PageEntity) = viewModelScope.launch { c.repo.deletePage(p) }
    fun move(p: PageEntity, delta: Int) = viewModelScope.launch { c.repo.movePage(p, delta) }
    fun setNodes(ids: Set<Long>) = viewModelScope.launch { c.repo.setDocNodes(docId, ids) }
    fun createNode(kind: NodeKind, name: String) = viewModelScope.launch {
        val id = c.repo.addNode(kind, name, null)
        c.repo.addDocNodes(docId, listOf(id))
    }

    fun dictate() {
        if (listening.value) { voice.stop(); return }
        voice.listenOnce { text ->
            if (text.isNullOrBlank()) { voiceSummary.value = "Non ho capito, riprova"; return@listenOnce }
            viewModelScope.launch {
                // Persist what the user typed so far, then merge the spoken metadata.
                c.repo.getDoc(docId)?.let { c.repo.updateDoc(it.copy(title = title.value, notes = notes.value)) }
                val p = c.repo.applyVoiceMetadata(docId, text, settings.createUnknownTags)
                c.repo.getDoc(docId)?.let { title.value = it.title; notes.value = it.notes }
                voiceSummary.value = p.summary()
            }
        }
    }

    fun save(onDone: () -> Unit) = viewModelScope.launch {
        voice.stop()
        c.repo.markProcessing(docId, title.value, notes.value)
        c.sync.enqueueFinalize(docId)
        onDone()
    }

    fun discard(onDone: () -> Unit) = viewModelScope.launch {
        voice.stop()
        c.repo.deleteDoc(docId)
        onDone()
    }

    override fun onCleared() { voice.destroy(); super.onCleared() }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ReviewScreen(
    docId: Long,
    fromScan: Boolean,
    onAddPages: (Long) -> Unit,
    onCrop: (Long) -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as Application
    val vm: ReviewViewModel = viewModel(key = "review_$docId") { ReviewViewModel(app, docId) }
    val doc by vm.doc.collectAsStateWithLifecycle()
    val pages by vm.pages.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val endpoints by vm.endpoints.collectAsStateWithLifecycle()
    val nodeIds by vm.nodeIds.collectAsStateWithLifecycle()
    val title by vm.title.collectAsStateWithLifecycle()
    val notes by vm.notes.collectAsStateWithLifecycle()
    val busy by vm.busyPages.collectAsStateWithLifecycle()
    val listening by vm.listening.collectAsStateWithLifecycle()
    val partial by vm.partial.collectAsStateWithLifecycle()
    val summary by vm.voiceSummary.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val isDraft = doc?.status == DocStatus.DRAFT
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()

    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.dictate() }
    fun dictate() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.dictate()
        else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(fromScan) {
        if (fromScan && vm.settings.voiceMetaPost && vm.voiceAvailable) dictate()
    }
    BackHandler { if (isDraft) confirmDiscard = true else onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (pages.isEmpty()) "Rivedi" else "Pagina ${pager.currentPage + 1} di ${pages.size}") },
                navigationIcon = {
                    IconButton(onClick = { if (isDraft) confirmDiscard = true else onClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro")
                    }
                },
                actions = {
                    Button(onClick = { vm.save(onClose) }, enabled = pages.isNotEmpty() && busy.isEmpty(), modifier = Modifier.padding(end = 8.dp)) {
                        Text("Salva")
                    }
                },
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
                if (pages.isEmpty()) {
                    Text("Nessuna pagina", Modifier.align(Alignment.Center))
                } else {
                    HorizontalPager(pager, Modifier.fillMaxSize(), key = { pages.getOrNull(it)?.id ?: it }) { i ->
                        val p = pages.getOrNull(i)
                        Box(Modifier.fillMaxSize().padding(12.dp), contentAlignment = Alignment.Center) {
                            if (p != null) AsyncImage(
                                model = File(p.processedPath), contentDescription = "Pagina ${i + 1}",
                                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()
                            )
                            if (p != null && p.id in busy) CircularProgressIndicator()
                        }
                    }
                }
            }
            val current = pages.getOrNull(pager.currentPage)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToolButton(Icons.Filled.Crop, "Ritaglia", current != null) { current?.let { onCrop(it.id) } }
                ToolButton(Icons.Filled.RotateRight, "Ruota", current != null) { current?.let { vm.rotate(it) } }
                ToolButton(Icons.Filled.ChevronLeft, "Sposta", current != null && pager.currentPage > 0) {
                    current?.let { vm.move(it, -1); scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }
                }
                ToolButton(Icons.Filled.ChevronRight, "Sposta", current != null && pager.currentPage < pages.lastIndex) {
                    current?.let { vm.move(it, 1); scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }
                }
                ToolButton(Icons.Filled.Delete, "Elimina", current != null) { current?.let { vm.delete(it) } }
                ToolButton(Icons.Filled.AddAPhoto, "Aggiungi", true) { onAddPages(docId) }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PageFilter.entries.forEach { f ->
                    FilterChip(selected = current?.filter == f, onClick = { current?.let { vm.setFilter(it, f) } }, label = { Text(f.label) })
                }
            }
            Column(
                Modifier.fillMaxWidth().heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                OutlinedTextField(
                    value = title, onValueChange = { vm.title.value = it },
                    label = { Text("Titolo") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        if (vm.voiceAvailable) IconButton(onClick = { dictate() }) {
                            Icon(Icons.Filled.Mic, "Detta metadati", tint = if (listening) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                )
                if (listening) Text("🎙 " + partial.ifBlank { "Parla: «titolo … categoria … tag … nota …»" }, style = MaterialTheme.typography.bodySmall)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                val byId = nodes.associateBy { it.id }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 4.dp)) {
                    nodeIds.mapNotNull { byId[it] }.sortedBy { it.kind }.forEach { n ->
                        InputChip(
                            selected = n.kind == NodeKind.CATEGORY,
                            onClick = { vm.setNodes(nodeIds - n.id) },
                            label = { Text(Repository.nodePath(n, byId), maxLines = 1) },
                            trailingIcon = { Text("✕") },
                        )
                    }
                    AssistChip(onClick = { picker = true }, label = { Text("Categorie e tag") }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                }
                OutlinedTextField(
                    value = notes, onValueChange = { vm.notes.value = it },
                    label = { Text("Note") }, modifier = Modifier.fillMaxWidth(), maxLines = 3,
                )
                Spacer(Modifier.size(8.dp))
            }
        }
    }

    if (picker) TreePickerDialog(
        nodes = nodes, endpoints = endpoints, initial = nodeIds,
        onCreate = { k, name -> vm.createNode(k, name) },
        onDismiss = { picker = false },
        onConfirm = { vm.setNodes(it); picker = false },
    )
    if (confirmDiscard) AlertDialog(
        onDismissRequest = { confirmDiscard = false },
        title = { Text("Scartare la scansione?") },
        text = { Text("Le pagine acquisite verranno eliminate.") },
        confirmButton = { TextButton(onClick = { confirmDiscard = false; vm.discard(onClose) }) { Text("Scarta") } },
        dismissButton = {
            TextButton(onClick = { confirmDiscard = false; vm.save(onClose) }) { Text("Salva") }
        },
    )
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun Thumb(path: String, modifier: Modifier = Modifier) {
    AsyncImage(
        model = File(path), contentDescription = null, contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(6.dp)).background(Color.LightGray)
    )
}
