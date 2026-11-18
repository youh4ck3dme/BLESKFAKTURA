package com.example.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.ConnectivityManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import com.example.ui.PdfExporter
import com.example.ui.PdfExportResult
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real bundled ML Kit on an Android device. CI disables device networking before this suite. */
@RunWith(AndroidJUnit4::class)
class LocalReceiptNativeOcrTest {
    private fun assertOffline() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull("Run this suite with Wi-Fi and mobile data disabled", context.getSystemService(ConnectivityManager::class.java).activeNetwork)
    }
    private fun image(lines: List<String>): Bitmap {
        val bitmap = Bitmap.createBitmap(1200, 1000, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK; textSize = 54f; typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        lines.forEachIndexed { index, text -> canvas.drawText(text, 60f, 110f + index * 100f, paint) }
        return bitmap
    }
    @Test fun bundledModel_readsSyntheticReceiptWithDeviceNetworkDisabled() = runBlocking {
        assertOffline()
        val bitmap = image(listOf("SYNTHETIC SHOP s.r.o.", "FAKTURA", "ICO: 12345678", "Datum vystavenia:", "18.11.2026", "CELKOM 12,30 EUR"))
        try {
            val result = LocalReceiptOcr.extractInvoiceData(bitmap)
            assertTrue("Expected receipt recognition, got $result", result is AiExtractionResult.Success)
            val data = (result as AiExtractionResult.Success).data
            assertTrue(data.supplierName.isNotBlank()); assertEquals("12345678", data.ico)
            assertEquals("2026-11-18", data.issueDate); assertEquals(12.30, data.totalAmount, 0.001)
            assertNull(data.vatAmount); assertNull(data.vatRate); assertFalse(bitmap.isRecycled)
            assertTrue(data.reviewWarnings.isNotEmpty())
        } finally { bitmap.recycle() }
    }

    @Test fun offlineReceipt_roundTripsThroughRealRoomAndNativePdfWithoutInventedVat() = runBlocking {
        assertOffline()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = InvoiceRepository(context)
        repo.signInDemoUser()
        val uid = checkNotNull(repo.currentUser.value?.uid)
        assertTrue(uid.startsWith("demo_"))
        repo.initializeUserStats(uid)
        val before = withTimeout(15_000) { repo.getUserStatsFlow().first { it.userId == uid } }.credits
        val bitmap = image(listOf("SYNTHETIC SHOP s.r.o.", "FAKTURA", "18.11.2026", "CELKOM 12,30 EUR"))
        var savedId: String? = null
        var generated: java.io.File? = null
        try {
            val result = LocalReceiptOcr.extractInvoiceData(bitmap) as AiExtractionResult.Success
            val invoice = Invoice(supplierName = result.data.supplierName, issueDate = result.data.issueDate,
                totalAmount = result.data.totalAmount, totalAmountCents = SlovakFinanceUtils.doubleToCents(result.data.totalAmount),
                vatAmount = result.data.vatAmount, vatRate = result.data.vatRate, userId = uid)
            assertTrue(repo.insertInvoice(invoice).isSuccess); savedId = invoice.id
            val saved = withTimeout(15_000) { repo.getInvoices().first { rows -> rows.any { it.id == invoice.id } } }.single { it.id == invoice.id }
            assertEquals(1230L, saved.totalAmountCents); assertNull(saved.vatAmount); assertNull(saved.vatAmountCents); assertNull(saved.vatRate)
            assertEquals(before, withTimeout(15_000) { repo.getUserStatsFlow().first { it.invoiceCount > 0 } }.credits)
            val pdf = PdfExporter.generatePdfFile(context, listOf(saved), "Synthetic offline OCR test")
            assertTrue("PDF generation failed: $pdf", pdf is PdfExportResult.Success)
            val file = (pdf as PdfExportResult.Success).file
            generated = file
            assertTrue(file.length() > 0)
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { renderer ->
                assertTrue(renderer.pageCount > 0)
                renderer.openPage(0).use { page -> assertTrue(page.width > 0); assertTrue(page.height > 0) }
            }
        } finally {
            bitmap.recycle(); savedId?.let { repo.deleteInvoice(it) }; generated?.delete(); repo.stopCloudSync()
        }
    }

    @Test fun unrelatedImage_isRejectedByRealModelWithoutCloudFallback() = runBlocking {
        assertOffline()
        val bitmap = image(listOf("SUMMER HOLIDAY", "BLUE SKY", "GREEN FOREST"))
        try {
            assertTrue(LocalReceiptOcr.extractInvoiceData(bitmap) is AiExtractionResult.InvalidDocument)
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }
}
