package com.smartscan.export

import com.smartscan.data.DocumentEntity
import com.smartscan.data.PageEntity
import com.smartscan.ocr.PageCode
import kotlinx.serialization.json.Json
import java.text.Normalizer
import java.util.Locale

/** Per-page metadata written into exported files: voice note, capture position, decoded codes. */
class PageMeta(val note: String, val lat: Double?, val lon: Double?, val codes: List<PageCode>) {
    val isEmpty get() = note.isBlank() && lat == null && codes.isEmpty()

    /** Human readable block, used for the PDF page annotation and the image description. */
    fun text(): String = buildList {
        if (note.isNotBlank()) add("Nota: $note")
        codes.forEach { add("Codice ${it.format}: ${it.value}") }
        if (lat != null && lon != null) add("Posizione: ${coord(lat)}, ${coord(lon)}")
    }.joinToString("\n")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(p: PageEntity) = PageMeta(
            p.note, p.lat, p.lon,
            runCatching { json.decodeFromString<List<PageCode>>(p.codes) }.getOrDefault(emptyList()),
        )

        fun coord(v: Double) = String.format(Locale.US, "%.6f", v)
    }
}

/** Document-level metadata: title, full notes, categories and tags. */
class DocMeta(val doc: DocumentEntity, val keywords: List<String>, val pages: List<PageMeta>) {
    val title get() = Exporter.displayTitle(doc)
    val allCodes get() = pages.flatMap { it.codes }.distinct()
}

/** XMP packet (Dublin Core + smartscan namespace). ASCII only: other chars become XML entities. */
object Xmp {
    fun build(title: String, description: String, subjects: List<String>, page: PageMeta?): String {
        val sb = StringBuilder()
        sb.append("<?xpacket begin=\"\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
        sb.append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
        sb.append("<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
        sb.append(" xmlns:smartscan=\"urn:smartscan:ns:1\">")
        sb.append("<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(title)}</rdf:li></rdf:Alt></dc:title>")
        if (description.isNotBlank()) {
            sb.append("<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(description)}</rdf:li></rdf:Alt></dc:description>")
        }
        if (subjects.isNotEmpty()) {
            sb.append("<dc:subject><rdf:Bag>")
            subjects.forEach { sb.append("<rdf:li>${esc(it)}</rdf:li>") }
            sb.append("</rdf:Bag></dc:subject>")
        }
        if (page != null) {
            if (page.note.isNotBlank()) sb.append("<smartscan:pageNote>${esc(page.note)}</smartscan:pageNote>")
            if (page.codes.isNotEmpty()) {
                sb.append("<smartscan:codes><rdf:Seq>")
                page.codes.forEach { sb.append("<rdf:li>${esc(it.format + ": " + it.value)}</rdf:li>") }
                sb.append("</rdf:Seq></smartscan:codes>")
            }
        }
        sb.append("</rdf:Description></rdf:RDF></x:xmpmeta><?xpacket end=\"w\"?>")
        return sb.toString()
    }

    private fun esc(s: String): String {
        val sb = StringBuilder()
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;")
            ch == '<' -> sb.append("&lt;")
            ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;")
            ch == '\n' -> sb.append("&#10;")
            ch.code in 0x20..0x7E -> sb.append(ch)
            ch.code < 0x20 -> Unit
            else -> sb.append("&#x").append(Integer.toHexString(ch.code)).append(';')
        }
        return sb.toString()
    }

    /** EXIF ASCII fields: accents folded, other characters dropped. */
    fun ascii(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\p{M}+"), "")
            .map { if (it == '\n' || it.code in 0x20..0x7E) it else '?' }.joinToString("")
}
