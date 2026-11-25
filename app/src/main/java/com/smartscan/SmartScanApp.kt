package com.smartscan

import android.app.Application
import android.content.Context
import android.util.Log
import com.smartscan.data.AppDatabase
import com.smartscan.data.AppSettings
import com.smartscan.data.Repository
import com.smartscan.export.Exporter
import com.smartscan.ocr.OcrEngine
import com.smartscan.sync.SyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.opencv.android.OpenCVLoader
import java.util.concurrent.TimeUnit

class SmartScanApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (!OpenCVLoader.initLocal()) Log.e("SmartScan", "OpenCV non inizializzato")
        // Neural document corner model: loaded in the background, OpenCV detection is used until it is ready.
        Thread { com.smartscan.scan.NeuralCorners.init(this) }.start()
        container = AppContainer(this)
        container.appScope.launch {
            runCatching { container.repo.cleanupEmptyDrafts() }
            runCatching { container.sync.syncAllTaxonomies() }
        }
    }
}

class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val db = AppDatabase.build(context)
    val settings = AppSettings(context)
    val repo = Repository(context, db)
    val ocr by lazy { OcrEngine() }
    val codes by lazy { com.smartscan.ocr.CodeReader() }
    val exporter by lazy { Exporter(context, repo, settings) }
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(300, TimeUnit.SECONDS)
            .build()
    }
    val sync by lazy { SyncManager(context, repo, exporter, http, settings) }
}

val Context.container: AppContainer get() = (applicationContext as SmartScanApp).container

/** Volume keys act as shutter while the scanner is open. */
object HardwareKeys {
    @Volatile var shutterEnabled = false
    val events = MutableSharedFlow<Int>(extraBufferCapacity = 4)
}

/** smartscan:// links (endpoint configuration) received from outside or from the QR scanner. */
object DeepLinks {
    val pending = MutableStateFlow<String?>(null)
}
