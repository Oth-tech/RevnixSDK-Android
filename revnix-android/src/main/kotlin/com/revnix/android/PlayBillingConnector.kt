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
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryPurchasesAsync
import com.revnix.RegisterPurchaseInput
import com.revnix.RevnixClient
import com.revnix.RevnixDiagnostic
import com.revnix.RevnixError
import com.revnix.RevnixStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

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
public class PlayBillingConnector private constructor(
    context: Context,
    private val client: RevnixClient,
    private val onDiagnostic: ((RevnixDiagnostic) -> Unit)?,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val purchasesUpdatedListener = PurchasesUpdatedListener { result, purchases ->
        if (result.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            purchases.forEach { purchase -> scope.launch { register(purchase) } }
        } else if (result.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            diagnostic("purchasesUpdated", "billing result ${result.responseCode}")
        }
    }

    private val billing: BillingClient = BillingClient.newBuilder(context.applicationContext)
        .setListener(purchasesUpdatedListener)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build()
        )
        // v8 reconnects on its own; without this every dropped service
        // binding would silently stop delivering purchase updates.
        .enableAutoServiceReconnection()
        .build()

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
            PlayBillingConnector(context, client, onDiagnostic).also { it.connect() }
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
                if (register(purchase)) registered += 1
            }
        }
        return registered
    }

    /**
     * Register one purchase, then acknowledge it. Returns true when the claim
     * is safely on its way to Revnix (delivered or durably queued).
     */
    private suspend fun register(purchase: Purchase): Boolean {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            // PENDING (e.g. cash payment) — Play will call back on completion.
            return false
        }
        val input = RegisterPurchaseInput(
            source = RevnixStore.GOOGLE,
            // Google has no separate transaction id — the token is both.
            token = purchase.purchaseToken,
            productId = purchase.products.firstOrNull().orEmpty(),
            transactionId = purchase.purchaseToken,
            occurredAt = purchase.purchaseTime,
        )

        val recorded = try {
            client.registerPurchase(input)
            true
        } catch (err: RevnixError) {
            // Retryable failures are queued durably by the client, so the
            // claim is not lost and acknowledging is still correct. A
            // deliberate refusal is NOT queued — leaving it unacknowledged
            // lets Google refund it rather than stranding the customer.
            if (err.isRetryable) {
                diagnostic("registerPurchase", "queued: ${err.message}")
                true
            } else {
                diagnostic("registerPurchase", "refused: ${err.message}")
                false
            }
        }

        if (recorded) acknowledge(purchase)
        return recorded
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
        scope.cancel()
    }

    private fun diagnostic(op: String, message: String) {
        onDiagnostic?.invoke(RevnixDiagnostic(op, message))
    }
}
