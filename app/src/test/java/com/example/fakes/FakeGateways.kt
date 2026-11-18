package com.example.fakes

import android.app.Activity
import android.graphics.Bitmap
import com.example.data.AiExtractionResult
import com.example.data.BillingGateway
import com.example.data.BillingProduct
import com.example.data.CreditReservationResult
import com.example.data.ExtractedInvoiceJson
import com.example.data.Invoice
import com.example.data.InvoiceAiExtractor
import com.example.data.InvoiceDataGateway
import com.example.data.PlayBillingUiState
import com.example.data.SlovakFinanceUtils
import com.example.data.UserInfo
import com.example.data.UserStats
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class FakeInvoiceDataGateway(
    initialUser: UserInfo? = UserInfo(
        uid = "user_test_1",
        email = "tester@bleskovafaktura.sk",
        displayName = "Test Živnostník",
        photoUrl = null
    ),
    initialInvoices: List<Invoice> = emptyList(),
    initialCredits: Int = 10,
    initialSubscribed: Boolean = false
) : InvoiceDataGateway {

    private val _currentUser = MutableStateFlow(initialUser)
    override val currentUser: StateFlow<UserInfo?> = _currentUser.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    override val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val _invoices = MutableStateFlow(initialInvoices)
    private val _userStats = MutableStateFlow(
        buildStats(
            uid = initialUser?.uid.orEmpty(),
            invoices = initialInvoices,
            credits = initialCredits,
            isSubscribed = initialSubscribed
        )
    )

    var firebaseModeEnabled: Boolean = true
    var activeReservationId: String? = null
    var reserveCalls: Int = 0
    var commitCalls: Int = 0
    var rollbackCalls: Int = 0
    val committedReservations = mutableListOf<Pair<String, String>>()
    val rolledBackReservations = mutableListOf<Pair<String, String>>()
    var signOutCalls: Int = 0
    var deleteAccountResult: Result<Unit> = Result.success(Unit)
    var insertInvoiceResult: Result<Unit> = Result.success(Unit)

    fun setUser(user: UserInfo?) {
        _currentUser.value = user
    }

    fun setSyncError(message: String?) {
        _syncError.value = message
    }

    fun setInvoices(list: List<Invoice>) {
        _invoices.value = list
        recalculateStats()
    }

    fun setCreditsAndSubscription(credits: Int, isSubscribed: Boolean) {
        _userStats.value = _userStats.value.copy(
            credits = credits,
            isSubscribed = isSubscribed
        )
    }

    fun currentStatsSnapshot(): UserStats = _userStats.value

    fun currentInvoicesSnapshot(): List<Invoice> = _invoices.value

    override fun isFirebaseMode(): Boolean = firebaseModeEnabled

    override fun clearSyncError() {
        _syncError.value = null
    }

    override suspend fun signInWithGoogle(idToken: String): Result<UserInfo> {
        val user = UserInfo(
            uid = "google_$idToken",
            email = "google@bleskovafaktura.sk",
            displayName = "Google User",
            photoUrl = null
        )
        _currentUser.value = user
        return Result.success(user)
    }

    override fun signInDemoUser() {
        _currentUser.value = UserInfo(
            uid = "demo_szco_2026",
            email = "demo@bleskovafaktura.local",
            displayName = "Demo Živnostník",
            photoUrl = null
        )
    }

    override suspend fun signOutAndClearLocalCache() {
        signOutCalls += 1
        _syncError.value = null
        _invoices.value = emptyList()
        _currentUser.value = null
    }

    override fun stopCloudSync() = Unit

    override suspend fun initializeUserStats(userId: String) {
        recalculateStats(userId)
    }

    override fun getInvoices(): Flow<List<Invoice>> = _invoices.asStateFlow()

    override suspend fun insertInvoice(invoice: Invoice): Result<Unit> {
        if (insertInvoiceResult.isFailure) return insertInvoiceResult
        val current = _invoices.value.toMutableList()
        val idx = current.indexOfFirst { it.id == invoice.id }
        if (idx >= 0) {
            current[idx] = invoice
        } else {
            current.add(0, invoice)
        }
        _invoices.value = current
        recalculateStats()
        return Result.success(Unit)
    }

    override suspend fun deleteInvoice(id: String) {
        _invoices.value = _invoices.value.filterNot { it.id == id }
        recalculateStats()
    }

    override fun getUserStatsFlow(): Flow<UserStats> = _userStats.asStateFlow()

    override suspend fun reserveScanCredit(ownerUid: String): CreditReservationResult {
        reserveCalls += 1
        val stats = _userStats.value
        if (stats.isSubscribed) return CreditReservationResult.UnlimitedSubscriber
        if (stats.credits <= 0) return CreditReservationResult.InsufficientCredits
        if (!activeReservationId.isNullOrBlank()) return CreditReservationResult.ScanAlreadyInProgress
        val resId = "res_${UUID.randomUUID()}"
        activeReservationId = resId
        _userStats.value = stats.copy(credits = stats.credits - 1)
        return CreditReservationResult.Reserved(resId)
    }

    override suspend fun commitScanReservation(ownerUid: String, reservationId: String) {
        commitCalls += 1
        committedReservations.add(ownerUid to reservationId)
        if (activeReservationId == reservationId) {
            activeReservationId = null
        }
    }

    override suspend fun rollbackScanReservation(ownerUid: String, reservationId: String) {
        rollbackCalls += 1
        rolledBackReservations.add(ownerUid to reservationId)
        if (activeReservationId == reservationId) {
            activeReservationId = null
            _userStats.value = _userStats.value.copy(credits = _userStats.value.credits + 1)
        }
    }

    override suspend fun deleteAccount(targetUid: String?): Result<Unit> {
        if (deleteAccountResult.isSuccess) {
            _invoices.value = emptyList()
            _currentUser.value = null
        }
        return deleteAccountResult
    }

    private fun recalculateStats(uidOverride: String? = null) {
        val uid = uidOverride ?: _currentUser.value?.uid ?: _userStats.value.userId
        val current = _userStats.value
        _userStats.value = buildStats(
            uid = uid,
            invoices = _invoices.value,
            credits = current.credits,
            isSubscribed = current.isSubscribed
        )
    }

    private fun buildStats(
        uid: String,
        invoices: List<Invoice>,
        credits: Int,
        isSubscribed: Boolean
    ): UserStats {
        val totalCents = invoices.sumOf { it.effectiveTotalCents() }
        return UserStats(
            userId = uid,
            credits = credits,
            isSubscribed = isSubscribed,
            totalInvoiced = SlovakFinanceUtils.centsToDouble(totalCents),
            totalInvoicedCents = totalCents,
            invoiceCount = invoices.size
        )
    }
}

class FakeBillingGateway(
    initialPendingCount: Int = 0
) : BillingGateway {

    private val _billingState = MutableStateFlow<PlayBillingUiState>(PlayBillingUiState.Idle)
    override val billingState: StateFlow<PlayBillingUiState> = _billingState.asStateFlow()

    private val _catalogPrices = MutableStateFlow<Map<String, String>>(emptyMap())
    override val catalogPrices: StateFlow<Map<String, String>> = _catalogPrices.asStateFlow()

    private val _pendingUnverifiedCount = MutableStateFlow(initialPendingCount)
    override val pendingUnverifiedCount: StateFlow<Int> = _pendingUnverifiedCount.asStateFlow()

    var startConnectionCalls: Int = 0
    var retryCalls: Int = 0
    var clearMessageCalls: Int = 0
    val launchedProducts = mutableListOf<BillingProduct>()

    fun setBillingState(state: PlayBillingUiState) {
        _billingState.value = state
    }

    fun setCatalogPrices(prices: Map<String, String>) {
        _catalogPrices.value = prices
    }

    fun setPendingCount(count: Int) {
        _pendingUnverifiedCount.value = count
    }

    override fun startConnection() {
        startConnectionCalls += 1
    }

    override fun launchPurchase(activity: Activity?, product: BillingProduct) {
        launchedProducts.add(product)
    }

    override fun retryPendingVerifications() {
        retryCalls += 1
    }

    override fun clearMessage() {
        clearMessageCalls += 1
        _billingState.value = PlayBillingUiState.Idle
    }

    override fun endConnection() = Unit
}

class FakeInvoiceAiExtractor(
    private val extractor: suspend (Bitmap) -> AiExtractionResult = {
        AiExtractionResult.Success(
            ExtractedInvoiceJson(
                supplierName = "Alza.sk s.r.o.",
                issueDate = "2026-11-17",
                totalAmount = 120.0,
                vatAmount = 22.44,
                vatRate = 23.0,
                category = "Technika",
                description = "Príslušenstvo"
            )
        )
    }
) : InvoiceAiExtractor {
    var calls: Int = 0

    override suspend fun extractInvoiceData(bitmap: Bitmap): AiExtractionResult {
        calls += 1
        return extractor(bitmap)
    }
}
