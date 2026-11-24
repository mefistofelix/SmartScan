package com.smartscan.sync

import android.net.Uri
import android.util.Base64
import com.smartscan.data.EndpointEntity
import com.smartscan.data.EndpointType
import com.smartscan.data.UploadFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Endpoint configuration carried by a QR code (or a smartscan://add?cfg=<base64url> link).
 *
 * {"v":1,"name":"Ufficio","type":"http","url":"https://host/api/upload",
 *  "headers":{"Authorization":"Bearer ..."},"taxonomy":"https://host/api/taxonomy",
 *  "auto":true,"format":"pdf_ocr"}
 */
@Serializable
data class EndpointQr(
    val v: Int = 1,
    val name: String,
    val type: String = "http",
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val taxonomy: String? = null,
    val auto: Boolean = true,
    val format: String = "pdf_ocr",
)

object EndpointConfig {
    val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): EndpointEntity {
        var s = raw.trim()
        if (s.startsWith("smartscan://", ignoreCase = true)) {
            val cfg = Uri.parse(s).getQueryParameter("cfg")
                ?: throw IllegalArgumentException("Link SmartScan senza parametro cfg")
            s = String(Base64.decode(cfg, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
        }
        val q = try {
            json.decodeFromString<EndpointQr>(s)
        } catch (e: Exception) {
            throw IllegalArgumentException("QR non valido: non è una configurazione SmartScan")
        }
        require(q.url.startsWith("https://") || q.url.startsWith("http://")) { "URL non valido" }
        q.taxonomy?.let { require(it.startsWith("https://") || it.startsWith("http://")) { "URL tassonomia non valido" } }
        val type = when (q.type.lowercase()) {
            "webdav", "dav" -> EndpointType.WEBDAV
            else -> EndpointType.HTTP
        }
        val format = when (q.format.lowercase()) {
            "pdf" -> UploadFormat.PDF
            "images", "jpg", "jpeg", "image" -> UploadFormat.IMAGES
            else -> UploadFormat.PDF_OCR
        }
        return EndpointEntity(
            name = q.name.ifBlank { Uri.parse(q.url).host ?: "Endpoint" },
            type = type,
            url = q.url,
            headers = json.encodeToString(q.headers),
            taxonomyUrl = q.taxonomy?.takeIf { it.isNotBlank() },
            autoUpload = q.auto,
            format = format,
        )
    }

    fun headers(e: EndpointEntity): Map<String, String> =
        runCatching { json.decodeFromString<Map<String, String>>(e.headers) }.getOrDefault(emptyMap())
}
