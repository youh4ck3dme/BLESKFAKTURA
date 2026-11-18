package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.example.data.AiExtractionResult
import com.example.data.ExtractedInvoiceJson
import com.example.data.Invoice
import com.example.data.UserInfo
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceAiExtractor
import com.example.fakes.FakeInvoiceDataGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 36])
class InvoiceViewModelScanAndCreditsTest {

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun testBitmap(): Bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)

    private fun TestScope.createHarness(
        initialUser: UserInfo? = UserInfo("user_1", "user@blesk.sk", "Tester", null),
        initialCredits: Int = 10,
        initialSubscribed: Boolean = false,
        aiExtractor: FakeInvoiceAiExtractor = FakeInvoiceAiExtractor(),
        archiveReceipt: suspend (Bitmap, String) -> String? = { _, id -> "/private/receipts/$id.jpg" },
        clearCredentialStateAction: suspend () -> Unit = {}
    ): Triple<InvoiceViewModel, FakeInvoiceDataGateway, FakeInvoiceAiExtractor> {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repo = FakeInvoiceDataGateway(
            initialUser = initialUser,
            initialCredits = initialCredits,
            initialSubscribed = initialSubscribed
        )
        val vm = InvoiceViewModel(
            application = app,
            repository = repo,
            playBillingManager = FakeBillingGateway(),
            aiExtractor = aiExtractor,
            archiveReceipt = archiveReceipt,
            clearCredentialStateAction = clearCredentialStateAction
        )
        return Triple(vm, repo, aiExtractor)
    }

    @Test
    fun scanInvoiceImage_unauthenticatedUser_emitsErrorWithoutReservingCredits() = runTest {
        val (vm, repo, ai) = createHarness(initialUser = null)

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()

        val state = vm.scanState.value
        assertTrue("Expected Error, got $state", state is ScanUiState.Error)
        assertTrue((state as ScanUiState.Error).message.contains("prihlásiť"))
        assertEquals(0, repo.reserveCalls)
        assertEquals(0, ai.calls)
    }

    @Test
    fun scanInvoiceImage_insufficientCredits_emitsErrorWithoutCallingAi() = runTest {
        val (vm, repo, ai) = createHarness(initialCredits = 0, initialSubscribed = false)

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()

        val state = vm.scanState.value
        assertTrue(state is ScanUiState.Error)
        assertTrue((state as ScanUiState.Error).message.contains("Nemáte dostatok kreditov"))
        assertEquals(1, repo.reserveCalls)
        assertEquals(0, ai.calls)
        assertEquals(0, repo.currentStatsSnapshot().credits)
    }

    @Test
    fun scanInvoiceImage_serverActiveReservation_emitsAlreadyInProgressError() = runTest {
        val (vm, repo, ai) = createHarness(initialCredits = 5)
        repo.activeReservationId = "existing_active_lock"

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()

        val state = vm.scanState.value
        assertTrue(state is ScanUiState.Error)
        assertTrue((state as ScanUiState.Error).message.contains("Už prebieha"))
        assertEquals(1, repo.reserveCalls)
        assertEquals(0, ai.calls)
        assertEquals(5, repo.currentStatsSnapshot().credits)
    }

    @Test
    fun scanInvoiceImage_success_commitsReservationAndKeepsDecrementedCredit() = runTest {
        val (vm, repo, ai) = createHarness(initialCredits = 10)

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()

        val state = vm.scanState.value
        assertTrue("Expected Success, got $state", state is ScanUiState.Success)
        assertEquals("Alza.sk s.r.o.", (state as ScanUiState.Success).extractedData.supplierName)
        assertEquals(1, ai.calls)
        assertEquals(1, repo.reserveCalls)
        assertEquals(1, repo.commitCalls)
        assertEquals(0, repo.rollbackCalls)
        assertEquals(9, repo.currentStatsSnapshot().credits)
    }

    @Test
    fun scanInvoiceImage_unlimitedSubscriber_scansWithoutConsumingCredits() = runTest {
        val (vm, repo, ai) = createHarness(initialCredits = 0, initialSubscribed = true)

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()

        assertTrue(vm.scanState.value is ScanUiState.Success)
        assertEquals(1, ai.calls)
        assertEquals(1, repo.reserveCalls)
        assertEquals(0, repo.commitCalls)
        assertEquals(0, repo.rollbackCalls)
        assertEquals(0, repo.currentStatsSnapshot().credits)
    }

    @Test
    fun scanInvoiceImage_invalidDocument_rollsBackCreditWithRefundMessage() = runTest {
        val ai = FakeInvoiceAiExtractor {
            AiExtractionResult.InvalidDocument("Snímka nie je účtovný doklad.")
        }
        val (vm, repo, _) = createHarness(initialCredits = 5, aiExtractor = ai)

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()

        val state = vm.scanState.value as ScanUiState.Error
        assertTrue(state.message.contains("Snímka nie je účtovný doklad."))
        assertTrue(state.message.contains("Kredit bol vrátený."))
        assertEquals(1, repo.rollbackCalls)
        assertEquals(0, repo.commitCalls)
        assertEquals(5, repo.currentStatsSnapshot().credits)
    }

    @Test
    fun scanInvoiceImage_timeout_rollsBackCreditWithRefundMessage() = runTest {
        val ai = FakeInvoiceAiExtractor { AiExtractionResult.Timeout }
        val (vm, repo, _) = createHarness(initialCredits = 4, aiExtractor = ai)

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()

        val state = vm.scanState.value as ScanUiState.Error
        assertTrue(state.message.contains("vypršala"))
        assertTrue(state.message.contains("Kredit bol vrátený."))
        assertEquals(1, repo.rollbackCalls)
        assertEquals(4, repo.currentStatsSnapshot().credits)
    }

    @Test
    fun scanInvoiceImage_serviceErrorAndUnexpectedException_rollBackCredit() = runTest {
        var throwMode = false
        val ai = FakeInvoiceAiExtractor {
            if (throwMode) {
                throw IllegalStateException("Unexpected network drop")
            } else {
                AiExtractionResult.ServiceError("Služba dočasne nedostupná.")
            }
        }
        val (vm, repo, _) = createHarness(initialCredits = 7, aiExtractor = ai)

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()
        val firstErr = vm.scanState.value as ScanUiState.Error
        assertTrue(firstErr.message.contains("Kredit bol vrátený."))
        assertEquals(1, repo.rollbackCalls)
        assertEquals(7, repo.currentStatsSnapshot().credits)

        throwMode = true
        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()
        val secondErr = vm.scanState.value as ScanUiState.Error
        assertTrue(secondErr.message.contains("Unexpected network drop"))
        assertTrue(secondErr.message.contains("Kredit bol vrátený."))
        assertEquals(2, repo.rollbackCalls)
        assertEquals(7, repo.currentStatsSnapshot().credits)
    }

    @Test
    fun scanInvoiceImage_duplicateCallWhileInFlight_isIgnored() = runTest {
        val gate = CompletableDeferred<AiExtractionResult>()
        val ai = FakeInvoiceAiExtractor { gate.await() }
        val (vm, repo, _) = createHarness(initialCredits = 10, aiExtractor = ai)

        val bmp = testBitmap()
        vm.scanInvoiceImage(bmp)
        testScheduler.runCurrent()

        assertTrue(vm.scanState.value is ScanUiState.Scanning)
        assertEquals(1, repo.reserveCalls)
        assertEquals(9, repo.currentStatsSnapshot().credits)

        // Second click while activeScanJob is still running must be ignored
        vm.scanInvoiceImage(bmp)
        testScheduler.runCurrent()
        assertEquals(1, repo.reserveCalls)
        assertEquals(1, ai.calls)

        gate.complete(
            AiExtractionResult.Success(
                ExtractedInvoiceJson(supplierName = "Lidl Slovenská republika", issueDate = "2026-11-17", totalAmount = 19.5)
            )
        )
        advanceUntilIdle()

        assertTrue(vm.scanState.value is ScanUiState.Success)
        assertEquals(1, repo.commitCalls)
        assertEquals(9, repo.currentStatsSnapshot().credits)
    }

    @Test
    fun scanInvoiceImage_accountSwitchedMidFlight_rollsBackOriginalUsersCredit() = runTest {
        val gate = CompletableDeferred<AiExtractionResult>()
        val ai = FakeInvoiceAiExtractor { gate.await() }
        val (vm, repo, _) = createHarness(
            initialUser = UserInfo("user_1", "one@blesk.sk", "User One", null),
            initialCredits = 10,
            aiExtractor = ai
        )

        vm.scanInvoiceImage(testBitmap())
        testScheduler.runCurrent()
        assertEquals(9, repo.currentStatsSnapshot().credits)

        // Switch user mid-flight before AI returns
        repo.setUser(UserInfo("user_2", "two@blesk.sk", "User Two", null))
        testScheduler.runCurrent()

        gate.complete(
            AiExtractionResult.Success(
                ExtractedInvoiceJson(supplierName = "Tesco", issueDate = "2026-11-17", totalAmount = 12.0)
            )
        )
        advanceUntilIdle()

        assertEquals(1, repo.rollbackCalls)
        assertEquals("user_1", repo.rolledBackReservations.first().first)
        assertEquals(0, repo.commitCalls)
        assertEquals(10, repo.currentStatsSnapshot().credits)
        assertTrue(vm.scanState.value is ScanUiState.Scanning)
    }

    @Test
    fun signOutMidFlight_cancelsActiveScanRollsBackCreditAndClearsCredentialState() = runTest {
        val gate = CompletableDeferred<AiExtractionResult>()
        val ai = FakeInvoiceAiExtractor { gate.await() }
        var clearedCredentialState = false
        val (vm, repo, _) = createHarness(
            initialCredits = 8,
            aiExtractor = ai,
            clearCredentialStateAction = { clearedCredentialState = true }
        )

        vm.scanInvoiceImage(testBitmap())
        testScheduler.runCurrent()
        assertEquals(7, repo.currentStatsSnapshot().credits)

        vm.signOut()
        advanceUntilIdle()

        assertTrue(clearedCredentialState)
        assertEquals(1, repo.signOutCalls)
        assertEquals(1, repo.rollbackCalls)
        assertEquals(8, repo.currentStatsSnapshot().credits)
        assertEquals(ScanUiState.Idle, vm.scanState.value)
    }

    @Test
    fun saveScannedInvoice_archivesReceiptInsertsInvoiceAndResetsScanState() = runTest {
        var archivedId: String? = null
        var onSavedCalled = false
        val (vm, repo, _) = createHarness(
            archiveReceipt = { _, id ->
                archivedId = id
                "/files/receipts/$id.jpg"
            }
        )

        vm.scanInvoiceImage(testBitmap())
        advanceUntilIdle()
        assertTrue(vm.scanState.value is ScanUiState.Success)

        val invoice = Invoice(
            id = "inv_saved_1",
            supplierName = "O2 Business",
            issueDate = "2026-11-17",
            totalAmountCents = 4_200L
        )
        vm.saveScannedInvoice(invoice, testBitmap()) {
            onSavedCalled = true
        }
        advanceUntilIdle()

        assertTrue(onSavedCalled)
        assertEquals("inv_saved_1", archivedId)
        assertEquals(ScanUiState.Idle, vm.scanState.value)
        val saved = repo.currentInvoicesSnapshot().first { it.id == "inv_saved_1" }
        assertEquals("/files/receipts/inv_saved_1.jpg", saved.imageUrl)
    }
}
