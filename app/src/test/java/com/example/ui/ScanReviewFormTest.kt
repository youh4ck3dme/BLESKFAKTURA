package com.example.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.semantics.SemanticsActions
import com.example.data.ExtractedInvoiceJson
import com.example.data.Invoice
import com.example.ui.screens.ReviewScannedInvoiceForm
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], qualifiers = "w411dp-h891dp")
class ScanReviewFormTest {
    @get:Rule
    val composeRule = createComposeRule()

    private fun showReview(attempts: MutableList<Invoice>, saving: Boolean = false) {
        composeRule.setContent {
            MyApplicationTheme {
                ReviewScannedInvoiceForm(
                    extracted = ExtractedInvoiceJson(
                        supplierName = "Synthetic merchant", issueDate = "2026-11-18", totalAmount = 12.30
                    ),
                    bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888),
                    isSaving = saving, onCancel = {}, onConfirm = { attempts += it }
                )
            }
        }
    }

    private fun replace(tag: String, text: String) {
        composeRule.onNodeWithTag("review_scanned_form").performScrollToNode(hasTestTag(tag))
        composeRule.onNodeWithTag(tag).performTextReplacement(text)
        composeRule.waitForIdle()
    }

    private fun confirm() {
        composeRule.onNodeWithTag("review_scanned_form").performScrollToNode(hasTestTag("confirm_scanned_button"))
        composeRule.onNodeWithTag("confirm_scanned_button").performSemanticsAction(SemanticsActions.OnClick) { it() }
    }

    @Test
    fun invalidFields_blockSaveUntilCorrectedAndNormalizeSlovakValues() {
        val attempts = mutableListOf<Invoice>()
        showReview(attempts)
        val invalid = listOf(
            Triple("scanned_supplier_input", " ", "Synthetic merchant"),
            Triple("scanned_amount_input", "-1", "12,30"),
            Triple("scanned_vat_rate_input", "101", "20"),
            Triple("scanned_date_input", "31.02.2026", "18.11.2026"),
            Triple("scanned_due_date_input", "2026-11-18 extra", "30.11.2026")
        )
        for ((tag, bad, good) in invalid) {
            replace(tag, bad)
            confirm()
            composeRule.runOnIdle { assertTrue(attempts.isEmpty()) }
            composeRule.onNodeWithTag("scan_validation_error").assertExists()
            replace(tag, good)
        }
        confirm()
        val diagnostic = composeRule.onRoot().printToString()
        composeRule.runOnIdle {
            assertEquals(diagnostic, 1, attempts.size)
            assertEquals(1230L, attempts.single().totalAmountCents)
            assertEquals("2026-11-18", attempts.single().issueDate)
            assertEquals("2026-11-30", attempts.single().dueDate)
        }
    }

    @Test
    fun oversizedVat_isRejectedBeforeCentConversionAndCanBeCorrected() {
        val attempts = mutableListOf<Invoice>()
        showReview(attempts)
        replace("scanned_vat_amount_input", "1e100")
        confirm()
        composeRule.runOnIdle { assertTrue(attempts.isEmpty()) }
        composeRule.onNodeWithTag("scan_validation_error").assertExists()
        replace("scanned_vat_amount_input", "2,05")
        confirm()
        val diagnostic = composeRule.onRoot().printToString()
        composeRule.runOnIdle {
            assertEquals(diagnostic, 1, attempts.size)
            assertEquals(205L, attempts.single().vatAmountCents)
        }
    }

    @Test
    fun savingForm_disablesConfirmationToPreventDuplicateWrites() {
        val attempts = mutableListOf<Invoice>()
        showReview(attempts, saving = true)
        composeRule.onNodeWithTag("review_scanned_form").performScrollToNode(hasTestTag("confirm_scanned_button"))
        composeRule.onNodeWithTag("confirm_scanned_button").assertIsNotEnabled()
        composeRule.runOnIdle { assertTrue(attempts.isEmpty()) }
    }

    @Test
    fun retryAfterSaveFailure_preservesDocumentIdentityAndReviewedValues() {
        val attempts = mutableListOf<Invoice>()
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        composeRule.setContent {
            MyApplicationTheme {
                ReviewScannedInvoiceForm(
                    extracted = ExtractedInvoiceJson(
                        supplierName = "Synthetic merchant", issueDate = "2026-11-17", totalAmount = 12.30
                    ),
                    bitmap = bitmap,
                    isSaving = false,
                    onCancel = {},
                    // A failed persistence attempt keeps the form on screen.
                    onConfirm = { attempts += it }
                )
            }
        }
        composeRule.onNodeWithTag("review_scanned_form").performScrollToNode(hasTestTag("confirm_scanned_button"))
        composeRule.onNodeWithTag("confirm_scanned_button").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("review_scanned_form").performScrollToNode(hasTestTag("confirm_scanned_button"))
        composeRule.onNodeWithTag("confirm_scanned_button").performClick()
        composeRule.runOnIdle {
            assertEquals(2, attempts.size)
            assertTrue(attempts.first().id.isNotBlank())
            assertEquals(attempts.first().id, attempts.last().id)
            assertEquals("Synthetic merchant", attempts.last().supplierName)
            assertEquals(1230L, attempts.last().totalAmountCents)
        }
    }
}
