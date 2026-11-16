package com.example.data

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout

/** The bundled model reads the image on-device; receipt images are never sent to Gemini. */
internal object InvoiceExtractionProvider {
    suspend fun extractInvoiceData(bitmap: Bitmap): AiExtractionResult {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val recognized = withTimeout(60_000L) {
                recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
            }
            LocalReceiptParser.parse(recognized.text)
        } catch (_: TimeoutCancellationException) {
            AiExtractionResult.Timeout
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            AiExtractionResult.ServiceError("Lokálne čítanie dokladu zlyhalo. Skúste ostrejšiu fotografiu alebo obrázok z galérie.")
        } finally {
            recognizer.close()
        }
    }
}
