package com.example.data

import android.app.Activity
import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface InvoiceDataGateway {
    val currentUser: StateFlow<UserInfo?>
    val syncError: StateFlow<String?>
    fun isFirebaseMode(): Boolean
    fun clearSyncError()
    suspend fun signInWithGoogle(idToken: String): Result<UserInfo>
    fun signInDemoUser()
    suspend fun signOutAndClearLocalCache()
    fun stopCloudSync()
    suspend fun initializeUserStats(userId: String)
    fun getInvoices(): Flow<List<Invoice>>
    suspend fun insertInvoice(invoice: Invoice): Result<Unit>
    suspend fun deleteInvoice(id: String)
    fun getUserStatsFlow(): Flow<UserStats>
    suspend fun reserveScanCredit(ownerUid: String): CreditReservationResult
    suspend fun commitScanReservation(ownerUid: String, reservationId: String)
    suspend fun rollbackScanReservation(ownerUid: String, reservationId: String)
    suspend fun deleteAccount(targetUid: String? = null): Result<Unit>
}

interface BillingGateway {
    val billingState: StateFlow<PlayBillingUiState>
    val catalogPrices: StateFlow<Map<String, String>>
    val pendingUnverifiedCount: StateFlow<Int>
    fun startConnection()
    fun launchPurchase(activity: Activity?, product: BillingProduct)
    fun retryPendingVerifications()
    fun clearMessage()
    fun endConnection()
}

fun interface InvoiceAiExtractor {
    suspend fun extractInvoiceData(bitmap: Bitmap): AiExtractionResult
}
