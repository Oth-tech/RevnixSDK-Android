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
import kotlinx.serialization.json.long
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
        device: DeviceFacts? = fixedDevice,
        onImplicitPaywall: ((RevnixImplicitTrigger) -> Unit)? = null,
        implicitPlacements: Boolean? = null,
        onDeferredDeepLink: ((String, DeferredDeepLinkMatch) -> Unit)? = null,
        onAttribution: ((RevnixAttribution) -> Unit)? = null,
        lifecycle: RevnixLifecycle? = null,
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
            lifecycle = lifecycle,
            device = device,
            onImplicitPaywall = onImplicitPaywall,
            implicitPlacements = implicitPlacements,
            onDeferredDeepLink = onDeferredDeepLink,
            onAttribution = onAttribution,
        )
    ).also { clients += it }

    /** REV-268: a fixed device so the header is deterministic under test. */
    private val fixedDevice = DeviceFacts(
        platform = "android", osVersion = "15", appVersion = "1.2.10", locale = "en-US",
        currency = "USD", storefront = "US", model = "Pixel 8", sandbox = true,
    )

    /** Decode the base64url JSON the client put in X-Revnix-Device. */
    private fun decodeDeviceHeader(header: String) =
        kotlinx.serialization.json.Json.parseToJsonElement(
            String(java.util.Base64.getUrlDecoder().decode(header), Charsets.UTF_8)
        ).jsonObject

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
    fun `isEntitled answers false for a revoked key even with an active entitlement cached`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) {
            json(401, """{"error":"revoked"}""")
        })
        val client = makeClient(entitlementsTtl = Duration.ZERO)
        client.entitlements()
        assertFalse(client.isEntitled("pro"))
    }

    @Test
    fun `isEntitled still serves the cache on a transient failure`() = runBlocking {
        route("/entitlements" to onceThen(json(200, entitlementsBody)) {
            json(503, """{"error":"unavailable"}""")
        })
        val client = makeClient(entitlementsTtl = Duration.ZERO)
        client.entitlements()
        assertTrue(client.isEntitled("pro"))
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

    // MARK: - Device attribute contract (REV-268)

    @Test
    fun `every resolve carries the device facts with the SDK-owned fields added`() = runBlocking {
        route("/placements" to { json(200, placementBody) })
        val storage = MemoryStorage()
        val client = makeClient(now = { 1_700_000_000_000 }, storage = storage)
        client.resolvePlacement("main")
        val header = server.takeRequest().getHeader("X-Revnix-Device")
        assertNotNull(header)
        val facts = decodeDeviceHeader(header)
        assertEquals("android", facts["platform"]?.jsonPrimitive?.content)
        assertEquals("15", facts["osVersion"]?.jsonPrimitive?.content)
        assertEquals("1.2.10", facts["appVersion"]?.jsonPrimitive?.content)
        assertEquals("en-US", facts["locale"]?.jsonPrimitive?.content)
        assertEquals("USD", facts["currency"]?.jsonPrimitive?.content)
        assertEquals("US", facts["storefront"]?.jsonPrimitive?.content)
        assertEquals("Pixel 8", facts["model"]?.jsonPrimitive?.content)
        assertEquals("true", facts["sandbox"]?.jsonPrimitive?.content)
        assertEquals("1700000000000", facts["installedAt"]?.jsonPrimitive?.content)
        assertEquals("true", facts["firstOpen"]?.jsonPrimitive?.content)
        assertNotNull(facts["sdkVersion"])
        assertEquals("1700000000000", storage.get("revnix.installedAt"))

        // A later session on the same storage: same install date, not the
        // first open any more.
        val later = makeClient(now = { 1_700_086_400_000 }, storage = storage)
        later.resolvePlacement("main")
        val second = decodeDeviceHeader(server.takeRequest().getHeader("X-Revnix-Device")!!)
        assertEquals("1700000000000", second["installedAt"]?.jsonPrimitive?.content)
        assertEquals("false", second["firstOpen"]?.jsonPrimitive?.content)
    }

    @Test
    fun `device facts can be disabled and the header is then absent`() = runBlocking {
        route("/placements" to { json(200, placementBody) })
        val client = makeClient(device = null)
        client.resolvePlacement("main")
        assertNull(server.takeRequest().getHeader("X-Revnix-Device"))
    }

    // MARK: - Reinstall handling (MS2)

    @Test
    fun `registerInstall carries deviceKey in the body but never in the X-Revnix-Device header`() = runBlocking {
        route(
            "/placements" to { json(200, placementBody) },
            "v1/installs" to { json(200, "{}") },
        )
        val client = makeClient(device = fixedDevice.copy(deviceKey = "dk_test"))

        client.resolvePlacement("main")
        val header = decodeDeviceHeader(server.takeRequest().getHeader("X-Revnix-Device")!!)
        assertNull(header["deviceKey"])

        client.registerInstall()
        val installBody = requestBody(server.takeRequest())
        assertEquals("dk_test", installBody["deviceKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `registerInstall omits deviceKey when the device facts have none`() = runBlocking {
        route("v1/installs" to { json(200, "{}") })
        val client = makeClient(device = fixedDevice)
        client.registerInstall()
        val body = requestBody(server.takeRequest())
        assertNull(body["deviceKey"])
    }

    @Test
    fun `handleInstallReferrer also carries deviceKey`() = runBlocking {
        route("v1/installs" to { json(200, "{}") })
        val client = makeClient(device = fixedDevice.copy(deviceKey = "dk_test"))
        client.handleInstallReferrer("utm_source=x")
        val body = requestBody(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!)
        assertEquals("dk_test", body["deviceKey"]!!.jsonPrimitive.content)
    }

    @Test
    fun `overriddenBy keeps deviceKey when the override has none, and overrides it when set`() {
        val base = fixedDevice.copy(deviceKey = "dk_base")
        val unchanged = base.overriddenBy(DeviceFacts(platform = "android"))
        assertEquals("dk_base", unchanged.deviceKey)

        val overridden = base.overriddenBy(DeviceFacts(deviceKey = "dk_override"))
        assertEquals("dk_override", overridden.deviceKey)
    }

    @Test
    fun `detect answers from the running JVM`() {
        val facts = DeviceFacts.detect()
        assertNotNull(facts.locale)
        // No Android on a plain JVM test run: platform and model stay unknown
        // rather than guessed.
        assertNull(facts.platform)
        assertNull(facts.model)
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

    private val previewToken = "a".repeat(64)

    private val previewBodyWithoutStatusRevisionOffering = """
        {"placementKey":"revnix_preview","revision":null,"offering":null,"paywall":{"paywallId":"pw_preview","name":"Preview","config":{"template":"focus","headline":"Preview","features":[],"ctaLabel":"Continue"}},"experiment":null,"targeting":null,"preview":true,"expiresAt":1800000000000}
    """.trimIndent()

    @Test
    fun `a preview link fetches the preview and hands it to onImplicitPaywall, never triggered`() =
        runBlocking {
            route(
                "v1/config" to { json(200, """{"implicitPlacements":["deeplink_open"]}""") },
                "paywalls/preview/$previewToken" to { json(200, previewBodyWithoutStatusRevisionOffering) },
                "placements/triggered" to { json(200, paywallLegacyBody) },
            )
            val seen = mutableListOf<RevnixImplicitTrigger>()
            val client = makeClient(onImplicitPaywall = { seen += it })

            client.handleDeepLink("voigu://revnix-preview?revnix_preview=$previewToken")

            val requests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
            assertTrue(requests.any { it.path.orEmpty().contains("paywalls/preview/$previewToken") })
            assertTrue(requests.none { it.path.orEmpty().contains("placements/triggered") })
            assertEquals(1, seen.size)
            assertEquals(RevnixImplicitPlacement.DEEPLINK_OPEN, seen.single().placement)
            assertEquals(REVNIX_PREVIEW_PLACEMENT_KEY, seen.single().resolution.placementKey)
            assertEquals(true, seen.single().resolution.preview)
        }

    @Test
    fun `a preview body with null revision, no status and null offering parses`() = runBlocking {
        route("paywalls/preview/$previewToken" to { json(200, previewBodyWithoutStatusRevisionOffering) })
        val seen = mutableListOf<RevnixImplicitTrigger>()
        val client = makeClient(onImplicitPaywall = { seen += it }, implicitPlacements = false)

        client.handleDeepLink("voigu://revnix-preview?revnix_preview=$previewToken")

        val resolution = seen.single().resolution
        assertEquals("ok", resolution.status)
        assertEquals(0L, resolution.revision)
        assertEquals("", resolution.offering.offeringId)
        assertEquals("pw_preview", resolution.paywall?.paywallId)
    }

    @Test
    fun `a preview on the first frame waits for the launch batch, presenting after it`() =
        runBlocking {
            route(
                "v1/config" to { json(200, """{"implicitPlacements":["app_launch"]}""") },
                "placements/triggered" to { json(200, paywallLegacyBody) },
                "paywalls/preview/$previewToken" to { json(200, previewBodyWithoutStatusRevisionOffering) },
            )
            val seen = mutableListOf<RevnixImplicitTrigger>()
            val client = makeClient(onImplicitPaywall = { seen += it })
            assertTrue(
                server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.path!!.contains("v1/config"),
            )

            client.handleDeepLink("voigu://revnix-preview?revnix_preview=$previewToken")

            assertEquals(
                listOf(RevnixImplicitPlacement.APP_LAUNCH, RevnixImplicitPlacement.DEEPLINK_OPEN),
                seen.map { it.placement },
            )
            assertEquals(REVNIX_PREVIEW_PLACEMENT_KEY, seen.last().resolution.placementKey)
        }

    @Test
    fun `no onImplicitPaywall handler configured, a preview does not throw`() = runBlocking {
        route("paywalls/preview/$previewToken" to { json(200, previewBodyWithoutStatusRevisionOffering) })
        val client = makeClient(implicitPlacements = false)

        client.handleDeepLink("voigu://revnix-preview?revnix_preview=$previewToken")
    }

    @Test
    fun `a malformed token falls through to the ordinary deep-link path`() = runBlocking {
        route(
            "v1/config" to { json(200, """{"implicitPlacements":["deeplink_open"]}""") },
            "placements/triggered" to { json(200, paywallLegacyBody) },
        )
        val seen = mutableListOf<RevnixImplicitTrigger>()
        val client = makeClient(onImplicitPaywall = { seen += it })

        client.handleDeepLink("voigu://revnix-preview?revnix_preview=not-hex")

        val requests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        assertTrue(requests.any { it.path.orEmpty().contains("placements/triggered") })
        assertEquals(RevnixImplicitPlacement.DEEPLINK_OPEN, seen.single().placement)
    }

    @Test
    fun `an ordinary URL is unaffected`() = runBlocking {
        route(
            "v1/config" to { json(200, """{"implicitPlacements":["deeplink_open"]}""") },
            "placements/triggered" to { json(200, paywallLegacyBody) },
        )
        val seen = mutableListOf<RevnixImplicitTrigger>()
        val client = makeClient(onImplicitPaywall = { seen += it })

        client.handleDeepLink("https://example.com/promo")

        val requests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        assertTrue(requests.any { it.path.orEmpty().contains("placements/triggered") })
        assertEquals(RevnixImplicitPlacement.DEEPLINK_OPEN, seen.single().placement)
    }

    @Test
    fun `no handler, a deep link reports link facts only with resolve false and no config read`() =
        runBlocking {
            route("placements/triggered" to { json(200, paywallLegacyBody) })
            val client = makeClient()

            client.handleDeepLink("https://example.com/promo?utm_source=ig")

            val requests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
            assertEquals(1, requests.size)
            assertTrue(requests.single().path.orEmpty().contains("placements/triggered"))
            val sent = kotlinx.serialization.json.Json.parseToJsonElement(
                requests.single().body.readUtf8(),
            ).jsonObject
            assertEquals("https://example.com/promo?utm_source=ig", sent["url"]!!.jsonPrimitive.content)
            assertEquals(false, sent["resolve"]!!.jsonPrimitive.content.toBoolean())
        }

    @Test
    fun `implicit placements off with a handler, resolve false and the handler never fires`() =
        runBlocking {
            route("placements/triggered" to { json(200, paywallLegacyBody) })
            val seen = mutableListOf<RevnixImplicitTrigger>()
            val client = makeClient(onImplicitPaywall = { seen += it }, implicitPlacements = false)

            client.handleDeepLink("https://example.com/promo")

            val requests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
            val sent = kotlinx.serialization.json.Json.parseToJsonElement(
                requests.single { it.path.orEmpty().contains("placements/triggered") }.body.readUtf8(),
            ).jsonObject
            assertEquals(false, sent["resolve"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(seen.isEmpty())
        }

    @Test
    fun `implicit placements on with a handler, the trigger body carries no resolve key`() =
        runBlocking {
            route(
                "v1/config" to { json(200, """{"implicitPlacements":["deeplink_open"]}""") },
                "placements/triggered" to { json(200, paywallLegacyBody) },
            )
            val seen = mutableListOf<RevnixImplicitTrigger>()
            val client = makeClient(onImplicitPaywall = { seen += it })

            client.handleDeepLink("https://example.com/promo")

            val requests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
            val sent = kotlinx.serialization.json.Json.parseToJsonElement(
                requests.single { it.path.orEmpty().contains("placements/triggered") }.body.readUtf8(),
            ).jsonObject
            assertFalse(sent.containsKey("resolve"))
            assertEquals(RevnixImplicitPlacement.DEEPLINK_OPEN, seen.single().placement)
            assertNotNull(seen.single().resolution.paywall)
        }

    @Test
    fun `implicit on, config lists only paywall_decline, the deep-link trigger resolves false and never presents`() =
        runBlocking {
            route(
                "v1/config" to { json(200, """{"implicitPlacements":["paywall_decline"]}""") },
                "placements/triggered" to { json(200, paywallLegacyBody) },
            )
            val seen = mutableListOf<RevnixImplicitTrigger>()
            val client = makeClient(onImplicitPaywall = { seen += it })
            assertTrue(
                server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.path!!.contains("v1/config"),
            )

            client.handleDeepLink("https://example.com/promo")

            val requests = generateSequence { server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS) }.toList()
            val deepLinkBody = requests
                .filter { it.path.orEmpty().contains("placements/triggered") }
                .map { kotlinx.serialization.json.Json.parseToJsonElement(it.body.readUtf8()).jsonObject }
                .single { it["placement"]!!.jsonPrimitive.content == "deeplink_open" }
            assertEquals(false, deepLinkBody["resolve"]!!.jsonPrimitive.content.toBoolean())
            assertTrue(seen.isEmpty())
        }

    private fun fakeLifecycle(): Pair<RevnixLifecycle, () -> ((RevnixAppState) -> Unit)?> {
        var handler: ((RevnixAppState) -> Unit)? = null
        val lifecycle = RevnixLifecycle { h -> handler = h; { handler = null } }
        return lifecycle to { handler }
    }

    private fun sessionStartTriggeredBodies(timeoutMs: Long = 5000) =
        generateSequence { server.takeRequest(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }
            .filter { it.path.orEmpty().contains("placements/triggered") }
            .map { kotlinx.serialization.json.Json.parseToJsonElement(it.body.readUtf8()).jsonObject }
            .filter { it["placement"]!!.jsonPrimitive.content == "session_start" }
            .toList()

    @Test
    fun `a session that timed out reports the previous session's wall-clock length`() = runBlocking {
        route(
            "v1/config" to { json(200, """{"implicitPlacements":["session_start"]}""") },
            "placements/triggered" to { json(200, """{"status":"ok","paywall":null}""") },
        )
        val t0 = 1_700_000_000_000L
        var current = t0
        val (lifecycle, handler) = fakeLifecycle()
        makeClient(now = { current }, lifecycle = lifecycle, onImplicitPaywall = { })

        val coldStart = sessionStartTriggeredBodies(500).also {
            assertEquals(1, it.size)
        }
        assertFalse(coldStart.single().containsKey("previousSessionMs"))

        current = t0 + 10 * 60_000
        handler()!!.invoke(RevnixAppState.BACKGROUND)
        current = t0 + 41 * 60_000
        handler()!!.invoke(RevnixAppState.FOREGROUND)

        val second = sessionStartTriggeredBodies().single()
        second["previousSessionMs"]!!.jsonPrimitive.let {
            assertFalse(it.isString)
            assertEquals(600_000L, it.long)
        }
    }

    @Test
    fun `a previous session started before a cold start still reports its length`() = runBlocking {
        route(
            "v1/config" to { json(200, """{"implicitPlacements":["session_start"]}""") },
            "placements/triggered" to { json(200, """{"status":"ok","paywall":null}""") },
        )
        val storage = MemoryStorage()
        val t0 = 1_700_000_000_000L
        var current = t0
        val (lifecycleA, handlerA) = fakeLifecycle()
        makeClient(now = { current }, storage = storage, lifecycle = lifecycleA, onImplicitPaywall = { })
        assertEquals(1, sessionStartTriggeredBodies(500).size)

        current = t0 + 5 * 60_000
        handlerA()!!.invoke(RevnixAppState.BACKGROUND)

        current = t0 + 2 * 3600_000
        makeClient(now = { current }, storage = storage, onImplicitPaywall = { })

        val coldStartB = sessionStartTriggeredBodies().single()
        coldStartB["previousSessionMs"]!!.jsonPrimitive.let {
            assertFalse(it.isString)
            assertEquals(300_000L, it.long)
        }
    }

    @Test
    fun `a 404 preview never throws and never presents`() = runBlocking {
        route("paywalls/preview/$previewToken" to { json(404, """{"error":"not found"}""") })
        val seen = mutableListOf<RevnixImplicitTrigger>()
        val diagnostics = mutableListOf<String>()
        val client = makeClient(
            onImplicitPaywall = { seen += it },
            implicitPlacements = false,
            onDiagnostic = { diagnostics += it.op },
        )

        client.handleDeepLink("voigu://revnix-preview?revnix_preview=$previewToken")

        assertTrue(seen.isEmpty())
        assertTrue(diagnostics.contains("preview"))
    }

    @Test
    fun `resolveDeepLink returns the server's unwrapped url and requests v1 links resolve`() = runBlocking {
        val wrapped = "https://click.mailchimp.com/track/abc"
        route("v1/links/resolve" to {
            json(200, """{"url":"com.voigu.app://promo?utm_source=x","hops":2}""")
        })
        val client = makeClient()

        val resolved = client.resolveDeepLink(wrapped)

        assertEquals("com.voigu.app://promo?utm_source=x", resolved)
        val request = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertNotNull(request)
        assertTrue(request.path.orEmpty().startsWith("/v1/links/resolve?url="))
        assertTrue(request.path.orEmpty().contains(java.net.URLEncoder.encode(wrapped, "UTF-8")))
    }

    @Test
    fun `resolveDeepLink never throws, returning the input when the transport fails`() = runBlocking {
        route("v1/links/resolve" to { disconnect() })
        val client = makeClient()

        val resolved = client.resolveDeepLink("https://click.mailchimp.com/track/abc")

        assertEquals("https://click.mailchimp.com/track/abc", resolved)
    }

    @Test
    fun `getLastDeepLink is null before any link`() = runBlocking {
        val client = makeClient()
        assertNull(client.getLastDeepLink())
    }

    @Test
    fun `handleDeepLink records the full url and receivedAt`() = runBlocking {
        route("placements/triggered" to { json(200, paywallLegacyBody) })
        val clock = TestClock(1_000L)
        val client = makeClient(now = clock::now)

        client.handleDeepLink("https://example.com/promo?utm_source=ig")

        assertEquals(
            LastDeepLink("https://example.com/promo?utm_source=ig", 1_000L),
            client.getLastDeepLink(),
        )
    }

    @Test
    fun `a later deep link overwrites the stored one`() = runBlocking {
        route("placements/triggered" to { json(200, paywallLegacyBody) })
        val clock = TestClock(1_000L)
        val client = makeClient(now = clock::now)

        client.handleDeepLink("https://example.com/first")
        clock.advance(500)
        client.handleDeepLink("https://example.com/second")

        assertEquals(
            LastDeepLink("https://example.com/second", 1_500L),
            client.getLastDeepLink(),
        )
    }

    @Test
    fun `a dashboard preview link is not recorded`() = runBlocking {
        route("paywalls/preview/$previewToken" to { json(200, previewBodyWithoutStatusRevisionOffering) })
        val client = makeClient(implicitPlacements = false)

        client.handleDeepLink("voigu://revnix-preview?revnix_preview=$previewToken")

        assertNull(client.getLastDeepLink())
    }

    @Test
    fun `a delivered deferred link is recorded`() = runBlocking {
        route("v1/installs" to { json(200, """{"deferredDeepLink":{"url":"https://revnix.io/promo","match":"exact"}}""") })
        val clock = TestClock(2_000L)
        val latch = java.util.concurrent.CountDownLatch(1)
        val client = makeClient(now = clock::now, onDeferredDeepLink = { _, _ -> latch.countDown() })

        client.handleInstallReferrer("utm_source=x")
        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(LastDeepLink("https://revnix.io/promo", 2_000L), client.getLastDeepLink())
    }

    @Test
    fun `a malformed stored value returns null`() = runBlocking {
        val storage = MemoryStorage()
        storage.set("revnix.lastDeepLink", "not json")
        val client = makeClient(storage = storage)

        assertNull(client.getLastDeepLink())
    }

    @Test
    fun `an ordinary app-scheme link and an over-length https link skip the request`() = runBlocking {
        route("v1/links/resolve" to { json(200, """{"url":"never","hops":1}""") })
        val client = makeClient()
        val plain = "com.voigu.app://promo"
        val overLong = "https://click.mailchimp.com/" + "x".repeat(1024)

        assertEquals(plain, client.resolveDeepLink(plain))
        assertEquals(overLong, client.resolveDeepLink(overLong))

        assertNull(server.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a coroutine cancellation while resolving propagates rather than returning the input`() = runBlocking {
        val cancelling = OkHttpClient.Builder()
            .addInterceptor { throw kotlinx.coroutines.CancellationException("boom") }
            .build()
        val client = RevnixClient(
            RevnixConfig(
                apiKey = "rvx_pk_test_abc",
                baseUrl = server.url("/").toString().trimEnd('/'),
                storage = MemoryStorage(),
                httpClient = cancelling,
            )
        ).also { clients += it }

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            client.resolveDeepLink("https://click.mailchimp.com/track/abc")
        }
        Unit
    }

    @Test
    fun `logPaywallDisplay (logPaywallShown) with the preview key sends no request`() = runBlocking {
        val client = makeClient()
        val viewId = client.logPaywallDisplay(REVNIX_PREVIEW_PLACEMENT_KEY, "pw_preview")
        assertNotNull(viewId)
        assertNull(server.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `logAdRevenue posts the beacon, and zero revenue sends nothing`() = runBlocking {
        route("v1/ad-revenue" to { json(200, "{}") })
        val client = makeClient()

        client.logAdRevenue(
            revenue = 0.0032,
            currency = "USD",
            network = "admob",
            mediation = "applovin_max",
            adUnit = "banner_home",
            placement = "home_footer",
            format = "banner",
            eventId = "evt_1",
        )

        val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/ad-revenue", request.path)
        val body = requestBody(request)
        assertEquals(client.customerId(), body["customerId"]!!.jsonPrimitive.content)
        assertEquals(0.0032, body["revenue"]!!.jsonPrimitive.content.toDouble())
        assertEquals("USD", body["currency"]!!.jsonPrimitive.content)
        assertEquals("admob", body["network"]!!.jsonPrimitive.content)
        assertEquals("applovin_max", body["mediation"]!!.jsonPrimitive.content)
        assertEquals("banner_home", body["adUnit"]!!.jsonPrimitive.content)
        assertEquals("home_footer", body["placement"]!!.jsonPrimitive.content)
        assertEquals("banner", body["format"]!!.jsonPrimitive.content)
        assertEquals("evt_1", body["eventId"]!!.jsonPrimitive.content)

        client.logAdRevenue(revenue = 0.0, currency = "USD")
        assertNull(server.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `setAttribution posts the beacon with only the set optional fields`() = runBlocking {
        route("v1/attribution" to { json(200, "{}") })
        val client = makeClient()

        client.setAttribution(
            provider = "adjust",
            network = "Facebook Installs",
            campaign = "summer_sale",
        )

        val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/attribution", request.path)
        val body = requestBody(request)
        assertEquals(client.customerId(), body["customerId"]!!.jsonPrimitive.content)
        assertEquals("adjust", body["provider"]!!.jsonPrimitive.content)
        assertEquals("Facebook Installs", body["network"]!!.jsonPrimitive.content)
        assertEquals("summer_sale", body["campaign"]!!.jsonPrimitive.content)
        assertNull(body["adGroup"])
        assertNull(body["creative"])
    }

    @Test
    fun `setAttribution an identical repeat sends nothing, a changed payload sends again`() = runBlocking {
        route("v1/attribution" to { json(200, "{}") })
        val client = makeClient()

        client.setAttribution(provider = "adjust", network = "Facebook Installs")
        assertEquals("POST", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.method)

        client.setAttribution(provider = "adjust", network = "Facebook Installs")
        assertNull(server.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS))

        client.setAttribution(provider = "adjust", network = "Google Installs")
        assertEquals("POST", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.method)
    }

    @Test
    fun `setPushToken posts the beacon then an identical repeat sends nothing`() = runBlocking {
        route("v1/push-token" to { json(200, "{}") })
        val client = makeClient()

        client.setPushToken("abc123")
        val request = server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/push-token", request.path)
        val body = requestBody(request)
        assertEquals(client.customerId(), body["customerId"]!!.jsonPrimitive.content)
        assertEquals("android", body["platform"]!!.jsonPrimitive.content)
        assertEquals("abc123", body["token"]!!.jsonPrimitive.content)

        client.setPushToken("abc123")
        assertNull(server.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS))

        client.setPushToken("def456")
        assertEquals("POST", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.method)

        client.logout()
        client.setPushToken("def456")
        assertEquals("POST", server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!.method)
    }

    private fun requestBody(request: RecordedRequest) =
        kotlinx.serialization.json.Json.parseToJsonElement(request.body.readUtf8()).jsonObject

    @Test
    fun `handleInstallReferrer posts the referrer verbatim, truncated at 1024`() = runBlocking {
        route("v1/installs" to { json(200, "{}") })
        val client = makeClient()
        val referrer = "utm_source=instagram&utm_medium=cpc" + "x".repeat(2000)

        client.handleInstallReferrer(referrer)

        val body = requestBody(server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)!!)
        assertEquals(client.customerId(), body["customerId"]!!.jsonPrimitive.content)
        assertEquals(referrer.take(1024), body["installReferrer"]!!.jsonPrimitive.content)
    }

    @Test
    fun `handleInstallReferrer refuses a blank referrer`() = runBlocking {
        val client = makeClient()
        client.handleInstallReferrer("   ")
        assertNull(server.takeRequest(50, java.util.concurrent.TimeUnit.MILLISECONDS))
    }

    @Test
    fun `a deferredDeepLink on the handleInstallReferrer response fires the callback once, exact`() =
        runBlocking {
            route("v1/installs" to { json(200, """{"deferredDeepLink":{"url":"https://revnix.io/promo","match":"exact"}}""") })
            val latch = java.util.concurrent.CountDownLatch(1)
            val seen = mutableListOf<Pair<String, DeferredDeepLinkMatch>>()
            val client = makeClient(onDeferredDeepLink = { url, match -> seen += url to match; latch.countDown() })

            client.handleInstallReferrer("utm_source=x")
            assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))

            assertEquals(listOf("https://revnix.io/promo" to DeferredDeepLinkMatch.EXACT), seen)
        }

    @Test
    fun `a second deferredDeepLink response does not fire the callback again`() = runBlocking {
        route("v1/installs" to { json(200, """{"deferredDeepLink":{"url":"https://revnix.io/promo","match":"exact"}}""") })
        val storage = MemoryStorage()
        val seen = mutableListOf<String>()
        val latch1 = java.util.concurrent.CountDownLatch(1)
        val client = makeClient(
            storage = storage,
            onDeferredDeepLink = { url, _ -> seen += url; latch1.countDown() },
        )

        client.handleInstallReferrer("utm_source=x")
        assertTrue(latch1.await(2, java.util.concurrent.TimeUnit.SECONDS))
        server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)

        client.logout()
        client.registerInstall()

        assertEquals(1, seen.size)
    }

    @Test
    fun `two concurrent handleInstallReferrer calls with a slow storage claim fire the callback once`() =
        runBlocking {
            route("v1/installs" to { json(200, """{"deferredDeepLink":{"url":"https://revnix.io/promo","match":"exact"}}""") })
            val fired = java.util.concurrent.atomic.AtomicInteger(0)
            val client = makeClient(
                storage = SlowGetStorage("revnix.deferredDeepLinkDelivered"),
                onDeferredDeepLink = { _, _ -> fired.incrementAndGet() },
            )

            client.handleInstallReferrer("utm_source=x")
            client.handleInstallReferrer("utm_source=y")

            server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
            server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)
            kotlinx.coroutines.delay(700)

            assertEquals(1, fired.get())
        }

    @Test
    fun `a registerInstall response with a probabilistic match fires the callback`() = runBlocking {
        route("v1/installs" to { json(200, """{"deferredDeepLink":{"url":"https://revnix.io/promo","match":"probabilistic"}}""") })
        val latch = java.util.concurrent.CountDownLatch(1)
        val seen = mutableListOf<Pair<String, DeferredDeepLinkMatch>>()
        val client = makeClient(onDeferredDeepLink = { url, match -> seen += url to match; latch.countDown() })

        client.registerInstall()
        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(listOf("https://revnix.io/promo" to DeferredDeepLinkMatch.PROBABILISTIC), seen)
    }

    @Test
    fun `no deferredDeepLink on the response fires nothing`() = runBlocking {
        route("v1/installs" to { json(200, "{}") })
        val seen = mutableListOf<String>()
        val client = makeClient(onDeferredDeepLink = { url, _ -> seen += url })

        client.registerInstall()

        assertTrue(seen.isEmpty())
    }

    @Test
    fun `a malformed deferredDeepLink body still counts registerInstall as delivered`() = runBlocking {
        route("v1/installs" to { json(200, """{"deferredDeepLink":{"match":"exact"}}""") })
        val diagnostics = mutableListOf<String>()
        val seen = mutableListOf<String>()
        val client = makeClient(
            onDiagnostic = { diagnostics += it.op },
            onDeferredDeepLink = { url, _ -> seen += url },
        )

        client.registerInstall()

        assertTrue(seen.isEmpty())
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `a throwing onDeferredDeepLink handler is swallowed and reported`() = runBlocking {
        route("v1/installs" to { json(200, """{"deferredDeepLink":{"url":"https://revnix.io/promo","match":"exact"}}""") })
        val diagnostics = mutableListOf<String>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val client = makeClient(
            onDiagnostic = { diagnostics += it.op; latch.countDown() },
            onDeferredDeepLink = { _, _ -> throw IllegalStateException("boom") },
        )

        client.handleInstallReferrer("utm_source=x")
        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))

        assertTrue(diagnostics.contains("onDeferredDeepLink"))
    }

    private val attributionBody = """
        {"installMatch":"referrer","attributedAt":1737000000000,"linkToken":"lnk_1","referrerSource":"play","matchSignals":["referrer","device"],"source":"google","medium":"cpc","campaign":"summer","term":"pro","content":"a1"}
    """.trimIndent()

    private val reattributedBody = """
        {"installMatch":"click","attributedAt":1737000000000,"reattributedAt":1737900000000,"source":"meta"}
    """.trimIndent()

    @Test
    fun `getAttribution returns the parsed verdict`() = runBlocking {
        route("attribution" to { json(200, attributionBody) })
        val client = makeClient()

        assertEquals(
            RevnixAttribution(
                installMatch = "referrer",
                attributedAt = 1_737_000_000_000L,
                linkToken = "lnk_1",
                referrerSource = "play",
                matchSignals = listOf("referrer", "device"),
                source = "google",
                medium = "cpc",
                campaign = "summer",
                term = "pro",
                content = "a1",
            ),
            client.getAttribution(),
        )
    }

    @Test
    fun `an unknown verdict is null and reports nothing`() = runBlocking {
        route("attribution" to { json(200, """{"installMatch":"unknown"}""") })
        val diagnostics = mutableListOf<String>()
        val client = makeClient(onDiagnostic = { diagnostics += it.op })

        assertNull(client.getAttribution())
        assertTrue(diagnostics.isEmpty())
    }

    @Test
    fun `a failed attribution read is null and reported`() = runBlocking {
        route("attribution" to { disconnect() })
        val diagnostics = mutableListOf<String>()
        val client = makeClient(onDiagnostic = { diagnostics += it.op })

        assertNull(client.getAttribution())
        assertTrue(diagnostics.contains("getAttribution"))
    }

    @Test
    fun `onAttribution fires once for the first verdict and not for an identical one`() = runBlocking {
        route("attribution" to { json(200, attributionBody) })
        val seen = mutableListOf<RevnixAttribution>()
        val client = makeClient(onAttribution = { seen += it })

        client.getAttribution()
        client.getAttribution()

        assertEquals(1, seen.size)
        assertEquals("referrer", seen.single().installMatch)
    }

    @Test
    fun `onAttribution fires again when the verdict changes`() = runBlocking {
        route("attribution" to onceThen(json(200, attributionBody)) { json(200, reattributedBody) })
        val seen = mutableListOf<RevnixAttribution>()
        val client = makeClient(onAttribution = { seen += it })

        client.getAttribution()
        client.getAttribution()

        assertEquals(listOf("referrer", "click"), seen.map { it.installMatch })
        assertEquals(1_737_900_000_000L, seen.last().reattributedAt)
    }

    @Test
    fun `a throwing onAttribution handler is swallowed and reported`() = runBlocking {
        route("attribution" to { json(200, attributionBody) })
        val diagnostics = mutableListOf<String>()
        val client = makeClient(
            onDiagnostic = { diagnostics += it.op },
            onAttribution = { throw IllegalStateException("boom") },
        )

        assertEquals("referrer", client.getAttribution()?.installMatch)
        assertTrue(diagnostics.contains("onAttribution"))
    }

    @Test
    fun `an install report fetches no attribution without a handler`() = runBlocking {
        val fetches = AtomicInteger(0)
        route(
            "v1/installs" to { json(200, "{}") },
            "attribution" to { fetches.incrementAndGet(); json(200, attributionBody) },
        )
        val client = makeClient()

        client.registerInstall()
        client.handleInstallReferrer("utm_source=x")
        server.takeRequest(2, java.util.concurrent.TimeUnit.SECONDS)

        assertEquals(0, fetches.get())
    }

    @Test
    fun `an install report fetches the attribution exactly once with a handler`() = runBlocking {
        val fetches = AtomicInteger(0)
        route(
            "v1/installs" to { json(200, "{}") },
            "attribution" to { fetches.incrementAndGet(); json(200, attributionBody) },
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        val client = makeClient(onAttribution = { latch.countDown() })

        client.registerInstall()
        assertTrue(latch.await(2, java.util.concurrent.TimeUnit.SECONDS))

        assertEquals(1, fetches.get())
    }
}

/** Mutable test clock. */
private class TestClock(private var current: Long) {
    fun now(): Long = current
    fun advance(ms: Long) {
        current += ms
    }
}

private class SlowGetStorage(
    private val slowKey: String,
    private val delayMs: Long = 200,
    private val delegate: RevnixStorage = MemoryStorage(),
) : RevnixStorage {
    override fun get(key: String): String? {
        val value = delegate.get(key)
        if (key == slowKey) Thread.sleep(delayMs)
        return value
    }

    override fun set(key: String, value: String) = delegate.set(key, value)
    override fun remove(key: String) = delegate.remove(key)
}
