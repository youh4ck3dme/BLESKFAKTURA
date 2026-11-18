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
    @Test fun unrelatedImage_isRejectedByRealModelWithoutCloudFallback() = runBlocking {
        assertOffline()
        val bitmap = image(listOf("SUMMER HOLIDAY", "BLUE SKY", "GREEN FOREST"))
        try {
            assertTrue(LocalReceiptOcr.extractInvoiceData(bitmap) is AiExtractionResult.InvalidDocument)
            assertFalse(bitmap.isRecycled)
        } finally { bitmap.recycle() }
    }
}
