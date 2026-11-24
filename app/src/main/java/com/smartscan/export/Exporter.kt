package com.smartscan.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.smartscan.data.AppSettings
import com.smartscan.data.DocumentEntity
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
            val doc = requireNotNull(repo.getDoc(docId)) { "Documento non trovato" }
            val pages = repo.pages(docId)
            require(pages.isNotEmpty()) { "Il documento non ha pagine" }
            val q = settings.quality
            val pdfPages = pages.map { p ->
                val ocr = if (withOcr) runCatching { json.decodeFromString<List<OcrLine>>(p.ocrLines) }.getOrNull() else null
                PdfPage(encode(p.processedPath, p.filter == PageFilter.BW, q.maxSide, q.jpegQuality), ocr)
            }
            outDir.mkdirs()
            val file = File(outDir, fileBase(doc) + ".pdf")
            file.outputStream().buffered().use { PdfWriter(it).write(pdfPages, displayTitle(doc), settings.pageSize) }
            file
        }

    suspend fun exportImages(docId: Long, outDir: File = exportsDir()): List<File> =
        withContext(Dispatchers.Default) {
            val doc = requireNotNull(repo.getDoc(docId)) { "Documento non trovato" }
            val q = settings.quality
            outDir.mkdirs()
            repo.pages(docId).mapIndexed { i, p ->
                val m = ImageProcessor.readForExport(p.processedPath, q.maxSide)
                val bw = p.filter == PageFilter.BW
                val f = File(outDir, "${fileBase(doc)}_p${i + 1}." + if (bw) "png" else "jpg")
                val params = if (bw) MatOfInt(Imgcodecs.IMWRITE_PNG_BILEVEL, 1)
                else MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, q.jpegQuality)
                Imgcodecs.imwrite(f.absolutePath, m, params)
                m.release()
                f
            }
        }

    suspend fun exportText(docId: Long, outDir: File = exportsDir()): File = withContext(Dispatchers.IO) {
        val doc = requireNotNull(repo.getDoc(docId)) { "Documento non trovato" }
        val text = repo.pages(docId).mapIndexed { i, p -> "--- Pagina ${i + 1} ---\n${p.ocrText.orEmpty()}" }
            .joinToString("\n\n")
        outDir.mkdirs()
        File(outDir, fileBase(doc) + ".txt").apply { writeText(text) }
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
        return Intent.createChooser(send, "Condividi").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        fun displayTitle(doc: DocumentEntity) = doc.title.ifBlank { "Documento ${doc.id}" }

        fun fileBase(doc: DocumentEntity): String {
            val t = displayTitle(doc).replace(Regex("[^\\p{L}\\p{N} _.-]"), "_").trim().take(60)
            return "${t.ifBlank { "documento" }}_${doc.id}"
        }
    }
}
