package com.smartscan.export

import com.smartscan.data.PdfPageSize
import com.smartscan.ocr.OcrLine
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.Deflater
import kotlin.math.max
import kotlin.math.min

/** An already-encoded page image ready to embed in a PDF. */
class EncodedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    val kind: Kind,
) {
    enum class Kind { JPEG_RGB, JPEG_GRAY, BITS1_FLATE }
}

class PdfPage(val image: EncodedImage, val ocr: List<OcrLine>?)

/**
 * Minimal PDF 1.4 writer. Images are embedded as-is (JPEG passthrough or 1-bit Flate),
 * which gives full control over output size. OCR text is written as an invisible
 * text layer (render mode 3) so the PDF is searchable and selectable.
 */
class PdfWriter(private val out: OutputStream) {
    private var pos = 0L
    private val offsets = HashMap<Int, Long>()
    private var nextId = 1

    private fun newId() = nextId++
    private fun raw(b: ByteArray) { out.write(b); pos += b.size }
    private fun raw(s: String) = raw(s.toByteArray(Charsets.ISO_8859_1))

    private fun obj(id: Int, body: String) {
        offsets[id] = pos
        raw("$id 0 obj\n$body\nendobj\n")
    }

    private fun stream(id: Int, dict: String, data: ByteArray) {
        offsets[id] = pos
        raw("$id 0 obj\n<< $dict /Length ${data.size} >>\nstream\n")
        raw(data)
        raw("\nendstream\nendobj\n")
    }

    fun write(pages: List<PdfPage>, title: String, pageSize: PdfPageSize) {
        raw("%PDF-1.4\n")
        raw(byteArrayOf(0x25, 0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte(), 0x0A))

        val catalog = newId()
        val pagesId = newId()
        val font = newId()
        val info = newId()
        val ids = pages.map { Triple(newId(), newId(), newId()) } // page, image, content

        obj(catalog, "<< /Type /Catalog /Pages $pagesId 0 R >>")
        obj(pagesId, "<< /Type /Pages /Kids [${ids.joinToString(" ") { "${it.first} 0 R" }}] /Count ${pages.size} >>")
        obj(font, "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        val date = SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(Date())
        obj(info, "<< /Title ${utf16Hex(title)} /Producer (SmartScan) /Creator (SmartScan) /CreationDate (D:$date) >>")

        pages.forEachIndexed { i, page ->
            val (pageId, imgId, contentId) = ids[i]
            val img = page.image
            val (pw, ph) = pageDims(img.width, img.height, pageSize)
            val s = min(pw / img.width, ph / img.height)
            val iw = img.width * s
            val ih = img.height * s
            val ix = (pw - iw) / 2
            val iy = (ph - ih) / 2

            val imgDict = when (img.kind) {
                EncodedImage.Kind.JPEG_RGB ->
                    "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode"
                EncodedImage.Kind.JPEG_GRAY ->
                    "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} /ColorSpace /DeviceGray /BitsPerComponent 8 /Filter /DCTDecode"
                EncodedImage.Kind.BITS1_FLATE ->
                    "/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} /ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode"
            }
            stream(imgId, imgDict, img.bytes)

            val sb = StringBuilder()
            sb.append("q ${f(iw)} 0 0 ${f(ih)} ${f(ix)} ${f(iy)} cm /Im0 Do Q\n")
            val lines = page.ocr.orEmpty().filter { it.t.isNotBlank() }
            if (lines.isNotEmpty()) {
                sb.append("BT 3 Tr\n")
                for (l in lines) {
                    val boxW = (l.r - l.l) * iw
                    val boxH = (l.b - l.tp) * ih
                    if (boxW <= 0 || boxH <= 0) continue
                    val fs = max(1.0, boxH * 0.9)
                    val x = ix + l.l * iw
                    val y = iy + (1 - l.b) * ih + boxH * 0.15
                    val est = 0.5 * fs * l.t.length
                    val tz = (100.0 * boxW / est).coerceIn(10.0, 500.0)
                    sb.append("/F1 ${f(fs)} Tf ${f(tz)} Tz 1 0 0 1 ${f(x)} ${f(y)} Tm <${winAnsiHex(l.t)}> Tj\n")
                }
                sb.append("ET\n")
            }
            stream(contentId, "/Filter /FlateDecode", deflate(sb.toString().toByteArray(Charsets.ISO_8859_1)))

            obj(
                pageId,
                "<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${f(pw)} ${f(ph)}] " +
                    "/Resources << /XObject << /Im0 $imgId 0 R >> /Font << /F1 $font 0 R >> >> " +
                    "/Contents $contentId 0 R >>"
            )
        }

        val xref = pos
        val size = nextId
        val sb = StringBuilder()
        sb.append("xref\n0 $size\n0000000000 65535 f \n")
        for (i in 1 until size) sb.append(String.format(Locale.US, "%010d 00000 n \n", offsets[i] ?: 0L))
        sb.append("trailer\n<< /Size $size /Root $catalog 0 R /Info $info 0 R >>\nstartxref\n$xref\n%%EOF\n")
        raw(sb.toString())
        out.flush()
    }

    private fun pageDims(w: Int, h: Int, size: PdfPageSize): Pair<Double, Double> {
        val landscape = w > h
        return when (size) {
            PdfPageSize.AUTO -> {
                val s = 842.0 / max(w, h)
                w * s to h * s
            }
            PdfPageSize.A4 -> if (landscape) 842.0 to 595.0 else 595.0 to 842.0
            PdfPageSize.LETTER -> if (landscape) 792.0 to 612.0 else 612.0 to 792.0
        }
    }

    companion object {
        fun f(v: Double): String = String.format(Locale.US, "%.2f", v)

        fun deflate(data: ByteArray): ByteArray {
            val d = Deflater(Deflater.BEST_COMPRESSION)
            d.setInput(data)
            d.finish()
            val bos = ByteArrayOutputStream(max(64, data.size / 4))
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) {
                val n = d.deflate(buf)
                bos.write(buf, 0, n)
            }
            d.end()
            return bos.toByteArray()
        }

        private fun utf16Hex(s: String): String {
            val sb = StringBuilder("<FEFF")
            for (ch in s) sb.append(String.format(Locale.US, "%04X", ch.code))
            return sb.append('>').toString()
        }

        /** Encodes text for a WinAnsi (cp1252) Type1 font; unknown chars become '?'. */
        private fun winAnsiHex(s: String): String {
            val sb = StringBuilder()
            for (ch in s) {
                val code = when {
                    ch.code in 0x20..0x7E -> ch.code
                    ch.code in 0xA0..0xFF -> ch.code
                    ch == '€' -> 0x80
                    ch == '’' || ch == '‘' -> 0x27
                    ch == '“' || ch == '”' -> 0x22
                    ch == '–' -> 0x96
                    ch == '—' -> 0x97
                    else -> 0x3F
                }
                sb.append(String.format(Locale.US, "%02X", code))
            }
            return sb.toString()
        }
    }
}
