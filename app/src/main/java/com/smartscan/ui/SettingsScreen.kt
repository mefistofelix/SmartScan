package com.smartscan.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartscan.container
import com.smartscan.data.ExportQuality
import com.smartscan.data.PageFilter
import com.smartscan.data.PdfPageSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onClose: () -> Unit) {
    val s = LocalContext.current.container.settings
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Impostazioni") },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section("Scansione")
            Toggle("Scatto automatico", "Scatta quando il documento è fermo e a fuoco", s.autoCapture) { s.autoCapture = it }
            Toggle("Raddrizzamento automatico", "Corregge l'inclinazione residua del testo", s.autoDeskew) { s.autoDeskew = it }
            Toggle("Suono otturatore", null, s.shutterSound) { s.shutterSound = it }
            Choice("Filtro predefinito", PageFilter.entries, s.defaultFilter, { it.label }) { s.defaultFilter = it }

            Section("Esportazione")
            Choice("Qualità / dimensione file", ExportQuality.entries, s.quality, { it.label }) { s.quality = it }
            Choice("Formato pagina PDF", PdfPageSize.entries, s.pageSize, { it.label }) { s.pageSize = it }

            Section("Voce")
            Toggle("Comandi vocali durante la scansione", "«scatta», «fine documento», «nuovo documento», «annulla ultima»…", s.voiceCommands) { s.voiceCommands = it }
            Toggle("Detta metadati a inizio documento", "All'apertura della fotocamera e dopo ogni «Fine»", s.voiceMetaPre) { s.voiceMetaPre = it }
            Toggle("Detta metadati a fine documento", "Dopo «Fine», prima di salvare", s.voiceMetaPost) { s.voiceMetaPost = it }
            Toggle("Crea tag sconosciuti", "I tag dettati che non esistono vengono creati in locale", s.createUnknownTags) { s.createUnknownTags = it }
            Choice("Lingua", listOf("it-IT", "en-US", "de-DE", "fr-FR", "es-ES"), s.voiceLanguage, { it }) { s.voiceLanguage = it }

            Section("Invio")
            Toggle("Solo Wi-Fi", "Gli upload attendono una rete non a consumo", s.wifiOnly) { s.wifiOnly = it }
        }
    }
}

@Composable
private fun Section(title: String) {
    HorizontalDivider()
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp))
}

@Composable
private fun Toggle(title: String, subtitle: String?, initial: Boolean, onChange: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = v, onCheckedChange = { v = it; onChange(it) }) },
    )
}

@Composable
private fun <T> Choice(title: String, options: List<T>, initial: T, label: (T) -> String, onChange: (T) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(selected = o == v, onClick = { v = o; onChange(o) }, label = { Text(label(o)) }) }
        }
    }
}
