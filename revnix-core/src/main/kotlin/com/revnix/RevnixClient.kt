package com.revnix

import java.io.IOException
import java.io.InterruptedIOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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

    private companion object {
        const val EXPIRY_GRACE_MS = 3L * 24 * 3600 * 1000
        const val ROLLBACK_TOLERANCE_MS = 5L * 60 * 1000
        const val CACHE_CUSTOMERS = 4
        const val SDK_VERSION = "0.2.0"

        const val KEY_CUSTOMER_ID = "revnix.customerId"
        const val KEY_WALL_CLOCK = "revnix.lastWallClock"
        const val KEY_QUEUE = "revnix.pendingPurchases"
        const val KEY_CACHE_INDEX = "revnix.entIndex"
    }

    // MARK: - Identity

    /** Anonymous id, minted and persisted on first call. */
    public fun customerId(): String {
        config.storage.get(KEY_CUSTOMER_ID)?.let { return it }
        val minted = generateAnonymousId()
        config.storage.set(KEY_CUSTOMER_ID, minted)
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

    /** Gate helper — never throws; unknown/unreachable = locked. */
    public suspend fun isEntitled(entitlementId: String): Boolean {
        val snapshot = try {
            entitlements()
        } catch (_: RevnixError) {
            cachedEntitlements()
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
        try {
            val raw = request("GET", listOf("v1", "placements", key, "offering"), query = query)
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
            request("POST", listOf("v1", "installs"), body)
            config.storage.set(installReportedKey(cid), "1")
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("registerInstall", err.message.orEmpty())
        }
    }

    /** Fire-and-forget impression beacon (feeds funnels + view conversions). */
    public suspend fun logPaywallShown(placementKey: String?, paywallId: String?) {
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(customerId()))
            put("viewId", JsonPrimitive(UUID.randomUUID().toString().lowercase()))
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
    ): String = withContext(Dispatchers.IO) {
        val url = config.baseUrl.toHttpUrl().newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()

        val builder = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-Revnix-SDK", "revnix-kotlin/$SDK_VERSION")

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

    private fun diagnostic(op: String, message: String) {
        config.onDiagnostic?.invoke(RevnixDiagnostic(op, message))
    }
}
