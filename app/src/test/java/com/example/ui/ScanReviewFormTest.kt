package com.example.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
