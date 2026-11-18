package com.example.data

import android.graphics.Bitmap
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.ThinkingLevel
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.firebase.ai.type.thinkingConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

object InvoiceAiService : InvoiceAiExtractor {
    private const val TAG = "InvoiceAiService"
    private const val MODEL_NAME = "gemini-3.5-flash-lite"
    private const val AI_TIMEOUT_MS = 60_000L
    private const val MAX_AI_IMAGE_DIMENSION = 1600

    private val allowedCategories = listOf(
        "Kancelária",
        "Cestovné",
        "Služby",
        "Technika",
        "Strava",
        "Iné"
    )

    private val invoiceSchema = Schema.obj(
        properties = mapOf(
            "isAccountingDocument" to Schema.boolean(
                description = "True only if the image is clearly an invoice, receipt, or accounting document."
            ),
            "supplierName" to Schema.string(
                description = "Supplier or merchant business name (e.g. Tesco, Orange, Lidl)."
            ),
            "ico" to Schema.string(
                description = "Slovak ICO identification number (digits only)",
                nullable = true
            ),
            "dic" to Schema.string(
                description = "Slovak DIC or IC DPH tax identifier",
                nullable = true
            ),
            "issueDate" to Schema.string(
                description = "Issue date strictly formatted as YYYY-MM-DD."
            ),
            "totalAmount" to Schema.double(
                description = "Total payable gross amount including VAT as a positive decimal number."
            ),
            "vatAmount" to Schema.double(
                description = "Total VAT amount in EUR if present on the document",
                nullable = true
            ),
            "vatRate" to Schema.double(
                description = "Primary VAT rate percentage (e.g. 23.0, 20.0, 10.0, 5.0, 0.0)",
                nullable = true
            ),
            "category" to Schema.enumeration(
                values = allowedCategories,
                description = "Expense category matching Slovak SZCO accounting."
            ),
            "description" to Schema.string(
                description = "Short Slovak summary of the expense",
                nullable = true
            )
        ),
        optionalProperties = listOf("ico", "dic", "vatAmount", "vatRate", "description")
    )

    private val model by lazy {
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                responseMimeType = "application/json"
                responseSchema = invoiceSchema
                maxOutputTokens = 4096
                thinkingConfig = thinkingConfig {
                    thinkingLevel = ThinkingLevel.MINIMAL
                }
            }
        )
    }

    override suspend fun extractInvoiceData(bitmap: Bitmap): AiExtractionResult =
        extractInvoiceData(bitmap, Dispatchers.IO) { scaledBitmap, promptText ->
            model.generateContent(
                content {
                    image(scaledBitmap)
                    text(promptText)
                }
            ).text
        }

    internal suspend fun extractInvoiceData(
        bitmap: Bitmap,
        dispatcher: CoroutineDispatcher,
        request: suspend (Bitmap, String) -> String?
    ): AiExtractionResult = withContext(dispatcher) {
        val scaledBitmap = scaleBitmapForAi(bitmap)
        val promptText = """
            Si presný audítor slovenského účtovníctva pre živnostníkov (SZČO).
            Analyzuj obrázok a zisti, či ide o faktúru alebo pokladničný blok.
            Ak obrázok NIE JE účtovný doklad alebo je úplne nečitateľný, nastav "isAccountingDocument" na false.
            Ak ide o doklad, extrahuj údaje do JSON schémy:
            - supplierName: obchodné meno dodávateľa
            - ico: IČO (iba číslice) alebo null
            - dic: DIČ alebo IČ DPH alebo null
            - issueDate: dátum vystavenia vo formáte YYYY-MM-DD
            - totalAmount: celková suma s DPH (kladné číslo)
            - vatAmount: celková vyčíslená DPH (ak je na doklade) alebo null
            - vatRate: hlavná sadzba DPH v % (napr. 23.0, 20.0, 10.0, 5.0, 0.0) alebo null
            - category: jedna z hodnôt ${allowedCategories.joinToString(", ")}
            - description: stručný slovenský popis položky alebo nákupu
        """.trimIndent()

        try {
            // A local deadline returns null; cancellation by the caller still propagates.
            withTimeoutOrNull(AI_TIMEOUT_MS) {
                val rawJson = request(scaledBitmap, promptText)
                if (rawJson.isNullOrBlank()) {
                    AiExtractionResult.InvalidDocument(
                        "AI nevrátila žiadne údaje z dokladu. Skúste odfotiť doklad znova."
                    )
                } else {
                    parseAndValidateJson(rawJson)
                }
            } ?: run {
                Log.e(TAG, "AI invoice extraction timed out")
                AiExtractionResult.Timeout
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // SDK exceptions can contain HTTP bodies, credentials and receipt contents.
            Log.e(TAG, "AI invoice extraction failed")
            AiExtractionResult.ServiceError(
                "AI analýza sa nepodarila. Skúste to znova."
            )
        } finally {
            if (scaledBitmap !== bitmap && !scaledBitmap.isRecycled) {
                scaledBitmap.recycle()
            }
        }
    }

    internal fun parseAndValidateJson(rawJson: String): AiExtractionResult {
        return try {
            val json = JSONObject(rawJson)
            if (json.length() == 0) {
                return AiExtractionResult.InvalidDocument(
                    "Doklad sa nepodarilo rozpoznať. Nahrajte čitateľnejšiu fotografiu faktúry alebo bločku."
                )
            }

            if (json.has("isAccountingDocument") && !json.optBoolean("isAccountingDocument", true)) {
                return AiExtractionResult.InvalidDocument(
                    "Obrázok nebol rozpoznaný ako faktúra ani pokladničný blok."
                )
            }

            val supplierName = json.optString("supplierName", "").trim()
            if (supplierName.isBlank() || supplierName.equals("null", ignoreCase = true)) {
                return AiExtractionResult.InvalidDocument(
                    "Na doklade sa nepodarilo identifikovať názov dodávateľa."
                )
            }

            if (!json.has("totalAmount") || json.isNull("totalAmount")) {
                return AiExtractionResult.InvalidDocument(
                    "Na doklade chýba celková suma k úhrade."
                )
            }
            val totalAmount = json.optDouble("totalAmount", Double.NaN)
            if (!totalAmount.isFinite() || totalAmount <= 0.0 || totalAmount > 1_000_000.0) {
                return AiExtractionResult.InvalidDocument(
                    "Extrahovaná celková suma nie je platná."
                )
            }

            val rawDate = json.optString("issueDate", "").trim()
            val normalizedDate = normalizeIsoDate(rawDate)
                ?: return AiExtractionResult.InvalidDocument(
                    "Na doklade chýba platný dátum vystavenia."
                )

            val vatAmount = if (json.has("vatAmount") && !json.isNull("vatAmount")) {
                val parsedVat = json.optDouble("vatAmount", Double.NaN)
                if (!parsedVat.isFinite() || parsedVat < 0.0 || parsedVat > totalAmount) {
                    return AiExtractionResult.InvalidDocument("Extrahovaná suma DPH nie je konzistentná s celkovou sumou.")
                }
                parsedVat
            } else {
                null
            }

            val vatRate = if (json.has("vatRate") && !json.isNull("vatRate")) {
                val parsedRate = json.optDouble("vatRate", Double.NaN)
                if (!parsedRate.isFinite() || parsedRate < 0.0 || parsedRate > 100.0) {
                    return AiExtractionResult.InvalidDocument("Extrahovaná sadzba DPH nie je platná.")
                }
                parsedRate
            } else {
                null
            }

            val rawCategory = json.optString("category", "Iné").trim()
            val category = if (rawCategory in allowedCategories) rawCategory else "Iné"

            val ico = json.optString("ico", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            val dic = json.optString("dic", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
            val description = json.optString("description", "").trim().takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

            AiExtractionResult.Success(
                ExtractedInvoiceJson(
                    supplierName = supplierName,
                    ico = ico,
                    dic = dic,
                    issueDate = normalizedDate,
                    totalAmount = totalAmount,
                    vatAmount = vatAmount,
                    vatRate = vatRate,
                    category = category,
                    description = description
                )
            )
        } catch (_: Exception) {
            Log.e(TAG, "Failed to parse structured AI JSON")
            AiExtractionResult.InvalidDocument(
                "Odpoveď AI nemá platný formát dokladu. Skúste prosím skenovať znova."
            )
        }
    }

    private fun normalizeIsoDate(raw: String): String? =
        SlovakFinanceUtils.normalizeIsoDate(raw)

    private fun scaleBitmapForAi(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val maxDim = maxOf(width, height)
        if (maxDim <= MAX_AI_IMAGE_DIMENSION) return bitmap
        val ratio = MAX_AI_IMAGE_DIMENSION.toFloat() / maxDim.toFloat()
        val targetWidth = (width * ratio).toInt().coerceAtLeast(1)
        val targetHeight = (height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
    }
}
