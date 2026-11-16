package com.example.ui

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.data.Invoice
import com.example.data.SlovakFinanceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface PdfExportResult {
    data class Success(val file: File) : PdfExportResult
    data object Empty : PdfExportResult
    data class Error(val message: String) : PdfExportResult
}

object PdfExporter {
    private val slovakLocale: Locale = Locale.forLanguageTag("sk-SK")
    private const val PDF_PREFIX = "Prehlad_dokladov_"

    suspend fun exportInvoicesToPdf(
        context: Context,
        invoices: List<Invoice>,
        periodLabel: String = "Všetky obdobia"
    ): PdfExportResult {
        if (invoices.isEmpty()) {
            return PdfExportResult.Empty
        }

        val generatedFile = generatePdfFile(context, invoices, periodLabel)
        return when (generatedFile) {
            is PdfExportResult.Success -> {
                withContext(Dispatchers.Main) {
                    sharePdfFile(context, generatedFile.file, periodLabel)
                }
                generatedFile
            }
            else -> generatedFile
        }
    }

    internal data class PdfReportSummary(
        val periodLabel: String,
        val totalDocCount: Int,
        val totalInvoicedCents: Long,
        val totalVatCents: Long,
        val formattedTotalInvoiced: String,
        val formattedTotalVat: String
    )

    internal data class PdfInvoiceRow(
        val dateText: String,
        val supplierText: String,
        val icoDicText: String,
        val categoryText: String,
        val vatText: String,
        val amountText: String
    )

    internal fun prepareReport(
        invoices: List<Invoice>,
        periodLabel: String
    ): Pair<PdfReportSummary, List<PdfInvoiceRow>> {
        val totalInvoicedCents = invoices.sumOf { it.effectiveTotalCents() }
        val totalVatCents = invoices.sumOf { it.effectiveVatCents() ?: 0L }
        val summary = PdfReportSummary(
            periodLabel = periodLabel,
            totalDocCount = invoices.size,
            totalInvoicedCents = totalInvoicedCents,
            totalVatCents = totalVatCents,
            formattedTotalInvoiced = SlovakFinanceUtils.formatMoneyCents(totalInvoicedCents),
            formattedTotalVat = SlovakFinanceUtils.formatMoneyCents(totalVatCents)
        )
        val rows = invoices.map { invoice ->
            val dateText = SlovakFinanceUtils.normalizeIsoDate(invoice.issueDate) ?: invoice.issueDate
            val supplierText = if (invoice.supplierName.length > 22) {
                invoice.supplierName.substring(0, 20) + "..."
            } else {
                invoice.supplierName
            }
            val rawIcoDic = when {
                !invoice.ico.isNullOrBlank() && !invoice.dic.isNullOrBlank() -> "${invoice.ico} / ${invoice.dic}"
                !invoice.ico.isNullOrBlank() -> invoice.ico
                !invoice.dic.isNullOrBlank() -> invoice.dic
                else -> "-"
            }
            val icoDicText = if (rawIcoDic.length > 18) rawIcoDic.substring(0, 16) + "..." else rawIcoDic
            val vatCents = invoice.effectiveVatCents()
            val vatText = if (vatCents != null && vatCents > 0L) {
                SlovakFinanceUtils.formatMoneyCents(vatCents, invoice.currency)
            } else {
                "-"
            }
            val amountText = SlovakFinanceUtils.formatMoneyCents(invoice.effectiveTotalCents(), invoice.currency)
            PdfInvoiceRow(
                dateText = dateText,
                supplierText = supplierText,
                icoDicText = icoDicText,
                categoryText = invoice.category,
                vatText = vatText,
                amountText = amountText
            )
        }
        return summary to rows
    }

    internal suspend fun generatePdfFile(
        context: Context,
        invoices: List<Invoice>,
        periodLabel: String = "Všetky obdobia",
        writePdf: (File, PdfReportSummary, List<PdfInvoiceRow>) -> Unit = ::renderAndroidPdfDocument
    ): PdfExportResult = withContext(Dispatchers.IO) {
        if (invoices.isEmpty()) return@withContext PdfExportResult.Empty

        val cacheDir = context.cacheDir
        cleanOldExportPdfs(cacheDir)
        val pdfFile = File(cacheDir, "${PDF_PREFIX}${System.currentTimeMillis()}.pdf")
        val (summary, rows) = prepareReport(invoices, periodLabel)

        try {
            writePdf(pdfFile, summary, rows)
            PdfExportResult.Success(pdfFile)
        } catch (e: Exception) {
            pdfFile.delete()
            PdfExportResult.Error(e.localizedMessage ?: "Neznáma chyba pri zápise PDF")
        }
    }

    private fun renderAndroidPdfDocument(
        pdfFile: File,
        summary: PdfReportSummary,
        rows: List<PdfInvoiceRow>
    ) {
        val pdfDocument = PdfDocument()
        try {
            var pageNumber = 1
            var pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
            var page = pdfDocument.startPage(pageInfo)
            var canvas = page.canvas

            val textPaint = Paint().apply {
                isAntiAlias = true
                color = Color.rgb(29, 27, 32)
                textSize = 9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            val boldPaint = Paint().apply {
                isAntiAlias = true
                color = Color.rgb(29, 27, 32)
                textSize = 9f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val rightAlignPaint = Paint().apply {
                isAntiAlias = true
                color = Color.rgb(29, 27, 32)
                textSize = 9f
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            val rightAlignBoldPaint = Paint().apply {
                isAntiAlias = true
                color = Color.rgb(29, 27, 32)
                textSize = 9f
                textAlign = Paint.Align.RIGHT
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val headerPaint = Paint().apply {
                isAntiAlias = true
                color = Color.rgb(103, 80, 164)
                textSize = 18f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }

            val subHeaderPaint = Paint().apply {
                isAntiAlias = true
                color = Color.rgb(73, 69, 79)
                textSize = 10f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
            }

            val linePaint = Paint().apply {
                isAntiAlias = true
                strokeWidth = 1f
                color = Color.rgb(202, 196, 208)
            }

            val thinLinePaint = Paint().apply {
                isAntiAlias = true
                strokeWidth = 0.5f
                color = Color.rgb(230, 225, 230)
            }

            val boxPaint = Paint().apply {
                isAntiAlias = true
                color = Color.rgb(243, 237, 255)
                style = Paint.Style.FILL
            }

            val rowBgPaint = Paint().apply {
                color = Color.rgb(250, 248, 252)
                style = Paint.Style.FILL
            }

            fun drawPageHeader(pageNum: Int, canvasToWrite: android.graphics.Canvas) {
                canvasToWrite.drawText("PREHĽAD VÝDAVKOV A FAKTÚR", 40f, 60f, headerPaint)
                val dateStr = SimpleDateFormat("dd.MM.yyyy HH:mm", slovakLocale).format(Date())
                canvasToWrite.drawText("Prehľad (${summary.periodLabel}) vygenerovaný dňa: $dateStr", 40f, 78f, subHeaderPaint)
                canvasToWrite.drawText("Strana $pageNum", 555f, 60f, rightAlignPaint)

                linePaint.color = Color.rgb(103, 80, 164)
                linePaint.strokeWidth = 1.5f
                canvasToWrite.drawLine(40f, 90f, 555f, 90f, linePaint)
            }

            fun drawTableHeader(yPos: Float, canvasToWrite: android.graphics.Canvas) {
                boldPaint.color = Color.rgb(29, 27, 32)
                canvasToWrite.drawText("Dátum", 40f, yPos, boldPaint)
                canvasToWrite.drawText("Dodávateľ", 110f, yPos, boldPaint)
                canvasToWrite.drawText("IČO / DIČ", 240f, yPos, boldPaint)
                canvasToWrite.drawText("Kategória", 345f, yPos, boldPaint)
                canvasToWrite.drawText("DPH", 455f, yPos, boldPaint)
                canvasToWrite.drawText("Suma s DPH", 555f, yPos, rightAlignBoldPaint)

                linePaint.color = Color.rgb(103, 80, 164)
                linePaint.strokeWidth = 1.2f
                canvasToWrite.drawLine(40f, yPos + 8f, 555f, yPos + 8f, linePaint)
            }

            drawPageHeader(pageNumber, canvas)

            canvas.drawRoundRect(40f, 110f, 555f, 165f, 12f, 12f, boxPaint)
            linePaint.color = Color.rgb(202, 196, 208)
            linePaint.strokeWidth = 1f
            linePaint.style = Paint.Style.STROKE
            canvas.drawRoundRect(40f, 110f, 555f, 165f, 12f, 12f, linePaint)
            linePaint.style = Paint.Style.FILL

            boldPaint.textSize = 9.5f
            canvas.drawText("SÚHRNNÉ INFORMÁCIE PRE ÚČTOVNÍKA", 55f, 128f, boldPaint)
            boldPaint.textSize = 9f

            canvas.drawText("Počet dokladov: ${summary.totalDocCount}", 55f, 143f, textPaint)
            canvas.drawText("Obdobie: ${summary.periodLabel}", 55f, 155f, textPaint)

            canvas.drawText(
                "Suma celkom (s DPH): ${summary.formattedTotalInvoiced}",
                295f,
                143f,
                boldPaint
            )
            canvas.drawText(
                "DPH celkom: ${summary.formattedTotalVat}",
                295f,
                155f,
                textPaint
            )

            var y = 195f
            drawTableHeader(y, canvas)
            y = 220f

            rows.forEachIndexed { index, row ->
                if (y > 780f) {
                    pdfDocument.finishPage(page)
                    pageNumber++
                    pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                    page = pdfDocument.startPage(pageInfo)
                    canvas = page.canvas

                    drawPageHeader(pageNumber, canvas)
                    y = 115f
                    drawTableHeader(y, canvas)
                    y = 140f
                }

                if (index % 2 == 1) {
                    canvas.drawRect(40f, y - 11f, 555f, y + 14f, rowBgPaint)
                }

                canvas.drawText(row.dateText, 40f, y, textPaint)
                canvas.drawText(row.supplierText, 110f, y, textPaint)
                canvas.drawText(row.icoDicText, 240f, y, textPaint)
                canvas.drawText(row.categoryText, 345f, y, textPaint)
                canvas.drawText(row.vatText, 455f, y, textPaint)
                canvas.drawText(row.amountText, 555f, y, rightAlignPaint)

                canvas.drawLine(40f, y + 14f, 555f, y + 14f, thinLinePaint)
                y += 25f
            }

            if (y > 760f) {
                pdfDocument.finishPage(page)
                pageNumber++
                pageInfo = PdfDocument.PageInfo.Builder(595, 842, pageNumber).create()
                page = pdfDocument.startPage(pageInfo)
                canvas = page.canvas

                drawPageHeader(pageNumber, canvas)
                y = 110f
            }

            linePaint.color = Color.rgb(29, 27, 32)
            linePaint.strokeWidth = 1.5f
            canvas.drawLine(40f, y, 555f, y, linePaint)

            y += 18f
            boldPaint.textSize = 10f
            rightAlignBoldPaint.textSize = 10f

            canvas.drawText("SPOLU CELKOM (€):", 335f, y, boldPaint)
            canvas.drawText(
                if (summary.totalVatCents > 0L) summary.formattedTotalVat else "-",
                455f,
                y,
                boldPaint
            )
            canvas.drawText(
                summary.formattedTotalInvoiced,
                555f,
                y,
                rightAlignBoldPaint
            )

            canvas.drawLine(40f, y + 6f, 555f, y + 6f, thinLinePaint)
            canvas.drawLine(40f, y + 8.5f, 555f, y + 8.5f, thinLinePaint)

            pdfDocument.finishPage(page)

            FileOutputStream(pdfFile).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
        } finally {
            pdfDocument.close()
        }
    }

    internal fun cleanOldExportPdfs(cacheDir: File) {
        cacheDir.listFiles()?.forEach { file ->
            if (file.name.startsWith(PDF_PREFIX) && file.name.endsWith(".pdf")) {
                file.delete()
            }
        }
    }

    private fun sharePdfFile(context: Context, file: File, periodLabel: String) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, file)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Prehľad dokladov pre účtovníka ($periodLabel)")
            putExtra(
                Intent.EXTRA_TEXT,
                "Dobrý deň,\nv prílohe Vám zasielam prehľad výdavkov a prijatých faktúr za obdobie: $periodLabel."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Zdieľať prehľad ($periodLabel)")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }
}
