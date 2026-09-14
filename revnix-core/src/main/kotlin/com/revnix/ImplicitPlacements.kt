package com.revnix

/**
 * REV-272: implicit placements — the six moments the SDK reports on its own.
 *
 * A placement is normally a location the HOST resolves by name, so every new
 * place a paywall could appear costs a code change and a Play release. Six
 * moments are the same in every app and visible to the SDK without the host
 * saying anything, so an operator can attach a paywall to them from the
 * dashboard alone.
 *
 * Mirrors `convex/lib/implicitPlacements.ts` in revnix-app and `src/implicit.ts`
 * in revnix-react — the backend owns the key spellings, and a mismatch here is
 * silent (the server 400s a key it does not know), so the list is asserted
 * against the documented contract in the tests.
 */
public enum class RevnixImplicitPlacement(public val key: String) {
    APP_INSTALL("app_install"),
    APP_LAUNCH("app_launch"),
    SESSION_START("session_start"),

    /**
     * Lowercase, not Superwall's `deepLink_open`: a placement key must match
     * `^[a-z0-9][a-z0-9._-]{0,63}$` server-side.
     */
    DEEPLINK_OPEN("deeplink_open"),
    PAYWALL_DECLINE("paywall_decline"),
    TRANSACTION_ABANDON("transaction_abandon"),
    ;

    public companion object {
        public fun fromKey(key: String): RevnixImplicitPlacement? =
            entries.firstOrNull { it.key == key }
    }
}

/**
 * The `placementKey` a dashboard QR/link preview resolution carries
 * (`<scheme>://revnix-preview?revnix_preview=<token>`). Mirrors the server's
 * spelling, not one of the six above — a preview is never in
 * [RevnixImplicitPlacement] and never sent to `/v1/placements/triggered`.
 */
public const val REVNIX_PREVIEW_PLACEMENT_KEY: String = "revnix_preview"

/**
 * What the host is handed when a moment resolved to a paywall. Only ever
 * delivered WITH a paywall — a moment the server answered with none (nothing
 * attached, or the same paywall the customer is leaving) is reported and then
 * dropped, since there is nothing to present.
 */
public data class RevnixImplicitTrigger(
    /** Which of the six fired. */
    val placement: RevnixImplicitPlacement,
    /** The resolution, exactly as [RevnixClient.resolvePlacement] would return. */
    val resolution: PlacementResolution,
)

/**
 * Where the app is. Both transitions matter: a session is defined by how long
 * the app was in the BACKGROUND, which cannot be known from foreground events
 * alone — "time since the last return" would mint a session after 35 minutes of
 * continuous use plus a three-second app switch.
 */
public enum class RevnixAppState { FOREGROUND, BACKGROUND }

/**
 * How the SDK learns the app's foreground/background transitions, which is
 * what `session_start` is built on.
 *
 * `revnix-core` is plain Kotlin with no Android dependency, so the platform
 * implementation lives in `revnix-android` (`AndroidLifecycle`), built on
 * `Application.ActivityLifecycleCallbacks` — deliberately NOT
 * `androidx.lifecycle.ProcessLifecycleOwner`, which would drag a new dependency
 * into every host for one callback.
 */
public fun interface RevnixLifecycle {
    /**
     * Subscribe to app state transitions. Returns a cancel lambda; the client
     * calls it from [RevnixClient.close]. Repeated reports of the same state
     * are harmless.
     */
    public fun onStateChange(handler: (RevnixAppState) -> Unit): () -> Unit
}

/** Foreground detection off. Launch-time moments still fire. */
public val RevnixLifecycleDisabled: RevnixLifecycle = RevnixLifecycle { {} }

/**
 * How long the app must have been backgrounded for the return to count as a new
 * session rather than an app switch. Matches Superwall's own definition so a
 * team moving over gets the same numbers.
 */
public const val REVNIX_DEFAULT_SESSION_TIMEOUT_MS: Long = 30L * 60 * 1000
