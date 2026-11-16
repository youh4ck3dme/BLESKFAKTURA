package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalReceiptParserTest {
    @Test
    fun slovakReceipt_extractsExplicitIdentifiersDateAndGrandTotal_withoutInventingTax() {
        val result = LocalReceiptParser.parse("""
            Testovací obchod, s.r.o.
            FAKTÚRA
            IČO: 12345678
            DIČ: 1234567890
            Dátum vystavenia: 16.11.2026
            Základ bez DPH 100,00
            DPH 23,00
            CELKOM K ÚHRADE: 123,00 EUR
        """.trimIndent()) as AiExtractionResult.Success
        assertEquals("Testovací obchod, s.r.o.", result.data.supplierName)
        assertEquals("12345678", result.data.ico)
        assertEquals("1234567890", result.data.dic)
        assertEquals("2026-11-16", result.data.issueDate)
        assertEquals(123.0, result.data.totalAmount, 0.001)
        assertNull(result.data.vatAmount)
        assertNull(result.data.vatRate)
    }

    @Test
    fun totalOnFollowingLine_andThousandsSeparator_areRecognized() {
        val result = LocalReceiptParser.parse("""
            Test obchod
            DOKLAD
            2026-11-16
            CELKOM
            1 234,56 EUR
        """.trimIndent()) as AiExtractionResult.Success
        assertEquals(1234.56, result.data.totalAmount, 0.001)
    }

    @Test
    fun missingTotalAndDate_stayEmptyForMandatoryReview_insteadOfUsingItemPriceOrToday() {
        val result = LocalReceiptParser.parse("""
            Test obchod
            FAKTÚRA
            Papier 15,20 EUR
            IČO: 12345678
        """.trimIndent()) as AiExtractionResult.Success
        assertEquals(0.0, result.data.totalAmount, 0.001)
        assertEquals("", result.data.issueDate)
    }

    @Test
    fun dueDate_isNotMistakenForIssueDate() {
        val result = LocalReceiptParser.parse("""
            Test obchod
            FAKTÚRA
            Dátum splatnosti 30.11.2026
            Dátum vystavenia 16.11.2026
            CELKOM 99,90 EUR
        """.trimIndent()) as AiExtractionResult.Success
        assertEquals("2026-11-16", result.data.issueDate)
    }

    @Test
    fun negativeTotal_isNeverConvertedToAPositiveExpense() {
        for (signed in listOf("-12,30", "−12,30", "- 12,30", "–12,30")) {
            val result = LocalReceiptParser.parse("Obchod s.r.o.\nDOKLAD\nCelkom $signed\n16.11.2026")
            assertTrue("Negative total was accepted: $signed -> $result", result is AiExtractionResult.InvalidDocument)
        }
    }

    @Test
    fun unrelatedOrEmptyText_isRejected() {
        assertTrue(LocalReceiptParser.parse("") is AiExtractionResult.InvalidDocument)
        assertTrue(LocalReceiptParser.parse("Letný výlet\nNádherná krajina") is AiExtractionResult.InvalidDocument)
    }
}
