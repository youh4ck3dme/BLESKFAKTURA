package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.Invoice
import com.example.ui.PdfExportResult
import com.example.ui.PdfExporter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PdfExporterTest {

    private val sampleInvoices = listOf(
        Invoice(
            id = "inv-1",
            supplierName = "Slovak Telekom, a.s.",
            ico = "35763469",
            dic = "2020273893",
            issueDate = "2026-11-10",
            totalAmount = 123.0,
            totalAmountCents = 12300L,
            vatAmount = 23.0,
            vatAmountCents = 2300L,
            vatRate = 23.0,
            category = "Telekomunikácie",
            userId = "u1"
        ),
        Invoice(
            id = "inv-2",
            supplierName = "Alza.sk s.r.o.",
            issueDate = "16.11.2026",
            totalAmount = 60.0,
            totalAmountCents = 6000L,
            vatAmount = 10.0,
            vatAmountCents = 1000L,
            vatRate = 20.0,
            category = "Hardware",
            userId = "u1"
        )
    )

    @Test
    fun generatePdfFile_emptyList_returnsEmpty(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val result = PdfExporter.generatePdfFile(context, emptyList(), "11/2026")
        assertTrue("Expected Empty, got $result", result is PdfExportResult.Empty)
    }

    @Test
    fun prepareReport_calculatesExactCentsAndSlovakFormatting() {
        val (summary, rows) = PdfExporter.prepareReport(sampleInvoices, "11/2026")
        assertEquals("11/2026", summary.periodLabel)
        assertEquals(2, summary.totalDocCount)
        assertEquals(18300L, summary.totalInvoicedCents)
        assertEquals(3300L, summary.totalVatCents)
        assertEquals("183,00 €", summary.formattedTotalInvoiced)
        assertEquals("33,00 €", summary.formattedTotalVat)

        assertEquals(2, rows.size)
        assertEquals("2026-11-10", rows[0].dateText)
        assertEquals("2026-11-16", rows[1].dateText)
        assertEquals("60,00 €", rows[1].amountText)
    }

    @Test
    fun generatePdfFile_cleansOldExportsAndWritesPdfFile(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val staleFile = File(context.cacheDir, "Prehlad_dokladov_1000.pdf").apply {
            writeText("stale")
        }
        assertTrue(staleFile.exists())

        val result = PdfExporter.generatePdfFile(
            context = context,
            invoices = sampleInvoices,
            periodLabel = "11/2026"
        ) { targetFile, summary, rows ->
            assertEquals("183,00 €", summary.formattedTotalInvoiced)
            assertEquals(2, rows.size)
            targetFile.writeText("%PDF-1.4\n%%EOF\n")
        }

        assertTrue("Expected Success, got $result", result is PdfExportResult.Success)
        val generated = (result as PdfExportResult.Success).file
        assertFalse(staleFile.exists())
        assertTrue(generated.exists())
        assertTrue(generated.readText().startsWith("%PDF"))
        generated.delete()
        Unit
    }

    @Test
    fun generatePdfFile_onWriteFailure_deletesPartialFileAndReturnsError(): Unit = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var capturedTarget: File? = null

        val result = PdfExporter.generatePdfFile(
            context = context,
            invoices = sampleInvoices,
            periodLabel = "11/2026"
        ) { targetFile, _, _ ->
            capturedTarget = targetFile
            targetFile.writeText("partial")
            throw IOException("Disk full")
        }

        assertTrue("Expected Error, got $result", result is PdfExportResult.Error)
        assertEquals("Disk full", (result as PdfExportResult.Error).message)
        assertFalse(capturedTarget!!.exists())
    }
}
