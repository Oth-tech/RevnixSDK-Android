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

@Serializable
public data class PlacementResolution(
    val status: String,
    val placementKey: String,
    /** Published catalog revision this resolution came from. */
    val revision: Long,
    val offering: PlacementOffering,
    /** Remote paywall render contract (see docs) — app-rendered in v1. */
    val paywall: JsonElement? = null,
)

/** Swallowed background failure (queue drains, telemetry beacons). */
public data class RevnixDiagnostic(val op: String, val message: String)
