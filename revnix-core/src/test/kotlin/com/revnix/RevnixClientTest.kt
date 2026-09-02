package com.revnix

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

/**
 * Behavior tests ported from revnix-react's `resilience.test.ts` — that file is
 * the policy spec; these must stay in agreement with it, and with the Swift
 * port in revnix-swift. Each test notes the spec case it mirrors.
 */
class RevnixClientTest {

    private lateinit var server: MockWebServer
    private val clients = mutableListOf<RevnixClient>()

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun tearDown() {
        clients.forEach { it.close() }
        clients.clear()
        server.shutdown()
    }

    // MARK: - Fixtures

    private val dayMs = 24L * 60 * 60 * 1000

    private val entitlementsBody = """
        {"customerId":"cust_1","cursor":7,"entitlements":[{"entitlementId":"pro","isActive":true,"expiresAt":4102444800000,"sources":[{"kind":"subscription","key":"s1","isActive":true,"expiresAt":4102444800000}]}]}
    """.trimIndent()

    private val purchaseBody = """
        {"eventId":"evt_1","seq":9,"duplicate":false,"customerId":"cust_1","transferred":false}
    """.trimIndent()

    private val placementBody = """
        {"status":"ok","placementKey":"main","revision":1,"offering":{"offeringId":"off_1","displayName":"Default","packages":[{"packageId":"pkg_1","productId":"pro.monthly"}]}}
    """.trimIndent()

    /** REV-219: a running experiment served this customer a sticky variant. */
    private val placementExperimentBody = """
        {"status":"ok","placementKey":"main","revision":3,"offering":{"offeringId":"off_2","displayName":"Variant B","packages":[{"packageId":"pkg_1","productId":"pro.monthly"}]},"experiment":{"key":"summer-price-test","variantId":"var_b"}}
    """.trimIndent()

    /** No experiment on the placement — the server sends an explicit null. */
    private val placementNullExperimentBody = """
        {"status":"ok","placementKey":"main","revision":1,"offering":{"offeringId":"off_1","displayName":"Default","packages":[{"packageId":"pkg_1","productId":"pro.monthly"}]},"experiment":null}
    """.trimIndent()

    /** Templated paywall using the full REV-028 surface, plus an unknown key. */
    private val paywallFullBody = """
        {"status":"ok","placementKey":"main","revision":2,"offering":{"offeringId":"off_1","displayName":"Default","packages":[{"packageId":"pkg_1","productId":"pro.monthly"}]},"paywall":{"paywallId":"pw_1","name":"Summer promo","config":{"template":"reveal","mode":"light","headline":"Go Pro","subheadline":"Everything unlocked","features":[{"icon":"star","title":"All features","description":"No limits"}],"ctaLabel":"Continue","highlightPackageId":"pkg_1","badgeText":"SAVE 17%","accent":"#6478ff","heroImageUrl":"https://cdn.example/hero.png","review":{"rating":4.8,"quote":"Worth it","author":"Ana","count":"Join 2M+ users"},"offer":{"strikethroughPrice":"PKR 9,999","urgencyText":"Ends tonight"},"footer":{"showRestore":true,"showTerms":false,"showPrivacy":true,"termsUrl":"https://revnix.io/terms"},"futureKnob":true}}}
    """.trimIndent()

    /** Pre-templates config: no mode/review/offer/footer, original layout. */
    private val paywallLegacyBody = """
        {"status":"ok","placementKey":"main","revision":1,"offering":{"offeringId":"off_1","displayName":"Default","packages":[{"packageId":"pkg_1","productId":"pro.monthly"}]},"paywall":{"paywallId":"pw_0","name":"Legacy","config":{"template":"focus","headline":"Unlock","features":[{"title":"Feature one"}],"ctaLabel":"Subscribe"}}}
    """.trimIndent()

    private fun entitlementsBody(expiresAt: Long?): String {
        val expiry = expiresAt?.toString() ?: "null"
        return """
            {"customerId":"cust_1","cursor":5,"entitlements":[{"entitlementId":"pro","isActive":true,"expiresAt":$expiry,"sources":[]}]}
        """.trimIndent()
    }

    private fun makeClient(
        now: () -> Long = { System.currentTimeMillis() },
        storage: RevnixStorage = MemoryStorage(),
        timeout: Duration = 10.seconds,
        entitlementsTtl: Duration = 30.seconds,
        readYourWritesDelays: List<Duration> =
            listOf(250.milliseconds, 500.milliseconds, 1.seconds, 2.seconds),
        onDiagnostic: ((RevnixDiagnostic) -> Unit)? = null,
    ): RevnixClient = RevnixClient(
        RevnixConfig(
            apiKey = "rvx_pk_test_abc",
            baseUrl = server.url("/").toString().trimEnd('/'),
            storage = storage,
            timeout = timeout,
            entitlementsTtl = entitlementsTtl,
            readYourWritesDelays = readYourWritesDelays,
            onDiagnostic = onDiagnostic,
            now = now,
            httpClient = OkHttpClient.Builder()
                .callTimeout(timeout.inWholeMilliseconds, java.util.concurrent.TimeUnit.MILLISECONDS)
                .build(),
        )
    ).also { clients += it }

    /** Route by path substring; each rule may serve a one-shot response first. */
    private fun route(vararg rules: Pair<String, (RecordedRequest) -> MockResponse>) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val rule = rules.firstOrNull { path.contains(it.first) }
                return rule?.second?.invoke(request)
                    ?: MockResponse().setResponseCode(404).setBody("""{"error":"no stub"}""")
            }
        }
    }

    private fun json(status: Int, body: String, headers: Map<String, String> = emptyMap()) =
        MockResponse().setResponseCode(status)
            .setHeader("Content-Type", "application/json")
            .apply { headers.forEach { (k, v) -> setHeader(k, v) } }
            .setBody(body)

    /** Serves [first] once, then [then] for every later call. */
    private fun onceThen(first: MockResponse, then: () -> MockResponse): (RecordedRequest) -> MockResponse {
        val calls = AtomicInteger(0)
        return { if (calls.getAndIncrement() == 0) first else then() }
    }

    /**
     * A dropped connection. `DISCONNECT_DURING_RESPONSE_BODY` is deterministic
     * where `DISCONNECT_AT_START` races OkHttp's connection retry.
     */
    private fun disconnect() = MockResponse()
        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        .setBody("{\"partial\":")

    // MARK: - Request timeout (spec: "C7 request timeout")

    @Test
    fun `hung request times out rather than hanging`() = runBlocking {
        route("/placements" to {
            MockResponse().apply { socketPolicy = SocketPolicy.NO_RESPONSE }
        })
        val client = makeClient(timeout = 500.milliseconds)
        val err = assertFailsWith<RevnixError.Timeout> { client.resolvePlacement("main") }
        assertTrue(err.isRetryable)
    }

    // MARK: - Typed errors (spec: "C7 typed errors")

    @Test
    fun `429 carries retryAfterMs`() = runBlocking {
        route("/placements" to {
            json(429, """{"error":"rate limit exceeded"}""", mapOf("Retry-After" to "2"))
        })
        val client = makeClient()
        val err = assertFailsWith<RevnixError.RateLimited> { client.resolvePlacement("main") }
        assertEquals(2000L, err.retryAfterMs)
        assertTrue(err.isRetryable)
    }

    @Test
    fun `Retry-After accepts an HTTP date and tolerates garbage`() {
        val now = java.time.Instant.ofEpochMilli(1_000_000)
        assertEquals(2000L, RevnixError.parseRetryAfter("2"))
        assertEquals(
            10_000L,
            RevnixError.parseRetryAfter(
                "Thu, 01 Jan 1970 00:16:50 GMT",
                java.time.Instant.ofEpochMilli(1_000_000),
            ),
        )
        assertNull(RevnixError.parseRetryAfter(null, now))
        assertNull(RevnixError.parseRetryAfter("not-a-date", now))
    }

    @Test
    fun `a network failure is typed as a network error`() = runBlocking {
        route("/placements" to { disconnect() })
        val client = makeClient()
        val err = assertFailsWith<RevnixError.Network> { client.resolvePlacement("main") }
        assertTrue(err.isRetryable)
    }

    // MARK: - Entitlement cache (spec: "C7 entitlement cache")

    @Test
    fun `entitlements happy path`() = runBlocking {
        route("/entitlements" to { json(200, entitlementsBody) })
        val client = makeClient()
        val result = client.entitlements()
        assertEquals(7L, result.cursor)
        assertEquals(false, result.stale)
        assertEquals("pro", result.entitlements.first().entitlementId)
        assertTrue(client.isEntitled("pro"))
        assertFalse(client.isEntitled("gold"))
    }

    @Test
    fun `falls back to cached entitlements when the network read fails`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) { disconnect() })
        val client = makeClient(entitlementsTtl = Duration.ZERO)

        val fresh = client.entitlements()
        assertEquals(false, fresh.stale)

        // Network is down now → the paying customer is still shown entitled.
        val cached = client.entitlements()
        assertEquals(true, cached.stale)
        assertEquals(true, cached.entitlements.first().isActive)
        assertTrue(client.isEntitled("pro"))
    }

    @Test
    fun `cachedEntitlements reads the cache with no network call`() = runBlocking {
        route("/entitlements" to { json(200, entitlementsBody) })
        val client = makeClient()

        assertNull(client.cachedEntitlements())
        client.entitlements()
        val cached = client.cachedEntitlements()
        assertEquals(true, cached?.stale)
        assertEquals("pro", cached?.entitlements?.first()?.entitlementId)
        assertEquals(1, server.requestCount)
    }

    // MARK: - Local expiry grace (spec: "REV-157 local expiry")

    private suspend fun cachedServe(expiresAt: Long?): Pair<RevnixClient, CustomerEntitlements> {
        route("/entitlements" to onceThen(json(200, entitlementsBody(expiresAt))) { disconnect() })
        val client = makeClient(entitlementsTtl = Duration.ZERO)
        val fresh = client.entitlements()
        // The fresh read is authoritative — served verbatim even when the
        // local clock disagrees.
        assertEquals(true, fresh.entitlements.first().isActive)
        return client to client.entitlements()
    }

    @Test
    fun `expired beyond the 3-day grace is served inactive from cache`() = runBlocking {
        val (client, cached) = cachedServe(System.currentTimeMillis() - 5 * dayMs)
        assertEquals(true, cached.stale)
        assertEquals(false, cached.entitlements.first().isActive)
        assertFalse(client.isEntitled("pro"))
    }

    @Test
    fun `expired within the grace is still active`() = runBlocking {
        // The renewal an offline device cannot see.
        val (client, cached) = cachedServe(System.currentTimeMillis() - 1 * dayMs)
        assertEquals(true, cached.stale)
        assertEquals(true, cached.entitlements.first().isActive)
        assertTrue(client.isEntitled("pro"))
    }

    @Test
    fun `no expiresAt is untouched`() = runBlocking {
        val (_, cached) = cachedServe(null)
        assertEquals(true, cached.entitlements.first().isActive)
    }

    @Test
    fun `cachedEntitlements applies the same expiry evaluation`() = runBlocking {
        val (client, _) = cachedServe(System.currentTimeMillis() - 5 * dayMs)
        assertEquals(false, client.cachedEntitlements()?.entitlements?.first()?.isActive)
    }

    // MARK: - Persisted purchase retry (spec: "C7 persisted purchase retry")

    private val purchase = RegisterPurchaseInput(
        source = RevnixStore.APPLE,
        token = "orig.1",
        productId = "pro.monthly",
        transactionId = "txn.1",
    )

    @Test
    fun `a failed registerPurchase is queued then drained on retry`() = runBlocking {
        route("/purchases" to { disconnect() })
        val storage = MemoryStorage()
        val client = makeClient(storage = storage)

        assertFailsWith<RevnixError.Network> { client.registerPurchase(purchase) }
        assertEquals(1, client.pendingPurchaseCount())
        // Persisted under the documented key, keyed source:token:transactionId.
        assertTrue(storage.get("revnix.pendingPurchases")!!.contains("apple:orig.1:txn.1"))

        // A later launch with connectivity drains it (idempotent server-side
        // via the shared purchaseKey). A FRESH client proves the queue
        // survived process death, not just in-memory state.
        route("/purchases" to { json(201, purchaseBody) })
        val relaunched = makeClient(storage = storage)
        assertEquals(1, relaunched.retryPendingPurchases())
        assertEquals(0, relaunched.pendingPurchaseCount())
    }

    @Test
    fun `the same failing purchase is not queued twice`() = runBlocking {
        route("/purchases" to { disconnect() })
        val client = makeClient()
        runCatching { client.registerPurchase(purchase) }
        runCatching { client.registerPurchase(purchase) }
        assertEquals(1, client.pendingPurchaseCount())
    }

    @Test
    fun `a deliberate purchase rejection is not queued`() = runBlocking {
        route("/purchases" to { json(409, """{"error":"blocked"}""") })
        val client = makeClient()
        val err = assertFailsWith<RevnixError.PurchaseBlocked> {
            client.registerPurchase(purchase)
        }
        assertFalse(err.isRetryable)
        assertEquals(0, client.pendingPurchaseCount())
    }

    // MARK: - Cache fallback discipline (spec: "REV-198")

    @Test
    fun `a revoked key 401 is not papered over by the cache`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) {
            json(401, """{"error":"revoked"}""")
        })
        val client = makeClient(entitlementsTtl = Duration.ZERO)
        client.entitlements() // cached
        val err = assertFailsWith<RevnixError.Auth> { client.entitlements() }
        assertEquals(401, err.status)
        assertFalse(err.isRetryable)
    }

    @Test
    fun `an unknown placement 404 is not papered over by the cache`() = runBlocking {
        route("/placements" to onceThen(json(200, placementBody)) {
            json(404, """{"error":"unknown placement"}""")
        })
        val client = makeClient()
        client.resolvePlacement("main") // cached
        assertFailsWith<RevnixError.NotFound> { client.resolvePlacement("main") }
        Unit
    }

    // MARK: - Paywall config decoding (REV-028 templates)

    @Test
    fun `a full templated paywall config decodes typed`() = runBlocking {
        route("/placements" to { json(200, paywallFullBody) })
        val client = makeClient()
        val paywall = assertNotNull(client.resolvePlacement("main").paywall)
        assertEquals("pw_1", paywall.paywallId)
        assertEquals("Summer promo", paywall.name)
        val config = paywall.config
        assertEquals("reveal", config.template)
        assertEquals("light", config.mode)
        assertEquals("Go Pro", config.headline)
        assertEquals("star", config.features.single().icon)
        assertEquals("pkg_1", config.highlightPackageId)
        assertEquals(4.8, config.review?.rating)
        assertEquals("Join 2M+ users", config.review?.count)
        assertEquals("PKR 9,999", config.offer?.strikethroughPrice)
        assertEquals("Ends tonight", config.offer?.urgencyText)
        assertEquals(false, config.footer?.showTerms)
        assertEquals("https://revnix.io/terms", config.footer?.termsUrl)
        assertNull(config.footer?.privacyUrl)
    }

    @Test
    fun `a legacy minimal paywall config still decodes`() = runBlocking {
        route("/placements" to { json(200, paywallLegacyBody) })
        val client = makeClient()
        val paywall = assertNotNull(client.resolvePlacement("main").paywall)
        val config = paywall.config
        assertEquals("focus", config.template)
        assertEquals("Unlock", config.headline)
        assertEquals("Feature one", config.features.single().title)
        assertNull(config.mode)
        assertNull(config.review)
        assertNull(config.offer)
        assertNull(config.footer)
    }

    // MARK: - Raw wire passthrough (bridges that render the paywall themselves)

    private val placementDesignedBody = """
        {"status":"ok","placementKey":"main","revision":5,"offering":{"offeringId":"off_3","displayName":"Designed","packages":[{"packageId":"pkg_3","productId":"pro.yearly"}]},"paywall":{"paywallId":"pw_1","name":"Main","config":{"template":"focus","headline":"Unlock","ctaLabel":"Go","blocks":{"version":1,"layout":"flow","blocks":[{"type":"hologram","spin":3}]},"futureField":"kept"}},"experiment":{"key":"summer-pricing","variantId":"var_b"}}
    """.trimIndent()

    /**
     * `paywallJson` / `experimentJson` are the wire values untouched: a block
     * type and a config field this SDK does not know survive there, while the
     * typed `paywall` still decodes beside them — and the cache stores the raw
     * copy, so offline hands a bridge the same document.
     */
    @Test
    fun `raw paywall and experiment survive beside the typed views`() = runBlocking {
        route("/placements" to onceThen(json(200, placementDesignedBody)) { disconnect() })
        val client = makeClient()
        val live = client.resolvePlacement("main")
        assertEquals("pw_1", live.paywall?.paywallId)
        val config = live.paywallJson!!.jsonObject["config"]!!.jsonObject
        assertEquals("kept", config["futureField"]!!.jsonPrimitive.content)
        val firstBlock = config["blocks"]!!.jsonObject["blocks"]!!.jsonArray.first().jsonObject
        assertEquals("hologram", firstBlock["type"]!!.jsonPrimitive.content)
        assertEquals("var_b", live.experimentJson!!.jsonObject["variantId"]!!.jsonPrimitive.content)

        // Offline now → the cached resolution carries the same raw document.
        val cached = client.resolvePlacement("main")
        assertEquals(live.paywallJson, cached.paywallJson)
        assertEquals(live.experimentJson, cached.experimentJson)
        assertEquals(live.paywall, cached.paywall)
    }

    /**
     * A paywall the typed model cannot read (no `headline` / `ctaLabel`, an
     * experiment missing `variantId`) must not fail the resolution — the
     * offering and the raw copies still arrive, the typed views are null.
     */
    @Test
    fun `an untypeable paywall still delivers the offering and the raw copies`() = runBlocking {
        route("/placements" to { json(200, """{"status":"ok","placementKey":"main","revision":6,"offering":{"offeringId":"off_4","displayName":"Blocks only","packages":[{"packageId":"pkg_4","productId":"pro.weekly"}]},"paywall":{"paywallId":"pw_2","name":"Next","config":{"template":"canvas","blocks":{"version":2,"layout":"grid","blocks":[]}}},"experiment":{"key":"k"}}""") })
        val client = makeClient()
        val resolution = client.resolvePlacement("main")
        assertEquals("off_4", resolution.offering.offeringId)
        assertNull(resolution.paywall)
        assertNull(resolution.experiment)
        assertEquals("pw_2", resolution.paywallJson!!.jsonObject["paywallId"]!!.jsonPrimitive.content)
        assertEquals("k", resolution.experimentJson!!.jsonObject["key"]!!.jsonPrimitive.content)
    }

    @Test
    fun `raw copies are null when the wire has none`() = runBlocking {
        route("/placements" to onceThen(json(200, placementNullExperimentBody)) { json(200, placementBody) })
        val client = makeClient()
        val nullCase = client.resolvePlacement("main")
        assertNull(nullCase.paywallJson)
        assertNull(nullCase.experimentJson)
        val absentCase = client.resolvePlacement("main")
        assertNull(absentCase.paywallJson)
        assertNull(absentCase.experimentJson)
    }

    // MARK: - Experiments (REV-219)

    @Test
    fun `an experiment assignment decodes and round-trips through the cache`() = runBlocking {
        route("/placements" to onceThen(json(200, placementExperimentBody)) { disconnect() })
        val client = makeClient()
        val live = client.resolvePlacement("main")
        assertEquals("summer-price-test", live.experiment?.key)
        assertEquals("var_b", live.experiment?.variantId)
        assertEquals("off_2", live.offering.offeringId)

        // Offline now → the cached resolution keeps the assignment.
        val cached = client.resolvePlacement("main")
        assertEquals("summer-price-test", cached.experiment?.key)
        assertEquals("var_b", cached.experiment?.variantId)
    }

    @Test
    fun `a null or absent experiment field decodes as null`() = runBlocking {
        route("/placements" to onceThen(json(200, placementNullExperimentBody)) {
            json(200, placementBody)
        })
        val client = makeClient()
        assertNull(client.resolvePlacement("main").experiment) // explicit null
        assertNull(client.resolvePlacement("main").experiment) // absent (older server)
    }

    @Test
    fun `the resolve request carries the customer id for sticky assignment`() = runBlocking {
        route("/placements" to { json(200, placementBody) })
        val client = makeClient()
        client.resolvePlacement("main")
        val sent = server.takeRequest()
        assertEquals(client.customerId(), sent.requestUrl?.queryParameter("customer"))
    }

    @Test
    fun `a 500 still falls back to the cache`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) {
            json(500, """{"error":"boom"}""")
        })
        val client = makeClient(entitlementsTtl = Duration.ZERO)
        client.entitlements()
        val cached = client.entitlements()
        assertEquals(true, cached.stale)
        assertEquals(true, cached.entitlements.first().isActive)
    }

    // MARK: - Offline cache age bound (spec: "REV-198")

    @Test
    fun `the cache age ceiling serves inactive`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) {
            json(500, """{"error":"down"}""")
        })
        val clock = TestClock(System.currentTimeMillis())
        val client = makeClient(now = clock::now)
        client.entitlements()

        // 15 days later, offline: past the 14-day ceiling → all inactive.
        clock.advance(15 * dayMs)
        val served = client.entitlements()
        assertEquals(true, served.stale)
        assertEquals(false, served.entitlements.first().isActive)
    }

    @Test
    fun `within the offline window it still grants`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) {
            json(500, """{"error":"down"}""")
        })
        val clock = TestClock(System.currentTimeMillis())
        val client = makeClient(now = clock::now)
        client.entitlements()

        clock.advance(13 * dayMs)
        val served = client.entitlements()
        assertEquals(true, served.stale)
        assertEquals(true, served.entitlements.first().isActive)
    }

    @Test
    fun `a rolled-back clock serves inactive`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) {
            json(500, """{"error":"down"}""")
        })
        val clock = TestClock(System.currentTimeMillis())
        val client = makeClient(now = clock::now)
        client.entitlements()

        // Roll the clock back 30 minutes, go offline → all inactive.
        clock.advance(-30 * 60 * 1000)
        val served = client.entitlements()
        assertEquals(true, served.stale)
        assertEquals(false, served.entitlements.first().isActive)
    }

    // MARK: - Soft TTL + coalescing (spec: "REV-199")

    @Test
    fun `reads within the TTL cost one request and concurrent reads share one`() = runBlocking {
        route("/entitlements" to { json(200, entitlementsBody) })
        val client = makeClient()

        // Concurrent burst — a screen full of gates → one request.
        listOf(
            async { client.isEntitled("pro") },
            async { client.isEntitled("pro") },
            async { client.entitlements() },
        ).awaitAll()
        assertEquals(1, server.requestCount)

        // Within the TTL → still one.
        client.entitlements()
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a zero TTL restores always-fetch`() = runBlocking {
        route("/entitlements" to { json(200, entitlementsBody) })
        val client = makeClient(entitlementsTtl = Duration.ZERO)
        client.entitlements()
        client.entitlements()
        assertEquals(2, server.requestCount)
    }

    /**
     * Regression: `waitForEntitlements` must bypass the soft TTL. Polling
     * through the TTL re-read the SAME cached snapshot, so the cursor never
     * advanced and every post-purchase unlock spun until it gave up whenever a
     * gate had been checked in the preceding 30 s.
     */
    @Test
    fun `waitForEntitlements bypasses the soft TTL`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) {
            json(
                200,
                """{"customerId":"cust_1","cursor":9,"entitlements":[{"entitlementId":"pro","isActive":true,"expiresAt":4102444800000,"sources":[]}]}""",
            )
        })
        // Default 30 s TTL stays ON — that is the point of the regression.
        val client = makeClient()
        assertEquals(7L, client.entitlements().cursor)

        val settled = client.waitForEntitlements(9)
        assertEquals(9L, settled.cursor)
        assertEquals(false, settled.stale)
    }

    @Test
    fun `waitForEntitlements returns the last read when the cursor never catches up`() =
        runBlocking {
            route("/entitlements" to { json(200, entitlementsBody) })
            val client = makeClient(
                readYourWritesDelays = listOf(10.milliseconds, 10.milliseconds),
            )
            assertEquals(7L, client.waitForEntitlements(999).cursor)
        }

    // MARK: - Diagnostics (spec: "REV-200")

    @Test
    fun `a background failure reaches diagnostics and rides the next request header`() =
        runBlocking {
            val storage = MemoryStorage()
            // A queued purchase from a "previous launch" whose retry will fail.
            storage.set(
                "revnix.pendingPurchases",
                """[{"key":"apple:otx-9:tx-9","source":"apple","token":"otx-9","productId":"pro.monthly","transactionId":"tx-9"}]""",
            )
            route(
                "/purchases" to { disconnect() },
                "/entitlements" to { json(200, entitlementsBody) },
            )
            val events = mutableListOf<String>()
            val client = makeClient(
                storage = storage,
                entitlementsTtl = Duration.ZERO,
                onDiagnostic = { events += it.op },
            )

            assertEquals(0, client.retryPendingPurchases())
            assertTrue(events.isNotEmpty())
            // Retryable failure → the item stays queued for the next launch.
            assertEquals(1, client.pendingPurchaseCount())

            // The counter rides the next successful request…
            client.entitlements()
            val withHeader = server.takeRequest()
            val seen = generateSequence { server.takeRequest(1, java.util.concurrent.TimeUnit.MILLISECONDS) }
            // Find the entitlements request and assert the header rode along.
            var header: String? = withHeader.getHeader("X-Revnix-Bg-Failures")
            if (withHeader.path?.contains("/entitlements") != true) {
                header = seen.firstOrNull { it.path?.contains("/entitlements") == true }
                    ?.getHeader("X-Revnix-Bg-Failures")
            }
            assertNotNull(header)
            assertTrue(header.toInt() > 0)

            // …and is cleared once delivered.
            client.entitlements()
            val next = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
                .firstOrNull { it.path?.contains("/entitlements") == true }
            assertNull(next?.getHeader("X-Revnix-Bg-Failures"))
        }

    @Test
    fun `a diagnostic handler never masks the underlying error`() = runBlocking {
        route("/entitlements" to { disconnect() })
        val events = mutableListOf<String>()
        val client = makeClient(onDiagnostic = { events += it.op })
        assertFailsWith<RevnixError.Network> { client.entitlements() }
        Unit
    }
}

/** Mutable test clock. */
private class TestClock(private var current: Long) {
    fun now(): Long = current
    fun advance(ms: Long) {
        current += ms
    }
}
