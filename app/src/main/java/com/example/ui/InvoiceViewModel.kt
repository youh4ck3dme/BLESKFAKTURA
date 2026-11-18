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
import kotlinx.coroutines.delay
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
    }
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

    init {
        viewModelScope.launch {
            currentUser.collectLatest { user ->
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
        activeScanJob?.cancel()
        activeScanJob = null
        _scanState.value = ScanUiState.Idle
        viewModelScope.launch {
            withContext(NonCancellable) {
                clearCredentialStateAction()
                repository.signOutAndClearLocalCache()
            }
        }
    }

    fun deleteAccount(
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        activeScanJob?.cancel()
        activeScanJob = null
        _scanState.value = ScanUiState.Idle
        viewModelScope.launch {
            val result = repository.deleteAccount()
            if (result.isSuccess) {
                withContext(NonCancellable) {
                    clearCredentialStateAction()
                }
                onSuccess()
            } else {
                val msg = result.exceptionOrNull()?.localizedMessage
                    ?: "Účet sa nepodarilo vymazať. Ak ste sa neprihlásili nedávno, odhláste sa, znova prihláste cez Google a operáciu zopakujte."
                onError(msg)
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
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val archivedPath = if (bitmap != null) {
                archiveReceipt(bitmap, invoice.id)
            } else {
                null
            }
            val finalInvoice = invoice.copy(imageUrl = archivedPath ?: invoice.imageUrl)
            repository.insertInvoice(finalInvoice)
            _scanState.value = ScanUiState.Idle
            onSaved()
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

    fun startNewScan() {
        activeScanJob?.cancel()
        activeScanJob = null
        _scanState.value = ScanUiState.Idle
    }

    fun scanInvoiceImage(bitmap: Bitmap) {
        if (activeScanJob?.isActive == true) {
            return
        }
        val requestUid = currentUser.value?.uid ?: run {
            _scanState.value = ScanUiState.Error("Pred skenovaním sa musíte prihlásiť.")
            return
        }

        activeScanJob = viewModelScope.launch {
            _scanState.value = ScanUiState.Scanning("Bleskovo kontrolujem kredity...")

            val reservation = repository.reserveScanCredit(requestUid)
            val reservationId = when (reservation) {
                is CreditReservationResult.Reserved -> reservation.reservationId
                is CreditReservationResult.UnlimitedSubscriber -> null
                is CreditReservationResult.InsufficientCredits -> {
                    if (currentUser.value?.uid == requestUid) {
                        _scanState.value = ScanUiState.Error(
                            "Nemáte dostatok kreditov na skenovanie. Zakúpte si kredity alebo Blesk Premium v sekcii Predplatné."
                        )
                    }
                    return@launch
                }
                is CreditReservationResult.ScanAlreadyInProgress -> {
                    if (currentUser.value?.uid == requestUid) {
                        _scanState.value = ScanUiState.Error(
                            "Už prebieha aktívna analýza dokladu. Počkajte prosím na jej dokončenie."
                        )
                    }
                    return@launch
                }
            }

            val progressJob = launch {
                for (msg in scannerMessages) {
                    if (currentUser.value?.uid == requestUid) {
                        _scanState.value = ScanUiState.Scanning(msg)
                    }
                    delay(1200)
                }
            }

            try {
                val result = aiExtractor.extractInvoiceData(bitmap)

                // Guard against account switch / signOut during async network call (BF-08)
                if (currentUser.value?.uid != requestUid) {
                    if (reservationId != null) {
                        withContext(NonCancellable) {
                            repository.rollbackScanReservation(requestUid, reservationId)
                        }
                    }
                    return@launch
                }

                when (result) {
                    is AiExtractionResult.Success -> {
                        if (reservationId != null) {
                            repository.commitScanReservation(requestUid, reservationId)
                        }
                        _scanState.value = ScanUiState.Success(result.data, bitmap)
                    }
                    is AiExtractionResult.InvalidDocument -> {
                        if (reservationId != null) {
                            repository.rollbackScanReservation(requestUid, reservationId)
                        }
                        val refundNote = if (reservationId != null) " Kredit bol vrátený." else ""
                        _scanState.value = ScanUiState.Error("${result.message}$refundNote")
                    }
                    is AiExtractionResult.Timeout -> {
                        if (reservationId != null) {
                            repository.rollbackScanReservation(requestUid, reservationId)
                        }
                        val refundNote = if (reservationId != null) " Kredit bol vrátený." else ""
                        _scanState.value = ScanUiState.Error(
                            "AI analýza vypršala po 60 sekundách. Skúste to prosím znova.$refundNote"
                        )
                    }
                    is AiExtractionResult.ServiceError -> {
                        if (reservationId != null) {
                            repository.rollbackScanReservation(requestUid, reservationId)
                        }
                        val refundNote = if (reservationId != null) " Kredit bol vrátený." else ""
                        _scanState.value = ScanUiState.Error("${result.message}$refundNote")
                    }
                }
            } catch (e: CancellationException) {
                if (reservationId != null) {
                    withContext(NonCancellable) {
                        repository.rollbackScanReservation(requestUid, reservationId)
                    }
                }
                throw e
            } catch (e: Exception) {
                if (reservationId != null) {
                    withContext(NonCancellable) {
                        repository.rollbackScanReservation(requestUid, reservationId)
                    }
                }
                if (currentUser.value?.uid == requestUid) {
                    val refundNote = if (reservationId != null) " Kredit bol vrátený." else ""
                    _scanState.value = ScanUiState.Error(
                        "Neočakávaná chyba pri spracovaní dokladu: ${e.localizedMessage ?: "neznáma chyba"}.$refundNote"
                    )
                }
            } finally {
                progressJob.cancel()
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
