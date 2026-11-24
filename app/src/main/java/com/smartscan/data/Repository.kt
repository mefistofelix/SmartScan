package com.smartscan.data

import android.content.Context
import androidx.room.withTransaction
import com.smartscan.ocr.OcrEngine
import com.smartscan.scan.Corners
import com.smartscan.scan.ImageProcessor
import com.smartscan.voice.MetadataParser
import com.smartscan.voice.ParsedMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class Repository(private val context: Context, val db: AppDatabase) {
    private val docs = db.documents()
    private val pagesDao = db.pages()
    private val tax = db.taxonomy()
    private val eps = db.endpoints()
    private val ups = db.uploads()
    private val renderMutex = Mutex()

    fun docDir(docId: Long) = File(context.filesDir, "docs/$docId").apply { mkdirs() }

    // ---- documents ----
    suspend fun createDraft(): Long = docs.insert(DocumentEntity())
    fun observeDocList(): Flow<List<DocListRow>> = docs.observeList()
    fun observeDoc(id: Long): Flow<DocumentEntity?> = docs.observe(id)
    suspend fun getDoc(id: Long) = docs.get(id)
    suspend fun updateDoc(d: DocumentEntity) = docs.update(d.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteDoc(id: Long) {
        docs.delete(id)
        withContext(Dispatchers.IO) { File(context.filesDir, "docs/$id").deleteRecursively() }
    }

    suspend fun cleanupEmptyDrafts() = docs.emptyDraftIds().forEach { deleteDoc(it) }

    /** Marks the document as complete; OCR + uploads are then enqueued by SyncManager. */
    suspend fun markProcessing(docId: Long, title: String?, notes: String?) {
        val d = docs.get(docId) ?: return
        val t = (title ?: d.title).ifBlank {
            "Scansione " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(d.createdAt))
        }
        updateDoc(d.copy(title = t, notes = notes ?: d.notes, status = DocStatus.PROCESSING))
    }

    // ---- pages ----
    fun observePages(docId: Long) = pagesDao.observe(docId)
    suspend fun pages(docId: Long) = pagesDao.list(docId)
    suspend fun page(id: Long) = pagesDao.get(id)
    suspend fun pageCount(docId: Long) = pagesDao.count(docId)

    suspend fun addCapturedPage(
        docId: Long, raw: File, hint: FloatArray?, filter: PageFilter, deskew: Boolean,
    ): PageEntity = renderMutex.withLock {
        withContext(Dispatchers.Default) {
            val dir = docDir(docId)
            val orig = File(dir, "orig_${System.currentTimeMillis()}.jpg")
            ImageProcessor.normalizeCapture(raw, orig)
            raw.delete()
            val corners = ImageProcessor.detectCorners(orig.absolutePath)
                ?: hint?.takeIf { Corners.area(it) > 0.1f }
                ?: Corners.FULL
            val pos = (pagesDao.maxPosition(docId) ?: -1) + 1
            val draft = PageEntity(
                docId = docId, position = pos, originalPath = orig.absolutePath, processedPath = "",
                corners = Corners.format(corners), filter = filter,
            )
            val id = pagesDao.insert(draft)
            val withId = draft.copy(id = id)
            val processed = ImageProcessor.renderPage(withId, dir, deskew)
            val done = withId.copy(processedPath = processed)
            pagesDao.update(done)
            done
        }
    }

    /** Re-renders a page after crop / filter / rotation changes. OCR is reset. */
    suspend fun rerender(page: PageEntity, deskew: Boolean): PageEntity = renderMutex.withLock {
        withContext(Dispatchers.Default) {
            val newPath = ImageProcessor.renderPage(page, docDir(page.docId), deskew)
            val old = pagesDao.get(page.id)?.processedPath
            val upd = page.copy(processedPath = newPath, ocrText = null, ocrLines = "[]")
            pagesDao.update(upd)
            if (old != null && old != newPath) File(old).delete()
            upd
        }
    }

    suspend fun deletePage(p: PageEntity) {
        pagesDao.delete(p)
        File(p.originalPath).delete()
        File(p.processedPath).delete()
        pagesDao.updateAll(pagesDao.list(p.docId).mapIndexed { i, it -> it.copy(position = i) })
    }

    suspend fun movePage(p: PageEntity, delta: Int) {
        val list = pagesDao.list(p.docId).toMutableList()
        val i = list.indexOfFirst { it.id == p.id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, list.lastIndex)
        if (i == j) return
        val item = list.removeAt(i)
        list.add(j, item)
        pagesDao.updateAll(list.mapIndexed { k, it -> it.copy(position = k) })
    }

    suspend fun runOcr(docId: Long, engine: OcrEngine) {
        for (p in pagesDao.list(docId)) {
            if (p.ocrText != null) continue
            val upd = try {
                val (text, lines) = engine.recognize(p.processedPath)
                p.copy(ocrText = text, ocrLines = Json.encodeToString(lines))
            } catch (e: Exception) {
                p.copy(ocrText = "")
            }
            // Skip if the page was re-rendered meanwhile.
            val current = pagesDao.get(p.id) ?: continue
            if (current.processedPath == p.processedPath) pagesDao.update(upd)
        }
        val all = pagesDao.list(docId).joinToString("\n\n") { it.ocrText.orEmpty() }.trim()
        docs.get(docId)?.let { docs.update(it.copy(ocrText = all, status = DocStatus.READY)) }
    }

    // ---- taxonomy ----
    fun observeNodes() = tax.observeAll()
    suspend fun nodes() = tax.all()
    suspend fun addNode(kind: NodeKind, name: String, parentId: Long?): Long =
        tax.insert(TaxonomyNode(kind = kind, name = name.trim(), parentId = parentId))
    suspend fun updateNode(n: TaxonomyNode) = tax.update(n)
    suspend fun deleteNode(n: TaxonomyNode) = tax.delete(n)
    suspend fun nodesForEndpoint(endpointId: Long) = tax.forEndpoint(endpointId)
    fun observeRefs() = tax.observeRefs()
    fun observeDocNodeIds(docId: Long) = tax.observeNodeIds(docId)
    suspend fun docNodeIds(docId: Long) = tax.nodeIds(docId)

    suspend fun setDocNodes(docId: Long, ids: Set<Long>) = db.withTransaction {
        tax.clearRefs(docId)
        tax.insertRefs(ids.map { DocNodeRef(docId, it) })
    }

    suspend fun addDocNodes(docId: Long, ids: Collection<Long>) =
        tax.insertRefs(ids.map { DocNodeRef(docId, it) })

    /** Parses a spoken phrase and applies title / notes / date / categories / tags to [docId]. */
    suspend fun applyVoiceMetadata(docId: Long, text: String, createTags: Boolean): ParsedMetadata {
        val parsed = MetadataParser.parse(text, tax.all())
        val doc = docs.get(docId) ?: return parsed
        val created = if (createTags) parsed.newTags.map { addNode(NodeKind.TAG, it, null) } else emptyList()
        addDocNodes(docId, parsed.nodeIds + created)
        updateDoc(
            doc.copy(
                title = parsed.title ?: doc.title,
                notes = parsed.notes?.let { n -> if (doc.notes.isBlank()) n else doc.notes + "\n" + n } ?: doc.notes,
                docDate = parsed.date ?: doc.docDate,
            )
        )
        return parsed.copy(createdTagIds = created)
    }

    // ---- endpoints / uploads ----
    fun observeEndpoints() = eps.observeAll()
    suspend fun endpoints() = eps.all()
    suspend fun endpoint(id: Long) = eps.get(id)
    suspend fun addEndpoint(e: EndpointEntity) = eps.insert(e)
    suspend fun updateEndpoint(e: EndpointEntity) = eps.update(e)
    suspend fun deleteEndpoint(e: EndpointEntity) = eps.delete(e)
    fun observeUploads() = ups.observeAll()
    fun observeUploads(docId: Long) = ups.observeForDoc(docId)

    suspend fun setUpload(docId: Long, endpointId: Long, status: UploadStatus, msg: String = "", ref: String? = null) {
        runCatching { ups.upsert(UploadEntity(docId, endpointId, status, msg, ref)) }
    }

    companion object {
        fun nodePath(n: TaxonomyNode, byId: Map<Long, TaxonomyNode>): String {
            val parts = ArrayList<String>()
            var cur: TaxonomyNode? = n
            var guard = 0
            while (cur != null && guard++ < 64) {
                parts += cur.name
                cur = cur.parentId?.let { byId[it] }
            }
            return parts.reversed().joinToString("/")
        }
    }
}
