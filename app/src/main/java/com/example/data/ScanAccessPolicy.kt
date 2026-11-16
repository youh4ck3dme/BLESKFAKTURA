package com.example.data

/** Separates the free internal test from paid entitlement handling. */
internal class ScanAccessPolicy(private val internalTesting: Boolean) {
    suspend fun reserve(
        ownerUid: String,
        reserveCredit: suspend (String) -> CreditReservationResult
    ): CreditReservationResult = if (internalTesting) {
        CreditReservationResult.FreeInternalTest
    } else {
        reserveCredit(ownerUid)
    }
}
