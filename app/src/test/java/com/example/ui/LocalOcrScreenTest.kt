package com.example.ui

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.AiExtractionResult
import com.example.data.ExtractedInvoiceJson
import com.example.data.InvoiceAiExtractor
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceDataGateway
import com.example.ui.screens.ScanScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], qualifiers = "w411dp-h891dp")
class LocalOcrScreenTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    @After fun cleanup() { store.clear() }
    @Test fun explicitOcrChoice_readsWithZeroCreditsAndShowsReviewWarnings() {
        val repo = FakeInvoiceDataGateway(initialCredits = 0)
        var cloudCalls = 0; var localCalls = 0
        val vm = InvoiceViewModel(ApplicationProvider.getApplicationContext<Application>(), repo, FakeBillingGateway(),
            InvoiceAiExtractor { cloudCalls++; error("Cloud must not be called") },
            clearCredentialStateAction = {}, localOcrExtractor = InvoiceAiExtractor {
                localCalls++; AiExtractionResult.Success(ExtractedInvoiceJson(
                    supplierName = "Synthetic merchant", issueDate = "2026-11-18", totalAmount = 12.3,
                    reviewWarnings = listOf("Údaje z OCR pred uložením skontrolujte podľa fotografie.")
                ))
            }).also { store.put("vm", it) }
        compose.setContent { MyApplicationTheme { ScanScreen(vm, {}) } }
        compose.onNodeWithTag("scan_mock_sample_button").performClick()
        compose.onNodeWithTag("local_ocr_switch").performScrollTo().performClick()
        compose.onNodeWithText("Lokálne OCR · bez kreditu").assertIsDisplayed()
        compose.onNodeWithTag("analyze_with_ai_button").performScrollTo().performClick()
        compose.waitUntil(5_000) { vm.scanState.value is ScanUiState.Success }
        compose.onNodeWithText("Lokálne čítanie dokončené").assertIsDisplayed()
        compose.onNodeWithTag("ocr_review_warnings").assertExists()
        compose.runOnIdle {
            assertEquals(0, cloudCalls); assertEquals(1, localCalls)
            assertEquals(0, repo.reserveCalls); assertEquals(0, repo.commitCalls); assertEquals(0, repo.rollbackCalls)
        }
    }
}
