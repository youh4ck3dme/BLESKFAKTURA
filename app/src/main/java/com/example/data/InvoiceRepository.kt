package com.example.data

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.R
import com.example.ui.PdfExporter
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.UUID

data class UserInfo(
    val uid: String,
    val email: String?,
    val displayName: String?,
    val photoUrl: String?
)

sealed interface CreditReservationResult {
    data class Reserved(val reservationId: String) : CreditReservationResult
    data object UnlimitedSubscriber : CreditReservationResult
    data object InsufficientCredits : CreditReservationResult
    data object ScanAlreadyInProgress : CreditReservationResult
}

class InvoiceRepository(
    context: Context,
    private val injectedFirestore: FirebaseFirestore? = null,
    private val injectedAuth: FirebaseAuth? = null
) : InvoiceDataGateway {
    private val appContext = context.applicationContext
    private val database = AppDatabase.getDatabase(appContext)
    private val invoiceDao = database.invoiceDao()
    private val userStatsDao = database.userStatsDao()
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val creditMutex = Mutex()

    private var userStatsListener: ListenerRegistration? = null
    private var invoicesListener: ListenerRegistration? = null
    private var syncedUid: String? = null

    private val _currentUser = MutableStateFlow<UserInfo?>(null)
    override val currentUser: StateFlow<UserInfo?> = _currentUser.asStateFlow()

    private val _syncError = MutableStateFlow<String?>(null)
    override val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private val firebaseAuth: FirebaseAuth? by lazy {
        injectedAuth ?: try {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext)
            }
            FirebaseAuth.getInstance()
        } catch (e: Exception) {
            Log.e(TAG, "FirebaseAuth initialization failed: ${e.message}")
            null
        }
    }

    private val firestoreDb: FirebaseFirestore? by lazy {
        injectedFirestore ?: try {
            if (FirebaseApp.getApps(appContext).isEmpty()) {
                FirebaseApp.initializeApp(appContext)
            }
            val databaseId = appContext.getString(R.string.firestore_database_id)
            FirebaseFirestore.getInstance(databaseId)
        } catch (e: Exception) {
            Log.e(TAG, "Firestore initialization failed: ${e.message}")
            null
        }
    }

    private val isFirebaseReady: Boolean
        get() = firebaseAuth != null && firestoreDb != null

    init {
        val activeAuth = firebaseAuth
        if (activeAuth != null && firestoreDb != null) {
            try {
                val firebaseUser = activeAuth.currentUser
                if (firebaseUser != null) {
                    _currentUser.value = UserInfo(
                        uid = firebaseUser.uid,
                        email = firebaseUser.email,
                        displayName = firebaseUser.displayName,
                        photoUrl = firebaseUser.photoUrl?.toString()
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking active Firebase session", e)
            }
        }
    }

    override fun isFirebaseMode(): Boolean = isFirebaseReady

    override fun clearSyncError() {
        _syncError.value = null
    }

    // --- Authentication ---

    override suspend fun signInWithGoogle(idToken: String): Result<UserInfo> = withContext(Dispatchers.IO) {
        val auth = firebaseAuth
        if (auth == null || firestoreDb == null) {
            return@withContext Result.failure(IllegalStateException("Firebase nie je v tejto zostave nakonfigurovaný."))
        }

        try {
            val credential = GoogleAuthProvider.getCredential(idToken, null)
            val authResult = auth.signInWithCredential(credential).await()
            val firebaseUser = authResult.user ?: throw IllegalStateException("Auth vrátilo prázdneho používateľa")
            val userInfo = UserInfo(
                uid = firebaseUser.uid,
                email = firebaseUser.email,
                displayName = firebaseUser.displayName,
                photoUrl = firebaseUser.photoUrl?.toString()
            )
            _currentUser.value = userInfo
            initializeUserStats(userInfo.uid)
            Result.success(userInfo)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Firebase Google sign-in failed", e)
            Result.failure(e)
        }
    }

    override fun signInDemoUser() {
        if (!BuildConfig.DEBUG) {
            Log.w(TAG, "Demo sign-in blocked in release builds.")
            return
        }
        stopCloudSync()
        val demoUser = UserInfo(
            uid = DEMO_USER_ID,
            email = "demo@bleskovafaktura.local",
            displayName = "Demo Živnostník",
            photoUrl = null
        )
        _currentUser.value = demoUser
        repositoryScope.launch {
            initializeUserStats(demoUser.uid)
        }
    }

    override suspend fun signOutAndClearLocalCache() = withContext(Dispatchers.IO) {
        val oldUid = _currentUser.value?.uid
        stopCloudSync()
        _syncError.value = null
        try {
            firebaseAuth?.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Error signing out from Firebase", e)
        }
        if (oldUid != null) {
            invoiceDao.deleteAllForUser(oldUid)
            userStatsDao.deleteUserStats(oldUid)
        }
        clearTempCacheDirectories()
        _currentUser.value = null
    }

    fun signOut() {
        val oldUid = _currentUser.value?.uid
        stopCloudSync()
        _syncError.value = null
        try {
            firebaseAuth?.signOut()
        } catch (e: Exception) {
            Log.e(TAG, "Error signing out from Firebase", e)
        }
        _currentUser.value = null
        repositoryScope.launch {
            if (oldUid != null) {
                invoiceDao.deleteAllForUser(oldUid)
                userStatsDao.deleteUserStats(oldUid)
            }
            clearTempCacheDirectories()
        }
    }

    // --- Cloud Sync & Lifecycle ---

    override fun stopCloudSync() {
        userStatsListener?.remove()
        userStatsListener = null
        invoicesListener?.remove()
        invoicesListener = null
        syncedUid = null
    }

    override suspend fun initializeUserStats(userId: String) = withContext(Dispatchers.IO) {
        if (userId.isBlank()) return@withContext
        val isCloudUser = isFirebaseReady && !userId.startsWith("demo_") && firebaseAuth?.currentUser?.uid == userId
        val db = firestoreDb

        if (isCloudUser && db != null) {
            val userRef = db.collection("users").document(userId)
            try {
                val doc = userRef.get().await()
                if (!doc.exists()) {
                    val starterPayload = mapOf(
                        "userId" to userId,
                        "credits" to DEFAULT_STARTER_CREDITS,
                        "isSubscribed" to false,
                        "totalInvoiced" to 0.0,
                        "totalInvoicedCents" to 0L,
                        "invoiceCount" to 0,
                        "activeReservationId" to null,
                        "createdAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    userRef.set(starterPayload).await()
                } else {
                    // Recover orphaned reservation left behind if app was killed mid-AI-scan
                    val orphanResId = doc.getString("activeReservationId")
                    if (!orphanResId.isNullOrBlank()) {
                        rollbackScanReservation(userId, orphanResId)
                    }
                }

                val refreshed = userRef.get().await()
                val credits = refreshed.getLong("credits")?.toInt() ?: DEFAULT_STARTER_CREDITS
                val isSubscribed = refreshed.getBoolean("isSubscribed") ?: false
                val totalCents = refreshed.getLong("totalInvoicedCents")
                    ?: SlovakFinanceUtils.doubleToCents(refreshed.getDouble("totalInvoiced") ?: 0.0)
                val invoiceCount = refreshed.getLong("invoiceCount")?.toInt() ?: 0

                userStatsDao.insertUserStats(
                    UserStatsEntity(
                        userId = userId,
                        credits = credits,
                        isSubscribed = isSubscribed,
                        totalInvoiced = SlovakFinanceUtils.centsToDouble(totalCents),
                        totalInvoicedCents = totalCents,
                        invoiceCount = invoiceCount
                    )
                )

                // Initial cloud invoice restore
                syncInvoicesFromCloudOnce(userId, db)
                startRealtimeSyncListeners(userId, db)
                return@withContext
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.GET, userRef.path, firebaseAuth)
                _syncError.value = "Nepodarilo sa zosynchronizovať účet s cloudom: ${e.localizedMessage ?: "chyba siete"}"
            }
        }

        // Local-only / Demo / Offline fallback
        if (userStatsDao.getUserStats(userId) == null) {
            userStatsDao.insertUserStats(
                UserStatsEntity(
                    userId = userId,
                    credits = DEFAULT_STARTER_CREDITS,
                    isSubscribed = false,
                    totalInvoiced = 0.0,
                    totalInvoicedCents = 0L,
                    invoiceCount = 0
                )
            )
        }
    }

    private suspend fun syncInvoicesFromCloudOnce(userId: String, db: FirebaseFirestore) {
        val colPath = "users/$userId/invoices"
        try {
            val snap = db.collection("users")
                .document(userId)
                .collection("invoices")
                .get()
                .await()
            val entities = snap.documents.mapNotNull { doc ->
                val existingLocal = invoiceDao.getInvoiceByIdForUser(doc.id, userId)
                documentToInvoiceEntity(doc, userId, existingLocal?.imageUrl)
            }
            if (entities.isNotEmpty()) {
                invoiceDao.insertAll(entities)
                recalculateStats(userId, updateFirestore = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colPath, firebaseAuth)
        }
    }

    private fun startRealtimeSyncListeners(userId: String, db: FirebaseFirestore) {
        if (syncedUid == userId && userStatsListener != null && invoicesListener != null) return
        stopCloudSync()
        syncedUid = userId

        val userRef = db.collection("users").document(userId)
        userStatsListener = userRef.addSnapshotListener { snap, error ->
            if (error != null) {
                if (firebaseAuth?.currentUser?.uid == userId) {
                    handleFirestoreError(error, OperationType.GET, userRef.path, firebaseAuth)
                }
                return@addSnapshotListener
            }
            if (snap != null && snap.exists()) {
                val credits = snap.getLong("credits")?.toInt() ?: DEFAULT_STARTER_CREDITS
                val isSubscribed = snap.getBoolean("isSubscribed") ?: false
                val totalCents = snap.getLong("totalInvoicedCents")
                    ?: SlovakFinanceUtils.doubleToCents(snap.getDouble("totalInvoiced") ?: 0.0)
                val invoiceCount = snap.getLong("invoiceCount")?.toInt() ?: 0
                repositoryScope.launch {
                    userStatsDao.insertUserStats(
                        UserStatsEntity(
                            userId = userId,
                            credits = credits,
                            isSubscribed = isSubscribed,
                            totalInvoiced = SlovakFinanceUtils.centsToDouble(totalCents),
                            totalInvoicedCents = totalCents,
                            invoiceCount = invoiceCount
                        )
                    )
                }
            }
        }

        val invoicesRef = userRef.collection("invoices")
        invoicesListener = invoicesRef.addSnapshotListener { snap, error ->
            if (error != null) {
                if (firebaseAuth?.currentUser?.uid == userId) {
                    handleFirestoreError(error, OperationType.LIST, invoicesRef.path, firebaseAuth)
                }
                return@addSnapshotListener
            }
            if (snap != null) {
                repositoryScope.launch {
                    val localExisting = invoiceDao.getInvoicesForUserOnce(userId).associateBy { it.id }
                    val cloudEntities = snap.documents.mapNotNull { doc ->
                        documentToInvoiceEntity(doc, userId, localExisting[doc.id]?.imageUrl)
                    }
                    val cloudIds = cloudEntities.map { it.id }.toSet()
                    if (cloudEntities.isNotEmpty()) {
                        invoiceDao.insertAll(cloudEntities)
                    }
                    localExisting.keys.filter { it !in cloudIds }.forEach { removedId ->
                        ReceiptImageProcessor.deleteArchivedReceipt(appContext, localExisting[removedId]?.imageUrl)
                        invoiceDao.deleteInvoiceForUser(removedId, userId)
                    }
                    recalculateStats(userId, updateFirestore = false)
                }
            }
        }
    }

    private fun documentToInvoiceEntity(
        doc: com.google.firebase.firestore.DocumentSnapshot,
        fallbackUserId: String,
        preservedLocalImagePath: String?
    ): InvoiceEntity? {
        val supplierName = doc.getString("supplierName") ?: return null
        val issueDate = doc.getString("issueDate") ?: return null
        val totalCents = doc.getLong("totalAmountCents")
            ?: SlovakFinanceUtils.doubleToCents(doc.getDouble("totalAmount") ?: 0.0)
        val vatCents = doc.getLong("vatAmountCents")
            ?: doc.getDouble("vatAmount")?.let { SlovakFinanceUtils.doubleToCents(it) }
        val createdAtMillis = doc.getTimestamp("createdAt")?.toDate()?.time
            ?: doc.getLong("createdAt")
            ?: System.currentTimeMillis()

        return InvoiceEntity(
            id = doc.id,
            supplierName = supplierName,
            ico = doc.getString("ico"),
            dic = doc.getString("dic"),
            issueDate = issueDate,
            dueDate = doc.getString("dueDate"),
            isPaid = doc.getBoolean("isPaid") ?: false,
            currency = doc.getString("currency") ?: "EUR",
            totalAmount = SlovakFinanceUtils.centsToDouble(totalCents),
            totalAmountCents = totalCents,
            vatAmount = vatCents?.let { SlovakFinanceUtils.centsToDouble(it) },
            vatAmountCents = vatCents,
            vatRate = doc.getDouble("vatRate"),
            category = doc.getString("category") ?: "Iné",
            description = doc.getString("description"),
            imageUrl = preservedLocalImagePath,
            userId = doc.getString("userId") ?: fallbackUserId,
            createdAt = createdAtMillis
        )
    }

    // --- Invoices Data Access ---

    override fun getInvoices(): Flow<List<Invoice>> {
        val user = _currentUser.value ?: return flow { emit(emptyList()) }
        return invoiceDao.getInvoicesForUser(user.uid).map { entities ->
            entities.map { it.toInvoice() }
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun insertInvoice(invoice: Invoice): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = _currentUser.value?.uid ?: firebaseAuth?.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Používateľ nie je prihlásený."))

        val cents = invoice.effectiveTotalCents()
        val vatCents = invoice.effectiveVatCents()
        val normalizedIssueDate = SlovakFinanceUtils.normalizeIsoDate(invoice.issueDate) ?: invoice.issueDate
        val normalizedDueDate = invoice.dueDate?.takeIf { it.isNotBlank() }?.let {
            SlovakFinanceUtils.normalizeIsoDate(it) ?: it
        }
        val normalizedInvoice = invoice.copy(
            userId = uid,
            supplierName = invoice.supplierName.trim().take(200),
            issueDate = normalizedIssueDate,
            dueDate = normalizedDueDate,
            currency = invoice.currency.ifBlank { "EUR" }.take(3).uppercase(Locale.ROOT),
            totalAmountCents = cents,
            totalAmount = SlovakFinanceUtils.centsToDouble(cents),
            vatAmountCents = vatCents,
            vatAmount = vatCents?.let { SlovakFinanceUtils.centsToDouble(it) },
            category = invoice.category.takeIf { it in ALLOWED_CATEGORIES } ?: "Iné"
        )

        // Save locally immediately (preserves local imageUrl path)
        invoiceDao.insertInvoice(normalizedInvoice.toEntity())

        val db = firestoreDb
        val isCloudUser = isFirebaseReady && !uid.startsWith("demo_") && firebaseAuth?.currentUser?.uid == uid
        if (isCloudUser && db != null) {
            val docRef = db.collection("users").document(uid).collection("invoices").document(normalizedInvoice.id)
            try {
                val baseFields = mutableMapOf<String, Any?>(
                    "supplierName" to normalizedInvoice.supplierName,
                    "ico" to normalizedInvoice.ico?.trim()?.takeIf { it.isNotEmpty() }?.take(32),
                    "dic" to normalizedInvoice.dic?.trim()?.takeIf { it.isNotEmpty() }?.take(32),
                    "issueDate" to normalizedInvoice.issueDate,
                    "dueDate" to normalizedInvoice.dueDate,
                    "isPaid" to normalizedInvoice.isPaid,
                    "currency" to normalizedInvoice.currency,
                    "totalAmount" to normalizedInvoice.totalAmount,
                    "totalAmountCents" to normalizedInvoice.totalAmountCents,
                    "vatAmount" to normalizedInvoice.vatAmount,
                    "vatAmountCents" to normalizedInvoice.vatAmountCents,
                    "vatRate" to normalizedInvoice.vatRate,
                    "category" to normalizedInvoice.category,
                    "description" to normalizedInvoice.description?.trim()?.takeIf { it.isNotEmpty() }?.take(500),
                    "updatedAt" to FieldValue.serverTimestamp()
                )

                db.runTransaction { tx ->
                    val existingSnap = tx.get(docRef)
                    if (!existingSnap.exists()) {
                        val createPayload = HashMap(baseFields).apply {
                            put("userId", uid)
                            put("createdAt", FieldValue.serverTimestamp())
                        }.filterValues { it != null }
                        tx.set(docRef, createPayload)
                    } else {
                        tx.update(docRef, baseFields)
                    }
                }.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.WRITE, docRef.path, firebaseAuth)
                _syncError.value = "Doklad uložený lokálne, ale cloudová synchronizácia zlyhala."
                recalculateStats(uid, updateFirestore = false)
                return@withContext Result.failure(e)
            }
        }

        recalculateStats(uid, updateFirestore = true)
        Result.success(Unit)
    }

    override suspend fun deleteInvoice(id: String) = withContext(Dispatchers.IO) {
        val uid = _currentUser.value?.uid ?: firebaseAuth?.currentUser?.uid ?: return@withContext
        val existing = invoiceDao.getInvoiceByIdForUser(id, uid)
        ReceiptImageProcessor.deleteArchivedReceipt(appContext, existing?.imageUrl)
        invoiceDao.deleteInvoiceForUser(id, uid)

        val db = firestoreDb
        val isCloudUser = isFirebaseReady && !uid.startsWith("demo_") && firebaseAuth?.currentUser?.uid == uid
        if (isCloudUser && db != null) {
            val docRef = db.collection("users").document(uid).collection("invoices").document(id)
            try {
                docRef.delete().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, docRef.path, firebaseAuth)
                _syncError.value = "Doklad vymazaný lokálne; cloudové zmazanie zlyhalo."
            }
        }

        recalculateStats(uid, updateFirestore = true)
    }

    // --- User Statistics & Atomic Credit Reservations ---

    override fun getUserStatsFlow(): Flow<UserStats> {
        val user = _currentUser.value ?: return flow { emit(UserStats()) }
        return userStatsDao.getUserStatsFlow(user.uid).map { entity ->
            entity?.toUserStats() ?: UserStats(userId = user.uid)
        }.flowOn(Dispatchers.IO)
    }

    override suspend fun reserveScanCredit(ownerUid: String): CreditReservationResult = creditMutex.withLock {
        withContext(Dispatchers.IO) {
            val db = firestoreDb
            val isCloudUser = isFirebaseReady && !ownerUid.startsWith("demo_") && firebaseAuth?.currentUser?.uid == ownerUid
            if (isCloudUser && db != null) {
                val userRef = db.collection("users").document(ownerUid)
                return@withContext try {
                    val reservationId = UUID.randomUUID().toString()
                    val result = db.runTransaction { tx ->
                        val snap = tx.get(userRef)
                        if (!snap.exists()) {
                            return@runTransaction CreditReservationResult.InsufficientCredits
                        }
                        if (snap.getBoolean("isSubscribed") == true) {
                            return@runTransaction CreditReservationResult.UnlimitedSubscriber
                        }
                        val credits = snap.getLong("credits")?.toInt() ?: 0
                        if (credits <= 0) {
                            return@runTransaction CreditReservationResult.InsufficientCredits
                        }
                        val active = snap.getString("activeReservationId")
                        if (!active.isNullOrBlank()) {
                            return@runTransaction CreditReservationResult.ScanAlreadyInProgress
                        }
                        tx.update(
                            userRef,
                            mapOf(
                                "credits" to (credits - 1),
                                "activeReservationId" to reservationId,
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                        )
                        CreditReservationResult.Reserved(reservationId)
                    }.await()

                    if (result is CreditReservationResult.Reserved) {
                        val local = userStatsDao.getUserStats(ownerUid)
                        if (local != null) {
                            userStatsDao.insertUserStats(local.copy(credits = (local.credits - 1).coerceAtLeast(0)))
                        }
                    }
                    result
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleFirestoreError(e, OperationType.UPDATE, userRef.path, firebaseAuth)
                    CreditReservationResult.InsufficientCredits
                }
            }

            // Local / Debug Demo fallback
            val stats = userStatsDao.getUserStats(ownerUid)?.toUserStats() ?: UserStats(userId = ownerUid)
            if (stats.isSubscribed) return@withContext CreditReservationResult.UnlimitedSubscriber
            if (stats.credits <= 0) return@withContext CreditReservationResult.InsufficientCredits
            val resId = UUID.randomUUID().toString()
            userStatsDao.insertUserStats(stats.copy(credits = stats.credits - 1).toEntity())
            CreditReservationResult.Reserved(resId)
        }
    }

    override suspend fun commitScanReservation(ownerUid: String, reservationId: String) {
        creditMutex.withLock {
            withContext(Dispatchers.IO) {
                val db = firestoreDb
                val isCloudUser = isFirebaseReady && !ownerUid.startsWith("demo_") && firebaseAuth?.currentUser?.uid == ownerUid
                if (isCloudUser && db != null) {
                    val userRef = db.collection("users").document(ownerUid)
                    try {
                        db.runTransaction { tx ->
                            val snap = tx.get(userRef)
                            if (snap.exists() && snap.getString("activeReservationId") == reservationId) {
                                tx.update(
                                    userRef,
                                    mapOf(
                                        "activeReservationId" to null,
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    )
                                )
                            }
                        }.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleFirestoreError(e, OperationType.UPDATE, userRef.path, firebaseAuth)
                    }
                }
            }
        }
    }

    override suspend fun rollbackScanReservation(ownerUid: String, reservationId: String) {
        creditMutex.withLock {
            withContext(Dispatchers.IO) {
                val db = firestoreDb
                val isCloudUser = isFirebaseReady && !ownerUid.startsWith("demo_") && firebaseAuth?.currentUser?.uid == ownerUid
                if (isCloudUser && db != null) {
                    val userRef = db.collection("users").document(ownerUid)
                    try {
                        val rolledBack = db.runTransaction { tx ->
                            val snap = tx.get(userRef)
                            if (snap.exists() && snap.getString("activeReservationId") == reservationId) {
                                val currentCredits = snap.getLong("credits")?.toInt() ?: 0
                                tx.update(
                                    userRef,
                                    mapOf(
                                        "credits" to (currentCredits + 1),
                                        "activeReservationId" to null,
                                        "updatedAt" to FieldValue.serverTimestamp()
                                    )
                                )
                                true
                            } else {
                                false
                            }
                        }.await()
                        if (rolledBack) {
                            val local = userStatsDao.getUserStats(ownerUid)
                            if (local != null) {
                                userStatsDao.insertUserStats(local.copy(credits = local.credits + 1))
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        handleFirestoreError(e, OperationType.UPDATE, userRef.path, firebaseAuth)
                    }
                } else {
                    val local = userStatsDao.getUserStats(ownerUid)
                    if (local != null) {
                        userStatsDao.insertUserStats(local.copy(credits = local.credits + 1))
                    }
                }
            }
        }
    }

    // --- Account Deletion (BF-17) ---

    override suspend fun deleteAccount(targetUid: String?): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = targetUid ?: _currentUser.value?.uid ?: firebaseAuth?.currentUser?.uid
            ?: return@withContext Result.failure(IllegalStateException("Žiadny prihlásený účet na vymazanie."))

        stopCloudSync()

        // 1. Delete archived receipt photos & Room records for uid
        val localInvoices = invoiceDao.getInvoicesForUserOnce(uid)
        localInvoices.forEach { ReceiptImageProcessor.deleteArchivedReceipt(appContext, it.imageUrl) }
        invoiceDao.deleteAllForUser(uid)
        userStatsDao.deleteUserStats(uid)
        clearTempCacheDirectories()

        // 2. Delete Firestore subcollection documents + user profile document
        val db = firestoreDb
        val isCloudUser = isFirebaseReady && !uid.startsWith("demo_") && firebaseAuth?.currentUser?.uid == uid
        if (isCloudUser && db != null) {
            val userRef = db.collection("users").document(uid)
            try {
                val cloudInvoices = userRef.collection("invoices").get().await()
                cloudInvoices.documents.forEach { doc ->
                    doc.reference.delete().await()
                }
                userRef.delete().await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.DELETE, userRef.path, firebaseAuth)
                return@withContext Result.failure(e)
            }

            // 3. Delete Firebase Auth account
            try {
                firebaseAuth?.currentUser?.delete()?.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "FirebaseAuth user deletion failed", e)
                return@withContext Result.failure(e)
            }
        }

        _currentUser.value = null
        Result.success(Unit)
    }

    private suspend fun recalculateStats(userId: String, updateFirestore: Boolean) {
        val list = invoiceDao.getInvoicesForUserOnce(userId)
        val totalCents = list.sumOf {
            if (it.totalAmountCents > 0L) it.totalAmountCents else SlovakFinanceUtils.doubleToCents(it.totalAmount)
        }
        val totalDouble = SlovakFinanceUtils.centsToDouble(totalCents)
        val count = list.size

        val currentStats = userStatsDao.getUserStats(userId)?.toUserStats() ?: UserStats(userId = userId)
        val updatedStats = currentStats.copy(
            totalInvoiced = totalDouble,
            totalInvoicedCents = totalCents,
            invoiceCount = count
        )
        userStatsDao.insertUserStats(updatedStats.toEntity())

        val db = firestoreDb
        val isCloudUser = updateFirestore && isFirebaseReady && !userId.startsWith("demo_") && firebaseAuth?.currentUser?.uid == userId
        if (isCloudUser && db != null) {
            val userRef = db.collection("users").document(userId)
            try {
                userRef.update(
                    mapOf(
                        "totalInvoiced" to totalDouble,
                        "totalInvoicedCents" to totalCents,
                        "invoiceCount" to count,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                ).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                handleFirestoreError(e, OperationType.UPDATE, userRef.path, firebaseAuth)
            }
        }
    }

    private fun clearTempCacheDirectories() {
        runCatching<Unit> {
            File(appContext.cacheDir, "camera").deleteRecursively()
            PdfExporter.cleanOldExportPdfs(appContext.cacheDir)
        }
    }

    private companion object {
        const val TAG = "InvoiceRepository"
        const val DEFAULT_STARTER_CREDITS = 10
        const val DEMO_USER_ID = "demo_szco_2026"
        val ALLOWED_CATEGORIES = setOf("Kancelária", "Cestovné", "Služby", "Technika", "Strava", "Iné")
    }
}
