package com.example.data

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Collections
import java.security.MessageDigest

internal fun playBillingAccountId(uid: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest("blesk-play-account-v1:$uid".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

enum class BillingProduct(
    val productId: String,
    val productType: String,
    val fallbackTitle: String,
    val fallbackPrice: String
) {
    PREMIUM_MONTHLY(
        productId = "blesk_premium_monthly",
        productType = BillingClient.ProductType.SUBS,
        fallbackTitle = "Blesk Premium",
        fallbackPrice = "9,99 € / mesiac"
    ),
    CREDITS_10(
        productId = "blesk_credits_10",
        productType = BillingClient.ProductType.INAPP,
        fallbackTitle = "Balík 10 skenov",
        fallbackPrice = "1,99 €"
    ),
    CREDITS_50(
        productId = "blesk_credits_50",
        productType = BillingClient.ProductType.INAPP,
        fallbackTitle = "Balík 50 skenov",
        fallbackPrice = "4,99 €"
    ),
    CREDITS_100(
        productId = "blesk_credits_100",
        productType = BillingClient.ProductType.INAPP,
        fallbackTitle = "Balík 100 skenov",
        fallbackPrice = "7,99 €"
    )
}

data class BillingVerificationResponse(
    val status: String,
    val idempotent: Boolean,
    val productId: String,
    val creditsGranted: Int,
    val isSubscribed: Boolean
)

fun interface BillingPurchaseVerifier {
    suspend fun verifyPurchase(
        packageName: String,
        productId: String,
        purchaseToken: String
    ): Result<BillingVerificationResponse>
}

class FirebaseBillingPurchaseVerifier(
    private val context: Context,
    private val region: String = FUNCTIONS_REGION
) : BillingPurchaseVerifier {

    override suspend fun verifyPurchase(
        packageName: String,
        productId: String,
        purchaseToken: String
    ): Result<BillingVerificationResponse> {
        return try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            val currentUser = FirebaseAuth.getInstance().currentUser
                ?: return Result.failure(
                    IllegalStateException("Pred overením nákupu sa najprv prihláste svojím účtom Google.")
                )
            if (currentUser.uid.isBlank()) {
                return Result.failure(IllegalStateException("Neplatná relácia používateľa."))
            }

            val payload = hashMapOf(
                "packageName" to packageName,
                "productId" to productId,
                "purchaseToken" to purchaseToken
            )
            val callableResult = FirebaseFunctions.getInstance(region)
                .getHttpsCallable(CALLABLE_VERIFY_PLAY_PURCHASE)
                .call(payload)
                .await()

            val data = callableResult.getData() as? Map<*, *>
                ?: return Result.failure(IllegalStateException("Server vrátil neplatnú odpoveď."))

            val status = (data["status"] as? String).orEmpty()
            val responseProductId = (data["productId"] as? String).orEmpty().ifBlank { productId }
            val idempotent = data["idempotent"] as? Boolean ?: false
            val creditsGranted = (data["creditsGranted"] as? Number)?.toInt() ?: 0
            val isSubscribed = data["isSubscribed"] as? Boolean ?: false

            if (status.isBlank()) {
                return Result.failure(IllegalStateException("V odpovedi servera chýba stav overenia."))
            }

            Result.success(
                BillingVerificationResponse(
                    status = status,
                    idempotent = idempotent,
                    productId = responseProductId,
                    creditsGranted = creditsGranted,
                    isSubscribed = isSubscribed
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: FirebaseFunctionsException) {
            val friendlyMessage = when (e.code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED ->
                    "Overenie nákupu vyžaduje platné prihlásenie Google."
                FirebaseFunctionsException.Code.PERMISSION_DENIED ->
                    "Nákup je už priradený inému účtu alebo zlyhalo overenie integrity aplikácie (App Check)."
                FirebaseFunctionsException.Code.FAILED_PRECONDITION ->
                    "Nákup v Google Play ešte nie je dokončený alebo bol zrušený."
                FirebaseFunctionsException.Code.INVALID_ARGUMENT ->
                    "Neplatné parametre produktu alebo identifikátor aplikácie."
                FirebaseFunctionsException.Code.NOT_FOUND,
                FirebaseFunctionsException.Code.UNAVAILABLE ->
                    "Overovacia služba je dočasne nedostupná. Skúste prosím znova o chvíľu."
                else ->
                    "Serverové overenie nákupu zlyhalo (${e.code}). Skúste prosím obnoviť nákup."
            }
            Log.e(TAG, "verifyPlayPurchase failed with code=${e.code}", e)
            Result.failure(IllegalStateException(friendlyMessage, e))
        } catch (e: Exception) {
            Log.e(TAG, "verifyPlayPurchase unexpected failure", e)
            Result.failure(
                IllegalStateException(
                    "Nepodarilo sa overiť nákup na serveri. Skontrolujte pripojenie a skúste obnoviť nákup.",
                    e
                )
            )
        }
    }

    private companion object {
        const val TAG = "FirebaseBillingVerifier"
        const val FUNCTIONS_REGION = "europe-west1"
        const val CALLABLE_VERIFY_PLAY_PURCHASE = "verifyPlayPurchase"
    }
}

sealed interface PlayBillingUiState {
    data object Idle : PlayBillingUiState
    data object Connecting : PlayBillingUiState
    data object LaunchingFlow : PlayBillingUiState
    data class PendingVerification(val productId: String, val message: String) : PlayBillingUiState
    data class Verified(
        val productId: String,
        val creditsGranted: Int,
        val isSubscribed: Boolean,
        val idempotent: Boolean,
        val message: String
    ) : PlayBillingUiState
    data class Info(val message: String) : PlayBillingUiState
    data class Error(val message: String) : PlayBillingUiState
}

class PlayBillingManager(
    context: Context,
    private val verifier: BillingPurchaseVerifier = FirebaseBillingPurchaseVerifier(context.applicationContext),
    private val billingScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val packageNameProvider: () -> String = { BuildConfig.APPLICATION_ID },
    enableBillingClient: Boolean = true
) : PurchasesUpdatedListener, BillingGateway {
    private val appContext = context.applicationContext

    private val _billingState = MutableStateFlow<PlayBillingUiState>(PlayBillingUiState.Idle)
    override val billingState: StateFlow<PlayBillingUiState> = _billingState.asStateFlow()

    private val _catalogPrices = MutableStateFlow<Map<String, String>>(emptyMap())
    override val catalogPrices: StateFlow<Map<String, String>> = _catalogPrices.asStateFlow()

    private val _pendingUnverifiedCount = MutableStateFlow(0)
    override val pendingUnverifiedCount: StateFlow<Int> = _pendingUnverifiedCount.asStateFlow()

    private val _productDetails = mutableMapOf<String, ProductDetails>()
    private val inFlightTokens: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
    private val pendingLock = Any()
    private val pendingInAppTokens = mutableSetOf<String>()
    private val pendingSubsTokens = mutableSetOf<String>()
    private val pendingUpdatedTokens = mutableSetOf<String>()
    private var isConnected = false

    private fun refreshPendingCountLocked() {
        val uniqueTokens = LinkedHashSet<String>()
        uniqueTokens.addAll(pendingInAppTokens)
        uniqueTokens.addAll(pendingSubsTokens)
        uniqueTokens.addAll(pendingUpdatedTokens)
        uniqueTokens.addAll(inFlightTokens)
        _pendingUnverifiedCount.value = uniqueTokens.size
    }

    private val billingClient: BillingClient? = if (enableBillingClient) {
        BillingClient.newBuilder(appContext)
            .setListener(this)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .enablePrepaidPlans()
                    .build()
            )
            .enableAutoServiceReconnection()
            .build()
    } else {
        null
    }

    override fun startConnection() {
        val client = billingClient ?: return
        if (client.isReady) {
            isConnected = true
            queryCatalogProducts()
            queryExistingPurchases()
            return
        }
        _billingState.value = PlayBillingUiState.Connecting
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    if (_billingState.value is PlayBillingUiState.Connecting) {
                        _billingState.value = PlayBillingUiState.Idle
                    }
                    queryCatalogProducts()
                    queryExistingPurchases()
                } else {
                    isConnected = false
                    Log.w(TAG, "Billing setup failed: ${billingResult.responseCode} ${billingResult.debugMessage}")
                    _billingState.value = PlayBillingUiState.Error(
                        "Služba Google Play Billing nie je v tomto zariadení dostupná (${billingResult.responseCode})."
                    )
                }
            }

            override fun onBillingServiceDisconnected() {
                isConnected = false
            }
        })
    }

    override fun launchPurchase(activity: Activity?, product: BillingProduct) {
        if (activity == null) {
            _billingState.value = PlayBillingUiState.Error("Nákup vyžaduje aktívne okno aplikácie.")
            return
        }
        val client = billingClient
        if (client == null || !client.isReady) {
            startConnection()
            _billingState.value = PlayBillingUiState.Error(
                "Spojenie s Google Play sa práve inicializuje. Skúste prosím o chvíľu."
            )
            return
        }

        val details = _productDetails[product.productId]
        if (details == null) {
            queryCatalogProducts()
            _billingState.value = PlayBillingUiState.Error(
                "Produkt '${product.productId}' nie je v Google Play Console aktívny alebo publikovaný pre tento build."
            )
            return
        }

        val productParamsBuilder = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)

        if (product.productType == BillingClient.ProductType.SUBS) {
            val offerToken = details.subscriptionOfferDetails?.firstOrNull()?.offerToken
            if (offerToken.isNullOrBlank()) {
                _billingState.value = PlayBillingUiState.Error(
                    "V Google Play Console chýba aktívna cenová ponuka (base plan) pre ${product.productId}."
                )
                return
            }
            productParamsBuilder.setOfferToken(offerToken)
        }

        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            _billingState.value = PlayBillingUiState.Error("Pred nákupom sa prihláste svojím účtom Google.")
            return
        }
        val flowParams = BillingFlowParams.newBuilder()
            .setObfuscatedAccountId(playBillingAccountId(uid))
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()

        _billingState.value = PlayBillingUiState.LaunchingFlow
        val launchResult = client.launchBillingFlow(activity, flowParams)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            _billingState.value = PlayBillingUiState.Error(
                "Nepodarilo sa otvoriť Google Play pokladňu: ${launchResult.debugMessage}"
            )
        }
    }

    override fun retryPendingVerifications() {
        val client = billingClient ?: return
        if (!client.isReady) {
            startConnection()
            return
        }
        queryExistingPurchases()
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        billingScope.launch {
            processUpdatedPurchases(
                responseCode = billingResult.responseCode,
                debugMessage = billingResult.debugMessage,
                purchases = purchases
            )
        }
    }

    internal suspend fun processUpdatedPurchases(
        responseCode: Int,
        debugMessage: String,
        purchases: List<Purchase>?
    ) {
        when (responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchasedList = purchases.orEmpty()
                if (purchasedList.isEmpty()) {
                    _billingState.value = PlayBillingUiState.Idle
                    return
                }
                for (purchase in purchasedList) {
                    val productId = purchase.products.firstOrNull().orEmpty()
                    handleSinglePurchase(
                        productId = productId,
                        purchaseToken = purchase.purchaseToken,
                        purchaseState = purchase.purchaseState
                    )
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingState.value = PlayBillingUiState.Idle
            }
            else -> {
                _billingState.value = PlayBillingUiState.Error(
                    "Google Play nákup nebol dokončený ($responseCode): $debugMessage"
                )
            }
        }
    }

    internal suspend fun handleSinglePurchase(
        productId: String,
        purchaseToken: String,
        purchaseState: Int
    ) {
        when (purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                if (productId.isBlank() || purchaseToken.isBlank()) {
                    _billingState.value = PlayBillingUiState.Error("Nákup neobsahuje platný identifikátor produktu alebo token.")
                    return
                }
                if (!inFlightTokens.add(purchaseToken)) {
                    return
                }
                synchronized(pendingLock) {
                    pendingUpdatedTokens.add(purchaseToken)
                    refreshPendingCountLocked()
                }
                try {
                    _billingState.value = PlayBillingUiState.PendingVerification(
                        productId = productId,
                        message = "Overujeme účtenku Google Play ($productId) na zabezpečenom serveri..."
                    )
                    val verifyResult = verifier.verifyPurchase(
                        packageName = packageNameProvider(),
                        productId = productId,
                        purchaseToken = purchaseToken
                    )
                    verifyResult.fold(
                        onSuccess = { response ->
                            if (response.status in setOf("GRANTED", "ALREADY_PROCESSED", "SUBSCRIPTION_ACTIVE", "SUBSCRIPTION_INACTIVE")) {
                                synchronized(pendingLock) {
                                    pendingUpdatedTokens.remove(purchaseToken)
                                    pendingInAppTokens.remove(purchaseToken)
                                    pendingSubsTokens.remove(purchaseToken)
                                }
                            }
                            _billingState.value = when (response.status) {
                                "GRANTED" -> PlayBillingUiState.Verified(
                                    productId = response.productId,
                                    creditsGranted = response.creditsGranted,
                                    isSubscribed = false,
                                    idempotent = false,
                                    message = "Nákup overený. Na účet bolo pripísaných +${response.creditsGranted} kreditov."
                                )
                                "ALREADY_PROCESSED" -> PlayBillingUiState.Verified(
                                    productId = response.productId,
                                    creditsGranted = 0,
                                    isSubscribed = response.isSubscribed,
                                    idempotent = true,
                                    message = "Nákup ($productId) už bol skôr bezpečne overený a spracovaný."
                                )
                                "SUBSCRIPTION_ACTIVE" -> PlayBillingUiState.Verified(
                                    productId = response.productId,
                                    creditsGranted = 0,
                                    isSubscribed = true,
                                    idempotent = response.idempotent,
                                    message = "Predplatné Blesk Premium bolo overené na serveri a je aktívne."
                                )
                                "SUBSCRIPTION_INACTIVE" -> PlayBillingUiState.Info(
                                    "Predplatné v Google Play momentálne nie je aktívne alebo čaká na úhradu."
                                )
                                else -> PlayBillingUiState.Error(
                                    "Neznámy výsledok serverového overenia: ${response.status}"
                                )
                            }
                        },
                        onFailure = { error ->
                            _billingState.value = PlayBillingUiState.Error(
                                error.message ?: "Serverové overenie nákupu zlyhalo."
                            )
                        }
                    )
                } finally {
                    inFlightTokens.remove(purchaseToken)
                    synchronized(pendingLock) {
                        refreshPendingCountLocked()
                    }
                }
            }
            Purchase.PurchaseState.PENDING -> {
                if (purchaseToken.isNotBlank()) {
                    synchronized(pendingLock) {
                        pendingUpdatedTokens.add(purchaseToken)
                        refreshPendingCountLocked()
                    }
                }
                _billingState.value = PlayBillingUiState.Info(
                    "Platba v Google Play čaká na dokončenie. Nárok sa pripíše až po potvrdení platby a overení serverom."
                )
            }
            else -> {
                _billingState.value = PlayBillingUiState.Idle
            }
        }
    }

    override fun clearMessage() {
        _billingState.value = PlayBillingUiState.Idle
    }

    override fun endConnection() {
        if (billingClient?.isReady == true) {
            billingClient.endConnection()
        }
        isConnected = false
        billingScope.cancel()
    }

    private fun queryCatalogProducts() {
        queryByType(
            BillingClient.ProductType.INAPP,
            BillingProduct.entries.filter { it.productType == BillingClient.ProductType.INAPP }
        )
        queryByType(
            BillingClient.ProductType.SUBS,
            BillingProduct.entries.filter { it.productType == BillingClient.ProductType.SUBS }
        )
    }

    private fun queryByType(type: String, items: List<BillingProduct>) {
        val client = billingClient ?: return
        if (items.isEmpty() || !client.isReady) return
        val productList = items.map { product ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(product.productId)
                .setProductType(type)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        client.queryProductDetailsAsync(params) { result, queryResult ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                val updatedMap = _catalogPrices.value.toMutableMap()
                queryResult.productDetailsList.forEach { details ->
                    _productDetails[details.productId] = details
                    val formattedPrice = if (type == BillingClient.ProductType.SUBS) {
                        details.subscriptionOfferDetails?.firstOrNull()
                            ?.pricingPhases?.pricingPhaseList?.firstOrNull()?.formattedPrice
                    } else {
                        details.oneTimePurchaseOfferDetails?.formattedPrice
                    }
                    if (!formattedPrice.isNullOrBlank()) {
                        updatedMap[details.productId] = formattedPrice
                    }
                }
                _catalogPrices.value = updatedMap
            } else {
                Log.w(TAG, "Product query ($type) returned ${result.responseCode}: ${result.debugMessage}")
            }
        }
    }

    private fun queryExistingPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return
        listOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS).forEach { type ->
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            client.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    val unresolvedPurchases = purchases.filter { purchase ->
                        purchase.purchaseState == Purchase.PurchaseState.PENDING ||
                            (purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                                (type == BillingClient.ProductType.INAPP || !purchase.isAcknowledged))
                    }
                    synchronized(pendingLock) {
                        val bucket = if (type == BillingClient.ProductType.INAPP) pendingInAppTokens else pendingSubsTokens
                        bucket.clear()
                        unresolvedPurchases.mapNotNullTo(bucket) { p -> p.purchaseToken.takeIf { it.isNotBlank() } }
                        pendingUpdatedTokens.retainAll(pendingInAppTokens + pendingSubsTokens)
                        refreshPendingCountLocked()
                    }
                    val pendingToVerify = unresolvedPurchases.filter {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                    }
                    if (pendingToVerify.isNotEmpty()) {
                        Log.i(TAG, "Recovering ${pendingToVerify.size} $type purchases via backend verifier.")
                        billingScope.launch {
                            pendingToVerify.forEach { purchase ->
                                val sku = purchase.products.firstOrNull().orEmpty()
                                handleSinglePurchase(
                                    productId = sku,
                                    purchaseToken = purchase.purchaseToken,
                                    purchaseState = purchase.purchaseState
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private companion object {
        const val TAG = "PlayBillingManager"
    }
}
