package com.revnix

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/** Response models are 1:1 with `revnix-app/public/openapi.yaml` schemas. */

public enum class RevnixStore {
    @SerialName("apple")
    APPLE,

    @SerialName("google")
    GOOGLE;

    public val wire: String
        get() = when (this) {
            APPLE -> "apple"
            GOOGLE -> "google"
        }

    public companion object {
        public fun fromWire(value: String): RevnixStore =
            entries.firstOrNull { it.wire == value } ?: APPLE
    }
}

// ——— GET /v1/customers/{id}/entitlements ———

@Serializable
public data class EntitlementSource(
    val kind: String,
    val key: String,
    val isActive: Boolean,
    val expiresAt: Long? = null,
)

@Serializable
public data class Entitlement(
    val entitlementId: String,
    val isActive: Boolean,
    val expiresAt: Long? = null,
    val sources: List<EntitlementSource> = emptyList(),
) {
    internal fun inactive(): Entitlement = copy(
        isActive = false,
        sources = sources.map { it.copy(isActive = false) },
    )
}

@Serializable
public data class CustomerEntitlements(
    val customerId: String,
    /** Ledger position this read reflects (read-your-writes, ADR 0004). */
    val cursor: Long,
    val entitlements: List<Entitlement> = emptyList(),
    /** Client-populated: true when served from the offline cache. */
    val stale: Boolean? = null,
    /** Client-populated: unix ms this snapshot was fetched. */
    val fetchedAt: Long? = null,
)

// ——— POST /v1/purchases ———

public data class RegisterPurchaseInput(
    val source: RevnixStore,
    /** Apple: originalTransactionId · Google: purchaseToken. */
    val token: String,
    val productId: String,
    /** Google: this is the purchaseToken too (the established rule). */
    val transactionId: String,
    val occurredAt: Long? = null,
    val expiresAt: Long? = null,
    /** StoreKit 2 JWS — the proof path; claims without it are provisional. */
    val signedTransactionInfo: String? = null,
    val rawPayload: JsonElement? = null,
)

@Serializable
public data class RegisterPurchaseResult(
    val eventId: String,
    /** Ledger position — poll entitlements until `cursor >= seq`. */
    val seq: Long,
    val duplicate: Boolean,
    /** The id THIS caller should use going forward (its canonical id). */
    val customerId: String,
    val transferred: Boolean = false,
    val ownedByOtherCustomer: Boolean? = null,
    val refused: String? = null,
    val restored: Boolean? = null,
    /**
     * Recorded without store proof — the entitlement is live but time-boxed
     * until the store confirms. Google device claims cannot carry proof, so
     * expect this on store-connected tenants until the server corroborates
     * via RTDN / subscriptionsv2.get.
     */
    val provisional: Boolean? = null,
)

// ——— GET /v1/placements/{key}/offering ———

@Serializable
public data class PlacementPackage(
    val packageId: String,
    val productId: String,
    val metadata: JsonElement? = null,
    val product: JsonElement? = null,
)

@Serializable
public data class PlacementOffering(
    val offeringId: String,
    val displayName: String,
    val metadata: JsonElement? = null,
    val packages: List<PlacementPackage> = emptyList(),
)

/**
 * Remote paywall design attached to a placement (REV-028). Render contract —
 * the app draws this with its own components; prices still come from the
 * store (StoreKit / Play Billing) so the display never disagrees with the
 * charge.
 */
@Serializable
public data class PaywallConfig(
    /**
     * Layout — the screen structure the paywall renders. Known values:
     * `"focus"`, `"feature-list"`, `"minimal"`, `"hero"`, `"timeline"`,
     * `"plans"`, `"feature-grid"`, `"offer"`, `"reveal"`. Kept as a plain
     * string so unknown future layouts decode instead of failing — render a
     * fallback layout for values you don't recognize.
     */
    val template: String,
    /** Color scheme, `"dark"` or `"light"`. Absent (legacy config) = dark. */
    val mode: String? = null,
    val headline: String,
    val subheadline: String? = null,
    val features: List<Feature> = emptyList(),
    val ctaLabel: String,
    /** packageId of the visually highlighted package. */
    val highlightPackageId: String? = null,
    /** Badge on the highlighted package, e.g. "SAVE 17%". */
    val badgeText: String? = null,
    /** Accent hex like "#6478ff"; fall back to the app theme when absent. */
    val accent: String? = null,
    /** Hero image URL rendered above the headline in place of the icon tile. */
    val heroImageUrl: String? = null,
    /** Social proof, dashboard-configured. Render the pieces that are set. */
    val review: Review? = null,
    /** Win-back/offer presentation on the highlighted package. */
    val offer: Offer? = null,
    /** Footer links, dashboard-configured. Absent (legacy) = show all three. */
    val footer: Footer? = null,
) {
    @Serializable
    public data class Feature(
        val icon: String? = null,
        val title: String,
        val description: String? = null,
    )

    @Serializable
    public data class Review(
        /** 0–5; rendered as a star row. */
        val rating: Double? = null,
        val quote: String? = null,
        val author: String? = null,
        /** e.g. "Join 2M+ users" — small line under the CTA. */
        val count: String? = null,
    )

    @Serializable
    public data class Offer(
        /** Anchor price struck through on the highlighted package. */
        val strikethroughPrice: String? = null,
        /** Urgency line above the CTA. */
        val urgencyText: String? = null,
    )

    @Serializable
    public data class Footer(
        val showRestore: Boolean = true,
        val showTerms: Boolean = true,
        val showPrivacy: Boolean = true,
        /** When set the SDK opens it directly; otherwise the host handles it. */
        val termsUrl: String? = null,
        val privacyUrl: String? = null,
    )
}

@Serializable
public data class PlacementPaywall(
    val paywallId: String,
    val name: String,
    val config: PaywallConfig,
)

@Serializable
public data class PlacementResolution(
    val status: String,
    val placementKey: String,
    /** Published catalog revision this resolution came from. */
    val revision: Long,
    val offering: PlacementOffering,
    /** Remote paywall attached to this placement — app-rendered in v1. */
    val paywall: PlacementPaywall? = null,
)

/** Swallowed background failure (queue drains, telemetry beacons). */
public data class RevnixDiagnostic(val op: String, val message: String)
