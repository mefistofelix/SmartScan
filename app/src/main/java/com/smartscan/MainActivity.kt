package com.smartscan

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.smartscan.ui.ArchiveScreen
import com.smartscan.ui.CropScreen
import com.smartscan.ui.DocumentScreen
import com.smartscan.ui.EndpointsScreen
import com.smartscan.ui.QrScanScreen
import com.smartscan.ui.ReviewScreen
import com.smartscan.ui.ScannerScreen
import com.smartscan.ui.SettingsScreen
import com.smartscan.ui.TaxonomyScreen
import com.smartscan.ui.theme.SmartScanTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { SmartScanTheme { AppNav() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val data = intent?.dataString ?: return
        if (data.startsWith("smartscan://", ignoreCase = true)) DeepLinks.pending.value = data
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (HardwareKeys.shutterEnabled &&
            (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP)
        ) {
            if (event.repeatCount == 0) HardwareKeys.events.tryEmit(keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}

@Composable
fun AppNav() {
    val nav = rememberNavController()
    val pending by DeepLinks.pending.collectAsState()
    LaunchedEffect(pending) {
        if (pending != null && nav.currentDestination?.route != "endpoints") nav.navigate("endpoints")
    }
    NavHost(nav, startDestination = "archive") {
        composable("archive") {
            ArchiveScreen(
                onScan = { nav.navigate("scanner/-1") },
                onOpen = { id, draft -> nav.navigate(if (draft) "review/$id" else "doc/$id") },
                onEndpoints = { nav.navigate("endpoints") },
                onTaxonomy = { nav.navigate("taxonomy") },
                onSettings = { nav.navigate("settings") },
            )
        }
        composable("scanner/{docId}", arguments = listOf(navArgument("docId") { type = NavType.LongType })) { e ->
            val docId = e.arguments?.getLong("docId") ?: -1L
            ScannerScreen(docId = docId, onClose = { nav.popBackStack() })
        }
        composable(
            "review/{docId}",
            arguments = listOf(navArgument("docId") { type = NavType.LongType }),
        ) { e ->
            ReviewScreen(
                docId = e.arguments?.getLong("docId") ?: -1L,
                onAddPages = { id -> nav.navigate("scanner/$id") },
                onCrop = { pageId -> nav.navigate("crop/$pageId") },
                onClose = { nav.popBackStack() },
            )
        }
        composable("crop/{pageId}", arguments = listOf(navArgument("pageId") { type = NavType.LongType })) { e ->
            CropScreen(pageId = e.arguments?.getLong("pageId") ?: -1L, onClose = { nav.popBackStack() })
        }
        composable("doc/{docId}", arguments = listOf(navArgument("docId") { type = NavType.LongType })) { e ->
            val id = e.arguments?.getLong("docId") ?: -1L
            DocumentScreen(
                docId = id,
                onEdit = { nav.navigate("review/$id") },
                onClose = { nav.popBackStack() },
            )
        }
        composable("endpoints") {
            EndpointsScreen(onScanQr = { nav.navigate("qr") }, onClose = { nav.popBackStack() })
        }
        composable("qr") {
            QrScanScreen(onResult = { raw -> DeepLinks.pending.value = raw; nav.popBackStack() }, onClose = { nav.popBackStack() })
        }
        composable("taxonomy") { TaxonomyScreen(onClose = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onClose = { nav.popBackStack() }) }
    }
}
