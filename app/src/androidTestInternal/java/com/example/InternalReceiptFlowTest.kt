package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.data.AiExtractionResult
import com.example.data.AppDatabase
import com.example.data.Invoice
import com.example.data.InvoiceExtractionProvider
import com.example.data.InvoiceRepository
import com.example.data.ReceiptImageProcessor
import com.example.ui.PdfExportResult
import com.example.ui.PdfExporter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Real bundled native OCR, Room and Android PDF on a device; no network/test AI response. */
@RunWith(AndroidJUnit4::class)
class InternalReceiptFlowTest {
    @Test
    fun localProfile_readsReceiptEditsStoresExportsSignsOutAndDeletes() = runBlocking {
        assertTrue(BuildConfig.INTERNAL_TESTING)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = syntheticReceipt()
        val recognized = withTimeout(60_000) { InvoiceExtractionProvider.extractInvoiceData(bitmap) }
        assertTrue("Actual on-device OCR failed: $recognized", recognized is AiExtractionResult.Success)
        val data = (recognized as AiExtractionResult.Success).data
        assertEquals("2026-11-16", data.issueDate)
        assertEquals(123.45, data.totalAmount, 0.001)
        assertNull(data.vatAmount)
        assertNull(data.vatRate)

        val repository = InvoiceRepository(context)
        repository.signInDemoUser()
        val uid = withTimeout(5_000) { repository.currentUser.filterNotNull().first().uid }
        assertTrue(uid.startsWith("demo_"))
        repository.initializeUserStats(uid)
        val id = "instrumented-${UUID.randomUUID()}"
        val image = ReceiptImageProcessor.archiveReceiptBitmap(context, bitmap, id)
        val imageFile = File(requireNotNull(image) { "Receipt image was not archived" })
        assertTrue(imageFile.exists())
        val invoice = Invoice(
            id = id,
            supplierName = data.supplierName,
            issueDate = data.issueDate,
            totalAmount = data.totalAmount,
            totalAmountCents = 12345,
            category = "Iné",
            imageUrl = imageFile.absolutePath,
            userId = uid
        )
        assertTrue(repository.insertInvoice(invoice).isSuccess)
        val edited = invoice.copy(supplierName = "Upravený testovací obchod", totalAmount = 124.50, totalAmountCents = 12450)
        assertTrue(repository.insertInvoice(edited).isSuccess)
        val saved = repository.getInvoices().first().single { it.id == id }
        assertEquals("Upravený testovací obchod", saved.supplierName)
        assertEquals(12450L, saved.effectiveTotalCents())

        val pdf = PdfExporter.generatePdfFile(context, listOf(saved), "11/2026")
        assertTrue("Android PDF rendering failed: $pdf", pdf is PdfExportResult.Success)
        val pdfFile = (pdf as PdfExportResult.Success).file
        assertTrue(pdfFile.length() > 500)
        assertEquals("%PDF-", pdfFile.inputStream().use { String(ByteArray(5).also { bytes -> check(it.read(bytes) == 5) }, Charsets.US_ASCII) })

        repository.signOutAndClearLocalCache()
        assertNull(repository.currentUser.value)
        // Signing out a local profile must not destroy the only copy of a receipt.
        assertTrue(imageFile.exists())
        assertFalse(pdfFile.exists())
        repository.signInDemoUser()
        repository.initializeUserStats(uid)
        assertEquals(12450L, repository.getInvoices().first().single { it.id == id }.effectiveTotalCents())
        assertTrue(repository.deleteAccount().isSuccess)
        assertNull(repository.currentUser.value)
        assertTrue(AppDatabase.getDatabase(context).invoiceDao().getInvoicesForUserOnce(uid).isEmpty())
        assertNull(AppDatabase.getDatabase(context).userStatsDao().getUserStats(uid))
        assertFalse(imageFile.exists())
        bitmap.recycle()
    }

    private fun syntheticReceipt(): Bitmap = Bitmap.createBitmap(1200, 850, Bitmap.Config.ARGB_8888).also { bitmap ->
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 62f }
        listOf(
            "TESTOVACI OBCHOD",
            "FAKTURA - TESTOVACI DOKLAD",
            "ICO: 12345678",
            "DIC: 1234567890",
            "Datum: 16.11.2026",
            "CELKOM: 123,45 EUR"
        ).forEachIndexed { index, line -> canvas.drawText(line, 55f, 110f + index * 120f, paint) }
    }
}
