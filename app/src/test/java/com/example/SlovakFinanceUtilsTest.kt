package com.example

import com.example.data.SlovakFinanceUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class SlovakFinanceUtilsTest {

    @Test
    fun normalizeIsoDate_rejectsTrailingTextImpossibleLeapDayAndOutOfRangeYears() {
        listOf("2026-11-18 extra", "18.11.2026x", "2026/11/18/19", "29.02.2025", "1989-12-31", "2101-01-01")
            .forEach { assertNull("Must reject the entire invalid input: $it", SlovakFinanceUtils.normalizeIsoDate(it)) }
        assertEquals("2024-02-29", SlovakFinanceUtils.normalizeIsoDate(" 29.02.2024 "))
    }

    @Test
    fun centConversion_roundsHalfCentsAndPreservesNegativeAdjustments() {
        assertEquals(101L, SlovakFinanceUtils.bigDecimalToCents(BigDecimal("1.005")))
        assertEquals(-101L, SlovakFinanceUtils.bigDecimalToCents(BigDecimal("-1.005")))
        assertEquals("-1,01", SlovakFinanceUtils.formatCentsForInput(-101L))
        assertEquals(0L, SlovakFinanceUtils.doubleToCents(Double.NaN))
        assertEquals(0L, SlovakFinanceUtils.doubleToCents(Double.POSITIVE_INFINITY))
    }

    @Test
    fun parseSlovakDecimal_handlesCommaDotThousandsAndCurrency() {
        assertEquals(BigDecimal("1234.56"), SlovakFinanceUtils.parseSlovakDecimal("1 234,56 €"))
        assertEquals(BigDecimal("1234.56"), SlovakFinanceUtils.parseSlovakDecimal("1.234,56 EUR"))
        assertEquals(BigDecimal("1234.56"), SlovakFinanceUtils.parseSlovakDecimal("1,234.56"))
        assertEquals(BigDecimal("42.5"), SlovakFinanceUtils.parseSlovakDecimal("42,5"))
        assertNull(SlovakFinanceUtils.parseSlovakDecimal(""))
        assertNull(SlovakFinanceUtils.parseSlovakDecimal("abc"))
    }

    @Test
    fun calculateVatCentsFromGross_usesHalfUpDecimalMath() {
        // 123.00 EUR at 23% VAT -> 123 * 23 / 123 = 23.00 EUR = 2300 cents
        assertEquals(2300L, SlovakFinanceUtils.calculateVatCentsFromGross(12300L, BigDecimal("23")))
        // 10.00 EUR at 20% VAT -> 10 * 20 / 120 = 1.6666... -> 1.67 EUR = 167 cents
        assertEquals(167L, SlovakFinanceUtils.calculateVatCentsFromGross(1000L, BigDecimal("20")))
        assertEquals(0L, SlovakFinanceUtils.calculateVatCentsFromGross(1000L, BigDecimal.ZERO))
    }

    @Test
    fun normalizeIsoDate_andExtractYearMonth_supportSlovakAndIsoFormats() {
        assertEquals("2026-11-16", SlovakFinanceUtils.normalizeIsoDate("16.11.2026"))
        assertEquals("2026-03-05", SlovakFinanceUtils.normalizeIsoDate("5.3.2026"))
        assertEquals("2026-11-16", SlovakFinanceUtils.normalizeIsoDate("2026-11-16"))
        assertEquals(2026 to 11, SlovakFinanceUtils.extractYearMonth("16.11.2026"))
        assertNull(SlovakFinanceUtils.normalizeIsoDate("31.02.2026"))
        assertNull(SlovakFinanceUtils.normalizeIsoDate("1800-01-01"))
    }

    @Test
    fun calendarDaysBetweenUtc_calculatesExactCalendarDays() {
        val start = SlovakFinanceUtils.parseDateUtcMidnightMillis("2026-03-28")!!
        val end = SlovakFinanceUtils.parseDateUtcMidnightMillis("2026-03-30")!!
        assertEquals(2, SlovakFinanceUtils.calendarDaysBetweenUtc(start, end))
        assertEquals(-2, SlovakFinanceUtils.calendarDaysBetweenUtc(end, start))
    }
}
