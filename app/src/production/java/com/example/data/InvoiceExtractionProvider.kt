package com.example.data

import android.graphics.Bitmap

internal object InvoiceExtractionProvider {
    suspend fun extractInvoiceData(bitmap: Bitmap): AiExtractionResult =
        InvoiceAiService.extractInvoiceData(bitmap)
}
