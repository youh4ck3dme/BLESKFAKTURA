package com.example.ui

import android.Manifest
import android.app.Application
import android.net.Uri
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceAiExtractor
import com.example.fakes.FakeInvoiceDataGateway
import com.example.ui.screens.ScanScreen
import com.example.ui.theme.MyApplicationTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36], qualifiers = "w411dp-h891dp")
class ScanCaptureTest {
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val repository = FakeInvoiceDataGateway()

    @After fun clearViewModels() { store.clear() }

    private fun show(registry: ActivityResultRegistry): InvoiceViewModel {
        val vm = InvoiceViewModel(app, repository, FakeBillingGateway(), FakeInvoiceAiExtractor(),
            clearCredentialStateAction = {}).also { store.put("scan", it) }
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry = registry
        }
        compose.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                MyApplicationTheme { ScanScreen(vm, onNavigateBack = {}) }
            }
        }
        return vm
    }

    @Test
    fun deniedCameraPermission_showsRecoveryAndNeverStartsCaptureOrAi() {
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        var permissionRequests = 0
        val vm = show(object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.RequestPermission)
                assertEquals(Manifest.permission.CAMERA, input)
                permissionRequests++
                dispatchResult(code, false)
            }
        })
        compose.onNodeWithTag("fab_instant_camera_capture").performClick()
        compose.onNodeWithTag("camera_permission_rationale").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("scan_gallery_button").performScrollTo().assertIsEnabled()
        compose.runOnIdle {
            assertEquals(1, permissionRequests)
            assertTrue(vm.scanState.value is ScanUiState.Idle)
            assertEquals(0, repository.reserveCalls)
        }
    }

    @Test
    fun cancelledCameraCapture_keepsCreditsAndAllowsAnotherCapture() {
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        var captures = 0
        val vm = show(object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.TakePicture)
                assertEquals("content", (input as Uri).scheme)
                captures++
                dispatchResult(code, false)
            }
        })
        repeat(2) { compose.onNodeWithTag("fab_instant_camera_capture").performClick() }
        compose.runOnIdle {
            assertEquals(2, captures)
            assertTrue(vm.scanState.value is ScanUiState.Idle)
            assertEquals(0, repository.reserveCalls)
        }
    }

    @Test
    fun cancelledPhotoPicker_doesNotStartAnalysisOrDebitCredits() {
        val vm = show(object : ActivityResultRegistry() {
            override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                assertTrue(contract is ActivityResultContracts.PickVisualMedia)
                dispatchResult<Uri?>(code, null)
            }
        })
        compose.onNodeWithTag("scan_gallery_button").performScrollTo().performClick()
        compose.runOnIdle {
            assertTrue(vm.scanState.value is ScanUiState.Idle)
            assertEquals(0, repository.reserveCalls)
            assertEquals(10, repository.currentStatsSnapshot().credits)
        }
    }
}
