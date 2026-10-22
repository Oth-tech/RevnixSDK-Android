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
import com.revnix.RevnixError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    private val consumed = mutableListOf<String>()
    private var owned = emptyList<Purchase>()
    private var consumeCode = BillingClient.BillingResponseCode.OK

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.start()
        client = RevnixClient(
            RevnixConfig(
                apiKey = "rvx_pk_test_abc",
                baseUrl = server.url("/").toString(),
                storage = MemoryStorage(),
                consumableProductIds = setOf("coins"),
            )
        )
    }

    @AfterTest
    fun tearDown() {
        client.close()
        server.shutdown()
        Dispatchers.resetMain()
    }

    private fun connector() = PlayBillingConnector(client, null) { listener = it; FakeBilling() }

    private fun waitingPurchase(productId: String = "pro"): CompletableDeferred<RegisterPurchaseResult?> =
        CompletableDeferred<RegisterPurchaseResult?>().also { connector().pending[productId] = it }

    private fun purchase(productId: String, token: String, acknowledged: Boolean = false) = Purchase(
        """{"productId":"$productId","purchaseToken":"$token","purchaseTime":1,"acknowledged":$acknowledged}""",
        "",
    )

    private fun registered() = MockResponse().setResponseCode(201)
        .setBody("""{"eventId":"evt_1","seq":9,"duplicate":false,"customerId":"cust_1"}""")

    private fun refused(status: Int, error: String) =
        MockResponse().setResponseCode(status).setBody("""{"error":"$error"}""")

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

    @Test
    fun `a consumable is consumed, not acknowledged`() {
        server.enqueue(registered())
        val deferred = waitingPurchase("coins")

        listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase("coins", "tok_c")))

        assertEquals(9, await(deferred)?.seq)
        assertEquals(listOf("tok_c"), consumed)
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun `a queued consumable is acknowledged, not consumed, and a later successful restore consumes it`() {
        server.enqueue(refused(503, "down"))
        val deferred = waitingPurchase("coins")

        listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase("coins", "tok_q")))

        assertTrue(assertFailsWith<RevnixError> { await(deferred) }.queuesPurchase)
        assertEquals(listOf("tok_q"), acknowledged)
        assertTrue(consumed.isEmpty())

        server.enqueue(registered())
        owned = listOf(purchase("coins", "tok_q", acknowledged = true))
        assertEquals(1, runBlocking { connector().restore() })
        assertEquals(listOf("tok_q"), consumed)
    }

    @Test
    fun `an already-owned consumable is restored and consumed, then fails as retryable instead of succeeding`() {
        server.enqueue(registered())
        owned = listOf(purchase("coins", "tok_stuck", acknowledged = true))
        val deferred = waitingPurchase("coins")

        listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED), null)

        val err = assertFailsWith<BillingException> { await(deferred) }
        assertEquals(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED, err.responseCode)
        assertTrue(err.isRetryable)
        assertEquals(listOf("tok_stuck"), consumed)
    }

    @Test
    fun `an already-owned non-consumable still resolves null after restore`() {
        server.enqueue(registered())
        owned = listOf(purchase("pro", "tok_owned", acknowledged = true))
        val deferred = waitingPurchase("pro")

        listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED), null)

        assertNull(await(deferred))
        assertTrue(consumed.isEmpty())
    }

    @Test
    fun `a failed consume falls back to acknowledge`() {
        consumeCode = BillingClient.BillingResponseCode.ERROR
        server.enqueue(registered())
        val deferred = waitingPurchase("coins")

        listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase("coins", "tok_sub_listed")))

        assertEquals(9, await(deferred)?.seq)
        assertEquals(listOf("tok_sub_listed"), consumed)
        assertEquals(listOf("tok_sub_listed"), acknowledged)
    }

    @Test
    fun `only the registered first product decides whether a purchase is consumed`() {
        server.enqueue(registered())
        owned = listOf(
            Purchase("""{"productIds":["pro","coins"],"purchaseToken":"tok_multi","purchaseTime":1,"acknowledged":false}""", ""),
        )

        runBlocking { connector().restore() }

        assertTrue(server.takeRequest().body.readUtf8().contains("\"productId\":\"pro\""))
        assertEquals(listOf("tok_multi"), acknowledged)
        assertTrue(consumed.isEmpty())
    }

    @Test
    fun `restore consumes an already acknowledged consumable and skips an acknowledged subscription`() {
        server.enqueue(registered())
        server.enqueue(registered())
        owned = listOf(purchase("coins", "tok_stuck", acknowledged = true), purchase("pro", "tok_sub", acknowledged = true))

        assertEquals(2, runBlocking { connector().restore() })

        assertEquals(listOf("tok_stuck"), consumed)
        assertTrue(acknowledged.isEmpty())
    }

    @Test
    fun `restore does not consume a consumable the server blocked`() {
        server.enqueue(refused(409, "purchase blocked"))
        owned = listOf(purchase("coins", "tok_blocked", acknowledged = true))

        assertEquals(0, runBlocking { connector().restore() })

        assertTrue(consumed.isEmpty())
    }

    @Test
    fun `401 and 404 acknowledge and queue the purchase`() {
        for ((status, token) in listOf(401 to "tok_401", 404 to "tok_404")) {
            server.enqueue(refused(status, "nope"))
            val deferred = waitingPurchase()

            listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase("pro", token)))

            val err = assertFailsWith<RevnixError> { await(deferred) }
            assertTrue(err.queuesPurchase)
        }
        assertEquals(listOf("tok_401", "tok_404"), acknowledged)
        assertEquals(2, client.pendingPurchaseCount())
    }

    @Test
    fun `a 409 purchase block and a 403 proof rejection are neither acknowledged nor queued`() {
        for ((status, token) in listOf(409 to "tok_409", 403 to "tok_403")) {
            server.enqueue(refused(status, "purchase blocked"))
            val deferred = waitingPurchase()

            listener.onPurchasesUpdated(billingResult(BillingClient.BillingResponseCode.OK), listOf(purchase("pro", token)))

            val err = assertFailsWith<RevnixError> { await(deferred) }
            assertFalse(err.queuesPurchase)
        }
        assertTrue(acknowledged.isEmpty())
        assertEquals(0, client.pendingPurchaseCount())
    }

    private inner class FakeBilling : BillingClient() {
        private var purchaseQueries = 0

        override fun consumeAsync(params: ConsumeParams, listener: ConsumeResponseListener) {
            consumed += params.purchaseToken
            listener.onConsumeResponse(billingResult(consumeCode), params.purchaseToken)
        }

        override fun queryPurchasesAsync(params: QueryPurchasesParams, listener: PurchasesResponseListener) {
            val inapp = purchaseQueries++ == 1
            listener.onQueryPurchasesResponse(billingResult(BillingResponseCode.OK), if (inapp) owned else emptyList())
        }

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
        override fun startConnection(listener: BillingClientStateListener) = unused()

        private fun unused(): Nothing = error("not used by these tests")
    }
}
