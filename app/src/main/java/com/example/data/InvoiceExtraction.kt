package com.example.data

data class ExtractedInvoiceJson(
    val supplierName: String = "",
    val ico: String? = null,
    val dic: String? = null,
    val issueDate: String = "",
    val totalAmount: Double = 0.0,
    val vatAmount: Double? = null,
    val vatRate: Double? = null,
    val category: String = "Iné",
    val description: String? = null,
    val reviewWarnings: List<String> = emptyList()
)

sealed interface AiExtractionResult {
    data class Success(val data: ExtractedInvoiceJson) : AiExtractionResult
    data class InvalidDocument(val message: String) : AiExtractionResult
    data class ServiceError(val message: String) : AiExtractionResult
    data object Timeout : AiExtractionResult
}
