package com.smartscan.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable
import kotlin.math.max

/** A decoded QR code / barcode found on a page. */
@Serializable
data class PageCode(val format: String, val value: String)

/** Finds every QR code and barcode on a page (ML Kit, on-device, all formats). */
class CodeReader {
    private val client = BarcodeScanning.getClient()

    /** Reads [primary] (the upright original, sharper for small codes) and falls back to [fallback]. */
    suspend fun read(primary: String, fallback: String?): List<PageCode> {
        val found = LinkedHashMap<String, PageCode>()
        for (path in listOfNotNull(primary, fallback)) {
            scan(path).forEach { found.putIfAbsent(it.format + "\u0000" + it.value, it) }
            if (found.isNotEmpty()) break
        }
        return found.values.toList()
    }

    private suspend fun scan(path: String): List<PageCode> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return emptyList()
        var sample = 1
        while (max(o.outWidth, o.outHeight) / sample > 3200) sample *= 2
        val bmp = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return emptyList()
        return try {
            client.process(InputImage.fromBitmap(bmp, 0)).await().mapNotNull { b ->
                val v = b.rawValue ?: b.displayValue ?: return@mapNotNull null
                PageCode(formatName(b.format), v)
            }
        } catch (e: Exception) {
            emptyList()
        } finally {
            bmp.recycle()
        }
    }

    companion object {
        fun formatName(f: Int) = when (f) {
            Barcode.FORMAT_QR_CODE -> "qr"
            Barcode.FORMAT_AZTEC -> "aztec"
            Barcode.FORMAT_DATA_MATRIX -> "datamatrix"
            Barcode.FORMAT_PDF417 -> "pdf417"
            Barcode.FORMAT_EAN_13 -> "ean13"
            Barcode.FORMAT_EAN_8 -> "ean8"
            Barcode.FORMAT_UPC_A -> "upca"
            Barcode.FORMAT_UPC_E -> "upce"
            Barcode.FORMAT_CODE_128 -> "code128"
            Barcode.FORMAT_CODE_39 -> "code39"
            Barcode.FORMAT_CODE_93 -> "code93"
            Barcode.FORMAT_CODABAR -> "codabar"
            Barcode.FORMAT_ITF -> "itf"
            else -> "other"
        }
    }
}
