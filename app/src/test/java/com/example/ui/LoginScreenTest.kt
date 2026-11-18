package com.example.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.example.data.UserInfo
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceAiExtractor
import com.example.fakes.FakeInvoiceDataGateway
import com.example.ui.screens.LoginScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], qualifiers = "w411dp-h891dp")
class LoginScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private fun buildHarness(
        initialUser: UserInfo? = null,
        firebaseMode: Boolean = true
    ): Pair<InvoiceViewModel, FakeInvoiceDataGateway> {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = FakeInvoiceDataGateway(initialUser = initialUser).apply {
            firebaseModeEnabled = firebaseMode
        }
        val vm = InvoiceViewModel(
            application = app,
            repository = repo,
            playBillingManager = FakeBillingGateway(),
            aiExtractor = FakeInvoiceAiExtractor()
        )
        return vm to repo
    }

    @Test
    fun loginScreen_firebaseMode_displaysBrandCopyAndLoginActions() {
        val (vm, _) = buildHarness(initialUser = null, firebaseMode = true)
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            MyApplicationTheme {
                LoginScreen(
                    viewModel = vm,
                    onLoginSuccess = {}
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("login_screen").assertIsDisplayed()
        composeTestRule.onNodeWithText("Blesková Faktúra").assertIsDisplayed()
        composeTestRule.onNodeWithText("Začnite účtovať bleskovo").assertIsDisplayed()
        composeTestRule.onNodeWithTag("google_login_button").assertIsDisplayed()
        composeTestRule.onNodeWithTag("demo_login_button").assertIsDisplayed()
        composeTestRule.onNodeWithText("Režim: Bezpečný Cloud").assertIsDisplayed()
    }

    @Test
    fun loginScreen_nonFirebaseMode_showsWarningBannerAndToastOnGoogleClick() {
        val (vm, _) = buildHarness(initialUser = null, firebaseMode = false)
        var loginCallbacks = 0
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            MyApplicationTheme {
                LoginScreen(
                    viewModel = vm,
                    onLoginSuccess = { loginCallbacks += 1 }
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithText("Chýba cloudová konfigurácia").assertIsDisplayed()
        composeTestRule.onNodeWithTag("google_login_button").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()

        assertEquals(0, loginCallbacks)
        assertEquals(
            "Cloudové prihlásenie nie je v tejto zostave dostupné.",
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun loginScreen_demoLoginButton_signsInDemoUserAndTriggersCallback() {
        val (vm, repo) = buildHarness(initialUser = null, firebaseMode = true)
        var loginCallbacks = 0
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            MyApplicationTheme {
                LoginScreen(
                    viewModel = vm,
                    onLoginSuccess = { loginCallbacks += 1 }
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()

        composeTestRule.onNodeWithTag("demo_login_button").performClick()
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()

        assertEquals("demo_szco_2026", repo.currentUser.value?.uid)
        assertTrue(loginCallbacks >= 1)
    }

    @Test
    fun loginScreen_existingAuthenticatedUser_triggersAutoRedirect() {
        val existingUser = UserInfo(
            uid = "existing_uid",
            email = "ucet@firma.sk",
            displayName = "Prihlásený Používateľ",
            photoUrl = null
        )
        val (vm, _) = buildHarness(initialUser = existingUser, firebaseMode = true)
        var loginCallbacks = 0
        composeTestRule.mainClock.autoAdvance = false

        composeTestRule.setContent {
            MyApplicationTheme {
                LoginScreen(
                    viewModel = vm,
                    onLoginSuccess = { loginCallbacks += 1 }
                )
            }
        }
        composeTestRule.mainClock.advanceTimeByFrame()
        composeTestRule.mainClock.advanceTimeByFrame()

        assertTrue(loginCallbacks >= 1)
    }
}
