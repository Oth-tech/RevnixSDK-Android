package com.revnix.kmp

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin Multiplatform client — the same resilience policy as revnix-react,
 * revnix-swift, and the JVM revnix-core. `resilience.test.ts` is the spec; the
 * four ports must stay in agreement.
 *
 * The only intentional divergence from `revnix-core` is the transport: Ktor
 * instead of OkHttp, so this reaches Darwin targets. Policy is identical.
 */
public class RevnixClient(private val config: RevnixConfig) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    private val http: HttpClient = config.httpClient ?: HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = config.timeout.inWholeMilliseconds
        }
    }

    private val scope = CoroutineScope(SupervisorJob())
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

    public fun customerId(): String {
        config.storage.get(KEY_CUSTOMER_ID)?.let { return it }
        val minted = generateAnonymousId()
        config.storage.set(KEY_CUSTOMER_ID, minted)
        return minted
    }

    public fun logout(): String {
        val minted = generateAnonymousId()
        config.storage.set(KEY_CUSTOMER_ID, minted)
        return minted
    }

    public fun close() {
        scope.cancel()
        if (config.httpClient == null) http.close()
    }

    // MARK: - Entitlements

    public suspend fun entitlements(): CustomerEntitlements {
        val cid = customerId()
        val nowMs = config.now()
        val rolledBack = updateWallClock(nowMs)

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
     * Network-only read — no TTL shortcut, no cache fallback. The
     * read-your-writes poll needs a genuinely fresh cursor.
     */
    private suspend fun fetchFreshEntitlements(cid: String, nowMs: Long): CustomerEntitlements {
        val raw = request(HttpMethod.Get, listOf("v1", "customers", cid, "entitlements"))
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
     * Read-your-writes: poll until the cursor reaches [seq]. Resolves with the
     * LAST read if the schedule runs out — never spins forever, never throws a
     * timeout.
     */
    public suspend fun waitForEntitlements(seq: Long): CustomerEntitlements {
        val cid = customerId()
        var last = entitlements()
        for (delayFor in config.readYourWritesDelays) {
            if (last.stale != true && last.cursor >= seq) return last
            // ±20% jitter so a promo push doesn't put a fleet's polls in
            // lockstep against our own rate limiter.
            delay(jittered(delayFor.inWholeMilliseconds))
            try {
                // TTL-bypassing: the point of this poll is a FRESH cursor.
                last = fetchFreshEntitlements(cid, config.now())
            } catch (err: RevnixError) {
                val retryAfter = (err as? RevnixError.RateLimited)?.retryAfterMs
                if (retryAfter != null && retryAfter > 0) delay(retryAfter)
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
        return snapshot.copy(entitlements = entitlements, stale = true, fetchedAt = entry.fetchedAt)
    }

    // MARK: - Purchases

    public suspend fun registerPurchase(input: RegisterPurchaseInput): RegisterPurchaseResult {
        val cid = customerId()
        try {
            val result = postPurchase(input, cid)
            if (result.customerId != cid) {
                config.storage.set(KEY_CUSTOMER_ID, result.customerId)
            }
            removeQueued(queueKey(input))
            return result
        } catch (err: RevnixError) {
            if (err.isRetryable) enqueue(input)
            throw err
        }
    }

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
        val raw = request(HttpMethod.Post, listOf("v1", "purchases"), body)
        return decode(RegisterPurchaseResult.serializer(), raw)
    }

    // MARK: - Placements & telemetry

    public suspend fun resolvePlacement(key: String): PlacementResolution {
        // The customer id makes experiment assignment sticky server-side
        // (REV-219); older servers simply ignore the parameter.
        val query = mapOf("customer" to customerId())
        try {
            val raw = request(
                HttpMethod.Get,
                listOf("v1", "placements", key, "offering"),
                query = query,
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
            request(HttpMethod.Post, listOf("v1", "installs"), body)
            config.storage.set(installReportedKey(cid), "1")
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("registerInstall", err.message.orEmpty())
        }
    }

    public suspend fun logPaywallShown(placementKey: String?, paywallId: String?) {
        val body = buildJsonObject {
            put("customerId", JsonPrimitive(customerId()))
            put("viewId", JsonPrimitive(generateAnonymousId().removePrefix("rvx_anon_")))
            put("sdkVersion", JsonPrimitive(SDK_VERSION))
            placementKey?.let { put("placementKey", JsonPrimitive(it)) }
            paywallId?.let { put("paywallId", JsonPrimitive(it)) }
        }
        try {
            request(HttpMethod.Post, listOf("v1", "paywalls", "viewed"), body)
        } catch (err: RevnixError) {
            bgFailures += 1
            diagnostic("logPaywallShown", err.message.orEmpty())
        }
    }

    // MARK: - Transport (Ktor)

    private suspend fun request(
        method: HttpMethod,
        segments: List<String>,
        body: JsonObject? = null,
        query: Map<String, String> = emptyMap(),
    ): String {
        val url = buildString {
            append(config.baseUrl.trimEnd('/'))
            segments.forEach { append('/').append(encodeSegment(it)) }
            query.entries.forEachIndexed { i, (name, value) ->
                append(if (i == 0) '?' else '&')
                append(encodeSegment(name)).append('=').append(encodeSegment(value))
            }
        }
        val failuresToReport = bgFailures.takeIf { it > 0 }

        val response = try {
            http.request(url) {
                this.method = method
                header("Authorization", "Bearer ${config.apiKey}")
                header("X-Revnix-SDK", "revnix-kmp/$SDK_VERSION")
                failuresToReport?.let { header("X-Revnix-Bg-Failures", it.toString()) }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(JsonObject.serializer(), body))
                }
            }
        } catch (err: HttpRequestTimeoutException) {
            throw RevnixError.Timeout(err)
        } catch (err: RevnixError) {
            throw err
        } catch (err: Throwable) {
            throw RevnixError.Network(err.message ?: "unreachable", err)
        }

        // Only clear once the request actually left the device.
        if (failuresToReport != null) bgFailures -= failuresToReport

        // Reading the body can fail too (connection dropped mid-response), so
        // it is mapped as well — otherwise an untyped error would escape and
        // the offline cache would never engage.
        val text = try {
            response.bodyAsText()
        } catch (err: Throwable) {
            throw RevnixError.Network(err.message ?: "body read failed", err)
        }

        if (!response.status.isSuccess()) {
            val message = runCatching {
                json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull().orEmpty()
            throw RevnixError.fromHttp(
                response.status.value,
                message,
                response.headers["Retry-After"],
            )
        }
        return text
    }

    private fun <T> decode(deserializer: DeserializationStrategy<T>, raw: String): T = try {
        json.decodeFromString(deserializer, raw)
    } catch (_: Exception) {
        // A 200 that is not our JSON = captive portal / interception —
        // retryable, so callers fall back to cache.
        throw RevnixError.BadResponse()
    }

    private fun jittered(ms: Long): Long = (ms * (0.8 + Random.nextDouble() * 0.4)).toLong()

    private fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.encodeToByteArray()) {
            val c = byte.toInt().toChar()
            if (c.isLetterOrDigit() || c in "-_.~") append(c)
            else append('%').append(((byte.toInt() and 0xFF).toString(16).padStart(2, '0')).uppercase())
        }
    }

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

    /** Defeats "set the clock back to stay subscribed offline". */
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
        // LRU so a shared device can't grow unbounded.
        val index = readIndex().toMutableList()
        index.remove(cid)
        index.add(0, cid)
        while (index.size > CACHE_CUSTOMERS) {
            config.storage.remove(cacheKey(index.removeAt(index.size - 1)))
        }
        config.storage.set(KEY_CACHE_INDEX, json.encodeToString(stringListSerializer, index))
    }

    private fun readIndex(): List<String> {
        val raw = config.storage.get(KEY_CACHE_INDEX) ?: return emptyList()
        return runCatching { json.decodeFromString(stringListSerializer, raw) }
            .getOrElse { emptyList() }
    }

    private fun queueKey(input: RegisterPurchaseInput) =
        "${input.source.wire}:${input.token}:${input.transactionId}"

    private fun queuedItems(): List<QueuedPurchase> {
        val raw = config.storage.get(KEY_QUEUE) ?: return emptyList()
        return runCatching { json.decodeFromString(queueSerializer, raw) }
            .getOrElse { emptyList() }
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
