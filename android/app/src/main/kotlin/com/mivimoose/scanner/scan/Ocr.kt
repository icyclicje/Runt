package com.mivimoose.scanner.scan

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.mivimoose.core.text.OcrLine
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** On-device text reading (ML Kit): Latin script, and Japanese (which also reads Latin). */
class Ocr {
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())

    /** Lines of text on a straightened card image, with boxes as fractions of the card. */
    suspend fun read(bmp: Bitmap, script: String): List<OcrLine> {
        val client = if (script == "ja" || script == "ko" || script.startsWith("zh")) japanese else latin
        val text: Text = suspendCancellableCoroutine<Text?> { cont ->
            client.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resume(null) }
        } ?: return emptyList()
        val w = bmp.width.toFloat(); val h = bmp.height.toFloat()
        return text.textBlocks.flatMap { b ->
            b.lines.mapNotNull { l ->
                val r = l.boundingBox ?: return@mapNotNull null
                OcrLine(l.text, floatArrayOf(r.left / w, r.top / h, r.right / w, r.bottom / h))
            }
        }
    }

    fun close() { latin.close(); japanese.close() }
}
