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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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

sealed interface PlayBillingUiState {
    data object Idle : PlayBillingUiState
    data object Connecting : PlayBillingUiState
    data object LaunchingFlow : PlayBillingUiState
    data class PendingVerification(val productId: String, val message: String) : PlayBillingUiState
    data class Info(val message: String) : PlayBillingUiState
    data class Error(val message: String) : PlayBillingUiState
}

class PlayBillingManager(context: Context) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext

    private val _billingState = MutableStateFlow<PlayBillingUiState>(PlayBillingUiState.Idle)
    val billingState: StateFlow<PlayBillingUiState> = _billingState.asStateFlow()

    private val _catalogPrices = MutableStateFlow<Map<String, String>>(emptyMap())
    val catalogPrices: StateFlow<Map<String, String>> = _catalogPrices.asStateFlow()

    private val _productDetails = mutableMapOf<String, ProductDetails>()
    private var isConnected = false

    private val billingClient: BillingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .enableAutoServiceReconnection()
        .build()

    fun startConnection() {
        if (billingClient.isReady) {
            isConnected = true
            queryCatalogProducts()
            queryExistingPurchases()
            return
        }
        _billingState.value = PlayBillingUiState.Connecting
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    isConnected = true
                    _billingState.value = PlayBillingUiState.Idle
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

    fun launchPurchase(activity: Activity?, product: BillingProduct) {
        if (activity == null) {
            _billingState.value = PlayBillingUiState.Error("Nákup vyžaduje aktívne okno aplikácie.")
            return
        }
        if (!billingClient.isReady) {
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

        val flowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParamsBuilder.build()))
            .build()

        _billingState.value = PlayBillingUiState.LaunchingFlow
        val launchResult = billingClient.launchBillingFlow(activity, flowParams)
        if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
            _billingState.value = PlayBillingUiState.Error(
                "Nepodarilo sa otvoriť Google Play pokladňu: ${launchResult.debugMessage}"
            )
        }
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                val purchasedList = purchases.orEmpty()
                if (purchasedList.isEmpty()) {
                    _billingState.value = PlayBillingUiState.Idle
                    return
                }
                purchasedList.forEach { purchase ->
                    when (purchase.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> {
                            // Zero-trust rule: never grant credits or isSubscribed locally without backend
                            // Play Developer API signature + token verification.
                            val productId = purchase.products.firstOrNull().orEmpty()
                            Log.i(TAG, "Play purchase received for $productId; awaiting server verification.")
                            _billingState.value = PlayBillingUiState.PendingVerification(
                                productId = productId,
                                message = "Nákup ($productId) bol zaznamenaný v Google Play. Kredity/Premium sa aktivujú automaticky po overení účtenky na backend serveri (Cloud Functions / Play Developer API)."
                            )
                        }
                        Purchase.PurchaseState.PENDING -> {
                            _billingState.value = PlayBillingUiState.Info(
                                "Platba v Google Play čaká na dokončenie. Nárok sa pripíše po potvrdení platby."
                            )
                        }
                        else -> {
                            _billingState.value = PlayBillingUiState.Idle
                        }
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _billingState.value = PlayBillingUiState.Idle
            }
            else -> {
                _billingState.value = PlayBillingUiState.Error(
                    "Google Play nákup nebol dokončený (${billingResult.responseCode}): ${billingResult.debugMessage}"
                )
            }
        }
    }

    fun clearMessage() {
        _billingState.value = PlayBillingUiState.Idle
    }

    fun endConnection() {
        if (billingClient.isReady) {
            billingClient.endConnection()
        }
        isConnected = false
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
        if (items.isEmpty() || !billingClient.isReady) return
        val productList = items.map { product ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(product.productId)
                .setProductType(type)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        billingClient.queryProductDetailsAsync(params) { result, queryResult ->
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
        if (!billingClient.isReady) return
        listOf(BillingClient.ProductType.INAPP, BillingClient.ProductType.SUBS).forEach { type ->
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            billingClient.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases.isNotEmpty()) {
                    Log.i(TAG, "Found ${purchases.size} existing $type purchases in Google Play cache.")
                }
            }
        }
    }

    private companion object {
        const val TAG = "PlayBillingManager"
    }
}
