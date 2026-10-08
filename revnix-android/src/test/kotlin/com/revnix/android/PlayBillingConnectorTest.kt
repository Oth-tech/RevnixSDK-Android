package com.revnix.android

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.AlternativeBillingOnlyAvailabilityListener
import com.android.billingclient.api.AlternativeBillingOnlyInformationDialogListener
import com.android.billingclient.api.AlternativeBillingOnlyReportingDetailsListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingConfigResponseListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingProgramAvailabilityListener
import com.android.billingclient.api.BillingProgramReportingDetailsListener
import com.android.billingclient.api.BillingProgramReportingDetailsParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ExternalOfferAvailabilityListener
import com.android.billingclient.api.ExternalOfferInformationDialogListener
import com.android.billingclient.api.ExternalOfferReportingDetailsListener
import com.android.billingclient.api.GetBillingConfigParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResponseListener
import com.android.billingclient.api.LaunchExternalLinkParams
import com.android.billingclient.api.LaunchExternalLinkResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.revnix.MemoryStorage
import com.revnix.RegisterPurchaseResult
import com.revnix.RevnixClient
import com.revnix.RevnixConfig
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

@OptIn(ExperimentalCoroutinesApi::class)
class PlayBillingConnectorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: RevnixClient
    private lateinit var listener: PurchasesUpdatedListener
    private val acknowledged = mutableListOf<String>()

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        client = RevnixClient(
            RevnixConfig(apiKey = "rvx_pk_test_abc", baseUrl = server.url("/").toString(), storage = MemoryStorage())
        )
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun waitingPurchase(): CompletableDeferred<RegisterPurchaseResult?> {
        val connector = PlayBillingConnector(client, null) { listener = it; FakeBilling() }
        return CompletableDeferred<RegisterPurchaseResult?>().also { connector.pending["pro"] = it }
    }

    private fun billingResult(code: Int) = BillingResult.newBuilder().setResponseCode(code).build()

    private fun await(deferred: CompletableDeferred<RegisterPurchaseResult?>) =
        runBlocking { withTimeout(5_000) { deferred.await() } }

    @Test
    fun `OK registers, acknowledges and resolves the registration`() {
        server.enqueue(
            MockResponse().setBody("""{"eventId":"evt_1","seq":9,"duplicate":false,"customerId":"cust_1"}""")
        )
        val deferred = waitingPurchase()
        val purchase = Purchase("""{"productId":"pro","purchaseToken":"tok_1","purchaseTime":1,"acknowledged":false}""", "")

        listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase))

        assertEquals(9, await(deferred)?.seq)
        assertEquals(listOf("tok_1"), acknowledged)
    }

    @Test
    fun `USER_CANCELED resolves null`() {
        val deferred = waitingPurchase()

        listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.USER_CANCELED), null)

        assertNull(await(deferred))
    }

    @Test
    fun `a transient store failure rejects with a retryable BillingException`() {
        val deferred = waitingPurchase()

        listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE), null)

        val err = assertFailsWith<BillingException> { await(deferred) }
        assertEquals(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE, err.responseCode)
        assertTrue(err.isRetryable)
    }

    private inner class FakeBilling : BillingClient() {
        override fun acknowledgePurchase(params: AcknowledgePurchaseParams, listener: AcknowledgePurchaseResponseListener) {
            acknowledged += params.purchaseToken
            listener.onAcknowledgePurchaseResponse(billingResult(BillingResponseCode.OK))
        }

        override fun getConnectionState(): Int = ConnectionState.CONNECTED
        override fun isReady(): Boolean = true
        override fun isFeatureSupported(feature: String): BillingResult = unused()
        override fun launchBillingFlow(activity: Activity, params: BillingFlowParams): BillingResult = unused()
        override fun showAlternativeBillingOnlyInformationDialog(
            activity: Activity, listener: AlternativeBillingOnlyInformationDialogListener,
        ): BillingResult = unused()
        override fun showExternalOfferInformationDialog(
            activity: Activity, listener: ExternalOfferInformationDialogListener,
        ): BillingResult = unused()
        override fun showInAppMessages(
            activity: Activity, params: InAppMessageParams, listener: InAppMessageResponseListener,
        ): BillingResult = unused()
        override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) = unused()
        override fun createAlternativeBillingOnlyReportingDetailsAsync(
            listener: AlternativeBillingOnlyReportingDetailsListener,
        ) = unused()
        override fun createBillingProgramReportingDetailsAsync(
            params: BillingProgramReportingDetailsParams, listener: BillingProgramReportingDetailsListener,
        ) = unused()
        override fun createExternalOfferReportingDetailsAsync(listener: ExternalOfferReportingDetailsListener) = unused()
        override fun endConnection() = Unit
        override fun getBillingConfigAsync(params: GetBillingConfigParams, listener: BillingConfigResponseListener) = unused()
        override fun isAlternativeBillingOnlyAvailableAsync(listener: AlternativeBillingOnlyAvailabilityListener) = unused()
        override fun isBillingProgramAvailableAsync(program: Int, listener: BillingProgramAvailabilityListener) = unused()
        override fun isExternalOfferAvailableAsync(listener: ExternalOfferAvailabilityListener) = unused()
        override fun launchExternalLink(
            activity: Activity, params: LaunchExternalLinkParams, listener: LaunchExternalLinkResponseListener,
        ) = unused()
        override fun queryProductDetailsAsync(params: QueryProductDetailsParams, listener: ProductDetailsResponseListener) = unused()
        override fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) = unused()
        override fun startConnection(listener: BillingClientStateListener) = unused()

        private fun unused(): Nothing = error("not used by these tests")
    }
}
