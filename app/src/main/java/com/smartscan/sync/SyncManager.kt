package com.smartscan.sync

import android.content.Context
import androidx.room.withTransaction
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.smartscan.container
import com.smartscan.data.AppSettings
import com.smartscan.data.DocOutput
import com.smartscan.data.DocumentEntity
import com.smartscan.data.EndpointEntity
import com.smartscan.data.EndpointType
import com.smartscan.data.NodeKind
import com.smartscan.data.Repository
import com.smartscan.data.TaxonomyNode
import com.smartscan.data.UploadFormat
import com.smartscan.data.UploadStatus
import com.smartscan.export.Exporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

@Serializable
data class RemoteNode(val id: String? = null, val name: String, val children: List<RemoteNode> = emptyList())

@Serializable
data class RemoteTaxonomy(val categories: List<RemoteNode> = emptyList(), val tags: List<RemoteNode> = emptyList())

class HttpStatusException(val code: Int, msg: String) : IOException(msg)

/** Background OCR, uploads to storage endpoints and remote taxonomy sync. */
class SyncManager(
    private val context: Context,
    private val repo: Repository,
    private val exporter: Exporter,
    private val http: OkHttpClient,
    private val settings: AppSettings,
) {
    private val wm get() = WorkManager.getInstance(context)
    private val json = EndpointConfig.json

    /** OCR first, then upload to every endpoint flagged for automatic upload (unless the document is local-only). */
    suspend fun enqueueFinalize(docId: Long) {
        val ocr = OneTimeWorkRequestBuilder<OcrWorker>().setInputData(workDataOf(KEY_DOC to docId)).build()
        val send = repo.getDoc(docId)?.upload != false
        val uploads = if (!send) emptyList() else repo.endpoints().filter { it.autoUpload }.map { ep ->
            repo.setUpload(docId, ep.id, UploadStatus.QUEUED, "In coda")
            uploadRequest(docId, ep.id)
        }
        var chain = wm.beginUniqueWork("finalize-$docId", ExistingWorkPolicy.REPLACE, ocr)
        if (uploads.isNotEmpty()) chain = chain.then(uploads)
        chain.enqueue()
    }

    suspend fun enqueueUpload(docId: Long, endpointId: Long) {
        repo.setUpload(docId, endpointId, UploadStatus.QUEUED, "In coda")
        wm.enqueueUniqueWork("upload-$docId-$endpointId", ExistingWorkPolicy.REPLACE, uploadRequest(docId, endpointId))
    }

    private fun uploadRequest(docId: Long, endpointId: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<UploadWorker>()
            .setInputData(workDataOf(KEY_DOC to docId, KEY_ENDPOINT to endpointId))
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag("upload")
            .build()

    // ---------- upload ----------

    suspend fun upload(docId: Long, endpointId: Long): String? = withContext(Dispatchers.IO) {
        val ep = repo.endpoint(endpointId) ?: throw IllegalStateException("Endpoint rimosso")
        val doc = repo.getDoc(docId) ?: throw IllegalStateException("Documento rimosso")
        if (repo.pages(docId).any { it.ocrText == null }) repo.runOcr(docId, context.container.ocr, context.container.codes)
        val dir = File(context.cacheDir, "upload/$docId-$endpointId").apply { deleteRecursively(); mkdirs() }
        try {
            val fmt = effectiveFormat(doc.output, ep.format)
            val files: List<Pair<File, String>> = when (fmt) {
                UploadFormat.PDF -> listOf(exporter.exportPdf(docId, false, dir) to "application/pdf")
                UploadFormat.PDF_OCR -> listOf(exporter.exportPdf(docId, true, dir) to "application/pdf")
                UploadFormat.IMAGES -> exporter.exportImages(docId, dir).map {
                    it to if (it.extension == "png") "image/png" else "image/jpeg"
                }
            }
            val freshDoc = repo.getDoc(docId) ?: doc
            val meta = metadata(freshDoc, ep, fmt, files.map { it.first.name }).toString()
            when (ep.type) {
                EndpointType.HTTP -> httpUpload(ep, meta, files)
                EndpointType.WEBDAV -> webdavUpload(ep, freshDoc, meta, files)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Request.Builder.withHeaders(ep: EndpointEntity): Request.Builder = apply {
        EndpointConfig.headers(ep).forEach { (k, v) -> header(k, v) }
        header("User-Agent", "SmartScan-Android")
    }

    private fun httpUpload(ep: EndpointEntity, meta: String, files: List<Pair<File, String>>): String? {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("metadata", "metadata.json", meta.toRequestBody("application/json".toMediaType()))
        files.forEach { (f, mime) -> body.addFormDataPart("file", f.name, f.asRequestBody(mime.toMediaType())) }
        val req = Request.Builder().url(ep.url).post(body.build()).withHeaders(ep).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code}: ${text.take(200)}")
            return runCatching {
                json.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
    }

    private fun webdavUpload(ep: EndpointEntity, doc: DocumentEntity, meta: String, files: List<Pair<File, String>>): String {
        val base = ep.url.trimEnd('/') + "/"
        for ((f, mime) in files) put(ep, base + Uri.encode(f.name), f.asRequestBody(mime.toMediaType()))
        val metaName = Exporter.fileBase(doc) + ".json"
        put(ep, base + Uri.encode(metaName), meta.toRequestBody("application/json".toMediaType()))
        return base + Uri.encode(files.first().first.name)
    }

    private fun put(ep: EndpointEntity, url: String, body: okhttp3.RequestBody) {
        val req = Request.Builder().url(url).put(body).withHeaders(ep).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpStatusException(resp.code, "WebDAV ${resp.code} su ${url.substringAfterLast('/')}")
        }
    }

    /** The document's choice wins; for PDF the endpoint decides whether it carries the OCR text layer. */
    private fun effectiveFormat(output: DocOutput, endpoint: UploadFormat): UploadFormat = when {
        output == DocOutput.IMAGES -> UploadFormat.IMAGES
        endpoint == UploadFormat.PDF -> UploadFormat.PDF
        else -> UploadFormat.PDF_OCR
    }

    private suspend fun metadata(
        doc: DocumentEntity,
        ep: EndpointEntity,
        fmt: UploadFormat,
        fileNames: List<String>,
    ): JsonObject {
        val all = repo.nodes()
        val byId = all.associateBy { it.id }
        val assigned = repo.docNodeIds(doc.id).mapNotNull { byId[it] }
        val pages = repo.pageCount(doc.id)
        fun node(n: TaxonomyNode) = buildJsonObject {
            put("name", n.name)
            put("path", Repository.nodePath(n, byId))
            if (n.endpointId == ep.id && n.remoteId != null) put("id", n.remoteId)
            put("source", if (n.endpointId == null) "local" else if (n.endpointId == ep.id) "endpoint" else "other")
        }
        return buildJsonObject {
            put("schema", "smartscan/1")
            put("deviceDocId", doc.id)
            put("title", doc.title)
            put("notes", doc.notes)
            doc.docDate?.let { put("date", it) }
            put("createdAt", iso(doc.createdAt))
            put("updatedAt", iso(doc.updatedAt))
            put("pages", pages)
            put("format", fmt.name.lowercase())
            putJsonArray("categories") { assigned.filter { it.kind == NodeKind.CATEGORY }.forEach { add(node(it)) } }
            putJsonArray("tags") { assigned.filter { it.kind == NodeKind.TAG }.forEach { add(node(it)) } }
            putJsonArray("files") { fileNames.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("ocrText", doc.ocrText)
            // Added in 0.3: per-page note, capture position and decoded QR codes / barcodes.
            val metas = repo.pages(doc.id).map { com.smartscan.export.PageMeta.of(it) }
            putJsonArray("pageInfo") {
                metas.forEachIndexed { i, m ->
                    add(buildJsonObject {
                        put("page", i + 1)
                        if (m.note.isNotBlank()) put("note", m.note)
                        if (m.lat != null && m.lon != null) {
                            put("lat", m.lat)
                            put("lon", m.lon)
                        }
                        putJsonArray("codes") {
                            m.codes.forEach { code ->
                                add(buildJsonObject { put("format", code.format); put("value", code.value) })
                            }
                        }
                    })
                }
            }
        }
    }

    private fun iso(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(ms))

    // ---------- taxonomy ----------

    /** Downloads the category/tag tree from the endpoint and merges it (stable ids, keeps assignments). */
    suspend fun syncTaxonomy(endpointId: Long): Result<Int> = withContext(Dispatchers.IO) {
        val ep = repo.endpoint(endpointId) ?: return@withContext Result.failure(IllegalStateException("Endpoint rimosso"))
        val url = ep.taxonomyUrl ?: return@withContext Result.success(0)
        val res = runCatching {
            val req = Request.Builder().url(url).get().withHeaders(ep).header("Accept", "application/json").build()
            val body = http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw HttpStatusException(resp.code, "HTTP ${resp.code}")
                resp.body?.string().orEmpty()
            }
            val tax = json.decodeFromString<RemoteTaxonomy>(body)
            merge(ep, tax)
        }
        repo.endpoint(endpointId)?.let {
            repo.updateEndpoint(
                it.copy(
                    lastSync = if (res.isSuccess) System.currentTimeMillis() else it.lastSync,
                    lastSyncError = res.exceptionOrNull()?.message
                )
            )
        }
        res
    }

    suspend fun syncAllTaxonomies() {
        repo.endpoints().filter { it.taxonomyUrl != null }.forEach { syncTaxonomy(it.id) }
    }

    private suspend fun merge(ep: EndpointEntity, tax: RemoteTaxonomy): Int = repo.db.withTransaction {
        val existing = repo.nodesForEndpoint(ep.id).associateBy { it.kind to it.remoteId }
        val seen = HashSet<Long>()
        suspend fun walk(items: List<RemoteNode>, kind: NodeKind, parentId: Long?, prefix: String) {
            for (item in items) {
                val rid = item.id ?: "$prefix/${item.name}"
                val ex = existing[kind to rid]
                val id = if (ex != null) {
                    if (ex.name != item.name || ex.parentId != parentId) repo.updateNode(ex.copy(name = item.name, parentId = parentId))
                    ex.id
                } else {
                    repo.db.taxonomy().insert(
                        TaxonomyNode(kind = kind, name = item.name, parentId = parentId, endpointId = ep.id, remoteId = rid)
                    )
                }
                seen += id
                walk(item.children, kind, id, rid)
            }
        }
        walk(tax.categories, NodeKind.CATEGORY, null, "c")
        walk(tax.tags, NodeKind.TAG, null, "t")
        // Delete leaves first so cascades don't hit nodes we are keeping.
        existing.values.filter { it.id !in seen }.forEach { n ->
            if (repo.nodesForEndpoint(ep.id).any { it.id == n.id }) repo.deleteNode(n)
        }
        seen.size
    }

    private object Uri {
        fun encode(s: String): String = android.net.Uri.encode(s)
    }

    companion object {
        const val KEY_DOC = "docId"
        const val KEY_ENDPOINT = "endpointId"
    }
}

class OcrWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val docId = inputData.getLong(SyncManager.KEY_DOC, -1)
        if (docId < 0) return Result.failure()
        val c = applicationContext.container
        runCatching { c.repo.runOcr(docId, c.ocr, c.codes) }
        return Result.success()
    }
}

class UploadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val docId = inputData.getLong(SyncManager.KEY_DOC, -1)
        val epId = inputData.getLong(SyncManager.KEY_ENDPOINT, -1)
        if (docId < 0 || epId < 0) return Result.failure()
        val c = applicationContext.container
        c.repo.setUpload(docId, epId, UploadStatus.RUNNING, "Invio in corso…")
        return try {
            val ref = c.sync.upload(docId, epId)
            c.repo.setUpload(docId, epId, UploadStatus.DONE, "Inviato", ref)
            Result.success()
        } catch (e: Exception) {
            val retryable = e is IOException && !(e is HttpStatusException && e.code in 400..499 && e.code != 408 && e.code != 429)
            if (retryable && runAttemptCount < 5) {
                c.repo.setUpload(docId, epId, UploadStatus.QUEUED, "Nuovo tentativo: ${e.message}")
                Result.retry()
            } else {
                c.repo.setUpload(docId, epId, UploadStatus.FAILED, e.message ?: e.javaClass.simpleName)
                Result.failure()
            }
        }
    }
}
