package com.example.ui

import android.app.Application
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.BillingProduct
import com.example.data.PlayBillingUiState
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceAiExtractor
import com.example.fakes.FakeInvoiceDataGateway
import com.example.ui.screens.PricingScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], qualifiers = "w411dp-h891dp")
class PricingScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun buildHarness(
        initialSubscribed: Boolean = false,
        initialCredits: Int = 5
    ): Triple<InvoiceViewModel, FakeInvoiceDataGateway, FakeBillingGateway> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = FakeInvoiceDataGateway(
            initialCredits = initialCredits,
            initialSubscribed = initialSubscribed
        )
        val billing = FakeBillingGateway()
        val vm = InvoiceViewModel(
            application = app,
            repository = repo,
            playBillingManager = billing,
            aiExtractor = FakeInvoiceAiExtractor()
        )
        return Triple(vm, repo, billing)
    }

    @Test
    fun pricingScreen_connectsBillingOnLaunch_andRendersCatalogPricesAndBackNavigation() {
        val (vm, _, billing) = buildHarness()
        billing.setCatalogPrices(
            mapOf(
                BillingProduct.PREMIUM_MONTHLY.productId to "8,99 € / mesiac",
                BillingProduct.CREDITS_10.productId to "1,49 €",
                BillingProduct.CREDITS_50.productId to "4,49 €",
                BillingProduct.CREDITS_100.productId to "6,99 €"
            )
        )
        var backClicks = 0

        composeTestRule.setContent {
            MyApplicationTheme {
                PricingScreen(
                    viewModel = vm,
                    onNavigateBack = { backClicks += 1 }
                )
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(1, billing.startConnectionCalls)
        composeTestRule.onNodeWithTag("pricing_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("8,99 € / mesiac").assertIsDisplayed()

        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("buy_button_credit_pack_100"))
        composeTestRule.onNodeWithText("1,49 €").assertIsDisplayed()
        composeTestRule.onNodeWithText("4,49 €").assertIsDisplayed()
        composeTestRule.onNodeWithText("6,99 €").assertIsDisplayed()

        composeTestRule.onNodeWithTag("back_button").performClick()
        composeTestRule.waitForIdle()
        assertEquals(1, backClicks)
    }

    @Test
    fun pricingScreen_unsubscribedUser_launchesSubscriptionAndAllCreditPacks() {
        val (vm, _, billing) = buildHarness(initialSubscribed = false)

        composeTestRule.setContent {
            MyApplicationTheme {
                PricingScreen(
                    viewModel = vm,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("buy_subscription_button").performClick()

        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("buy_button_credit_pack_10"))
        composeTestRule.onNodeWithTag("buy_button_credit_pack_10").performClick()

        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("buy_button_credit_pack_50"))
        composeTestRule.onNodeWithTag("buy_button_credit_pack_50").performClick()

        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("buy_button_credit_pack_100"))
        composeTestRule.onNodeWithTag("buy_button_credit_pack_100").performClick()

        assertEquals(
            listOf(
                BillingProduct.PREMIUM_MONTHLY,
                BillingProduct.CREDITS_10,
                BillingProduct.CREDITS_50,
                BillingProduct.CREDITS_100
            ),
            billing.launchedProducts
        )
    }

    @Test
    fun pricingScreen_subscribedUser_showsActiveBadgeAndOpensGooglePlayManagement() {
        val (vm, _, _) = buildHarness(initialSubscribed = true)

        composeTestRule.setContent {
            MyApplicationTheme {
                PricingScreen(
                    viewModel = vm,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("AKTÍVNE").assertIsDisplayed()
        composeTestRule.onAllNodesWithTag("buy_subscription_button").assertCountEquals(0)
        composeTestRule.onNodeWithTag("manage_subscription_button").assertIsDisplayed().performClick()
        composeTestRule.waitForIdle()

        val startedIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, startedIntent?.action)
        assertEquals(
            "https://play.google.com/store/account/subscriptions",
            startedIntent?.dataString
        )
    }

    @Test
    fun pricingScreen_restorePurchasesButton_triggersPendingBillingVerification() {
        val (vm, _, billing) = buildHarness()

        composeTestRule.setContent {
            MyApplicationTheme {
                PricingScreen(
                    viewModel = vm,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("restore_purchases_button"))
        composeTestRule.onNodeWithTag("restore_purchases_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, billing.retryCalls)
    }

    @Test
    fun pricingScreen_statusBannersAndVerifiedDialog_displayAndDismissCleanly() {
        val (vm, _, billing) = buildHarness()

        composeTestRule.setContent {
            MyApplicationTheme {
                PricingScreen(
                    viewModel = vm,
                    onNavigateBack = {}
                )
            }
        }

        composeTestRule.waitForIdle()

        // Info banner
        billing.setBillingState(PlayBillingUiState.Info("Nákup čaká na potvrdenie platby."))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("billing_status_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Nákup čaká na potvrdenie platby.").assertIsDisplayed()

        // Error banner
        billing.setBillingState(PlayBillingUiState.Error("Google Play nie je dostupný."))
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("billing_status_banner").assertIsDisplayed()
        composeTestRule.onNodeWithText("Google Play nie je dostupný.").assertIsDisplayed()

        // Verified dialog & dismiss clears billing state
        billing.setBillingState(
            PlayBillingUiState.Verified(
                productId = BillingProduct.CREDITS_50.productId,
                creditsGranted = 50,
                isSubscribed = false,
                idempotent = false,
                message = "Pripísaných +50 kreditov na váš účet."
            )
        )
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("purchase_verified_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pripísaných +50 kreditov na váš účet.").assertIsDisplayed()
        composeTestRule.onNodeWithTag("success_dismiss_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, billing.clearMessageCalls)
        assertTrue(billing.billingState.value is PlayBillingUiState.Idle)
        composeTestRule.onAllNodesWithTag("purchase_verified_dialog").assertCountEquals(0)
    }

    @Test
    fun pricingScreen_launchingAndPendingVerificationDialogs_displayProgressCopy() {
        val (vm, _, billing) = buildHarness()
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            MyApplicationTheme {
                PricingScreen(
                    viewModel = vm,
                    onNavigateBack = {}
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        billing.setBillingState(PlayBillingUiState.LaunchingFlow)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithText("Otváram Google Play...").assertIsDisplayed()

        billing.setBillingState(
            PlayBillingUiState.PendingVerification(
                productId = BillingProduct.CREDITS_50.productId,
                message = "Overujem podpis transakcie..."
            )
        )
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onNodeWithTag("purchase_verification_dialog").assertIsDisplayed()
        composeTestRule.onNodeWithText("Overujem nákup na serveri").assertIsDisplayed()
        composeTestRule.onNodeWithText("Overujem podpis transakcie...").assertIsDisplayed()

        billing.setBillingState(PlayBillingUiState.Idle)
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.onAllNodesWithTag("purchase_verification_dialog").assertCountEquals(0)
    }
}
