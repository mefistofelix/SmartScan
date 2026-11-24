package com.smartscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.smartscan.data.EndpointEntity
import com.smartscan.data.NodeKind
import com.smartscan.data.TaxonomyNode
import com.smartscan.voice.normalizeText
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDate(ms: Long): String = SimpleDateFormat("dd MMM yyyy · HH:mm", Locale.ITALY).format(Date(ms))

data class TreeRow(val node: TaxonomyNode, val depth: Int, val hasChildren: Boolean)

/** Flattens a node list into visible rows, honouring expansion and an optional text filter. */
fun flattenTree(nodes: List<TaxonomyNode>, expanded: Set<Long>, filter: String = ""): List<TreeRow> {
    val children = nodes.groupBy { it.parentId }
    val byId = nodes.associateBy { it.id }
    val visible: Set<Long>? = if (filter.isBlank()) null else {
        val f = normalizeText(filter)
        val set = HashSet<Long>()
        nodes.filter { normalizeText(it.name).contains(f) }.forEach { n ->
            var cur: TaxonomyNode? = n
            var guard = 0
            while (cur != null && guard++ < 64) { set += cur.id; cur = cur.parentId?.let { byId[it] } }
        }
        set
    }
    val out = ArrayList<TreeRow>()
    fun walk(parent: Long?, depth: Int) {
        for (n in children[parent].orEmpty().sortedBy { it.name.lowercase() }) {
            if (visible != null && n.id !in visible) continue
            val hasC = children[n.id]?.isNotEmpty() == true
            out += TreeRow(n, depth, hasC)
            if (depth < 32 && (visible != null || n.id in expanded)) walk(n.id, depth + 1)
        }
    }
    // Roots = no parent, or parent not in this subset.
    val roots = nodes.filter { it.parentId == null || it.parentId !in byId }
    if (roots.any { it.parentId != null }) {
        for (r in roots.filter { it.parentId != null }) {
            out += TreeRow(r, 0, children[r.id]?.isNotEmpty() == true)
            if (visible != null || r.id in expanded) walk(r.id, 1)
        }
    }
    walk(null, 0)
    return out
}

/** Groups nodes by source: local first, then each endpoint. */
fun sourceGroups(nodes: List<TaxonomyNode>, endpoints: List<EndpointEntity>): List<Pair<String, List<TaxonomyNode>>> {
    val out = ArrayList<Pair<String, List<TaxonomyNode>>>()
    out += "Locale" to nodes.filter { it.endpointId == null }
    endpoints.forEach { ep ->
        val list = nodes.filter { it.endpointId == ep.id }
        if (list.isNotEmpty()) out += ep.name to list
    }
    return out
}

@Composable
fun TreeRowView(
    row: TreeRow,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (row.depth * 20).dp, end = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).clickable(enabled = row.hasChildren, onClick = onToggleExpand), contentAlignment = Alignment.Center) {
            if (row.hasChildren) Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null)
        }
        leading?.invoke()
        Text(row.node.name, Modifier.weight(1f), maxLines = 1)
        trailing?.invoke()
    }
}

/**
 * Category / tag tree picker. Multi-select (document labelling) or single-select (archive filter).
 */
@Composable
fun TreePickerDialog(
    nodes: List<TaxonomyNode>,
    endpoints: List<EndpointEntity>,
    initial: Set<Long>,
    single: Boolean = false,
    title: String = "Categorie e tag",
    onCreate: ((NodeKind, String) -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit,
) {
    var tab by remember { mutableIntStateOf(0) }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(setOf<Long>()) }
    val kind = if (tab == 0) NodeKind.CATEGORY else NodeKind.TAG

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.fillMaxWidth(0.94f).fillMaxHeight(0.86f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp, 20.dp, 20.dp, 8.dp))
                TabRow(selectedTabIndex = tab) {
                    NodeKind.entries.forEachIndexed { i, k ->
                        val count = nodes.count { it.kind == k && it.id in selected }
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(if (count > 0) "${k.label} ($count)" else k.label) })
                    }
                }
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    placeholder = { Text("Cerca o crea…") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                )
                LazyColumn(Modifier.weight(1f)) {
                    for ((source, list) in sourceGroups(nodes.filter { it.kind == kind }, endpoints)) {
                        val rows = flattenTree(list, expanded, query)
                        if (rows.isEmpty()) continue
                        item(key = "h_$source") {
                            Text(
                                source, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp)
                            )
                        }
                        items(rows, key = { it.node.id }) { row ->
                            val id = row.node.id
                            val toggle = {
                                selected = if (single) (if (id in selected) emptySet() else setOf(id))
                                else if (id in selected) selected - id else selected + id
                            }
                            TreeRowView(
                                row, id in expanded || query.isNotBlank(),
                                onToggleExpand = { expanded = if (id in expanded) expanded - id else expanded + id },
                                leading = {
                                    if (single) RadioButton(selected = id in selected, onClick = toggle)
                                    else Checkbox(checked = id in selected, onCheckedChange = { toggle() })
                                },
                                onClick = toggle,
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    if (onCreate != null && query.isNotBlank() &&
                        nodes.none { it.kind == kind && normalizeText(it.name) == normalizeText(query) }
                    ) {
                        TextButton(onClick = { onCreate(kind, query.trim()); query = "" }) {
                            Icon(Icons.Filled.Add, null); Spacer(Modifier.width(4.dp))
                            Text("Crea \"${query.trim()}\"", maxLines = 1)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (single) TextButton(onClick = { onConfirm(emptySet()) }) { Text("Tutti") }
                    TextButton(onClick = onDismiss) { Text("Annulla") }
                    TextButton(onClick = { onConfirm(selected) }) { Text("OK") }
                }
            }
        }
    }
}
