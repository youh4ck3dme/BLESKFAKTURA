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
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.example.data.Invoice
import com.example.data.UserInfo
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceAiExtractor
import com.example.fakes.FakeInvoiceDataGateway
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], qualifiers = "w411dp-h891dp")
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private fun buildHarness(
        user: UserInfo? = UserInfo(
            uid = "user_settings_1",
            email = "janko@firma.sk",
            displayName = "Janko Hraško",
            photoUrl = null
        ),
        credits: Int = 14,
        isSubscribed: Boolean = false,
        invoices: List<Invoice> = listOf(
            Invoice(id = "i1", supplierName = "Slovak Telekom", issueDate = "2026-11-01", totalAmountCents = 2500L),
            Invoice(id = "i2", supplierName = "O2 Slovakia", issueDate = "2026-11-05", totalAmountCents = 3000L)
        ),
        onClearCredentialState: () -> Unit = {}
    ): Triple<InvoiceViewModel, FakeInvoiceDataGateway, FakeBillingGateway> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = FakeInvoiceDataGateway(
            initialUser = user,
            initialInvoices = invoices,
            initialCredits = credits,
            initialSubscribed = isSubscribed
        )
        val billing = FakeBillingGateway()
        val vm = InvoiceViewModel(
            application = app,
            repository = repo,
            playBillingManager = billing,
            aiExtractor = FakeInvoiceAiExtractor(),
            clearCredentialStateAction = { onClearCredentialState() }
        )
        return Triple(vm, repo, billing)
    }

    @Test
    fun settingsScreen_displaysProfileStats_andHandlesBackAndPricingNavigation() {
        val (vm, repo, _) = buildHarness(credits = 14, isSubscribed = false)
        var backClicks = 0
        var pricingClicks = 0

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = { backClicks += 1 },
                    onNavigateToPricing = { pricingClicks += 1 }
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("settings_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Janko Hraško").assertIsDisplayed()
        composeTestRule.onNodeWithText("janko@firma.sk").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zostatok kreditov: 14 skenov • Dokladov: 2").assertIsDisplayed()

        // Upgrading to subscription reflects reactively in Settings card
        repo.setCreditsAndSubscription(credits = 14, isSubscribed = true)
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Tarifa: Blesk Premium (aktívne)").assertIsDisplayed()

        composeTestRule.onNodeWithTag("settings_pricing_button").performClick()
        composeTestRule.onNodeWithTag("settings_back_button").performClick()
        composeTestRule.waitForIdle()

        assertEquals(1, pricingClicks)
        assertEquals(1, backClicks)
    }

    @Test
    fun settingsScreen_privacyDialogAndSupportEmailActionWorkCleanly() {
        val (vm, _, _) = buildHarness()

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = {},
                    onNavigateToPricing = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        // Empty privacy_policy_url in strings.xml hides the web HTTPS link button
        composeTestRule.onAllNodesWithTag("privacy_policy_web_button").assertCountEquals(0)

        // Open and close Privacy Policy modal
        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("privacy_policy_button"))
        composeTestRule.onNodeWithTag("privacy_policy_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Zásady ochrany osobných údajov").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zavrieť").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onAllNodesWithText("Zásady ochrany osobných údajov").assertCountEquals(0)

        // Trigger GDPR support mailto intent
        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("support_email_button"))
        composeTestRule.onNodeWithTag("support_email_button").performClick()
        composeTestRule.waitForIdle()

        val emailIntent = Shadows.shadowOf(composeTestRule.activity).nextStartedActivity
        assertEquals(Intent.ACTION_SENDTO, emailIntent?.action)
        assertEquals("mailto:erikbabcan@gmail.com", emailIntent?.dataString)
        assertEquals(
            "Blesková Faktúra — GDPR / Podpora",
            emailIntent?.getStringExtra(Intent.EXTRA_SUBJECT)
        )
    }

    @Test
    fun settingsScreen_signOutButton_clearsSessionAndCredentialState() {
        var credentialCleared = false
        val (vm, repo, _) = buildHarness(onClearCredentialState = { credentialCleared = true })

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = {},
                    onNavigateToPricing = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("settings_sign_out_button"))
        composeTestRule.onNodeWithTag("settings_sign_out_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(credentialCleared)
        assertEquals(1, repo.signOutCalls)
        assertNull(repo.currentUser.value)
        assertTrue(repo.currentInvoicesSnapshot().isEmpty())
    }

    @Test
    fun settingsScreen_deleteAccount_cancelThenConfirmSuccess_clearsAllUserData() {
        var credentialCleared = false
        val (vm, repo, _) = buildHarness(onClearCredentialState = { credentialCleared = true })

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = {},
                    onNavigateToPricing = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("settings_delete_account_button"))

        // Open confirmation dialog and cancel -> user stays signed in
        composeTestRule.onNodeWithTag("settings_delete_account_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Natrvalo vymazať účet?").assertIsDisplayed()
        composeTestRule.onNodeWithText("Zrušiť").performClick()
        composeTestRule.waitForIdle()

        assertEquals("user_settings_1", repo.currentUser.value?.uid)
        assertEquals(2, repo.currentInvoicesSnapshot().size)

        // Confirm permanent deletion -> clears user, invoices, and credential state
        composeTestRule.onNodeWithTag("settings_delete_account_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_delete_account_button").performClick()
        composeTestRule.waitForIdle()

        assertTrue(credentialCleared)
        assertNull(repo.currentUser.value)
        assertTrue(repo.currentInvoicesSnapshot().isEmpty())
    }

    @Test
    fun settingsScreen_deleteAccountFailure_displaysReauthErrorDialog() {
        val (vm, repo, _) = buildHarness()
        repo.deleteAccountResult = Result.failure(
            IllegalStateException("Pre vymazanie účtu sa prosím znova prihláste cez Google.")
        )

        composeTestRule.setContent {
            MyApplicationTheme {
                SettingsScreen(
                    viewModel = vm,
                    onNavigateBack = {},
                    onNavigateToPricing = {}
                )
            }
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNode(hasScrollToIndexAction())
            .performScrollToNode(hasTestTag("settings_delete_account_button"))
        composeTestRule.onNodeWithTag("settings_delete_account_button").performClick()
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithTag("confirm_delete_account_button").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Výmaz účtu vyžaduje overenie").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pre vymazanie účtu sa prosím znova prihláste cez Google.")
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Rozumiem").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onAllNodesWithText("Výmaz účtu vyžaduje overenie").assertCountEquals(0)
        assertEquals("user_settings_1", repo.currentUser.value?.uid)
    }
}
