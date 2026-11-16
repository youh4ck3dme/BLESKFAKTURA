package com.example.data

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/** Conservative OCR suggestions. Missing data remains empty for explicit user review. */
internal object LocalReceiptParser {
    private val amountPattern = Regex("""(?<![\d.,])(?:\d{1,3}(?:[ \u00A0]\d{3})+|\d+)[,.]\d{2}(?!\d)""")
    private val datePattern = Regex("""\b(?:\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}\.\s?\d{1,2}\.\s?\d{4})\b""")
    private val receiptMarker = Regex("""\b(ICO|DIC|DPH|SPOLU|CELKOM|FAKTURA|DOKLAD|UCTENKA|TOTAL|UHRAD[AEU])\b""")

    fun parse(text: String): AiExtractionResult {
        val lines = text.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.size < 2 || !receiptMarker.containsMatchIn(normalized(text))) {
            return AiExtractionResult.InvalidDocument("Na obrázku sa nepodarilo rozpoznať účtovný doklad. Skúste ostrejšiu fotografiu celej faktúry alebo bločku.")
        }
        val supplier = lines.firstOrNull { line ->
            val value = normalized(line)
            line.count(Char::isLetter) >= 3 &&
                !receiptMarker.containsMatchIn(value) &&
                !value.contains("DATUM") && !datePattern.containsMatchIn(line) &&
                !amountPattern.containsMatchIn(line) && !value.startsWith("TEL") &&
                !value.startsWith("WWW") && !value.contains("@")
        }.orEmpty().take(200)

        val totalCandidates = lines.mapIndexedNotNull { index, line ->
            val value = normalized(line)
            val priority = when {
                value.contains("K UHRADE") || value.contains("NA UHRADU") -> 4
                value.contains("CELKOM") || value.contains("CELKOVA SUMA") -> 3
                value.contains("SPOLU") && !value.contains("DPH") -> 2
                value.contains("TOTAL") -> 1
                else -> return@mapIndexedNotNull null
            }
            if (value.contains("BEZ DPH") || value.contains("ZAKLAD")) return@mapIndexedNotNull null
            val sameLine = amountPattern.findAll(line).lastOrNull()?.value
            val nextLine = lines.getOrNull(index + 1)?.let { amountPattern.findAll(it).lastOrNull()?.value }
            val amount = (sameLine ?: nextLine)?.let(SlovakFinanceUtils::parseSlovakDecimal)
                ?: return@mapIndexedNotNull null
            if (amount <= BigDecimal.ZERO || amount > BigDecimal(1_000_000)) return@mapIndexedNotNull null
            priority to amount
        }
        val total = totalCandidates.maxByOrNull { it.first }?.second?.toDouble() ?: 0.0
        val issueDate = lines.asSequence()
            .filterNot { normalized(it).contains("SPLATNOST") }
            .flatMap { datePattern.findAll(it).map { match -> match.value.replace(" ", "") } }
            .mapNotNull(SlovakFinanceUtils::normalizeIsoDate)
            .firstOrNull().orEmpty()
        val ico = findIdentifier(lines, Regex("""\bICO\s*:?\s*(\d[\d ]{6,12})"""), 8)
        val dic = findIdentifier(lines, Regex("""\b(?:DIC|IC DPH)\s*:?\s*(?:SK\s*)?(\d[\d ]{8,16})"""), 10)
        return AiExtractionResult.Success(
            ExtractedInvoiceJson(
                supplierName = supplier,
                ico = ico,
                dic = dic,
                issueDate = issueDate,
                totalAmount = total,
                // Tax cannot safely be inferred from a single OCR total or rate table.
                vatAmount = null,
                vatRate = null,
                category = "Iné",
                description = null
            )
        )
    }

    private fun findIdentifier(lines: List<String>, pattern: Regex, length: Int): String? =
        lines.firstNotNullOfOrNull { line ->
            pattern.find(normalized(line))?.groupValues?.get(1)
                ?.filter(Char::isDigit)?.takeIf { it.length == length }
        }

    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("""\p{M}+"""), "")
        .uppercase(Locale.ROOT)
}
