package com.smartscan.data

import android.content.Context
import androidx.room.withTransaction
import com.smartscan.ocr.CodeReader
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

    /**
     * Applies a new archive order for [ids] (top first): their existing sort keys are redistributed, so the
     * documents not in the list (filtered out) keep their place. Does not touch updatedAt.
     */
    suspend fun reorderDocs(ids: List<Long>) = db.withTransaction {
        val keys = ids.mapNotNull { docs.get(it)?.sortKey }.sortedDescending()
        if (keys.size != ids.size) return@withTransaction
        // Equal keys would make the order ambiguous: spread them first.
        val distinct = keys.mapIndexed { i, k -> k - i }
        ids.forEachIndexed { i, id -> docs.setSortKey(id, distinct[i]) }
    }

    suspend fun cleanupEmptyDrafts() = docs.emptyDraftIds().forEach { deleteDoc(it) }

    /** Marks the document as complete; OCR + uploads are then enqueued by SyncManager. */
    suspend fun markProcessing(
        docId: Long, title: String?, notes: String?, output: DocOutput? = null, upload: Boolean? = null,
    ) {
        val d = docs.get(docId) ?: return
        val t = (title ?: d.title).ifBlank {
            "Scansione " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALY).format(Date(d.createdAt))
        }
        updateDoc(d.copy(title = t, notes = notes ?: d.notes, output = output ?: d.output, upload = upload ?: d.upload,
            status = DocStatus.PROCESSING,
        ))
    }

    // ---- pages ----
    fun observePages(docId: Long) = pagesDao.observe(docId)
    suspend fun pages(docId: Long) = pagesDao.list(docId)
    suspend fun page(id: Long) = pagesDao.get(id)
    suspend fun pageCount(docId: Long) = pagesDao.count(docId)

    suspend fun addCapturedPage(
        docId: Long, raw: File, hint: FloatArray?, filter: PageFilter, deskew: Boolean,
        detect: Boolean = true, hq: Boolean = false, lat: Double? = null, lon: Double? = null,
    ): PageEntity = renderMutex.withLock {
        withContext(Dispatchers.Default) {
            val dir = docDir(docId)
            val orig = File(dir, "orig_${System.currentTimeMillis()}.jpg")
            ImageProcessor.normalizeCapture(raw, orig, hq)
            raw.delete()
            val corners = if (!detect) Corners.FULL
            else ImageProcessor.detectCorners(orig.absolutePath)
                ?: hint?.takeIf { Corners.area(it) > 0.1f }
                ?: Corners.FULL
            val pos = (pagesDao.maxPosition(docId) ?: -1) + 1
            val draft = PageEntity(
                docId = docId, position = pos, originalPath = orig.absolutePath, processedPath = "",
                corners = Corners.format(corners), filter = filter,
                lat = lat, lon = lon,
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
            val cur = pagesDao.get(page.id)
            val old = cur?.processedPath
            val upd = page.copy(
                processedPath = newPath, ocrText = null, ocrLines = "[]", codes = "[]", note = cur?.note ?: page.note,
            )
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

    suspend fun runOcr(docId: Long, engine: OcrEngine, codeReader: CodeReader? = null) {
        for (p in pagesDao.list(docId)) {
            if (p.ocrText != null) continue
            val result = try {
                val (text, lines) = engine.recognize(p.processedPath)
                val codes = runCatching { codeReader?.read(p.originalPath, p.processedPath) }.getOrNull().orEmpty()
                Triple(text, Json.encodeToString(lines), Json.encodeToString(codes))
            } catch (e: Exception) {
                Triple("", "[]", "[]")
            }
            // Skip if the page was re-rendered meanwhile; keep fields changed meanwhile (notes).
            val current = pagesDao.get(p.id) ?: continue
            if (current.processedPath == p.processedPath) pagesDao.update(
                current.copy(ocrText = result.first, ocrLines = result.second, codes = result.third)
            )
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

    /**
     * Appends a dictated note to the document notes (one line per dictation) and, when [pageId] is
     * given, to that page's own note. While the document has
     * no title yet, the first words of the note become its title.
     */
    suspend fun appendNote(docId: Long, text: String, pageId: Long? = null): DocumentEntity? {
        val doc = docs.get(docId) ?: return null
        val t = normalizeNote(text)
        if (t.isEmpty()) return doc
        pageId?.let { pagesDao.get(it) }?.let { p ->
            pagesDao.update(p.copy(note = if (p.note.isBlank()) t else p.note + "\n" + t))
        }
        val upd = doc.copy(
            title = doc.title.ifBlank { titleFrom(t) },
            notes = if (doc.notes.isBlank()) t else doc.notes + "\n" + t,
        )
        updateDoc(upd)
        return upd
    }

    /**
     * Removes one dictated note (the last line equal to [text]) from the document and its page. A title that
     * was taken from this note is cleared, so the automatic name or the next note applies.
     */
    suspend fun removeNote(docId: Long, text: String, pageId: Long?) {
        fun drop(all: String): String {
            val lines = all.split("\n").toMutableList()
            val i = lines.lastIndexOf(text)
            if (i >= 0) lines.removeAt(i)
            return lines.joinToString("\n")
        }
        pageId?.let { pagesDao.get(it) }?.let { p -> pagesDao.update(p.copy(note = drop(p.note))) }
        val doc = docs.get(docId) ?: return
        updateDoc(doc.copy(notes = drop(doc.notes), title = if (doc.title == titleFrom(text)) "" else doc.title))
    }

    private fun titleFrom(note: String): String {
        val words = note.split(Regex("\\s+")).take(8)
        val sb = StringBuilder()
        for (w in words) {
            if (sb.isNotEmpty() && sb.length + 1 + w.length > 50) break
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(w)
        }
        return sb.toString().take(50).trimEnd(',', '.', ';', ':')
    }

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
        fun normalizeNote(text: String) = text.trim().replaceFirstChar { it.uppercase() }

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
