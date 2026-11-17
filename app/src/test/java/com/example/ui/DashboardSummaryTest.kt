package com.example.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.data.Invoice
import com.example.data.SlovakFinanceUtils
import com.example.ui.screens.DashboardKpiSummaryCard
import com.example.ui.screens.RecentInvoicesSummaryCard
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class DashboardSummaryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun computeDashboardSummary_sortsRecent3_countsUnpaidOverdueAndPurchases() {
        val todayMillis = SlovakFinanceUtils.parseDateUtcMidnightMillis("2026-11-17")!!

        val invoices = listOf(
            Invoice(
                id = "inv_old_paid",
                supplierName = "Old Paid s.r.o.",
                issueDate = "2026-10-01",
                dueDate = "2026-10-15",
                isPaid = true,
                totalAmountCents = 10_000L,
                createdAt = 100L
            ),
            Invoice(
                id = "inv_newest_unpaid",
                supplierName = "Alza.sk",
                issueDate = "2026-11-16",
                dueDate = "2026-11-30",
                isPaid = false,
                totalAmountCents = 25_000L,
                createdAt = 500L
            ),
            Invoice(
                id = "inv_overdue",
                supplierName = "Slovak Telekom",
                issueDate = "2026-11-05",
                dueDate = "2026-11-10",
                isPaid = false,
                totalAmountCents = 4_999L,
                createdAt = 300L
            ),
            Invoice(
                id = "inv_same_day_newer",
                supplierName = "O2 Business",
                issueDate = "16.11.2026",
                dueDate = null,
                isPaid = true,
                totalAmountCents = 3_500L,
                createdAt = 900L
            )
        )

        val summary = computeDashboardSummary(
            invoices = invoices,
            pendingPurchaseCount = 2,
            todayUtcMillis = todayMillis
        )

        assertEquals(3, summary.recentInvoices.size)
        assertEquals(listOf("inv_same_day_newer", "inv_newest_unpaid", "inv_overdue"), summary.recentInvoices.map { it.id })
        assertEquals(33_499L, summary.recentTotalCents)
        assertEquals(2, summary.unpaidCount)
        assertEquals(29_999L, summary.unpaidTotalCents)
        assertEquals(1, summary.overdueCount)
        assertEquals(2, summary.pendingPurchaseCount)
        assertEquals(4, summary.totalPendingCount)
    }

    @Test
    fun dashboardKpiSummaryCard_displaysCountsAndHandlesTaps() {
        var unpaidClicked = false
        var purchasesClicked = false
        val summary = DashboardSummaryUiState(
            unpaidCount = 3,
            unpaidTotalCents = 45_000L,
            overdueCount = 1,
            pendingPurchaseCount = 2,
            totalPendingCount = 5
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                DashboardKpiSummaryCard(
                    summary = summary,
                    isUnpaidFilterActive = false,
                    onToggleUnpaidFilter = { unpaidClicked = true },
                    onPendingPurchasesClick = { purchasesClicked = true }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("kpi_summary_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("kpi_total_pending_badge").assertTextContains("Čakajúce položky: 5")
        composeTestRule.onNodeWithTag("kpi_overdue_badge").assertTextContains("1 po splatnosti")
        composeTestRule.onNodeWithText("3 ks").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 ks").assertIsDisplayed()
        composeTestRule.onNodeWithText("Overiť nákup").assertIsDisplayed()

        composeTestRule.onNodeWithTag("kpi_unpaid_tile").performClick()
        composeTestRule.onNodeWithTag("kpi_unverified_purchases_tile").performClick()
        composeTestRule.waitForIdle()

        assertTrue(unpaidClicked)
        assertTrue(purchasesClicked)
    }

    @Test
    fun recentInvoicesSummaryCard_rendersRecentRowsAndEmptyState() {
        var clickedId: String? = null
        var seeAllClicked = false
        val recent = listOf(
            Invoice(id = "r1", supplierName = "SPP Energia", issueDate = "2026-11-15", totalAmountCents = 8_900L, isPaid = true),
            Invoice(id = "r2", supplierName = "ZSE a.s.", issueDate = "2026-11-14", totalAmountCents = 12_400L, isPaid = false)
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                RecentInvoicesSummaryCard(
                    recentInvoices = recent,
                    recentTotalCents = 21_300L,
                    onInvoiceClick = { clickedId = it.id },
                    onSeeAllClick = { seeAllClicked = true },
                    onScanClick = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recent_invoices_card").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recent_invoice_row_r1").performClick()
        composeTestRule.onNodeWithTag("recent_invoices_see_all_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals("r1", clickedId)
        assertTrue(seeAllClicked)
    }

    @Test
    fun recentInvoicesSummaryCard_emptyStateTriggersScanCallback() {
        var scanClicked = false

        composeTestRule.setContent {
            MyApplicationTheme {
                RecentInvoicesSummaryCard(
                    recentInvoices = emptyList(),
                    recentTotalCents = 0L,
                    onInvoiceClick = {},
                    onSeeAllClick = {},
                    onScanClick = { scanClicked = true }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("recent_invoices_empty_state").assertIsDisplayed()
        composeTestRule.onNodeWithTag("recent_invoices_empty_scan_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(scanClicked)
    }
}
