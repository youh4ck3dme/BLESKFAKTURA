package com.example.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.semantics.SemanticsProperties
import com.example.data.Invoice
import com.example.ui.screens.InvoiceDetailDialog
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], qualifiers = "w411dp-h891dp")
class InvoiceDetailTaxTest {
    @get:Rule val compose = createComposeRule()
    private fun show(saved: MutableList<Invoice>) {
        compose.setContent { MyApplicationTheme { InvoiceDetailDialog(
            Invoice(id = "synthetic-ocr-id", supplierName = "Synthetic merchant", issueDate = "2026-11-18", totalAmount = 12.3, totalAmountCents = 1230),
            onDismiss = {}, onSave = { saved += it }) } }
    }
    private fun replace(tag: String, value: String) {
        compose.onNodeWithTag(tag).performScrollTo().performTextReplacement(value)
    }
    @Test fun editingOcrInvoice_doesNotInventVatOrChangeIdentity() {
        val saved = mutableListOf<Invoice>(); show(saved)
        compose.onNodeWithTag("edit_vat_rate_input").performScrollTo()
        assertEquals("", compose.onNodeWithTag("edit_vat_rate_input").fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        compose.onNodeWithTag("save_edit_button").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, saved.size); assertEquals("synthetic-ocr-id", saved.single().id)
            assertNull(saved.single().vatRate); assertNull(saved.single().vatAmount); assertNull(saved.single().vatAmountCents)
        }
    }
    @Test fun extremeVat_isRejectedAndCanBeCorrected() {
        val saved = mutableListOf<Invoice>(); show(saved)
        replace("edit_vat_amount_input", "1e100")
        compose.onNodeWithTag("save_edit_button").performScrollTo().performClick()
        compose.onNodeWithTag("detail_validation_error").assertExists()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
        replace("edit_vat_amount_input", "2,05")
        compose.onNodeWithTag("save_edit_button").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(205L, saved.single().vatAmountCents) }
    }
    @Test fun extremeRecalculation_isRejectedBeforeConversion() {
        val saved = mutableListOf<Invoice>(); show(saved)
        replace("edit_amount_input", "1e100"); replace("edit_vat_rate_input", "20")
        compose.onNodeWithTag("recalculate_detail_vat_button").performScrollTo().performClick()
        compose.onNodeWithTag("detail_validation_error").assertExists()
        compose.runOnIdle { assertTrue(saved.isEmpty()) }
    }
}
