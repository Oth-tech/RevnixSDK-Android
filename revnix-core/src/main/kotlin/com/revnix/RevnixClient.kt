package com.revnix

import java.io.IOException
import java.io.InterruptedIOException
import java.util.Collections
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Core client — a faithful port of revnix-react's resilience policy
 * (revnix-sdk `resilience.test.ts` is the behavioral spec):
 * - entitlements are network-first; TRANSIENT failures serve the cache flagged
 *   `stale`, DELIBERATE rejections (401/403/404/409) always throw;
 * - cached entitlements past expiry by >3 days serve inactive; snapshots older
 *   than [RevnixConfig.offlineMaxCacheAge] (14 d) or behind a >5 min clock
 *   rollback serve all-inactive;
 * - a soft TTL plus in-flight coalescing keeps a screen of gates to one fetch;
 * - failed purchase registrations persist to a retry queue keyed
 *   `source:token:transactionId` and drain idempotently (the server dedupes on
 *   the shared purchaseKey).
 */
public class RevnixClient(private val config: RevnixConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val http: OkHttpClient = config.httpClient ?: OkHttpClient.Builder()
        .callTimeout(config.timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private var inflight: Deferred<CustomerEntitlements>? = null
    private var bgFailures = 0

    private val deferredDeepLinkLock = Any()

    /**
     * REV-268: the encoded X-Revnix-Device value, built on the first resolve and
     * kept for the client's lifetime (so `firstOpen` holds for the whole first
     * session). Wrapped so "built, and there is nothing to send" is
     * distinguishable from "not built yet".
     */
    private val deviceHeader: String? by lazy { buildDeviceHeader() }

    // REV-272: implicit placements. Off unless the host said what to do with a
    // paywall — without a handler there is nothing to do with the answer, and
    // firing anyway would spend requests and ledger rows on nobody's behalf.
    private val implicitEnabled: Boolean get() = config.implicitPlacementsEnabled

    /**
     * Which of the six this app has configured. One in-flight job, coalesced;
     * a SUCCESS is memoised for the client's lifetime, a FAILURE clears the
     * field so the next moment asks again — an offline cold start must not
     * disable every implicit moment until the next launch.
     * [implicitConfigRetryAt] keeps that retry from happening on every paywall
     * close of an offline session: one probe per minute.
     */
    private var implicitConfigJob: Deferred<Set<String>>? = null
    @Volatile private var implicitConfigRetryAt: Long = Long.MIN_VALUE
    private val implicitMutex = Mutex()
    private var lifecycleCancel: (() -> Unit)? = null

    /**
     * When the app last went to the BACKGROUND, or null while it has not. A
     * return after [RevnixConfig.sessionTimeoutMs] away is a new session; sooner
     * is an app switch. Measured from the background transition, not from the
     * last return — the latter would mint a session after 35 minutes of
     * continuous use plus a three-second switch.
     */
    @Volatile private var lastBackgroundAt: Long? = null

    /**
     * LOOP GUARD: view ids of displays that came FROM an implicit trigger. A
     * paywall shown because a paywall was dismissed must not itself fire
     * `paywall_decline`, or the customer is handed the same screen forever.
     * Never released — a double-tapped close reports two closes on one id, and
     * releasing on the first would let the second re-enter the loop. Bounded by
     * implicit displays per process: a handful of ids.
     */
    private val implicitViewIds = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * The cold-start batch while it runs, completing with whether it presented.
     * A deep link delivered on the first frame (`onCreate`'s intent) waits for
     * it, or the customer gets the launch paywall AND the link paywall.
     */
    @Volatile private var launchBatch: Deferred<Boolean>? = null

    /**
     * True when [customerId] minted the id on THIS launch — the install signal,
     * the same one `/v1/installs` uses. In memory on purpose: a stored "seen
     * this id" marker would fire `app_install` for the entire existing base on
     * the first launch after an SDK upgrade, and again after every [logout].
     */
    @Volatile private var mintedThisLaunch = false

    init {
        if (implicitEnabled) {
            // Launched rather than awaited — a launch must never wait on
            // /v1/config, and every implicit path is fire-and-forget from here
            // down. `close()` cancels the scope, which ends anything in flight.
            scope.launch { startImplicitPlacements() }
        }
    }

    private companion object {
        const val EXPIRY_GRACE_MS = 3L * 24 * 3600 * 1000
        const val ROLLBACK_TOLERANCE_MS = 5L * 60 * 1000
        const val CACHE_CUSTOMERS = 4
        const val SDK_VERSION = "0.3.0"

        const val KEY_CUSTOMER_ID = "revnix.customerId"
        const val KEY_INSTALLED_AT = "revnix.installedAt"
        const val KEY_WALL_CLOCK = "revnix.lastWallClock"
        const val KEY_QUEUE = "revnix.pendingPurchases"
        const val KEY_CACHE_INDEX = "revnix.entIndex"
        const val KEY_DEFERRED_DEEP_LINK_DELIVERED = "revnix.deferredDeepLinkDelivered"
        const val KEY_LAST_DEEP_LINK = "revnix.lastDeepLink"
        const val KEY_ATTRIBUTION = "revnix.attribution"
        const val KEY_LAST_ATTRIBUTION = "revnix.lastAttribution"
        const val KEY_SESSION_STARTED_AT = "revnix.sessionStartedAt"
        const val KEY_LAST_BACKGROUND_AT = "revnix.lastBackgroundAt"

        const val IMPLICIT_CONFIG_RETRY_HOLD_MS = 60_000L

        val PREVIEW_TOKEN_REGEX = Regex("[?&]revnix_preview=([0-9a-f]{64})(?:[&#]|$)")
    }

    // MARK: - Identity

    /** Anonymous id, minted and persisted on first call. */
    public fun customerId(): String {
        config.storage.get(KEY_CUSTOMER_ID)?.let { return it }
        val minted = generateAnonymousId()
        config.storage.set(KEY_CUSTOMER_ID, minted)
        // REV-272: the install signal. [logout] below deliberately does not set
        // it — a new anonymous session is not a new install.
        mintedThisLaunch = true
        return minted
    }

    /** Fresh anonymous identity. Per-customer caches age out via LRU. */
    public fun logout(): String {
        val minted = generateAnonymousId()
        config.storage.set(KEY_CUSTOMER_ID, minted)
        return minted
    }

    /** Release the internal coroutine scope. */
    public fun close() {
        // REV-272: a replaced client would otherwise keep a foreground observer
        // alive and mint a session on every return alongside its successor.
        lifecycleCancel?.invoke()
        lifecycleCancel = null
        scope.cancel()
    }

    // MARK: - Entitlements

    public suspend fun entitlements(): CustomerEntitlements {
        val cid = customerId()
        val nowMs = config.now()
        val rolledBack = updateWallClock(nowMs)

        // Soft TTL: a live snapshot this fresh is authoritative. A TTL of zero
        // disables the shortcut entirely (always-fetch).
        val ttlMs = config.entitlementsTtl.inWholeMilliseconds
        if (ttlMs > 0 && !rolledBack) {
            val entry = cacheEntry(cid)
            if (entry != null && nowMs - entry.fetchedAt <= ttlMs) {
                return entry.snapshot.copy(stale = false, fetchedAt = entry.fetchedAt)
            }
        }

        // In-flight coalescing: a screen full of gates shares one request.
        val deferred = mutex.withLock {
            inflight ?: scope.async { fetchEntitlements(cid, nowMs, rolledBack) }
                .also { inflight = it }
        }
        try {
            return deferred.await()
        } finally {
            mutex.withLock { if (inflight === deferred) inflight = null }
        }
    }

    /**
     * Network-only read — no TTL shortcut, no cache fallback. Throws on any
     * failure. The read-your-writes poll needs a genuinely fresh cursor.
     */
    private suspend fun fetchFreshEntitlements(cid: String, nowMs: Long): CustomerEntitlements {
        val raw = request("GET", listOf("v1", "customers", cid, "entitlements"))
        val snapshot = decode(CustomerEntitlements.serializer(), raw)
            .copy(stale = false, fetchedAt = nowMs)
        storeCacheEntry(CacheEntry(snapshot, nowMs), cid)
        return snapshot
    }

    private suspend fun fetchEntitlements(
        cid: String,
        nowMs: Long,
        rolledBack: Boolean,
    ): CustomerEntitlements {
        try {
            return fetchFreshEntitlements(cid, nowMs)
        } catch (err: RevnixError) {
            // Deliberate rejections rethrow: a kill-switch must not be
            // defeated by the cache.
            if (!err.isRetryable) throw err
            val entry = cacheEntry(cid) ?: throw err
            return applyOfflinePolicy(entry, nowMs, rolledBack)
        }
    }

    /**
     * Last cached snapshot with the offline policy applied; null when the
     * customer has never had a live read.
     */
    public fun cachedEntitlements(): CustomerEntitlements? {
        val cid = customerId()
        val nowMs = config.now()
        val entry = cacheEntry(cid) ?: return null
        return applyOfflinePolicy(entry, nowMs, updateWallClock(nowMs))
    }

    /** Gate helper: never throws. A transient failure answers from the offline cache; a deliberate rejection (401/403/404/409), an unknown id, or no cache answers false. */
    public suspend fun isEntitled(entitlementId: String): Boolean {
        val snapshot = try {
            entitlements()
        } catch (_: RevnixError) {
            null
        }
        return snapshot?.entitlements?.any { it.entitlementId == entitlementId && it.isActive }
            ?: false
    }

    /**
     * Read-your-writes: poll entitlements until the response's ledger cursor is
     * at least [seq], then return it. Resolves with the LAST read if the
     * schedule runs out or reads only come from the stale cache — it never
     * spins forever and never throws a timeout, so a slow ledger degrades to
     * "not unlocked yet" rather than an error the app has to handle.
     */
    public suspend fun waitForEntitlements(seq: Long): CustomerEntitlements {
        val cid = customerId()
        var last = entitlements()
        for (delayFor in config.readYourWritesDelays) {
            if (last.stale != true && last.cursor >= seq) return last
            // ±20% jitter: promo pushes synchronize a fleet's purchases, and
            // identical schedules keep every device's poll in lockstep against
            // our own rate limiter.
            delay(jittered(delayFor.inWholeMilliseconds))
            try {
                // TTL-bypassing: the point of this poll is a FRESH cursor, so
                // the soft TTL must not answer it from the last snapshot.
                last = fetchFreshEntitlements(cid, config.now())
            } catch (err: RevnixError) {
                // The server said exactly how long to back off — honor it
                // instead of fighting our own rate limiter.
                val retryAfter = (err as? RevnixError.RateLimited)?.retryAfterMs
                if (retryAfter != null && retryAfter > 0) delay(retryAfter)
                // Transient failure: keep the last read, let the schedule run.
            }
        }
        return last
    }

    private fun applyOfflinePolicy(
        entry: CacheEntry,
        nowMs: Long,
        rolledBack: Boolean,
    ): CustomerEntitlements {
        val snapshot = entry.snapshot
        val tooOld = nowMs - entry.fetchedAt > config.offlineMaxCacheAge.inWholeMilliseconds
        val entitlements = if (tooOld || rolledBack) {
            snapshot.entitlements.map { it.inactive() }
        } else {
            snapshot.entitlements.map { ent ->
                val expiresAt = ent.expiresAt
                if (ent.isActive && expiresAt != null && nowMs > expiresAt + EXPIRY_GRACE_MS) {
                    ent.inactive()
                } else {
                    ent
                }
            }
        }
        return snapshot.copy(
            entitlements = entitlements,
            stale = true,
            fetchedAt = entry.fetchedAt,
        )
    }

    // MARK: - Purchases

    public suspend fun registerPurchase(input: RegisterPurchaseInput): RegisterPurchaseResult {
        val cid = customerId()
        try {
            val result = postPurchase(input, cid)
            if (result.customerId != cid) {
                // Identity resolution merged us — adopt the canonical id.
                config.storage.set(KEY_CUSTOMER_ID, result.customerId)
            }
            removeQueued(queueKey(input))
            return result
        } catch (err: RevnixError) {
            if (err.isRetryable) enqueue(input)
            throw err
        }
    }

    /**
     * Drain the persisted queue. Safe to call on every launch/foreground — the
     * server dedupes on the shared purchaseKey. Returns the number delivered.
     */
    public suspend fun retryPendingPurchases(): Int {
        val cid = customerId()
        var delivered = 0
        for (item in queuedItems()) {
            try {
                postPurchase(item.toInput(), cid)
                removeQueued(item.key)
                delivered += 1
            } catch (err: RevnixError) {
                if (!err.isRetryable) {
                    // The server refused on purpose — retrying forever is noise.
                    removeQueued(item.key)
                    diagnostic("retryPendingPurchases", "dropped ${item.key}: ${err.message}")
                } else {
                    bgFailures += 1
                    diagnostic("retryPendingPurchases", "kept ${item.key}: ${err.message}")
                }
            }
        }
        return delivered
    }

    public fun pendingPurchaseCount(): Int = queuedItems().size

    private suspend fun postPurchase(
        input: RegisterPurchaseInput,
        cid: String,
    ): RegisterPurchaseResult {
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(cid))
            put("source", JsonPrimitive(input.source.wire))
            put("token", JsonPrimitive(input.token))
            put("productId", JsonPrimitive(input.productId))
            put("transactionId", JsonPrimitive(input.transactionId))
            input.occurredAt?.let { put("occurredAt", JsonPrimitive(it)) }
            input.expiresAt?.let { put("expiresAt", JsonPrimitive(it)) }
            input.signedTransactionInfo?.let { put("signedTransactionInfo", JsonPrimitive(it)) }
            input.rawPayload?.let { put("rawPayload", it) }
        }
        val raw = request("POST", listOf("v1", "purchases"), body)
        return decode(RegisterPurchaseResult.serializer(), raw)
    }

    // MARK: - Placements & telemetry

    public suspend fun resolvePlacement(key: String): PlacementResolution {
        // The customer id makes experiment assignment sticky server-side
        // (REV-219); older servers simply ignore the parameter.
        val query = mapOf("customer" to customerId())
        // REV-268: the device facts ride along so targeting rules see THIS
        // device on THIS request, and the server stores them as device.*
        // attributes. Older servers ignore the header.
        val headers = deviceHeader?.let { mapOf("X-Revnix-Device" to it) }.orEmpty()
        try {
            val raw = request(
                "GET",
                listOf("v1", "placements", key, "offering"),
                query = query,
                headers = headers,
            )
            val resolution = decode(PlacementResolution.serializer(), raw)
            config.storage.set(placementKey(key), raw)
            return resolution
        } catch (err: RevnixError) {
            if (!err.isRetryable) throw err
            val cached = config.storage.get(placementKey(key)) ?: throw err
            return runCatching { decode(PlacementResolution.serializer(), cached) }
                .getOrElse { throw err }
        }
    }

    /**
     * REV-268: assemble the device facts once. `installedAt` is the first launch
     * this storage ever saw — written then, read back on every later one — and
     * `firstOpen` is true for the whole of that first session.
     */
    private fun buildDeviceHeader(): String? {
        val facts = config.device ?: return null
        val stored = config.storage.get(KEY_INSTALLED_AT)?.toLongOrNull()
        val installedAt: Long
        val firstOpen: Boolean
        if (stored != null && stored > 0) {
            installedAt = stored
            firstOpen = false
        } else {
            installedAt = config.now()
            firstOpen = true
            config.storage.set(KEY_INSTALLED_AT, installedAt.toString())
        }
        return facts.encodedHeader(SDK_VERSION, installedAt, firstOpen)
    }

    /** Fire-and-forget install beacon; once per customer id. */
    public suspend fun registerInstall(platform: String? = null, appVersion: String? = null) {
        val cid = customerId()
        if (config.storage.get(installReportedKey(cid)) != null) return
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(cid))
            put("sdkVersion", JsonPrimitive(SDK_VERSION))
            platform?.let { put("platform", JsonPrimitive(it)) }
            appVersion?.let { put("appVersion", JsonPrimitive(it)) }
        }
        try {
            val raw = request("POST", listOf("v1", "installs"), body)
            config.storage.set(installReportedKey(cid), "1")
            refreshAttribution()
            deliverDeferredDeepLink(decodeInstallResponse(raw)?.deferredDeepLink)
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("registerInstall", err.message.orEmpty())
        }
    }

    /**
     * Fire-and-forget install referrer report; safe to call late or twice.
     *
     * [source] names the store or network the referrer was read from. The
     * server accepts exactly `play`, `huawei`, `samsung`, `xiaomi`, `vivo`,
     * `meta` and `preinstall`, and refuses the report with a 400 on any other
     * value. Left null, the server records no source at all — it is never
     * assumed to be `play`.
     */
    public fun handleInstallReferrer(
        referrer: String,
        platform: String? = null,
        appVersion: String? = null,
        source: String? = null,
    ) {
        if (referrer.isBlank()) return
        scope.launch {
            try {
                val body = buildJsonObject {
                    put("customerId", JsonPrimitive(customerId()))
                    put("occurredAt", JsonPrimitive(config.now()))
                    put("sdkVersion", JsonPrimitive(SDK_VERSION))
                    put("installReferrer", JsonPrimitive(referrer.take(1024)))
                    platform?.let { put("platform", JsonPrimitive(it)) }
                    appVersion?.let { put("appVersion", JsonPrimitive(it)) }
                    source?.let { put("referrerSource", JsonPrimitive(it)) }
                }
                val raw = request("POST", listOf("v1", "installs"), body)
                refreshAttribution()
                deliverDeferredDeepLink(decodeInstallResponse(raw)?.deferredDeepLink)
            } catch (err: Throwable) {
                bgFailures += 1
                diagnostic("handleInstallReferrer", err.message.orEmpty())
            }
        }
    }

    @Serializable
    private data class InstallResponse(val deferredDeepLink: DeferredDeepLinkPayload? = null)

    @Serializable
    private data class DeferredDeepLinkPayload(val url: String, val match: String)

    private fun decodeInstallResponse(raw: String): InstallResponse? =
        runCatching { json.decodeFromString(InstallResponse.serializer(), raw) }.getOrNull()

    private fun deliverDeferredDeepLink(link: DeferredDeepLinkPayload?) {
        if (link == null) return
        val handler = config.onDeferredDeepLink ?: return
        val match = when (link.match) {
            "exact" -> DeferredDeepLinkMatch.EXACT
            "probabilistic" -> DeferredDeepLinkMatch.PROBABILISTIC
            else -> return
        }
        val claimed = synchronized(deferredDeepLinkLock) {
            if (config.storage.get(KEY_DEFERRED_DEEP_LINK_DELIVERED) != null) {
                false
            } else {
                config.storage.set(KEY_DEFERRED_DEEP_LINK_DELIVERED, "1")
                true
            }
        }
        if (!claimed) return
        recordLastDeepLink(link.url)
        runCatching { handler(link.url, match) }
            .onFailure { diagnostic("onDeferredDeepLink", it.message.orEmpty()) }
    }

    /** Fire-and-forget impression beacon (feeds funnels + view conversions). */
    public suspend fun logPaywallShown(placementKey: String?, paywallId: String?) {
        logPaywallDisplay(placementKey, paywallId)
    }

    /**
     * The same beacon, returning the view id it generated (REV-252).
     *
     * Hand that id to [logPaywallClosed] when the customer dismisses THIS
     * display: the two events sharing one view id is what lets the ledger pair
     * a close with the display it ended, and the gap between their timestamps
     * is the customer's dwell on the screen. The id comes back even when
     * delivery fails — the caller's pairing must not depend on the network,
     * and the close beacon carries its own idempotency key.
     */
    public suspend fun logPaywallDisplay(placementKey: String?, paywallId: String?): String {
        val viewId = UUID.randomUUID().toString().lowercase()
        if (placementKey == REVNIX_PREVIEW_PLACEMENT_KEY) return viewId
        // REV-272 LOOP GUARD: a display whose placement is one of the six came
        // FROM an implicit trigger, so its dismissal must not fire another one
        // — otherwise "show a win-back when a paywall is declined" hands the
        // customer the same screen until they force-quit. Recognised from the
        // placementKey the caller reports; a caller that reports none cannot be
        // protected here, which is why the server keeps its own same-paywall
        // backstop.
        if (implicitEnabled && placementKey != null &&
            RevnixImplicitPlacement.fromKey(placementKey) != null
        ) {
            implicitViewIds.add(viewId)
        }
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(customerId()))
            put("viewId", JsonPrimitive(viewId))
            put("sdkVersion", JsonPrimitive(SDK_VERSION))
            placementKey?.let { put("placementKey", JsonPrimitive(it)) }
            paywallId?.let { put("paywallId", JsonPrimitive(it)) }
        }
        try {
            request("POST", listOf("v1", "paywalls", "viewed"), body)
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("logPaywallShown", err.message.orEmpty())
        }
        return viewId
    }

    /**
     * Fire-and-forget dismissal beacon (REV-252) — the other half of a
     * display's life. Idempotent per view id, exactly like the view report.
     *
     * Pass the id [logPaywallDisplay] returned for this display.
     *
     * A close is a DECLINE. Do not report one for a display that ended in a
     * purchase — with implicit placements on, a close is also the
     * `paywall_decline` moment, and a win-back offer seconds after a successful
     * purchase is the one thing an operator never means. `RevnixPaywallView`
     * only reports its close affordances, never a purchase-driven dismissal.
     */
    public suspend fun logPaywallClosed(
        viewId: String,
        placementKey: String?,
        paywallId: String?,
    ) {
        if (placementKey == REVNIX_PREVIEW_PLACEMENT_KEY) return
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(customerId()))
            put("viewId", JsonPrimitive(viewId))
            put("sdkVersion", JsonPrimitive(SDK_VERSION))
            placementKey?.let { put("placementKey", JsonPrimitive(it)) }
            paywallId?.let { put("paywallId", JsonPrimitive(it)) }
        }
        try {
            request("POST", listOf("v1", "paywalls", "closed"), body)
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("logPaywallClosed", err.message.orEmpty())
        }
        // REV-272: the dismissal IS the `paywall_decline` moment. No second
        // ledger event — the server reuses the paywall.closed just reported —
        // so this is only the resolve that decides what is attached to it.
        fireImplicitFromPaywall(RevnixImplicitPlacement.PAYWALL_DECLINE, viewId, paywallId)
    }


    /**
     * Report one of the six paywall interactions (REV-263) — what the customer
     * DID on a display, between the [logPaywallDisplay] that opened it and the
     * [logPaywallClosed] (or purchase) that ended it.
     *
     * Fire-and-forget like the other beacons: never throws.
     *
     * [viewId] is the id [logPaywallDisplay] returned for THIS display.
     * Passing it is what threads the whole life of one impression together and
     * puts the event on the paywall's own analytics row.
     *
     * `RevnixPaywallView` reports [RevnixPaywallEvent.Selected],
     * [RevnixPaywallEvent.PurchaseStarted], [RevnixPaywallEvent.Restore] and a
     * no-products [RevnixPaywallEvent.Error] for you. The purchase OUTCOME is
     * yours: only your app performs the Play Billing call, so report
     * [RevnixPaywallEvent.PurchaseAbandoned] / [RevnixPaywallEvent.PurchaseFailed]
     * from your own `BillingResult` handling (`USER_CANCELED` is an
     * abandonment, anything else is a failure).
     *
     * [eventId] is the idempotency key and defaults to [viewId], which caps
     * the report at one per display per event. Pass one per occurrence — and
     * reuse it across your own retries — to record each occurrence.
     */
    public suspend fun logPaywallEvent(
        event: RevnixPaywallEvent,
        viewId: String,
        placementKey: String? = null,
        paywallId: String? = null,
        productId: String? = null,
        code: String? = null,
        message: String? = null,
        eventId: String? = null,
    ) {
        if (placementKey == REVNIX_PREVIEW_PLACEMENT_KEY) return
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(customerId()))
            put("viewId", JsonPrimitive(viewId))
            put("event", JsonPrimitive(event.wireName))
            put("sdkVersion", JsonPrimitive(SDK_VERSION))
            eventId?.let { put("eventId", JsonPrimitive(it)) }
            placementKey?.let { put("placementKey", JsonPrimitive(it)) }
            paywallId?.let { put("paywallId", JsonPrimitive(it)) }
            productId?.let { put("productId", JsonPrimitive(it)) }
            code?.let { put("code", JsonPrimitive(it)) }
            // The server bounds `message` at 1024; trimming here keeps a long
            // localized store error from turning the whole report into a 400.
            message?.let { put("message", JsonPrimitive(it.take(1024))) }
        }
        try {
            request("POST", listOf("v1", "paywalls", "events"), body)
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("logPaywallEvent", err.message.orEmpty())
        }
        // REV-272: backing out of the store sheet is the `transaction_abandon`
        // moment. Reuses the paywall.purchase_abandoned just reported.
        if (event == RevnixPaywallEvent.PurchaseAbandoned) {
            fireImplicitFromPaywall(
                RevnixImplicitPlacement.TRANSACTION_ABANDON,
                viewId,
                paywallId,
            )
        }
    }

    /**
     * Report impression-level ad revenue from your mediation SDK's paid-event
     * callback (AdMob `OnPaidEventListener`, AppLovin MAX `onAdRevenuePaid`).
     * Fire-and-forget like the other beacons: never throws.
     */
    public suspend fun logAdRevenue(
        revenue: Double,
        currency: String,
        network: String? = null,
        mediation: String? = null,
        adUnit: String? = null,
        placement: String? = null,
        format: String? = null,
        eventId: String? = null,
    ) {
        if (!revenue.isFinite() || revenue <= 0) {
            diagnostic("logAdRevenue", "revenue must be a finite value > 0")
            return
        }
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(customerId()))
            put("revenue", JsonPrimitive(revenue))
            put("currency", JsonPrimitive(currency.take(100)))
            put("sdkVersion", JsonPrimitive(SDK_VERSION))
            network?.let { put("network", JsonPrimitive(it.take(100))) }
            mediation?.let { put("mediation", JsonPrimitive(it.take(100))) }
            adUnit?.let { put("adUnit", JsonPrimitive(it.take(100))) }
            placement?.let { put("placement", JsonPrimitive(it.take(100))) }
            format?.let { put("format", JsonPrimitive(it.take(100))) }
            eventId?.let { put("eventId", JsonPrimitive(it.take(100))) }
        }
        try {
            request("POST", listOf("v1", "ad-revenue"), body)
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("logAdRevenue", err.message.orEmpty())
        }
    }

    /**
     * PT11: forward an MMP's attribution callback (Adjust, AppsFlyer, …) so
     * Revnix credits revenue to the right network/campaign.
     * Fire-and-forget like the other beacons: never throws.
     */
    public suspend fun setAttribution(
        provider: String,
        network: String,
        campaign: String? = null,
        adGroup: String? = null,
        creative: String? = null,
    ) {
        val payload = listOf(provider, network, campaign.orEmpty(), adGroup.orEmpty(), creative.orEmpty())
            .joinToString("\u0001")
        if (config.storage.get(KEY_LAST_ATTRIBUTION) == payload) return
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(customerId()))
            put("provider", JsonPrimitive(provider.take(100)))
            put("network", JsonPrimitive(network.take(100)))
            put("sdkVersion", JsonPrimitive(SDK_VERSION))
            campaign?.let { put("campaign", JsonPrimitive(it.take(100))) }
            adGroup?.let { put("adGroup", JsonPrimitive(it.take(100))) }
            creative?.let { put("creative", JsonPrimitive(it.take(100))) }
        }
        try {
            request("POST", listOf("v1", "attribution"), body)
            config.storage.set(KEY_LAST_ATTRIBUTION, payload)
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("setAttribution", err.message.orEmpty())
        }
    }

    /**
     * Set attributes on the current customer (REV-033 v2). Attributes are what
     * A/B-test audiences target — set `country`, `app_version`, `locale`, or
     * any custom key you want to segment on. A null value deletes the key.
     *
     * Throws, unlike the fire-and-forget beacons: the next placement resolve
     * may depend on these, so a silent failure would look like broken
     * targeting. `email` and `username` are reserved (secret key only), and an
     * attribute your backend already set cannot be changed from a device.
     */
    // MARK: - Implicit placements (REV-272)

    /**
     * Begin watching for the six implicit moments. Called automatically from
     * `init` when implicit placements are on.
     *
     * A cold start is always both a launch AND a session — an operator who
     * configured only `session_start` still wants the first one — and it is an
     * install too when this launch minted the customer id. The three run in
     * order, most specific first, and only the first that resolves to a paywall
     * is handed to the host. All are still REPORTED — a launch is a launch
     * whether or not a paywall showed — but an app that configured all three
     * must not have three paywalls pushed onto its first frame.
     */
    private suspend fun startImplicitPlacements() {
        val sessionExtra = consumePreviousSessionMs()
        // Subscribed BEFORE the batch, which can take a full network timeout
        // when offline: a customer who backgrounds the app during that window
        // and comes back an hour later is a session, and missing the
        // background transition would lose it.
        config.lifecycle?.let { lifecycle ->
            lifecycleCancel = runCatching {
                lifecycle.onStateChange { state -> onAppState(state) }
            }.getOrElse {
                // A broken adapter costs session detection, not the client.
                diagnostic("lifecycleSubscribe", it.message.orEmpty())
                null
            }
        }

        // `customerId()` is what sets mintedThisLaunch, so it runs first.
        customerId()
        val moments = buildList {
            if (mintedThisLaunch) add(RevnixImplicitPlacement.APP_INSTALL)
            add(RevnixImplicitPlacement.APP_LAUNCH)
            add(RevnixImplicitPlacement.SESSION_START)
        }
        val batch = scope.async {
            var presented = false
            for (placement in moments) {
                val extra = if (placement == RevnixImplicitPlacement.SESSION_START) sessionExtra else null
                val shown = fireImplicit(placement, extra, present = !presented)
                presented = presented || shown
            }
            presented
        }
        launchBatch = batch
        batch.await()
        launchBatch = null
    }

    private fun onAppState(state: RevnixAppState) {
        when (state) {
            RevnixAppState.BACKGROUND -> {
                // First report wins: a platform that repeats "background" (a
                // paused Activity re-reporting) must not keep resetting the
                // clock forward.
                if (lastBackgroundAt == null) {
                    val now = config.now()
                    lastBackgroundAt = now
                    config.storage.set(KEY_LAST_BACKGROUND_AT, now.toString())
                }
            }
            RevnixAppState.FOREGROUND -> {
                // A foreground with no background before it is the launch
                // itself, which the batch already counted.
                val since = lastBackgroundAt ?: return
                lastBackgroundAt = null
                // An app switch is not a session.
                if (config.now() - since >= config.sessionTimeoutMs) {
                    val extra = consumePreviousSessionMs()
                    scope.launch {
                        // A new session is also when the memoised config is
                        // re-asked: the server promises an operator's change
                        // shows up within ~30 s, and a process backgrounded for
                        // days would otherwise keep firing a moment the operator
                        // turned off — or never fire one they turned on — until
                        // the next cold start.
                        implicitMutex.withLock { implicitConfigJob = null }
                        fireImplicit(RevnixImplicitPlacement.SESSION_START, extra)
                    }
                }
            }
        }
    }

    /**
     * Hand the SDK the URL that opened your app, from wherever you already
     * receive it (`Intent.getData()` in `onCreate` / `onNewIntent`).
     *
     * This is the one implicit moment the SDK cannot see for itself — the URL
     * goes to your Activity, and an SDK intercepting it would be fighting your
     * navigation. An ordinary link is always reported to the server so its
     * `link.*` attribution facts land on the customer; a paywall presents
     * only if implicit placements are on AND `deeplink_open` is configured on
     * the dashboard. Delivered on the first frame, while the cold-start batch
     * is still deciding what to show, it waits for the batch and presents
     * only if the batch showed nothing — the moment is reported either way.
     *
     * A dashboard QR/link preview (`<scheme>://revnix-preview?revnix_preview=<token>`,
     * scanned or tapped) is recognised here too and is always presented —
     * `deeplink_open` never fires for it, and it never reports the ledger
     * event `deeplink_open` does. `onImplicitPaywall`'s trigger carries
     * `RevnixImplicitPlacement.DEEPLINK_OPEN` (a deep link is what opened the
     * app); `resolution.placementKey` is [REVNIX_PREVIEW_PLACEMENT_KEY] and
     * `resolution.preview` is true, which is how a host tells a preview apart
     * from a real deep-link paywall.
     */
    public suspend fun handleDeepLink(url: String) {
        val previewToken = PREVIEW_TOKEN_REGEX.find(url)?.groupValues?.get(1)
        if (previewToken != null) {
            presentPreview(previewToken)
            return
        }
        recordLastDeepLink(url)
        val present = launchBatch?.let { !it.await() } ?: true
        fireImplicit(
            RevnixImplicitPlacement.DEEPLINK_OPEN,
            buildJsonObject { put("url", JsonPrimitive(url.take(1024))) },
            present = present,
        )
    }

    /** Last deep link [handleDeepLink] or a delivered deferred link recorded; null when none, or the stored value is malformed. Never throws. */
    public fun getLastDeepLink(): LastDeepLink? {
        return runCatching {
            config.storage.get(KEY_LAST_DEEP_LINK)?.let { json.decodeFromString(LastDeepLink.serializer(), it) }
        }.getOrNull()
    }

    /**
     * AT11: the install-attribution verdict for this customer — null when none
     * has been recorded yet (a normal cold-start race, the server's
     * `installMatch: "unknown"`) or when the read fails.
     *
     * Fetches fresh on every call rather than caching in memory: the point is
     * to answer with whatever the server currently believes. A CHANGED answer
     * also reaches [RevnixConfig.onAttribution], so a host that only wants
     * updates need not call this at all. Never throws.
     */
    public suspend fun getAttribution(): RevnixAttribution? {
        return try {
            val raw = request("GET", listOf("v1", "customers", customerId(), "attribution"))
            val match = runCatching {
                json.parseToJsonElement(raw).jsonObject["installMatch"]?.jsonPrimitive?.contentOrNull
            }.getOrNull()
            if (match == "unknown") return null
            val attribution = decode(RevnixAttribution.serializer(), raw)
            deliverAttribution(attribution)
            attribution
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            bgFailures += 1
            diagnostic("getAttribution", err.message.orEmpty())
            null
        }
    }

    private fun refreshAttribution() {
        if (config.onAttribution == null) return
        scope.launch { getAttribution() }
    }

    private fun deliverAttribution(attribution: RevnixAttribution) {
        val serialized = json.encodeToString(RevnixAttribution.serializer(), attribution)
        val cached = runCatching { config.storage.get(KEY_ATTRIBUTION) }
            .onFailure { diagnostic("onAttribution", it.message.orEmpty()) }
            .getOrNull()
        if (cached == serialized) return
        runCatching { config.storage.set(KEY_ATTRIBUTION, serialized) }
            .onFailure { diagnostic("onAttribution", it.message.orEmpty()) }
        val handler = config.onAttribution ?: return
        runCatching { handler(attribution) }
            .onFailure { diagnostic("onAttribution", it.message.orEmpty()) }
    }

    private fun recordLastDeepLink(url: String) {
        runCatching {
            config.storage.set(
                KEY_LAST_DEEP_LINK,
                json.encodeToString(LastDeepLink.serializer(), LastDeepLink(url, config.now())),
            )
        }
    }

    /**
     * Unwrap a link an email service provider (Mailchimp, SendGrid, …)
     * rewrote through its own click-tracking domain, e.g.
     * `https://click.mailchimp.com/track/abc` back into
     * `com.voigu.app://promo?utm_source=email&utm_campaign=summer50`. Route on
     * the result and hand it to [handleDeepLink]; the result may still be an
     * http(s) URL when the chain could not be unwrapped, so check its scheme
     * before routing. On any failure, or an input the server would reject
     * (blank, over 1024 characters, or not http/https), the input is returned
     * unchanged with no request sent.
     */
    public suspend fun resolveDeepLink(url: String): String {
        val isHttp = url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)
        if (url.length > 1024 || !isHttp) return url
        return try {
            val raw = request("GET", listOf("v1", "links", "resolve"), query = mapOf("url" to url))
            val resolved = json.parseToJsonElement(raw).jsonObject["url"]?.jsonPrimitive?.contentOrNull
            if (resolved.isNullOrBlank()) url else resolved
        } catch (err: Throwable) {
            if (err is CancellationException) throw err
            bgFailures += 1
            diagnostic("resolveDeepLink", err.message.orEmpty())
            url
        }
    }

    private suspend fun presentPreview(token: String) {
        launchBatch?.await()
        try {
            val raw = request("GET", listOf("v1", "paywalls", "preview", token))
            val resolution = decodePreviewResolution(raw)
            config.onImplicitPaywall?.invoke(
                RevnixImplicitTrigger(
                    placement = RevnixImplicitPlacement.DEEPLINK_OPEN,
                    resolution = resolution,
                ),
            )
        } catch (err: Throwable) {
            bgFailures += 1
            diagnostic("preview", err.message.orEmpty())
        }
    }

    private fun decodePreviewResolution(raw: String): PlacementResolution {
        val obj = json.parseToJsonElement(raw).jsonObject
        val patched = JsonObject(
            obj + mapOf(
                "status" to JsonPrimitive("ok"),
                "revision" to (obj["revision"]?.takeUnless { it is JsonNull } ?: JsonPrimitive(0)),
                "offering" to (
                    obj["offering"]?.takeUnless { it is JsonNull } ?: buildJsonObject {
                        put("offeringId", JsonPrimitive(""))
                        put("displayName", JsonPrimitive(""))
                    }
                ),
            )
        )
        return json.decodeFromJsonElement(PlacementResolution.serializer(), patched).copy(preview = true)
    }

    /** Which of the six this app has configured. See [implicitConfigJob]. */
    private suspend fun implicitConfig(): Set<String> {
        val job = implicitMutex.withLock {
            implicitConfigJob ?: run {
                // Inside the hold after a failure: answer "none" without a
                // request.
                if (config.now() < implicitConfigRetryAt) return emptySet()
                scope.async {
                    // The id does not change the answer — this route is
                    // customer-independent — it only picks the server's
                    // rate-limit bucket, so one busy app cannot 429 its own
                    // fleet off the feature.
                    val raw = request(
                        "GET",
                        listOf("v1", "config"),
                        query = mapOf("customer" to customerId()),
                    )
                    val obj = json.parseToJsonElement(raw).jsonObject
                    obj["implicitPlacements"]?.let { element ->
                        decode(stringListSerializer, element.toString()).toSet()
                    } ?: emptySet()
                }.also { implicitConfigJob = it }
            }
        }
        return try {
            job.await()
        } catch (err: Throwable) {
            implicitMutex.withLock { if (implicitConfigJob === job) implicitConfigJob = null }
            implicitConfigRetryAt = config.now() + IMPLICIT_CONFIG_RETRY_HOLD_MS
            bgFailures += 1
            diagnostic("implicitConfig", err.message.orEmpty())
            emptySet()
        }
    }

    private fun consumePreviousSessionMs(): JsonObject? {
        val started = config.storage.get(KEY_SESSION_STARTED_AT)?.toLongOrNull()
        val lastBg = config.storage.get(KEY_LAST_BACKGROUND_AT)?.toLongOrNull()
        val extra = if (started != null && lastBg != null && lastBg >= started) {
            buildJsonObject { put("previousSessionMs", JsonPrimitive(lastBg - started)) }
        } else {
            null
        }
        config.storage.set(KEY_SESSION_STARTED_AT, config.now().toString())
        config.storage.remove(KEY_LAST_BACKGROUND_AT)
        return extra
    }

    /**
     * Report one implicit moment and present whatever it resolves to. Returns
     * true when a paywall was handed to the host. `present` false still reports
     * the moment (its ledger event is a fact either way) but hands nothing over
     * — how the launch batch keeps a cold start to ONE paywall. Never throws:
     * this runs on a launch and on every return to the foreground.
     */
    private suspend fun fireImplicit(
        placement: RevnixImplicitPlacement,
        extra: JsonObject? = null,
        present: Boolean = true,
    ): Boolean {
        val resolve = implicitEnabled && placement.key in implicitConfig()
        if (!resolve && placement != RevnixImplicitPlacement.DEEPLINK_OPEN) return false

        val body = buildJsonObject {
            put("customerId", JsonPrimitive(customerId()))
            put("placement", JsonPrimitive(placement.key))
            // One id per occurrence: retries of the same launch are absorbed, a
            // genuine second launch counts separately. Required server-side for
            // the three moments that append an event.
            put("occurrenceId", JsonPrimitive(UUID.randomUUID().toString().lowercase()))
            put("occurredAt", JsonPrimitive(config.now()))
            put("sdkVersion", JsonPrimitive(SDK_VERSION))
            if (!resolve) put("resolve", JsonPrimitive(false))
            extra?.forEach { (k, v) -> put(k, v) }
        }
        val headers = deviceHeader?.let { mapOf("X-Revnix-Device" to it) }.orEmpty()
        return try {
            val raw = request(
                "POST",
                listOf("v1", "placements", "triggered"),
                body,
                headers = headers,
            )
            // The route's own body shape, read before the strict decode: an
            // unconfigured moment answers 200 with `paywall: null` and no
            // `status`/`revision` at all — a normal state, not a decode
            // failure to count against the server.
            val obj = json.parseToJsonElement(raw).jsonObject
            val resolved = obj["status"]?.jsonPrimitive?.contentOrNull == "ok" &&
                obj["paywall"] != null && obj["paywall"] !is JsonNull
            if (!resolved || !present || !resolve) return false
            val resolution = decode(PlacementResolution.serializer(), raw)
            config.onImplicitPaywall?.invoke(
                RevnixImplicitTrigger(placement = placement, resolution = resolution),
            )
            true
        } catch (err: Throwable) {
            bgFailures += 1
            diagnostic("implicit:${placement.key}", err.message.orEmpty())
            false
        }
    }

    /**
     * The two moments that happen ON a paywall, with the loop guard applied.
     * `fromPaywallId` travels so the server can refuse to hand back the very
     * paywall being dismissed.
     */
    private suspend fun fireImplicitFromPaywall(
        placement: RevnixImplicitPlacement,
        viewId: String,
        paywallId: String?,
    ) {
        if (!implicitEnabled) return
        // One hop, never a chain: this display was itself implicit.
        if (viewId in implicitViewIds) return
        fireImplicit(
            placement,
            buildJsonObject {
                put("fromViewId", JsonPrimitive(viewId))
                paywallId?.let { put("fromPaywallId", JsonPrimitive(it)) }
            },
        )
    }


    public suspend fun setAttributes(attributes: Map<String, Any?>) {
        val body = buildJsonObject {
            put(
                "attributes",
                buildJsonObject {
                    attributes.forEach { (key, value) ->
                        put(
                            key,
                            when (value) {
                                null -> JsonNull
                                is Number -> JsonPrimitive(value)
                                is String -> JsonPrimitive(value)
                                else -> throw IllegalArgumentException(
                                    "Attribute \"$key\" must be a String, Number, or null")
                            },
                        )
                    }
                },
            )
        }
        request("POST", listOf("v1", "customers", customerId(), "attributes"), body)
    }

    // MARK: - Transport

    private suspend fun request(
        method: String,
        segments: List<String>,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
    ): String = withContext(Dispatchers.IO) {
        val url = config.baseUrl.toHttpUrl().newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-Revnix-SDK", "revnix-kotlin/$SDK_VERSION")
        headers.forEach { (name, value) -> builder.header(name, value) }

        if (bgFailures > 0) {
            // Server-visible client pain with zero app wiring.
            builder.header("X-Revnix-Bg-Failures", bgFailures.toString())
            bgFailures = 0
        }

        if (body != null) {
            builder.method(
                method,
                json.encodeToString(JsonObject.serializer(), body)
                    .toRequestBody("application/json".toMediaType()),
            )
        } else {
            builder.method(method, null)
        }

        // Reading the body can fail too (connection dropped mid-response), so
        // the whole exchange is mapped — otherwise a raw IOException would
        // escape untyped and the offline cache would never engage.
        try {
            http.newCall(builder.build()).execute().use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val message = runCatching {
                        json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
                    }.getOrNull().orEmpty()
                    throw RevnixError.fromHttp(it.code, message, it.header("Retry-After"))
                }
                text
            }
        } catch (err: InterruptedIOException) {
            throw RevnixError.Timeout(err)
        } catch (err: IOException) {
            throw RevnixError.Network(err.message ?: "unreachable", err)
        }
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, raw: String): T = try {
        json.decodeFromString(deserializer, raw)
    } catch (_: Exception) {
        // A 200 that is not our JSON = captive portal / interception —
        // retryable, so callers fall back to cache instead of unlocking
        // nothing forever.
        throw RevnixError.BadResponse()
    }

    /** ±20% jitter around a delay. */
    private fun jittered(ms: Long): Long = (ms * (0.8 + Random.nextDouble() * 0.4)).toLong()

    // MARK: - Cache plumbing

    @Serializable
    private data class CacheEntry(val snapshot: CustomerEntitlements, val fetchedAt: Long)

    @Serializable
    private data class QueuedPurchase(
        val key: String,
        val source: String,
        val token: String,
        val productId: String,
        val transactionId: String,
        val occurredAt: Long? = null,
        val expiresAt: Long? = null,
        val signedTransactionInfo: String? = null,
        val rawPayload: JsonElement? = null,
    ) {
        fun toInput(): RegisterPurchaseInput = RegisterPurchaseInput(
            source = RevnixStore.fromWire(source),
            token = token,
            productId = productId,
            transactionId = transactionId,
            occurredAt = occurredAt,
            expiresAt = expiresAt,
            signedTransactionInfo = signedTransactionInfo,
            rawPayload = rawPayload,
        )
    }

    private val stringListSerializer = ListSerializer(String.serializer())
    private val queueSerializer = ListSerializer(QueuedPurchase.serializer())

    private fun cacheKey(cid: String) = "revnix.ent.$cid"
    private fun placementKey(key: String) = "revnix.placement.$key"
    private fun installReportedKey(cid: String) = "revnix.installReported.$cid"

    /**
     * Persist the high-water wall clock; report whether the clock has been
     * rolled back past tolerance (defeats "set the clock back to stay
     * subscribed offline").
     */
    private fun updateWallClock(nowMs: Long): Boolean {
        val stored = config.storage.get(KEY_WALL_CLOCK)?.toLongOrNull() ?: 0L
        if (nowMs > stored) config.storage.set(KEY_WALL_CLOCK, nowMs.toString())
        return nowMs + ROLLBACK_TOLERANCE_MS < stored
    }

    private fun cacheEntry(cid: String): CacheEntry? {
        val raw = config.storage.get(cacheKey(cid)) ?: return null
        return runCatching { json.decodeFromString(CacheEntry.serializer(), raw) }.getOrNull()
    }

    private fun storeCacheEntry(entry: CacheEntry, cid: String) {
        config.storage.set(cacheKey(cid), json.encodeToString(CacheEntry.serializer(), entry))
        // LRU over recent customers so a shared device can't grow unbounded.
        val index = readIndex().toMutableList()
        index.remove(cid)
        index.add(0, cid)
        while (index.size > CACHE_CUSTOMERS) {
            config.storage.remove(cacheKey(index.removeAt(index.size - 1)))
        }
        config.storage.set(
            KEY_CACHE_INDEX,
            json.encodeToString(stringListSerializer, index),
        )
    }

    private fun readIndex(): List<String> {
        val raw = config.storage.get(KEY_CACHE_INDEX) ?: return emptyList()
        return runCatching {
            json.decodeFromString(stringListSerializer, raw)
        }.getOrElse { emptyList() }
    }

    private fun queueKey(input: RegisterPurchaseInput) =
        "${input.source.wire}:${input.token}:${input.transactionId}"

    private fun queuedItems(): List<QueuedPurchase> {
        val raw = config.storage.get(KEY_QUEUE) ?: return emptyList()
        return runCatching {
            json.decodeFromString(queueSerializer, raw)
        }.getOrElse { emptyList() }
    }

    private fun persistQueue(items: List<QueuedPurchase>) {
        config.storage.set(KEY_QUEUE, json.encodeToString(queueSerializer, items))
    }

    private fun enqueue(input: RegisterPurchaseInput) {
        val items = queuedItems()
        val key = queueKey(input)
        if (items.any { it.key == key }) return
        persistQueue(
            items + QueuedPurchase(
                key = key,
                source = input.source.wire,
                token = input.token,
                productId = input.productId,
                transactionId = input.transactionId,
                occurredAt = input.occurredAt,
                expiresAt = input.expiresAt,
                signedTransactionInfo = input.signedTransactionInfo,
                rawPayload = input.rawPayload,
            )
        )
    }

    private fun removeQueued(key: String) {
        persistQueue(queuedItems().filterNot { it.key == key })
    }

    /**
     * A block paywall reporting a paint string it could not read. Routed to the
     * same sink as every other swallowed failure, so a host that already wired
     * `onDiagnostic` needs no new wiring to see render fallbacks.
     */
    public fun reportRenderDiagnostic(message: String) {
        diagnostic("paywall.render", message)
    }

    private fun diagnostic(op: String, message: String) {
        config.onDiagnostic?.invoke(RevnixDiagnostic(op, message))
    }
}
