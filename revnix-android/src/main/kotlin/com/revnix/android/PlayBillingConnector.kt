package com.revnix.android

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
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
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.revnix.RegisterPurchaseInput
import com.revnix.RegisterPurchaseResult
import com.revnix.RevnixClient
import com.revnix.RevnixDiagnostic
import com.revnix.RevnixError
import com.revnix.RevnixStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Play Billing 8 glue — the reason a native SDK exists on Android.
 *
 * Two rules here are not negotiable and are easy to get wrong:
 *
 * 1. **Acknowledgement is the SDK's job, and it happens AFTER the claim is
 *    recorded.** The backend deliberately never acknowledges. Google refunds
 *    any purchase not acknowledged within 3 days, so acknowledging *before*
 *    Revnix has the claim would trade a refund window for a lost entitlement.
 *    We acknowledge once [RevnixClient.registerPurchase] has either succeeded
 *    or been durably queued — both mean the claim will reach the server.
 * 2. **`transactionId` is the purchaseToken.** Google has no separate
 *    transaction identifier; the token is the stable key the backend dedupes
 *    on.
 *
 * Proof note: a device claim cannot carry Play-side proof (the server
 * corroborates via RTDN / `subscriptionsv2.get`), so expect
 * `provisional = true` on store-connected tenants until it does. Surface it in
 * your UI if you message entitlement state differently while pending.
 */
public class PlayBillingConnector internal constructor(
    private val client: RevnixClient,
    private val onDiagnostic: ((RevnixDiagnostic) -> Unit)?,
    billingClient: (PurchasesUpdatedListener) -> BillingClient,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    internal val pending = mutableMapOf<String, CompletableDeferred<RegisterPurchaseResult?>>()

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        when {
            result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null ->
                purchases.forEach { purchase ->
                    scope.launch {
                        val waiting = purchase.products.mapNotNull { pending.remove(it) }
                        try {
                            when (val outcome = register(purchase)) {
                                is Registration.Registered -> waiting.forEach { it.complete(outcome.result) }
                                is Registration.Queued -> waiting.forEach { it.completeExceptionally(outcome.err) }
                                is Registration.Refused -> waiting.forEach { it.completeExceptionally(outcome.err) }
                                Registration.Pending -> waiting.forEach { it.complete(null) }
                            }
                        } catch (err: Throwable) {
                            waiting.forEach { it.completeExceptionally(err) }
                        }
                    }
                }
            result.responseCode == BillingClient.BillingResponseCode.OK ||
                result.responseCode == BillingClient.BillingResponseCode.USER_CANCELED -> {
                pending.values.forEach { it.complete(null) }
                pending.clear()
            }
            result.responseCode == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                val waiting = pending.values.toList()
                pending.clear()
                scope.launch {
                    runCatching { restore() }
                    waiting.forEach { it.complete(null) }
                }
            }
            else -> {
                diagnostic("purchasesUpdated", "billing result ${result.responseCode}")
                val failure = billingFailure(result)
                pending.values.forEach { it.completeExceptionally(failure) }
                pending.clear()
            }
        }
    }

    private val billing: BillingClient = billingClient(purchasesUpdatedListener)

    public companion object {
        /**
         * Start at app launch. Connects to Play, replays purchases the device
         * already owns (a purchase completed while the app was closed arrives
         * here, not through the listener), and drains the offline queue.
         */
        public fun start(
            context: Context,
            client: RevnixClient,
            onDiagnostic: ((RevnixDiagnostic) -> Unit)? = null,
        ): PlayBillingConnector =
            PlayBillingConnector(client, onDiagnostic) { listener ->
                BillingClient.newBuilder(context.applicationContext)
                    .setListener(listener)
                    .enablePendingPurchases(
                        PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
                    )
                    // v8 reconnects on its own; without this every dropped service
                    // binding would silently stop delivering purchase updates.
                    .enableAutoServiceReconnection()
                    .build()
            }.also { it.connect() }
    }

    private fun connect() {
        billing.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    scope.launch {
                        client.retryPendingPurchases()
                        restore()
                    }
                } else {
                    diagnostic("billingSetup", "response ${result.responseCode}")
                }
            }

            override fun onBillingServiceDisconnected() {
                // v8 auto-reconnects; nothing to do but note it.
                diagnostic("billingDisconnected", "service disconnected")
            }
        })
    }

    /**
     * Launch the purchase flow. `obfuscatedAccountId` is set to the Revnix
     * customer id so the server-to-server RTDN self-identifies without the
     * app having to reconcile anything.
     */
    public fun launchPurchase(
        activity: Activity,
        productDetails: ProductDetails,
        offerToken: String? = null,
    ): BillingResult {
        val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(productDetails)
            .apply { offerToken?.let { setOfferToken(it) } }
            .build()

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productParams))
            .setObfuscatedAccountId(client.customerId())
            .build()

        return billing.launchBillingFlow(activity, params)
    }

    /**
     * Buy [productId]: opens the Play sheet, registers the purchase with
     * Revnix, acknowledges it and returns the registration. Null when the
     * customer cancels, the purchase is pending, or it was already owned (then
     * it is restored instead). Throws the [RevnixError] registration raised
     * when Play charged but Revnix queued (acknowledged) or refused (not
     * acknowledged) the claim, [ProductNotFoundException] when Play has no such
     * product, and [BillingException] for any other Play failure.
     */
    public suspend fun purchase(activity: Activity, productId: String): RegisterPurchaseResult? =
        withContext(Dispatchers.Main.immediate) {
            val lookups = mutableListOf<BillingResult>()
            val details = listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)
                .firstNotNullOfOrNull { type ->
                    val product = QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(productId)
                        .setProductType(type)
                        .build()
                    val params = QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build()
                    val result = billing.queryProductDetails(params)
                    lookups += result.billingResult
                    result.productDetailsList?.firstOrNull()
                } ?: throw if (lookups.all { it.responseCode != BillingClient.BillingResponseCode.OK }) {
                    billingFailure(lookups.last())
                } else {
                    ProductNotFoundException(productId)
                }

            val deferred = CompletableDeferred<RegisterPurchaseResult?>()
            pending.put(productId, deferred)?.complete(null)
            // ponytail: first offer of the first base plan; take an offer token from the caller once a product sells several base plans.
            val launched = launchPurchase(activity, details, details.subscriptionOfferDetails?.firstOrNull()?.offerToken)
            if (launched.responseCode == BillingClient.BillingResponseCode.OK) return@withContext deferred.await()
            pending.remove(productId)
            when (launched.responseCode) {
                BillingClient.BillingResponseCode.USER_CANCELED -> null
                BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    restore()
                    null
                }
                else -> throw billingFailure(launched)
            }
        }

    /**
     * Re-register everything the device owns. The server dedupes on the shared
     * purchaseKey, so this is always safe to call.
     */
    public suspend fun restore(): Int {
        var registered = 0
        for (type in listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)) {
            val params = QueryPurchasesParams.newBuilder().setProductType(type).build()
            val result = billing.queryPurchasesAsync(params)
            if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                diagnostic("restore", "query $type failed ${result.billingResult.responseCode}")
                continue
            }
            for (purchase in result.purchasesList) {
                val outcome = register(purchase)
                if (outcome is Registration.Registered || outcome is Registration.Queued) registered += 1
            }
        }
        return registered
    }

    private sealed interface Registration {
        data class Registered(val result: RegisterPurchaseResult) : Registration
        data class Queued(val err: RevnixError) : Registration
        data class Refused(val err: RevnixError) : Registration
        data object Pending : Registration
    }

    private suspend fun register(purchase: Purchase): Registration {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return Registration.Pending
        val input = RegisterPurchaseInput(
            source = RevnixStore.GOOGLE,
            // Google has no separate transaction id — the token is both.
            token = purchase.purchaseToken,
            productId = purchase.products.firstOrNull().orEmpty(),
            transactionId = purchase.purchaseToken,
            occurredAt = purchase.purchaseTime,
        )

        val outcome = try {
            Registration.Registered(client.registerPurchase(input))
        } catch (err: RevnixError) {
            if (err.isRetryable) {
                diagnostic("registerPurchase", "queued: ${err.message}")
                Registration.Queued(err)
            } else {
                diagnostic("registerPurchase", "refused: ${err.message}")
                Registration.Refused(err)
            }
        }

        if (outcome !is Registration.Refused) acknowledge(purchase)
        return outcome
    }

    /** Acknowledge inside Google's 3-day window — never before the claim. */
    private suspend fun acknowledge(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        val result = billing.acknowledgePurchase(params)
        if (result.responseCode != BillingClient.BillingResponseCode.OK) {
            diagnostic("acknowledge", "failed ${result.responseCode}")
        }
    }

    /** Release the connection and the internal scope. */
    public fun close() {
        billing.endConnection()
        pending.values.forEach { it.completeExceptionally(BillingException(-1, "billing closed", false)) }
        pending.clear()
        scope.cancel()
    }

    private fun billingFailure(result: BillingResult) = BillingException(
        result.responseCode,
        "billing ${result.responseCode} ${result.debugMessage}",
        isRetryable = result.responseCode in transientBillingCodes,
    )

    private fun diagnostic(op: String, message: String) {
        onDiagnostic?.invoke(RevnixDiagnostic(op, message))
    }
}

private val transientBillingCodes = setOf(
    BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
    BillingClient.BillingResponseCode.NETWORK_ERROR,
    BillingClient.BillingResponseCode.ERROR,
    BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
)

/** A Play Billing failure; [isRetryable] is true only for transient Play codes. */
public class BillingException(
    public val responseCode: Int,
    message: String,
    public val isRetryable: Boolean,
) : RuntimeException(message)

/** Play has no product with this id. */
public class ProductNotFoundException(productId: String) : NoSuchElementException("No store product \"$productId\"")
