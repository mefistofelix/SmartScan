package com.smartscan.export

import com.smartscan.str

import com.smartscan.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.smartscan.data.AppSettings
import com.smartscan.data.DocOutput
import com.smartscan.data.DocStatus
import com.smartscan.data.DocumentEntity
import com.smartscan.data.ExportQuality
import com.smartscan.data.PageEntity
import com.smartscan.data.PageFilter
import com.smartscan.data.Repository
import com.smartscan.ocr.OcrLine
import com.smartscan.scan.ImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.imgcodecs.Imgcodecs
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds PDF / image / text exports with size optimization driven by [AppSettings.quality]. */
class Exporter(
    private val context: Context,
    private val repo: Repository,
    private val settings: AppSettings,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun exportsDir(): File = File(context.cacheDir, "exports").apply { mkdirs() }

    suspend fun exportPdf(docId: Long, withOcr: Boolean, outDir: File = exportsDir()): File =
        withContext(Dispatchers.Default) {
            val doc = requireNotNull(repo.getDoc(docId)) { str(R.string.err_doc_not_found) }
            val pages = repo.pages(docId)
            require(pages.isNotEmpty()) { str(R.string.err_doc_no_pages) }
            val q = settings.quality
            val meta = docMeta(doc, pages)
            val pdfPages = pages.mapIndexed { i, p ->
                val ocr = if (withOcr) runCatching { json.decodeFromString<List<OcrLine>>(p.ocrLines) }.getOrNull() else null
                val (side, jq) = limits(p.originalPath, q)
                PdfPage(encode(p.processedPath, p.filter == PageFilter.BW, side, jq), ocr, meta.pages[i].text())
            }
            outDir.mkdirs()
            val file = File(outDir, fileBase(doc) + ".pdf")
            file.outputStream().buffered().use { PdfWriter(it).write(pdfPages, displayTitle(doc), settings.pageSize, pdfInfo(meta)) }
            file
        }

    suspend fun exportImages(docId: Long, outDir: File = exportsDir()): List<File> =
        withContext(Dispatchers.Default) {
            val doc = requireNotNull(repo.getDoc(docId)) { str(R.string.err_doc_not_found) }
            val q = settings.quality
            outDir.mkdirs()
            val pages = repo.pages(docId)
            val meta = docMeta(doc, pages)
            pages.mapIndexed { i, p ->
                val (side, jq) = limits(p.originalPath, q)
                val m = ImageProcessor.readForExport(p.processedPath, side)
                val bw = p.filter == PageFilter.BW
                val f = File(outDir, "${fileBase(doc)}_p${i + 1}." + if (bw) "png" else "jpg")
                val params = if (bw) MatOfInt(Imgcodecs.IMWRITE_PNG_BILEVEL, 1)
                else MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jq)
                Imgcodecs.imwrite(f.absolutePath, m, params)
                m.release()
                writeImageMeta(f, meta, meta.pages[i])
                f
            }
        }

    suspend fun exportText(docId: Long, outDir: File = exportsDir()): File = withContext(Dispatchers.IO) {
        val doc = requireNotNull(repo.getDoc(docId)) { str(R.string.err_doc_not_found) }
        val text = repo.pages(docId).mapIndexed { i, p -> str(R.string.ocr_page_header, i + 1) + "\n" + p.ocrText.orEmpty() }
            .joinToString("\n\n")
        outDir.mkdirs()
        File(outDir, fileBase(doc) + ".txt").apply { writeText(text) }
    }

    private suspend fun docMeta(doc: DocumentEntity, pages: List<PageEntity>): DocMeta {
        val byId = repo.nodes().associateBy { it.id }
        val names = repo.docNodeIds(doc.id).mapNotNull { byId[it]?.name }
        return DocMeta(doc, names, pages.map { PageMeta.of(it) })
    }

    /** Title, notes (Subject), categories and tags (Keywords), decoded codes and capture position. */
    private fun pdfInfo(m: DocMeta): PdfInfo {
        val first = m.pages.firstOrNull { it.lat != null }
        return PdfInfo(
            subject = m.doc.notes,
            keywords = m.keywords.joinToString("; "),
            custom = buildMap {
                if (m.allCodes.isNotEmpty()) put("SmartScanCodes", m.allCodes.joinToString("\n") { it.format + ": " + it.value })
                if (first?.lat != null && first.lon != null) put("SmartScanGPS", PageMeta.coord(first.lat) + "," + PageMeta.coord(first.lon))
                m.doc.docDate?.let { put("SmartScanDate", it) }
            },
        )
    }

    /**
     * EXIF + XMP on an exported image: description (title and page note), full notes, tags, codes and GPS.
     * EXIF text is ASCII only, so the complete UTF-8 text goes into the XMP packet as well.
     */
    private fun writeImageMeta(f: File, m: DocMeta, page: PageMeta) {
        runCatching {
            val exif = ExifInterface(f.absolutePath)
            val desc = listOf(m.title, page.text()).filter { it.isNotBlank() }.joinToString("\n")
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, Xmp.ascii(desc))
            if (m.doc.notes.isNotBlank()) exif.setAttribute(ExifInterface.TAG_USER_COMMENT, Xmp.ascii(m.doc.notes))
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, "SmartScan")
            if (page.lat != null && page.lon != null) exif.setLatLong(page.lat, page.lon)
            val description = listOf(m.doc.notes, page.text()).filter { it.isNotBlank() }.joinToString("\n")
            exif.setAttribute(ExifInterface.TAG_XMP, Xmp.build(m.title, description, m.keywords, page))
            exif.saveAttributes()
        }.onFailure { android.util.Log.w("Exporter", "metadata ${f.name}", it) }
    }

    /** Pages captured with «Qualità max» skip the export downscaling and keep low compression. */
    private fun limits(originalPath: String, q: ExportQuality): Pair<Int, Int> =
        if (ImageProcessor.isHighQuality(originalPath)) ImageProcessor.HQ_EXPORT_MAX_SIDE to ImageProcessor.HQ_JPEG_QUALITY
        else q.maxSide to q.jpegQuality

    /**
     * One ZIP with several documents, each in its own output format: searchable PDF (plain PDF while
     * OCR is pending) or a folder of images.
     */
    suspend fun exportZip(docIds: List<Long>): File = withContext(Dispatchers.IO) {
        require(docIds.isNotEmpty()) { str(R.string.err_none_selected) }
        val work = File(exportsDir(), "zip_${System.nanoTime()}").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.ITALY).format(Date())
        val zip = File(exportsDir(), "SmartScan_${docIds.size}doc_$stamp.zip")
        try {
            val used = HashSet<String>()
            ZipOutputStream(zip.outputStream().buffered()).use { out ->
                for (id in docIds) {
                    val doc = repo.getDoc(id) ?: continue
                    if (repo.pageCount(id) == 0) continue
                    val dir = File(work, id.toString()).apply { mkdirs() }
                    var base = fileBase(doc)
                    while (!used.add(base)) base += "_"
                    if (doc.output == DocOutput.IMAGES) {
                        exportImages(id, dir).forEach { addEntry(out, "$base/${it.name}", it) }
                    } else {
                        val pdf = exportPdf(id, doc.status == DocStatus.READY, dir)
                        addEntry(out, "$base.pdf", pdf)
                    }
                }
            }
        } finally {
            work.deleteRecursively()
        }
        zip
    }

    private fun addEntry(out: ZipOutputStream, name: String, file: File) {
        out.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(out) }
        out.closeEntry()
    }

    private fun encode(path: String, bw: Boolean, maxSide: Int, quality: Int): EncodedImage {
        val m: Mat = ImageProcessor.readForExport(path, maxSide)
        try {
            if (bw && m.channels() == 1) {
                val w = m.cols()
                val h = m.rows()
                val data = ByteArray(w * h)
                m.get(0, 0, data)
                val rowBytes = (w + 7) / 8
                val packed = ByteArray(rowBytes * h)
                for (y in 0 until h) {
                    val ro = y * w
                    val po = y * rowBytes
                    for (x in 0 until w) {
                        if ((data[ro + x].toInt() and 0xFF) > 127) {
                            val idx = po + (x shr 3)
                            packed[idx] = (packed[idx].toInt() or (0x80 ushr (x and 7))).toByte()
                        }
                    }
                }
                return EncodedImage(PdfWriter.deflate(packed), w, h, EncodedImage.Kind.BITS1_FLATE)
            }
            val buf = MatOfByte()
            Imgcodecs.imencode(".jpg", m, buf, MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality))
            val bytes = buf.toArray()
            buf.release()
            val kind = if (m.channels() == 1) EncodedImage.Kind.JPEG_GRAY else EncodedImage.Kind.JPEG_RGB
            return EncodedImage(bytes, m.cols(), m.rows(), kind)
        } finally {
            m.release()
        }
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)

    fun shareIntent(files: List<File>, mime: String): Intent {
        val uris = ArrayList(files.map { uriFor(it) })
        val send = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        }
        send.type = mime
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return Intent.createChooser(send, str(R.string.share)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        fun displayTitle(doc: DocumentEntity) = doc.title.ifBlank { str(R.string.doc_n, doc.id) }

        fun fileBase(doc: DocumentEntity): String {
            val t = displayTitle(doc).replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().take(60)
            return "${t.ifBlank { "documento" }}_${doc.id}"
        }
    }
}
