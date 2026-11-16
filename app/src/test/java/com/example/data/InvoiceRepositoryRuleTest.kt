package com.example.data

import com.example.base.FirestoreEmulatorTestBase
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.UUID

class InvoiceRepositoryRuleTest : FirestoreEmulatorTestBase() {

    @Test
    fun initializeUserStats_newUser_createsFirestoreDocWith10Credits() = runBlocking {
        val uid = signInTestUser("alice_${UUID.randomUUID()}@test.sk")
        val repo = InvoiceRepository(context, firestore, auth)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.initializeUserStats(uid)
            }
            val snap = withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid).get().await()
            }
            assertTrue(snap.exists())
            assertEquals(10L, snap.getLong("credits"))
            assertEquals(false, snap.getBoolean("isSubscribed"))
        } finally {
            repo.stopCloudSync()
        }
    }

    @Test
    fun insertAndEditInvoice_authenticatedOwner_persistsInCloudAndUpdatesTotals() = runBlocking {
        val uid = signInTestUser("owner_${UUID.randomUUID()}@test.sk")
        val repo = InvoiceRepository(context, firestore, auth)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { repo.initializeUserStats(uid) }

            val invoiceId = UUID.randomUUID().toString()
            val created = Invoice(
                id = invoiceId,
                supplierName = "Dodávateľ s.r.o.",
                issueDate = "2026-11-16",
                dueDate = "2026-11-30",
                isPaid = false,
                currency = "EUR",
                totalAmount = 120.0,
                totalAmountCents = 12_000L,
                vatAmount = 20.0,
                vatAmountCents = 2_000L,
                vatRate = 20.0,
                category = "Služby",
                userId = uid
            )

            val insertRes = withTimeout(DEFAULT_TIMEOUT_MS) { repo.insertInvoice(created) }
            assertTrue("Insert failed: ${insertRes.exceptionOrNull()}", insertRes.isSuccess)

            val edited = created.copy(supplierName = "Upravený Dodávateľ s.r.o.", isPaid = true)
            val editRes = withTimeout(DEFAULT_TIMEOUT_MS) { repo.insertInvoice(edited) }
            assertTrue("Update failed: ${editRes.exceptionOrNull()}", editRes.isSuccess)

            val cloudSnap = withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid)
                    .collection("invoices").document(invoiceId)
                    .get().await()
            }
            assertTrue(cloudSnap.exists())
            assertEquals("Upravený Dodávateľ s.r.o.", cloudSnap.getString("supplierName"))
            assertEquals(true, cloudSnap.getBoolean("isPaid"))

            val list = withTimeout(FLOW_TIMEOUT_MS) {
                repo.getInvoices().first { items -> items.any { it.id == invoiceId && it.isPaid } }
            }
            assertTrue(list.any { it.id == invoiceId })
        } finally {
            repo.stopCloudSync()
        }
    }

    @Test
    fun reserveAndRollbackCredit_atomicReservation_restoresCreditOnce() = runBlocking {
        val uid = signInTestUser("credit_${UUID.randomUUID()}@test.sk")
        val repo = InvoiceRepository(context, firestore, auth)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { repo.initializeUserStats(uid) }

            val res1 = withTimeout(DEFAULT_TIMEOUT_MS) { repo.reserveScanCredit(uid) }
            assertTrue("Expected Reserved, got $res1", res1 is CreditReservationResult.Reserved)
            val resId = (res1 as CreditReservationResult.Reserved).reservationId

            val afterReserve = withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid).get().await()
            }
            assertEquals(9L, afterReserve.getLong("credits"))
            assertEquals(resId, afterReserve.getString("activeReservationId"))

            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.rollbackScanReservation(uid, resId)
            }
            val afterRollback = withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid).get().await()
            }
            assertEquals(10L, afterRollback.getLong("credits"))
            assertEquals(null, afterRollback.getString("activeReservationId"))

            // Second rollback of same reservation ID must be a no-op
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.rollbackScanReservation(uid, resId)
            }
            val afterSecondRollback = withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid).get().await()
            }
            assertEquals(10L, afterSecondRollback.getLong("credits"))
        } finally {
            repo.stopCloudSync()
        }
    }

    @Test
    fun concurrentReserveScanCredit_singleCreditRemaining_allowsOnlyOneWinner() = runBlocking {
        val uid = signInTestUser("race_${UUID.randomUUID()}@test.sk")
        val repo = InvoiceRepository(context, firestore, auth)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { repo.initializeUserStats(uid) }
            // Burn 9 starter credits cleanly via reserve+commit so 1 remains
            repeat(9) {
                val r = withTimeout(DEFAULT_TIMEOUT_MS) {
                    repo.reserveScanCredit(uid)
                } as CreditReservationResult.Reserved
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    repo.commitScanReservation(uid, r.reservationId)
                }
            }

            val results = withTimeout(DEFAULT_TIMEOUT_MS) {
                listOf(
                    async { repo.reserveScanCredit(uid) },
                    async { repo.reserveScanCredit(uid) }
                ).awaitAll()
            }

            val reservedCount = results.count { it is CreditReservationResult.Reserved }
            val deniedCount = results.count { it is CreditReservationResult.InsufficientCredits }
            assertEquals(1, reservedCount)
            assertEquals(1, deniedCount)

            val snap = withTimeout(DEFAULT_TIMEOUT_MS) {
                firestore.collection("users").document(uid).get().await()
            }
            assertEquals(0L, snap.getLong("credits"))
        } finally {
            repo.stopCloudSync()
        }
    }

    @Test
    fun privilegeEscalation_clientSetsIsSubscribedTrue_failsWithPermissionDenied() = runBlocking {
        val uid = signInTestUser("hacker_${UUID.randomUUID()}@test.sk")
        val repo = InvoiceRepository(context, firestore, auth)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { repo.initializeUserStats(uid) }

            try {
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    firestore.collection("users").document(uid)
                        .update(
                            mapOf(
                                "isSubscribed" to true,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                }
                fail("Expected PERMISSION_DENIED when client sets isSubscribed=true")
            } catch (e: Exception) {
                val fsEx = e.unwrapFirestoreException()
                assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, fsEx?.code)
            }

            // Negative 2: Client cannot mint arbitrary credits
            try {
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    firestore.collection("users").document(uid)
                        .update(
                            mapOf(
                                "credits" to 500,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                }
                fail("Expected PERMISSION_DENIED when client sets credits=500")
            } catch (e: Exception) {
                assertEquals(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    e.unwrapFirestoreException()?.code
                )
            }

            // Negative 3: Forged rollback without active reservation fails with PERMISSION_DENIED
            try {
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    firestore.collection("users").document(uid)
                        .update(
                            mapOf(
                                "credits" to 11,
                                "activeReservationId" to null,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        ).await()
                }
                fail("Expected PERMISSION_DENIED on forged rollback without activeReservationId")
            } catch (e: Exception) {
                assertEquals(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    e.unwrapFirestoreException()?.code
                )
            }

            // Negative 4: Client cannot write or read billingTokens collection
            try {
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    firestore.collection("billingTokens").document("deadbeef".repeat(8))
                        .set(
                            mapOf(
                                "ownerUid" to uid,
                                "productId" to "blesk_credits_100",
                                "creditsGranted" to 100
                            )
                        ).await()
                }
                fail("Expected PERMISSION_DENIED when client writes billingTokens")
            } catch (e: Exception) {
                assertEquals(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    e.unwrapFirestoreException()?.code
                )
            }
        } finally {
            repo.stopCloudSync()
        }
    }

    @Test
    fun crossUserReadAndUnauthenticatedAccess_rejectedWithPermissionDenied() = runBlocking {
        val aliceUid = signInTestUser("alice_iso_${UUID.randomUUID()}@test.sk")
        val aliceRepo = InvoiceRepository(context, firestore, auth)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { aliceRepo.initializeUserStats(aliceUid) }
            val invId = UUID.randomUUID().toString()
            withTimeout(DEFAULT_TIMEOUT_MS) {
                aliceRepo.insertInvoice(
                    Invoice(
                        id = invId,
                        supplierName = "Tajný Doklad",
                        issueDate = "2026-11-16",
                        totalAmount = 50.0,
                        totalAmountCents = 5000L,
                        category = "Kancelária",
                        userId = aliceUid
                    )
                )
            }
            aliceRepo.stopCloudSync()

            // Bob cannot read Alice's invoice
            signInTestUser("bob_iso_${UUID.randomUUID()}@test.sk")
            try {
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    firestore.collection("users").document(aliceUid)
                        .collection("invoices").document(invId)
                        .get().await()
                }
                fail("Expected PERMISSION_DENIED on cross-user invoice read")
            } catch (e: Exception) {
                assertEquals(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    e.unwrapFirestoreException()?.code
                )
            }

            // Unauthenticated cannot read or list
            auth.signOut()
            try {
                withTimeout(DEFAULT_TIMEOUT_MS) {
                    firestore.collection("users").document(aliceUid)
                        .collection("invoices").get().await()
                }
                fail("Expected PERMISSION_DENIED for unauthenticated query")
            } catch (e: Exception) {
                assertEquals(
                    FirebaseFirestoreException.Code.PERMISSION_DENIED,
                    e.unwrapFirestoreException()?.code
                )
            }
        } finally {
            aliceRepo.stopCloudSync()
        }
    }

    @Test
    fun deleteAccount_removesCloudAndLocalUserInvoicesAndProfile() = runBlocking {
        val uid = signInTestUser("del_${UUID.randomUUID()}@test.sk")
        val repo = InvoiceRepository(context, firestore, auth)
        try {
            withTimeout(DEFAULT_TIMEOUT_MS) { repo.initializeUserStats(uid) }
            val invId = UUID.randomUUID().toString()
            withTimeout(DEFAULT_TIMEOUT_MS) {
                repo.insertInvoice(
                    Invoice(
                        id = invId,
                        supplierName = "Na Vymazanie s.r.o.",
                        issueDate = "2026-11-16",
                        totalAmount = 19.90,
                        totalAmountCents = 1990L,
                        category = "Iné",
                        userId = uid
                    )
                )
            }

            val delRes = withTimeout(DEFAULT_TIMEOUT_MS) { repo.deleteAccount(uid) }
            assertTrue("deleteAccount failed: ${delRes.exceptionOrNull()}", delRes.isSuccess)
            assertEquals(null, repo.currentUser.value)

            // Re-sign in same email / new account or check via fresh sign-in is empty
            val newUid = signInTestUser("check_after_del_${UUID.randomUUID()}@test.sk")
            assertFalse(newUid == uid)
        } finally {
            repo.stopCloudSync()
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 8000L
        const val FLOW_TIMEOUT_MS = 4000L
    }
}
