package com.smartscan.ui

import com.smartscan.str

import com.smartscan.R

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
            title = { Text(str(R.string.settings)) },
            navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, str(R.string.back)) } },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(str(R.string.scanning))
            Toggle(str(R.string.auto_capture), str(R.string.auto_capture_desc), s.autoCapture) { s.autoCapture = it }
            Toggle(str(R.string.auto_deskew), str(R.string.auto_deskew_desc), s.autoDeskew) { s.autoDeskew = it }
            Toggle(str(R.string.start_camera), str(R.string.start_camera_desc), s.startInScanner) { s.startInScanner = it }
            Toggle(str(R.string.shutter_sound), null, s.shutterSound) { s.shutterSound = it }
            Choice(str(R.string.default_filter), PageFilter.entries, s.defaultFilter, { it.label }) { s.defaultFilter = it }

            Section(str(R.string.export))
            Choice(str(R.string.quality_size), ExportQuality.entries, s.quality, { it.label }) { s.quality = it }
            Choice(str(R.string.pdf_page_size), PdfPageSize.entries, s.pageSize, { it.label }) { s.pageSize = it }

            Section(str(R.string.voice))
            Toggle(str(R.string.dict_pre_setting), str(R.string.dict_pre_desc), s.voiceMetaPre) { s.voiceMetaPre = it }
            Toggle(str(R.string.dict_post_setting), str(R.string.dict_post_desc), s.voiceMetaPost) { s.voiceMetaPost = it }
            Toggle(str(R.string.create_tags), str(R.string.create_tags_desc), s.createUnknownTags) { s.createUnknownTags = it }
            Choice(str(R.string.language), listOf("it-IT", "en-US", "de-DE", "fr-FR", "es-ES"), s.voiceLanguage, { it }) { s.voiceLanguage = it }

            Section(str(R.string.uploads))
            Toggle(str(R.string.wifi_only), str(R.string.wifi_only_desc), s.wifiOnly) { s.wifiOnly = it }
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
