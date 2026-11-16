package com.example.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAccessPolicyTest {
    @Test
    fun internalTest_neverCallsCreditReservation() = runBlocking {
        var charged = false
        val result = ScanAccessPolicy(internalTesting = true).reserve("local-user") {
            charged = true
            CreditReservationResult.InsufficientCredits
        }
        assertEquals(CreditReservationResult.FreeInternalTest, result)
        assertFalse(charged)
    }

    @Test
    fun production_preservesEntitlementDecisionAndUserIdentity() = runBlocking {
        var called = false
        val result = ScanAccessPolicy(internalTesting = false).reserve("owner-123") { uid ->
            called = true
            assertEquals("owner-123", uid)
            CreditReservationResult.InsufficientCredits
        }
        assertTrue(called)
        assertEquals(CreditReservationResult.InsufficientCredits, result)
    }
}
