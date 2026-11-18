package com.example.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.example.data.Invoice
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceAiExtractor
import com.example.fakes.FakeInvoiceDataGateway
import com.example.ui.screens.DashboardScreen
import com.example.ui.theme.MyApplicationTheme
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
class DashboardScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun sampleInvoices(): List<Invoice> = listOf(
        Invoice(
            id = "inv_unpaid_overdue",
            supplierName = "Slovak Telekom",
            issueDate = "2020-01-10",
            dueDate = "2020-01-20",
            isPaid = false,
            totalAmountCents = 4_900L,
            category = "Služby",
            createdAt = 100L
        ),
        Invoice(
            id = "inv_unpaid_fresh",
            supplierName = "Alza.sk",
            issueDate = "2026-11-16",
            dueDate = "2099-12-31",
            isPaid = false,
            totalAmountCents = 25_000L,
            category = "Technika",
            createdAt = 400L
        ),
        Invoice(
            id = "inv_paid_recent",
            supplierName = "SPP Energia",
            issueDate = "2026-11-15",
            dueDate = "2026-11-25",
            isPaid = true,
            totalAmountCents = 8_500L,
            category = "Kancelária",
            createdAt = 300L
        ),
        Invoice(
            id = "inv_paid_older",
            supplierName = "Nay Elektrodom",
            issueDate = "2026-10-05",
            dueDate = "2026-10-15",
            isPaid = true,
            totalAmountCents = 12_000L,
            category = "Technika",
            createdAt = 200L
        )
    )

    private fun buildViewModel(
        invoices: List<Invoice> = sampleInvoices(),
        pendingPurchases: Int = 1
    ): Triple<InvoiceViewModel, FakeInvoiceDataGateway, FakeBillingGateway> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val fakeRepo = FakeInvoiceDataGateway(initialInvoices = invoices)
        val fakeBilling = FakeBillingGateway(initialPendingCount = pendingPurchases)
        val viewModel = InvoiceViewModel(
            application = app,
            repository = fakeRepo,
            playBillingManager = fakeBilling,
            aiExtractor = FakeInvoiceAiExtractor()
        )
        return Triple(viewModel, fakeRepo, fakeBilling)
    }

    @Test
    fun kpiUnpaidTileAndChip_filterUnpaidInvoicesAndRestoreAll() {
        val (viewModel, _, _) = buildViewModel(pendingPurchases = 1)

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToScan = {},
                    onNavigateToPricing = {},
                    onNavigateToSettings = {},
                    onSignOut = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("kpi_total_pending_badge").assertTextContains("Čakajúce položky: 3")
        composeTestRule.onNodeWithTag("kpi_overdue_badge").assertTextContains("1 po splatnosti")

        // Tap unpaid KPI tile -> enables unpaid filter & scrolls to ledger
        composeTestRule.onNodeWithTag("kpi_unpaid_tile").performClick()
        composeTestRule.waitForIdle()

        // Paid invoices should be filtered out of the ledger
        composeTestRule.onAllNodesWithTag("invoice_row_inv_paid_recent").assertCountEquals(0)
        composeTestRule.onAllNodesWithTag("invoice_row_inv_paid_older").assertCountEquals(0)

        // Unpaid invoices remain visible when scrolled into view
        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("invoice_row_inv_unpaid_fresh"))
        composeTestRule.onNodeWithTag("invoice_row_inv_unpaid_fresh").assertIsDisplayed()

        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("invoice_row_inv_unpaid_overdue"))
        composeTestRule.onNodeWithTag("invoice_row_inv_unpaid_overdue").assertIsDisplayed()

        // Toggle filter off using the filter chip
        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("filter_chip_unpaid"))
        composeTestRule.onNodeWithTag("filter_chip_unpaid").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("invoice_row_inv_paid_recent"))
        composeTestRule.onNodeWithTag("invoice_row_inv_paid_recent").assertIsDisplayed()
    }

    @Test
    fun seeAllButton_clearsSearchAndUnpaidFilters() {
        val (viewModel, _, _) = buildViewModel()

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToScan = {},
                    onNavigateToPricing = {},
                    onNavigateToSettings = {},
                    onSignOut = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("search_invoices_input"))
        composeTestRule.onNodeWithTag("search_invoices_input").performTextInput("Telekom")
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithTag("invoice_row_inv_paid_recent").assertCountEquals(0)

        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("recent_invoices_see_all_button"))
        composeTestRule.onNodeWithTag("recent_invoices_see_all_button").performClick()
        composeTestRule.waitForIdle()

        // After tapping "Zobraziť všetky", all invoices are restored
        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("invoice_row_inv_paid_recent"))
        composeTestRule.onNodeWithTag("invoice_row_inv_paid_recent").assertIsDisplayed()
    }

    @Test
    fun recentInvoiceRowTap_markPaidInDialog_updatesKpiSummaryReactively() {
        val (viewModel, _, _) = buildViewModel(pendingPurchases = 0)

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToScan = {},
                    onNavigateToPricing = {},
                    onNavigateToSettings = {},
                    onSignOut = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("kpi_total_pending_badge").assertTextContains("Čakajúce položky: 2")

        // Open detail dialog for newest unpaid invoice from Recent 3 card
        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("recent_invoice_row_inv_unpaid_fresh"))
        composeTestRule.onNodeWithTag("recent_invoice_row_inv_unpaid_fresh").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithTag("invoice_detail_dialog").assertIsDisplayed()

        // Mark as paid and save
        composeTestRule.onNodeWithTag("edit_paid_switch").performScrollTo().performClick()
        composeTestRule.onNodeWithTag("save_edit_button").performScrollTo().performClick()
        composeTestRule.waitForIdle()

        // KPI counts update reactively from 2 -> 1 unpaid
        composeTestRule.onNodeWithTag("dashboard_lazy_column")
            .performScrollToNode(hasTestTag("kpi_summary_card"))
        composeTestRule.onNodeWithTag("kpi_total_pending_badge").assertTextContains("Čakajúce položky: 1")
        composeTestRule.onNodeWithText("1 ks").assertIsDisplayed()
    }

    @Test
    fun fabScanInvoice_setsAutoLaunchCameraAndNavigatesToScan() {
        val (viewModel, _, _) = buildViewModel()
        var navigatedToScan = false

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToScan = { navigatedToScan = true },
                    onNavigateToPricing = {},
                    onNavigateToSettings = {},
                    onSignOut = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        assertFalse(viewModel.shouldAutoLaunchCamera)

        composeTestRule.onNodeWithTag("fab_scan_invoice").performClick()
        composeTestRule.waitForIdle()

        assertTrue(viewModel.shouldAutoLaunchCamera)
        assertTrue(navigatedToScan)
    }

    @Test
    fun pendingPurchasesTile_retriesWhenPendingElseNavigatesToPricing() {
        val (viewModel, _, fakeBilling) = buildViewModel(pendingPurchases = 2)
        var pricingNavigations = 0

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToScan = {},
                    onNavigateToPricing = { pricingNavigations += 1 },
                    onNavigateToSettings = {},
                    onSignOut = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("kpi_unverified_purchases_tile").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, fakeBilling.retryCalls)
        assertEquals(0, pricingNavigations)

        // When backend resolves pending purchases -> 0, tapping opens PricingScreen
        fakeBilling.setPendingCount(0)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("kpi_unverified_purchases_tile").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, pricingNavigations)
    }

    @Test
    @Config(qualifiers = "w840dp-h960dp")
    fun wideTabletLayout_pairsHeroAndKpiSideBySide() {
        val (viewModel, _, _) = buildViewModel(pendingPurchases = 1)

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardScreen(
                    viewModel = viewModel,
                    onNavigateToScan = {},
                    onNavigateToPricing = {},
                    onNavigateToSettings = {},
                    onSignOut = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        val heroBounds = composeTestRule.onNodeWithTag("hero_expenses_bento_card").fetchSemanticsNode().boundsInRoot
        val kpiBounds = composeTestRule.onNodeWithTag("kpi_summary_card").fetchSemanticsNode().boundsInRoot

        // Side-by-side: KPI card sits to the right of Hero card at approximately the same top Y
        assertTrue("Expected KPI card to the right of Hero card ($heroBounds vs $kpiBounds)", kpiBounds.left >= heroBounds.right)
        assertTrue("Expected Hero and KPI cards top-aligned", kotlin.math.abs(kpiBounds.top - heroBounds.top) < 20f)
        composeTestRule.onNodeWithTag("recent_invoices_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("fab_scan_invoice").assertIsDisplayed()
    }
}
