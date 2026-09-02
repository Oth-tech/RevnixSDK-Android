package com.revnix.kmp

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull

/**
 * Response models are 1:1 with `revnix-app/public/openapi.yaml` schemas, and
 * identical to the JVM `revnix-core` shapes — the two must not drift.
 */

public enum class RevnixStore {
    APPLE,
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
    /** Recorded without store proof — live but time-boxed until confirmed. */
    val provisional: Boolean? = null,
)

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
 * store so the display never disagrees with the charge.
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

/**
 * A/B experiment assignment (REV-219). The served `offering`/`paywall` are
 * already the assigned variant's — this is attribution metadata, not something
 * the app needs to branch on.
 */
@Serializable
public data class PlacementExperiment(
    val key: String,
    val variantId: String,
)

@Serializable(with = PlacementResolutionSerializer::class)
public data class PlacementResolution(
    val status: String,
    val placementKey: String,
    /** Published catalog revision this resolution came from. */
    val revision: Long,
    val offering: PlacementOffering,
    /** Remote paywall attached to this placement — app-rendered in v1. */
    val paywall: PlacementPaywall? = null,
    /**
     * Sticky experiment assignment for this customer; null (or absent, on
     * older servers) when no running experiment covers the placement.
     */
    val experiment: PlacementExperiment? = null,
    /**
     * The `paywall` value exactly as the server sent it, alongside the typed
     * view above. A host that renders the document itself (the Flutter and
     * Capacitor bridges hand it to Dart / JS) forwards THIS, so a document
     * from a newer dashboard arrives untouched. Null when the server sent
     * null or omitted the key.
     */
    val paywallJson: JsonElement? = null,
    /** `experiment` as the server sent it; same purpose as [paywallJson]. */
    val experimentJson: JsonElement? = null,
)

/**
 * Reads `paywall` / `experiment` twice from one wire object: loose into
 * [PlacementResolution.paywallJson] / [PlacementResolution.experimentJson],
 * typed into the fields the native renderer uses. The typed halves are
 * tolerant for the same reason `PaywallConfig.blocks` is raw: a document from
 * a newer dashboard must never be able to fail the whole resolution — the
 * offering and the raw copy still arrive, the typed view is simply null.
 *
 * On the way out the raw value wins when present, so any re-encode (the
 * client caches the wire string itself and never takes this path) stays
 * lossless. JSON only: nothing else ever carries these models.
 */
internal object PlacementResolutionSerializer : KSerializer<PlacementResolution> {
    @Serializable
    private class Wire(
        val status: String,
        val placementKey: String,
        val revision: Long,
        val offering: PlacementOffering,
        val paywall: JsonElement? = null,
        val experiment: JsonElement? = null,
    )

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor

    override fun deserialize(decoder: Decoder): PlacementResolution {
        // The caller's Json (the client's, ignoreUnknownKeys) decodes the typed
        // halves, so a new field on a paywall config stays as tolerated as before.
        val json = (decoder as JsonDecoder).json
        val wire = decoder.decodeSerializableValue(Wire.serializer())
        val paywallJson = wire.paywall?.takeUnless { it is JsonNull }
        val experimentJson = wire.experiment?.takeUnless { it is JsonNull }
        return PlacementResolution(
            status = wire.status,
            placementKey = wire.placementKey,
            revision = wire.revision,
            offering = wire.offering,
            paywall = paywallJson?.let {
                runCatching { json.decodeFromJsonElement(PlacementPaywall.serializer(), it) }.getOrNull()
            },
            experiment = experimentJson?.let {
                runCatching { json.decodeFromJsonElement(PlacementExperiment.serializer(), it) }.getOrNull()
            },
            paywallJson = paywallJson,
            experimentJson = experimentJson,
        )
    }

    override fun serialize(encoder: Encoder, value: PlacementResolution) {
        val json = (encoder as JsonEncoder).json
        val wire = Wire(
            status = value.status,
            placementKey = value.placementKey,
            revision = value.revision,
            offering = value.offering,
            paywall = value.paywallJson
                ?: value.paywall?.let { json.encodeToJsonElement(PlacementPaywall.serializer(), it) },
            experiment = value.experimentJson
                ?: value.experiment?.let { json.encodeToJsonElement(PlacementExperiment.serializer(), it) },
        )
        encoder.encodeSerializableValue(Wire.serializer(), wire)
    }
}

/** Swallowed background failure (queue drains, telemetry beacons). */
public data class RevnixDiagnostic(val op: String, val message: String)
