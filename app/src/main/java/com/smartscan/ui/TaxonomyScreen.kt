package com.smartscan.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.smartscan.container
import com.smartscan.data.NodeKind
import com.smartscan.data.TaxonomyNode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TaxonomyViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val nodes = c.repo.observeNodes().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val endpoints = c.repo.observeEndpoints().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    fun add(kind: NodeKind, name: String, parent: Long?) = viewModelScope.launch { c.repo.addNode(kind, name, parent) }
    fun rename(n: TaxonomyNode, name: String) = viewModelScope.launch { c.repo.updateNode(n.copy(name = name.trim())) }
    fun delete(n: TaxonomyNode) = viewModelScope.launch { c.repo.deleteNode(n) }
    fun syncAll() = viewModelScope.launch { c.sync.syncAllTaxonomies() }
}

private sealed interface NameEdit {
    data class Add(val parent: TaxonomyNode?) : NameEdit
    data class Rename(val node: TaxonomyNode) : NameEdit
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaxonomyScreen(onClose: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val vm: TaxonomyViewModel = viewModel { TaxonomyViewModel(app) }
    val nodes by vm.nodes.collectAsStateWithLifecycle()
    val endpoints by vm.endpoints.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    var edit by remember { mutableStateOf<NameEdit?>(null) }
    var toDelete by remember { mutableStateOf<TaxonomyNode?>(null) }
    val kind = if (tab == 0) NodeKind.CATEGORY else NodeKind.TAG

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Categorie e tag") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
                actions = { IconButton(onClick = { vm.syncAll() }) { Icon(Icons.Filled.Sync, "Sincronizza remoti") } },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = { edit = NameEdit.Add(null) }) { Icon(Icons.Filled.Add, "Nuovo") } },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                NodeKind.entries.forEachIndexed { i, k -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(k.label) }) }
            }
            LazyColumn(contentPadding = PaddingValues(bottom = 96.dp)) {
                for ((source, list) in sourceGroups(nodes.filter { it.kind == kind }, endpoints)) {
                    val editable = list.firstOrNull()?.endpointId == null
                    item(key = "h_$source") {
                        Text(
                            if (editable) source else "$source (remoto, sola lettura)",
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp)
                        )
                    }
                    val rows = flattenTree(list, expanded)
                    if (rows.isEmpty()) item(key = "e_$source") {
                        Text("Vuoto: usa + per creare.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                    }
                    items(rows, key = { it.node.id }) { row ->
                        val n = row.node
                        TreeRowView(
                            row, n.id in expanded,
                            onToggleExpand = { expanded = if (n.id in expanded) expanded - n.id else expanded + n.id },
                            trailing = {
                                if (n.endpointId == null) {
                                    var menu by remember { mutableStateOf(false) }
                                    Box {
                                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Azioni") }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            DropdownMenuItem(text = { Text("Aggiungi sotto-voce") }, onClick = { menu = false; edit = NameEdit.Add(n); expanded = expanded + n.id })
                                            DropdownMenuItem(text = { Text("Rinomina") }, onClick = { menu = false; edit = NameEdit.Rename(n) })
                                            DropdownMenuItem(text = { Text("Elimina") }, onClick = { menu = false; toDelete = n })
                                        }
                                    }
                                }
                            },
                            onClick = { if (row.hasChildren) expanded = if (n.id in expanded) expanded - n.id else expanded + n.id },
                        )
                    }
                }
            }
        }
    }

    edit?.let { e ->
        var name by remember(e) { mutableStateOf(if (e is NameEdit.Rename) e.node.name else "") }
        AlertDialog(
            onDismissRequest = { edit = null },
            title = {
                Text(
                    when (e) {
                        is NameEdit.Add -> if (e.parent == null) "Nuova voce in ${kind.label}" else "Nuova voce in «${e.parent.name}»"
                        is NameEdit.Rename -> "Rinomina"
                    }
                )
            },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Nome") }) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    when (e) {
                        is NameEdit.Add -> vm.add(e.parent?.kind ?: kind, name, e.parent?.id)
                        is NameEdit.Rename -> vm.rename(e.node, name)
                    }
                    edit = null
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("Annulla") } },
        )
    }
    toDelete?.let { n ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Eliminare «${n.name}»?") },
            text = { Text("Verranno eliminate anche le sotto-voci e tolte dai documenti.") },
            confirmButton = { TextButton(onClick = { vm.delete(n); toDelete = null }) { Text("Elimina") } },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Annulla") } },
        )
    }
}
