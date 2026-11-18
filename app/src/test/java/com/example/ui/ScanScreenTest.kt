package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.AiExtractionResult
import com.example.data.ExtractedInvoiceJson
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceAiExtractor
import com.example.fakes.FakeInvoiceDataGateway
import com.example.ui.screens.ScanScreen
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], qualifiers = "w411dp-h891dp")
class ScanScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildHarness(
        aiExtractor: FakeInvoiceAiExtractor = FakeInvoiceAiExtractor(),
        archiveReceipt: suspend (Bitmap, String) -> String? = { _, id -> "/private/receipts/$id.jpg" }
    ): Triple<InvoiceViewModel, FakeInvoiceDataGateway, FakeInvoiceAiExtractor> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = FakeInvoiceDataGateway(initialCredits = 10)
        val vm = InvoiceViewModel(
            application = app,
            repository = repo,
            playBillingManager = FakeBillingGateway(),
            aiExtractor = aiExtractor,
            archiveReceipt = archiveReceipt
        )
        return Triple(vm, repo, aiExtractor)
    }

    @Test
    fun scanScreen_idleMockSampleStaging_andClearStagedFile() {
        val (vm, _, _) = buildHarness()

        composeTestRule.setContent {
            MyApplicationTheme {
                ScanScreen(
                    viewModel = vm,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scan_screen").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fab_instant_camera_capture").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scan_camera_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("scan_gallery_button").assertIsDisplayed()

        // Stage sample receipt bitmap
        composeTestRule.onNodeWithTag("scan_mock_sample_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("vzorovy_tesco_doklad.png").assertIsDisplayed()
        composeTestRule.onNodeWithText("23.4 KB").assertIsDisplayed()
        composeTestRule.onNodeWithTag("analyze_with_ai_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("change_staged_file_button").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("fab_instant_camera_capture").assertCountEquals(0)

        // Clear staged file -> returns to source selection
        composeTestRule.onNodeWithTag("clear_staged_file_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scan_camera_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fab_instant_camera_capture").assertIsDisplayed()
    }

    @Test
    fun scanScreen_scanningProgress_errorAndRetryFlow() {
        val gate = CompletableDeferred<AiExtractionResult>()
        val (vm, repo, _) = buildHarness(
            aiExtractor = FakeInvoiceAiExtractor { gate.await() }
        )
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            MyApplicationTheme {
                ScanScreen(
                    viewModel = vm,
                    onNavigateBack = {}
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("scan_mock_sample_button").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("analyze_with_ai_button").performClick()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        composeTestRule.mainClock.advanceTimeByFrame()

        assertTrue(vm.scanState.value is ScanUiState.Scanning)
        composeTestRule.onNodeWithText("BLESKOVÁ ANALÝZA DOKLADU").assertIsDisplayed()

        // Fail extraction -> displays refunded error screen
        gate.complete(AiExtractionResult.InvalidDocument("Doklad nie je čitateľný."))
        composeTestRule.mainClock.autoAdvance = true
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            vm.scanState.value is ScanUiState.Error
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Vyskytla sa chyba").assertIsDisplayed()
        composeTestRule.onNodeWithText("Doklad nie je čitateľný. Kredit bol vrátený.").assertIsDisplayed()
        assertEquals(10, repo.currentStatsSnapshot().credits)

        // Retry resets state back to Idle while preserving the staged receipt so user can retry immediately
        composeTestRule.onNodeWithTag("retry_scan_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(ScanUiState.Idle, vm.scanState.value)
        composeTestRule.onNodeWithTag("analyze_with_ai_button").assertIsDisplayed()
    }

    @Test
    fun scanScreen_reviewForm_validatesInputsAndRecalculatesVat() {
        val ai = FakeInvoiceAiExtractor {
            AiExtractionResult.Success(
                ExtractedInvoiceJson(
                    supplierName = "Tesco Stores SR",
                    issueDate = "2026-11-17",
                    totalAmount = 123.0,
                    vatRate = 23.0,
                    vatAmount = 10.0,
                    category = "Kancelária"
                )
            )
        }
        val (vm, _, _) = buildHarness(aiExtractor = ai)

        composeTestRule.setContent {
            MyApplicationTheme {
                ScanScreen(
                    viewModel = vm,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scan_mock_sample_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("analyze_with_ai_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            vm.scanState.value is ScanUiState.Success
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("review_scanned_form").assertIsDisplayed()
        composeTestRule.onNodeWithText("AI extrakcia úspešná").assertIsDisplayed()

        // Blank supplier triggers Slovak validation error
        composeTestRule.onNodeWithTag("review_scanned_form")
            .performScrollToNode(hasTestTag("scanned_supplier_input"))
        composeTestRule.onNodeWithTag("scanned_supplier_input").performTextClearance()

        composeTestRule.onNodeWithTag("review_scanned_form")
            .performScrollToNode(hasTestTag("confirm_scanned_button"))
        composeTestRule.onNodeWithTag("confirm_scanned_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("review_scanned_form")
            .performScrollToNode(hasTestTag("scan_validation_error"))
        composeTestRule.onNodeWithTag("scan_validation_error").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zadajte názov dodávateľa.").assertIsDisplayed()

        // Restore supplier and recalculate 23% VAT from 123,00 EUR gross -> 23,00 EUR VAT
        composeTestRule.onNodeWithTag("review_scanned_form")
            .performScrollToNode(hasTestTag("scanned_supplier_input"))
        composeTestRule.onNodeWithTag("scanned_supplier_input").performTextInput("Tesco Stores SR")

        composeTestRule.onNodeWithTag("review_scanned_form")
            .performScrollToNode(hasTestTag("recalculate_scan_vat_button"))
        composeTestRule.onNodeWithTag("recalculate_scan_vat_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("scanned_vat_amount_input").assertTextContains("23,00")
    }

    @Test
    fun scanScreen_reviewForm_confirmSavesEditedUnpaidInvoiceAndNavigatesBack() {
        var archivedId: String? = null
        var backCalls = 0
        val (vm, repo, _) = buildHarness(
            archiveReceipt = { _, id ->
                archivedId = id
                "/files/receipts/$id.jpg"
            }
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                ScanScreen(
                    viewModel = vm,
                    onNavigateBack = { backCalls += 1 }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scan_mock_sample_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("analyze_with_ai_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            vm.scanState.value is ScanUiState.Success
        }
        composeTestRule.waitForIdle()

        // Edit due date and mark invoice as unpaid before saving
        composeTestRule.onNodeWithTag("review_scanned_form")
            .performScrollToNode(hasTestTag("scanned_due_date_input"))
        composeTestRule.onNodeWithTag("scanned_due_date_input").performTextInput("2026-12-01")

        composeTestRule.onNodeWithTag("review_scanned_form")
            .performScrollToNode(hasTestTag("scanned_paid_switch"))
        composeTestRule.onNodeWithTag("scanned_paid_switch").performClick()

        composeTestRule.onNodeWithTag("review_scanned_form")
            .performScrollToNode(hasTestTag("confirm_scanned_button"))
        composeTestRule.onNodeWithTag("confirm_scanned_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            backCalls > 0
        }
        composeTestRule.waitForIdle()

        assertEquals(1, backCalls)
        assertEquals(ScanUiState.Idle, vm.scanState.value)
        val saved = repo.currentInvoicesSnapshot().single()
        assertEquals("Alza.sk s.r.o.", saved.supplierName)
        assertEquals("2026-12-01", saved.dueDate)
        assertFalse(saved.isPaid)
        assertEquals("/files/receipts/${saved.id}.jpg", saved.imageUrl)
        assertEquals(saved.id, archivedId)
    }

    @Test
    fun scanScreen_backButton_resetsActiveErrorStateAndNavigatesBack() {
        val (vm, _, _) = buildHarness(
            aiExtractor = FakeInvoiceAiExtractor { AiExtractionResult.Timeout }
        )
        var backCalls = 0

        composeTestRule.setContent {
            MyApplicationTheme {
                ScanScreen(
                    viewModel = vm,
                    onNavigateBack = { backCalls += 1 }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("scan_mock_sample_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("analyze_with_ai_button").performClick()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            vm.scanState.value is ScanUiState.Error
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("back_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, backCalls)
        assertEquals(ScanUiState.Idle, vm.scanState.value)
    }
}
