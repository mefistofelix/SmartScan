package com.smartscan.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.Serializable

/** One recognized text line with a box normalized to the page image (0..1). */
@Serializable
data class OcrLine(val t: String, val l: Float, val tp: Float, val r: Float, val b: Float)

/** On-device OCR (ML Kit, Latin script, bundled model: works offline). */
class OcrEngine {
    private val client = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognize(path: String): Pair<String, List<OcrLine>> {
        val bmp = BitmapFactory.decodeFile(path) ?: return "" to emptyList()
        try {
            val res = client.process(InputImage.fromBitmap(bmp, 0)).await()
            val w = bmp.width.toFloat()
            val h = bmp.height.toFloat()
            val lines = res.textBlocks.flatMap { it.lines }.mapNotNull { line ->
                line.boundingBox?.let { b ->
                    OcrLine(line.text, b.left / w, b.top / h, b.right / w, b.bottom / h)
                }
            }
            return res.text to lines
        } finally {
            bmp.recycle()
        }
    }
}
