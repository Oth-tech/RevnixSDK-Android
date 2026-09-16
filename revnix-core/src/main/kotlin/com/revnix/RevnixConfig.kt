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
    /**
     * REV-268: facts about the device, sent with every placement resolve so
     * targeting rules can be evaluated on the request that serves the paywall,
     * and stored on the customer as reserved `device.*` attributes. Defaults to
     * [DeviceFacts.detect]; on Android prefer `AndroidDeviceFacts.detect(context)`
     * from `revnix-android`, which adds the app version and sandbox flag. Pass
     * null to send nothing.
     */
    val device: DeviceFacts? = DeviceFacts.detect(),
    /**
     * REV-272: called when one of the six implicit moments resolved to a
     * paywall — an app launch, a session start, a deep link, a dismissed
     * paywall, an abandoned checkout, or the install itself. Present it however
     * your app presents paywalls; the SDK deliberately does not present for
     * you, because it does not own your back stack and a paywall thrown over a
     * splash activity is worse than no paywall.
     *
     * Providing this handler is what TURNS IMPLICIT PLACEMENTS ON. Without it
     * the SDK makes no extra requests for five of the six — the exception is
     * [RevnixClient.handleDeepLink], which always reports the link it is
     * handed regardless of this handler or the dashboard config, so the
     * server's `link.*` attribution facts land on the customer. With this
     * handler set, the SDK also asks `GET /v1/config` once and then fires for
     * the moments this app has actually configured in the dashboard.
     *
     * ⚠️ Pass `trigger.resolution.placementKey` to [RevnixClient.logPaywallDisplay]
     * for the display you present. That is what tells the SDK this display came
     * FROM an implicit trigger, and it is the only thing that stops a
     * `paywall_decline` paywall from firing `paywall_decline` again when the
     * customer dismisses it — a loop with no way out but force-quitting. The
     * server refuses to serve back the very same paywall as a backstop, but it
     * cannot see a rule pointing at a DIFFERENT paywall that points back.
     */
    val onImplicitPaywall: ((RevnixImplicitTrigger) -> Unit)? = null,
    /**
     * REV-272: explicit off switch, even when [onImplicitPaywall] is set. Null
     * means "on when a handler is present". Does not stop
     * [RevnixClient.handleDeepLink] from reporting the link it is handed —
     * that report is attribution, not a paywall moment — but off means
     * [RevnixClient.handleDeepLink] never presents a deep-link paywall either.
     * A dashboard preview link is the exception: it still presents regardless
     * of this setting.
     */
    val implicitPlacements: Boolean? = null,
    /**
     * REV-272: how the SDK learns the app came to the foreground —
     * `session_start` is built on it. `revnix-core` has no Android dependency,
     * so pass `AndroidLifecycle(application)` from `revnix-android`. Null means
     * launch-time moments only.
     */
    val lifecycle: RevnixLifecycle? = null,
    /**
     * REV-272: how long the app must have been backgrounded for the return to
     * count as a new session rather than an app switch. Default 30 minutes.
     */
    val sessionTimeoutMs: Long = REVNIX_DEFAULT_SESSION_TIMEOUT_MS,
    /**
     * REV-299: called with the link the customer clicked before they had the
     * app, when `POST /v1/installs` matched the install to an ad click
     * (Android: exact, from the Play Install Referrer). The SDK calls this at
     * most once per install.
     * Route it yourself; you may also pass it to [RevnixClient.handleDeepLink]
     * for `deeplink_open` paywall rules. Called off the main thread, like
     * [onImplicitPaywall]: post to the main thread before navigating. Never
     * throws into your app: an exception from this handler is reported through
     * [onDiagnostic] and swallowed.
     */
    val onDeferredDeepLink: ((url: String, match: DeferredDeepLinkMatch) -> Unit)? = null,
) {
    /**
     * REV-272: the rule the client reads — [implicitPlacements] when set, else
     * whether a handler is present.
     */
    val implicitPlacementsEnabled: Boolean
        get() = implicitPlacements ?: (onImplicitPaywall != null)
}

/** How [RevnixConfig.onDeferredDeepLink] matched the install to a click. */
public enum class DeferredDeepLinkMatch { EXACT, PROBABILISTIC }
