package com.revnix.kmp

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Behavior tests ported from revnix-react's `resilience.test.ts` — the policy
 * spec — and kept in agreement with the Swift and JVM ports. These run on every
 * KMP target, so the policy is proven on Darwin as well as JVM.
 */
/** Transport failure the MockEngine can raise on any target (java.io is JVM-only). */
private class ConnectionDropped : Exception("connection dropped")

class RevnixClientTest {

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

    /** Records every request so tests can assert counts and outbound headers. */
    private class Recorder {
        val paths = mutableListOf<String>()
        val headers = mutableListOf<Map<String, String>>()

        fun count(substring: String) = paths.count { it.contains(substring) }

        fun lastHeader(name: String, substring: String): String? {
            val i = paths.indexOfLast { it.contains(substring) }
            return if (i >= 0) headers[i][name] else null
        }
    }

    private fun engine(
        recorder: Recorder,
        handler: (path: String, call: Int) -> Pair<HttpStatusCode, String>?,
    ) = MockEngine { request ->
        val path = request.url.encodedPath
        val call = recorder.count(path.substringAfterLast('/'))
        recorder.paths += path
        recorder.headers += request.headers.entries()
            .associate { it.key to it.value.first() }
        val outcome = handler(path, call)
            ?: throw ConnectionDropped()
        respond(
            content = outcome.second,
            status = outcome.first,
            headers = headersOf("Content-Type", "application/json"),
        )
    }

    private fun makeClient(
        recorder: Recorder,
        storage: RevnixStorage = MemoryStorage(),
        now: () -> Long = { 1_700_000_000_000 },
        entitlementsTtl: Duration = 30.seconds,
        readYourWritesDelays: List<Duration> = listOf(1.milliseconds, 1.milliseconds),
        onDiagnostic: ((RevnixDiagnostic) -> Unit)? = null,
        handler: (path: String, call: Int) -> Pair<HttpStatusCode, String>?,
    ): RevnixClient = RevnixClient(
        RevnixConfig(
            apiKey = "rvx_pk_test_abc",
            baseUrl = "https://example.convex.site",
            storage = storage,
            entitlementsTtl = entitlementsTtl,
            readYourWritesDelays = readYourWritesDelays,
            onDiagnostic = onDiagnostic,
            now = now,
            httpClient = HttpClient(engine(recorder, handler)) {
                install(HttpTimeout) { requestTimeoutMillis = 5_000 }
            },
        )
    )

    private fun ok(body: String) = HttpStatusCode.OK to body

    // MARK: - Typed errors

    @Test
    fun rate_limit_carries_retryAfterMs() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> null }
        // Ktor MockEngine with an explicit 429 + Retry-After.
        val limited = RevnixClient(
            RevnixConfig(
                apiKey = "k",
                baseUrl = "https://example.convex.site",
                httpClient = HttpClient(
                    MockEngine {
                        respond(
                            """{"error":"rate limit exceeded"}""",
                            HttpStatusCode.TooManyRequests,
                            headersOf("Retry-After", "2"),
                        )
                    }
                ),
            )
        )
        val err = assertFailsWith<RevnixError.RateLimited> { limited.resolvePlacement("main") }
        assertEquals(2000L, err.retryAfterMs)
        assertTrue(err.isRetryable)
        client.close()
        limited.close()
    }

    @Test
    fun retryAfter_accepts_http_date_and_tolerates_garbage() {
        assertEquals(2000L, RevnixError.parseRetryAfter("2"))
        // 1970-01-01T00:16:50Z == 1_010_000 ms; from t=1_000_000 → 10_000 ms.
        assertEquals(
            10_000L,
            RevnixError.parseRetryAfter("Thu, 01 Jan 1970 00:16:50 GMT", nowMs = 1_000_000L),
        )
        assertNull(RevnixError.parseRetryAfter(null))
        assertNull(RevnixError.parseRetryAfter("not-a-date"))
    }

    @Test
    fun a_network_failure_is_typed_as_a_network_error() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> null }
        val err = assertFailsWith<RevnixError.Network> { client.resolvePlacement("main") }
        assertTrue(err.isRetryable)
        client.close()
    }

    // MARK: - Entitlement cache

    @Test
    fun entitlements_happy_path() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> ok(entitlementsBody) }
        val result = client.entitlements()
        assertEquals(7L, result.cursor)
        assertEquals(false, result.stale)
        assertTrue(client.isEntitled("pro"))
        assertFalse(client.isEntitled("gold"))
        client.close()
    }

    @Test
    fun falls_back_to_cached_entitlements_when_the_network_read_fails() = runTest {
        val rec = Recorder()
        val client = makeClient(rec, entitlementsTtl = Duration.ZERO) { _, call ->
            if (call == 0) ok(entitlementsBody) else null
        }
        assertEquals(false, client.entitlements().stale)

        val cached = client.entitlements()
        assertEquals(true, cached.stale)
        assertEquals(true, cached.entitlements.first().isActive)
        assertTrue(client.isEntitled("pro"))
        client.close()
    }

    @Test
    fun cachedEntitlements_reads_the_cache_with_no_network_call() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> ok(entitlementsBody) }
        assertNull(client.cachedEntitlements())
        client.entitlements()
        val cached = client.cachedEntitlements()
        assertEquals(true, cached?.stale)
        assertEquals(1, rec.count("entitlements"))
        client.close()
    }

    // MARK: - Local expiry grace

    private suspend fun cachedServe(expiresAt: Long?): Pair<RevnixClient, CustomerEntitlements> {
        val rec = Recorder()
        val client = makeClient(rec, entitlementsTtl = Duration.ZERO) { _, call ->
            if (call == 0) ok(entitlementsBody(expiresAt)) else null
        }
        assertEquals(true, client.entitlements().entitlements.first().isActive)
        return client to client.entitlements()
    }

    @Test
    fun expired_beyond_the_grace_is_served_inactive() = runTest {
        val (client, cached) = cachedServe(1_700_000_000_000 - 5 * dayMs)
        assertEquals(true, cached.stale)
        assertEquals(false, cached.entitlements.first().isActive)
        assertFalse(client.isEntitled("pro"))
        client.close()
    }

    @Test
    fun expired_within_the_grace_is_still_active() = runTest {
        val (client, cached) = cachedServe(1_700_000_000_000 - 1 * dayMs)
        assertEquals(true, cached.entitlements.first().isActive)
        assertTrue(client.isEntitled("pro"))
        client.close()
    }

    @Test
    fun no_expiresAt_is_untouched() = runTest {
        val (client, cached) = cachedServe(null)
        assertEquals(true, cached.entitlements.first().isActive)
        client.close()
    }

    @Test
    fun cachedEntitlements_applies_the_same_expiry_evaluation() = runTest {
        val (client, _) = cachedServe(1_700_000_000_000 - 5 * dayMs)
        assertEquals(false, client.cachedEntitlements()?.entitlements?.first()?.isActive)
        client.close()
    }

    // MARK: - Persisted purchase retry

    private val purchase = RegisterPurchaseInput(
        source = RevnixStore.APPLE,
        token = "orig.1",
        productId = "pro.monthly",
        transactionId = "txn.1",
    )

    @Test
    fun a_failed_registerPurchase_is_queued_then_drained() = runTest {
        val rec = Recorder()
        val storage = MemoryStorage()
        val offline = makeClient(rec, storage = storage) { _, _ -> null }
        assertFailsWith<RevnixError.Network> { offline.registerPurchase(purchase) }
        assertEquals(1, offline.pendingPurchaseCount())
        assertTrue(storage.get("revnix.pendingPurchases")!!.contains("apple:orig.1:txn.1"))
        offline.close()

        // A fresh client proves the queue survived process death.
        val rec2 = Recorder()
        val relaunched = makeClient(rec2, storage = storage) { _, _ -> ok(purchaseBody) }
        assertEquals(1, relaunched.retryPendingPurchases())
        assertEquals(0, relaunched.pendingPurchaseCount())
        relaunched.close()
    }

    @Test
    fun the_same_failing_purchase_is_not_queued_twice() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> null }
        runCatching { client.registerPurchase(purchase) }
        runCatching { client.registerPurchase(purchase) }
        assertEquals(1, client.pendingPurchaseCount())
        client.close()
    }

    @Test
    fun a_deliberate_purchase_rejection_is_not_queued() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ ->
            HttpStatusCode.Conflict to """{"error":"blocked"}"""
        }
        val err = assertFailsWith<RevnixError.PurchaseBlocked> {
            client.registerPurchase(purchase)
        }
        assertFalse(err.isRetryable)
        assertEquals(0, client.pendingPurchaseCount())
        client.close()
    }

    // MARK: - Cache fallback discipline

    @Test
    fun a_revoked_key_401_is_not_papered_over_by_the_cache() = runTest {
        val rec = Recorder()
        val client = makeClient(rec, entitlementsTtl = Duration.ZERO) { _, call ->
            if (call == 0) ok(entitlementsBody)
            else HttpStatusCode.Unauthorized to """{"error":"revoked"}"""
        }
        client.entitlements()
        val err = assertFailsWith<RevnixError.Auth> { client.entitlements() }
        assertEquals(401, err.status)
        assertFalse(err.isRetryable)
        client.close()
    }

    @Test
    fun an_unknown_placement_404_is_not_papered_over_by_the_cache() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, call ->
            if (call == 0) ok(placementBody)
            else HttpStatusCode.NotFound to """{"error":"unknown placement"}"""
        }
        client.resolvePlacement("main")
        assertFailsWith<RevnixError.NotFound> { client.resolvePlacement("main") }
        client.close()
    }

    // MARK: - Paywall config decoding (REV-028 templates)

    @Test
    fun a_full_templated_paywall_config_decodes_typed() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> ok(paywallFullBody) }
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
        client.close()
    }

    @Test
    fun a_legacy_minimal_paywall_config_still_decodes() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> ok(paywallLegacyBody) }
        val paywall = assertNotNull(client.resolvePlacement("main").paywall)
        val config = paywall.config
        assertEquals("focus", config.template)
        assertEquals("Unlock", config.headline)
        assertEquals("Feature one", config.features.single().title)
        assertNull(config.mode)
        assertNull(config.review)
        assertNull(config.offer)
        assertNull(config.footer)
        client.close()
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
    fun raw_paywall_and_experiment_survive_beside_the_typed_views() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, call ->
            if (call == 0) ok(placementDesignedBody) else null
        }
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
        client.close()
    }

    /**
     * A paywall the typed model cannot read (no `headline` / `ctaLabel`, an
     * experiment missing `variantId`) must not fail the resolution — the
     * offering and the raw copies still arrive, the typed views are null.
     */
    @Test
    fun an_untypeable_paywall_still_delivers_the_offering_and_the_raw_copies() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> ok("""{"status":"ok","placementKey":"main","revision":6,"offering":{"offeringId":"off_4","displayName":"Blocks only","packages":[{"packageId":"pkg_4","productId":"pro.weekly"}]},"paywall":{"paywallId":"pw_2","name":"Next","config":{"template":"canvas","blocks":{"version":2,"layout":"grid","blocks":[]}}},"experiment":{"key":"k"}}""") }
        val resolution = client.resolvePlacement("main")
        assertEquals("off_4", resolution.offering.offeringId)
        assertNull(resolution.paywall)
        assertNull(resolution.experiment)
        assertEquals("pw_2", resolution.paywallJson!!.jsonObject["paywallId"]!!.jsonPrimitive.content)
        assertEquals("k", resolution.experimentJson!!.jsonObject["key"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun raw_copies_are_null_when_the_wire_has_none() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, call ->
            if (call == 0) ok(placementNullExperimentBody) else ok(placementBody)
        }
        val nullCase = client.resolvePlacement("main")
        assertNull(nullCase.paywallJson)
        assertNull(nullCase.experimentJson)
        val absentCase = client.resolvePlacement("main")
        assertNull(absentCase.paywallJson)
        assertNull(absentCase.experimentJson)
        client.close()
    }

    // MARK: - Experiments (REV-219)

    @Test
    fun an_experiment_assignment_decodes_and_round_trips_through_the_cache() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, call ->
            if (call == 0) ok(placementExperimentBody) else null
        }
        val live = client.resolvePlacement("main")
        assertEquals("summer-price-test", live.experiment?.key)
        assertEquals("var_b", live.experiment?.variantId)
        assertEquals("off_2", live.offering.offeringId)

        // Offline now → the cached resolution keeps the assignment.
        val cached = client.resolvePlacement("main")
        assertEquals("summer-price-test", cached.experiment?.key)
        assertEquals("var_b", cached.experiment?.variantId)
        client.close()
    }

    @Test
    fun a_null_or_absent_experiment_field_decodes_as_null() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, call ->
            if (call == 0) ok(placementNullExperimentBody) else ok(placementBody)
        }
        assertNull(client.resolvePlacement("main").experiment) // explicit null
        assertNull(client.resolvePlacement("main").experiment) // absent (older server)
        client.close()
    }

    @Test
    fun the_resolve_request_carries_the_customer_id_for_sticky_assignment() = runTest {
        var customerParam: String? = null
        val client = RevnixClient(
            RevnixConfig(
                apiKey = "k",
                baseUrl = "https://example.convex.site",
                httpClient = HttpClient(
                    MockEngine { request ->
                        customerParam = request.url.parameters["customer"]
                        respond(
                            placementBody,
                            HttpStatusCode.OK,
                            headersOf("Content-Type", "application/json"),
                        )
                    }
                ),
            )
        )
        client.resolvePlacement("main")
        assertEquals(client.customerId(), assertNotNull(customerParam))
        client.close()
    }

    @Test
    fun a_500_still_falls_back_to_the_cache() = runTest {
        val rec = Recorder()
        val client = makeClient(rec, entitlementsTtl = Duration.ZERO) { _, call ->
            if (call == 0) ok(entitlementsBody)
            else HttpStatusCode.InternalServerError to """{"error":"boom"}"""
        }
        client.entitlements()
        val cached = client.entitlements()
        assertEquals(true, cached.stale)
        assertEquals(true, cached.entitlements.first().isActive)
        client.close()
    }

    // MARK: - Offline cache age bound

    @Test
    fun the_cache_age_ceiling_serves_inactive() = runTest {
        val rec = Recorder()
        var now = 1_700_000_000_000L
        val client = makeClient(rec, now = { now }, entitlementsTtl = Duration.ZERO) { _, call ->
            if (call == 0) ok(entitlementsBody)
            else HttpStatusCode.InternalServerError to """{"error":"down"}"""
        }
        client.entitlements()
        now += 15 * dayMs
        val served = client.entitlements()
        assertEquals(true, served.stale)
        assertEquals(false, served.entitlements.first().isActive)
        client.close()
    }

    @Test
    fun within_the_offline_window_it_still_grants() = runTest {
        val rec = Recorder()
        var now = 1_700_000_000_000L
        val client = makeClient(rec, now = { now }, entitlementsTtl = Duration.ZERO) { _, call ->
            if (call == 0) ok(entitlementsBody)
            else HttpStatusCode.InternalServerError to """{"error":"down"}"""
        }
        client.entitlements()
        now += 13 * dayMs
        val served = client.entitlements()
        assertEquals(true, served.stale)
        assertEquals(true, served.entitlements.first().isActive)
        client.close()
    }

    @Test
    fun a_rolled_back_clock_serves_inactive() = runTest {
        val rec = Recorder()
        var now = 1_700_000_000_000L
        val client = makeClient(rec, now = { now }, entitlementsTtl = Duration.ZERO) { _, call ->
            if (call == 0) ok(entitlementsBody)
            else HttpStatusCode.InternalServerError to """{"error":"down"}"""
        }
        client.entitlements()
        now -= 30 * 60 * 1000
        val served = client.entitlements()
        assertEquals(true, served.stale)
        assertEquals(false, served.entitlements.first().isActive)
        client.close()
    }

    // MARK: - Soft TTL + coalescing

    @Test
    fun reads_within_the_ttl_cost_one_request_and_concurrent_reads_share_one() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> ok(entitlementsBody) }
        listOf(
            async { client.isEntitled("pro") },
            async { client.isEntitled("pro") },
            async { client.entitlements() },
        ).awaitAll()
        assertEquals(1, rec.count("entitlements"))
        client.entitlements()
        assertEquals(1, rec.count("entitlements"))
        client.close()
    }

    @Test
    fun a_zero_ttl_restores_always_fetch() = runTest {
        val rec = Recorder()
        val client = makeClient(rec, entitlementsTtl = Duration.ZERO) { _, _ ->
            ok(entitlementsBody)
        }
        client.entitlements()
        client.entitlements()
        assertEquals(2, rec.count("entitlements"))
        client.close()
    }

    /**
     * Regression: `waitForEntitlements` must bypass the soft TTL. Polling
     * through the TTL re-read the SAME cached snapshot, so the cursor never
     * advanced and every post-purchase unlock spun until it gave up whenever a
     * gate had been checked in the preceding 30 s.
     */
    @Test
    fun waitForEntitlements_bypasses_the_soft_ttl() = runTest {
        val rec = Recorder()
        // Default 30 s TTL stays ON — that is the point of the regression.
        val client = makeClient(rec) { _, call ->
            if (call == 0) ok(entitlementsBody)
            else ok(
                """{"customerId":"cust_1","cursor":9,"entitlements":[{"entitlementId":"pro","isActive":true,"expiresAt":4102444800000,"sources":[]}]}"""
            )
        }
        assertEquals(7L, client.entitlements().cursor)
        val settled = client.waitForEntitlements(9)
        assertEquals(9L, settled.cursor)
        assertEquals(false, settled.stale)
        client.close()
    }

    @Test
    fun waitForEntitlements_returns_the_last_read_when_the_cursor_never_catches_up() = runTest {
        val rec = Recorder()
        val client = makeClient(rec) { _, _ -> ok(entitlementsBody) }
        assertEquals(7L, client.waitForEntitlements(999).cursor)
        client.close()
    }

    // MARK: - Diagnostics

    @Test
    fun a_background_failure_reaches_diagnostics_and_rides_the_next_request_header() = runTest {
        val rec = Recorder()
        val storage = MemoryStorage()
        storage.set(
            "revnix.pendingPurchases",
            """[{"key":"apple:otx-9:tx-9","source":"apple","token":"otx-9","productId":"pro.monthly","transactionId":"tx-9"}]""",
        )
        val events = mutableListOf<String>()
        val client = makeClient(
            rec,
            storage = storage,
            entitlementsTtl = Duration.ZERO,
            onDiagnostic = { events += it.op },
        ) { path, _ ->
            if (path.contains("purchases")) null else ok(entitlementsBody)
        }

        assertEquals(0, client.retryPendingPurchases())
        assertTrue(events.isNotEmpty())
        assertEquals(1, client.pendingPurchaseCount())

        client.entitlements()
        val header = rec.lastHeader("X-Revnix-Bg-Failures", "entitlements")
        assertNotNull(header)
        assertTrue(header.toInt() > 0)

        // …and is cleared once delivered.
        client.entitlements()
        assertNull(rec.lastHeader("X-Revnix-Bg-Failures", "entitlements"))
        client.close()
    }

    @Test
    fun a_diagnostic_handler_never_masks_the_underlying_error() = runTest {
        val rec = Recorder()
        val events = mutableListOf<String>()
        val client = makeClient(rec, onDiagnostic = { events += it.op }) { _, _ -> null }
        assertFailsWith<RevnixError.Network> { client.entitlements() }
        client.close()
    }
}
