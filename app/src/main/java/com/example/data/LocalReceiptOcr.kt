package com.example.data

import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Bundled Latin model: image recognition does not call cloud AI or download its model. */
internal object LocalReceiptOcr : InvoiceAiExtractor {
    private val direct = Executor { it.run() }
    private val nativeBusy = AtomicBoolean(false)
    internal interface Recognizer {
        fun process(bitmap: Bitmap): Task<String>
        fun close()
    }

    override suspend fun extractInvoiceData(bitmap: Bitmap): AiExtractionResult = extract(bitmap)

    internal suspend fun extract(
        bitmap: Bitmap,
        dispatcher: CoroutineDispatcher = Dispatchers.Default,
        timeoutMillis: Long = 60_000L,
        factory: () -> Recognizer = {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            object : Recognizer {
                override fun process(bitmap: Bitmap): Task<String> = recognizer
                    .process(InputImage.fromBitmap(bitmap, 0))
                    .continueWith(direct) { it.result.text }
                override fun close() = recognizer.close()
            }
        }
    ): AiExtractionResult = withContext(dispatcher) {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) {
            return@withContext AiExtractionResult.InvalidDocument("Obrázok už nie je dostupný. Vyberte ho znova.")
        }
        if (!nativeBusy.compareAndSet(false, true)) {
            return@withContext AiExtractionResult.ServiceError("Predchádzajúce lokálne čítanie ešte dobieha. Skúste to o chvíľu znova.")
        }
        var owned: Bitmap? = null
        var recognizer: Recognizer? = null
        var submitted: Task<String>? = null
        val released = AtomicBoolean(false)
        fun release() {
            if (released.compareAndSet(false, true)) {
                try { recognizer?.close() } catch (_: Exception) { /* Do not expose SDK cleanup errors. */ }
                finally { try { owned?.recycle() } finally { nativeBusy.set(false) } }
            }
        }
        try {
            val ratio = 1600.0 / max(bitmap.width, bitmap.height).coerceAtLeast(1600)
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).roundToInt().coerceAtLeast(1),
                (bitmap.height * ratio).roundToInt().coerceAtLeast(1), true)
            // ML Kit Tasks are not cancelled by coroutine cancellation. Own the input until completion.
            owned = if (scaled === bitmap) bitmap.copy(Bitmap.Config.ARGB_8888, false) else scaled
            val input = checkNotNull(owned)
            val client = factory()
            recognizer = client
            val task = client.process(input)
            submitted = task
            task.addOnCompleteListener(direct) { release() }
            val text = withTimeoutOrNull(timeoutMillis) { task.await() }
                ?: return@withContext AiExtractionResult.Timeout
            LocalReceiptParser.parse(text)
        } catch (e: CancellationException) {
            throw e
        } catch (_: OutOfMemoryError) {
            AiExtractionResult.ServiceError("Na čítanie obrázka nie je dosť pamäte. Skúste menšiu fotografiu.")
        } catch (_: Exception) {
            AiExtractionResult.ServiceError("Lokálne čítanie dokladu zlyhalo. Skúste ostrejšiu fotografiu alebo obrázok z galérie.")
        } finally {
            if (submitted == null || submitted.isComplete) release()
        }
    }
}
