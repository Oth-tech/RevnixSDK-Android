# Revnix Kotlin SDK

Native Android SDK for [Revnix](https://revnix.com) — Play Billing 8 purchase
glue plus the same resilience policy as `revnix-react` and `revnix-swift`.

- **Play Billing 8 native**, with auto-reconnect and the acknowledgement rule
  handled correctly (see below — this is the one most integrations get wrong).
- **Offline-correct by design.** A network blip keeps paying customers
  unlocked; a revoked key still locks them out.
- **One fetch per screen.** Soft TTL plus in-flight coalescing.

Requires Android minSdk 24 and JDK 17.

## Modules

| Module | What it is |
|---|---|
| `revnix-core` | Pure JVM client — entitlements, cache policy, purchases, retry queue. No Android dependency, so the resilience matrix runs as a plain JVM test task. |
| `revnix-android` | Play Billing 8 glue (`PlayBillingConnector`) and `AndroidStorage`. |
| `revnix-kmp` | Kotlin Multiplatform build of the same client: identical resilience policy (but no `setAttributes` yet — see Targeting below), Ktor transport instead of OkHttp. Targets `jvm`, `androidTarget`, `iosX64`, `iosArm64`, `iosSimulatorArm64`. Use it from a shared KMP module; use `revnix-core` + `revnix-android` from an Android-only app. |

## Install

> Not yet published to Maven Central — build from source for now (see
> [Distribution status](#distribution-status)).

```kotlin
dependencies {
    implementation("com.revnix:revnix-android:0.2.0")
}
```

## Quick start

```kotlin
val client = RevnixClient(
    RevnixConfig(
        apiKey = "rvx_pk_live_…",
        baseUrl = "https://your-deployment.convex.site",
        storage = AndroidStorage(context),
    )
)

// At app launch: connect to Play, replay owned purchases, drain the queue.
val billing = PlayBillingConnector.start(context, client)
client.registerInstall(platform = "android")

// Buy. The connector registers the purchase and acknowledges it.
billing.launchPurchase(activity, productDetails)

// Gate. Never throws; unknown/unreachable = locked.
if (client.isEntitled("pro")) { /* … */ }
```

Use the **publishable** key (`rvx_pk_…`) only. Secret keys must never ship in a
binary, so `identify`/`alias` are deliberately not SDK methods — proxy them from
your server (see the docs recipe).

## Paywalls and A/B tests

`resolvePlacement` returns the published offering plus a typed `PaywallConfig`:
nine layouts in `template` — `focus`, `feature-list`, `minimal`, `hero`,
`timeline`, `plans`, `feature-grid`, `offer`, `reveal` — a light/dark `mode`,
and optional `review` (stars, quote, author, count) and `offer` (anchor price,
urgency line) blocks. `template` is a `String` on purpose so a config published
with a future layout still deserializes rather than failing the resolve.

The resolve sends the customer id, so a running A/B test serves that
customer's variant. The `offering` and `paywall` you get back are *already*
the variant's — render them as-is. `experiment` is attribution metadata, null
when no running test covers the placement:

```kotlin
val resolution = client.resolvePlacement("paywall_main")
resolution.experiment?.let { experiment ->
    analytics.log("paywall_shown", mapOf(
        "experiment" to experiment.key,
        "variant" to experiment.variantId,
    ))
}
```

Assignment is sticky per customer and survives identity merges.

### Targeting: `setAttributes`

A test can be narrowed to an audience — conditions over customer attributes.
`setAttributes` supplies the facts those conditions read:

```kotlin
client.setAttributes(mapOf(
    "country" to "US",
    "app_version" to "4.2.0",
    "lifetime_orders" to 3,
    "stale_key" to null,   // null deletes the key
))
```

Values must be `String`, `Number`, or `null` — anything else throws
`IllegalArgumentException` before a request is made. This suspends until the
write completes rather than firing and forgetting, because the next
`resolvePlacement` may depend on it. Set an audience's attributes *before* the
first resolve on a covered placement; eligibility is checked at that resolve.
`email` and `username` are reserved (secret key, from your server), and an
attribute your backend already set cannot be changed from a device — both
reject the whole batch rather than applying part of it.

## Two Android-specific rules

**Acknowledgement happens after the claim is recorded.** The backend never
acknowledges; the SDK does. Google refunds any purchase not acknowledged within
3 days, so acknowledging *before* Revnix has the claim would trade a refund
window for a lost entitlement. `PlayBillingConnector` acknowledges once
`registerPurchase` has either succeeded or been durably queued. A *deliberate*
server refusal is not queued and not acknowledged — better that Google refunds
it than that the customer is stranded having paid.

**`transactionId` is the purchaseToken.** Google has no separate transaction
identifier; the token is the stable key the backend dedupes on.

`obfuscatedAccountId` is set to the Revnix customer id on the billing flow, so
server-to-server RTDN self-identifies.

Google device claims cannot carry store-side proof, so expect
`provisional = true` on store-connected tenants until the server corroborates
via RTDN / `subscriptionsv2.get`. It is surfaced on `RegisterPurchaseResult`.

## Resilience policy

A product contract, not an implementation detail. `revnix-react`'s
`resilience.test.ts` is the spec; every case is ported to
`revnix-core/src/test/kotlin/com/revnix/RevnixClientTest.kt` and must stay in
agreement with it and with the Swift port.

| Behavior | Rule |
|---|---|
| Entitlement reads | Network-first |
| Transient failure (offline, timeout, 429, 5xx, non-JSON 200) | Serve cache, `stale = true` |
| Deliberate rejection (401/403/404/409) | **Always throw** — a cache must never defeat a kill-switch |
| Cached entitlement past `expiresAt` | Grace 3 days, then inactive |
| Cache age ceiling | 14 days → all inactive |
| Clock rolled back > 5 min | All inactive |
| Repeat reads | 30 s soft TTL + in-flight coalescing |
| Failed purchase registration | Persistent queue keyed `source:token:transactionId`, retryable failures only |
| Retry / poll delays | ±20% jitter; `Retry-After` honored |
| Swallowed background failures | `onDiagnostic` callback; count rides `X-Revnix-Bg-Failures` |

`waitForEntitlements(seq)` bypasses the soft TTL — the point of that poll is a
fresh ledger cursor — and resolves with the last read rather than throwing if
the ledger never catches up.

## Tests

```sh
./gradlew :revnix-core:test     # 31 tests — the resilience matrix
./gradlew :revnix-kmp:allTests  # 30 tests — the same matrix, Ktor transport
```

`revnix-core` runs against OkHttp's `MockWebServer`; `revnix-kmp` runs the
ported matrix on every configured target.

The Play Billing glue needs a real Play connection and is exercised with test
purchases in a sandbox app, not by either suite.

## Not in v1

- Paywall UI rendering — `resolvePlacement` ships the config, your app renders it.
- `identify` / `alias` — server-proxied by design.
- Amazon and other stores.

## Distribution status

**Not yet published.** `com.revnix:revnix-android:0.2.0` is the intended
coordinate, but nothing is on Maven Central yet, so that dependency will not
resolve. Until it ships, apps integrate over the
[REST API](https://revnix.com/docs/android) — the same `/v1` contract this SDK
speaks, so migrating later does not change the backend integration.
