package com.example.data

import org.junit.Assert.*
import org.junit.Test

class LocalReceiptParserTest {
    private fun success(text: String) = (LocalReceiptParser.parse(text.trimIndent()) as AiExtractionResult.Success).data
    private fun receipt(total: String) = "Syntetický obchod s.r.o.\nDOKLAD\nDátum vystavenia 18.11.2026\n$total"

    @Test fun slovakReceipt_usesExplicitTotalWithoutInventingTax() {
        val d = success("""
            Syntetický obchod s.r.o.
            FAKTÚRA
            IČO: 12345678
            DIČ: 1234567890
            Dátum vystavenia: 18.11.2026
            Základ bez DPH 100,00
            DPH 23,00
            CELKOM K ÚHRADE: 123,00 EUR
        """)
        assertEquals("Syntetický obchod s.r.o.", d.supplierName)
        assertEquals("12345678", d.ico); assertEquals("1234567890", d.dic)
        assertEquals("2026-11-18", d.issueDate); assertEquals(123.0, d.totalAmount, 0.001)
        assertNull(d.vatAmount); assertNull(d.vatRate); assertTrue(d.reviewWarnings.isNotEmpty())
    }
    @Test fun amounts_supportGroupingAndExplicitIntegerEuros() {
        for (s in listOf("1 234,56", "1\u00a0234,56", "1\u202f234,56", "1.234,56", "1,234.56", "1234.56")) {
            assertEquals(s, 1234.56, success(receipt("Celkom $s EUR")).totalAmount, 0.001)
        }
        assertEquals(12.0, success(receipt("Celkom 12 EUR")).totalAmount, 0.001)
        assertEquals(1234.56, success(receipt("CELKOM\n1 234,56 EUR")).totalAmount, 0.001)
    }
    @Test fun onlyAmountOnFollowingLine_isAccepted() {
        assertEquals(0.0, success(receipt("CELKOM\nPapier 12,30 EUR")).totalAmount, 0.001)
        assertEquals(0.0, success(receipt("CELKOM\nDPH 12,30 EUR")).totalAmount, 0.001)
    }
    @Test fun pricesPaymentsTaxAndSubtotal_areNotGrandTotal() {
        val d = success(receipt("Papier 99,90\nMedzisúčet 50,00\nDPH CELKOM 10,00\nCelkom 60,00\nHotovosť 100,00\nVýdavok 40,00"))
        assertEquals(60.0, d.totalAmount, 0.001)
        assertEquals(0.0, success(receipt("Papier 99,90\nDPH SPOLU 19,90")).totalAmount, 0.001)
    }
    @Test fun amountDue_hasPriorityOverLowerTotals() {
        assertEquals(12.3, success(receipt("Spolu 99,00\nCelkom 80,00\nK úhrade 12,30")).totalAmount, 0.001)
    }
    @Test fun conflictingTotals_requireReviewButRepeatedSameTotalIsFine() {
        assertEquals(0.0, success(receipt("Celkom 12,30\nCelkom 15,30")).totalAmount, 0.001)
        assertEquals(12.3, success(receipt("Celkom 12,30\nCelkom 12,30")).totalAmount, 0.001)
        assertEquals(0.0, success(receipt("Celkom 12,30 15,30")).totalAmount, 0.001)
        assertEquals(0.0, success(receipt("Celkom 12,30\nK úhrade\nĎakujeme za nákup")).totalAmount, 0.001)
    }
    @Test fun malformedDecimals_areNotPartiallyParsed() {
        for (s in listOf("1,23,45", "123,456", "1.234,567", "12..30")) {
            assertEquals(s, 0.0, success(receipt("Celkom $s EUR")).totalAmount, 0.001)
        }
    }
    @Test fun nonPositiveAndOverflowingTotals_areRejectedWithoutDroppingTheSign() {
        for (s in listOf("-12,30", "−12,30", "- 12,30", "–12,30", "—12,30", "(12,30)", "0,00", "1 000 000,01")) {
            assertTrue(s, LocalReceiptParser.parse(receipt("Celkom $s")) is AiExtractionResult.InvalidDocument)
        }
    }
    @Test fun foreignCurrencies_areNeverSilentlySavedAsEuro() {
        for (s in listOf("CZK", "Kč", "USD", "GBP", "CHF", "PLN", "HUF", "SKK", "$", "£", "¥", "UAH", "₴")) {
            assertTrue(s, LocalReceiptParser.parse(receipt("Celkom 12,30 $s")) is AiExtractionResult.InvalidDocument)
        }
    }
    @Test fun missingData_staysMissing() {
        val d = success("Syntetický obchod\nFAKTÚRA\nPapier 15,20 EUR\nIČO: 12345678")
        assertEquals(0.0, d.totalAmount, 0.001); assertEquals("", d.issueDate)
    }
    @Test fun issueDate_hasPriorityOverDueDeliveryAndPlainDates() {
        val d = success("Syntetický obchod\nFAKTÚRA\nSplatnosť 30.11.2026\nDátum dodania 17.11.2026\n01.11.2026\nDátum vystavenia 18.11.2026\nCelkom 12,30")
        assertEquals("2026-11-18", d.issueDate)
        assertEquals("", success("Syntetický obchod\nDOKLAD\nSplatnosť 30.11.2026\nCelkom 12,30").issueDate)
        assertEquals("2026-11-18", success("Syntetický obchod\nDOKLAD\nSplatnosť\n30.11.2026\nDátum vystavenia\n18.11.2026\nCelkom 12,30").issueDate)
    }
    @Test fun conflictingAndImpossibleDates_requireReview() {
        assertEquals("", success(receipt("Celkom 12,30\nDátum vystavenia 19.11.2026")).issueDate)
        assertEquals("", success("Syntetický obchod\nDOKLAD\n31.02.2026\nCelkom 12,30").issueDate)
    }
    @Test fun dateFormats_areNormalized() {
        for (s in listOf("18.11.2026", "18/11/2026", "18-11-2026", "2026-11-18", "2026/11/18")) {
            assertEquals(s, "2026-11-18", success("Syntetický obchod\nDOKLAD\nDátum vystavenia $s\nCelkom 12,30").issueDate)
        }
    }
    @Test fun supplierSection_doesNotUseCustomerIdentifiers() {
        val d = success("FAKTÚRA\nOdberateľ: Kupujúci\nIČO: 87654321\nDodávateľ: Syntetický obchod\nIČO: 12345678\nIČ DPH: SK1234567890\nCelkom 12,30")
        assertEquals("Syntetický obchod", d.supplierName); assertEquals("12345678", d.ico); assertEquals("1234567890", d.dic)
        val missing = success("FAKTÚRA\nOdberateľ: Kupujúci\nIČO: 87654321\nCelkom 12,30")
        assertEquals("", missing.supplierName); assertNull(missing.ico)
    }
    @Test fun identifierLengthsAndConflicts_areConservative() {
        assertNull(success(receipt("Celkom 12,30\nIČO: 123456789")).ico)
        assertNull(success(receipt("Celkom 12,30\nDIČ: 12345678901")).dic)
        assertNull(success(receipt("Celkom 12,30\nIČO: 12345678\nIČO: 87654321")).ico)
        assertEquals("12345678", success(receipt("Celkom 12,30\nIČO: 12 345 678")).ico)
    }
    @Test fun emptyUnrelatedAndExcessiveInput_isRejected() {
        for (s in listOf("", "Letný výlet\nNádherná krajina", "FAKTÚRA\n" + "x".repeat(200_001), "FAKTÚRA\n" + "x\n".repeat(3001))) {
            assertTrue(LocalReceiptParser.parse(s) is AiExtractionResult.InvalidDocument)
        }
    }
}
