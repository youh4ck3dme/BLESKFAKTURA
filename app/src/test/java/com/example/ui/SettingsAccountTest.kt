package com.example.ui

import android.app.Application
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.InvoiceDataGateway
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceAiExtractor
import com.example.fakes.FakeInvoiceDataGateway
import com.example.ui.screens.LoginScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24], qualifiers = "w411dp-h891dp")
class SettingsAccountTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()

    @After fun clearViewModels() { store.clear() }

    private fun viewModel(repository: InvoiceDataGateway) = InvoiceViewModel(
        ApplicationProvider.getApplicationContext<Application>(), repository, FakeBillingGateway(),
        FakeInvoiceAiExtractor(), clearCredentialStateAction = {}
    ).also { store.put("account", it) }

    private fun showSettings(repository: InvoiceDataGateway) {
        val vm = viewModel(repository)
        compose.setContent { MyApplicationTheme { SettingsScreen(vm, {}, {}) } }
    }

    private fun requestDeletion() {
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("settings_delete_account_button"))
        compose.onNodeWithTag("settings_delete_account_button").performClick()
    }

    @Test
    fun demoPrivacy_describesLocalStorageRatherThanGoogleCloud() {
        val repository = FakeInvoiceDataGateway().apply { signInDemoUser() }
        showSettings(repository)
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasTestTag("privacy_policy_button"))
        compose.onNodeWithText("Demo účet uchováva doklady v tomto zariadení.", substring = true).assertExists()
    }

    @Test
    fun cancelledDeletion_doesNotCallGatewayOrRemoveAccount() {
        val data = FakeInvoiceDataGateway()
        var deletionCalls = 0
        val repository = object : InvoiceDataGateway by data {
            override suspend fun deleteAccount(targetUid: String?): Result<Unit> {
                deletionCalls++
                return data.deleteAccount(targetUid)
            }
        }
        showSettings(repository)
        requestDeletion()
        compose.onNodeWithText("Zrušiť").performClick()
        compose.onNodeWithTag("confirm_delete_account_button").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(0, deletionCalls)
            assertNotNull(data.currentUser.value)
        }
    }

    @Test
    fun failedDeletion_showsSafePartialFailureAndEnablesRetry() {
        val data = FakeInvoiceDataGateway()
        var deletionCalls = 0
        val repository = object : InvoiceDataGateway by data {
            override suspend fun deleteAccount(targetUid: String?): Result<Unit> {
                deletionCalls++
                return Result.failure(IllegalStateException("PRIVATE_SDK_MARKER"))
            }
        }
        showSettings(repository)
        repeat(2) {
            requestDeletion()
            compose.onNodeWithTag("confirm_delete_account_button").performClick()
            compose.onNodeWithText("Časť údajov už mohla byť odstránená.", substring = true).assertExists()
            compose.onNodeWithText("PRIVATE_SDK_MARKER", substring = true).assertDoesNotExist()
            compose.onNodeWithText("Rozumiem").performClick()
        }
        compose.runOnIdle {
            assertEquals(2, deletionCalls)
            assertNotNull(data.currentUser.value)
        }
    }

    @Test
    fun debugDemoLogin_setsLocalIdentityAndNavigatesOnce() {
        val repository = FakeInvoiceDataGateway(initialUser = null)
        val vm = viewModel(repository)
        var navigations = 0
        compose.setContent { MyApplicationTheme { LoginScreen(vm) { navigations++ } } }
        compose.onNodeWithTag("demo_login_button").performClick()
        compose.runOnIdle {
            assertTrue(repository.currentUser.value!!.uid.startsWith("demo_"))
            assertEquals(1, navigations)
        }
    }
}
