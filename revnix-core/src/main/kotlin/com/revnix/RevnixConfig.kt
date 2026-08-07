package com.revnix

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import okhttp3.OkHttpClient

public data class RevnixConfig(
    /**
     * Publishable key (`rvx_pk_live_…` / `rvx_pk_test_…`). The key fixes app +
     * environment server-side. Secret keys never ship in a binary — identify /
     * alias are server-proxied by design.
     */
    val apiKey: String,
    /** e.g. `https://your-deployment.convex.site` */
    val baseUrl: String,
    val storage: RevnixStorage = MemoryStorage(),
    /** Per-request timeout. Default 10 s (matches revnix-react). */
    val timeout: Duration = 10.seconds,
    /** Snapshots older than this serve as all-inactive. Default 14 days. */
    val offlineMaxCacheAge: Duration = 14.days,
    /**
     * Soft TTL on entitlement reads: a snapshot this fresh answers without a
     * network round trip, so a screen full of gates costs one fetch. Set
     * [Duration.ZERO] to always fetch.
     */
    val entitlementsTtl: Duration = 30.seconds,
    /**
     * Read-your-writes poll schedule after a purchase. Each delay is jittered
     * ±20% so a promo push does not put a fleet's polls in lockstep against
     * our own rate limiter. Empty disables polling.
     */
    val readYourWritesDelays: List<Duration> =
        listOf(250.milliseconds, 500.milliseconds, 1.seconds, 2.seconds),
    /** Swallowed background failures report here (queue drains, telemetry). */
    val onDiagnostic: ((RevnixDiagnostic) -> Unit)? = null,
    /** Injectable clock for tests. */
    val now: () -> Long = { System.currentTimeMillis() },
    /** Injectable HTTP client for tests. */
    val httpClient: OkHttpClient? = null,
)
