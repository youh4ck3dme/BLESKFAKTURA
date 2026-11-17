package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlayBillingManagerTest {

    @Test
    fun accountId_matchesBackendOwnershipContract() {
        assertEquals(
            "61ef41ccb77b7d1a9af28d85bb2a687159ed17ea8050e139b514a2ac127ad01f",
            playBillingAccountId("alice_uid")
        )
    }

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun fakePurchase(
        productId: String,
        token: String,
        state: Int,
        acknowledged: Boolean = false
    ): Purchase {
        val json = """
            {
              "orderId": "GPA.1111-2222-3333-44444",
              "packageName": "sk.bizagent.bleskovafaktura",
              "productId": "$productId",
              "purchaseTime": 1763290000000,
              "purchaseState": ${
                  when (state) {
                      Purchase.PurchaseState.PURCHASED -> 0
                      Purchase.PurchaseState.PENDING -> 4 // Billing Library Purchase.java maps JSON 4 -> PENDING
                      else -> 1
                  }
              },
              "purchaseToken": "$token",
              "acknowledged": $acknowledged
            }
        """.trimIndent()
        return Purchase(json, "fake_sig")
    }

    @Test
    fun purchasedInApp_verifiesWithBackendAndTransitionsToVerified() = runTest {
        val capturedCalls = mutableListOf<Triple<String, String, String>>()
        val manager = PlayBillingManager(
            context = context,
            verifier = BillingPurchaseVerifier { pkg, productId, token ->
                capturedCalls.add(Triple(pkg, productId, token))
                Result.success(
                    BillingVerificationResponse(
                        status = "GRANTED",
                        idempotent = false,
                        productId = productId,
                        creditsGranted = 50,
                        isSubscribed = false
                    )
                )
            },
            billingScope = TestScope(testScheduler),
            packageNameProvider = { "sk.bizagent.bleskovafaktura" },
            enableBillingClient = false
        )

        manager.processUpdatedPurchases(
            responseCode = BillingClient.BillingResponseCode.OK,
            debugMessage = "",
            purchases = listOf(
                fakePurchase("blesk_credits_50", "tok_valid_50_credits_1234", Purchase.PurchaseState.PURCHASED)
            )
        )

        assertEquals(1, capturedCalls.size)
        assertEquals(
            Triple("sk.bizagent.bleskovafaktura", "blesk_credits_50", "tok_valid_50_credits_1234"),
            capturedCalls.first()
        )
        val state = manager.billingState.value
        assertTrue("Expected Verified, got $state", state is PlayBillingUiState.Verified)
        val verified = state as PlayBillingUiState.Verified
        assertEquals(50, verified.creditsGranted)
        assertFalse(verified.isSubscribed)
        assertFalse(verified.idempotent)
    }

    @Test
    fun pendingPurchase_doesNotCallBackendVerifierAndShowsInfo() = runTest {
        var calls = 0
        val manager = PlayBillingManager(
            context = context,
            verifier = BillingPurchaseVerifier { _, _, _ ->
                calls += 1
                error("Verifier must not be called for PENDING state")
            },
            billingScope = TestScope(testScheduler),
            packageNameProvider = { "sk.bizagent.bleskovafaktura" },
            enableBillingClient = false
        )

        manager.processUpdatedPurchases(
            responseCode = BillingClient.BillingResponseCode.OK,
            debugMessage = "",
            purchases = listOf(
                fakePurchase("blesk_credits_10", "tok_pending_1234567890", Purchase.PurchaseState.PENDING)
            )
        )

        assertEquals(0, calls)
        assertTrue(manager.billingState.value is PlayBillingUiState.Info)
        assertEquals(1, manager.pendingUnverifiedCount.value)
    }

    @Test
    fun userCanceledPurchase_resetsToIdleWithoutCallingBackend() = runTest {
        var calls = 0
        val manager = PlayBillingManager(
            context = context,
            verifier = BillingPurchaseVerifier { _, _, _ ->
                calls += 1
                error("Verifier must not be called on cancel")
            },
            billingScope = TestScope(testScheduler),
            packageNameProvider = { "sk.bizagent.bleskovafaktura" },
            enableBillingClient = false
        )

        manager.processUpdatedPurchases(
            responseCode = BillingClient.BillingResponseCode.USER_CANCELED,
            debugMessage = "User canceled",
            purchases = null
        )

        assertEquals(0, calls)
        assertEquals(PlayBillingUiState.Idle, manager.billingState.value)
        assertEquals(0, manager.pendingUnverifiedCount.value)
    }

    @Test
    fun backendRejectionOrCrossUserReplay_transitionsToError() = runTest {
        val manager = PlayBillingManager(
            context = context,
            verifier = BillingPurchaseVerifier { _, _, _ ->
                Result.failure(IllegalStateException("Nákup je už priradený inému účtu."))
            },
            billingScope = TestScope(testScheduler),
            packageNameProvider = { "sk.bizagent.bleskovafaktura" },
            enableBillingClient = false
        )

        manager.processUpdatedPurchases(
            responseCode = BillingClient.BillingResponseCode.OK,
            debugMessage = "",
            purchases = listOf(
                fakePurchase("blesk_credits_100", "tok_stolen_replay_12345", Purchase.PurchaseState.PURCHASED)
            )
        )

        val state = manager.billingState.value
        assertTrue(state is PlayBillingUiState.Error)
        assertTrue((state as PlayBillingUiState.Error).message.contains("inému účtu"))
        assertEquals(1, manager.pendingUnverifiedCount.value)
    }

    @Test
    fun idempotentAlreadyProcessedAndActiveSubscription_emitVerifiedState() = runTest {
        var mode = "ALREADY_PROCESSED"
        val manager = PlayBillingManager(
            context = context,
            verifier = BillingPurchaseVerifier { _, productId, _ ->
                if (mode == "ALREADY_PROCESSED") {
                    Result.success(
                        BillingVerificationResponse(
                            status = "ALREADY_PROCESSED",
                            idempotent = true,
                            productId = productId,
                            creditsGranted = 0,
                            isSubscribed = false
                        )
                    )
                } else {
                    Result.success(
                        BillingVerificationResponse(
                            status = "SUBSCRIPTION_ACTIVE",
                            idempotent = false,
                            productId = productId,
                            creditsGranted = 0,
                            isSubscribed = true
                        )
                    )
                }
            },
            billingScope = TestScope(testScheduler),
            packageNameProvider = { "sk.bizagent.bleskovafaktura" },
            enableBillingClient = false
        )

        manager.handleSinglePurchase("blesk_credits_50", "tok_idem_1234567890", Purchase.PurchaseState.PURCHASED)
        val idemState = manager.billingState.value as PlayBillingUiState.Verified
        assertTrue(idemState.idempotent)
        assertEquals(0, idemState.creditsGranted)
        assertEquals(0, manager.pendingUnverifiedCount.value)

        mode = "SUBSCRIPTION_ACTIVE"
        manager.handleSinglePurchase("blesk_premium_monthly", "tok_sub_active_123456", Purchase.PurchaseState.PURCHASED)
        val subState = manager.billingState.value as PlayBillingUiState.Verified
        assertTrue(subState.isSubscribed)
        assertEquals("blesk_premium_monthly", subState.productId)
        assertEquals(0, manager.pendingUnverifiedCount.value)
    }

    @Test
    fun concurrentCallsForSamePurchaseToken_deduplicatedInFlight() = runTest {
        val gate = CompletableDeferred<Unit>()
        var invocationCount = 0
        val manager = PlayBillingManager(
            context = context,
            verifier = BillingPurchaseVerifier { _, productId, _ ->
                invocationCount += 1
                gate.await()
                Result.success(
                    BillingVerificationResponse(
                        status = "GRANTED",
                        idempotent = false,
                        productId = productId,
                        creditsGranted = 10,
                        isSubscribed = false
                    )
                )
            },
            billingScope = TestScope(testScheduler),
            packageNameProvider = { "sk.bizagent.bleskovafaktura" },
            enableBillingClient = false
        )

        val job1 = async {
            manager.handleSinglePurchase("blesk_credits_10", "tok_dup_flight_123456", Purchase.PurchaseState.PURCHASED)
        }
        val job2 = async {
            manager.handleSinglePurchase("blesk_credits_10", "tok_dup_flight_123456", Purchase.PurchaseState.PURCHASED)
        }
        testScheduler.advanceUntilIdle()
        assertEquals(1, invocationCount)
        assertTrue(manager.billingState.value is PlayBillingUiState.PendingVerification)
        assertEquals(1, manager.pendingUnverifiedCount.value)

        gate.complete(Unit)
        listOf(job1, job2).awaitAll()
        assertEquals(1, invocationCount)
        assertTrue(manager.billingState.value is PlayBillingUiState.Verified)
        assertEquals(0, manager.pendingUnverifiedCount.value)
    }
}
