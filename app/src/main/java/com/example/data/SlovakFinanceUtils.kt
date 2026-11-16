package com.example.data

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object SlovakFinanceUtils {
    private val slovakLocale: Locale = Locale.forLanguageTag("sk-SK")
    private val utcZone: TimeZone = TimeZone.getTimeZone("UTC")
    private val acceptedDateFormats = listOf(
        "yyyy-MM-dd",
        "dd.MM.yyyy",
        "d.M.yyyy",
        "yyyy/MM/dd"
    )

    fun parseSlovakDecimal(raw: String): BigDecimal? {
        val cleaned = raw
            .replace("\u00A0", "")
            .replace(" ", "")
            .replace("€", "")
            .replace("EUR", "", ignoreCase = true)
            .trim()
        if (cleaned.isEmpty()) return null

        val lastComma = cleaned.lastIndexOf(',')
        val lastDot = cleaned.lastIndexOf('.')
        val normalized = when {
            lastComma >= 0 && lastDot >= 0 -> {
                if (lastComma > lastDot) {
                    cleaned.replace(".", "").replace(',', '.')
                } else {
                    cleaned.replace(",", "")
                }
            }
            lastComma >= 0 -> cleaned.replace(',', '.')
            else -> cleaned
        }

        return try {
            BigDecimal(normalized)
        } catch (_: NumberFormatException) {
            null
        }
    }

    fun bigDecimalToCents(amount: BigDecimal): Long =
        amount.movePointRight(2).setScale(0, RoundingMode.HALF_UP).longValueExact()

    fun doubleToCents(amount: Double): Long {
        if (!amount.isFinite()) return 0L
        return BigDecimal.valueOf(amount)
            .movePointRight(2)
            .setScale(0, RoundingMode.HALF_UP)
            .longValueExact()
    }

    fun centsToDouble(cents: Long): Double =
        BigDecimal.valueOf(cents, 2).toDouble()

    fun calculateVatCentsFromGross(grossCents: Long, vatRatePercent: BigDecimal): Long {
        if (grossCents <= 0L || vatRatePercent <= BigDecimal.ZERO) return 0L
        val gross = BigDecimal.valueOf(grossCents, 2)
        val divisor = BigDecimal(100).add(vatRatePercent)
        val vat = gross.multiply(vatRatePercent).divide(divisor, 2, RoundingMode.HALF_UP)
        return bigDecimalToCents(vat)
    }

    fun formatCentsForInput(cents: Long): String {
        val symbols = DecimalFormatSymbols(slovakLocale).apply {
            decimalSeparator = ','
        }
        val df = DecimalFormat("0.00", symbols).apply {
            isGroupingUsed = false
        }
        return df.format(BigDecimal.valueOf(cents, 2))
    }

    fun formatMoneyCents(cents: Long, currency: String = "EUR"): String {
        val symbols = DecimalFormatSymbols(slovakLocale).apply {
            decimalSeparator = ','
            groupingSeparator = ' '
        }
        val df = DecimalFormat("#,##0.00", symbols)
        val suffix = if (currency.equals("EUR", ignoreCase = true) || currency.isBlank()) "€" else currency.uppercase(Locale.ROOT)
        return "${df.format(BigDecimal.valueOf(cents, 2))} $suffix"
    }

    fun normalizeIsoDate(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in acceptedDateFormats) {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                isLenient = false
                timeZone = utcZone
            }
            val parsed: Date = try {
                parser.parse(trimmed)
            } catch (_: Exception) {
                null
            } ?: continue

            val outFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
                timeZone = utcZone
            }
            val formatted = outFmt.format(parsed)
            val year = formatted.substring(0, 4).toIntOrNull() ?: continue
            if (year in 1990..2100) {
                return formatted
            }
        }
        return null
    }

    fun extractYearMonth(rawDate: String): Pair<Int, Int>? {
        val iso = normalizeIsoDate(rawDate) ?: return null
        val year = iso.substring(0, 4).toIntOrNull() ?: return null
        val month = iso.substring(5, 7).toIntOrNull() ?: return null
        return year to month
    }

    fun parseDateUtcMidnightMillis(rawDate: String): Long? {
        val iso = normalizeIsoDate(rawDate) ?: return null
        val parser = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            isLenient = false
            timeZone = utcZone
        }
        return try {
            parser.parse(iso)?.time
        } catch (_: Exception) {
            null
        }
    }

    fun todayUtcMidnightMillis(): Long {
        val localCal = Calendar.getInstance()
        val utcCal = Calendar.getInstance(utcZone).apply {
            clear()
            set(
                localCal.get(Calendar.YEAR),
                localCal.get(Calendar.MONTH),
                localCal.get(Calendar.DAY_OF_MONTH),
                0,
                0,
                0
            )
            set(Calendar.MILLISECOND, 0)
        }
        return utcCal.timeInMillis
    }

    fun calendarDaysBetweenUtc(startUtcMidnightMillis: Long, endUtcMidnightMillis: Long): Int {
        val diff = endUtcMidnightMillis - startUtcMidnightMillis
        return (diff / 86_400_000L).toInt()
    }
}
