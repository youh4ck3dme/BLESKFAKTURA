package com.example.data

import java.math.BigDecimal
import java.text.Normalizer
import java.util.Locale

/** Conservative EUR receipt suggestions. Ambiguous values require explicit user review. */
internal object LocalReceiptParser {
    private val money = Regex("""(?<![\d.,+\-−–—])(?:[+\-−–—]\s*)?(?:\d{1,3}(?:[ .,]\d{3})+|\d+)[,.]\d{2}(?![\d.,])""")
    private val integerEuro = Regex("""(?<![\d.,+\-−–—])(?:[+\-−–—]\s*)?\d+(?: \d{3})*\s*(?:EUR\b|€)""", RegexOption.IGNORE_CASE)
    private val date = Regex("""\b(?:\d{4}[-/]\d{1,2}[-/]\d{1,2}|\d{1,2}[./-]\s?\d{1,2}[./-]\s?\d{4})\b""")
    private val marker = Regex("""\b(ICO|DIC|DPH|SPOLU|CELKOM|FAKTURA|DOKLAD|UCTENKA|TOTAL|INVOICE|RECEIPT|UHRAD[AEU])\b""")
    private val customer = Regex("""\b(ODBERATEL|ZAKAZNIK|CUSTOMER|BILL TO)\b""")
    private val supplierLabel = Regex("""\b(DODAVATEL|PREDAJCA|SUPPLIER)\s*:?\s*""")
    private val foreignCurrency = Regex("""(?:\b(CZK|USD|GBP|CHF|PLN|HUF|SKK|SEK|NOK|DKK|RON|BGN|JPY|CNY|UAH|RUB|AUD|CAD|K[CČ])\b|[$£¥₴₽])""", RegexOption.IGNORE_CASE)
    private val nonTotal = Regex("""\b(BEZ DPH|ZAKLAD|SUBTOTAL|MEDZISUCET|VYDAVOK|VRATENE|ZAPLATENE|UHRADENE|HOTOVOST|KARTOU|CELKOM DPH|DPH CELKOM)\b""")
    private data class Candidate(val priority: Int, val amounts: List<BigDecimal>)

    fun parse(text: String): AiExtractionResult {
        if (text.length > 200_000) return invalid("Doklad obsahuje priveľa textu. Vyberte jeden doklad a skúste to znova.")
        val lines = text.replace('\u00a0', ' ').replace('\u202f', ' ')
            .lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
        if (lines.size > 3000 || lines.any { it.length > 2000 }) return invalid("Text dokladu sa nepodarilo bezpečne spracovať.")
        if (lines.size < 2 || !marker.containsMatchIn(normalized(text))) {
            return invalid("Na obrázku sa nepodarilo rozpoznať účtovný doklad. Skúste ostrejšiu fotografiu celého dokladu.")
        }
        if (lines.any { foreignCurrency.containsMatchIn(normalized(it)) }) {
            return invalid("Lokálne čítanie podporuje doklady v EUR. Doklad s inou menou skontrolujte a zadajte ručne.")
        }
        val warnings = mutableListOf("Údaje z OCR pred uložením skontrolujte podľa fotografie.")
        val supplierLines = supplierSection(lines)
        val explicitSupplier = supplierLines.indices.firstNotNullOfOrNull { i ->
            val label = supplierLabel.find(normalized(supplierLines[i])) ?: return@firstNotNullOfOrNull null
            val inline = supplierLines[i].substring(label.range.last + 1).trim().trimStart(':').trim()
            listOfNotNull(inline.takeIf(::plausibleSupplier), supplierLines.getOrNull(i + 1)?.takeIf(::plausibleSupplier)).firstOrNull()
        }
        val supplier = (explicitSupplier ?: supplierLines.firstOrNull(::plausibleSupplier)).orEmpty().take(200)
        if (supplier.isBlank()) warnings += "Dodávateľ nebol spoľahlivo rozpoznaný."

        val candidates = lines.mapIndexedNotNull { i, line ->
            val value = normalized(line)
            if (nonTotal.containsMatchIn(value) || (value.contains("SPOLU DPH") || value.contains("DPH SPOLU"))) return@mapIndexedNotNull null
            val priority = when {
                Regex("""\b(K UHRADE|NA UHRADU|AMOUNT DUE|TOTAL DUE|NA ZAPLATENIE)\b""").containsMatchIn(value) -> 4
                Regex("""\b(CELKOM|CELKOVA SUMA|GRAND TOTAL)\b""").containsMatchIn(value) -> 3
                Regex("""\bSPOLU\b""").containsMatchIn(value) -> 2
                Regex("""\bTOTAL\b""").containsMatchIn(value) -> 1
                else -> return@mapIndexedNotNull null
            }
            val amounts = amounts(line).ifEmpty {
                val next = lines.getOrNull(i + 1).orEmpty()
                // Never borrow a price from the next item, tax row, date or identifier.
                if (next.replace(money, "").replace(integerEuro, "").replace(Regex("(?i)EUR|€|[ :\\t]"), "").isEmpty()) amounts(next) else emptyList()
            }
            Candidate(priority, amounts)
        }
        val top = candidates.maxOfOrNull { it.priority }
        val best = candidates.filter { it.priority == top }
        val values = best.flatMap { it.amounts }.distinctBy { it.stripTrailingZeros() }
        if (values.any { it <= BigDecimal.ZERO }) return invalid("Doklady so zápornou alebo nulovou celkovou sumou zatiaľ nie sú podporované.")
        if (values.any { it > BigDecimal(1_000_000) }) return invalid("Celková suma je mimo podporovaného rozsahu. Skontrolujte doklad.")
        val total = if (best.isNotEmpty() && best.all { it.amounts.size == 1 } && values.size == 1) values.single().toDouble() else 0.0
        if (total == 0.0) warnings += "Celková suma chýba alebo je nejednoznačná. Doplňte ju podľa dokladu."
        val issue = issueDate(lines)
        if (issue.isEmpty()) warnings += "Dátum vystavenia chýba alebo je nejednoznačný."
        warnings += "DPH nebola automaticky určená. Ak je uvedená na doklade, doplňte ju."
        return AiExtractionResult.Success(ExtractedInvoiceJson(
            supplierName = supplier, ico = identifier(supplierLines, "ICO", 8),
            dic = identifier(supplierLines, "(?:DIC|IC\\s*DPH)", 10), issueDate = issue,
            totalAmount = total, vatAmount = null, vatRate = null, category = "Iné", reviewWarnings = warnings
        ))
    }

    private fun amounts(line: String): List<BigDecimal> {
        val matches = money.findAll(line).toList().ifEmpty { integerEuro.findAll(line).toList() }
        return matches.mapNotNull { match ->
            val raw = match.value.replace(Regex("[−–—]"), "-")
            val value = SlovakFinanceUtils.parseSlovakDecimal(raw) ?: return@mapNotNull null
            val parenthesized = line.take(match.range.first).trimEnd().endsWith("(") && line.drop(match.range.last + 1).trimStart().startsWith(")")
            if (parenthesized) value.negate() else value
        }
    }

    private fun supplierSection(lines: List<String>): List<String> {
        val start = lines.indexOfFirst { supplierLabel.containsMatchIn(normalized(it)) }.coerceAtLeast(0)
        val searchStart = if (supplierLabel.containsMatchIn(normalized(lines[start]))) start + 1 else start
        val end = (searchStart until lines.size).firstOrNull { customer.containsMatchIn(normalized(lines[it])) } ?: lines.size
        return lines.subList(start, end)
    }

    private fun plausibleSupplier(line: String): Boolean {
        val n = normalized(line)
        return line.count(Char::isLetter) >= 3 && !marker.containsMatchIn(n) &&
            !supplierLabel.containsMatchIn(n) && !customer.containsMatchIn(n) &&
            !n.contains("DATUM") && !date.containsMatchIn(line) && !money.containsMatchIn(line) &&
            !n.startsWith("TEL") && !n.startsWith("WWW") && !n.contains("@")
    }

    private fun issueDate(lines: List<String>): String {
        val candidates = lines.flatMapIndexed { index, line ->
            val n = normalized(if (date.containsMatchIn(line) && line.none(Char::isLetter)) lines.getOrNull(index - 1).orEmpty() + " " + line else line)
            if (Regex("""\b(SPLATNOST|SPLATNOSTI|DUE|DODANIA|DELIVERY)\b""").containsMatchIn(n)) return@flatMapIndexed emptyList()
            val priority = if (Regex("""\b(VYSTAVENIA|VYSTAVENE|VYHOTOVENIA|ISSUE DATE)\b""").containsMatchIn(n)) 3 else if (n.contains("DATUM")) 2 else 1
            date.findAll(line).mapNotNull { match ->
                val raw = match.value.replace(" ", "")
                val normalizedDate = if (raw.matches(Regex("\\d{4}/.*"))) raw.replace('/', '-') else if (!raw.matches(Regex("\\d{4}-.*"))) raw.replace('-', '.').replace('/', '.') else raw
                SlovakFinanceUtils.normalizeIsoDate(normalizedDate)?.let { priority to it }
            }.toList()
        }
        val best = candidates.maxOfOrNull { it.first }
        return candidates.filter { it.first == best }.map { it.second }.distinct().singleOrNull().orEmpty()
    }

    private fun identifier(lines: List<String>, label: String, length: Int): String? {
        val regex = Regex("\\b$label\\s*:?\\s*(?:SK\\s*)?(\\d(?:[ \\t]*\\d){${length - 1}})\\b(?![ \\t]*\\d)")
        return lines.flatMap { regex.findAll(normalized(it)).map { m -> m.groupValues[1].filter(Char::isDigit) }.toList() }.distinct().singleOrNull()
    }
    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").uppercase(Locale.ROOT)
    private fun invalid(message: String) = AiExtractionResult.InvalidDocument(message)
}
