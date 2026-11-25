package com.smartscan.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartscan.container
import com.smartscan.data.DocListRow
import com.smartscan.data.DocStatus
import com.smartscan.data.TaxonomyNode
import com.smartscan.data.UploadEntity
import com.smartscan.data.UploadStatus
import com.smartscan.voice.normalizeText
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ArchiveItem(val row: DocListRow, val nodes: List<TaxonomyNode>, val uploads: List<UploadEntity>)

class ArchiveViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val query = MutableStateFlow("")
    val filterNode = MutableStateFlow<Long?>(null)
    val nodes = c.repo.observeNodes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val endpoints = c.repo.observeEndpoints().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val base = combine(c.repo.observeDocList(), c.repo.observeRefs(), nodes, c.repo.observeUploads()) { docs, refs, ns, ups ->
        val byId = ns.associateBy { it.id }
        val refMap = refs.groupBy({ it.docId }, { it.nodeId })
        val upMap = ups.groupBy { it.docId }
        docs.filter { it.pageCount > 0 }.map { r ->
            ArchiveItem(r, refMap[r.doc.id].orEmpty().mapNotNull { byId[it] }, upMap[r.doc.id].orEmpty())
        }
    }

    val items = combine(base, query, filterNode, nodes) { list, q, f, ns ->
        val allowed: Set<Long>? = f?.let { root ->
            val children = ns.groupBy { it.parentId }
            val out = HashSet<Long>()
            val stack = ArrayDeque(listOf(root))
            while (stack.isNotEmpty()) {
                val id = stack.removeLast()
                if (out.add(id)) children[id]?.forEach { stack.add(it.id) }
            }
            out
        }
        val nq = normalizeText(q)
        list.filter { item ->
            (allowed == null || item.nodes.any { it.id in allowed }) &&
                (nq.isEmpty() || nq.split(' ').all { w ->
                    normalizeText(item.row.doc.title).contains(w) ||
                        normalizeText(item.row.doc.notes).contains(w) ||
                        normalizeText(item.row.doc.ocrText).contains(w) ||
                        item.nodes.any { normalizeText(it.name).contains(w) }
                })
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- multi-selection -> one ZIP ----
    val selected = MutableStateFlow<Set<Long>>(emptySet())
    val zipping = MutableStateFlow(false)
    val shares = MutableSharedFlow<Intent>(extraBufferCapacity = 2)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)

    fun toggleSelect(id: Long) = selected.update { if (id in it) it - id else it + id }
    fun selectAll() = selected.update { s -> items.value.map { it.row.doc.id }.toSet().let { all -> if (s == all) emptySet() else all } }
    fun clearSelection() { selected.value = emptySet() }

    fun shareZip() {
        val ids = items.value.map { it.row.doc.id }.filter { it in selected.value }
        if (ids.isEmpty() || zipping.value) return
        zipping.value = true
        viewModelScope.launch {
            try {
                val zip = c.exporter.exportZip(ids)
                shares.emit(c.exporter.shareIntent(listOf(zip), "application/zip"))
                selected.value = emptySet()
            } catch (e: Exception) {
                messages.emit("Errore ZIP: ${e.message}")
            } finally {
                zipping.value = false
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveScreen(
    onScan: () -> Unit,
    onOpen: (Long, Boolean) -> Unit,
    onEndpoints: () -> Unit,
    onTaxonomy: () -> Unit,
    onSettings: () -> Unit,
) {
    val app = LocalContext.current.applicationContext as Application
    val vm: ArchiveViewModel = viewModel { ArchiveViewModel(app) }
    val items by vm.items.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filter by vm.filterNode.collectAsStateWithLifecycle()
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val endpoints by vm.endpoints.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf(false) }
    val selected by vm.selected.collectAsStateWithLifecycle()
    val zipping by vm.zipping.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val selecting = selected.isNotEmpty()
    LaunchedEffect(Unit) { vm.shares.collect { context.startActivity(it) } }
    LaunchedEffect(Unit) { vm.messages.collect { Toast.makeText(context, it, Toast.LENGTH_LONG).show() } }
    BackHandler(enabled = selecting) { vm.clearSelection() }

    Scaffold(
        topBar = {
            if (selecting) TopAppBar(
                title = { Text("${selected.size} selezionati") },
                navigationIcon = { IconButton(onClick = { vm.clearSelection() }) { Icon(Icons.Filled.Close, "Annulla selezione") } },
                actions = {
                    IconButton(onClick = { vm.selectAll() }) { Icon(Icons.Filled.SelectAll, "Seleziona tutti") }
                },
            ) else TopAppBar(
                title = { Text("SmartScan") },
                actions = {
                    IconButton(onClick = onTaxonomy) { Icon(Icons.Filled.AccountTree, "Categorie e tag") }
                    IconButton(onClick = onEndpoints) { Icon(Icons.Filled.QrCodeScanner, "Endpoint") }
                    IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Impostazioni") }
                },
            )
        },
        floatingActionButton = {
            if (selecting) ExtendedFloatingActionButton(
                onClick = { vm.shareZip() },
                icon = {
                    if (zipping) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.FolderZip, null)
                },
                text = { Text(if (zipping) "Creo lo ZIP…" else "Condividi ZIP (${selected.size})") },
            ) else ExtendedFloatingActionButton(
                onClick = onScan,
                icon = { Icon(Icons.Filled.DocumentScanner, null) },
                text = { Text("Scansiona") },
            )
        },
        floatingActionButtonPosition = FabPosition.Center,
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { vm.query.value = it },
                    placeholder = { Text("Cerca titolo, testo, tag…") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, "Pulisci") } },
                    singleLine = true, modifier = Modifier.weight(1f),
                )
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                val name = filter?.let { id -> nodes.firstOrNull { it.id == id }?.name }
                FilterChip(
                    selected = name != null, onClick = { picker = true },
                    label = { Text(name ?: "Filtra per categoria/tag") },
                    leadingIcon = { Icon(Icons.Filled.FilterList, null) },
                )
            }
            if (items.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank() && filter == null) "Nessun documento.\nPremi «Scansiona» per iniziare." else "Nessun risultato",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else LazyColumn(contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.row.doc.id }) { item ->
                    val id = item.row.doc.id
                    DocCard(
                        item, selected = id in selected,
                        onClick = { if (selecting) vm.toggleSelect(id) else onOpen(id, item.row.doc.status == DocStatus.DRAFT) },
                        onLongClick = { vm.toggleSelect(id) },
                    )
                }
            }
        }
    }

    if (picker) TreePickerDialog(
        nodes = nodes, endpoints = endpoints, initial = setOfNotNull(filter), single = true, title = "Filtra archivio",
        onDismiss = { picker = false },
        onConfirm = { vm.filterNode.value = it.firstOrNull(); picker = false },
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DocCard(item: ArchiveItem, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
    val d = item.row.doc
    Card(
        Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = if (selected) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        else CardDefaults.cardColors(),
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                item.row.thumb?.let { Thumb(it, Modifier.size(56.dp, 74.dp)) }
                if (selected) Icon(
                    Icons.Filled.CheckCircle, "Selezionato", tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.title.ifBlank { "Bozza" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${formatDate(d.createdAt)} · ${item.row.pageCount} pag.", style = MaterialTheme.typography.bodySmall)
                if (item.nodes.isNotEmpty()) Text(
                    item.nodes.joinToString("  ") { "#" + it.name }, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            when {
                d.status == DocStatus.DRAFT -> Text("Bozza", style = MaterialTheme.typography.labelSmall)
                d.status == DocStatus.PROCESSING || item.uploads.any { it.status == UploadStatus.RUNNING } ->
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                item.uploads.any { it.status == UploadStatus.FAILED } -> Icon(Icons.Filled.Error, "Invio fallito", tint = MaterialTheme.colorScheme.error)
                item.uploads.any { it.status == UploadStatus.QUEUED } -> Icon(Icons.Filled.CloudUpload, "In coda")
                item.uploads.isNotEmpty() -> Icon(Icons.Filled.CloudDone, "Inviato", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
