package com.revnix.kmp

import io.ktor.client.HttpClient
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

public data class RevnixConfig(
    /**
     * Publishable key (`rvx_pk_live_…` / `rvx_pk_test_…`). Secret keys never
     * ship in a binary — identify / alias are server-proxied by design.
     */
    val apiKey: String,
    /** e.g. `https://your-deployment.convex.site` */
    val baseUrl: String,
    val storage: RevnixStorage = MemoryStorage(),
    /** Per-request timeout. Default 10 s. */
    val timeout: Duration = 10.seconds,
    /** Snapshots older than this serve as all-inactive. Default 14 days. */
    val offlineMaxCacheAge: Duration = 14.days,
    /** Soft TTL on entitlement reads. [Duration.ZERO] disables the shortcut. */
    val entitlementsTtl: Duration = 30.seconds,
    /** Read-your-writes poll schedule, each delay jittered ±20%. */
    val readYourWritesDelays: List<Duration> =
        listOf(250.milliseconds, 500.milliseconds, 1.seconds, 2.seconds),
    /** Swallowed background failures report here. */
    val onDiagnostic: ((RevnixDiagnostic) -> Unit)? = null,
    /** Injectable clock for tests. */
    val now: () -> Long = { currentTimeMs() },
    /**
     * Injectable Ktor client. KMP forks the transport to Ktor so it can reach
     * Darwin targets; the JVM/Android `revnix-core` keeps OkHttp and its
     * shipped public config unchanged.
     */
    val httpClient: HttpClient? = null,
    /**
     * REV-268: facts about the device, sent with every placement resolve so
     * targeting rules can be evaluated on the request that serves the paywall,
     * and stored on the customer as reserved `device.*` attributes. Defaults to
     * [detectDeviceFacts]; pass null to send nothing.
     */
    val device: DeviceFacts? = detectDeviceFacts(),
)
