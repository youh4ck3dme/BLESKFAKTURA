package com.example.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.data.AiExtractionResult
import com.example.data.CreditReservationResult
import com.example.data.ExtractedInvoiceJson
import com.example.data.Invoice
import com.example.data.InvoiceAiExtractor
import com.example.data.InvoiceDataGateway
import com.example.data.UserInfo
import com.example.fakes.FakeBillingGateway
import com.example.fakes.FakeInvoiceDataGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class InvoiceViewModelScanTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
    private val data = ExtractedInvoiceJson(
        supplierName = "Synthetic merchant", issueDate = "2026-11-17", totalAmount = 12.30
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        store.clear()
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
        bitmap.recycle()
    }

    private fun viewModel(
        repository: InvoiceDataGateway,
        extractor: InvoiceAiExtractor = InvoiceAiExtractor { AiExtractionResult.Success(data) },
        archive: suspend (Bitmap, String) -> String? = { _, _ -> null },
        local: InvoiceAiExtractor = InvoiceAiExtractor { AiExtractionResult.Success(data) }
    ): InvoiceViewModel = InvoiceViewModel(
        ApplicationProvider.getApplicationContext<Application>(), repository, FakeBillingGateway(),
        extractor, archive, clearCredentialStateAction = {}, localOcrExtractor = local
    ).also { store.put("viewModel", it) }


    @Test
    fun localOcrWithZeroCredits_doesNotCallCloudOrCreditLedger() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway(initialCredits = 0)
        var localCalls = 0
        val vm = viewModel(repository, extractor = InvoiceAiExtractor { error("Cloud must not be called") },
            local = InvoiceAiExtractor { localCalls++; AiExtractionResult.Success(data) })
        vm.scanInvoiceImage(bitmap, localOcr = true); runCurrent()
        assertTrue(vm.scanState.value is ScanUiState.Success); assertEquals(1, localCalls)
        assertEquals(0, repository.reserveCalls); assertEquals(0, repository.commitCalls)
        assertEquals(0, repository.rollbackCalls); assertEquals(0, repository.currentStatsSnapshot().credits)
    }

    @Test
    fun localOcrRejectionAndTimeout_neverFallBackToCloudOrInventRefund() = runTest(dispatcher) {
        for (result in listOf(AiExtractionResult.InvalidDocument("Doklad nie je čitateľný."), AiExtractionResult.Timeout)) {
            val repository = FakeInvoiceDataGateway(initialCredits = 0)
            val vm = viewModel(repository, extractor = InvoiceAiExtractor { error("Cloud must not be called") },
                local = InvoiceAiExtractor { result })
            vm.scanInvoiceImage(bitmap, localOcr = true); runCurrent()
            val state = vm.scanState.value as ScanUiState.Error
            assertFalse(state.message.contains("Kredit bol")); assertFalse(state.message.contains("AI analýza"))
            assertEquals(0, repository.reserveCalls); assertEquals(0, repository.rollbackCalls)
        }
    }

    @Test
    fun localOcrPendingDoubleTap_andCancellationDoNotStartCloudOrDebit() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway(initialCredits = 0)
        val gate = CompletableDeferred<AiExtractionResult>(); var calls = 0
        val vm = viewModel(repository, extractor = InvoiceAiExtractor { error("Cloud must not be called") },
            local = InvoiceAiExtractor { calls++; gate.await() })
        vm.scanInvoiceImage(bitmap, localOcr = true); runCurrent()
        vm.scanInvoiceImage(bitmap, localOcr = true); runCurrent(); assertEquals(1, calls)
        vm.startNewScan(); runCurrent(); gate.complete(AiExtractionResult.Success(data)); runCurrent()
        assertEquals(ScanUiState.Idle, vm.scanState.value); assertEquals(0, repository.reserveCalls)
    }

    @Test
    fun successfulScan_consumesExactlyOneCreditAndCommitsWithoutRefund() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway()
        val vm = viewModel(repository)
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertTrue(vm.scanState.value is ScanUiState.Success)
        assertEquals(9, repository.currentStatsSnapshot().credits)
        assertEquals(1, repository.commitCalls)
        assertEquals(0, repository.rollbackCalls)
    }

    @Test
    fun scanWithoutAccount_doesNotReserveCreditOrCallAi() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway(initialUser = null)
        var aiCalls = 0
        val vm = viewModel(repository, InvoiceAiExtractor { aiCalls++; AiExtractionResult.Success(data) })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertTrue(vm.scanState.value is ScanUiState.Error)
        assertEquals(0, repository.reserveCalls)
        assertEquals(0, aiCalls)
    }

    @Test
    fun insufficientCredits_doesNotCallAiOrCreateRefund() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway(initialCredits = 0)
        var aiCalls = 0
        val vm = viewModel(repository, InvoiceAiExtractor { aiCalls++; AiExtractionResult.Success(data) })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertTrue(vm.scanState.value is ScanUiState.Error)
        assertEquals(0, aiCalls)
        assertEquals(0, repository.rollbackCalls)
        assertEquals(0, repository.currentStatsSnapshot().credits)
    }

    @Test
    fun repeatedScanTap_whileRequestPendingOnlyReservesAndCallsAiOnce() = runTest(dispatcher) {
        val reply = CompletableDeferred<AiExtractionResult>()
        val repository = FakeInvoiceDataGateway()
        var aiCalls = 0
        val vm = viewModel(repository, InvoiceAiExtractor { aiCalls++; reply.await() })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertEquals(1, repository.reserveCalls)
        assertEquals(1, aiCalls)
        reply.complete(AiExtractionResult.Success(data))
        runCurrent()
        assertEquals(9, repository.currentStatsSnapshot().credits)
        assertEquals(1, repository.commitCalls)
    }

    @Test
    fun subscriberInvalidDocument_doesNotDebitOrInventARefund() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway(initialCredits = 0, initialSubscribed = true)
        val vm = viewModel(repository, InvoiceAiExtractor { AiExtractionResult.InvalidDocument("Nejde o doklad.") })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertEquals(0, repository.currentStatsSnapshot().credits)
        assertEquals(0, repository.commitCalls)
        assertEquals(0, repository.rollbackCalls)
        assertEquals("Nejde o doklad.", (vm.scanState.value as ScanUiState.Error).message)
    }

    @Test
    fun rejectedScan_refundsExactlyOnce() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway().apply { firebaseModeEnabled = false }
        val vm = viewModel(repository, InvoiceAiExtractor { AiExtractionResult.ServiceError("AI analýza sa nepodarila.") })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertEquals(10, repository.currentStatsSnapshot().credits)
        assertEquals(1, repository.reserveCalls)
        assertEquals(1, repository.rollbackCalls)
        assertEquals(0, repository.commitCalls)
        assertTrue((vm.scanState.value as ScanUiState.Error).message.contains("Kredit bol vrátený."))
    }

    @Test
    fun cloudRollbackUnitReturn_doesNotClaimConfirmedRefundForTypedOrUnexpectedError() = runTest(dispatcher) {
        for (unexpectedError in listOf(false, true)) {
            val ledger = FakeInvoiceDataGateway().apply { firebaseModeEnabled = true }
            var rollbackRequests = 0
            val repository = object : InvoiceDataGateway by ledger {
                override suspend fun rollbackScanReservation(ownerUid: String, reservationId: String) {
                    // Cloud gateway returns Unit even when the remote credit update failed.
                    rollbackRequests++
                }
            }
            val vm = viewModel(repository, InvoiceAiExtractor {
                if (unexpectedError) throw IllegalStateException("SECRET_MARKER")
                AiExtractionResult.ServiceError("Analýza zlyhala.")
            })
            vm.scanInvoiceImage(bitmap)
            runCurrent()
            assertEquals(1, rollbackRequests)
            assertEquals(9, ledger.currentStatsSnapshot().credits)
            val message = (vm.scanState.value as ScanUiState.Error).message
            assertFalse(message.contains("Kredit bol vrátený."))
            assertTrue(message.contains("Vrátenie kreditu bolo vyžiadané."))
            assertTrue(message.contains("Stav kreditu overte po synchronizácii."))
            assertFalse(message.contains("SECRET_MARKER"))
        }
    }

    @Test
    fun aiDeadlineResult_refundsDebitAndLeavesLoading() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway()
        val vm = viewModel(repository, InvoiceAiExtractor {
            delay(60_000)
            AiExtractionResult.Timeout
        })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertEquals(9, repository.currentStatsSnapshot().credits)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(10, repository.currentStatsSnapshot().credits)
        assertEquals(1, repository.rollbackCalls)
        assertEquals(0, repository.commitCalls)
        assertTrue((vm.scanState.value as ScanUiState.Error).message.contains("60 sekundách"))
    }

    @Test
    fun rollbackFailureAfterApplyingRefund_doesNotAttemptASecondRefundOrClaimConfirmation() = runTest(dispatcher) {
        val ledger = FakeInvoiceDataGateway()
        val repository = object : InvoiceDataGateway by ledger {
            override suspend fun rollbackScanReservation(ownerUid: String, reservationId: String) {
                ledger.rollbackScanReservation(ownerUid, reservationId)
                throw IllegalStateException("SECRET_MARKER RECEIPT_MARKER")
            }
        }
        val vm = viewModel(repository, InvoiceAiExtractor { AiExtractionResult.ServiceError("Analýza zlyhala.") })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertEquals(10, ledger.currentStatsSnapshot().credits)
        assertEquals(1, ledger.rollbackCalls)
        val message = (vm.scanState.value as ScanUiState.Error).message
        assertFalse(message.contains("Kredit bol vrátený."))
        assertFalse(message.contains("MARKER"))
    }

    @Test
    fun reservationFailure_leavesLoadingAndDoesNotExposeExceptionContents() = runTest(dispatcher) {
        val ledger = FakeInvoiceDataGateway()
        val repository = object : InvoiceDataGateway by ledger {
            override suspend fun reserveScanCredit(ownerUid: String): CreditReservationResult {
                throw IllegalStateException("SECRET_MARKER RECEIPT_MARKER")
            }
        }
        var aiCalls = 0
        val vm = viewModel(repository, InvoiceAiExtractor { aiCalls++; AiExtractionResult.Success(data) })
        ShadowLog.clear()
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        val error = vm.scanState.value as ScanUiState.Error
        assertFalse(error.message.contains("MARKER"))
        assertFalse(error.message.contains("Kredit bol vrátený."))
        assertEquals(0, aiCalls)
        assertEquals(10, ledger.currentStatsSnapshot().credits)
        assertEquals(0, ledger.rollbackCalls)
        ShadowLog.getLogsForTag("InvoiceViewModel").forEach {
            assertEquals(null, it.throwable)
            assertFalse(it.msg.orEmpty().contains("MARKER"))
        }
    }

    @Test
    fun cancellationDuringReservation_refundsLateDebitWithoutStartingAi() = runTest(dispatcher) {
        val ledger = FakeInvoiceDataGateway()
        val repository = object : InvoiceDataGateway by ledger {
            override suspend fun reserveScanCredit(ownerUid: String): CreditReservationResult {
                val reservation = ledger.reserveScanCredit(ownerUid)
                delay(1_000)
                return reservation
            }
        }
        var aiCalls = 0
        val vm = viewModel(repository, InvoiceAiExtractor { aiCalls++; AiExtractionResult.Success(data) })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertEquals(9, ledger.currentStatsSnapshot().credits)
        vm.startNewScan()
        advanceTimeBy(1_000)
        runCurrent()
        assertSame(ScanUiState.Idle, vm.scanState.value)
        assertEquals(10, ledger.currentStatsSnapshot().credits)
        assertEquals(1, ledger.rollbackCalls)
        assertEquals(0, ledger.commitCalls)
        assertEquals(0, aiCalls)
    }

    @Test
    fun accountSwitchDuringReservation_refundsOriginalOwnerAndDoesNotStartAi() = runTest(dispatcher) {
        val ledger = FakeInvoiceDataGateway()
        val oldUid = ledger.currentUser.value!!.uid
        val repository = object : InvoiceDataGateway by ledger {
            override suspend fun reserveScanCredit(ownerUid: String): CreditReservationResult {
                val reservation = ledger.reserveScanCredit(ownerUid)
                delay(1_000)
                return reservation
            }
        }
        var aiCalls = 0
        val vm = viewModel(repository, InvoiceAiExtractor { aiCalls++; AiExtractionResult.Success(data) })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        ledger.setUser(UserInfo("other-user", "other@example.test", "Other", null))
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
        assertSame(ScanUiState.Idle, vm.scanState.value)
        assertEquals(0, aiCalls)
        assertEquals(1, ledger.rollbackCalls)
        assertEquals(oldUid, ledger.rolledBackReservations.single().first)
    }

    @Test
    fun cancelledOldRequestFailure_cannotOverwriteNewScanForSameUser() = runTest(dispatcher) {
        val oldReply = CompletableDeferred<Unit>()
        val newReply = CompletableDeferred<AiExtractionResult>()
        var calls = 0
        val repository = FakeInvoiceDataGateway(initialSubscribed = true)
        val vm = viewModel(repository, InvoiceAiExtractor {
            calls++
            if (calls == 1) {
                withContext(NonCancellable) {
                    oldReply.await()
                    throw IllegalStateException("Old request failed after cancellation")
                }
            } else newReply.await()
        })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        vm.startNewScan()
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        oldReply.complete(Unit)
        runCurrent()
        assertTrue(vm.scanState.value is ScanUiState.Scanning)
        newReply.complete(AiExtractionResult.Success(data))
        runCurrent()
        assertTrue(vm.scanState.value is ScanUiState.Success)
        assertEquals(2, calls)
    }

    @Test
    fun signOut_waitsForCancelledScanRefundBeforeClearingAccount() = runTest(dispatcher) {
        val ledger = FakeInvoiceDataGateway()
        val neverReply = CompletableDeferred<AiExtractionResult>()
        val repository = object : InvoiceDataGateway by ledger {
            override suspend fun rollbackScanReservation(ownerUid: String, reservationId: String) {
                delay(500)
                ledger.rollbackScanReservation(ownerUid, reservationId)
            }
            override suspend fun signOutAndClearLocalCache() {
                assertEquals(10, ledger.currentStatsSnapshot().credits)
                ledger.signOutAndClearLocalCache()
            }
        }
        val vm = viewModel(repository, InvoiceAiExtractor { neverReply.await() })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        assertEquals(9, ledger.currentStatsSnapshot().credits)
        vm.signOut()
        runCurrent()
        assertEquals(0, ledger.signOutCalls)
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, ledger.rollbackCalls)
        assertEquals(1, ledger.signOutCalls)
        assertSame(ScanUiState.Idle, vm.scanState.value)
    }

    @Test
    fun failedSave_keepsReviewAndReportsErrorInsteadOfNavigatingAway() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway()
        val vm = viewModel(repository)
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        val review = vm.scanState.value
        repository.insertInvoiceResult = Result.failure(IllegalStateException("SECRET_MARKER RECEIPT_MARKER"))
        var saved = 0
        val errors = mutableListOf<String>()
        vm.saveScannedInvoice(Invoice(id = "retry-same-id"), bitmap, onError = { errors += it }, onSaved = { saved++ })
        runCurrent()
        assertSame(review, vm.scanState.value)
        assertEquals(0, saved)
        assertEquals(1, errors.size)
        assertFalse(errors.single().contains("MARKER"))
        repository.insertInvoiceResult = Result.success(Unit)
        vm.saveScannedInvoice(Invoice(id = "retry-same-id"), bitmap, onError = { errors += it }, onSaved = { saved++ })
        runCurrent()
        assertEquals(1, saved)
        assertEquals(1, repository.currentInvoicesSnapshot().size)
        assertSame(ScanUiState.Idle, vm.scanState.value)
    }

    @Test
    fun archiveFailure_reportsSafeSaveErrorAndKeepsReview() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway()
        val vm = viewModel(repository, archive = { _, _ -> throw IllegalStateException("SECRET_MARKER RECEIPT_MARKER") })
        vm.scanInvoiceImage(bitmap)
        runCurrent()
        val review = vm.scanState.value
        var saved = false
        var error: String? = null
        vm.saveScannedInvoice(Invoice(), bitmap, onError = { error = it }, onSaved = { saved = true })
        runCurrent()
        assertSame(review, vm.scanState.value)
        assertFalse(saved)
        assertTrue(error != null && !error!!.contains("MARKER"))
        assertTrue(repository.currentInvoicesSnapshot().isEmpty())
    }

    @Test
    fun failedAccountDeletion_reportsSafePartialFailureWithoutSdkContents() = runTest(dispatcher) {
        val repository = FakeInvoiceDataGateway().apply {
            deleteAccountResult = Result.failure(IllegalStateException("SECRET_MARKER RECEIPT_MARKER"))
        }
        val vm = viewModel(repository)
        var succeeded = false
        var message: String? = null
        vm.deleteAccount(onSuccess = { succeeded = true }, onError = { message = it })
        runCurrent()
        assertFalse(succeeded)
        assertTrue(message != null)
        assertFalse(message!!.contains("MARKER"))
        assertTrue(message!!.contains("Časť údajov už mohla byť odstránená."))
    }

}
