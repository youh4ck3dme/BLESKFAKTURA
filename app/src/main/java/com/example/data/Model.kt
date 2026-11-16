package com.example.data

import java.util.UUID

data class Invoice(
    val id: String = UUID.randomUUID().toString(),
    val supplierName: String = "",
    val ico: String? = null,
    val dic: String? = null,
    val issueDate: String = "",
    val dueDate: String? = null,
    val isPaid: Boolean = false,
    val currency: String = "EUR",
    val totalAmount: Double = 0.0,
    val totalAmountCents: Long = 0L,
    val vatAmount: Double? = null,
    val vatAmountCents: Long? = null,
    val vatRate: Double? = null,
    val category: String = "Iné",
    val description: String? = null,
    val imageUrl: String? = null,
    val userId: String = "",
    val createdAt: Long = System.currentTimeMillis()
) {
    fun effectiveTotalCents(): Long =
        if (totalAmountCents > 0L) totalAmountCents else SlovakFinanceUtils.doubleToCents(totalAmount)

    fun effectiveVatCents(): Long? =
        vatAmountCents ?: vatAmount?.let { SlovakFinanceUtils.doubleToCents(it) }
}

data class UserStats(
    val userId: String = "",
    val credits: Int = 10,
    val isSubscribed: Boolean = false,
    val totalInvoiced: Double = 0.0,
    val totalInvoicedCents: Long = 0L,
    val invoiceCount: Int = 0
) {
    fun effectiveTotalInvoicedCents(): Long =
        if (totalInvoicedCents > 0L) totalInvoicedCents else SlovakFinanceUtils.doubleToCents(totalInvoiced)
}
