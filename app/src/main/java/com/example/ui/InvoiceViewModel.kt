package com.example.ui

import android.app.Activity
import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AiExtractionResult
import com.example.data.BillingGateway
import com.example.data.BillingProduct
import com.example.data.CreditReservationResult
import com.example.data.ExtractedInvoiceJson
import com.example.data.Invoice
import com.example.data.InvoiceAiExtractor
import com.example.data.InvoiceAiService
import com.example.data.LocalReceiptOcr
import com.example.data.InvoiceDataGateway
import com.example.data.InvoiceRepository
import com.example.data.PlayBillingManager
import com.example.data.PlayBillingUiState
import com.example.data.ReceiptImageProcessor
import com.example.data.SlovakFinanceUtils
import com.example.data.UserInfo
import com.example.data.UserStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class DashboardSummaryUiState(
    val recentInvoices: List<Invoice> = emptyList(),
    val recentTotalCents: Long = 0L,
    val unpaidCount: Int = 0,
    val unpaidTotalCents: Long = 0L,
    val overdueCount: Int = 0,
    val pendingPurchaseCount: Int = 0,
    val totalPendingCount: Int = 0
)

internal fun computeDashboardSummary(
    invoices: List<Invoice>,
    pendingPurchaseCount: Int,
    todayUtcMillis: Long = SlovakFinanceUtils.todayUtcMidnightMillis()
): DashboardSummaryUiState {
    val recentInvoices = invoices
        .sortedWith(
            compareByDescending<Invoice> {
                SlovakFinanceUtils.normalizeIsoDate(it.issueDate) ?: ""
            }.thenByDescending { it.createdAt }
        )
        .take(3)
    val recentTotalCents = recentInvoices.sumOf { it.effectiveTotalCents() }

    val unpaidInvoices = invoices.filter { !it.isPaid }
    val unpaidTotalCents = unpaidInvoices.sumOf { it.effectiveTotalCents() }
    val overdueCount = unpaidInvoices.count { invoice ->
        val dueRaw = invoice.dueDate
        if (dueRaw.isNullOrBlank()) {
            false
        } else {
            val dueMillis = SlovakFinanceUtils.parseDateUtcMidnightMillis(dueRaw)
            dueMillis != null && SlovakFinanceUtils.calendarDaysBetweenUtc(todayUtcMillis, dueMillis) < 0
        }
    }
    val safePendingPurchases = pendingPurchaseCount.coerceAtLeast(0)

    return DashboardSummaryUiState(
        recentInvoices = recentInvoices,
        recentTotalCents = recentTotalCents,
        unpaidCount = unpaidInvoices.size,
        unpaidTotalCents = unpaidTotalCents,
        overdueCount = overdueCount,
        pendingPurchaseCount = safePendingPurchases,
        totalPendingCount = unpaidInvoices.size + safePendingPurchases
    )
}

sealed interface ScanUiState {
    data object Idle : ScanUiState
    data class Scanning(val progressMessage: String) : ScanUiState
    data class Success(val extractedData: ExtractedInvoiceJson, val bitmap: Bitmap) : ScanUiState
    data class Error(val message: String) : ScanUiState
}

@OptIn(ExperimentalCoroutinesApi::class)
class InvoiceViewModel(
    application: Application,
    private val repository: InvoiceDataGateway,
    private val playBillingManager: BillingGateway,
    private val aiExtractor: InvoiceAiExtractor,
    private val archiveReceipt: suspend (Bitmap, String) -> String? = { bitmap, id ->
        ReceiptImageProcessor.archiveReceiptBitmap(application, bitmap, id)
    },
    private val clearCredentialStateAction: suspend () -> Unit = {
        runCatching {
            CredentialManager.create(application).clearCredentialState(ClearCredentialStateRequest())
        }.onFailure { e ->
            Log.w("InvoiceViewModel", "clearCredentialState skipped: ${e.message}")
        }
    },
    private val localOcrExtractor: InvoiceAiExtractor = LocalReceiptOcr
) : AndroidViewModel(application) {
    constructor(application: Application) : this(
        application = application,
        repository = InvoiceRepository(application),
        playBillingManager = PlayBillingManager(application),
        aiExtractor = InvoiceAiService
    )

    val currentUser: StateFlow<UserInfo?> = repository.currentUser
    val syncError: StateFlow<String?> = repository.syncError

    val billingState: StateFlow<PlayBillingUiState> = playBillingManager.billingState
    val catalogPrices: StateFlow<Map<String, String>> = playBillingManager.catalogPrices
    val pendingUnverifiedPurchaseCount: StateFlow<Int> = playBillingManager.pendingUnverifiedCount

    val invoices: StateFlow<List<Invoice>> = currentUser
        .flatMapLatest { user ->
            if (user != null) {
                repository.getInvoices()
            } else {
                flowOf(emptyList())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val dashboardSummary: StateFlow<DashboardSummaryUiState> = combine(
        invoices,
        pendingUnverifiedPurchaseCount
    ) { invoiceList, pendingPurchases ->
        computeDashboardSummary(
            invoices = invoiceList,
            pendingPurchaseCount = pendingPurchases
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = DashboardSummaryUiState()
    )

    val userStats: StateFlow<UserStats> = currentUser
        .flatMapLatest { user ->
            if (user != null) {
                repository.getUserStatsFlow()
            } else {
                flowOf(UserStats())
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = UserStats()
        )

    private val _scanState = MutableStateFlow<ScanUiState>(ScanUiState.Idle)
    val scanState: StateFlow<ScanUiState> = _scanState.asStateFlow()

    var shouldAutoLaunchCamera = false

    val isFirebaseMode: Boolean = repository.isFirebaseMode()

    private var activeScanJob: Job? = null
    private var scanGeneration = 0L
    private var observedUserUid = currentUser.value?.uid

    init {
        viewModelScope.launch {
            currentUser.collectLatest { user ->
                if (user?.uid != observedUserUid) {
                    observedUserUid = user?.uid
                    startNewScan()
                }
                if (user != null) {
                    repository.initializeUserStats(user.uid)
                } else {
                    repository.stopCloudSync()
                }
            }
        }
    }

    fun clearSyncError() {
        repository.clearSyncError()
    }

    // --- Authentication Actions ---

    fun signInWithGoogle(idToken: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = repository.signInWithGoogle(idToken)
            onResult(result.isSuccess)
        }
    }

    fun signInDemoUser() {
        repository.signInDemoUser()
    }

    fun signOut() {
        val scanJob = invalidateScan()
        viewModelScope.launch {
            withContext(NonCancellable) {
                // Settle a debit before authentication/local data are cleared.
                scanJob?.join()
                clearCredentialStateAction()
                repository.signOutAndClearLocalCache()
            }
        }
    }

    fun deleteAccount(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val scanJob = invalidateScan()
        viewModelScope.launch {
            withContext(NonCancellable) { scanJob?.join() }
            val result = repository.deleteAccount()
            if (result.isSuccess) {
                withContext(NonCancellable) {
                    clearCredentialStateAction()
                }
                onSuccess()
            } else {
                onError(
                    "Účet sa nepodarilo úplne vymazať. Časť údajov už mohla byť odstránená. " +
                        "Skontrolujte pripojenie, prípadne sa znova prihláste, a výmaz zopakujte."
                )
            }
        }
    }

    // --- Invoices Management Actions ---

    fun saveInvoice(invoice: Invoice) {
        viewModelScope.launch {
            repository.insertInvoice(invoice)
        }
    }

    fun saveScannedInvoice(
        invoice: Invoice,
        bitmap: Bitmap?,
        onError: (String) -> Unit = {},
        onSaved: () -> Unit
    ) {
        val requestUid = currentUser.value?.uid
        val requestGeneration = scanGeneration
        if (requestUid == null) {
            onError("Pred uložením dokladu sa musíte prihlásiť.")
            return
        }
        viewModelScope.launch {
            try {
                val archivedPath = bitmap?.let { archiveReceipt(it, invoice.id) }
                currentCoroutineContext().ensureActive()
                if (currentUser.value?.uid != requestUid || scanGeneration != requestGeneration) return@launch
                val finalInvoice = invoice.copy(imageUrl = archivedPath ?: invoice.imageUrl)
                repository.insertInvoice(finalInvoice).getOrThrow()
                currentCoroutineContext().ensureActive()
                if (currentUser.value?.uid != requestUid || scanGeneration != requestGeneration) return@launch
                _scanState.value = ScanUiState.Idle
                onSaved()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("InvoiceViewModel", "Invoice save failed: ${e.javaClass.simpleName}")
                if (currentUser.value?.uid == requestUid && scanGeneration == requestGeneration) {
                    onError("Uloženie dokladu sa nepodarilo dokončiť. Údaje zostali v revízii; skúste uloženie znova.")
                }
            }
        }
    }

    fun deleteInvoice(id: String) {
        viewModelScope.launch {
            repository.deleteInvoice(id)
        }
    }

    // --- Scanner / AI Extraction Actions ---

    private val scannerMessages = listOf(
        "Blesk štartuje AI analyzátor...",
        "Čítame drobné písmo na doklade...",
        "Počítame DPH a celkovú sumu...",
        "Identifikujeme dodávateľa a IČO...",
        "Bleskovo kategorizujeme váš výdavok...",
        "Overujeme dátum a sumy dokladu..."
    )

    private fun invalidateScan(): Job? {
        scanGeneration += 1
        val previous = activeScanJob
        activeScanJob = null
        previous?.cancel()
        _scanState.value = ScanUiState.Idle
        return previous
    }

    fun startNewScan() {
        invalidateScan()
    }

    fun scanInvoiceImage(bitmap: Bitmap, localOcr: Boolean = false) {
        if (activeScanJob?.isActive == true) return
        val requestUid = currentUser.value?.uid ?: run {
            _scanState.value = ScanUiState.Error("Pred skenovaním sa musíte prihlásiť.")
            return
        }
        val generation = ++scanGeneration
        fun ownsScan() = generation == scanGeneration && currentUser.value?.uid == requestUid

        activeScanJob = viewModelScope.launch {
            var reservationId: String? = null
            var committed = false
            var refundAttempted = false
            var refunded = false
            var progressJob: Job? = null

            suspend fun refundCredit() {
                val id = reservationId ?: return
                if (committed || refundAttempted) return
                refundAttempted = true
                try {
                    withContext(NonCancellable) {
                        repository.rollbackScanReservation(requestUid, id)
                    }
                    refunded = true
                } catch (e: Exception) {
                    Log.e("InvoiceViewModel", "Scan credit rollback failed: ${e.javaClass.simpleName}")
                }
            }

            fun refundNote(): String {
                if (!refunded) return ""
                return if (!repository.isFirebaseMode() || requestUid.startsWith("demo_")) {
                    " Kredit bol vrátený."
                } else {
                    " Vrátenie kreditu bolo vyžiadané. Stav kreditu overte po synchronizácii."
                }
            }

            try {
                _scanState.value = ScanUiState.Scanning(if (localOcr) "Pripravujem lokálne čítanie..." else "Bleskovo kontrolujem kredity...")
                // A reservation can finish after cancellation. Obtain its ID so the
                // finally block can refund it without starting another AI request.
                val reservation = withContext(NonCancellable) {
                    if (localOcr) CreditReservationResult.FreeLocalScan else repository.reserveScanCredit(requestUid)
                }
                reservationId = (reservation as? CreditReservationResult.Reserved)?.reservationId
                currentCoroutineContext().ensureActive()
                if (!ownsScan()) return@launch
                when (reservation) {
                    is CreditReservationResult.InsufficientCredits -> {
                        _scanState.value = ScanUiState.Error(
                            "Nemáte dostatok kreditov na skenovanie. Zakúpte si kredity alebo Blesk Premium v sekcii Predplatné."
                        )
                        return@launch
                    }
                    is CreditReservationResult.ScanAlreadyInProgress -> {
                        _scanState.value = ScanUiState.Error(
                            "Už prebieha aktívna analýza dokladu. Počkajte prosím na jej dokončenie."
                        )
                        return@launch
                    }
                    else -> Unit
                }

                progressJob = launch {
                    for (message in if (localOcr) listOf("Čítam text v zariadení...", "Pripravujem údaje na kontrolu...") else scannerMessages) {
                        if (ownsScan()) _scanState.value = ScanUiState.Scanning(message)
                        delay(1200)
                    }
                }
                val result = (if (localOcr) localOcrExtractor else aiExtractor).extractInvoiceData(bitmap)
                currentCoroutineContext().ensureActive()
                if (!ownsScan()) return@launch
                when (result) {
                    is AiExtractionResult.Success -> {
                        reservationId?.let { id ->
                            withContext(NonCancellable) {
                                repository.commitScanReservation(requestUid, id)
                                committed = true
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        if (ownsScan()) _scanState.value = ScanUiState.Success(result.data, bitmap)
                    }
                    else -> {
                        refundCredit()
                        currentCoroutineContext().ensureActive()
                        if (ownsScan()) {
                            val message = when (result) {
                                is AiExtractionResult.InvalidDocument -> result.message
                                is AiExtractionResult.Timeout -> if (localOcr) "Lokálne čítanie vypršalo po 60 sekundách. Skúste to znova." else "AI analýza vypršala po 60 sekundách. Skúste to prosím znova."
                                is AiExtractionResult.ServiceError -> result.message
                                else -> error("Unhandled AI result")
                            }
                            _scanState.value = ScanUiState.Error("$message${refundNote()}")
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                refundCredit()
                Log.e("InvoiceViewModel", "Invoice scan failed: ${e.javaClass.simpleName}")
                if (ownsScan()) {
                    _scanState.value = ScanUiState.Error(
                        "Neočakávaná chyba pri spracovaní dokladu. Skúste to prosím znova.${refundNote()}"
                    )
                }
            } finally {
                progressJob?.cancel()
                refundCredit()
            }
        }
    }

    // --- Google Play Billing (BF-03) ---

    fun startBillingConnection() {
        playBillingManager.startConnection()
    }

    fun launchBillingPurchase(activity: Activity?, product: BillingProduct) {
        playBillingManager.launchPurchase(activity, product)
    }

    fun retryPendingBillingVerifications() {
        playBillingManager.retryPendingVerifications()
    }

    fun clearBillingMessage() {
        playBillingManager.clearMessage()
    }

    override fun onCleared() {
        super.onCleared()
        playBillingManager.endConnection()
        repository.stopCloudSync()
    }
}
